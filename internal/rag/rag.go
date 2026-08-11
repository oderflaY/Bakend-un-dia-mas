// Package rag recupera material de apoyo para inyectarlo en el turno del
// asistente. Es la capa que faltaba encima del léxico.
//
// El léxico (internal/lexicon) decide *qué tan grave* es lo que alguien escribió
// y eso no se le puede delegar a un modelo: tiene que ser determinista y
// funcionar sin red. Este paquete resuelve el otro problema, el que una lista de
// palabras no puede resolver: *qué decirle a la persona*. Ahí sí hace falta
// entender el contexto, y para eso están los embeddings.
//
// Tres decisiones que explican el diseño:
//
//  1. La recuperación es **híbrida y degradable**. Puntúa por BM25 (léxico, en
//     proceso, siempre disponible) y por similitud de embeddings (Gemini, en
//     red). Si no hay API key, si la llamada falla o si el índice todavía no
//     terminó de calentarse, sigue recuperando solo con BM25. Un RAG que se cae
//     con la red dejaría al asistente peor que sin RAG.
//  2. El corpus es **cerrado y curado**. No se indexa el diario de nadie ni
//     nada generado por el modelo: solo kb.json, que es material revisable en el
//     repositorio. En una app de adicciones, "el modelo se inventó una línea de
//     ayuda" no es un bug menor.
//  3. Lo único que sale a la red es la **pregunta del chat**, que ya iba a
//     Gemini de todos modos. El diario y el ánimo se analizan con el léxico y no
//     se embeben nunca.
package rag

import (
	"context"
	_ "embed"
	"encoding/json"
	"fmt"
	"math"
	"sort"
	"strings"
	"sync"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/lexicon"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/risk"
)

//go:embed kb.json
var kbJSON []byte

const (
	// topK: cuántos pasajes se inyectan como mucho. Tres cabe de sobra en el
	// contexto y evita que el material de apoyo ahogue lo que la persona dijo.
	topK = 3

	// minSemantico: por debajo de esto la similitud de embeddings no es señal.
	// Con text-embedding-004 sobre español, dos textos sin relación caen
	// alrededor de 0.4-0.5, así que el umbral corta ahí arriba.
	minSemantico = 0.62

	// Pesos de la mezcla. El semántico pesa más porque es el que aporta lo que
	// BM25 no puede: "me siento vacío los domingos" no comparte ninguna palabra
	// con "hueco de tiempo libre".
	pesoSemantico = 0.60
	pesoLexico    = 0.40
	pesoCategoria = 0.15

	// BM25, parámetros estándar.
	bm25K1 = 1.2
	bm25B  = 0.75

	// minRelativo descarta lo que puntúe por debajo de esta fracción del mejor
	// pasaje. Sin el piso, una consulta que solo engancha con un documento
	// arrastra otros dos por relleno —el ranking siempre tiene un segundo y un
	// tercero— y el modelo termina hablando de algo que nadie preguntó.
	minRelativo = 0.45

	// maxConsulta acota lo que se manda a embeber. Nadie escribe una consulta
	// útil de más de mil caracteres y sí hay quien pega un texto entero.
	maxConsulta = 1000
)

// Doc es un pasaje del corpus.
type Doc struct {
	ID         string   `json:"id"`
	Titulo     string   `json:"titulo"`
	Categorias []string `json:"categorias"` // los mismos nombres que usa el léxico
	Niveles    []string `json:"niveles"`    // semáforos en los que aplica
	Fuente     string   `json:"fuente"`
	Texto      string   `json:"texto"`

	// Sinonimos son las palabras con las que la gente escribe eso mismo:
	// "fumar", "chela", "cruda", "me late". Se indexan pero no se le muestran al
	// modelo. Sin esto el corpus solo se recupera si la persona resulta escribir
	// en el registro con el que fue redactado, y nadie escribe "urgencia de
	// consumo" a las tres de la mañana: escribe "no aguanto las ganas".
	Sinonimos []string `json:"sinonimos"`

	tokens    []string
	frec      map[string]int
	largo     float64
	niveles   map[risk.Level]bool
	categoria map[string]bool
	vector    []float32 // vacío hasta que Warmup termine
}

