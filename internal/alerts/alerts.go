// Package alerts concentra el protocolo de emergencia: persistir el aviso y
// entregarlo. Sustituye al trigger onCheckInCreated de Cloud Functions.
package alerts

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
	"github.com/oderflaY/Bakend-un-dia-mas/internal/risk"
)

type Alert struct {
	ID        string     `json:"id"`
	RiskLevel risk.Level `json:"riskLevel"`
	Message   string     `json:"message"`
	Handled   bool       `json:"handled"`
	CreatedAt time.Time  `json:"createdAt"`
}

type Contact struct {
	Nombre   string `json:"nombre"`
	Telefono string `json:"telefono"`
	Rol      string `json:"rol"`
}

type Service struct {
	db  *pgxpool.Pool
	hub *notify.Hub
}

func NewService(db *pgxpool.Pool, hub *notify.Hub) *Service {
	return &Service{db: db, hub: hub}
}

// Raise es el protocolo de emergencia completo para quien solo quiere levantar
// una alerta suelta. El orden importa: primero se escribe en la base y después
// se emite. Así, si la app está desconectada el aviso sigue existiendo y lo
// recoge al volver — que es lo que FCM resolvía entregando push con la app
// cerrada, y aquí se resuelve persistiendo.
//
// Quien además está cambiando el semáforo (internal/trafficlight) no usa esta
// función sino RaiseTx + Publish, para que la alerta y el registro del semáforo
// entren en la misma transacción.
func (s *Service) Raise(ctx context.Context, userID string, level risk.Level, message string) (Alert, error) {
	// Contexto propio: la alerta no debe perderse si el cliente corta la conexión.
	ctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
	defer cancel()

	tx, err := s.db.Begin(ctx)
	if err != nil {
		return Alert{}, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op si ya se hizo commit

	a, err := s.RaiseTx(ctx, tx, userID, level, message)
	if err != nil {
		return Alert{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return Alert{}, err
	}

	s.Publish(ctx, userID, a)
	return a, nil
}

// RaiseTx escribe la alerta dentro de una transacción ajena. No emite nada:
// emitir antes del commit permitiría que la app viera un aviso que después se
// revierte.
func (s *Service) RaiseTx(ctx context.Context, tx pgx.Tx, userID string, level risk.Level, message string) (Alert, error) {
	a := Alert{RiskLevel: level, Message: message}
	err := tx.QueryRow(ctx, `
		INSERT INTO alerts (user_id, risk_level, message)
		VALUES ($1, $2, $3)
		RETURNING id, handled, created_at`,
		userID, level.Code(), message).Scan(&a.ID, &a.Handled, &a.CreatedAt)
	if err != nil {
		return Alert{}, err
	}
	return a, nil
}

// Publish entrega una alerta ya persistida, con el contacto de confianza en el
// mismo payload para que la app no tenga que pedirlo aparte justo en el peor
// momento. Se llama siempre después del commit.
func (s *Service) Publish(ctx context.Context, userID string, a Alert) {
	contact, err := s.TrustedContact(ctx, userID)
	if err != nil {
		slog.Warn("no se pudo leer el contacto de confianza", "userId", userID, "err", err)
	}

	s.hub.Publish(userID, notify.Event{
		Type: "alert",
		Payload: map[string]any{
			"alert":           a,
			"trustedContact":  contact,
			"deliveredOnline": s.hub.Connected(userID),
		},
		CreatedAt: a.CreatedAt,
	})

	slog.Warn("protocolo de emergencia disparado",
		"userId", userID, "alertId", a.ID, "nivel", a.RiskLevel.String(),
		"conectado", s.hub.Connected(userID))
}

// TrustedContact es el primero de la lista (position = 0), la convención que ya
// usaba el protocolo de emergencia anterior.
func (s *Service) TrustedContact(ctx context.Context, userID string) (*Contact, error) {
	var c Contact
	err := s.db.QueryRow(ctx, `
		SELECT nombre, telefono, rol
		FROM emergency_contacts
		WHERE user_id = $1
		ORDER BY position ASC
		LIMIT 1`, userID).Scan(&c.Nombre, &c.Telefono, &c.Rol)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &c, nil
}

func (s *Service) List(ctx context.Context, userID string, limit int) ([]Alert, error) {
	rows, err := s.db.Query(ctx, `
		SELECT id, risk_level, message, handled, created_at
		FROM alerts
		WHERE user_id = $1
		ORDER BY created_at DESC
		LIMIT $2`, userID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	out := []Alert{}
	for rows.Next() {
		var a Alert
		var code string
		if err := rows.Scan(&a.ID, &code, &a.Message, &a.Handled, &a.CreatedAt); err != nil {
			return nil, err
		}
		a.RiskLevel = risk.Parse(code)
		out = append(out, a)
	}
	return out, rows.Err()
}

// SetHandled cierra el hallazgo #12: el campo existía pero las reglas de
// Firestore lo dejaban congelado en false para siempre.
func (s *Service) SetHandled(ctx context.Context, userID, alertID string, handled bool) (Alert, error) {
	var a Alert
	var code string
	err := s.db.QueryRow(ctx, `
		UPDATE alerts SET handled = $3
		WHERE id = $1 AND user_id = $2
		RETURNING id, risk_level, message, handled, created_at`,
		alertID, userID, handled).
		Scan(&a.ID, &code, &a.Message, &a.Handled, &a.CreatedAt)
	if err != nil {
		return Alert{}, err
	}
	a.RiskLevel = risk.Parse(code)
	return a, nil
}

type Handler struct {
	svc    *Service
	issuer *auth.TokenIssuer
}

func NewHandler(svc *Service, issuer *auth.TokenIssuer) *Handler {
	return &Handler{svc: svc, issuer: issuer}
}

func (h *Handler) Routes(mux *http.ServeMux) {
	mux.Handle("GET /v1/alerts", h.issuer.Middleware(http.HandlerFunc(h.list)))
	mux.Handle("PATCH /v1/alerts/{id}", h.issuer.Middleware(http.HandlerFunc(h.patch)))
}

func (h *Handler) list(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())
	items, err := h.svc.List(r.Context(), id.UserID, httpx.Limit(r, 20, 100))
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudieron leer las alertas")
		return
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"items": items})
}

func (h *Handler) patch(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())

	var in struct {
		Handled *bool `json:"handled"`
	}
	if err := httpx.Decode(w, r, &in); err != nil || in.Handled == nil {
		httpx.Error(w, http.StatusBadRequest, "invalid-argument", "se espera {\"handled\": true|false}")
		return
	}

	a, err := h.svc.SetHandled(r.Context(), id.UserID, r.PathValue("id"), *in.Handled)
	if httpx.IsNotFound(err) {
		httpx.Error(w, http.StatusNotFound, "not-found", "no existe esa alerta")
		return
	}
	if err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "no se pudo actualizar la alerta")
		return
	}
	httpx.JSON(w, http.StatusOK, a)
}
