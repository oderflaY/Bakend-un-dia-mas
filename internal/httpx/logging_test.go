package httpx

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

// Regresión: statusRecorder envolvía al ResponseWriter sin exponer el original,
// así que http.ResponseController no alcanzaba al de abajo y /v1/events
// respondía "el servidor no soporta streaming" en vez de abrir el stream.
func TestLoggingDejaPasarAlResponseController(t *testing.T) {
	var errDeadline, errFlush error

	// El handler corre en la goroutine del servidor y el Flush hace que la
	// respuesta llegue antes de que el handler termine: sin esta señal, el test
	// leería las variables mientras el handler todavía las escribe.
	hecho := make(chan struct{})

	srv := httptest.NewServer(Logging(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer close(hecho)
		rc := http.NewResponseController(w)
		errDeadline = rc.SetWriteDeadline(time.Time{})
		_, _ = w.Write([]byte("hola"))
		errFlush = rc.Flush()
	})))
	defer srv.Close()

	resp, err := http.Get(srv.URL)
	if err != nil {
		t.Fatalf("petición fallida: %v", err)
	}
	defer resp.Body.Close()
	<-hecho

	if errDeadline != nil {
		t.Errorf("SetWriteDeadline no atravesó el middleware: %v", errDeadline)
	}
	if errFlush != nil {
		t.Errorf("Flush no atravesó el middleware: %v", errFlush)
	}
}
