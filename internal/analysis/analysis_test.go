package analysis

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/alerts"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/lexicon"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/notify"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/rag"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/risk"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/testdb"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/trafficlight"
)

func servicio(t *testing.T) (*Service, *trafficlight.Service, *pgxpool.Pool) {
	t.Helper()

	pool := testdb.New(t)
	hub := notify.NewHub()
	lights := trafficlight.NewService(pool, hub, alerts.NewService(pool, hub))
	lex, err := lexicon.Default()
	if err != nil {
		t.Fatalf("léxico: %v", err)
	}
	// El RAG va sin embebedor: es el modo en el que atiende al diario en
	// producción, así que es el que hay que testear.
	ret, err := rag.New(lex, nil)
	if err != nil {
		t.Fatalf("corpus: %v", err)
	}
	return NewService(lex, lights, ret), lights, pool
}

func TestUnaEntradaConSenalesSubeElSemaforo(t *testing.T) {
	svc, lights, pool := servicio(t)
	ctx := t.Context()
	userID := testdb.NewUser(t, pool, "diario@undiamas.mx")

	svc.OnJournal(ctx, userID, "Llevo días con mucha ansiedad, estrés y no puedo dormir.")

	nivel, err := lights.Current(ctx, userID)
	if err != nil {
		t.Fatalf("Current: %v", err)
	}
	if nivel != risk.Yellow {
		t.Errorf("semáforo = %v, se esperaba AMARILLO", nivel)
	}

	// El motivo queda registrado con las categorías, nunca con el texto.
	logs, err := lights.List(ctx, userID, 5)
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(logs) != 1 {
		t.Fatalf("traffic_light_logs = %d, se esperaba 1", len(logs))
	}
	if logs[0].Reason == "" {
		t.Error("el registro quedó sin motivo")
	}
	for _, filtrado := range []string{"dormir", "ansiedad", "días"} {
		if strings.Contains(logs[0].Reason, filtrado) {
			t.Errorf("el motivo %q filtra el texto del diario", logs[0].Reason)
		}
	}
	if len(logs[0].SuggestedActions) == 0 {
		t.Error("no se sugirió ninguna acción")
	}
}

func TestUnDiaTranquiloNoTocaNada(t *testing.T) {
	svc, lights, pool := servicio(t)
	ctx := t.Context()
	userID := testdb.NewUser(t, pool, "tranquilo@undiamas.mx")

	svc.OnJournal(ctx, userID, "Hoy comí con mi hermana y salimos a caminar. Buen día.")

	logs, err := lights.List(ctx, userID, 5)
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(logs) != 0 {
		t.Errorf("un día verde no debería registrar nada, hubo %d entradas", len(logs))
	}
}

// La regla más importante del paquete: el análisis sube, nunca baja. Un texto
// tranquilo escrito durante una crisis no puede apagar un rojo.
func TestElAnalisisNoBajaElSemaforo(t *testing.T) {
	svc, lights, pool := servicio(t)
	ctx := t.Context()
	userID := testdb.NewUser(t, pool, "rojo@undiamas.mx")

	if _, err := lights.Record(ctx, userID, trafficlight.Evaluation{
		Status: risk.Red,
		Reason: "check-in rojo",
	}, "prueba"); err != nil {
		t.Fatalf("Record inicial: %v", err)
	}

	// Un texto que por sí solo daría amarillo.
	svc.OnJournal(ctx, userID, "Tengo algo de ansiedad pero fui a terapia y salí a correr.")

	nivel, err := lights.Current(ctx, userID)
	if err != nil {
		t.Fatalf("Current: %v", err)
	}
	if nivel != risk.Red {
		t.Errorf("el semáforo bajó a %v: el análisis solo puede subir", nivel)
	}
}

// Una recaída escrita en el diario dispara el protocolo de emergencia completo,
// igual que un check-in rojo.
func TestRecaidaEnElDiarioLevantaAlerta(t *testing.T) {
	svc, _, pool := servicio(t)
	ctx := t.Context()
	userID := testdb.NewUser(t, pool, "recaida@undiamas.mx")

	svc.OnJournal(ctx, userID, "Ya recaí, rompí mi racha anoche.")

	var alertas int
	var semaforo string
	if err := pool.QueryRow(ctx, `
		SELECT (SELECT count(*) FROM alerts WHERE user_id = $1),
		       (SELECT traffic_light::text FROM sobriety_trackers WHERE user_id = $1)`,
		userID).Scan(&alertas, &semaforo); err != nil {
		t.Fatalf("comprobación: %v", err)
	}
	if alertas != 1 {
		t.Errorf("alerts = %d, se esperaba 1", alertas)
	}
	if semaforo != "red" {
		t.Errorf("semáforo = %q, se esperaba red", semaforo)
	}
}

// El ánimo pasa por el mismo clasificador, pero una etiqueta suelta no basta
// para mover nada: "ANSIOSO" es una palabra de peso 1 y el umbral es 3.
func TestAnimoSueltoNoDisparaSolo(t *testing.T) {
	svc, lights, pool := servicio(t)
	ctx := t.Context()
	userID := testdb.NewUser(t, pool, "animo@undiamas.mx")

	svc.OnMood(ctx, userID, "ANSIOSO")

	nivel, err := lights.Current(ctx, userID)
	if err != nil {
		t.Fatalf("Current: %v", err)
	}
	if nivel != risk.Green {
		t.Errorf("semáforo = %v, una etiqueta de ánimo suelta no debería moverlo", nivel)
	}
}

