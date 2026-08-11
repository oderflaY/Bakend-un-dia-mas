package auth

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/addiction"
	"github.com/oderflaY/Bakend-un-dia-mas/internal/testdb"
)

// almacen es el atajo para los tests que no necesitan el pool aparte.
func almacen(t *testing.T) (*Store, context.Context) {
	t.Helper()
	return NewStore(testdb.New(t)), t.Context()
}

func TestCreateUserRolYCorreo(t *testing.T) {
	pool := testdb.New(t)
	ctx := t.Context()
	store := NewStore(pool)

	u, err := store.CreateUser(ctx, NewUser{Email: "Ana@UnDiaMas.mx", Hash: "hash", DisplayName: "Ana"})
	if err != nil {
		t.Fatalf("CreateUser: %v", err)
	}
	// El rol nunca viene del cliente: register siempre crea paciente.
	if u.Role != RolePatient {
		t.Errorf("rol = %q, se esperaba patient", u.Role)
	}

	// El correo se guarda en minúsculas, así que el mismo con otra caja choca.
	if _, err := store.CreateUser(ctx, NewUser{Email: "ana@undiamas.mx", Hash: "hash", DisplayName: "Ana otra vez"}); !errors.Is(err, ErrEmailTaken) {
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

	u, err := store.CreateUser(ctx, NewUser{Email: "sesion@undiamas.mx", Hash: "hash", DisplayName: "Sesión"})
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

	u, err := store.CreateUser(ctx, NewUser{Email: "expira@undiamas.mx", Hash: "hash", DisplayName: "Expira"})
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

// El perfil de recuperación entra en el alta y sale en la lectura: es lo que
// permite que la app sepa, en el primer login, si tiene que enseñar onboarding.
func TestElPerfilDeRecuperacionSobreviveAlAlta(t *testing.T) {
	store, ctx := almacen(t)

	desde := time.Date(2015, 3, 1, 0, 0, 0, 0, time.UTC)
	creado, err := store.CreateUser(ctx, NewUser{
		Email:         "perfil@undiamas.mx",
		Hash:          "hash",
		DisplayName:   "Perfil",
		Adicciones:    []addiction.Type{addiction.Alcohol, addiction.Tabaco},
		Principal:     addiction.Alcohol,
		ConsumoDesde:  &desde,
		EnTratamiento: true,
	})
	if err != nil {
		t.Fatalf("CreateUser: %v", err)
	}
	if !creado.OnboardingCompleto() {
		t.Error("con adicción principal el onboarding está completo")
	}

	leido, err := store.UserByEmail(ctx, "perfil@undiamas.mx")
	if err != nil {
		t.Fatalf("UserByEmail: %v", err)
	}
	if len(leido.Adicciones) != 2 || leido.Adicciones[0] != addiction.Alcohol {
		t.Errorf("adicciones = %v", leido.Adicciones)
	}
	if leido.Principal != addiction.Alcohol {
		t.Errorf("principal = %q", leido.Principal)
	}
	if !leido.EnTratamiento {
		t.Error("enTratamiento se perdió")
	}
	if leido.ConsumoDesde == nil || leido.ConsumoDesde.Format(time.DateOnly) != "2015-03-01" {
		t.Errorf("consumoDesde = %v", leido.ConsumoDesde)
	}
}

// Registrarse sin contestar nada tiene que seguir funcionando: el onboarding se
// puede completar después.
func TestSePuedeAltaSinPerfilDeRecuperacion(t *testing.T) {
	store, ctx := almacen(t)

	u, err := store.CreateUser(ctx, NewUser{Email: "sin-perfil@undiamas.mx", Hash: "hash"})
	if err != nil {
		t.Fatalf("CreateUser: %v", err)
	}
	if u.OnboardingCompleto() {
		t.Error("sin adicción principal el onboarding NO está completo")
	}
	if u.Adicciones == nil {
		t.Error("adicciones debe ser lista vacía y no nil: la app no debe recibir null")
	}
}
