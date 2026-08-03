// Package users expone el perfil y los contactos de emergencia.
//
// El contacto de confianza (position = 0) es lo que lee el protocolo de
// emergencia, así que esta parte no es cosmética: sin contactos, una alerta roja
// llega sin a quién avisar.
package users

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/auth"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/httpx"
)

type Profile struct {
	ID              string    `json:"id"`
	Email           string    `json:"email"`
	DisplayName     string    `json:"displayName"`
	Role            auth.Role `json:"role"`
	PorQuePersonal  string    `json:"porQuePersonal"`
	RecordRachaSecs int64     `json:"recordRachaSegundos"`
	Contacts        []Contact `json:"contactosEmergencia"`
	CreatedAt       time.Time `json:"createdAt"`
}

type Contact struct {
	Nombre   string `json:"nombre"`
	Telefono string `json:"telefono"`
	Rol      string `json:"rol"`
}

type Store struct{ db *pgxpool.Pool }

func NewStore(db *pgxpool.Pool) *Store { return &Store{db: db} }

func (s *Store) Profile(ctx context.Context, userID string) (Profile, error) {
	var p Profile
	err := s.db.QueryRow(ctx, `
		SELECT id, email, display_name, role, por_que_personal, record_racha_secs, created_at
		FROM users WHERE id = $1`, userID).
		Scan(&p.ID, &p.Email, &p.DisplayName, &p.Role,
			&p.PorQuePersonal, &p.RecordRachaSecs, &p.CreatedAt)
	if err != nil {
		return Profile{}, err
	}

	p.Contacts, err = s.Contacts(ctx, userID)
	return p, err
}

func (s *Store) Contacts(ctx context.Context, userID string) ([]Contact, error) {
	rows, err := s.db.Query(ctx, `
		SELECT nombre, telefono, rol
		FROM emergency_contacts
		WHERE user_id = $1
		ORDER BY position ASC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []Contact{}
	for rows.Next() {
		var c Contact
		if err := rows.Scan(&c.Nombre, &c.Telefono, &c.Rol); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// UpdateProfile solo toca los campos presentes en la petición. `role` no está
// entre ellos por construcción: es el equivalente de noTocaRol().
func (s *Store) UpdateProfile(ctx context.Context, userID string, displayName, porQue *string) error {
	_, err := s.db.Exec(ctx, `
		UPDATE users SET
			display_name     = COALESCE($2, display_name),
			por_que_personal = COALESCE($3, por_que_personal)
		WHERE id = $1`, userID, displayName, porQue)
	return err
}

// ReplaceContacts reescribe la lista completa. El orden del array define la
// prioridad, y el primero es el contacto de confianza.
func (s *Store) ReplaceContacts(ctx context.Context, userID string, contacts []Contact) error {
	tx, err := s.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op tras commit

	if _, err := tx.Exec(ctx, `DELETE FROM emergency_contacts WHERE user_id = $1`, userID); err != nil {
		return err
	}
	for i, c := range contacts {
		if _, err := tx.Exec(ctx, `
			INSERT INTO emergency_contacts (user_id, position, nombre, telefono, rol)
			VALUES ($1, $2, $3, $4, $5)`, userID, i, c.Nombre, c.Telefono, c.Rol); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

type Handler struct {
	store  *Store
	issuer *auth.TokenIssuer
}

func NewHandler(store *Store, issuer *auth.TokenIssuer) *Handler {
	return &Handler{store: store, issuer: issuer}
}

func (h *Handler) Routes(mux *http.ServeMux) {
	mux.Handle("GET /v1/users/me", h.issuer.Middleware(http.HandlerFunc(h.me)))
	mux.Handle("PATCH /v1/users/me", h.issuer.Middleware(http.HandlerFunc(h.patch)))
	mux.Handle("PUT /v1/users/me/emergency-contacts", h.issuer.Middleware(http.HandlerFunc(h.putContacts)))
}

func (h *Handler) me(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())
	p, err := h.store.Profile(r.Context(), id.UserID)
	if errors.Is(err, pgx.ErrNoRows) {
		httpx.Error(w, http.StatusNotFound, "not-found", "el perfil no existe")
		return
	}
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo leer el perfil")
		return
	}
	httpx.JSON(w, http.StatusOK, p)
}

func (h *Handler) patch(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())

	var in struct {
		DisplayName    *string `json:"displayName"`
		PorQuePersonal *string `json:"porQuePersonal"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}
	if err := h.store.UpdateProfile(r.Context(), id.UserID, in.DisplayName, in.PorQuePersonal); err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo actualizar el perfil")
		return
	}
	h.me(w, r)
}

func (h *Handler) putContacts(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())

	var in struct {
		Contactos []Contact `json:"contactosEmergencia"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}
	for i, c := range in.Contactos {
		if c.Nombre == "" || c.Telefono == "" {
			httpx.Error(w, http.StatusBadRequest, "invalid-argument",
				"cada contacto necesita nombre y teléfono (posición "+strconv.Itoa(i)+")")
			return
		}
	}
	if err := h.store.ReplaceContacts(r.Context(), id.UserID, in.Contactos); err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudieron guardar los contactos")
		return
	}

	contacts, err := h.store.Contacts(r.Context(), id.UserID)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudieron leer los contactos")
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"contactosEmergencia": contacts})
}
