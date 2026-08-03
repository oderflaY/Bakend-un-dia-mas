// Package therapist es la vista clínica: qué ve un terapeuta de sus pacientes,
// y qué no.
//
// Sustituye a esTerapeuta() de firestore.rules, que tenía dos problemas: costaba
// una lectura por evaluación de regla (#16) y dejaba a un terapeuta leer a
// *cualquier* paciente. Aquí el rol viaja en el token y el vínculo vive en
// `therapist_patients`, así que toda lectura clínica pasa por dos filtros: el
// rol del token y la existencia del vínculo.
//
// El vínculo lo crea el paciente, nunca el terapeuta. Es una decisión de fondo:
// el acceso a datos de recuperación se concede, no se toma.
//
// Lo que el terapeuta NO ve, aunque haya vínculo: el diario (internal/journal) y
// la conversación con el asistente (internal/ai). Son el espacio privado del
// paciente y ninguna ruta de este paquete los expone.
package therapist

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/alerts"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/auth"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/checkins"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/httpx"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/stats"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/tracker"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/trafficlight"
)

// ErrNoLink es el "no existe" de este paquete: un paciente que no es tuyo y un
// paciente que no existe se responden igual, con 404. Confirmar cuál de los dos
// es filtraría quién está en tratamiento.
var ErrNoLink = errors.New("no hay vínculo con ese paciente")

const maxNote = 10000

type Person struct {
	ID          string    `json:"id"`
	Email       string    `json:"email"`
	DisplayName string    `json:"displayName"`
	Desde       time.Time `json:"vinculadoDesde"`
}

type Note struct {
	ID          string    `json:"id"`
	PatientID   string    `json:"patientId"`
	TherapistID string    `json:"therapistId"`
	Content     string    `json:"content"`
	CreatedAt   time.Time `json:"createdAt"`
}

type Session struct {
	ID          string     `json:"id"`
	PatientID   string     `json:"patientId"`
	TherapistID string     `json:"therapistId"`
	Status      string     `json:"status"`
	ScheduledAt *time.Time `json:"scheduledAt"`
	Notes       string     `json:"notes"`
	CreatedAt   time.Time  `json:"createdAt"`
}

// Estados válidos de una sesión. Se validan en Go porque la columna es TEXT: el
// esquema anterior tampoco los restringía y no merece la pena una migración de
// enum por tres valores.
var sessionStatuses = map[string]bool{
	"scheduled": true, "completed": true, "cancelled": true,
}

type Store struct{ db *pgxpool.Pool }

func NewStore(db *pgxpool.Pool) *Store { return &Store{db: db} }

// EnsureLink es el guardián de todo este paquete. Cada lectura clínica lo llama
// antes de tocar nada del paciente.
func (s *Store) EnsureLink(ctx context.Context, therapistID, patientID string) error {
	var exists bool
	err := s.db.QueryRow(ctx, `
		SELECT EXISTS(
			SELECT 1 FROM therapist_patients
			WHERE therapist_id = $1 AND patient_id = $2)`, therapistID, patientID).Scan(&exists)
	if err != nil {
		// Un id que no es UUID llega aquí como error de sintaxis: es un paciente
		// que no existe, no un fallo del servidor.
		if httpx.IsNotFound(err) {
			return ErrNoLink
		}
		return err
	}
	if !exists {
		return ErrNoLink
	}
	return nil
}

func (s *Store) Patients(ctx context.Context, therapistID string) ([]Person, error) {
	return s.people(ctx, `
		SELECT u.id, u.email, u.display_name, tp.created_at
		FROM therapist_patients tp
		JOIN users u ON u.id = tp.patient_id
		WHERE tp.therapist_id = $1
		ORDER BY u.display_name ASC, u.email ASC`, therapistID)
}

func (s *Store) Therapists(ctx context.Context, patientID string) ([]Person, error) {
	return s.people(ctx, `
		SELECT u.id, u.email, u.display_name, tp.created_at
		FROM therapist_patients tp
		JOIN users u ON u.id = tp.therapist_id
		WHERE tp.patient_id = $1
		ORDER BY u.display_name ASC, u.email ASC`, patientID)
}

