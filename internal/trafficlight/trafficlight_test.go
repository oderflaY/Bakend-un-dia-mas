package trafficlight

import (
	"encoding/json"
	"testing"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/alerts"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/notify"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/risk"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/testdb"
)

// El escenario del hallazgo #1 de punta a punta: un rojo tiene que dejar
// registro del semáforo, alerta persistida, tracker al día y evento en el canal.
// Que las tres escrituras sean una sola transacción es lo que impide el estado
// intermedio que el trigger de Cloud Functions sí podía dejar.
func TestRecordRojoDisparaTodo(t *testing.T) {
	pool := testdb.New(t)
	ctx := t.Context()

	hub := notify.NewHub()
	svc := NewService(pool, hub, alerts.NewService(pool, hub))
	userID := testdb.NewUser(t, pool, "rojo@undiamas.mx")

	if _, err := pool.Exec(ctx, `
		INSERT INTO emergency_contacts (user_id, position, nombre, telefono, rol)
		VALUES ($1, 0, 'Ana', '555', 'hermana')`, userID); err != nil {
		t.Fatalf("contacto de confianza: %v", err)
	}

	// Suscribirse antes de publicar: el hub no guarda historia.
	events, unsubscribe := hub.Subscribe(userID)
	defer unsubscribe()

	res, err := svc.Record(ctx, userID, Evaluation{
		Status:           risk.Red,
		Reason:           "craving 9",
		SuggestedActions: []string{"llamar a Ana"},
	}, "Semáforo en rojo")
	if err != nil {
		t.Fatalf("Record: %v", err)
	}
	if res.Alert == nil {
		t.Fatal("un rojo tiene que generar alerta")
	}
	if res.Entry.TriggerLevel != 5 {
		t.Errorf("triggerLevel = %d, se esperaba 5 por defecto en rojo", res.Entry.TriggerLevel)
	}

	var logs, alertas int
	var semaforo string
	if err := pool.QueryRow(ctx, `
		SELECT (SELECT count(*) FROM traffic_light_logs WHERE user_id = $1),
		       (SELECT count(*) FROM alerts WHERE user_id = $1),
		       (SELECT traffic_light::text FROM sobriety_trackers WHERE user_id = $1)`,
		userID).Scan(&logs, &alertas, &semaforo); err != nil {
		t.Fatalf("lectura de comprobación: %v", err)
	}
	// Uno y solo uno: el registro del semáforo lo escribe este paquete, no
	// también alerts, que es lo que duplicaba filas antes de separarlos.
	if logs != 1 {
		t.Errorf("traffic_light_logs = %d filas, se esperaba 1", logs)
	}
	if alertas != 1 {
		t.Errorf("alerts = %d filas, se esperaba 1", alertas)
	}
	if semaforo != "red" {
		t.Errorf("tracker.traffic_light = %q, se esperaba red", semaforo)
	}

	// Dos eventos: el del semáforo y el de la alerta, en ese orden.
	tipos := []string{}
	for range 2 {
		select {
		case data := <-events:
			var ev notify.Event
			if err := json.Unmarshal(data, &ev); err != nil {
				t.Fatalf("evento ilegible: %v", err)
			}
			tipos = append(tipos, ev.Type)
		default:
			t.Fatalf("faltan eventos en el canal, llegaron %v", tipos)
		}
	}
	if tipos[0] != "traffic_light" || tipos[1] != "alert" {
		t.Errorf("eventos = %v, se esperaba [traffic_light alert]", tipos)
	}
}

func TestRecordVerdeNoLevantaAlerta(t *testing.T) {
	pool := testdb.New(t)
	ctx := t.Context()

	hub := notify.NewHub()
	svc := NewService(pool, hub, alerts.NewService(pool, hub))
	userID := testdb.NewUser(t, pool, "verde@undiamas.mx")

	res, err := svc.Record(ctx, userID, Evaluation{Status: risk.Green, Reason: "buen día"}, "")
	if err != nil {
		t.Fatalf("Record: %v", err)
	}
	if res.Alert != nil {
		t.Error("un verde no puede generar alerta")
	}

	var alertas int
	if err := pool.QueryRow(ctx,
		`SELECT count(*) FROM alerts WHERE user_id = $1`, userID).Scan(&alertas); err != nil {
		t.Fatalf("conteo de alertas: %v", err)
	}
	if alertas != 0 {
		t.Errorf("alerts = %d filas, se esperaban 0", alertas)
	}
}

func TestListSoloVeLoPropio(t *testing.T) {
	pool := testdb.New(t)
	ctx := t.Context()

	hub := notify.NewHub()
	svc := NewService(pool, hub, alerts.NewService(pool, hub))
	yo := testdb.NewUser(t, pool, "yo@undiamas.mx")
	otro := testdb.NewUser(t, pool, "otro@undiamas.mx")

	if _, err := svc.Record(ctx, otro, Evaluation{Status: risk.Yellow, Reason: "suyo"}, ""); err != nil {
		t.Fatalf("Record del otro: %v", err)
	}

	mios, err := svc.List(ctx, yo, 20)
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(mios) != 0 {
		t.Errorf("List devolvió %d filas ajenas", len(mios))
	}
}
