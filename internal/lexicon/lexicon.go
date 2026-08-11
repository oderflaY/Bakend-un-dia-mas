// Package lexicon clasifica texto libre —una entrada de diario, una etiqueta de
// ánimo— y devuelve un nivel de semáforo.
//
// No es un RAG ni usa el modelo: es un diccionario con pesos por categoría. Se
// hace así a propósito y no por falta de ambición:
//
//   - Es determinista. El mismo texto da el mismo nivel siempre, y eso se puede
//     probar. Un modelo que decide si alguien está en riesgo no se puede testear
//     con una tabla de casos.
//   - No depende de la red. Si Gemini está caído, el semáforo sigue funcionando;
//     y el semáforo es justo lo que no puede dejar de funcionar.
//   - No cuesta tokens ni manda el diario de nadie a un tercero. El diario es el
//     dato más íntimo de la app.
//
// Lo que un diccionario no puede hacer es entender contexto. Por eso hay tres
// mitigaciones dentro: la frase larga gana a la palabra suelta, hay negación, y
// las categorías protectoras restan. El paso siguiente —cuando haya datos reales
// que medir— es sumar embeddings a este mismo Result, no sustituirlo: este
// clasificador es el suelo, no el techo.
package lexicon

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"sort"
	"strings"
	"sync"
	"unicode"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/risk"
)

//go:embed lexicon.json
var lexiconJSON []byte

// maxPorTermino: un término repetido veinte veces no significa veinte veces más
// riesgo. Sin este tope, "no puedo, no puedo, no puedo" dispararía un rojo.
const maxPorTermino = 3

// ventanaNegacion: cuántos tokens antes de la coincidencia se buscan negadores.
// Tres cubre "no quiero fumar" y "ya no voy a beber" sin cruzar oraciones.
const ventanaNegacion = 3

type categoria struct {
	Nombre   string   `json:"nombre"`
	Peso     float64  `json:"peso"`
	Critica  bool     `json:"critica"`
	Accion   string   `json:"accion"`
	Terminos []string `json:"terminos"`
}

type archivo struct {
	Version  int `json:"version"`
	Umbrales struct {
		Amarillo float64 `json:"amarillo"`
		Rojo     float64 `json:"rojo"`
	} `json:"umbrales"`
	Negadores  []string    `json:"negadores"`
	Categorias []categoria `json:"categorias"`
}

type termino struct {
	tokens []string
	texto  string
	cat    *categoria
}

// Lexicon es inmutable una vez cargado, así que se comparte entre peticiones sin
// candado.
type Lexicon struct {
	umbralAmarillo float64
	umbralRojo     float64
	negadores      map[string]bool
	// Indexado por primer token: en un texto de mil palabras solo se prueban los
	// términos que empiezan por la palabra que se está mirando.
	porPrimerToken map[string][]termino
}

// Match es una coincidencia, ya agrupada por término.
type Match struct {
	Termino   string  `json:"termino"`
	Categoria string  `json:"categoria"`
	Veces     int     `json:"veces"`
	Peso      float64 `json:"peso"`
}

// Result es lo que sale del análisis. Nunca incluye el texto original: se usa
// como `reason` del registro del semáforo, que un terapeuta sí puede leer, y el
// diario no es suyo.
type Result struct {
	Score      float64    `json:"score"`
	Level      risk.Level `json:"nivel"`
	Critico    bool       `json:"critico"`
	Matches    []Match    `json:"coincidencias"`
	Categorias []string   `json:"categorias"`
	Resumen    string     `json:"resumen"`
	Acciones   []string   `json:"acciones"`
}

var (
	porDefecto *Lexicon
	unaVez     sync.Once
	errCarga   error
)

// Default carga el léxico embebido una sola vez. Si el JSON estuviera mal, es un
// error de compilación de datos y hay que verlo al arrancar, no en la primera
// entrada de diario de alguien.
func Default() (*Lexicon, error) {
	unaVez.Do(func() {
		porDefecto, errCarga = parse(lexiconJSON)
	})
	return porDefecto, errCarga
}

