# Backend UnDiaMas (Go)

Backend propio en Go + PostgreSQL. **Cero Firebase**: sustituye a Firestore,
Cloud Functions, las reglas de seguridad, Firebase Auth y también a Firebase
Cloud Messaging. El análisis del sistema anterior y los hallazgos que motivan
cada decisión están en [docs/analisis-backend.md](docs/analisis-backend.md).

Lo único que sigue siendo un servicio externo es Gemini, para el asistente.
Está detrás de la interfaz `ai.Client`, así que cambiarlo por un modelo local
es escribir un cliente nuevo, nada más.

## Arranque

Go 1.26+ y PostgreSQL 16+. En Arch/CachyOS, la primera vez:

```sh
sudo pacman -S postgresql
# initdb tiene que correr como el usuario postgres y con su propio login shell:
# con `sudo -u postgres` falla por permisos del directorio actual.
sudo su -l postgres -c "initdb --locale=C.UTF-8 --encoding=UTF8 -D /var/lib/postgres/data"
sudo systemctl enable --now postgresql
```

Después, ya sin root:

```sh
cp .env.example .env
openssl rand -base64 48        # pégalo en JWT_SECRET
make db-create                 # crea rol y base 'undiamas'
make run                       # aplica migraciones y escucha en :8080
```

Las migraciones van embebidas en el binario (`internal/db/migrations/*.sql`) y se
aplican solas al arrancar, una por transacción, registradas en `schema_migrations`.
Para añadir una nueva, crea `0002_loquesea.sql`: el orden es alfabético.

```sh
make test    # no necesita base de datos
make build   # binario en bin/api
```

## Rutas

