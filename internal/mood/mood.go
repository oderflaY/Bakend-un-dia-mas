// Package mood registra el estado de ánimo. Es el recurso más pequeño de todos:
// una etiqueta y una fecha.
//
// El vocabulario se normaliza a mayúsculas al entrar, igual que hace risk con el
// semáforo. La razón es la misma: si "feliz" y "FELIZ" entran como valores
// distintos, cualquier agregación posterior (internal/stats) miente.
package mood

import (
	"context"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/auth"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/httpx"
)

// Default es lo que se guarda cuando la app manda el campo vacío; coincide con
// el DEFAULT de la columna, que era el que ya usaba el modelo anterior.
const Default = "NEUTRAL"

const maxMoodRunes = 40

type Log struct {
	ID        string    `json:"id"`
	Mood      string    `json:"mood"`
	CreatedAt time.Time `json:"createdAt"`
}

type Store struct{ db *pgxpool.Pool }

func NewStore(db *pgxpool.Pool) *Store { return &Store{db: db} }

func (s *Store) Create(ctx context.Context, userID, mood string) (Log, error) {
	l := Log{Mood: mood}
	err := s.db.QueryRow(ctx, `
		INSERT INTO mood_logs (user_id, mood)
		VALUES ($1, $2)
		RETURNING id, created_at`, userID, mood).Scan(&l.ID, &l.CreatedAt)
	return l, err
}

func (s *Store) List(ctx context.Context, userID string, limit int) ([]Log, error) {
	rows, err := s.db.Query(ctx, `
		SELECT id, mood, created_at
		FROM mood_logs
		WHERE user_id = $1
		ORDER BY created_at DESC
		LIMIT $2`, userID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []Log{}
	for rows.Next() {
		var l Log
		if err := rows.Scan(&l.ID, &l.Mood, &l.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, l)
	}
	return out, rows.Err()
}

// Normalize deja el valor en la forma canónica. Exportada porque internal/stats
// agrupa por esta misma clave.
func Normalize(s string) string {
	s = strings.ToUpper(strings.TrimSpace(s))
	if s == "" {
		return Default
	}
	return s
}

type Handler struct {
	store  *Store
	issuer *auth.TokenIssuer
}

func NewHandler(store *Store, issuer *auth.TokenIssuer) *Handler {
	return &Handler{store: store, issuer: issuer}
}

func (h *Handler) Routes(mux *http.ServeMux) {
	mux.Handle("POST /v1/mood-logs", h.issuer.Middleware(http.HandlerFunc(h.create)))
	mux.Handle("GET /v1/mood-logs", h.issuer.Middleware(http.HandlerFunc(h.list)))
}

func (h *Handler) create(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())

	var in struct {
		Mood string `json:"mood"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}
	mood := Normalize(in.Mood)
	if len([]rune(mood)) > maxMoodRunes {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "la etiqueta de ánimo es demasiado larga")
		return
	}

	l, err := h.store.Create(r.Context(), id.UserID, mood)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo guardar el ánimo")
		return
	}
	httpx.JSON(w, http.StatusCreated, l)
}

func (h *Handler) list(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())
	items, err := h.store.List(r.Context(), id.UserID, httpx.Limit(r, 30, 200))
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudieron leer los ánimos")
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"items": items})
}
