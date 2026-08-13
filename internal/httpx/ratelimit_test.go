package httpx

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestElLimiteCortaAlPasarse(t *testing.T) {
	l := NewLimiter(3, time.Minute)
	for i := range 3 {
		if !l.Allow("mismo") {
			t.Fatalf("el intento %d se cortó antes de tiempo", i+1)
		}
	}
	if l.Allow("mismo") {
		t.Error("el cuarto intento pasó, con límite de 3")
	}
	// Cada clave lleva su propia cuenta: que una IP se pase no puede dejar fuera
	// a las demás.
	if !l.Allow("otro") {
		t.Error("una clave distinta heredó el límite de la primera")
	}
}

func TestLaVentanaSeOlvidaDeLoViejo(t *testing.T) {
	l := NewLimiter(1, 30*time.Millisecond)
	if !l.Allow("ip") {
		t.Fatal("el primer intento se cortó")
	}
	if l.Allow("ip") {
		t.Fatal("el segundo intento inmediato pasó")
	}
	time.Sleep(40 * time.Millisecond)
	if !l.Allow("ip") {
		t.Error("pasada la ventana, el intento debería volver a pasar")
	}
}

// El barrido es lo que separa esto de una fuga de memoria: contra IPs de
// internet el mapa crecería sin techo, y quien las genera es quien ataca.
func TestLasClavesMuertasSeBarren(t *testing.T) {
	l := NewLimiter(5, 20*time.Millisecond)
	for _, ip := range []string{"1.1.1.1", "2.2.2.2", "3.3.3.3"} {
		l.Allow(ip)
	}
	if len(l.hits) != 3 {
		t.Fatalf("hits = %d, se esperaban 3", len(l.hits))
	}

	// Pasada la ventana, la primera llamada nueva se lleva por delante a las
	// tres viejas y deja solo la suya.
	time.Sleep(30 * time.Millisecond)
	l.Allow("4.4.4.4")
	if len(l.hits) != 1 {
		t.Errorf("hits = %d tras el barrido, se esperaba 1", len(l.hits))
	}
}

// Detrás de Caddy la IP real es la ÚLTIMA de X-Forwarded-For. Si se leyera la
// primera, cualquiera se saltaría el límite mandando una cabecera distinta en
// cada petición.
func TestLaIPSeLeeDelFinalDeXForwardedFor(t *testing.T) {
	r := httptest.NewRequest(http.MethodPost, "/v1/auth/login", nil)
	r.RemoteAddr = "10.0.0.5:34567"
	r.Header.Set("X-Forwarded-For", "1.2.3.4, 203.0.113.9")

	if ip := ClientIP(r); ip != "203.0.113.9" {
		t.Errorf("ClientIP = %q, se esperaba la última entrada 203.0.113.9", ip)
	}
}

func TestSinProxySeUsaRemoteAddr(t *testing.T) {
	r := httptest.NewRequest(http.MethodPost, "/v1/auth/login", nil)
	r.RemoteAddr = "192.168.1.20:51000"

	if ip := ClientIP(r); ip != "192.168.1.20" {
		t.Errorf("ClientIP = %q, se esperaba 192.168.1.20", ip)
	}
}

func TestElLimitePorIPResponde429(t *testing.T) {
	l := NewLimiter(1, time.Minute)
	h := l.PorIP(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))

	pide := func() *httptest.ResponseRecorder {
		r := httptest.NewRequest(http.MethodPost, "/v1/auth/login", nil)
		r.RemoteAddr = "198.51.100.7:40000"
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		return w
	}

	if got := pide().Code; got != http.StatusOK {
		t.Fatalf("la primera petición devolvió %d", got)
	}
	w := pide()
	if w.Code != http.StatusTooManyRequests {
		t.Errorf("la segunda devolvió %d, se esperaba 429", w.Code)
	}
	if w.Header().Get("Retry-After") == "" {
		t.Error("falta Retry-After: la app no sabe cuánto esperar")
	}
}
