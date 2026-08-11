// Package analysis conecta el clasificador de texto con el semáforo.
//
// Es el único sitio donde una entrada de diario puede cambiar el estado de
// alguien, y por eso concentra las tres decisiones delicadas:
//
//  1. El análisis **solo sube** el semáforo, nunca lo baja. Un texto tranquilo
//     escrito en medio de una crisis no puede apagar un rojo; bajar de nivel se
//     hace con un check-in explícito, que es un acto consciente de la persona.
//  2. El texto del diario **no sale de aquí**. Lo que se guarda como motivo del
//     cambio son los nombres de las categorías ("antojo, sustancias"), nunca lo
//     que la persona escribió. El terapeuta ve el semáforo; el diario sigue
//     siendo privado.
//  3. Un fallo del análisis no puede tumbar la escritura del diario. Si algo
//     falla se registra en el log y la entrada queda guardada igual: perder el
//     diario de alguien por un error del clasificador sería peor que no
//     clasificar.
//
// El resultado del análisis vuelve a la app en la **misma respuesta** de la
// escritura (ver Outcome). Antes solo se guardaba y salía por SSE, lo que
// obligaba a la app a escribir, esperar el evento y releer el semáforo para
// saber qué había pasado con lo que su usuario acababa de escribir.
//
// El material de apoyo que acompaña al veredicto lo recupera internal/rag en
// **modo local**: BM25 en proceso, sin embeddings y sin red. El diario no sale
// de este servidor.
package analysis

import (
	"context"
	"log/slog"
	"net/http"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/auth"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/httpx"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/lexicon"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/rag"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/risk"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/trafficlight"
)

// maxTexto acota lo que se analiza. Una entrada de diario más larga que esto se
// analiza truncada: el clasificador es lineal, pero no hay razón para gastar
// tiempo de petición en las últimas diez mil palabras.
const maxTexto = 20000

// maxApoyo: cuántos pasajes de apoyo se devuelven. Es una tarjeta en la app, no
// un manual; tres ya es más de lo que alguien lee en un mal momento.
const maxApoyo = 3

type Service struct {
	lex    *lexicon.Lexicon
	lights *trafficlight.Service
	rag    Retriever // opcional: sin él el veredicto viaja sin material
}

// Retriever es la parte del RAG que este paquete usa. Solo el modo local, el que
// no toca la red: el diario no se manda a ningún tercero.
type Retriever interface {
	RetrieveLocal(consulta string, level risk.Level) []rag.Hit
}

func NewService(lex *lexicon.Lexicon, lights *trafficlight.Service, ret Retriever) *Service {
	return &Service{lex: lex, lights: lights, rag: ret}
}

// Apoyo es un pasaje del corpus, ya listo para pintarse en la app.
type Apoyo struct {
	ID     string `json:"id"`
	Titulo string `json:"titulo"`
	Texto  string `json:"texto"`
	Fuente string `json:"fuente"`
}

// Outcome es el veredicto que la app recibe en la respuesta de la escritura.
//
// Lleva dos niveles a propósito y no uno: `Nivel` es lo que dice el texto que se
// acaba de escribir, y `Semaforo` es el estado en que quedó la persona. Pueden
// diferir, porque el análisis solo sube: si alguien está en rojo y escribe algo
// tranquilo, Nivel es verde y Semaforo sigue rojo. Devolver solo uno de los dos
// haría que la app mintiera en uno de los dos sentidos.
type Outcome struct {
	Nivel      risk.Level  `json:"nivel"`
	Semaforo   *risk.Level `json:"semaforo"` // null si no se pudo leer
	Subio      bool        `json:"subioElSemaforo"`
	Score      float64     `json:"score"`
	Categorias []string    `json:"categorias"`
	Resumen    string      `json:"resumen"`
	Acciones   []string    `json:"acciones"`
	Apoyo      []Apoyo     `json:"apoyo"`
}

// Analyze es el análisis puro, sin efectos: lo usa la ruta de prueba y lo usan
// los tests.
func (s *Service) Analyze(texto string) lexicon.Result {
	if len(texto) > maxTexto {
		texto = texto[:maxTexto]
	}
	return s.lex.Analyze(texto)
}

// Preview es el veredicto sin efectos: puntúa y recupera apoyo, pero no toca la
// base ni el semáforo. Es lo que responde POST /v1/analysis/text.
func (s *Service) Preview(texto string) Outcome {
	res := s.Analyze(texto)
	return Outcome{
		Nivel:      res.Level,
		Score:      res.Score,
		Categorias: res.Categorias,
		Resumen:    res.Resumen,
		Acciones:   res.Acciones,
		Apoyo:      s.apoyo(texto, res.Level),
	}
}

