package rag

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/lexicon"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/risk"
)

// fakeEmbedder da a cada documento un vector unitario distinto (uno en su
// posición, ceros en el resto). Así la similitud coseno es 1 con el documento
// elegido y 0 con todos los demás, y el test puede afirmar sobre el ranking sin
// depender de qué opine un modelo real.
type fakeEmbedder struct {
	n        int
	objetivo int  // índice del documento al que "se parece" la consulta
	fallaDoc bool // Warmup falla
	fallaQry bool // la consulta falla, el caso de red caída en producción
}

func (f *fakeEmbedder) EmbedDocs(_ context.Context, textos []string) ([][]float32, error) {
	if f.fallaDoc {
		return nil, errors.New("sin cuota")
	}
	f.n = len(textos)
	out := make([][]float32, len(textos))
	for i := range textos {
		v := make([]float32, len(textos))
		v[i] = 1
		out[i] = v
	}
	return out, nil
}

func (f *fakeEmbedder) EmbedQuery(_ context.Context, _ string) ([]float32, error) {
	if f.fallaQry {
		return nil, errors.New("timeout")
	}
	v := make([]float32, f.n)
	v[f.objetivo] = 1
	return v, nil
}

func nuevoStore(t *testing.T, emb Embedder) *Store {
	t.Helper()
	lex, err := lexicon.Default()
	if err != nil {
		t.Fatalf("léxico: %v", err)
	}
	s, err := New(lex, emb)
	if err != nil {
		t.Fatalf("corpus: %v", err)
	}
	return s
}

func indiceDe(t *testing.T, s *Store, id string) int {
	t.Helper()
	for i, d := range s.docs {
		if d.ID == id {
			return i
		}
	}
	t.Fatalf("no existe el documento %q", id)
	return -1
}

func ids(hits []Hit) []string {
	out := make([]string, len(hits))
	for i, h := range hits {
		out[i] = h.ID
	}
	return out
}

func contiene(xs []string, x string) bool {
	for _, v := range xs {
		if v == x {
			return true
		}
	}
	return false
}

// El corpus se valida en el test y no en New: un pasaje sin nivel o con una
// categoría mal escrita no rompe nada en tiempo de ejecución, solo deja de
// recuperarse en silencio, que es peor.
func TestCorpusBienFormado(t *testing.T) {
	lex, err := lexicon.Default()
	if err != nil {
		t.Fatalf("léxico: %v", err)
	}
	validas := lex.Categorias()
	s := nuevoStore(t, nil)

	vistos := map[string]bool{}
	for _, d := range s.docs {
		if vistos[d.ID] {
			t.Errorf("id duplicado: %s", d.ID)
		}
		vistos[d.ID] = true
		if d.Titulo == "" || d.Texto == "" || d.Fuente == "" {
			t.Errorf("%s: le falta título, texto o fuente", d.ID)
		}
		if len(d.niveles) == 0 {
			t.Errorf("%s: sin niveles, nunca se va a recuperar", d.ID)
		}
		for _, c := range d.Categorias {
			if !contiene(validas, c) {
				t.Errorf("%s: la categoría %q no existe en el léxico", d.ID, c)
			}
		}
	}
}

// Sin embeddings el RAG tiene que seguir sirviendo. Es el modo en el que corre
// mientras se calienta el índice y cada vez que Gemini no contesta.
func TestRecuperaSoloConBM25(t *testing.T) {
	s := nuevoStore(t, nil)
	if s.Listo() {
		t.Fatal("sin embebedor no debería estar listo")
	}

	hits := s.Retrieve(context.Background(), "tengo muchas ganas de fumar, no aguanto", risk.Yellow)
	if len(hits) == 0 {
		t.Fatal("no recuperó nada en modo léxico")
	}
	if len(hits) > topK {
		t.Errorf("devolvió %d pasajes, el tope es %d", len(hits), topK)
	}
	for _, h := range hits {
		if h.Semantico != 0 {
			t.Errorf("%s trae similitud semántica sin índice: %v", h.ID, h.Semantico)
		}
	}
}

// El filtro por semáforo es duro: material de urgencia solo en rojo.
func TestFiltraPorNivel(t *testing.T) {
	s := nuevoStore(t, nil)
	consulta := "creo que tuvo una sobredosis, no responde y respira raro"

	if contiene(ids(s.Retrieve(context.Background(), consulta, risk.Green)), "sobredosis-urgencia") {
		t.Error("el protocolo de sobredosis salió en verde")
	}
	if !contiene(ids(s.Retrieve(context.Background(), consulta, risk.Red)), "sobredosis-urgencia") {
		t.Error("el protocolo de sobredosis no salió en rojo, que es donde hace falta")
	}
}