func (s *Store) people(ctx context.Context, query, id string) ([]Person, error) {
	rows, err := s.db.Query(ctx, query, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []Person{}
	for rows.Next() {
		var p Person
		if err := rows.Scan(&p.ID, &p.Email, &p.DisplayName, &p.Desde); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// Link lo ejecuta el paciente sobre el correo de su terapeuta.
//
// El filtro de rol va dentro del INSERT ... SELECT: no hay un hueco entre
// "comprobar que es terapeuta" y "vincular" en el que la cuenta pudiera cambiar.
// ON CONFLICT DO NOTHING lo hace idempotente, y la lectura posterior devuelve el
// vínculo tanto si acaba de crearse como si ya existía.
func (s *Store) Link(ctx context.Context, patientID, therapistEmail string) (Person, error) {
	if _, err := s.db.Exec(ctx, `
		INSERT INTO therapist_patients (therapist_id, patient_id)
		SELECT u.id, $1
		FROM users u
		WHERE u.email = lower($2) AND u.role = 'therapist' AND u.id <> $1
		ON CONFLICT DO NOTHING`, patientID, therapistEmail); err != nil {
		return Person{}, err
	}

	var p Person
	err := s.db.QueryRow(ctx, `
		SELECT u.id, u.email, u.display_name, tp.created_at
		FROM therapist_patients tp
		JOIN users u ON u.id = tp.therapist_id
		WHERE tp.patient_id = $1 AND u.email = lower($2)`, patientID, therapistEmail).
		Scan(&p.ID, &p.Email, &p.DisplayName, &p.Desde)
	if errors.Is(err, pgx.ErrNoRows) {
		return Person{}, ErrNoLink
	}
	return p, err
}

func (s *Store) Unlink(ctx context.Context, patientID, therapistID string) error {
	tag, err := s.db.Exec(ctx, `
		DELETE FROM therapist_patients
		WHERE patient_id = $1 AND therapist_id = $2`, patientID, therapistID)
	if err != nil {
		if httpx.IsNotFound(err) {
			return ErrNoLink
		}
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNoLink
	}
	return nil
}

func (s *Store) AddNote(ctx context.Context, therapistID, patientID, content string) (Note, error) {
	n := Note{PatientID: patientID, TherapistID: therapistID, Content: content}
	err := s.db.QueryRow(ctx, `
		INSERT INTO clinical_notes (patient_id, therapist_id, content)
		VALUES ($1, $2, $3)
		RETURNING id, created_at`, patientID, therapistID, content).Scan(&n.ID, &n.CreatedAt)
	return n, err
}

// Notes filtra por terapeuta además de por paciente: la nota clínica es del
// profesional que la escribió, no del expediente compartido.
func (s *Store) Notes(ctx context.Context, therapistID, patientID string, limit int) ([]Note, error) {
	rows, err := s.db.Query(ctx, `
		SELECT id, patient_id, therapist_id, content, created_at
		FROM clinical_notes
		WHERE patient_id = $1 AND therapist_id = $2
		ORDER BY created_at DESC
		LIMIT $3`, patientID, therapistID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []Note{}
	for rows.Next() {
		var n Note
		if err := rows.Scan(&n.ID, &n.PatientID, &n.TherapistID, &n.Content, &n.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, n)
	}
	return out, rows.Err()
}

func (s *Store) CreateSession(ctx context.Context, therapistID, patientID string, at *time.Time, notes string) (Session, error) {
	ses := Session{PatientID: patientID, TherapistID: therapistID, Status: "scheduled", ScheduledAt: at, Notes: notes}
	err := s.db.QueryRow(ctx, `
		INSERT INTO sessions (patient_id, therapist_id, scheduled_at, notes)
		VALUES ($1, $2, $3, $4)
		RETURNING id, status, created_at`, patientID, therapistID, at, notes).
		Scan(&ses.ID, &ses.Status, &ses.CreatedAt)
	return ses, err
}

// UpdateSession solo la toca su terapeuta. El paciente puede leer sus sesiones
// pero no cambiarles el estado.
func (s *Store) UpdateSession(ctx context.Context, therapistID, sessionID string, status, notes *string, at *time.Time) (Session, error) {
	var ses Session
	err := s.db.QueryRow(ctx, `
		UPDATE sessions SET
			status       = COALESCE($3, status),
			notes        = COALESCE($4, notes),
			scheduled_at = COALESCE($5, scheduled_at)
		WHERE id = $1 AND therapist_id = $2
		RETURNING id, patient_id, therapist_id, status, scheduled_at, notes, created_at`,
		sessionID, therapistID, status, notes, at).
		Scan(&ses.ID, &ses.PatientID, &ses.TherapistID, &ses.Status,
			&ses.ScheduledAt, &ses.Notes, &ses.CreatedAt)
	return ses, err
}

// Sessions sirve a los dos lados: `column` es "therapist_id" o "patient_id", y
// nunca viene de la petición.
func (s *Store) Sessions(ctx context.Context, column, id string, limit int) ([]Session, error) {
	query := `
		SELECT id, patient_id, therapist_id, status, scheduled_at, notes, created_at
		FROM sessions
		WHERE ` + column + ` = $1
		ORDER BY scheduled_at DESC NULLS LAST, created_at DESC
		LIMIT $2`
	rows, err := s.db.Query(ctx, query, id, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []Session{}
	for rows.Next() {
		var ses Session
		if err := rows.Scan(&ses.ID, &ses.PatientID, &ses.TherapistID, &ses.Status,
			&ses.ScheduledAt, &ses.Notes, &ses.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, ses)
	}
	return out, rows.Err()
}

// Handler junta los stores que ya existen en lugar de reescribir sus consultas:
// una vez comprobado el vínculo, leer los check-ins de un paciente es la misma
// operación que leer los propios.
type Handler struct {
	store    *Store
	checkIns *checkins.Store
	tracker  *tracker.Store
	lights   *trafficlight.Service
	alerts   *alerts.Service
	stats    *stats.Store
	issuer   *auth.TokenIssuer
}

func NewHandler(store *Store, checkIns *checkins.Store, trk *tracker.Store,
	lights *trafficlight.Service, alertSvc *alerts.Service, st *stats.Store,
	issuer *auth.TokenIssuer) *Handler {
	return &Handler{
		store: store, checkIns: checkIns, tracker: trk,
		lights: lights, alerts: alertSvc, stats: st, issuer: issuer,
	}
}

func (h *Handler) Routes(mux *http.ServeMux) {
	// Lado del paciente: conceder y retirar el acceso, y ver sus sesiones.
	mux.Handle("GET /v1/me/therapists", h.issuer.Middleware(http.HandlerFunc(h.myTherapists)))
	mux.Handle("POST /v1/me/therapists", h.issuer.Middleware(http.HandlerFunc(h.link)))
	mux.Handle("DELETE /v1/me/therapists/{id}", h.issuer.Middleware(http.HandlerFunc(h.unlink)))
	mux.Handle("GET /v1/me/sessions", h.issuer.Middleware(http.HandlerFunc(h.mySessions)))

	// Lado clínico: además del vínculo, exige rol therapist en el token.
	clinical := func(f http.HandlerFunc) http.Handler {
		return h.issuer.Middleware(auth.RequireRole(auth.RoleTherapist, f))
	}
	mux.Handle("GET /v1/therapist/patients", clinical(h.patients))
	mux.Handle("GET /v1/therapist/patients/{id}", clinical(h.patientSummary))
	mux.Handle("GET /v1/therapist/patients/{id}/check-ins", clinical(h.patientCheckIns))
	mux.Handle("GET /v1/therapist/patients/{id}/traffic-light", clinical(h.patientLights))
	mux.Handle("GET /v1/therapist/patients/{id}/alerts", clinical(h.patientAlerts))
	mux.Handle("GET /v1/therapist/patients/{id}/stats", clinical(h.patientStats))
	mux.Handle("GET /v1/therapist/patients/{id}/notes", clinical(h.listNotes))
	mux.Handle("POST /v1/therapist/patients/{id}/notes", clinical(h.addNote))
	mux.Handle("GET /v1/therapist/sessions", clinical(h.sessions))
	mux.Handle("POST /v1/therapist/sessions", clinical(h.createSession))
	mux.Handle("PATCH /v1/therapist/sessions/{id}", clinical(h.patchSession))
}

// patient resuelve el paciente de la ruta comprobando el vínculo. Devuelve ""
// y ya ha respondido cuando no procede seguir.
func (h *Handler) patient(w http.ResponseWriter, r *http.Request) string {
	id := auth.MustFrom(r.Context())
	patientID := r.PathValue("id")
	err := h.store.EnsureLink(r.Context(), id.UserID, patientID)
	if errors.Is(err, ErrNoLink) {
		httpx.Error(w, http.StatusNotFound, "not-found", "no existe ese paciente entre los tuyos")
		return ""
	}
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo verificar el vínculo")
		return ""
	}
	return patientID
}

func (h *Handler) myTherapists(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())
	items, err := h.store.Therapists(r.Context(), id.UserID)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo leer tu equipo de cuidado")
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"items": items})
}

func (h *Handler) link(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())

	var in struct {
		Email string `json:"email"`
	}
	if err := httpx.Decode(w, r, &in); err != nil || strings.TrimSpace(in.Email) == "" {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "falta el correo del terapeuta")
		return
	}

	p, err := h.store.Link(r.Context(), id.UserID, strings.TrimSpace(in.Email))
	if errors.Is(err, ErrNoLink) {
		// Mismo error para "no existe" y "no es terapeuta": esta ruta no sirve
		// para averiguar qué correos están registrados.
		httpx.Error(w, http.StatusNotFound, "not-found", "no hay ningún terapeuta con ese correo")
		return
	}
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo conceder el acceso")
		return
	}
	httpx.JSON(w, http.StatusCreated, p)
}