func parse(raw []byte) (*Lexicon, error) {
	var a archivo
	if err := json.Unmarshal(raw, &a); err != nil {
		return nil, fmt.Errorf("léxico ilegible: %w", err)
	}
	if len(a.Categorias) == 0 {
		return nil, fmt.Errorf("léxico sin categorías")
	}

	l := &Lexicon{
		umbralAmarillo: a.Umbrales.Amarillo,
		umbralRojo:     a.Umbrales.Rojo,
		negadores:      map[string]bool{},
		porPrimerToken: map[string][]termino{},
	}
	for _, n := range a.Negadores {
		l.negadores[normalizar(n)] = true
	}

	for i := range a.Categorias {
		cat := &a.Categorias[i]
		for _, t := range cat.Terminos {
			tokens := tokenizar(t)
			if len(tokens) == 0 {
				continue
			}
			primero := tokens[0]
			l.porPrimerToken[primero] = append(l.porPrimerToken[primero], termino{
				tokens: tokens,
				texto:  strings.Join(tokens, " "),
				cat:    cat,
			})
		}
	}

	// Más largo primero: así "quiero fumar" (intención, peso 5) gana a "fumar"
	// (consumo, peso 1.5) y no se cuentan los dos. Es la corrección directa al
	// problema de que una lista plana no distingue mencionar de querer.
	for k := range l.porPrimerToken {
		ts := l.porPrimerToken[k]
		sort.SliceStable(ts, func(i, j int) bool {
			return len(ts[i].tokens) > len(ts[j].tokens)
		})
	}
	return l, nil
}

// Categorias devuelve los nombres de categoría que este léxico conoce. La usa
// el test del corpus del RAG para que un pasaje no pueda etiquetarse con una
// categoría inexistente: un "prevencion " con espacio de más no daría error en
// ningún lado, solo dejaría de sumar en el ranking para siempre.
func (l *Lexicon) Categorias() []string {
	vistas := map[string]bool{}
	out := []string{}
	for _, ts := range l.porPrimerToken {
		for _, t := range ts {
			if !vistas[t.cat.Nombre] {
				vistas[t.cat.Nombre] = true
				out = append(out, t.cat.Nombre)
			}
		}
	}
	sort.Strings(out)
	return out
}

// Analyze puntúa el texto y decide el nivel.
func (l *Lexicon) Analyze(texto string) Result {
	tokens := tokenizar(texto)
	res := Result{Level: risk.Green, Matches: []Match{}, Categorias: []string{}, Acciones: []string{}}
	if len(tokens) == 0 {
		return res
	}

	type acumulado struct {
		veces int
		cat   *categoria
	}
	porTermino := map[string]*acumulado{}
	orden := []string{}

	for i := 0; i < len(tokens); {
		t, largo := l.buscar(tokens, i)
		if largo == 0 {
			i++
			continue
		}
		// La negación se mira solo antes del inicio de la coincidencia. Así
		// "no aguanto más", que es un término que empieza por "no", cuenta como
		// riesgo, mientras que "no quiero fumar" queda descartado.
		if l.negado(tokens, i) {
			i += largo
			continue
		}

		a, vista := porTermino[t.texto]
		if !vista {
			a = &acumulado{cat: t.cat}
			porTermino[t.texto] = a
			orden = append(orden, t.texto)
		}
		a.veces++
		i += largo
	}

	cats := map[string]*categoria{}
	for _, texto := range orden {
		a := porTermino[texto]
		veces := min(a.veces, maxPorTermino)
		aporte := a.cat.Peso * float64(veces)
		res.Score += aporte
		res.Matches = append(res.Matches, Match{
			Termino:   texto,
			Categoria: a.cat.Nombre,
			Veces:     veces,
			Peso:      aporte,
		})
		if a.cat.Critica {
			res.Critico = true
		}
		cats[a.cat.Nombre] = a.cat
	}

	// El texto protector puede bajar el score hasta cero, no por debajo: un buen
	// día no compra crédito contra el siguiente.
	if res.Score < 0 {
		res.Score = 0
	}

	// Ordenar por aporte deja arriba lo que de verdad movió el nivel; el resumen
	// y las acciones se construyen desde ahí.
	sort.SliceStable(res.Matches, func(i, j int) bool {
		return res.Matches[i].Peso > res.Matches[j].Peso
	})

	nombres := []string{}
	for _, m := range res.Matches {
		if cat := cats[m.Categoria]; cat != nil && cat.Peso > 0 {
			if !contiene(nombres, m.Categoria) {
				nombres = append(nombres, m.Categoria)
			}
			if cat.Accion != "" && !contiene(res.Acciones, cat.Accion) && len(res.Acciones) < 3 {
				res.Acciones = append(res.Acciones, cat.Accion)
			}
		}
	}
	res.Categorias = nombres

	res.Level = l.nivel(res.Score, res.Critico)
	res.Resumen = resumen(nombres)
	return res
}

