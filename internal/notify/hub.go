// Package notify entrega avisos en tiempo real por SSE (Server-Sent Events).
//
// Sustituye a Firebase Cloud Messaging, que era la última pieza de Firebase en
// el sistema. FCM entrega push aunque la app esté cerrada, cosa que un servidor
// propio no puede hacer en Android; a cambio, aquí no hay dependencia de Google
// y el aviso nunca se pierde: toda notificación se persiste en `alerts` antes de
// emitirse, así que la app la recupera al reconectar aunque estuviera offline.
package notify

import (
	"encoding/json"
	"sync"
	"time"
)

type Event struct {
	Type      string    `json:"type"` // "alert" | "traffic_light" | "ping"
	Payload   any       `json:"payload,omitempty"`
	CreatedAt time.Time `json:"createdAt"`
}

// Hub reparte eventos a las conexiones abiertas de cada usuario.
type Hub struct {
	mu          sync.RWMutex
	subscribers map[string]map[chan []byte]struct{} // userID → conexiones
}

func NewHub() *Hub {
	return &Hub{subscribers: map[string]map[chan []byte]struct{}{}}
}

// Subscribe devuelve el canal de un cliente y la función para darlo de baja.
func (h *Hub) Subscribe(userID string) (<-chan []byte, func()) {
	// Con buffer: un cliente lento no puede bloquear al que publica.
	ch := make(chan []byte, 8)

	h.mu.Lock()
	if h.subscribers[userID] == nil {
		h.subscribers[userID] = map[chan []byte]struct{}{}
	}
	h.subscribers[userID][ch] = struct{}{}
	h.mu.Unlock()

	return ch, func() {
		h.mu.Lock()
		defer h.mu.Unlock()
		if conns, ok := h.subscribers[userID]; ok {
			if _, live := conns[ch]; live {
				delete(conns, ch)
				close(ch)
			}
			if len(conns) == 0 {
				delete(h.subscribers, userID)
			}
		}
	}
}

// Publish emite a todas las conexiones del usuario. Nunca bloquea: si un canal
// está lleno se descarta el evento para esa conexión, que de todas formas
// recuperará el dato de la base al reconectar.
func (h *Hub) Publish(userID string, ev Event) {
	if ev.CreatedAt.IsZero() {
		ev.CreatedAt = time.Now()
	}
	data, err := json.Marshal(ev)
	if err != nil {
		return
	}

	h.mu.RLock()
	defer h.mu.RUnlock()
	for ch := range h.subscribers[userID] {
		select {
		case ch <- data:
		default:
		}
	}
}

// Connected sirve para decidir si hay que escalar por otra vía.
func (h *Hub) Connected(userID string) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.subscribers[userID]) > 0
}