// Lo que justifica el RAG entero: recuperar algo que no comparte ni una palabra
// con la consulta. BM25 no puede; los embeddings sí.
func TestElSemanticoRecuperaSinVocabularioEnComun(t *testing.T) {
	emb := &fakeEmbedder{}
	s := nuevoStore(t, emb)
	emb.objetivo = indiceDe(t, s, "aburrimiento-vacio")

	if err := s.Warmup(context.Background()); err != nil {
		t.Fatalf("warmup: %v", err)
	}
	if !s.Listo() {
		t.Fatal("tras el warmup debería estar listo")
	}

	// Ni "domingos" ni "hueco" aparecen en ese pasaje con esas palabras.
	hits := s.Retrieve(context.Background(), "los domingos se me hacen larguísimos", risk.Yellow)
	if len(hits) == 0 || hits[0].ID != "aburrimiento-vacio" {
		t.Fatalf("se esperaba aburrimiento-vacio arriba, salió %v", ids(hits))
	}
	if hits[0].Semantico < minSemantico {
		t.Errorf("similitud %v por debajo del umbral", hits[0].Semantico)
	}
}

// Si la llamada de embeddings falla en pleno chat, la respuesta no se cae: se
// recupera con lo que hay en proceso.
func TestSiFallaElEmbebedorDegradaALexico(t *testing.T) {
	emb := &fakeEmbedder{}
	s := nuevoStore(t, emb)
	emb.objetivo = indiceDe(t, s, "antojo-ola")
	if err := s.Warmup(context.Background()); err != nil {
		t.Fatalf("warmup: %v", err)
	}

	emb.fallaQry = true
	hits := s.Retrieve(context.Background(), "tengo un antojo horrible de beber", risk.Yellow)
	if len(hits) == 0 {
		t.Fatal("con el embebedor caído dejó de recuperar")
	}
	for _, h := range hits {
		if h.Semantico != 0 {
			t.Errorf("%s trae semántico con la consulta fallida", h.ID)
		}
	}
}

func TestWarmupQueFallaNoDejaElIndiceAMedias(t *testing.T) {
	s := nuevoStore(t, &fakeEmbedder{fallaDoc: true})
	if err := s.Warmup(context.Background()); err == nil {
		t.Fatal("se esperaba error del warmup")
	}
	if s.Listo() {
		t.Error("quedó marcado como listo tras fallar")
	}
	if len(s.Retrieve(context.Background(), "quiero fumar", risk.Yellow)) == 0 {
		t.Error("tras un warmup fallido debería seguir recuperando por BM25")
	}
}

// Una consulta sin relación con el corpus no debe arrastrar el pasaje "menos
// malo": es preferible no inyectar nada.
func TestConsultaSinRelacionNoDevuelveNada(t *testing.T) {
	s := nuevoStore(t, nil)
	if hits := s.Retrieve(context.Background(), "zxqv wrrkl ñbbt", risk.Green); len(hits) != 0 {
		t.Errorf("devolvió %v para una consulta sin relación", ids(hits))
	}
	if p := s.Prompt(context.Background(), "zxqv wrrkl ñbbt", risk.Green); p != "" {
		t.Errorf("armó bloque de material sin tener nada: %q", p)
	}
}

func TestPromptTraeLasInstruccionesDeUso(t *testing.T) {
	s := nuevoStore(t, nil)
	p := s.Prompt(context.Background(), "ya recaí, volví a beber anoche", risk.Red)
	if p == "" {
		t.Fatal("no armó el bloque de material")
	}
	for _, frase := range []string{"no lo cites textualmente", "no los inventes"} {
		if !strings.Contains(p, frase) {
			t.Errorf("el bloque no le dice al modelo %q", frase)
		}
	}
}

// El ranking tiene que ser reproducible: sin esto, ajustar pesos es adivinar.
func TestElRankingEsEstable(t *testing.T) {
	s := nuevoStore(t, nil)
	consulta := "me siento solo y con ganas de tomar"
	primero := ids(s.Retrieve(context.Background(), consulta, risk.Yellow))
	for i := 0; i < 5; i++ {
		if got := ids(s.Retrieve(context.Background(), consulta, risk.Yellow)); strings.Join(got, ",") != strings.Join(primero, ",") {
			t.Fatalf("orden inestable: %v vs %v", got, primero)
		}
	}
}

// El piso relativo: cuando solo un pasaje viene al caso, no se rellena con dos
// más para completar el top 3.
func TestNoRellenaConPasajesFlojos(t *testing.T) {
	s := nuevoStore(t, nil)
	hits := s.Retrieve(context.Background(), "cuántos días llevo de racha", risk.Green)
	if len(hits) == 0 {
		t.Fatal("no recuperó nada")
	}
	piso := hits[0].Score * minRelativo
	for _, h := range hits[1:] {
		if h.Score < piso {
			t.Errorf("%s pasó el corte con %.3f (piso %.3f)", h.ID, h.Score, piso)
		}
	}
}
