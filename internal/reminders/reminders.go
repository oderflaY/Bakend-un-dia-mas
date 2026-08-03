// Package reminders cierra el hallazgo #8: la Fase 3·09 pedía recordatorios de
// check-in y no existía ninguna función onSchedule.
//
// Aquí no hace falta Cloud Scheduler ni un cron del sistema: el propio proceso
// lleva un ticker de un minuto y consulta quién tiene aviso pendiente. La
// ventana de disparo se decide en SQL, sobre la hora local de cada usuario, y
// `last_sent_on` garantiza un aviso por día aunque el ticker pase varias veces
// dentro de la misma ventana o el servidor se reinicie a media tarde.
//
// La entrega es por SSE, con la misma limitación que las alertas: si la app
// está cerrada el recordatorio no aparece. Es honesto para lo que es —un
// empujón, no una emergencia— y por eso el recordatorio no se persiste como las
// alertas, solo se marca como enviado.
package reminders

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/auth"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/httpx"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/notify"
)

// tick: cada minuto. La ventana de disparo (window) es más ancha que el tick a
// propósito, para que un GC largo o un reinicio corto no se salten el aviso.
const (
	tick   = time.Minute
	window = 15 * time.Minute
)

type Settings struct {
	Enabled  bool       `json:"enabled"`
	Hour     int        `json:"hora"`
	Minute   int        `json:"minuto"`
	Timezone string     `json:"zona"`
	LastSent *time.Time `json:"ultimoEnvio"`
}

type Store struct{ db *pgxpool.Pool }

func NewStore(db *pgxpool.Pool) *Store { return &Store{db: db} }

// Get devuelve los valores por defecto de la tabla si el usuario nunca tocó su
// configuración, así la app siempre tiene algo que mostrar en el formulario.
func (s *Store) Get(ctx context.Context, userID string) (Settings, error) {
	var st Settings
	var last *time.Time
	err := s.db.QueryRow(ctx, `
		SELECT enabled, hour_local, minute_local, timezone, last_sent_on
		FROM reminder_settings WHERE user_id = $1`, userID).
		Scan(&st.Enabled, &st.Hour, &st.Minute, &st.Timezone, &last)
	if errors.Is(err, pgx.ErrNoRows) {
		return Settings{Enabled: false, Hour: 21, Minute: 0, Timezone: "America/Mexico_City"}, nil
	}
	if err != nil {
		return Settings{}, err
	}
	st.LastSent = last
	return st, nil
}

// Save es un upsert: la fila nace la primera vez que el usuario configura su
// recordatorio, no al registrarse. Un usuario que nunca lo pidió no aparece en
// el barrido del planificador.
func (s *Store) Save(ctx context.Context, userID string, st Settings) error {
	_, err := s.db.Exec(ctx, `
		INSERT INTO reminder_settings (user_id, enabled, hour_local, minute_local, timezone)
		VALUES ($1, $2, $3, $4, $5)
		ON CONFLICT (user_id) DO UPDATE SET
			enabled      = EXCLUDED.enabled,
			hour_local   = EXCLUDED.hour_local,
			minute_local = EXCLUDED.minute_local,
			timezone     = EXCLUDED.timezone`,
		userID, st.Enabled, st.Hour, st.Minute, st.Timezone)
	return err
}

