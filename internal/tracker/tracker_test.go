package tracker

import (
	"testing"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/testdb"
)

// El hallazgo #6: la recaída tiene que dejar rastro y conservar el récord. Aquí
// se comprueba lo que antes no escribía nadie.
func TestRelapseGuardaRecordYReinicia(t *testing.T) {
	pool := testdb.New(t)
	ctx := t.Context()
	store := NewStore(pool)

	userID := testdb.NewUser(t, pool, "racha@undiamas.mx")

	// Diez días de racha, puestos a mano para no depender del reloj.
	if _, err := pool.Exec(ctx, `
		UPDATE sobriety_trackers SET start_date = now() - interval '10 days'
		WHERE user_id = $1`, userID); err != nil {
		t.Fatalf("preparar la racha: %v", err)
	}

	rel, err := store.Relapse(ctx, userID, "mal día", []string{"fiesta"})
	if err != nil {
		t.Fatalf("Relapse: %v", err)
	}

	// 10 días en segundos, con holgura por el tiempo que tarde el test.
	const diezDias = 10 * 24 * 60 * 60
	if rel.PreviousStreakSecs < diezDias-60 || rel.PreviousStreakSecs > diezDias+60 {
		t.Errorf("previousStreakSeconds = %d, se esperaban ~%d", rel.PreviousStreakSecs, diezDias)
	}

	t2, err := store.Get(ctx, userID)
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	if t2.StreakSeconds > 60 {
		t.Errorf("la racha no se reinició: %d segundos", t2.StreakSeconds)
	}
	if t2.RecordSeconds < diezDias-60 {
		t.Errorf("el récord se perdió: %d segundos", t2.RecordSeconds)
	}
	if t2.TrafficLight.Code() != "red" {
		t.Errorf("tras una recaída el semáforo debería quedar en rojo, quedó en %s", t2.TrafficLight)
	}

	lista, err := store.Relapses(ctx, userID, 10)
	if err != nil {
		t.Fatalf("Relapses: %v", err)
	}
	if len(lista) != 1 || lista[0].Note != "mal día" {
		t.Errorf("Relapses = %+v, se esperaba una entrada con la nota", lista)
	}
}

// Una segunda recaída al día siguiente no puede rebajar el récord anterior.
func TestRelapseNoRebajaElRecord(t *testing.T) {
	pool := testdb.New(t)
	ctx := t.Context()
	store := NewStore(pool)

	userID := testdb.NewUser(t, pool, "record@undiamas.mx")
	if _, err := pool.Exec(ctx, `
		UPDATE sobriety_trackers SET start_date = now() - interval '30 days'
		WHERE user_id = $1`, userID); err != nil {
		t.Fatalf("preparar la racha: %v", err)
	}

	if _, err := store.Relapse(ctx, userID, "", nil); err != nil {
		t.Fatalf("primera recaída: %v", err)
	}
	if _, err := store.Relapse(ctx, userID, "", nil); err != nil {
		t.Fatalf("segunda recaída: %v", err)
	}

	trk, err := store.Get(ctx, userID)
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	const treintaDias = 30 * 24 * 60 * 60
	if trk.RecordSeconds < treintaDias-60 {
		t.Errorf("récord = %d, la segunda recaída lo pisó (se esperaban ~%d)",
			trk.RecordSeconds, treintaDias)
	}
}

func TestGetSinTrackerDaNoRows(t *testing.T) {
	pool := testdb.New(t)

	// Un id que no corresponde a nadie: la lectura no puede devolver el tracker
	// de otro ni un cero silencioso.
	if _, err := NewStore(pool).Get(t.Context(), "00000000-0000-0000-0000-000000000000"); err == nil {
		t.Error("Get de un usuario inexistente debería fallar")
	}
}
