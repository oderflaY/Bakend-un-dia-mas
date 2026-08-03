package notify

import (
	"encoding/json"
	"testing"
	"time"
)

func TestPublishLlegaSoloAlDestinatario(t *testing.T) {
	hub := NewHub()

	ana, cerrarAna := hub.Subscribe("ana")
	defer cerrarAna()
	beto, cerrarBeto := hub.Subscribe("beto")
	defer cerrarBeto()

	hub.Publish("ana", Event{Type: "alert", Payload: map[string]any{"id": "a1"}})

	select {
	case raw := <-ana:
		var ev Event
		if err := json.Unmarshal(raw, &ev); err != nil {
			t.Fatalf("evento ilegible: %v", err)
		}
		if ev.Type != "alert" {
			t.Errorf("type = %q", ev.Type)
		}
		if ev.CreatedAt.IsZero() {
			t.Error("createdAt debería rellenarse solo")
		}
	case <-time.After(time.Second):
		t.Fatal("ana no recibió el evento")
	}

	select {
	case <-beto:
		t.Fatal("beto recibió una alerta ajena")
	case <-time.After(50 * time.Millisecond):
	}
}

func TestPublishNoBloqueaConUnClienteLento(t *testing.T) {
	hub := NewHub()
	_, cerrar := hub.Subscribe("ana") // nadie lee del canal
	defer cerrar()

	hecho := make(chan struct{})
	go func() {
		for range 100 { // supera con creces el buffer de 8
			hub.Publish("ana", Event{Type: "alert"})
		}
		close(hecho)
	}()

	select {
	case <-hecho:
	case <-time.After(2 * time.Second):
		t.Fatal("Publish se bloqueó por un cliente que no consume")
	}
}

func TestVariasConexionesDelMismoUsuario(t *testing.T) {
	hub := NewHub()
	movil, cerrarMovil := hub.Subscribe("ana")
	defer cerrarMovil()
	tablet, cerrarTablet := hub.Subscribe("ana")
	defer cerrarTablet()

	if !hub.Connected("ana") {
		t.Fatal("Connected debería ser true")
	}
	hub.Publish("ana", Event{Type: "alert"})

	for nombre, ch := range map[string]<-chan []byte{"móvil": movil, "tablet": tablet} {
		select {
		case <-ch:
		case <-time.After(time.Second):
			t.Errorf("%s no recibió el evento", nombre)
		}
	}
}

func TestUnsubscribeEsIdempotente(t *testing.T) {
	hub := NewHub()
	_, cerrar := hub.Subscribe("ana")
	cerrar()
	cerrar() // un doble cierre no debe entrar en pánico

	if hub.Connected("ana") {
		t.Error("no debería quedar ninguna conexión")
	}
}
