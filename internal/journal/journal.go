// Package journal es el diario personal: una porción vertical calcada de
// internal/checkins, con la única diferencia de que aquí sí se puede borrar.
//
// El diario es lo más íntimo que guarda la app, así que un usuario tiene que
// poder retirar lo que escribió; en Firestore el borrado estaba prohibido por
// regla y una entrada era para siempre.
package journal

import (
	"context"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/auth"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/httpx"
)

// maxContent es generoso a propósito: una entrada de diario es texto largo. El
// tope real de la petición lo pone httpx.Decode (1 MiB).
const maxContent = 20000

type Entry struct {
	ID        string    `json:"id"`
	Content   string    `json:"content"`
	CreatedAt time.Time `json:"createdAt"`
}

type Store struct{ db *pgxpool.Pool }

func NewStore(db *pgxpool.Pool) *Store { return &Store{db: db} }

func (s *Store) Create(ctx context.Context, userID, content string) (Entry, error) {
	e := Entry{Content: content}
	err := s.db.QueryRow(ctx, `
		INSERT INTO journal_entries (user_id, content)
		VALUES ($1, $2)
		RETURNING id, created_at`, userID, content).Scan(&e.ID, &e.CreatedAt)
	return e, err
}

func (s *Store) List(ctx context.Context, userID string, limit int) ([]Entry, error) {
	rows, err := s.db.Query(ctx, `
		SELECT id, content, created_at
		FROM journal_entries
		WHERE user_id = $1
		ORDER BY created_at DESC
		LIMIT $2`, userID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []Entry{}
	for rows.Next() {
		var e Entry
		if err := rows.Scan(&e.ID, &e.Content, &e.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

func (s *Store) ByID(ctx context.Context, userID, id string) (Entry, error) {
	var e Entry
	err := s.db.QueryRow(ctx, `
		SELECT id, content, created_at
		FROM journal_entries WHERE id = $1 AND user_id = $2`, id, userID).
		Scan(&e.ID, &e.Content, &e.CreatedAt)
	return e, err
}

// Delete lleva el user_id en el WHERE, así que borrar una entrada ajena no
// borra nada y se responde 404: no confirma que el id exista.
func (s *Store) Delete(ctx context.Context, userID, id string) error {
	tag, err := s.db.Exec(ctx, `
		DELETE FROM journal_entries WHERE id = $1 AND user_id = $2`, id, userID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return pgx.ErrNoRows
	}
	return nil
}

type Handler struct {
	store  *Store
	issuer *auth.TokenIssuer
}

func NewHandler(store *Store, issuer *auth.TokenIssuer) *Handler {
	return &Handler{store: store, issuer: issuer}
}

func (h *Handler) Routes(mux *http.ServeMux) {
	mux.Handle("POST /v1/journal", h.issuer.Middleware(http.HandlerFunc(h.create)))
	mux.Handle("GET /v1/journal", h.issuer.Middleware(http.HandlerFunc(h.list)))
	mux.Handle("GET /v1/journal/{id}", h.issuer.Middleware(http.HandlerFunc(h.get)))
	mux.Handle("DELETE /v1/journal/{id}", h.issuer.Middleware(http.HandlerFunc(h.delete)))
}

func (h *Handler) create(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())

	var in struct {
		Content string `json:"content"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}
	in.Content = strings.TrimSpace(in.Content)
	if in.Content == "" {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "la entrada no puede estar vacía")
		return
	}
	if len([]rune(in.Content)) > maxContent {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "la entrada es demasiado larga")
		return
	}

	e, err := h.store.Create(r.Context(), id.UserID, in.Content)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo guardar la entrada")
		return
	}
	httpx.JSON(w, http.StatusCreated, e)
}

func (h *Handler) list(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())
	items, err := h.store.List(r.Context(), id.UserID, httpx.Limit(r, 20, 100))
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo leer el diario")
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"items": items})
}

func (h *Handler) get(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())
	e, err := h.store.ByID(r.Context(), id.UserID, r.PathValue("id"))
	if httpx.IsNotFound(err) {
		httpx.Error(w, http.StatusNotFound, "not-found", "no existe esa entrada")
		return
	}
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo leer la entrada")
		return
	}
	httpx.JSON(w, http.StatusOK, e)
}

func (h *Handler) delete(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())
	err := h.store.Delete(r.Context(), id.UserID, r.PathValue("id"))
	if httpx.IsNotFound(err) {
		httpx.Error(w, http.StatusNotFound, "not-found", "no existe esa entrada")
		return
	}
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo borrar la entrada")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
