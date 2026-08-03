// Package testdb da a cada test su propia base, aislada de las demás y de la de
// desarrollo.
//
// El aislamiento se hace con un esquema por test y no con una base por test:
// CREATE DATABASE serializa contra el resto del cluster y tarda cientos de
// milisegundos; un esquema son unos pocos. Cada pool se configura con su
// search_path, así que las migraciones —que no cualifican ningún nombre— caen
// dentro del esquema del test y desaparecen con él.
//
// Sin TEST_DATABASE_URL los tests se saltan en lugar de fallar: `make test`
// tiene que seguir funcionando en una máquina sin Postgres.
package testdb

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/oderflaY/Bakend-un-dia-mas/internal/db"
)

// New devuelve un pool ya migrado sobre un esquema recién creado. El esquema se
// borra al terminar el test, pase lo que pase.
func New(t *testing.T) *pgxpool.Pool {
	t.Helper()

	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("TEST_DATABASE_URL vacía: se omiten los tests de integración")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	schema := "test_" + randomSuffix(t)

	// Un pool aparte, sin search_path, solo para crear y borrar el esquema: el
	// del test no puede crear su propio contenedor.
	admin, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatalf("no hay conexión con TEST_DATABASE_URL: %v", err)
	}
	defer admin.Close()

	if _, err := admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
		t.Fatalf("no se pudo crear el esquema %s: %v", schema, err)
	}

	cfg, err := pgxpool.ParseConfig(url)
	if err != nil {
		t.Fatalf("TEST_DATABASE_URL inválida: %v", err)
	}
	// public queda en el path por las extensiones; el esquema del test va
	// primero, así que todo lo que cree la migración aterriza ahí.
	cfg.ConnConfig.RuntimeParams["search_path"] = schema + ", public"
	cfg.MaxConns = 4

	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		t.Fatalf("no se pudo abrir el pool del test: %v", err)
	}

	t.Cleanup(func() {
		pool.Close()

		// Contexto propio: el del test ya puede estar cancelado y el esquema se
		// tiene que ir igualmente.
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()

		clean, err := pgxpool.New(ctx, url)
		if err != nil {
			t.Logf("no se pudo limpiar el esquema %s: %v", schema, err)
			return
		}
		defer clean.Close()
		if _, err := clean.Exec(ctx, "DROP SCHEMA IF EXISTS "+schema+" CASCADE"); err != nil {
			t.Logf("no se pudo borrar el esquema %s: %v", schema, err)
		}
	})

	if err := db.Migrate(ctx, pool); err != nil {
		t.Fatalf("fallaron las migraciones: %v", err)
	}
	return pool
}

// NewUser crea un usuario con su tracker, que es la precondición de casi todo
// lo demás. Devuelve el id.
func NewUser(t *testing.T, pool *pgxpool.Pool, email string) string {
	t.Helper()

	ctx := t.Context()
	var id string
	err := pool.QueryRow(ctx, `
		INSERT INTO users (email, password_hash, display_name)
		VALUES (lower($1), 'x', $1) RETURNING id`, email).Scan(&id)
	if err != nil {
		t.Fatalf("no se pudo crear el usuario %s: %v", email, err)
	}
	if _, err := pool.Exec(ctx,
		`INSERT INTO sobriety_trackers (user_id, start_date) VALUES ($1, now())`, id); err != nil {
		t.Fatalf("no se pudo crear el tracker de %s: %v", email, err)
	}
	return id
}

// NewTherapist es igual que NewUser pero con el rol que abre la vista clínica.
func NewTherapist(t *testing.T, pool *pgxpool.Pool, email string) string {
	t.Helper()

	id := NewUser(t, pool, email)
	if _, err := pool.Exec(t.Context(),
		`UPDATE users SET role = 'therapist' WHERE id = $1`, id); err != nil {
		t.Fatalf("no se pudo marcar como terapeuta a %s: %v", email, err)
	}
	return id
}

func randomSuffix(t *testing.T) string {
	t.Helper()
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		t.Fatalf("sin aleatoriedad: %v", err)
	}
	return hex.EncodeToString(b)
}
