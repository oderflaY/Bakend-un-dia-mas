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
	"golang.org/x/crypto/bcrypt"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/addiction"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/auth"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/httpx"
)

type Profile struct {
	ID          string    `json:"id"`
	Email       string    `json:"email"`
	DisplayName string    `json:"displayName"`
	Role        auth.Role `json:"role"`

	// El perfil de recuperación: de qué se está recuperando esta persona.
	// `adicciones` puede traer varias —alcohol y tabaco juntos es el caso más
	// común— y `adiccionPrincipal` es la que rige la racha y el ahorro, que son
	// de una sola cosa.
	Adicciones    []addiction.Type `json:"adicciones"`
	Principal     addiction.Type   `json:"adiccionPrincipal"`
	ConsumoDesde  *string          `json:"consumoDesde"` // YYYY-MM-DD
	EnTratamiento bool             `json:"enTratamiento"`
	Onboarding    bool             `json:"onboardingCompleto"`

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
	var adicciones []string
	var principal string
	var consumoDesde *time.Time
	err := s.db.QueryRow(ctx, `
		SELECT id, email, display_name, role,
		       adicciones, adiccion_principal, consumo_desde, en_tratamiento,
		       por_que_personal, record_racha_secs, created_at
		FROM users WHERE id = $1`, userID).
		Scan(&p.ID, &p.Email, &p.DisplayName, &p.Role,
			&adicciones, &principal, &consumoDesde, &p.EnTratamiento,
			&p.PorQuePersonal, &p.RecordRachaSecs, &p.CreatedAt)
	if err != nil {
		return Profile{}, err
	}
	p.Adicciones = addiction.Types(adicciones)
	p.Principal = addiction.Type(principal)
	p.Onboarding = principal != ""
	if consumoDesde != nil {
		s := consumoDesde.Format(time.DateOnly)
		p.ConsumoDesde = &s
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

// Update son los campos editables del perfil. Todos son punteros porque un
// PATCH distingue "no lo mandes" de "ponlo en vacío", y en `adicciones` esa
// diferencia importa: mandar `[]` es decir "ya no declaro ninguna".
type Update struct {
	DisplayName    *string
	PorQuePersonal *string
	Adicciones     *[]addiction.Type
	Principal      *addiction.Type
	ConsumoDesde   *time.Time
	EnTratamiento  *bool
}

// UpdateProfile solo toca los campos presentes en la petición. `role` no está
// entre ellos por construcción: es el equivalente de noTocaRol(). `email`
// tampoco: cambiarlo es otra operación, con verificación de por medio.
func (s *Store) UpdateProfile(ctx context.Context, userID string, u Update) error {
	var adicciones *[]string
	if u.Adicciones != nil {
		lista := addiction.Strings(*u.Adicciones)
		adicciones = &lista
	}
	var principal *string
	if u.Principal != nil {
		p := string(*u.Principal)
		principal = &p
	}

	_, err := s.db.Exec(ctx, `
		UPDATE users SET
			display_name       = COALESCE($2, display_name),
			por_que_personal   = COALESCE($3, por_que_personal),
			adicciones         = COALESCE($4, adicciones),
			adiccion_principal = COALESCE($5, adiccion_principal),
			consumo_desde      = COALESCE($6, consumo_desde),
			en_tratamiento     = COALESCE($7, en_tratamiento)
		WHERE id = $1`,
		userID, u.DisplayName, u.PorQuePersonal,
		adicciones, principal, u.ConsumoDesde, u.EnTratamiento)
	return err
}

// VerifyPassword confirma que quien pide el borrado es quien dice ser. Vive
// aquí y no en internal/auth para no exponer el hash fuera de su paquete: lo que
// cruza la frontera es un sí o un no.
func (s *Store) VerifyPassword(ctx context.Context, userID, password string) (bool, error) {
	var hash string
	if err := s.db.QueryRow(ctx,
		`SELECT password_hash FROM users WHERE id = $1`, userID).Scan(&hash); err != nil {
		return false, err
	}
	return bcrypt.CompareHashAndPassword([]byte(hash), []byte(password)) == nil, nil
}

// Delete borra la cuenta. Una sola sentencia: la cascada del esquema hace el
// resto. Ver el comentario del handler.
func (s *Store) Delete(ctx context.Context, userID string) error {
	_, err := s.db.Exec(ctx, `DELETE FROM users WHERE id = $1`, userID)
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
	mux.Handle("DELETE /v1/users/me", h.issuer.Middleware(http.HandlerFunc(h.delete)))
	mux.Handle("PUT /v1/users/me/emergency-contacts", h.issuer.Middleware(http.HandlerFunc(h.putContacts)))
}

// delete es el borrado de emergencia: la persona se lleva todo lo suyo.
//
// Es un DELETE de una sola fila en `users`; todo lo demás cae por las FK con
// ON DELETE CASCADE —check-ins, diario, ánimo, semáforo, alertas, recaídas,
// tracker, recordatorios, notas clínicas, y las cuatro tablas de comunidad
// (perfil, historias, "me ayudó" y bloqueos)—. Que la cascada esté en el
// esquema y no en código es lo que hace que añadir una tabla nueva no deje
// huérfanos por olvido.
//
// No hay periodo de gracia ni papelera: alguien que borra su cuenta en una app
// de adicciones suele estar en un momento en el que necesita que desaparezca
// ahora, no en treinta días.
func (h *Handler) delete(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())

	// Se exige la contraseña: el token puede estar en un teléfono que la persona
	// dejó abierto, y esto no se deshace.
	var in struct {
		Password string `json:"password"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}
	if in.Password == "" {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "confirma con tu contraseña")
		return
	}

	ok, err := h.store.VerifyPassword(r.Context(), id.UserID, in.Password)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo borrar la cuenta")
		return
	}
	if !ok {
		httpx.Error(w, http.StatusUnauthorized, "invalid-credentials", "la contraseña no coincide")
		return
	}

	if err := h.store.Delete(r.Context(), id.UserID); err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo borrar la cuenta")
		return
	}
	w.WriteHeader(http.StatusNoContent)
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
		DisplayName    *string   `json:"displayName"`
		PorQuePersonal *string   `json:"porQuePersonal"`
		Adicciones     *[]string `json:"adicciones"`
		Principal      *string   `json:"adiccionPrincipal"`
		ConsumoDesde   *string   `json:"consumoDesde"`
		EnTratamiento  *bool     `json:"enTratamiento"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}

	up := Update{
		DisplayName:    in.DisplayName,
		PorQuePersonal: in.PorQuePersonal,
		EnTratamiento:  in.EnTratamiento,
	}

	if in.Adicciones != nil {
		lista, malo := addiction.ParseLista(*in.Adicciones)
		if malo != "" {
			httpx.Error(w, http.StatusBadRequest, "invalid-argument", "tipo de adicción desconocido: "+malo)
			return
		}
		up.Adicciones = &lista
	}
	if in.Principal != nil {
		p, ok := addiction.Parse(*in.Principal)
		if !ok {
			httpx.Error(w, http.StatusBadRequest, "invalid-argument", "tipo de adicción desconocido: "+*in.Principal)
			return
		}
		up.Principal = &p
	}
	if in.ConsumoDesde != nil && *in.ConsumoDesde != "" {
		fecha, err := time.Parse(time.DateOnly, *in.ConsumoDesde)
		if err != nil {
			httpx.Error(w, http.StatusBadRequest, "invalid-argument", "consumoDesde debe tener el formato YYYY-MM-DD")
			return
		}
		if fecha.After(time.Now()) {
			httpx.Error(w, http.StatusBadRequest, "invalid-argument", "consumoDesde no puede estar en el futuro")
			return
		}
		up.ConsumoDesde = &fecha
	}

	// La coherencia se comprueba contra lo que quedará guardado, no solo contra
	// lo que vino en la petición: cambiar la principal sin tocar la lista tiene
	// que fallar si la nueva no está declarada.
	if err := h.validaCoherencia(r.Context(), id.UserID, &up); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}

	if err := h.store.UpdateProfile(r.Context(), id.UserID, up); err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo actualizar el perfil")
		return
	}
	h.me(w, r)
}

