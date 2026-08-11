package ai

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/alerts"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/auth"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/checkins"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/httpx"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/risk"
)

const (
	maxPromptRunes  = 4000 // #10: sin tope, un cliente podía quemar tokens a placer
	maxHistoryTurns = 20
)

type Handler struct {
	db        *pgxpool.Pool
	client    Client
	checkIns  *checkins.Store
	alerts    *alerts.Service
	issuer    *auth.TokenIssuer
	retriever Retriever // puede ser nil: ver el comentario de Retriever
	limiter   *rateLimiter
}

func NewHandler(db *pgxpool.Pool, client Client, checkIns *checkins.Store,
	alertSvc *alerts.Service, issuer *auth.TokenIssuer, retriever Retriever) *Handler {
	return &Handler{
		db:        db,
		client:    client,
		checkIns:  checkIns,
		alerts:    alertSvc,
		issuer:    issuer,
		retriever: retriever,
		limiter:   newRateLimiter(20, time.Minute),
	}
}

func (h *Handler) Routes(mux *http.ServeMux) {
	mux.Handle("POST /v1/ai/chat", h.issuer.Middleware(http.HandlerFunc(h.chat)))
	mux.Handle("GET /v1/ai/messages", h.issuer.Middleware(http.HandlerFunc(h.messages)))
	mux.Handle("POST /v1/ai/retrieve", h.issuer.Middleware(http.HandlerFunc(h.retrieve)))
}

// retrieve es la ruta de diagnóstico del RAG: devuelve qué pasajes se habrían
// inyectado y con qué puntuación, sin llamar al modelo ni guardar nada. Es lo
// que permite ajustar pesos y corpus mirando consultas reales; sin ella, el
// ranking solo se puede evaluar leyendo respuestas del chat, que es justo donde
// no se distingue un buen retrieval de un buen modelo tapando un mal retrieval.
func (h *Handler) retrieve(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())

	var in struct {
		Query string `json:"query"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}
	if in.Query == "" {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "query vacía")
		return
	}
	searcher, ok := h.retriever.(Searcher)
	if !ok || searcher == nil {
		httpx.Error(w, http.StatusServiceUnavailable, "rag-unavailable", "el recuperador no está disponible")
		return
	}

	// El nivel sale del check-in, igual que en el chat: la app no elige contra
	// qué semáforo se filtra el material.
	level := risk.Green
	if latest, ok, err := h.checkIns.Latest(r.Context(), id.UserID); err == nil && ok {
		level = latest.RiskLevel
	}
	httpx.JSON(w, http.StatusOK, map[string]any{
		"riskLevel": level,
		"items":     searcher.Retrieve(r.Context(), in.Query, level),
	})
}

func (h *Handler) chat(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())

	if !h.limiter.allow(id.UserID) {
		httpx.Error(w, http.StatusTooManyRequests, "rate-limited", "demasiados mensajes seguidos, espera un momento")
		return
	}

	var in struct {
		Prompt string `json:"prompt"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}
	if in.Prompt == "" {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "prompt vacío")
		return
	}
	if len([]rune(in.Prompt)) > maxPromptRunes {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "el mensaje es demasiado largo")
		return
	}

	// Ni el riesgo ni el historial vienen del cliente: se leen de la base con el
	// uid del token. El cliente ya no puede mentir sobre su semáforo (#2), y el
	// historial sobrevive a reinstalaciones porque no vive en el dispositivo.
	level := risk.Green
	if latest, ok, err := h.checkIns.Latest(r.Context(), id.UserID); err == nil && ok {
		level = latest.RiskLevel
	}

	history, err := h.loadHistory(r.Context(), id.UserID, maxHistoryTurns)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo leer la conversación")
		return
	}

	if err := h.saveMessage(r.Context(), id.UserID, "user", in.Prompt, level); err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo guardar el mensaje")
		return
	}

	res, err := RunTurn(r.Context(), h.client, h, h.retriever, id.UserID, level, history, in.Prompt)
	if err != nil {
		slog.Error("fallo el turno del agente", "userId", id.UserID, "err", err)
		httpx.Error(w, http.StatusBadGateway, "ai-unavailable", "el asistente no está disponible ahora mismo")
		return
	}

	if err := h.saveMessage(r.Context(), id.UserID, "assistant", res.Reply, level); err != nil {
		slog.Error("no se guardó la respuesta del asistente", "userId", id.UserID, "err", err)
	}

	httpx.JSON(w, http.StatusOK, map[string]any{
		"reply":     res.Reply,
		"riskLevel": level,
		"alertIds":  res.SavedAlertIDs,
	})
}

