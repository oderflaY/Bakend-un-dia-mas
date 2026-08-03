// Package tracker lleva la racha de sobriedad y las recaídas.
//
// Cierra el hallazgo #6: RelapseEvent existía en dominio y la colección tenía
// reglas y esquema, pero ningún repositorio la escribía. El reinicio de racha
// conservando el récord no dejaba rastro en ninguna parte.
package tracker

import (
	"context"
	"errors"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/auth"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/httpx"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/risk"
)

type Tracker struct {
	StartDate        *time.Time `json:"startDate"`
	DailySavingsRate float64    `json:"dailySavingsRate"`
	Currency         string     `json:"currency"`
	TrafficLight     risk.Level `json:"trafficLightStatus"`
	LastStatusUpdate *time.Time `json:"lastStatusUpdate"`

	// Derivados: se calculan al vuelo para que la app no tenga que hacerlo y no
	// puedan quedar desincronizados con startDate.
	StreakSeconds int64   `json:"rachaSegundos"`
	RecordSeconds int64   `json:"recordRachaSegundos"`
	SavedAmount   float64 `json:"ahorroAcumulado"`
}

type Relapse struct {
	ID                 string    `json:"id"`
	Note               string    `json:"note"`
	Triggers           []string  `json:"triggers"`
	PreviousStreakSecs int64     `json:"previousStreakSeconds"`
	CreatedAt          time.Time `json:"createdAt"`
}

type Store struct{ db *pgxpool.Pool }

func NewStore(db *pgxpool.Pool) *Store { return &Store{db: db} }

func (s *Store) Get(ctx context.Context, userID string) (Tracker, error) {
	var t Tracker
	var code string
	err := s.db.QueryRow(ctx, `
		SELECT st.start_date, st.daily_savings_rate, st.currency,
		       st.traffic_light, st.last_status_update, u.record_racha_secs
		FROM sobriety_trackers st
		JOIN users u ON u.id = st.user_id
		WHERE st.user_id = $1`, userID).
		Scan(&t.StartDate, &t.DailySavingsRate, &t.Currency,
			&code, &t.LastStatusUpdate, &t.RecordSeconds)
	if err != nil {
		return Tracker{}, err
	}
	t.TrafficLight = risk.Parse(code)
	t.fill()
	return t, nil
}

// fill calcula racha y ahorro a partir de startDate.
func (t *Tracker) fill() {
	if t.StartDate == nil {
		return
	}
	t.StreakSeconds = max(int64(time.Since(*t.StartDate).Seconds()), 0)
	t.SavedAmount = float64(t.StreakSeconds) / 86400 * t.DailySavingsRate
	t.RecordSeconds = max(t.RecordSeconds, t.StreakSeconds)
}

func (s *Store) Update(ctx context.Context, userID string, start *time.Time, rate *float64, currency *string) error {
	_, err := s.db.Exec(ctx, `
		UPDATE sobriety_trackers SET
			start_date         = COALESCE($2, start_date),
			daily_savings_rate = COALESCE($3, daily_savings_rate),
			currency           = COALESCE($4, currency)
		WHERE user_id = $1`, userID, start, rate, currency)
	return err
}