// apoyo recupera el material del corpus para este texto. Va en modo local: ni
// una palabra del diario sale del proceso.
func (s *Service) apoyo(texto string, level risk.Level) []Apoyo {
	if s.rag == nil {
		return []Apoyo{}
	}
	hits := s.rag.RetrieveLocal(texto, level)
	if len(hits) > maxApoyo {
		hits = hits[:maxApoyo]
	}
	out := make([]Apoyo, 0, len(hits))
	for _, h := range hits {
		out = append(out, Apoyo{ID: h.ID, Titulo: h.Titulo, Texto: h.Texto, Fuente: h.Fuente})
	}
	return out
}

// OnText analiza y, si procede, registra el cambio de semáforo. Se llama
// síncrono con la escritura que lo origina, igual que el protocolo de emergencia
// del check-in: un rojo no puede quedar pendiente de una goroutine que nadie
// vigila.
//
// `fuente` es "diario" o "animo": aparece en el motivo para que la persona sepa
// de dónde salió el cambio.
// El Outcome que devuelve es lo que la app pinta al terminar de escribir. Un
// error al registrar el semáforo no anula el veredicto: el texto se analizó
// igual y la persona merece ver el resultado aunque la escritura del log falle.
func (s *Service) OnText(ctx context.Context, userID, fuente, texto string) Outcome {
	res := s.Analyze(texto)
	out := Outcome{
		Nivel:      res.Level,
		Score:      res.Score,
		Categorias: res.Categorias,
		Resumen:    res.Resumen,
		Acciones:   res.Acciones,
		Apoyo:      s.apoyo(texto, res.Level),
	}

	// Se lee el semáforo vigente incluso cuando el texto sale verde: es una
	// lectura por clave primaria, y sin ella la app no podría decir "tu semáforo
	// sigue en amarillo" después de una entrada tranquila.
	actual, err := s.lights.Current(ctx, userID)
	if err != nil {
		slog.Error("no se pudo leer el semáforo actual", "userId", userID, "err", err)
		return out
	}
	out.Semaforo = &actual

	// Solo hacia arriba. Ver la decisión 1 del comentario del paquete.
	if res.Level <= actual {
		return out
	}

	_, err = s.lights.Record(ctx, userID, trafficlight.Evaluation{
		Status: res.Level,
		// Categorías, no texto: el motivo lo puede leer un terapeuta.
		Reason:           fuente + ": " + res.Resumen,
		SuggestedActions: res.Acciones,
	}, "Señales detectadas en tu "+fuente)
	if err != nil {
		slog.Error("no se pudo registrar el semáforo del análisis",
			"userId", userID, "fuente", fuente, "err", err)
		return out
	}

	out.Semaforo = &res.Level
	out.Subio = true
	return out
}

// OnJournal y OnMood son los enganches que se pasan a esos paquetes, para que no
// tengan que conocer este. Devuelven `any` —y no *Outcome— por lo mismo: así la
// firma del enganche no obliga a journal ni a mood a importar este paquete, y el
// veredicto viaja hasta la respuesta HTTP como un campo más del JSON.
func (s *Service) OnJournal(ctx context.Context, userID, texto string) any {
	return s.OnText(ctx, userID, "diario", texto)
}

func (s *Service) OnMood(ctx context.Context, userID, texto string) any {
	return s.OnText(ctx, userID, "animo", texto)
}

type Handler struct {
	svc    *Service
	issuer *auth.TokenIssuer
}

func NewHandler(svc *Service, issuer *auth.TokenIssuer) *Handler {
	return &Handler{svc: svc, issuer: issuer}
}

func (h *Handler) Routes(mux *http.ServeMux) {
	mux.Handle("POST /v1/analysis/text", h.issuer.Middleware(http.HandlerFunc(h.analyze)))
}

// analyze es una prueba en seco: puntúa el texto y no guarda nada. Sirve para
// que la app pueda avisar "esto que escribiste va a poner tu semáforo en
// amarillo" antes de guardar, y para ajustar pesos sin ensuciar datos reales.
func (h *Handler) analyze(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Text string `json:"text"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}
	if in.Text == "" {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "texto vacío")
		return
	}
	httpx.JSON(w, http.StatusOK, h.svc.Preview(in.Text))
}