type archivo struct {
	Version    int   `json:"version"`
	Documentos []Doc `json:"documentos"`
}

// Hit es un pasaje recuperado con el desglose de por qué se recuperó. El
// desglose no es decorativo: es lo que permite ajustar pesos mirando consultas
// reales en vez de a ojo.
type Hit struct {
	Doc        *Doc    `json:"-"`
	ID         string  `json:"id"`
	Titulo     string  `json:"titulo"`
	Texto      string  `json:"texto"`
	Fuente     string  `json:"fuente"`
	Score      float64 `json:"score"`
	Semantico  float64 `json:"semantico"`
	Lexico     float64 `json:"lexico"`
	Categorias float64 `json:"categorias"`
}

// Embedder es la única dependencia de red. Se inyecta para poder testear la
// recuperación entera sin tocar Gemini.
type Embedder interface {
	EmbedDocs(ctx context.Context, textos []string) ([][]float32, error)
	EmbedQuery(ctx context.Context, texto string) ([]float32, error)
}

// Store es el índice. Los documentos y las estadísticas de BM25 son inmutables
// tras New; lo único que muta es el vector de cada doc, que Warmup escribe una
// sola vez bajo el candado.
type Store struct {
	docs       []*Doc
	idf        map[string]float64
	largoMedio float64
	lex        *lexicon.Lexicon
	emb        Embedder

	mu    sync.RWMutex
	listo bool
}

// New construye el índice léxico con el corpus embebido. No toca la red: el
// servidor arranca aunque Gemini esté caído o no haya key.
func New(lex *lexicon.Lexicon, emb Embedder) (*Store, error) {
	var a archivo
	if err := json.Unmarshal(kbJSON, &a); err != nil {
		return nil, fmt.Errorf("corpus ilegible: %w", err)
	}
	if len(a.Documentos) == 0 {
		return nil, fmt.Errorf("corpus vacío")
	}

	s := &Store{idf: map[string]float64{}, lex: lex, emb: emb}
	df := map[string]int{}
	total := 0.0

	for i := range a.Documentos {
		d := &a.Documentos[i]
		// Título y sinónimos entran al índice junto con el cuerpo. Al modelo se le
		// manda solo Texto: los sinónimos son vocabulario de búsqueda, no material
		// que tenga sentido leerle a nadie.
		d.tokens = tokenizar(d.Titulo + " " + strings.Join(d.Sinonimos, " ") + " " + d.Texto)
		d.frec = map[string]int{}
		for _, t := range d.tokens {
			d.frec[t]++
		}
		d.largo = float64(len(d.tokens))
		total += d.largo

		d.niveles = map[risk.Level]bool{}
		for _, n := range d.Niveles {
			d.niveles[risk.Parse(n)] = true
		}
		d.categoria = map[string]bool{}
		for _, c := range d.Categorias {
			d.categoria[c] = true
		}
		for t := range d.frec {
			df[t]++
		}
		s.docs = append(s.docs, d)
	}

	n := float64(len(s.docs))
	s.largoMedio = total / n
	for t, c := range df {
		s.idf[t] = math.Log(1 + (n-float64(c)+0.5)/(float64(c)+0.5))
	}
	return s, nil
}

