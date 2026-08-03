package journal

import (
	"testing"

	"github.com/jackc/pgx/v5"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/testdb"
)

// El aislamiento entre usuarios es la sustitución directa de firestore.rules:
// aquí no hay reglas, hay un WHERE user_id = $1 en cada consulta. Esto lo prueba
// sobre la base real, que es donde vive la garantía.
func TestNadieToaElDiarioAjeno(t *testing.T) {
	pool := testdb.New(t)
	ctx := t.Context()
	store := NewStore(pool)

	yo := testdb.NewUser(t, pool, "yo@undiamas.mx")
	otro := testdb.NewUser(t, pool, "otro@undiamas.mx")

	mia, err := store.Create(ctx, yo, "hoy fue difícil")
	if err != nil {
		t.Fatalf("Create: %v", err)
	}

	if _, err := store.ByID(ctx, otro, mia.ID); err != pgx.ErrNoRows {
		t.Errorf("ByID ajeno = %v, se esperaba ErrNoRows", err)
	}
	if err := store.Delete(ctx, otro, mia.ID); err != pgx.ErrNoRows {
		t.Errorf("Delete ajeno = %v, se esperaba ErrNoRows", err)
	}

	// Y sigue ahí después del intento.
	if _, err := store.ByID(ctx, yo, mia.ID); err != nil {
		t.Errorf("la entrada propia desapareció: %v", err)
	}

	ajenas, err := store.List(ctx, otro, 20)
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(ajenas) != 0 {
		t.Errorf("List del otro usuario devolvió %d entradas", len(ajenas))
	}

	// El borrado propio sí funciona: el diario es lo único que el usuario puede
	// retirar del todo.
	if err := store.Delete(ctx, yo, mia.ID); err != nil {
		t.Fatalf("Delete propio: %v", err)
	}
	if _, err := store.ByID(ctx, yo, mia.ID); err != pgx.ErrNoRows {
		t.Errorf("tras borrar, ByID = %v, se esperaba ErrNoRows", err)
	}
}

// La paginación va en la consulta, no en memoria (#14).
func TestListRespetaElLimite(t *testing.T) {
	pool := testdb.New(t)
	ctx := t.Context()
	store := NewStore(pool)

	userID := testdb.NewUser(t, pool, "yo@undiamas.mx")
	for _, texto := range []string{"una", "dos", "tres"} {
		if _, err := store.Create(ctx, userID, texto); err != nil {
			t.Fatalf("Create(%s): %v", texto, err)
		}
	}

	dos, err := store.List(ctx, userID, 2)
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(dos) != 2 {
		t.Fatalf("List(limit=2) devolvió %d entradas", len(dos))
	}
	// Más reciente primero.
	if dos[0].Content != "tres" {
		t.Errorf("primera entrada = %q, se esperaba \"tres\"", dos[0].Content)
	}
}
