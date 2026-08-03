// Package checkins implementa la porción vertical de referencia: modelo, store
// y handler. El resto de recursos (journal, mood, alerts, relapses) se calcan
// de aquí cambiando tabla y campos.
package checkins

import (
	"context"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/auth"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/httpx"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/risk"
)

type CheckIn struct {
	ID           string            `json:"id"`
	RiskLevel    risk.Level        `json:"riskLevel"`
	CravingLevel int               `json:"cravingLevel"`
	Mood         string            `json:"mood"`
	Triggers     []string          `json:"triggers"`
	Note         string            `json:"note"`
	Answers      map[string]string `json:"answers"`
	CreatedAt    time.Time         `json:"createdAt"`
}

type Store struct{ db *pgxpool.Pool }

func NewStore(db *pgxpool.Pool) *Store { return &Store{db: db} }

func (s *Store) Create(ctx context.Context, userID string, c CheckIn) (CheckIn, error) {
	// triggers y answers son NOT NULL en el esquema: nil se traduciría a NULL.
	if c.Triggers == nil {
		c.Triggers = []string{}
	}
	if c.Answers == nil {
		c.Answers = map[string]string{}
	}

	err := s.db.QueryRow(ctx, `
		INSERT INTO check_ins (user_id, risk_level, craving_level, mood, triggers, note, answers)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
		RETURNING id, created_at`,
		userID, c.RiskLevel.Code(), c.CravingLevel, c.Mood, c.Triggers, c.Note, c.Answers,
	).Scan(&c.ID, &c.CreatedAt)
	return c, err
}

// List filtra siempre por user_id y pagina en la query, no en memoria (#14).
func (s *Store) List(ctx context.Context, userID string, limit int) ([]CheckIn, error) {
	rows, err := s.db.Query(ctx, `
		SELECT id, risk_level, craving_level, mood, triggers, note, answers, created_at
		FROM check_ins
		WHERE user_id = $1
		ORDER BY created_at DESC
		LIMIT $2`, userID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := make([]CheckIn, 0, limit)
	for rows.Next() {
		var c CheckIn
		var code string
		if err := rows.Scan(&c.ID, &code, &c.CravingLevel, &c.Mood,
			&c.Triggers, &c.Note, &c.Answers, &c.CreatedAt); err != nil {
			return nil, err
		}
		c.RiskLevel = risk.Parse(code)
		out = append(out, c)
	}
	return out, rows.Err()
}

// Latest da el contexto de riesgo vigente para el chat con el asistente.
func (s *Store) Latest(ctx context.Context, userID string) (CheckIn, bool, error) {
	list, err := s.List(ctx, userID, 1)
	if err != nil || len(list) == 0 {
		return CheckIn{}, false, err
	}
	return list[0], true, nil
}

func (s *Store) ByID(ctx context.Context, userID, id string) (CheckIn, error) {
	var c CheckIn
	var code string
	err := s.db.QueryRow(ctx, `
		SELECT id, risk_level, craving_level, mood, triggers, note, answers, created_at
		FROM check_ins WHERE id = $1 AND user_id = $2`, id, userID).
		Scan(&c.ID, &code, &c.CravingLevel, &c.Mood, &c.Triggers, &c.Note, &c.Answers, &c.CreatedAt)
	if err != nil {
		return CheckIn{}, err
	}
	c.RiskLevel = risk.Parse(code)
	return c, nil
}

type Handler struct {
	store  *Store
	onRed  func(ctx context.Context, userID string, c CheckIn)
	issuer *auth.TokenIssuer
}

// NewHandler recibe onRed: el protocolo de emergencia. Es el reemplazo directo
// del trigger onCheckInCreated, pero síncrono con la escritura, así que ya no
// puede "salir en silencio" sin que nadie se entere.
func NewHandler(store *Store, issuer *auth.TokenIssuer, onRed func(context.Context, string, CheckIn)) *Handler {
	return &Handler{store: store, issuer: issuer, onRed: onRed}
}

func (h *Handler) Routes(mux *http.ServeMux) {
	mux.Handle("POST /v1/check-ins", h.issuer.Middleware(http.HandlerFunc(h.create)))
	mux.Handle("GET /v1/check-ins", h.issuer.Middleware(http.HandlerFunc(h.list)))
	mux.Handle("GET /v1/check-ins/{id}", h.issuer.Middleware(http.HandlerFunc(h.get)))
}

func (h *Handler) create(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())

	var in struct {
		RiskLevel    string            `json:"riskLevel"`
		CravingLevel int               `json:"cravingLevel"`
		Mood         string            `json:"mood"`
		Triggers     []string          `json:"triggers"`
		Note         string            `json:"note"`
		Answers      map[string]string `json:"answers"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}
	if in.CravingLevel < 0 || in.CravingLevel > 10 {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "cravingLevel debe estar entre 0 y 10")
		return
	}
	if in.Triggers == nil {
		in.Triggers = []string{}
	}
	if in.Answers == nil {
		in.Answers = map[string]string{}
	}

	c, err := h.store.Create(r.Context(), id.UserID, CheckIn{
		RiskLevel:    risk.Parse(in.RiskLevel),
		CravingLevel: in.CravingLevel,
		Mood:         in.Mood,
		Triggers:     in.Triggers,
		Note:         in.Note,
		Answers:      in.Answers,
	})
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo guardar el check-in")
		return
	}

	if c.RiskLevel == risk.Red && h.onRed != nil {
		h.onRed(r.Context(), id.UserID, c)
	}
	httpx.JSON(w, http.StatusCreated, c)
}

func (h *Handler) list(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())
	list, err := h.store.List(r.Context(), id.UserID, httpx.Limit(r, 20, 100))
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudieron leer los check-ins")
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"items": list})
}

func (h *Handler) get(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())
	c, err := h.store.ByID(r.Context(), id.UserID, r.PathValue("id"))
	if httpx.IsNotFound(err) {
		// 404 y no 403: pedir un id ajeno no confirma que exista.
		httpx.Error(w, http.StatusNotFound, "not-found", "no existe ese check-in")
		return
	}
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo leer el check-in")
		return
	}
	httpx.JSON(w, http.StatusOK, c)
}