func (h *Handler) unlink(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())
	err := h.store.Unlink(r.Context(), id.UserID, r.PathValue("id"))
	if errors.Is(err, ErrNoLink) {
		httpx.Error(w, http.StatusNotFound, "not-found", "ese terapeuta no tiene acceso a tus datos")
		return
	}
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo retirar el acceso")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) mySessions(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())
	items, err := h.store.Sessions(r.Context(), "patient_id", id.UserID, httpx.Limit(r, 20, 100))
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudieron leer las sesiones")
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"items": items})
}

func (h *Handler) patients(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())
	items, err := h.store.Patients(r.Context(), id.UserID)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudieron leer los pacientes")
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"items": items})
}

// patientSummary es la pantalla de apertura del terapeuta: dónde está hoy el
// paciente y qué pasó últimamente, en una sola petición.
func (h *Handler) patientSummary(w http.ResponseWriter, r *http.Request) {
	patientID := h.patient(w, r)
	if patientID == "" {
		return
	}

	trk, err := h.tracker.Get(r.Context(), patientID)
	if err != nil && !httpx.IsNotFound(err) {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo leer el tracker")
		return
	}
	recent, err := h.checkIns.List(r.Context(), patientID, 5)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudieron leer los check-ins")
		return
	}
	openAlerts, err := h.alerts.List(r.Context(), patientID, 5)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudieron leer las alertas")
		return
	}

	httpx.JSON(w, http.StatusOK, map[string]any{
		"pacienteId": patientID,
		"tracker":    trk,
		"checkIns":   recent,
		"alertas":    openAlerts,
	})
}

