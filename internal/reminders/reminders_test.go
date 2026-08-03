package reminders

import (
	"slices"
	"testing"
	"time"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/testdb"
)

// due() es toda la lógica del hallazgo #8 concentrada en una consulta, así que
// es lo único que merece test: a quién avisa, a quién no y cuántas veces.
func TestDue(t *testing.T) {
	// "Hace cinco minutos" cruzaría a ayer si el test corre justo tras la
	// medianoche UTC, y entonces la hora configurada quedaría en el futuro.
	if n := time.Now().UTC(); n.Hour() == 0 && n.Minute() < 10 {
		t.Skip("demasiado cerca de la medianoche UTC para fijar una hora pasada")
	}

	pool := testdb.New(t)
	ctx := t.Context()
	store := NewStore(pool)

	// La hora se fija a "hace cinco minutos en la zona del usuario", así el test
	// no depende de a qué hora del día se ejecute.
	configurar := func(userID string, enabled bool) {
		t.Helper()
		_, err := pool.Exec(ctx, `
			INSERT INTO reminder_settings (user_id, enabled, hour_local, minute_local, timezone)
			SELECT $1, $2,
			       EXTRACT(HOUR   FROM (now() AT TIME ZONE 'UTC') - interval '5 minutes')::int,
			       EXTRACT(MINUTE FROM (now() AT TIME ZONE 'UTC') - interval '5 minutes')::int,
			       'UTC'`, userID, enabled)
		if err != nil {
			t.Fatalf("configurar recordatorio: %v", err)
		}
	}

	tocaAviso := testdb.NewUser(t, pool, "avisa@undiamas.mx")
	apagado := testdb.NewUser(t, pool, "apagado@undiamas.mx")
	yaHizoCheckIn := testdb.NewUser(t, pool, "hecho@undiamas.mx")
	sinConfigurar := testdb.NewUser(t, pool, "nada@undiamas.mx")

	configurar(tocaAviso, true)
	configurar(apagado, false)
	configurar(yaHizoCheckIn, true)
	_ = sinConfigurar

	if _, err := pool.Exec(ctx,
		`INSERT INTO check_ins (user_id) VALUES ($1)`, yaHizoCheckIn); err != nil {
		t.Fatalf("check-in de hoy: %v", err)
	}

	pendientes, err := store.due(ctx)
	if err != nil {
		t.Fatalf("due: %v", err)
	}
	if !slices.Contains(pendientes, tocaAviso) {
		t.Errorf("due() no incluyó al usuario que sí toca: %v", pendientes)
	}
	if slices.Contains(pendientes, apagado) {
		t.Error("due() avisó a quien tiene el recordatorio apagado")
	}
	if slices.Contains(pendientes, yaHizoCheckIn) {
		t.Error("due() avisó a quien ya hizo su check-in de hoy")
	}
	if slices.Contains(pendientes, sinConfigurar) {
		t.Error("due() avisó a quien nunca configuró recordatorio")
	}

	// La segunda pasada del ticker, dentro de la misma ventana, no repite: es lo
	// que hace last_sent_on al escribirse en el mismo UPDATE que selecciona.
	repetidos, err := store.due(ctx)
	if err != nil {
		t.Fatalf("due (segunda pasada): %v", err)
	}
	if len(repetidos) != 0 {
		t.Errorf("due() repitió el aviso: %v", repetidos)
	}
}

func TestGetDevuelveDefaultsSinFila(t *testing.T) {
	pool := testdb.New(t)

	userID := testdb.NewUser(t, pool, "sin-fila@undiamas.mx")
	st, err := NewStore(pool).Get(t.Context(), userID)
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	// Apagado por defecto: el recordatorio se pide, no se impone.
	if st.Enabled {
		t.Error("sin configurar, el recordatorio debería venir apagado")
	}
	if st.Hour != 21 {
		t.Errorf("hora por defecto = %d, se esperaba 21", st.Hour)
	}
}

func TestSaveEsUpsert(t *testing.T) {
	pool := testdb.New(t)
	ctx := t.Context()
	store := NewStore(pool)

	userID := testdb.NewUser(t, pool, "upsert@undiamas.mx")
	primera := Settings{Enabled: true, Hour: 8, Minute: 30, Timezone: "America/Mexico_City"}
	if err := store.Save(ctx, userID, primera); err != nil {
		t.Fatalf("primer Save: %v", err)
	}
	segunda := Settings{Enabled: false, Hour: 22, Minute: 15, Timezone: "UTC"}
	if err := store.Save(ctx, userID, segunda); err != nil {
		t.Fatalf("segundo Save: %v", err)
	}

	st, err := store.Get(ctx, userID)
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	if st.Enabled || st.Hour != 22 || st.Minute != 15 || st.Timezone != "UTC" {
		t.Errorf("Get = %+v, se esperaba la segunda configuración", st)
	}
}
