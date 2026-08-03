package therapist

import (
	"errors"
	"testing"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/testdb"
)

// El vínculo lo concede el paciente y solo hacia una cuenta con rol therapist.
// Es la corrección del "un terapeuta puede leer a cualquier paciente" que
// permitían las reglas de Firestore.
func TestLinkSoloConTerapeutas(t *testing.T) {
	pool := testdb.New(t)
	ctx := t.Context()
	store := NewStore(pool)

	paciente := testdb.NewUser(t, pool, "paciente@undiamas.mx")
	otroPaciente := testdb.NewUser(t, pool, "otro@undiamas.mx")
	terapeuta := testdb.NewTherapist(t, pool, "tera@undiamas.mx")

	if _, err := store.Link(ctx, paciente, "otro@undiamas.mx"); !errors.Is(err, ErrNoLink) {
		t.Errorf("vincularse con un paciente devolvió %v, se esperaba ErrNoLink", err)
	}
	if _, err := store.Link(ctx, paciente, "nadie@undiamas.mx"); !errors.Is(err, ErrNoLink) {
		t.Errorf("vincularse con un correo inexistente devolvió %v, se esperaba ErrNoLink", err)
	}
	_ = otroPaciente

	p, err := store.Link(ctx, paciente, "tera@undiamas.mx")
	if err != nil {
		t.Fatalf("Link: %v", err)
	}
	if p.ID != terapeuta {
		t.Errorf("Link devolvió %s, se esperaba %s", p.ID, terapeuta)
	}

	// Idempotente: repetirlo no duplica ni falla.
	if _, err := store.Link(ctx, paciente, "TERA@undiamas.mx"); err != nil {
		t.Errorf("Link repetido (y con mayúsculas) falló: %v", err)
	}
	pacientes, err := store.Patients(ctx, terapeuta)
	if err != nil {
		t.Fatalf("Patients: %v", err)
	}
	if len(pacientes) != 1 {
		t.Errorf("Patients = %d, se esperaba 1", len(pacientes))
	}
}

func TestEnsureLink(t *testing.T) {
	pool := testdb.New(t)
	ctx := t.Context()
	store := NewStore(pool)

	paciente := testdb.NewUser(t, pool, "paciente@undiamas.mx")
	ajeno := testdb.NewUser(t, pool, "ajeno@undiamas.mx")
	terapeuta := testdb.NewTherapist(t, pool, "tera@undiamas.mx")

	if _, err := store.Link(ctx, paciente, "tera@undiamas.mx"); err != nil {
		t.Fatalf("Link: %v", err)
	}

	if err := store.EnsureLink(ctx, terapeuta, paciente); err != nil {
		t.Errorf("EnsureLink con vínculo: %v", err)
	}
	if err := store.EnsureLink(ctx, terapeuta, ajeno); !errors.Is(err, ErrNoLink) {
		t.Errorf("EnsureLink sin vínculo = %v, se esperaba ErrNoLink", err)
	}
	// Un id que ni siquiera es UUID es un paciente inexistente, no un 500.
	if err := store.EnsureLink(ctx, terapeuta, "no-soy-un-uuid"); !errors.Is(err, ErrNoLink) {
		t.Errorf("EnsureLink con id inválido = %v, se esperaba ErrNoLink", err)
	}

	// El paciente retira el acceso y el vínculo deja de existir en el acto.
	if err := store.Unlink(ctx, paciente, terapeuta); err != nil {
		t.Fatalf("Unlink: %v", err)
	}
	if err := store.EnsureLink(ctx, terapeuta, paciente); !errors.Is(err, ErrNoLink) {
		t.Errorf("tras Unlink, EnsureLink = %v, se esperaba ErrNoLink", err)
	}
	if err := store.Unlink(ctx, paciente, terapeuta); !errors.Is(err, ErrNoLink) {
		t.Errorf("Unlink repetido = %v, se esperaba ErrNoLink", err)
	}
}

// Una nota clínica es del profesional que la escribió: otro terapeuta del mismo
// paciente no la ve.
func TestNotasSonDeSuAutor(t *testing.T) {
	pool := testdb.New(t)
	ctx := t.Context()
	store := NewStore(pool)

	paciente := testdb.NewUser(t, pool, "paciente@undiamas.mx")
	tera1 := testdb.NewTherapist(t, pool, "uno@undiamas.mx")
	tera2 := testdb.NewTherapist(t, pool, "dos@undiamas.mx")
	for _, email := range []string{"uno@undiamas.mx", "dos@undiamas.mx"} {
		if _, err := store.Link(ctx, paciente, email); err != nil {
			t.Fatalf("Link con %s: %v", email, err)
		}
	}

	if _, err := store.AddNote(ctx, tera1, paciente, "primera sesión"); err != nil {
		t.Fatalf("AddNote: %v", err)
	}

	mias, err := store.Notes(ctx, tera1, paciente, 20)
	if err != nil {
		t.Fatalf("Notes(tera1): %v", err)
	}
	if len(mias) != 1 {
		t.Errorf("el autor ve %d notas, se esperaba 1", len(mias))
	}

	ajenas, err := store.Notes(ctx, tera2, paciente, 20)
	if err != nil {
		t.Fatalf("Notes(tera2): %v", err)
	}
	if len(ajenas) != 0 {
		t.Errorf("otro terapeuta ve %d notas ajenas, se esperaba 0", len(ajenas))
	}
}

func TestSesionesSoloLasTuyas(t *testing.T) {
	pool := testdb.New(t)
	ctx := t.Context()
	store := NewStore(pool)

	paciente := testdb.NewUser(t, pool, "paciente@undiamas.mx")
	terapeuta := testdb.NewTherapist(t, pool, "tera@undiamas.mx")
	intruso := testdb.NewTherapist(t, pool, "intruso@undiamas.mx")
	if _, err := store.Link(ctx, paciente, "tera@undiamas.mx"); err != nil {
		t.Fatalf("Link: %v", err)
	}

	ses, err := store.CreateSession(ctx, terapeuta, paciente, nil, "primera cita")
	if err != nil {
		t.Fatalf("CreateSession: %v", err)
	}
	if ses.Status != "scheduled" {
		t.Errorf("status inicial = %q, se esperaba scheduled", ses.Status)
	}

	// El paciente ve su sesión.
	suyas, err := store.Sessions(ctx, "patient_id", paciente, 20)
	if err != nil {
		t.Fatalf("Sessions(paciente): %v", err)
	}
	if len(suyas) != 1 {
		t.Errorf("el paciente ve %d sesiones, se esperaba 1", len(suyas))
	}

	// Otro terapeuta no puede cerrarla.
	completada := "completed"
	if _, err := store.UpdateSession(ctx, intruso, ses.ID, &completada, nil, nil); err == nil {
		t.Error("un terapeuta ajeno pudo actualizar la sesión")
	}

	actualizada, err := store.UpdateSession(ctx, terapeuta, ses.ID, &completada, nil, nil)
	if err != nil {
		t.Fatalf("UpdateSession: %v", err)
	}
	if actualizada.Status != "completed" {
		t.Errorf("status = %q, se esperaba completed", actualizada.Status)
	}
	if actualizada.Notes != "primera cita" {
		t.Errorf("el COALESCE pisó las notas: %q", actualizada.Notes)
	}
}