// Relapse reinicia la racha conservando el récord, y deja constancia.
//
// Todo ocurre en una transacción: si el registro de la recaída fallara después
// de reiniciar la racha, el usuario perdería su historial sin explicación.
func (s *Store) Relapse(ctx context.Context, userID, note string, triggers []string) (Relapse, error) {
	// La columna es NOT NULL: un slice nil no puede llegar al INSERT. Se
	// normaliza aquí y no solo en el handler porque el store también lo llaman
	// los tests y cualquier futuro comando fuera de HTTP.
	if triggers == nil {
		triggers = []string{}
	}

	tx, err := s.db.Begin(ctx)
	if err != nil {
		return Relapse{}, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op tras commit

	// El récord se calcula en SQL sobre start_date para que no dependa de un
	// valor que mande el cliente.
	var previous int64
	err = tx.QueryRow(ctx, `
		UPDATE users u SET record_racha_secs = GREATEST(
			u.record_racha_secs,
			COALESCE(EXTRACT(EPOCH FROM (now() - st.start_date))::bigint, 0))
		FROM sobriety_trackers st
		WHERE u.id = $1 AND st.user_id = u.id
		RETURNING COALESCE(EXTRACT(EPOCH FROM (now() - st.start_date))::bigint, 0)`,
		userID).Scan(&previous)
	if errors.Is(err, pgx.ErrNoRows) {
		return Relapse{}, pgx.ErrNoRows
	}
	if err != nil {
		return Relapse{}, err
	}

	if _, err := tx.Exec(ctx, `
		UPDATE sobriety_trackers
		SET start_date = now(), traffic_light = 'red', last_status_update = now()
		WHERE user_id = $1`, userID); err != nil {
		return Relapse{}, err
	}

	r := Relapse{Note: note, Triggers: triggers, PreviousStreakSecs: previous}
	if err := tx.QueryRow(ctx, `
		INSERT INTO relapse_events (user_id, note, triggers, previous_streak_secs)
		VALUES ($1, $2, $3, $4)
		RETURNING id, created_at`,
		userID, note, triggers, previous).Scan(&r.ID, &r.CreatedAt); err != nil {
		return Relapse{}, err
	}

	return r, tx.Commit(ctx)
}

func (s *Store) Relapses(ctx context.Context, userID string, limit int) ([]Relapse, error) {
	rows, err := s.db.Query(ctx, `
		SELECT id, note, triggers, previous_streak_secs, created_at
		FROM relapse_events
		WHERE user_id = $1
		ORDER BY created_at DESC
		LIMIT $2`, userID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []Relapse{}
	for rows.Next() {
		var r Relapse
		if err := rows.Scan(&r.ID, &r.Note, &r.Triggers, &r.PreviousStreakSecs, &r.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

type Handler struct {
	store  *Store
	issuer *auth.TokenIssuer
}

func NewHandler(store *Store, issuer *auth.TokenIssuer) *Handler {
	return &Handler{store: store, issuer: issuer}
}

func (h *Handler) Routes(mux *http.ServeMux) {
	mux.Handle("GET /v1/tracker", h.issuer.Middleware(http.HandlerFunc(h.get)))
	mux.Handle("PATCH /v1/tracker", h.issuer.Middleware(http.HandlerFunc(h.patch)))
	mux.Handle("POST /v1/relapses", h.issuer.Middleware(http.HandlerFunc(h.relapse)))
	mux.Handle("GET /v1/relapses", h.issuer.Middleware(http.HandlerFunc(h.listRelapses)))
}

func (h *Handler) get(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())
	t, err := h.store.Get(r.Context(), id.UserID)
	if errors.Is(err, pgx.ErrNoRows) {
		httpx.Error(w, http.StatusNotFound, "not-found", "no hay tracker para este usuario")
		return
	}
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo leer el tracker")
		return
	}
	httpx.JSON(w, http.StatusOK, t)
}

func (h *Handler) patch(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())

	var in struct {
		StartDate        *time.Time `json:"startDate"`
		DailySavingsRate *float64   `json:"dailySavingsRate"`
		Currency         *string    `json:"currency"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}
	if in.StartDate != nil && in.StartDate.After(time.Now()) {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "startDate no puede estar en el futuro")
		return
	}
	if in.DailySavingsRate != nil && *in.DailySavingsRate < 0 {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "dailySavingsRate no puede ser negativo")
		return
	}

	if err := h.store.Update(r.Context(), id.UserID, in.StartDate, in.DailySavingsRate, in.Currency); err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo actualizar el tracker")
		return
	}
	h.get(w, r)
}

func (h *Handler) relapse(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())

	var in struct {
		Note     string   `json:"note"`
		Triggers []string `json:"triggers"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}
	if in.Triggers == nil {
		in.Triggers = []string{}
	}

	rel, err := h.store.Relapse(r.Context(), id.UserID, in.Note, in.Triggers)
	if errors.Is(err, pgx.ErrNoRows) {
		httpx.Error(w, http.StatusNotFound, "not-found", "no hay tracker para este usuario")
		return
	}
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo registrar la recaída")
		return
	}
	httpx.JSON(w, http.StatusCreated, rel)
}

func (h *Handler) listRelapses(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())
	items, err := h.store.Relapses(r.Context(), id.UserID, httpx.Limit(r, 20, 100))
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudieron leer las recaídas")
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"items": items})
}