| Método | Ruta | Auth | Nota |
|---|---|---|---|
| `GET`  | `/healthz` | — | incluye ping a la base |
| `POST` | `/v1/auth/register` | — | siempre crea rol `patient` |
| `POST` | `/v1/auth/login` | — | |
| `POST` | `/v1/auth/refresh` | — | rota el par de tokens |
| `POST` | `/v1/auth/logout` | Bearer | revoca todos los refresh del usuario |
| `GET`  | `/v1/users/me` | Bearer | perfil + contactos de emergencia |
| `PATCH`| `/v1/users/me` | Bearer | `displayName`, `porQuePersonal` — nunca `role` |
| `PUT`  | `/v1/users/me/emergency-contacts` | Bearer | el primero es el de confianza |
| `GET`  | `/v1/tracker` | Bearer | racha y ahorro calculados en el servidor |
| `PATCH`| `/v1/tracker` | Bearer | `startDate`, `dailySavingsRate`, `currency` |
| `POST` | `/v1/relapses` | Bearer | reinicia la racha conservando el récord |
| `GET`  | `/v1/relapses?limit=20` | Bearer | |
| `POST` | `/v1/check-ins` | Bearer | dispara el protocolo de emergencia si es rojo |
| `GET`  | `/v1/check-ins?limit=20` | Bearer | |
| `GET`  | `/v1/check-ins/{id}` | Bearer | 404 si es de otro usuario |
| `GET`  | `/v1/events` | Bearer | **SSE**, sustituye a FCM |
| `GET`  | `/v1/alerts?limit=20` | Bearer | |
| `PATCH`| `/v1/alerts/{id}` | Bearer | `{"handled": true}` |
| `POST` | `/v1/journal` | Bearer | |
| `GET`  | `/v1/journal?limit=20` | Bearer | |
| `GET`  | `/v1/journal/{id}` | Bearer | |
| `DELETE`| `/v1/journal/{id}` | Bearer | el diario sí se puede retirar |
| `POST` | `/v1/mood-logs` | Bearer | la etiqueta se normaliza a mayúsculas |
| `GET`  | `/v1/mood-logs?limit=30` | Bearer | |
| `POST` | `/v1/traffic-light` | Bearer | registra el semáforo; en rojo levanta alerta |
| `GET`  | `/v1/traffic-light?limit=20` | Bearer | estado actual + historial |
| `GET`  | `/v1/stats/risk-trends?days=30&tz=` | Bearer | tendencias de riesgo (#7) |
| `GET`  | `/v1/reminders` | Bearer | |
| `PUT`  | `/v1/reminders` | Bearer | `enabled`, `hora`, `minuto`, `zona` (#8) |
| `GET`  | `/v1/me/therapists` | Bearer | quién puede ver tus datos |
| `POST` | `/v1/me/therapists` | Bearer | `{email}` — el paciente concede el acceso |
| `DELETE`| `/v1/me/therapists/{id}` | Bearer | y lo retira |
| `GET`  | `/v1/me/sessions` | Bearer | tus sesiones, en solo lectura |
| `POST` | `/v1/ai/chat` | Bearer | |
| `GET`  | `/v1/ai/messages?limit=50` | Bearer | |

Y la vista clínica, que además del token exige rol `therapist` y vínculo con el
paciente. Sin las dos cosas, todo responde 404:

| Método | Ruta | Nota |
|---|---|---|
| `GET`  | `/v1/therapist/patients` | solo los tuyos |
| `GET`  | `/v1/therapist/patients/{id}` | tracker + últimos check-ins + alertas |
| `GET`  | `/v1/therapist/patients/{id}/check-ins` | |
| `GET`  | `/v1/therapist/patients/{id}/traffic-light` | |
| `GET`  | `/v1/therapist/patients/{id}/alerts` | |
| `GET`  | `/v1/therapist/patients/{id}/stats` | |
| `GET`  | `/v1/therapist/patients/{id}/notes` | solo las que escribiste tú |
| `POST` | `/v1/therapist/patients/{id}/notes` | |
| `GET`  | `/v1/therapist/sessions` | |
| `POST` | `/v1/therapist/sessions` | `patientId`, `scheduledAt`, `notes` |
| `PATCH`| `/v1/therapist/sessions/{id}` | `status`: scheduled·completed·cancelled |

El terapeuta **no** ve el diario ni la conversación con el asistente, haya o no
vínculo: ninguna ruta los expone.

Para conectar la app contra estas rutas —dirección del servidor, manejo del
token, errores y los fallos típicos— está [docs/conectar-la-app.md](docs/conectar-la-app.md).

Prueba rápida:

```sh
curl -sX POST localhost:8080/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"a@b.mx","password":"12345678","displayName":"Ana"}'
```

## Qué cambia respecto a Firebase

**Autorización.** No hay `firestore.rules`. Cada query lleva `WHERE user_id = $1`
con el uid del JWT verificado; el cuerpo de la petición nunca aporta identidad.
El rol viaja como claim del token, así que autorizar no cuesta una lectura extra
(hallazgo #16) y desaparece la dependencia circular con `/users`.

**El rol no se acepta del cliente en ningún endpoint.** `register` inserta
`patient` por construcción — es el equivalente de `noTocaRol()`.

**Paginación real.** `ORDER BY … LIMIT` en la query, no `.sortedByDescending{}.take()`
en memoria (#14), con los siete índices compuestos ya declarados en la migración (#15).

**Un solo vocabulario para el semáforo.** [internal/risk](internal/risk/risk.go) es la
única fuente de verdad: `green|yellow|red` en la base, `VERDE|AMARILLO|ROJO` hacia la app.
Se acabaron las dos tablas de traducción duplicadas.

**El chat sí tiene memoria y contexto** (#2). `/v1/ai/chat` recibe solo `{prompt}`:
el historial y el nivel de riesgo los lee el servidor de la base con el uid del token,
así que el cliente no puede mentir sobre su semáforo ni perder la conversación al
reinstalar. El *system prompt* se inyecta como `systemInstruction` y cambia según el nivel.

**La secuencia de function-calling está bien formada** (#3). `RunTurn` conserva el
turno `model` con su `functionCall` antes del turno `function` con la `functionResponse`;
[internal/ai/agent_test.go](internal/ai/agent_test.go) lo verifica sobre el cuerpo real
de la petición, que es justo lo que el mock del backend anterior ocultaba.

**`agentChat` ya no es gratis e ilimitado** (#10): tope de 4000 runas por mensaje y
20 mensajes por minuto y usuario.

**Las alertas se pueden cerrar** (#12): `PATCH /v1/alerts/{id}`. Con las reglas de
Firestore el campo `handled` estaba congelado en `false` para siempre.

**Las estadísticas ya no son código muerto** (#7). `aggregateRiskTrends` estaba
implementada y testeada en el backend anterior pero no la exportaba ninguna
función. Ahora es `GET /v1/stats/risk-trends`, con la agregación en SQL —no
trayéndose los check-ins al proceso— y el corte del día en la zona del usuario,
no en UTC. Lo único que se calcula en Go es la tendencia, porque es una decisión
de producto: [internal/stats](internal/stats/stats.go) la deja pura y con test
propio sin base de datos.

**Hay recordatorios de check-in** (#8). No hacen falta Cloud Scheduler ni un cron
del sistema: el proceso lleva un ticker de un minuto y pregunta a quién le toca,
según su hora local. `last_sent_on` se escribe en el mismo `UPDATE … RETURNING`
que selecciona a los pendientes, así que ni el ticker ni una segunda réplica
pueden avisar dos veces el mismo día. Quien ya hizo su check-in de hoy no recibe
nada.

**El semáforo tiene un solo escritor.** [internal/trafficlight](internal/trafficlight/trafficlight.go)
escribe el registro, actualiza el tracker y —si el nivel es rojo— levanta la
alerta, todo en la misma transacción. Antes `alerts.Raise` escribía también en
`traffic_light_logs` y cualquier otro camino habría duplicado filas o dejado un
semáforo en rojo sin alerta detrás.

**La alerta que levanta el asistente también suena.** La herramienta
`guardar_alerta` hacía un `INSERT` suelto contra la tabla y se quedaba muda;
ahora pasa por `alerts.Service`, así que sale por SSE con el contacto de
confianza como cualquier otra.

## Avisos sin FCM

El protocolo de emergencia (hallazgo #1) vive en
[internal/alerts](internal/alerts/alerts.go) y entrega por SSE, no por push:

1. `POST /v1/check-ins` con `riskLevel: "ROJO"` llama a `Service.Raise`.
2. `Raise` escribe la alerta y el registro del semáforo **en una transacción**, y
   solo entonces emite al hub.
3. La app, suscrita a `GET /v1/events`, recibe el evento con el contacto de
   confianza en el mismo payload.

Que se persista **antes** de emitir es lo que sustituye a la garantía de entrega
de FCM: si la app está cerrada no hay push, pero la alerta existe y la recoge de
`GET /v1/alerts` al volver. La diferencia real con FCM es que no hay aviso con la
app cerrada; si eso resulta imprescindible, la vía sin Google es UnifiedPush o un
ntfy autoalojado, ambos hablando con este mismo `Service.Raise`.

Detalles del canal: heartbeat cada 20 s (los proxies cortan un SSE ocioso),
`SetWriteDeadline` anulado por conexión (el `WriteTimeout` global lo mataría al
minuto), varias conexiones por usuario, y `Publish` que nunca bloquea aunque un
cliente deje de consumir.

## Tests

```sh
make test              # rápido, no necesita base de datos
make test-integration  # los mismos, con Postgres y -race
```

Los tests de integración corren contra la base real, no contra mocks: cada uno
crea su propio esquema, aplica las migraciones dentro y lo borra al terminar
([internal/testdb](internal/testdb/testdb.go)). Un esquema por test y no una base
por test porque `CREATE DATABASE` serializa contra el cluster entero y tarda
cientos de milisegundos. Sin `TEST_DATABASE_URL` se saltan solos, así que
`make test` sigue funcionando en una máquina sin Postgres.

Lo que cubren es lo que el mock del backend anterior escondía: que el aislamiento
entre usuarios lo hace de verdad el `WHERE user_id = $1`, que un rojo deja las
tres escrituras y los dos eventos, que un refresh token se gasta una sola vez, y
que un terapeuta sin vínculo recibe el mismo 404 que uno que pide un paciente
inexistente.

## Cuentas de terapeuta

`register` siempre crea `patient` y no hay endpoint que conceda el otro rol, ni
siquiera autenticado. Un terapeuta se marca a mano:

```sh
make db-therapist EMAIL=alguien@dominio.mx
```

A partir de ahí es el **paciente** quien concede el acceso con
`POST /v1/me/therapists`, y quien lo retira. El acceso a datos de recuperación se
concede, no se toma.

## Lo que falta

- Google Sign-In (#9) y adjuntos del diario (#11): los dos son integración con
  servicios externos, no lógica de este backend.
- Cápsulas del tiempo, hábitos y anclas (#13): siguen viviendo en memoria en la
  app y no tienen tabla aquí.
- El límite de peticiones vive en memoria: con más de una réplica hay que moverlo
  a Redis o a la base. Los recordatorios, en cambio, ya toleran varias réplicas.
- Un aviso con la app cerrada seguiría necesitando UnifiedPush o un ntfy
  autoalojado.

## Estructura

```
cmd/api/            arranque, rutas, apagado ordenado
internal/config/    variables de entorno, validadas al arrancar
internal/db/        pool + migraciones embebidas
internal/httpx/     JSON, errores, paginación, logging
internal/auth/      JWT, refresh tokens, middleware, register/login
internal/risk/      el semáforo, en un solo lugar
internal/checkins/  porción vertical de referencia (model + store + handler)
internal/users/     perfil y contactos de emergencia
internal/tracker/   racha de sobriedad y recaídas
internal/notify/    hub SSE — el reemplazo de FCM
internal/alerts/    protocolo de emergencia y bandeja de alertas
internal/trafficlight/ el semáforo: registro, tracker y alerta en una transacción
internal/journal/   diario personal, lo único que el usuario puede borrar
internal/mood/      registro de ánimo
internal/stats/     tendencias de riesgo — agregación en SQL, tendencia en Go
internal/reminders/ recordatorios de check-in y su planificador
internal/therapist/ vista clínica: pacientes, notas y sesiones
internal/ai/        cliente Gemini, orquestación de turnos, tools
internal/testdb/    esquema efímero por test
```

Sin framework HTTP: el router de `net/http` (Go 1.22+) ya resuelve
`GET /v1/check-ins/{id}`. Dependencias directas: `pgx`, `golang-jwt`, `x/crypto`.