// validaCoherencia impide que el perfil quede diciendo dos cosas distintas: una
// adicción principal que no está entre las declaradas. Si la lista nueva deja
// huérfana a la principal anterior, la principal se recalcula en vez de
// rechazar la petición —quien quita "alcohol" de su lista no está mandando un
// dato inválido, está corrigiendo su perfil.
func (h *Handler) validaCoherencia(ctx context.Context, userID string, up *Update) error {
	if up.Adicciones == nil && up.Principal == nil {
		return nil
	}

	actual, err := h.store.Profile(ctx, userID)
	if err != nil {
		return errors.New("no se pudo leer el perfil actual")
	}

	lista := actual.Adicciones
	if up.Adicciones != nil {
		lista = *up.Adicciones
	}
	principal := actual.Principal
	if up.Principal != nil {
		principal = *up.Principal
	}

	switch {
	case principal == "":
		// Sin principal, si queda una sola declarada esa es. Si no, se deja
		// vacía y el onboarding sigue incompleto.
		if len(lista) == 1 {
			up.Principal = &lista[0]
		}
	case addiction.Contiene(lista, principal):
		// Coherente: nada que hacer.
	case up.Principal != nil:
		// La mandó explícitamente y no está en la lista: eso sí es un error.
		return errors.New("la adicción principal tiene que estar en la lista de adicciones")
	case len(lista) > 0:
		// La principal anterior ya no está declarada: se toma la primera de las
		// que quedan.
		up.Principal = &lista[0]
	default:
		vacia := addiction.Type("")
		up.Principal = &vacia
	}
	return nil
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
