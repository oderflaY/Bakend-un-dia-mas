package httpx

import (
	"log/slog"
	"net/http"
	"time"
)

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(code int) {
	r.status = code
	r.ResponseWriter.WriteHeader(code)
}

// Unwrap deja que http.ResponseController alcance al ResponseWriter real.
// Sin esto, Flush() y SetWriteDeadline() fallan al atravesar este middleware y
// el stream SSE de /v1/events no puede siquiera abrirse.
func (r *statusRecorder) Unwrap() http.ResponseWriter { return r.ResponseWriter }

// Logging registra método, ruta, estado y duración. Nunca el cuerpo: por aquí
// pasan mensajes al asistente, que son el dato más íntimo de la app.
func Logging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		slog.Info("http",
			"method", r.Method,
			"path", r.URL.Path,
			"status", rec.status,
			"ms", time.Since(start).Milliseconds())
	})
}
