package httpx

import (
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Limiter es una ventana fija por clave, en memoria. Sirve para dos usos
// distintos en este backend:
//
//   - por usuario, para que nadie monopolice el chat de Gemini (internal/ai);
//   - por IP, para que /v1/auth/* no sea fuerza bruta gratis (internal/auth).
//
// El segundo uso es el que obliga a barrer claves viejas. Contra usuarios
// autenticados el mapa está acotado por el número de cuentas; contra IPs de
// internet no lo está, y un mapa que solo crece es una fuga de memoria a la que
// cualquiera puede darle cuerda desde fuera.
//
// Con varias réplicas esto deja de ser exacto: cada proceso cuenta lo suyo. Para
// ese día el reemplazo es Redis o una tabla, pero mientras el despliegue sea un
// solo contenedor —que es el caso— esto es lo correcto y no cuesta nada.
type Limiter struct {
	mu     sync.Mutex
	hits   map[string][]time.Time
	limit  int
	window time.Duration
	// Último barrido completo. Sin esto habría que recorrer el mapa entero en
	// cada petición para saber qué claves murieron.
	swept time.Time
}

func NewLimiter(limit int, window time.Duration) *Limiter {
	return &Limiter{
		hits:   map[string][]time.Time{},
		limit:  limit,
		window: window,
		swept:  time.Now(),
	}
}

// Allow registra un intento y dice si cabe dentro de la ventana.
func (l *Limiter) Allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()

	now := time.Now()
	cutoff := now.Add(-l.window)

	// El barrido va aquí y no en una goroutine aparte: ya tenemos el candado
	// tomado, y una goroutine por limitador sería un hilo vivo para siempre a
	// cambio de nada. Una vez por ventana el coste es O(claves vivas).
	if now.Sub(l.swept) > l.window {
		for k, ts := range l.hits {
			if len(ts) == 0 || !ts[len(ts)-1].After(cutoff) {
				delete(l.hits, k)
			}
		}
		l.swept = now
	}

	kept := l.hits[key][:0]
	for _, t := range l.hits[key] {
		if t.After(cutoff) {
			kept = append(kept, t)
		}
	}
	if len(kept) >= l.limit {
		l.hits[key] = kept
		return false
	}
	l.hits[key] = append(kept, now)
	return true
}

// PorIP limita por dirección de origen. Se usa en las rutas que todavía no
// tienen usuario —registro, login, refresh, recuperación—, que son justo las
// que alguien puede martillear sin credenciales.
func (l *Limiter) PorIP(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !l.Allow(ClientIP(r)) {
			// Retry-After en segundos: la app puede esperar lo justo en vez de
			// reintentar a ciegas.
			w.Header().Set("Retry-After", strconv.Itoa(int(l.window.Seconds())))
			Error(w, http.StatusTooManyRequests, "rate-limited",
				"demasiados intentos seguidos, espera un momento")
			return
		}
		next.ServeHTTP(w, r)
	})
}

// ClientIP saca la IP real del cliente detrás de Caddy.
//
// Caddy **añade** la IP de origen al final de X-Forwarded-For, conservando lo
// que viniera de fuera. Por eso se lee la última entrada y no la primera: la
// primera la controla quien llama, y confiar en ella dejaría el límite inútil
// —bastaría con mandar un X-Forwarded-For distinto en cada petición—. La última
// la escribió Caddy, que es el único que puede hablar con este proceso.
func ClientIP(r *http.Request) string {
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		partes := strings.Split(xff, ",")
		if ip := strings.TrimSpace(partes[len(partes)-1]); ip != "" {
			return ip
		}
	}
	// Sin proxy delante (desarrollo local, o una prueba directa al 8080).
	if host, _, err := net.SplitHostPort(r.RemoteAddr); err == nil {
		return host
	}
	return r.RemoteAddr
}