// Warmup embebe el corpus. Se llama en una goroutine al arrancar: si tarda o
// falla, la recuperación funciona en modo léxico hasta que termine, y si nunca
// termina se queda ahí. No devuelve error al arranque a propósito —el servidor
// no debe negarse a arrancar porque un servicio de embeddings esté caído.
func (s *Store) Warmup(ctx context.Context) error {
	if s.emb == nil {
		return nil
	}
	textos := make([]string, len(s.docs))
	for i, d := range s.docs {
		textos[i] = d.Titulo + ". " + d.Texto
	}
	vecs, err := s.emb.EmbedDocs(ctx, textos)
	if err != nil {
		return err
	}
	if len(vecs) != len(s.docs) {
		return fmt.Errorf("el embebedor devolvió %d vectores para %d documentos", len(vecs), len(s.docs))
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	for i, v := range vecs {
		s.docs[i].vector = normalizarVec(v)
	}
	s.listo = true
	return nil
}

// Listo dice si el índice semántico ya está disponible. Sirve para /healthz y
// para los tests; la recuperación no lo necesita porque degrada sola.
func (s *Store) Listo() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.listo
}

// Retrieve devuelve los pasajes más relevantes para la consulta, filtrados por
// el semáforo de la persona.
//
// El filtro por nivel es duro y no una preferencia: en rojo no se le habla a
// alguien de higiene del sueño, y en verde no se le recita el protocolo de
// sobredosis a quien preguntó cómo va su racha.
func (s *Store) Retrieve(ctx context.Context, consulta string, level risk.Level) []Hit {
	return s.buscar(ctx, consulta, level, true)
}

// RetrieveLocal recupera **sin tocar la red**: solo BM25 y categorías, todo en
// proceso.
//
// Existe para el diario y el ánimo. El chat ya manda su mensaje a Gemini, así
// que embeber la consulta no revela nada nuevo; el diario es otra cosa: es el
// dato más íntimo de la app y no sale de aquí. Con esta función el diario puede
// recibir material de apoyo sin que una sola palabra de lo que alguien escribió
// viaje a un tercero.
//
// No recibe contexto porque no hay nada que cancelar: es aritmética sobre un
// índice en memoria.
func (s *Store) RetrieveLocal(consulta string, level risk.Level) []Hit {
	return s.buscar(context.Background(), consulta, level, false)
}

func (s *Store) buscar(ctx context.Context, consulta string, level risk.Level, conRed bool) []Hit {
	if consulta == "" {
		return nil
	}
	if len([]rune(consulta)) > maxConsulta {
		consulta = string([]rune(consulta)[:maxConsulta])
	}

	qTokens := tokenizar(consulta)
	if len(qTokens) == 0 {
		return nil
	}

	// Las categorías vienen del mismo clasificador que mueve el semáforo, así
	// que el material recuperado y el nivel de riesgo hablan de lo mismo. Es la
	// unión de las dos capas: palabras clave para clasificar, embeddings para
	// entender.
	cats := map[string]bool{}
	if s.lex != nil {
		for _, c := range s.lex.Analyze(consulta).Categorias {
			cats[c] = true
		}
	}

	var qVec []float32
	if conRed && s.Listo() {
		v, err := s.emb.EmbedQuery(ctx, consulta)
		if err == nil {
			qVec = normalizarVec(v)
		}
		// Si falla, qVec queda nil y abajo se recupera solo por BM25. El error
		// lo registra el embebedor, que es quien sabe si fue cuota, red o clave.
	}

	s.mu.RLock()
	defer s.mu.RUnlock()

	hits := make([]Hit, 0, len(s.docs))
	maxLex := 0.0
	for _, d := range s.docs {
		if !d.niveles[level] {
			continue
		}
		lex := s.bm25(qTokens, d)
		sem := 0.0
		if qVec != nil && len(d.vector) > 0 {
			sem = coseno(qVec, d.vector)
		}
		// Un pasaje entra si comparte vocabulario o si el sentido se parece.
		// Sin esta puerta, con normalizar por el máximo siempre saldría algo
		// arriba aunque no viniera a cuento.
		if lex <= 0 && sem < minSemantico {
			continue
		}
		maxLex = math.Max(maxLex, lex)
		hits = append(hits, Hit{
			Doc: d, ID: d.ID, Titulo: d.Titulo, Texto: d.Texto, Fuente: d.Fuente,
			Semantico: sem, Lexico: lex, Categorias: solape(cats, d.categoria),
		})
	}

	for i := range hits {
		lexNorm := 0.0
		if maxLex > 0 {
			lexNorm = hits[i].Lexico / maxLex
		}
		sem := hits[i].Semantico
		if sem < minSemantico {
			sem = 0 // por debajo del umbral no suma; tampoco resta
		}
		hits[i].Score = pesoSemantico*sem + pesoLexico*lexNorm + pesoCategoria*hits[i].Categorias
	}

	if len(hits) == 0 {
		return nil
	}
	sort.SliceStable(hits, func(i, j int) bool {
		if hits[i].Score != hits[j].Score {
			return hits[i].Score > hits[j].Score
		}
		return hits[i].ID < hits[j].ID // desempate estable, para poder testear
	})
	if len(hits) > topK {
		hits = hits[:topK]
	}
	piso := hits[0].Score * minRelativo
	for i, h := range hits {
		if h.Score < piso {
			return hits[:i]
		}
	}
	return hits
}