// Lo que la app recibe al terminar de escribir: el veredicto en la misma
// respuesta, sin tener que releer el semáforo ni esperar el evento SSE.
func TestElVeredictoVuelveConMaterialDeApoyo(t *testing.T) {
	svc, _, pool := servicio(t)
	ctx := t.Context()
	userID := testdb.NewUser(t, pool, "veredicto@undiamas.mx")

	out := svc.OnText(ctx, userID, "diario", "Tengo muchas ganas de fumar y estoy solo en la casa.")

	if out.Nivel == risk.Green {
		t.Errorf("nivel = %v, ese texto tiene señales", out.Nivel)
	}
	if !out.Subio {
		t.Error("el semáforo debería haber subido")
	}
	if out.Semaforo == nil || *out.Semaforo != out.Nivel {
		t.Errorf("semaforo = %v, se esperaba %v", out.Semaforo, out.Nivel)
	}
	if len(out.Categorias) == 0 || out.Resumen == "" {
		t.Error("el veredicto llegó sin categorías ni resumen")
	}
	if len(out.Apoyo) == 0 {
		t.Fatal("el veredicto llegó sin material de apoyo")
	}
	if len(out.Apoyo) > maxApoyo {
		t.Errorf("%d pasajes, el tope es %d", len(out.Apoyo), maxApoyo)
	}
	for _, a := range out.Apoyo {
		if a.Titulo == "" || a.Texto == "" || a.Fuente == "" {
			t.Errorf("pasaje %q incompleto", a.ID)
		}
	}
}

// Un texto tranquilo también devuelve veredicto: la app tiene que poder decir
// "tu semáforo sigue en rojo" después de una entrada buena.
func TestUnTextoVerdeIgualDevuelveElSemaforoVigente(t *testing.T) {
	svc, lights, pool := servicio(t)
	ctx := t.Context()
	userID := testdb.NewUser(t, pool, "verde-con-rojo@undiamas.mx")

	if _, err := lights.Record(ctx, userID, trafficlight.Evaluation{
		Status: risk.Red, Reason: "check-in rojo",
	}, "prueba"); err != nil {
		t.Fatalf("Record inicial: %v", err)
	}

	out := svc.OnText(ctx, userID, "diario", "Hoy comí con mi hermana y salimos a caminar.")

	if out.Nivel != risk.Green {
		t.Errorf("nivel = %v, se esperaba VERDE", out.Nivel)
	}
	if out.Subio {
		t.Error("no debería haber subido nada")
	}
	if out.Semaforo == nil || *out.Semaforo != risk.Red {
		t.Errorf("semaforo = %v, se esperaba ROJO: el análisis no baja", out.Semaforo)
	}
}

// Preview no toca la base: es lo que responde POST /v1/analysis/text.
func TestPreviewNoRegistraNada(t *testing.T) {
	svc, lights, pool := servicio(t)
	ctx := t.Context()
	userID := testdb.NewUser(t, pool, "preview@undiamas.mx")

	out := svc.Preview("Ya recaí, rompí mi racha anoche.")
	if out.Nivel != risk.Red {
		t.Errorf("nivel = %v, se esperaba ROJO", out.Nivel)
	}
	if out.Semaforo != nil {
		t.Error("preview no debe reportar semáforo: no lee ni escribe estado")
	}

	logs, err := lights.List(ctx, userID, 5)
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(logs) != 0 {
		t.Errorf("preview registró %d entradas", len(logs))
	}
}

// La razón por la que el diario usa RetrieveLocal: ni una palabra sale a la red.
// Si alguien cambia analysis para usar Retrieve, este test truena.
func TestElDiarioNuncaSeEmbebe(t *testing.T) {
	pool := testdb.New(t)
	hub := notify.NewHub()
	lights := trafficlight.NewService(pool, hub, alerts.NewService(pool, hub))
	lex, err := lexicon.Default()
	if err != nil {
		t.Fatalf("léxico: %v", err)
	}

	espia := &embebedorEspia{}
	ret, err := rag.New(lex, espia)
	if err != nil {
		t.Fatalf("corpus: %v", err)
	}
	if err := ret.Warmup(t.Context()); err != nil {
		t.Fatalf("warmup: %v", err)
	}

	svc := NewService(lex, lights, ret)
	userID := testdb.NewUser(t, pool, "privado@undiamas.mx")
	svc.OnJournal(t.Context(), userID, "Tengo muchas ganas de fumar y estoy solo en la casa.")

	if espia.consultas > 0 {
		t.Errorf("el diario se mandó a embeber %d veces: eso lo saca del servidor", espia.consultas)
	}
}

// embebedorEspia cuenta las consultas embebidas. El corpus sí puede embeberse
// —es material público del repositorio—; el texto de la persona no.
type embebedorEspia struct{ consultas int }

func (e *embebedorEspia) EmbedDocs(_ context.Context, textos []string) ([][]float32, error) {
	out := make([][]float32, len(textos))
	for i := range textos {
		v := make([]float32, len(textos))
		v[i] = 1
		out[i] = v
	}
	return out, nil
}

func (e *embebedorEspia) EmbedQuery(_ context.Context, _ string) ([]float32, error) {
	e.consultas++
	return nil, errors.New("el diario no debería llegar aquí")
}
