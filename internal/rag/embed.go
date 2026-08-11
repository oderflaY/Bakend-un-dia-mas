package rag

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"sync"
	"time"
)

// ModeloPorDefecto es el modelo de embeddings de Gemini. Es un modelo distinto
// del de chat y se factura aparte; por eso tiene su propia variable de entorno.
const ModeloPorDefecto = "text-embedding-004"

const (
	// loteMaximo: batchEmbedContents acepta 100 peticiones por llamada.
	loteMaximo = 100

	// cacheTTL y cacheMax acotan la caché de consultas. La misma persona escribe
	// "tengo muchas ganas de fumar" varias veces en una misma tarde mala, y cada
	// una era una llamada de red dentro del tiempo de respuesta del chat.
	cacheTTL = 15 * time.Minute
	cacheMax = 512
)

// GeminiEmbedder implementa Embedder contra la API REST de Gemini.
type GeminiEmbedder struct {
	APIKey string
	Model  string
	HTTP   *http.Client

	mu    sync.Mutex
	cache map[string]entradaCache
}

type entradaCache struct {
	vec   []float32
	hasta time.Time
}

func NewGeminiEmbedder(apiKey, model string) *GeminiEmbedder {
	if model == "" {
		model = ModeloPorDefecto
	}
	return &GeminiEmbedder{
		APIKey: apiKey,
		Model:  model,
		// Más corto que el del chat: si embeber tarda diez segundos, el RAG ya
		// no vale lo que cuesta y es mejor responder sin material.
		HTTP:  &http.Client{Timeout: 10 * time.Second},
		cache: map[string]entradaCache{},
	}
}

type contenido struct {
	Parts []parte `json:"parts"`
}

type parte struct {
	Text string `json:"text"`
}

type peticionEmbed struct {
	Model    string    `json:"model"`
	Content  contenido `json:"content"`
	TaskType string    `json:"taskType"`
}

type respuestaEmbed struct {
	Embedding struct {
		Values []float32 `json:"values"`
	} `json:"embedding"`
}

type peticionLote struct {
	Requests []peticionEmbed `json:"requests"`
}

type respuestaLote struct {
	Embeddings []struct {
		Values []float32 `json:"values"`
	} `json:"embeddings"`
}

// EmbedDocs embebe el corpus. taskType RETRIEVAL_DOCUMENT y RETRIEVAL_QUERY no
// son adorno: Gemini proyecta pregunta y documento a espacios pensados para
// compararse entre sí, y usar el mismo tipo para los dos empeora el ranking.
func (e *GeminiEmbedder) EmbedDocs(ctx context.Context, textos []string) ([][]float32, error) {
	out := make([][]float32, 0, len(textos))
	for inicio := 0; inicio < len(textos); inicio += loteMaximo {
		fin := min(inicio+loteMaximo, len(textos))

		lote := peticionLote{Requests: make([]peticionEmbed, 0, fin-inicio)}
		for _, t := range textos[inicio:fin] {
			lote.Requests = append(lote.Requests, peticionEmbed{
				Model:    "models/" + e.Model,
				Content:  contenido{Parts: []parte{{Text: t}}},
				TaskType: "RETRIEVAL_DOCUMENT",
			})
		}

		var resp respuestaLote
		if err := e.post(ctx, "batchEmbedContents", lote, &resp); err != nil {
			return nil, err
		}
		if len(resp.Embeddings) != fin-inicio {
			return nil, fmt.Errorf("el lote devolvió %d vectores de %d", len(resp.Embeddings), fin-inicio)
		}
		for _, emb := range resp.Embeddings {
			out = append(out, emb.Values)
		}
	}
	return out, nil
}

func (e *GeminiEmbedder) EmbedQuery(ctx context.Context, texto string) ([]float32, error) {
	if v, ok := e.desdeCache(texto); ok {
		return v, nil
	}

	var resp respuestaEmbed
	err := e.post(ctx, "embedContent", peticionEmbed{
		Model:    "models/" + e.Model,
		Content:  contenido{Parts: []parte{{Text: texto}}},
		TaskType: "RETRIEVAL_QUERY",
	}, &resp)
	if err != nil {
		// Se registra aquí, donde se sabe qué falló, y se devuelve el error para
		// que Retrieve caiga a modo léxico sin romper la respuesta del chat.
		slog.Warn("no se pudo embeber la consulta, el RAG sigue en modo léxico", "err", err)
		return nil, err
	}
	if len(resp.Embedding.Values) == 0 {
		return nil, fmt.Errorf("gemini devolvió un embedding vacío")
	}

	e.guardarEnCache(texto, resp.Embedding.Values)
	return resp.Embedding.Values, nil
}

func (e *GeminiEmbedder) post(ctx context.Context, metodo string, body, out any) error {
	raw, err := json.Marshal(body)
	if err != nil {
		return err
	}
	endpoint := fmt.Sprintf(
		"https://generativelanguage.googleapis.com/v1beta/models/%s:%s",
		url.PathEscape(e.Model), metodo)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(raw))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("x-goog-api-key", e.APIKey)

	resp, err := e.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		var buf bytes.Buffer
		_, _ = buf.ReadFrom(resp.Body)
		return fmt.Errorf("gemini embeddings respondió %d: %s", resp.StatusCode, buf.String())
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

func (e *GeminiEmbedder) desdeCache(texto string) ([]float32, bool) {
	e.mu.Lock()
	defer e.mu.Unlock()
	ent, ok := e.cache[texto]
	if !ok || time.Now().After(ent.hasta) {
		return nil, false
	}
	return ent.vec, true
}

// guardarEnCache purga por completo al llegar al tope en vez de llevar un LRU.
// Con 512 consultas y quince minutos, vaciar de golpe cuesta una llamada extra
// de vez en cuando y ahorra una estructura que habría que mantener.
func (e *GeminiEmbedder) guardarEnCache(texto string, vec []float32) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if len(e.cache) >= cacheMax {
		e.cache = map[string]entradaCache{}
	}
	e.cache[texto] = entradaCache{vec: vec, hasta: time.Now().Add(cacheTTL)}
}