func (h *Handler) messages(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())
	rows, err := h.db.Query(r.Context(), `
		SELECT role, content, created_at
		FROM ai_messages
		WHERE user_id = $1
		ORDER BY created_at DESC
		LIMIT $2`, id.UserID, httpx.Limit(r, 50, 200))
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudieron leer los mensajes")
		return
	}
	defer rows.Close()

	type message struct {
		Role      string    `json:"role"`
		Content   string    `json:"content"`
		CreatedAt time.Time `json:"createdAt"`
	}
	items := []message{}
	for rows.Next() {
		var m message
		if err := rows.Scan(&m.Role, &m.Content, &m.CreatedAt); err != nil {
			httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudieron leer los mensajes")
			return
		}
		items = append(items, m)
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"items": items})
}

func (h *Handler) loadHistory(ctx context.Context, userID string, limit int) ([]Content, error) {
	rows, err := h.db.Query(ctx, `
		SELECT role, content FROM (
			SELECT role, content, created_at
			FROM ai_messages
			WHERE user_id = $1
			ORDER BY created_at DESC
			LIMIT $2
		) t ORDER BY created_at ASC`, userID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []Content
	for rows.Next() {
		var role, content string
		if err := rows.Scan(&role, &content); err != nil {
			return nil, err
		}
		if role == "assistant" {
			role = "model"
		}
		out = append(out, Content{Role: role, Parts: []Part{{Text: content}}})
	}
	return out, rows.Err()
}

func (h *Handler) saveMessage(ctx context.Context, userID, role, content string, level risk.Level) error {
	_, err := h.db.Exec(ctx, `
		INSERT INTO ai_messages (user_id, role, content, risk_level_context)
		VALUES ($1, $2, $3, $4)`, userID, role, content, level.Code())
	return err
}

// Run implementa ToolRunner. El userID viene del token, jamás de los args.
func (h *Handler) Run(ctx context.Context, userID, name string, args map[string]any) (map[string]any, error) {
	switch name {
	case "leer_historial_reciente":
		limit := 5
		if v, ok := args["limite"].(float64); ok && v >= 1 && v <= 10 {
			limit = int(v)
		}
		list, err := h.checkIns.List(ctx, userID, limit)
		if err != nil {
			return nil, err
		}
		items := make([]map[string]any, 0, len(list))
		for _, c := range list {
			items = append(items, map[string]any{
				"fecha":      c.CreatedAt.Format(time.RFC3339),
				"semaforo":   c.RiskLevel.String(),
				"craving":    c.CravingLevel,
				"animo":      c.Mood,
				"detonantes": c.Triggers,
			})
		}
		return map[string]any{"checkIns": items}, nil

	case "guardar_alerta":
		mensaje, _ := args["mensaje"].(string)
		nivel, _ := args["nivelRiesgo"].(string)
		if mensaje == "" {
			return nil, fmt.Errorf("mensaje vacío")
		}
		// Pasa por alerts.Service y no por un INSERT suelto: así la alerta que
		// levanta el asistente también sale por el canal SSE, con el contacto de
		// confianza. Escrita a mano contra la tabla, se quedaba muda.
		a, err := h.alerts.Raise(ctx, userID, risk.Parse(nivel), mensaje)
		if err != nil {
			return nil, err
		}
		return map[string]any{"alertId": a.ID, "guardada": true}, nil
	}
	return nil, fmt.Errorf("herramienta desconocida: %s", name)
}

// rateLimiter: ventana fija por usuario, en memoria. Suficiente para una sola
// instancia; con varias réplicas hay que moverlo a Redis o a la propia base.
type rateLimiter struct {
	mu     sync.Mutex
	hits   map[string][]time.Time
	limit  int
	window time.Duration
}

func newRateLimiter(limit int, window time.Duration) *rateLimiter {
	return &rateLimiter{hits: map[string][]time.Time{}, limit: limit, window: window}
}

func (l *rateLimiter) allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()

	cutoff := time.Now().Add(-l.window)
	kept := l.hits[key][:0]
	for _, t := range l.hits[key] {
		if t.After(cutoff) {
			kept = append(kept, t)
		}
	}
	if len(kept) >= l.limit {
		l.hits[key] = kept
		return false
	}
	l.hits[key] = append(kept, time.Now())
	return true
}