// Prompt arma el bloque que se cuelga de la instrucción de sistema. Devuelve ""
// cuando no hay nada relevante, y entonces el asistente conversa sin material,
// que es mejor que colarle un pasaje que no viene al caso.
func (s *Store) Prompt(ctx context.Context, consulta string, level risk.Level) string {
	hits := s.Retrieve(ctx, consulta, level)
	if len(hits) == 0 {
		return ""
	}
	var b strings.Builder
	b.WriteString(`
Material de apoyo recuperado para este mensaje. Úsalo si encaja con lo que la persona te está diciendo y descártalo si no.
Reformúlalo con tus palabras: no lo leas en voz alta, no lo cites textualmente y no menciones que existe este material.
Los datos concretos —números de teléfono, tiempos, nombres de técnicas— tómalos de aquí y no de tu memoria; si no están aquí, no los inventes.
`)
	for i, h := range hits {
		fmt.Fprintf(&b, "\n[%d] %s\n%s\n", i+1, h.Titulo, h.Texto)
	}
	return b.String()
}

func (s *Store) bm25(qTokens []string, d *Doc) float64 {
	score := 0.0
	// Un término repetido en la consulta no cuenta dos veces: "quiero fumar
	// quiero fumar" no es más relevante que decirlo una vez.
	vistos := map[string]bool{}
	for _, t := range qTokens {
		if vistos[t] {
			continue
		}
		vistos[t] = true
		f := float64(d.frec[t])
		if f == 0 {
			continue
		}
		idf := s.idf[t]
		score += idf * (f * (bm25K1 + 1)) /
			(f + bm25K1*(1-bm25B+bm25B*d.largo/s.largoMedio))
	}
	return score
}

// solape es la fracción de categorías del documento que el léxico detectó en la
// consulta.
func solape(consulta, doc map[string]bool) float64 {
	if len(consulta) == 0 || len(doc) == 0 {
		return 0
	}
	n := 0
	for c := range doc {
		if consulta[c] {
			n++
		}
	}
	return float64(n) / float64(len(doc))
}

func coseno(a, b []float32) float64 {
	if len(a) != len(b) {
		return 0
	}
	var s float64
	for i := range a {
		s += float64(a[i]) * float64(b[i])
	}
	return s // ambos vienen normalizados
}

func normalizarVec(v []float32) []float32 {
	var s float64
	for _, x := range v {
		s += float64(x) * float64(x)
	}
	if s == 0 {
		return v
	}
	n := math.Sqrt(s)
	out := make([]float32, len(v))
	for i, x := range v {
		out[i] = float32(float64(x) / n)
	}
	return out
}

// tokenizar reusa la normalización del léxico —minúsculas, sin acentos, sin
// puntuación— para que el índice y el clasificador partan el texto igual. Que
// "cocaína" y "cocaina" fueran tokens distintos en una capa y no en la otra
// sería una fuente de bugs silenciosos.
func tokenizar(s string) []string {
	return lexicon.Tokenizar(s)
}
