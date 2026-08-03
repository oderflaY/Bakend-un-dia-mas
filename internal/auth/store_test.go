package auth

import (
	"errors"
	"testing"
	"time"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/testdb"
)

func TestCreateUserRolYCorreo(t *testing.T) {
	pool := testdb.New(t)
	ctx := t.Context()
	store := NewStore(pool)

	u, err := store.CreateUser(ctx, "Ana@UnDiaMas.mx", "hash", "Ana")
	if err != nil {
		t.Fatalf("CreateUser: %v", err)
	}
	// El rol nunca viene del cliente: register siempre crea paciente.
	if u.Role != RolePatient {
		t.Errorf("rol = %q, se esperaba patient", u.Role)
	}

	// El correo se guarda en minúsculas, así que el mismo con otra caja choca.
	if _, err := store.CreateUser(ctx, "ana@undiamas.mx", "hash", "Ana otra vez"); !errors.Is(err, ErrEmailTaken) {
		t.Errorf("correo duplicado = %v, se esperaba ErrEmailTaken", err)
	}

	// Y se puede iniciar sesión escribiéndolo como sea.
	if _, err := store.UserByEmail(ctx, "ANA@undiamas.MX"); err != nil {
		t.Errorf("UserByEmail con otra caja: %v", err)
	}

	// El tracker nace con el usuario: ninguna lectura posterior tiene que
	// tratar el caso "perfil sin tracker".
	var trackers int
	if err := pool.QueryRow(ctx,
		`SELECT count(*) FROM sobriety_trackers WHERE user_id = $1`, u.ID).Scan(&trackers); err != nil {
		t.Fatalf("conteo de trackers: %v", err)
	}
	if trackers != 1 {
		t.Errorf("sobriety_trackers = %d filas, se esperaba 1", trackers)
	}
}

// Un refresh token se gasta una sola vez. Es la propiedad que hace que robar uno
// usado no sirva de nada, y la que un UPDATE ... RETURNING garantiza aunque
// lleguen dos peticiones a la vez.
func TestConsumeRefreshTokenSoloUnaVez(t *testing.T) {
	pool := testdb.New(t)
	ctx := t.Context()
	store := NewStore(pool)

	u, err := store.CreateUser(ctx, "sesion@undiamas.mx", "hash", "Sesión")
	if err != nil {
		t.Fatalf("CreateUser: %v", err)
	}

	raw, hash, err := NewRefreshToken()
	if err != nil {
		t.Fatalf("NewRefreshToken: %v", err)
	}
	if err := store.SaveRefreshToken(ctx, hash, u.ID, time.Now().Add(time.Hour)); err != nil {
		t.Fatalf("SaveRefreshToken: %v", err)
	}

	got, err := store.ConsumeRefreshToken(ctx, HashToken(raw))
	if err != nil {
		t.Fatalf("primer consumo: %v", err)
	}
	if got.ID != u.ID {
		t.Errorf("el token devolvió a otro usuario: %s", got.ID)
	}
	if _, err := store.ConsumeRefreshToken(ctx, HashToken(raw)); !errors.Is(err, ErrInvalidLogin) {
		t.Errorf("segundo consumo = %v, se esperaba ErrInvalidLogin", err)
	}
}

func TestRefreshTokenExpiradoYRevocado(t *testing.T) {
	pool := testdb.New(t)
	ctx := t.Context()
	store := NewStore(pool)

	u, err := store.CreateUser(ctx, "expira@undiamas.mx", "hash", "Expira")
	if err != nil {
		t.Fatalf("CreateUser: %v", err)
	}

	_, viejo, _ := NewRefreshToken()
	if err := store.SaveRefreshToken(ctx, viejo, u.ID, time.Now().Add(-time.Minute)); err != nil {
		t.Fatalf("SaveRefreshToken: %v", err)
	}
	if _, err := store.ConsumeRefreshToken(ctx, viejo); !errors.Is(err, ErrInvalidLogin) {
		t.Errorf("token expirado = %v, se esperaba ErrInvalidLogin", err)
	}

	rawVivo, vivo, _ := NewRefreshToken()
	if err := store.SaveRefreshToken(ctx, vivo, u.ID, time.Now().Add(time.Hour)); err != nil {
		t.Fatalf("SaveRefreshToken: %v", err)
	}
	// logout revoca todos los tokens del usuario, no solo el de esta sesión.
	if err := store.RevokeAllTokens(ctx, u.ID); err != nil {
		t.Fatalf("RevokeAllTokens: %v", err)
	}
	if _, err := store.ConsumeRefreshToken(ctx, HashToken(rawVivo)); !errors.Is(err, ErrInvalidLogin) {
		t.Errorf("token revocado = %v, se esperaba ErrInvalidLogin", err)
	}
}
