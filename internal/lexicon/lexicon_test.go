package lexicon

import (
	"strings"
	"testing"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/risk"
)

func cargar(t *testing.T) *Lexicon {
	t.Helper()
	l, err := Default()
	if err != nil {
		t.Fatalf("el léxico embebido no carga: %v", err)
	}
	return l
}

// La tabla de casos es el contrato del clasificador. Cada fila es una decisión
// de producto: si mañana se cambia un peso y una de estas cambia de color, hay
// que enterarse aquí y no en el teléfono de alguien.
func TestNiveles(t *testing.T) {
	l := cargar(t)

	casos := []struct {
		nombre string
		texto  string
		quiere risk.Level
	}{
		{
			"un día normal no mueve nada",
			"Hoy desayuné con mi hermana y fuimos al parque. Buen día.",
			risk.Green,
		},
		{
			"mencionar una sustancia de pasada no es riesgo",
			"En la cena había cerveza pero no me llamó la atención.",
			risk.Green,
		},
		{
			"malestar acumulado llega a amarillo",
			"Llevo días con mucha ansiedad, estrés en el trabajo y no puedo dormir.",
			risk.Yellow,
		},
		{
			"la intención explícita es amarillo, no rojo",
			"Quiero fumar.",
			risk.Yellow,
		},
		{
			"la recaída declarada es roja de inmediato",
			"Ya recaí, rompí mi racha anoche.",
			risk.Red,
		},
		{
			"la ideación es roja aunque el texto sea corto",
			"me quiero morir",
			risk.Red,
		},
		{
			"antojo intenso con búsqueda activa es rojo",
			"No aguanto más, tengo muchas ganas de tomar y estoy pensando en buscar alcohol, " +
				"quiero beber ya, ansiedad horrible.",
			risk.Red,
		},
	}

	for _, c := range casos {
		t.Run(c.nombre, func(t *testing.T) {
			got := l.Analyze(c.texto)
			if got.Level != c.quiere {
				t.Errorf("nivel = %v (score %.2f, crítico %v, categorías %v), se esperaba %v",
					got.Level, got.Score, got.Critico, got.Categorias, c.quiere)
			}
		})
	}
}

// La negación es lo que separa un clasificador usable de una lista de palabras.
// "No quiero fumar" es exactamente lo contrario de "quiero fumar".
func TestNegacion(t *testing.T) {
	l := cargar(t)

	negados := []string{
		"No quiero fumar.",
		"Hoy no tuve ganas de tomar.",
		"Ya no bebo, se acabó.",
		"Pasé por el bar sin ganas de beber.",
	}
	for _, texto := range negados {
		t.Run(texto, func(t *testing.T) {
			got := l.Analyze(texto)
			if got.Level == risk.Red {
				t.Errorf("%q se clasificó como ROJO: %+v", texto, got.Matches)
			}
		})
	}

	// El contraste: la misma frase sin el "no" sí tiene que puntuar.
	con := l.Analyze("Quiero fumar.")
	sin := l.Analyze("No quiero fumar.")
	if !(con.Score > sin.Score) {
		t.Errorf("la negación no baja la puntuación: con=%.2f sin=%.2f", con.Score, sin.Score)
	}
}

// Un término que empieza por "no" y está en el léxico no puede quedar anulado
// por su propio "no". Es el caso que rompe una implementación ingenua.
func TestNegacionNoSeComeSusPropiosTerminos(t *testing.T) {
	l := cargar(t)

	res := l.Analyze("No aguanto más.")
	if res.Score == 0 {
		t.Error(`"no aguanto más" quedó en cero: la negación se comió el propio término`)
	}
}

// La frase larga tiene que ganar a la palabra suelta, y contarse una sola vez.
func TestFraseGanaAPalabra(t *testing.T) {
	l := cargar(t)

	res := l.Analyze("quiero fumar")
	if len(res.Matches) != 1 {
		t.Fatalf("se esperaba una coincidencia, hubo %d: %+v", len(res.Matches), res.Matches)
	}
	if res.Matches[0].Categoria != "intencion" {
		t.Errorf("categoría = %q, se esperaba intencion (no consumo)", res.Matches[0].Categoria)
	}
}

func TestProtectorasRestan(t *testing.T) {
	l := cargar(t)

	solo := l.Analyze("Tuve ansiedad y estrés todo el día.")
	conApoyo := l.Analyze("Tuve ansiedad y estrés todo el día, pero fui a terapia, " +
		"salí a correr y hablé con mi padrino.")

	if !(conApoyo.Score < solo.Score) {
		t.Errorf("el texto con recuperación no bajó: %.2f vs %.2f", conApoyo.Score, solo.Score)
	}
	if conApoyo.Score < 0 {
		t.Errorf("la puntuación no puede ser negativa: %.2f", conApoyo.Score)
	}
}

// Acentos y mayúsculas no pueden cambiar el resultado: nadie escribe con tildes
// en el teléfono.
func TestNormalizacion(t *testing.T) {
	l := cargar(t)

	a := l.Analyze("Tengo mucha ansiedad y ganas de tomar")
	b := l.Analyze("TENGO MUCHA ANSIEDAD Y GANAS DE TOMAR!!!")
	c := l.Analyze("tengo mucha ansiédad y ganas de tomar")

	if a.Score != b.Score || a.Score != c.Score {
		t.Errorf("la normalización no iguala: %.2f / %.2f / %.2f", a.Score, b.Score, c.Score)
	}
}

// La repetición no puede escalar sin techo: quien escribe desahogándose repite.
func TestRepeticionTopeada(t *testing.T) {
	l := cargar(t)

	una := l.Analyze("no puedo")
	muchas := l.Analyze("no puedo no puedo no puedo no puedo no puedo no puedo no puedo")

	if muchas.Score > una.Score*maxPorTermino {
		t.Errorf("la repetición escaló sin tope: %.2f contra %.2f máximo",
			muchas.Score, una.Score*maxPorTermino)
	}
}

// El texto original nunca puede acabar en el resumen: ese campo se guarda como
// motivo del semáforo y un terapeuta puede leerlo.
func TestResumenNoFiltraElTexto(t *testing.T) {
	l := cargar(t)

	res := l.Analyze("Me peleé con Marta y quiero fumar")
	if res.Resumen == "" {
		t.Fatal("resumen vacío")
	}
	for _, palabra := range []string{"Marta", "marta", "peleé"} {
		if strings.Contains(res.Resumen, palabra) {
			t.Errorf("el resumen %q filtra el texto original", res.Resumen)
		}
	}
	// Lo que sí debe llevar son los nombres de las categorías.
	if !strings.Contains(res.Resumen, "intencion") {
		t.Errorf("resumen = %q, se esperaba que nombrara la categoría", res.Resumen)
	}
}

func TestTextoVacio(t *testing.T) {
	l := cargar(t)

	res := l.Analyze("   \n\t  ")
	if res.Level != risk.Green || res.Score != 0 {
		t.Errorf("texto vacío = %v/%.2f, se esperaba VERDE/0", res.Level, res.Score)
	}
}
