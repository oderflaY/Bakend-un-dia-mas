package notify

import (
	"fmt"
	"net/http"
	"time"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/auth"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/httpx"
)

// heartbeat mantiene viva la conexión: sin tráfico, los proxies cortan un SSE
// ocioso a los 30-60 s y la app se quedaría sin canal justo cuando hace falta.
const heartbeat = 20 * time.Second

type Handler struct {
	hub    *Hub
	issuer *auth.TokenIssuer
}

func NewHandler(hub *Hub, issuer *auth.TokenIssuer) *Handler {
	return &Handler{hub: hub, issuer: issuer}
}

func (h *Handler) Routes(mux *http.ServeMux) {
	mux.Handle("GET /v1/events", h.issuer.Middleware(http.HandlerFunc(h.stream)))
}

func (h *Handler) stream(w http.ResponseWriter, r *http.Request) {
	id := auth.MustFrom(r.Context())

	rc := http.NewResponseController(w)

	// El WriteTimeout global del servidor (60 s) cortaría este stream justo
	// cuando lleva más tiempo abierto. Se anula solo para esta conexión; el
	// heartbeat de abajo es lo que detecta clientes muertos.
	if err := rc.SetWriteDeadline(time.Time{}); err != nil {
		httpx.Error(w, http.StatusInternalServerError, "internal", "el servidor no soporta streaming")
		return
	}

	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("Connection", "keep-alive")
	w.Header().Set("X-Accel-Buffering", "no") // desactiva el buffering de nginx
	w.WriteHeader(http.StatusOK)

	events, unsubscribe := h.hub.Subscribe(id.UserID)
	defer unsubscribe()

	// Un primer evento inmediato para que la app confirme el canal abierto.
	fmt.Fprint(w, "event: ready\ndata: {}\n\n")
	rc.Flush()

	ticker := time.NewTicker(heartbeat)
	defer ticker.Stop()

	for {
		select {
		case <-r.Context().Done():
			return

		case data, open := <-events:
			if !open {
				return
			}
			fmt.Fprintf(w, "data: %s\n\n", data)
			rc.Flush()

		case <-ticker.C:
			// Comentario SSE: mantiene la conexión sin generar un evento en la app.
			fmt.Fprint(w, ": ping\n\n")
			rc.Flush()
		}
	}
}