// nivel aplica los umbrales.
//
// El rojo exige las dos cosas: puntuación alta **y** una categoría crítica. La
// razón no es estadística sino humana: un rojo llama al contacto de confianza de
// alguien. Un texto que solo acumula tristeza, estrés y menciones de alcohol
// llega a amarillo —que es visible y accionable— pero no despierta a su familia
// de madrugada. Para el rojo hace falta que aparezca intención, recaída,
// ideación o búsqueda activa.
func (l *Lexicon) nivel(score float64, critico bool) risk.Level {
	switch {
	case score >= l.umbralRojo && critico:
		return risk.Red
	case score >= l.umbralAmarillo:
		return risk.Yellow
	default:
		return risk.Green
	}
}

// buscar devuelve el término más largo que empieza en la posición i.
func (l *Lexicon) buscar(tokens []string, i int) (termino, int) {
	for _, t := range l.porPrimerToken[tokens[i]] {
		if i+len(t.tokens) > len(tokens) {
			continue
		}
		if coincide(tokens[i:i+len(t.tokens)], t.tokens) {
			return t, len(t.tokens)
		}
	}
	return termino{}, 0
}

func (l *Lexicon) negado(tokens []string, inicio int) bool {
	desde := max(inicio-ventanaNegacion, 0)
	for _, tok := range tokens[desde:inicio] {
		if l.negadores[tok] {
			return true
		}
	}
	return false
}

func coincide(a, b []string) bool {
	for i := range b {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// tokenizar normaliza y parte en palabras. Sin acentos y sin puntuación, así
// "cocaína", "COCAINA" y "cocaina!" son la misma cosa: la gente no escribe con
// acentos en el teléfono y el léxico no puede depender de eso.
func tokenizar(s string) []string {
	return strings.Fields(normalizar(s))
}

// Tokenizar expone la misma normalización para el índice de internal/rag. Que
// las dos capas partan el texto igual no es cosmético: si el índice tratara
// "cocaína" y "cocaina" como tokens distintos y el clasificador no, la
// recuperación fallaría justo en las entradas escritas a las tres de la mañana.
func Tokenizar(s string) []string {
	return tokenizar(s)
}

func normalizar(s string) string {
	var b strings.Builder
	b.Grow(len(s))
	for _, r := range strings.ToLower(s) {
		if d, ok := sinAcento[r]; ok {
			b.WriteRune(d)
			continue
		}
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(r)
			continue
		}
		b.WriteByte(' ')
	}
	return b.String()
}

// sinAcento cubre el español; una tabla explícita evita depender de
// golang.org/x/text solo para quitar cinco tildes.
var sinAcento = map[rune]rune{
	'á': 'a', 'é': 'e', 'í': 'i', 'ó': 'o', 'ú': 'u', 'ü': 'u', 'ñ': 'n',
}

func resumen(categorias []string) string {
	if len(categorias) == 0 {
		return "sin señales"
	}
	if len(categorias) > 3 {
		categorias = categorias[:3]
	}
	return strings.Join(categorias, ", ")
}

func contiene(xs []string, x string) bool {
	for _, v := range xs {
		if v == x {
			return true
		}
	}
	return false
}
