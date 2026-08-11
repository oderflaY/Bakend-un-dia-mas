.PHONY: run build test test-integration tidy seed db-create db-drop db-shell db-therapist

run:
	set -a; . ./.env; set +a; go run ./cmd/api

build:
	go build -o bin/api ./cmd/api

# Sin TEST_DATABASE_URL los tests de integración se saltan solos, así que esto
# corre en una máquina sin Postgres.
test:
	go test ./...

# Los mismos tests, pero con base: cada uno crea su propio esquema y lo borra al
# terminar, así que no ensucia la base de desarrollo.
test-integration:
	set -a; . ./.env; set +a; TEST_DATABASE_URL="$$DATABASE_URL" go test -race ./...

tidy:
	go mod tidy

# Dos cuentas de demostración con racha, historias en el muro y check-ins.
# Idempotente: vuelve a dejarlas en el mismo estado. Se niega a correr contra
# una base que no sea local salvo --force, porque las contraseñas son públicas.
seed:
	set -a; . ./.env; set +a; go run ./cmd/seed

# Postgres local, sin Docker. Recién inicializado el cluster, el único rol que
# existe es 'postgres', así que hay que crear el nuestro desde ahí.
# Idempotente y sin bloques DO: un $$ en un Makefile acaba expandido por el
# shell al PID del proceso, no como el delimitador de PL/pgSQL.
db-create:
	sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='undiamas'" | grep -q 1 || \
		sudo -u postgres psql -v ON_ERROR_STOP=1 -c "CREATE ROLE undiamas LOGIN PASSWORD 'undiamas';"
	sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='undiamas'" | grep -q 1 || \
		sudo -u postgres createdb -O undiamas undiamas

db-drop:
	sudo -u postgres dropdb --if-exists undiamas

# Consola psql sobre la base del proyecto.
db-shell:
	psql "$${DATABASE_URL:-postgres://undiamas:undiamas@localhost:5432/undiamas?sslmode=disable}"

# El registro siempre crea pacientes (es el equivalente de noTocaRol()), así que
# un terapeuta se marca a mano. Es deliberado: no hay endpoint que conceda ese
# rol, ni siquiera autenticado.
db-therapist:
	@test -n "$(EMAIL)" || (echo "uso: make db-therapist EMAIL=alguien@dominio.mx"; exit 1)
	psql "$${DATABASE_URL:-postgres://undiamas:undiamas@localhost:5432/undiamas?sslmode=disable}" \
		-v ON_ERROR_STOP=1 -c "UPDATE users SET role = 'therapist' WHERE email = lower('$(EMAIL)');"