// due devuelve a quién le toca aviso ahora mismo y lo marca como enviado en la
// misma sentencia. Hacerlo en un solo UPDATE ... RETURNING es lo que evita que
// dos réplicas manden el recordatorio dos veces: la segunda no encuentra filas.
func (s *Store) due(ctx context.Context) ([]string, error) {
	rows, err := s.db.Query(ctx, `
		WITH candidatos AS (
			SELECT r.user_id,
			       (now() AT TIME ZONE r.timezone)                       AS ahora_local,
			       (now() AT TIME ZONE r.timezone)::date                 AS hoy_local
			FROM reminder_settings r
			WHERE r.enabled
		), pendientes AS (
			SELECT c.user_id, c.hoy_local
			FROM candidatos c
			JOIN reminder_settings r ON r.user_id = c.user_id
			WHERE (r.last_sent_on IS NULL OR r.last_sent_on < c.hoy_local)
			  -- Ya pasó la hora elegida y no hace tanto como para que el aviso
			  -- llegue a deshora tras un reinicio a media noche.
			  AND c.ahora_local >= (c.hoy_local + make_time(r.hour_local, r.minute_local, 0))
			  AND c.ahora_local <  (c.hoy_local + make_time(r.hour_local, r.minute_local, 0) + $1::interval)
			  -- Si ya hizo su check-in de hoy, no hay nada que recordar.
			  AND NOT EXISTS (
			      SELECT 1 FROM check_ins ci
			      WHERE ci.user_id = c.user_id
			        AND (ci.created_at AT TIME ZONE r.timezone)::date = c.hoy_local
			  )
		)
		UPDATE reminder_settings r
		SET last_sent_on = p.hoy_local
		FROM pendientes p
		WHERE r.user_id = p.user_id
		RETURNING r.user_id`, window)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

// Scheduler es el reemplazo de onSchedule. Vive mientras viva el proceso y se
// apaga con el contexto del servidor.
type Scheduler struct {
	store *Store
	hub   *notify.Hub
}

func NewScheduler(store *Store, hub *notify.Hub) *Scheduler {
	return &Scheduler{store: store, hub: hub}
}

// Run bloquea hasta que se cancela el contexto; se lanza en su propia goroutine.
func (s *Scheduler) Run(ctx context.Context) {
	t := time.NewTicker(tick)
	defer t.Stop()

	slog.Info("planificador de recordatorios en marcha", "cada", tick.String())
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			s.sweep(ctx)
		}
	}
}

func (s *Scheduler) sweep(ctx context.Context) {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()

	users, err := s.store.due(ctx)
	if err != nil {
		slog.Error("no se pudo barrer los recordatorios", "err", err)
		return
	}
	for _, userID := range users {
		s.hub.Publish(userID, notify.Event{
			Type: "check_in_reminder",
			Payload: map[string]any{
				"mensaje": "¿Cómo va tu día? Tómate un minuto para tu check-in.",
			},
		})
	}
	if len(users) > 0 {
		slog.Info("recordatorios emitidos", "usuarios", len(users))
	}
}

type Handler struct {
	store  *Store
	issuer *auth.TokenIssuer
}

func NewHandler(store *Store, issuer *auth.TokenIssuer) *Handler {
	return &Handler{store: store, issuer: issuer}
}

func (h *Handler) Routes(mux *http.ServeMux) {
	mux.Handle("GET /v1/reminders", h.issuer.Middleware(http.HandlerFunc(h.get)))
	mux.Handle("PUT /v1/reminders", h.issuer.Middleware(http.HandlerFunc(h.put)))
}

func (h *Handler) get(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())
	st, err := h.store.Get(r.Context(), id.UserID)
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo leer el recordatorio")
		return
	}
	httpx.JSON(w, http.StatusOK, st)
}

func (h *Handler) put(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())

	var in struct {
		Enabled bool   `json:"enabled"`
		Hora    int    `json:"hora"`
		Minuto  int    `json:"minuto"`
		Zona    string `json:"zona"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}
	if in.Hora < 0 || in.Hora > 23 || in.Minuto < 0 || in.Minuto > 59 {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "hora fuera de rango")
		return
	}
	if in.Zona == "" {
		in.Zona = "America/Mexico_City"
	}
	// Se valida aquí y no en Postgres: una zona inventada rompería el barrido de
	// todos los usuarios, no solo el de quien la mandó.
	if _, err := time.LoadLocation(in.Zona); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "zona horaria desconocida")
		return
	}

	if err := h.store.Save(r.Context(), id.UserID, Settings{
		Enabled: in.Enabled, Hour: in.Hora, Minute: in.Minuto, Timezone: in.Zona,
	}); err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo guardar el recordatorio")
		return
	}
	h.get(w, r)
}
