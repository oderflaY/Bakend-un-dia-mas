// Package trafficlight es el estado del semáforo y su historial.
//
// Es el único que escribe `traffic_light_logs`, y lo hace junto con el estado
// vigente del tracker y —cuando el nivel es rojo— la alerta del protocolo de
// emergencia, todo en la misma transacción. Esa unidad es la que evita el
// escenario del hallazgo #1 en su forma más sutil: un semáforo que queda en rojo
// en la pantalla pero sin alerta detrás, o al revés.
package trafficlight

import (
	"context"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/alerts"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/auth"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/httpx"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/notify"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/risk"
)

const (
	maxReason  = 500
	maxActions = 10
)

type Entry struct {
	ID               string     `json:"id"`
	Status           risk.Level `json:"status"`
	Reason           string     `json:"reason"`
	TriggerLevel     int        `json:"triggerLevel"`
	SuggestedActions []string   `json:"suggestedActions"`
	CreatedAt        time.Time  `json:"createdAt"`
}

// Evaluation es lo que entra: una lectura del semáforo con su porqué.
type Evaluation struct {
	Status           risk.Level
	Reason           string
	TriggerLevel     int
	SuggestedActions []string
}

// Result distingue el registro del semáforo de la alerta que pudo generar, para
// que quien lo llama sepa si se disparó el protocolo de emergencia.
type Result struct {
	Entry Entry
	Alert *alerts.Alert
}

type Service struct {
	db     *pgxpool.Pool
	hub    *notify.Hub
	alerts *alerts.Service
}

func NewService(db *pgxpool.Pool, hub *notify.Hub, alertSvc *alerts.Service) *Service {
	return &Service{db: db, hub: hub, alerts: alertSvc}
}

// Record persiste la evaluación, deja el tracker al día y entrega los avisos.
//
// El contexto se desliga del de la petición por lo mismo que en alerts.Raise:
// un semáforo rojo no puede quedarse a medio escribir porque el usuario cerró
// la app justo después de mandarlo.
func (s *Service) Record(ctx context.Context, userID string, ev Evaluation, message string) (Result, error) {
	ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
	defer cancel()

	if ev.TriggerLevel == 0 {
		ev.TriggerLevel = DefaultTriggerLevel(ev.Status)
	}
	if ev.SuggestedActions == nil {
		ev.SuggestedActions = []string{}
	}

	tx, err := s.db.Begin(ctx)
	if err != nil {
		return Result{}, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op tras commit

	e := Entry{
		Status:           ev.Status,
		Reason:           ev.Reason,
		TriggerLevel:     ev.TriggerLevel,
		SuggestedActions: ev.SuggestedActions,
	}
	if err := tx.QueryRow(ctx, `
		INSERT INTO traffic_light_logs (user_id, status, reason, trigger_level, suggested_actions)
		VALUES ($1, $2, $3, $4, $5)
		RETURNING id, created_at`,
		userID, ev.Status.Code(), ev.Reason, ev.TriggerLevel, ev.SuggestedActions).
		Scan(&e.ID, &e.CreatedAt); err != nil {
		return Result{}, err
	}

	if _, err := tx.Exec(ctx, `
		UPDATE sobriety_trackers
		SET traffic_light = $2, last_status_update = now()
		WHERE user_id = $1`, userID, ev.Status.Code()); err != nil {
		return Result{}, err
	}

	res := Result{Entry: e}
	if ev.Status == risk.Red {
		if message == "" {
			message = "Semáforo en rojo"
		}
		a, err := s.alerts.RaiseTx(ctx, tx, userID, ev.Status, message)
		if err != nil {
			return Result{}, err
		}
		res.Alert = &a
	}

	if err := tx.Commit(ctx); err != nil {
		return Result{}, err
	}

	// Después del commit, nunca antes: emitir un aviso de algo que luego se
	// revierte es peor que no emitirlo.
	s.hub.Publish(userID, notify.Event{
		Type:      "traffic_light",
		Payload:   e,
		CreatedAt: e.CreatedAt,
	})
	if res.Alert != nil {
		s.alerts.Publish(ctx, userID, *res.Alert)
	}
	return res, nil
}

func (s *Service) List(ctx context.Context, userID string, limit int) ([]Entry, error) {
	rows, err := s.db.Query(ctx, `
		SELECT id, status, reason, trigger_level, suggested_actions, created_at
		FROM traffic_light_logs
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
		var code string
		if err := rows.Scan(&e.ID, &code, &e.Reason, &e.TriggerLevel,
			&e.SuggestedActions, &e.CreatedAt); err != nil {
			return nil, err
		}
		e.Status = risk.Parse(code)
		out = append(out, e)
	}
	return out, rows.Err()
}

// DefaultTriggerLevel traduce el nivel del semáforo a la escala 1..5 que ya
// usaba el modelo anterior, para cuando la app no manda uno propio.
func DefaultTriggerLevel(l risk.Level) int {
	switch l {
	case risk.Red:
		return 5
	case risk.Yellow:
		return 3
	default:
		return 1
	}
}

type Handler struct {
	svc    *Service
	issuer *auth.TokenIssuer
}

func NewHandler(svc *Service, issuer *auth.TokenIssuer) *Handler {
	return &Handler{svc: svc, issuer: issuer}
}

func (h *Handler) Routes(mux *http.ServeMux) {
	mux.Handle("POST /v1/traffic-light", h.issuer.Middleware(http.HandlerFunc(h.record)))
	mux.Handle("GET /v1/traffic-light", h.issuer.Middleware(http.HandlerFunc(h.list)))
}

func (h *Handler) record(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())

	var in struct {
		Status           string   `json:"status"`
		Reason           string   `json:"reason"`
		TriggerLevel     int      `json:"triggerLevel"`
		SuggestedActions []string `json:"suggestedActions"`
		Message          string   `json:"message"`
	}
	if err := httpx.Decode(w, r, &in); err != nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", err.Error())
		return
	}
	if in.TriggerLevel < 0 || in.TriggerLevel > 5 {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "triggerLevel debe estar entre 1 y 5")
		return
	}
	if len([]rune(in.Reason)) > maxReason {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "reason es demasiado largo")
		return
	}
	if len(in.SuggestedActions) > maxActions {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "demasiadas acciones sugeridas")
		return
	}
	for i, a := range in.SuggestedActions {
		in.SuggestedActions[i] = strings.TrimSpace(a)
	}

	res, err := h.svc.Record(r.Context(), id.UserID, Evaluation{
		// risk.Parse cae a verde ante un valor desconocido: un dato corrupto no
		// puede inventar un rojo y disparar el protocolo de emergencia.
		Status:           risk.Parse(in.Status),
		Reason:           strings.TrimSpace(in.Reason),
		TriggerLevel:     in.TriggerLevel,
		SuggestedActions: in.SuggestedActions,
	}, strings.TrimSpace(in.Message))
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo registrar el semáforo")
		return
	}

	httpx.JSON(w, http.StatusCreated, map[string]any{
		"entry": res.Entry,
		"alert": res.Alert, // null salvo que el nivel sea rojo
	})
}

func (h *Handler) list(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())
	items, err := h.svc.List(r.Context(), id.UserID, httpx.Limit(r, 20, 100))
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo leer el historial del semáforo")
		return
	}

	current := risk.Green
	var since *time.Time
	if len(items) > 0 {
		current = items[0].Status
		since = &items[0].CreatedAt
	}
	httpx.JSON(w, http.StatusOK, map[string]any{
		"current": current,
		"since":   since,
		"items":   items,
	})
}
