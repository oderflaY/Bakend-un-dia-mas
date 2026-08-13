package auth

import (
	"errors"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"golang.org/x/crypto/bcrypt"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/testdb"
)

// conCodigo crea a alguien con un código de recuperación vivo y devuelve el
// código en claro, que es lo único que en producción solo existe dentro del
// correo.
func conCodigo(t *testing.T, s *Store, pool *pgxpool.Pool, correo string) (userID, codigo string) {
	t.Helper()
	userID = testdb.NewUser(t, pool, correo)

	codigo = "482913"
	hash, err := bcrypt.GenerateFromPassword([]byte(codigo), bcrypt.MinCost)
	if err != nil {
		t.Fatalf("hash del código: %v", err)
	}
	if err := s.CrearReset(t.Context(), userID, string(hash), time.Now().Add(15*time.Minute)); err != nil {
		t.Fatalf("CrearReset: %v", err)
	}
	return userID, codigo
}

func TestElCodigoCorrectoCambiaLaPasswordYCierraLasSesiones(t *testing.T) {
	pool := testdb.New(t)
	s := NewStore(pool)
	ctx := t.Context()

	userID, codigo := conCodigo(t, s, pool, "olvida@undiamas.mx")

	// Una sesión abierta de antes, que tiene que morir con el cambio.
	if err := s.SaveRefreshToken(ctx, "hash-viejo", userID, time.Now().Add(time.Hour)); err != nil {
		t.Fatalf("SaveRefreshToken: %v", err)
	}

	nueva, err := bcrypt.GenerateFromPassword([]byte("contraseña-nueva"), bcrypt.MinCost)
	if err != nil {
		t.Fatalf("hash: %v", err)
	}
	if err := s.UsarReset(ctx, "olvida@undiamas.mx", codigo, string(nueva)); err != nil {
		t.Fatalf("UsarReset: %v", err)
	}

	u, err := s.UserByEmail(ctx, "olvida@undiamas.mx")
	if err != nil {
		t.Fatalf("UserByEmail: %v", err)
	}
	if bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte("contraseña-nueva")) != nil {
		t.Error("la contraseña no quedó cambiada")
	}

	// La sesión vieja ya no sirve: quien hubiera entrado con la contraseña
	// anterior queda fuera, que es el motivo de recuperar la cuenta.
	if _, err := s.ConsumeRefreshToken(ctx, "hash-viejo"); !errors.Is(err, ErrInvalidLogin) {
		t.Errorf("el refresh token viejo sigue vivo: %v", err)
	}
}

func TestElCodigoSoloSirveUnaVez(t *testing.T) {
	pool := testdb.New(t)
	s := NewStore(pool)
	ctx := t.Context()

	_, codigo := conCodigo(t, s, pool, "unavez@undiamas.mx")
	hash, _ := bcrypt.GenerateFromPassword([]byte("otra-contraseña"), bcrypt.MinCost)

	if err := s.UsarReset(ctx, "unavez@undiamas.mx", codigo, string(hash)); err != nil {
		t.Fatalf("primer UsarReset: %v", err)
	}
	err := s.UsarReset(ctx, "unavez@undiamas.mx", codigo, string(hash))
	if !errors.Is(err, ErrResetInvalido) {
		t.Errorf("el código se pudo reusar: %v", err)
	}
}

// El cerco contra la fuerza bruta: cinco fallos y el código muere, aunque
// después se acierte.
func TestCincoFallosMatanElCodigo(t *testing.T) {
	pool := testdb.New(t)
	s := NewStore(pool)
	ctx := t.Context()

	_, codigo := conCodigo(t, s, pool, "fuerzabruta@undiamas.mx")
	hash, _ := bcrypt.GenerateFromPassword([]byte("da-igual"), bcrypt.MinCost)

	for i := range resetMaxIntentos {
		err := s.UsarReset(ctx, "fuerzabruta@undiamas.mx", "000000", string(hash))
		if !errors.Is(err, ErrResetInvalido) {
			t.Fatalf("intento %d devolvió %v", i+1, err)
		}
	}
	if err := s.UsarReset(ctx, "fuerzabruta@undiamas.mx", codigo, string(hash)); !errors.Is(err, ErrResetInvalido) {
		t.Errorf("el código bueno siguió sirviendo tras %d fallos: %v", resetMaxIntentos, err)
	}
}

func TestUnCodigoCaducadoNoSirve(t *testing.T) {
	pool := testdb.New(t)
	s := NewStore(pool)
	ctx := t.Context()

	userID := testdb.NewUser(t, pool, "tarde@undiamas.mx")
	hash, _ := bcrypt.GenerateFromPassword([]byte("111111"), bcrypt.MinCost)
	// Nacido caducado: es lo mismo que dejar pasar 15 minutos, sin esperar.
	if err := s.CrearReset(ctx, userID, string(hash), time.Now().Add(-time.Second)); err != nil {
		t.Fatalf("CrearReset: %v", err)
	}

	nueva, _ := bcrypt.GenerateFromPassword([]byte("no-deberia-entrar"), bcrypt.MinCost)
	if err := s.UsarReset(ctx, "tarde@undiamas.mx", "111111", string(nueva)); !errors.Is(err, ErrResetInvalido) {
		t.Errorf("un código caducado sirvió: %v", err)
	}
}

// Pedir el código dos veces seguidas no manda dos correos: si no, la
// recuperación sería una forma de inundarle el buzón a cualquiera.
func TestNoSeReenviaElCodigoDeInmediato(t *testing.T) {
	pool := testdb.New(t)
	s := NewStore(pool)
	ctx := t.Context()

	userID, _ := conCodigo(t, s, pool, "insiste@undiamas.mx")

	reciente, err := s.ResetReciente(ctx, userID, time.Minute)
	if err != nil {
		t.Fatalf("ResetReciente: %v", err)
	}
	if !reciente {
		t.Error("un código recién creado no se detectó como reciente")
	}
}