func (h *Handler) patientCheckIns(w http.ResponseWriter, r *http.Request) {
	patientID := h.patient(w, r)
	if patientID == "" {
		return
	}
	items, err := h.checkIns.List(r.Context(), patientID, httpx.Limit(r, 20, 100))
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudieron leer los check-ins")
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"items": items})
}

func (h *Handler) patientLights(w http.ResponseWriter, r *http.Request) {
	patientID := h.patient(w, r)
	if patientID == "" {
		return
	}
	items, err := h.lights.List(r.Context(), patientID, httpx.Limit(r, 20, 100))
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo leer el semáforo")
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"items": items})
}

func (h *Handler) patientAlerts(w http.ResponseWriter, r *http.Request) {
	patientID := h.patient(w, r)
	if patientID == "" {
		return
	}
	items, err := h.alerts.List(r.Context(), patientID, httpx.Limit(r, 20, 100))
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudieron leer las alertas")
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"items": items})
}

func (h *Handler) patientStats(w http.ResponseWriter, r *http.Request) {
	patientID := h.patient(w, r)
	if patientID == "" {
		return
	}
	rep, err := h.stats.RiskTrends(r.Context(), patientID, 30, "America/Mexico_City")
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudieron calcular las estadísticas")
		return
	}
	httpx.JSON(w, http.StatusOK, rep)
}

func (h *Handler) listNotes(w http.ResponseWriter, r *http.Request) {
	patientID := h.patient(w, r)
	if patientID == "" {
		return
	}
	id := auth.MustFrom(r.Context())
	items, err := h.store.Notes(r.Context(), id.UserID, patientID, httpx.Limit(r, 20, 100))
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudieron leer las notas")
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"items": items})
}

func (h *Handler) addNote(w http.ResponseWriter, r *http.Request) {
	patientID := h.patient(w, r)
	if patientID == "" {
		return
	}
	id := auth.MustFrom(r.Context())

	var in struct {
		Content string `json:"content"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}
	in.Content = strings.TrimSpace(in.Content)
	if in.Content == "" || len([]rune(in.Content)) > maxNote {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "la nota está vacía o es demasiado larga")
		return
	}

	n, err := h.store.AddNote(r.Context(), id.UserID, patientID, in.Content)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo guardar la nota")
		return
	}
	httpx.JSON(w, http.StatusCreated, n)
}

func (h *Handler) sessions(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())
	items, err := h.store.Sessions(r.Context(), "therapist_id", id.UserID, httpx.Limit(r, 20, 100))
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudieron leer las sesiones")
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"items": items})
}

func (h *Handler) createSession(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())

	var in struct {
		PatientID   string     `json:"patientId"`
		ScheduledAt *time.Time `json:"scheduledAt"`
		Notes       string     `json:"notes"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}

	if err := h.store.EnsureLink(r.Context(), id.UserID, in.PatientID); err != nil {
		if errors.Is(err, ErrNoLink) {
			httpx.Error(w, http.StatusNotFound, "not-found", "no existe ese paciente entre los tuyos")
			return
		}
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo verificar el vínculo")
		return
	}

	ses, err := h.store.CreateSession(r.Context(), id.UserID, in.PatientID, in.ScheduledAt, in.Notes)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo crear la sesión")
		return
	}
	httpx.JSON(w, http.StatusCreated, ses)
}

func (h *Handler) patchSession(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())

	var in struct {
		Status      *string    `json:"status"`
		Notes       *string    `json:"notes"`
		ScheduledAt *time.Time `json:"scheduledAt"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}
	if in.Status != nil && !sessionStatuses[*in.Status] {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "status debe ser scheduled, completed o cancelled")
		return
	}

	ses, err := h.store.UpdateSession(r.Context(), id.UserID, r.PathValue("id"), in.Status, in.Notes, in.ScheduledAt)
	if httpx.IsNotFound(err) {
		httpx.Error(w, http.StatusNotFound, "not-found", "no existe esa sesión")
		return
	}
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo actualizar la sesión")
		return
	}
	httpx.JSON(w, http.StatusOK, ses)
}
