# UnDiaMas · Especificación del backend para un modelo

**Audiencia: un modelo de lenguaje que va a leer, modificar o extender este
repositorio.** No es un tutorial ni un README; es el mapa completo del sistema,
escrito para que puedas razonar sobre él sin leer los 7.000 renglones de Go y sin
inventarte nada. Todo lo que dice aquí está verificado contra el código en el
momento de escribirlo. Cuando algo **no** existe, se dice explícitamente en
[§14](#14-lo-que-no-existe-no-lo-alucines).

Si vas a tocar código, lee primero [§13 Invariantes](#13-invariantes-no-los-rompas):
son las reglas que el sistema entero da por ciertas.

---

## 1. Qué es esto en diez renglones

App de apoyo para personas en recuperación de una adicción. El backend es un
binario de Go + PostgreSQL. Sustituye a un backend anterior de Firebase
(Firestore, Cloud Functions, reglas de seguridad, Auth y FCM); el análisis de
aquel sistema y los hallazgos numerados que se citan en los comentarios
(`#1`…`#16`) están en [analisis-backend.md](analisis-backend.md).

El dominio gira alrededor de un **semáforo de riesgo** (verde / amarillo / rojo)
por usuario. Todo lo demás —check-ins, diario, ánimo, racha de sobriedad,
asistente conversacional, vista del terapeuta— alimenta o consume ese semáforo.
Cuando el semáforo se pone en rojo se dispara un **protocolo de emergencia**:
se persiste una alerta y se emite por SSE junto con el contacto de confianza.

Lo único externo es **Gemini** (chat y embeddings), detrás de interfaces. Si
Gemini no está, el sistema entero sigue funcionando con menos capacidades.

---

## 2. Stack e invariantes de arquitectura

| Cosa | Elección | Por qué importa |
|---|---|---|
| Lenguaje | Go 1.26 | `net/http` con enrutado por patrón (`"POST /v1/x/{id}"`), sin framework |
| Base | PostgreSQL 16+, `pgx/v5` con pool | Enums nativos, `TEXT[]`, `JSONB`, agregación en SQL |
| Migraciones | `.sql` embebidos, aplicadas al arrancar | `internal/db/migrations/`, orden alfabético, una transacción por archivo, registradas en `schema_migrations` |
| Auth | JWT HS256 (access) + refresh opaco hasheado | `golang-jwt/v5`, `bcrypt` para contraseñas |
| Tiempo real | SSE (`text/event-stream`) | No hay push con la app cerrada; se compensa persistiendo |
| IA | Gemini REST (`generateContent`, `embedContent`) | Detrás de `ai.Client` y `rag.Embedder` |
| Dependencias | 3 directas | `pgx`, `golang-jwt`, `x/crypto`. No hay framework web, ORM, ni cliente oficial de Gemini |

Estructura: `cmd/api/main.go` es el único punto de cableado. Cada paquete de
`internal/` expone `Store`/`Service` (dominio + SQL) y `Handler` con un método
`Routes(mux)`. No hay inyección de dependencias mágica: todo se pasa a mano en
`run()`.

---

## 3. Mapa de paquetes

| Paquete | Responsabilidad | Depende de |
|---|---|---|
| `internal/risk` | El tipo `Level` (Green/Yellow/Red) y sus traducciones. **Única fuente de verdad del semáforo.** | — |
| `internal/addiction` | Catálogo de tipos de adicción: `Type`, `Parse`, `ParseLista`. **Única fuente de verdad del catálogo.** | — |
| `internal/httpx` | Contrato HTTP: `JSON`, `Error`, `Decode`, `IsNotFound`, `Limit`, middleware `Logging` | pgx |
| `internal/config` | Variables de entorno; falla al arrancar, no en la primera petición | rag (por la constante del modelo) |
| `internal/db` | Pool y migraciones embebidas | pgx |
| `internal/auth` | Registro (con perfil de recuperación), login, refresh, logout, `Middleware`, `RequireRole`, `Identity` | addiction, httpx |
| `internal/users` | Perfil (`/v1/users/me`), perfil de recuperación y contactos de emergencia | addiction, auth, httpx |
| `internal/checkins` | Check-ins. Porción vertical de referencia: el resto se calca de aquí | auth, httpx, risk |
| `internal/tracker` | Racha de sobriedad, ahorro, recaídas | auth, httpx, risk |
| `internal/journal` | Diario privado (único recurso con DELETE) | auth, httpx |
| `internal/mood` | Etiquetas de ánimo | auth, httpx |
| `internal/lexicon` | **Clasificador determinista de texto → nivel de riesgo.** Sin red, sin modelo | risk |
| `internal/analysis` | Puente léxico → semáforo → respuesta HTTP. Enganches `OnJournal` / `OnMood` | lexicon, rag, trafficlight |
| `internal/trafficlight` | Estado e historial del semáforo. Único escritor de `traffic_light_logs` | alerts, notify, risk |
| `internal/alerts` | Protocolo de emergencia: persistir + emitir + contacto de confianza | notify, risk |
| `internal/notify` | Hub SSE en memoria; `GET /v1/events` | auth, httpx |
| `internal/reminders` | Recordatorio diario de check-in; ticker en proceso | notify, auth |
| `internal/stats` | Agregación de tendencias de riesgo, en SQL | risk, auth |
| `internal/therapist` | Vista clínica y frontera de privacidad | checkins, tracker, trafficlight, alerts, stats |
| `internal/community` | Muro de historias entre pares: alias, publicación, votos, reportes, bloqueos y filtro de contenido | auth, httpx |
| `internal/rag` | **Recuperación de material de apoyo** (BM25 + embeddings) | lexicon, risk |
| `internal/ai` | Cliente Gemini, orquestación del turno, herramientas, rate limit | rag, checkins, alerts, auth |
| `internal/testdb` | Un esquema Postgres por test; se salta sin `TEST_DATABASE_URL` | db |

**Grafo de dependencias relevante (no hay ciclos):**
`ai → rag → lexicon → risk`, y `analysis → {lexicon, rag, trafficlight} → {alerts → notify}`.

---

## 4. Modelo de datos

Enums de Postgres: `risk_level('green','yellow','red')`, `user_role('patient','therapist')`,
`ai_role('user','assistant')`.

| Tabla | Claves | Notas que cambian cómo razonas |
|---|---|---|
| `users` | PK uuid, `email` UNIQUE (lower) | `record_racha_secs` guarda el récord histórico; `por_que_personal` es la motivación que escribe la persona; **perfil de recuperación** en `adicciones TEXT[]`, `adiccion_principal`, `consumo_desde`, `en_tratamiento` (§5.1) |
| `emergency_contacts` | UNIQUE(user_id, position) | **`position = 0` es el contacto de confianza**, el que recibe el protocolo de emergencia |
| `therapist_patients` | PK(therapist_id, patient_id) | El vínculo lo crea **el paciente** |
| `refresh_tokens` | PK `token_hash` | Solo se guarda el SHA-256; el token en claro nunca toca la base |
| `sobriety_trackers` | PK user_id | Fila creada al registrarse. `traffic_light` aquí es **el nivel vigente** |
| `check_ins` | idx (user_id, created_at DESC) | `craving_level` 0..10 con CHECK; `triggers TEXT[]`; `answers JSONB` |
| `ai_messages` | idx (user_id, created_at DESC) | Conversación con el asistente; `risk_level_context` es el semáforo en ese momento |
| `alerts` | idx (user_id, created_at DESC) | `handled` sí es modificable (hallazgo #12) |
| `journal_entries` | idx (user_id, created_at DESC) | Borrables |
| `mood_logs` | idx (user_id, created_at DESC) | Etiqueta normalizada a MAYÚSCULAS |
| `traffic_light_logs` | idx (user_id, created_at DESC) | `trigger_level` 1..5, `suggested_actions TEXT[]` |
| `relapse_events` | idx (user_id, created_at DESC) | `previous_streak_secs` calculado en SQL, nunca por el cliente |
| `sessions`, `clinical_notes` | idx por paciente/terapeuta | Solo escribe el terapeuta vinculado |
| `reminder_settings` | PK user_id, idx parcial `WHERE enabled` | Hora **partida** (hora+minuto+zona), no timestamp; `last_sent_on` evita repetir |
| `community_profiles` | PK user_id, UNIQUE `lower(alias)` | el alias es la identidad pública, separada de la cuenta |
| `community_stories` | 5 índices (uno por orden del muro, parciales) | `author_id` **nunca sale del servidor**; `alias_snapshot` y `streak_days` congelados al publicar |
| `community_story_useful` | PK (story_id, user_id) | un "me ayudó" por persona; la PK *es* la regla |
| `community_reports` | UNIQUE (story_id, reporter_id) | un reporte por persona; al tercero distinto → `EN_REVISION` |
| `community_blocks` | PK (user_id, blocked_id) | contra la cuenta, no contra el alias: sobrevive al cambio de nombre |
| `schema_migrations` | PK version | La lleva `db.Migrate` |

Todas las tablas de usuario tienen `user_id UUID REFERENCES users(id) ON DELETE
CASCADE`, y **toda** query lleva `WHERE user_id = $1`. El aislamiento entre
usuarios no depende de un campo del documento como en Firestore: es FK + WHERE.

**El corpus del RAG no está en la base**: vive en `internal/rag/kb.json`,
embebido en el binario. No hay tabla de documentos ni base vectorial.

---

## 5. Autenticación y autorización

```
POST /v1/auth/register  → crea user (role=patient SIEMPRE) + su sobriety_tracker
POST /v1/auth/login     → verifica bcrypt y emite tokens
POST /v1/auth/refresh   → consume el refresh (UPDATE ... RETURNING, atómico) y emite otro par
POST /v1/auth/logout    → revoca todos los refresh del usuario
```

Las tres primeras responden **lo mismo**, por la misma función (`issue`):

```json
{
  "accessToken": "eyJ…",
  "refreshToken": "…",
  "expiresIn": 900,
  "user": {
    "id": "uuid", "email": "…", "displayName": "…", "role": "patient",
    "adicciones": ["alcohol", "tabaco"],
    "adiccionPrincipal": "alcohol",
    "consumoDesde": "2015-03-01",
    "enTratamiento": true,
    "onboardingCompleto": true
  }
}
```

El objeto `user` viaja con los tokens —también en el refresh— para que la
pantalla siguiente al login no tenga que esperar un `GET /v1/users/me` solo para
decidir si enseña el onboarding. `onboardingCompleto` se calcula al vuelo
(`adiccionPrincipal != ""`); no es una columna.

- El **access token** es JWT HS256 con `sub` = userID y un claim `role`. TTL por
  defecto 15 min. Que el rol viaje en el token es lo que hace que autorizar no
  cueste una lectura a la base (hallazgo #16).
- El **refresh token** es aleatorio de 32 bytes; se guarda solo su hash. TTL 30 días.
- `auth.Middleware` exige `Authorization: Bearer <access>`, parsea y mete
  `auth.Identity{UserID, Role}` en el contexto. Dentro de un handler protegido se
  lee con `auth.MustFrom(r.Context())`.
- `auth.RequireRole(RoleTherapist, …)` se encadena después.
- **No hay endpoint que conceda el rol de terapeuta.** Se hace a mano en SQL
  (`make db-therapist EMAIL=…`). Es deliberado.

**Regla absoluta: el `userID` sale del token. Nunca del cuerpo, nunca de la
query string, nunca de un argumento de herramienta del modelo.**

### 5.1 El perfil de recuperación (de qué se está recuperando la persona)

`POST /v1/auth/register` acepta, **todo opcional**, junto al correo y la
contraseña:

| Campo | Tipo | Regla |
|---|---|---|
| `adicciones` | `["alcohol","tabaco"]` | cada valor debe existir en el catálogo; se quitan duplicados |
| `adiccionPrincipal` | `"alcohol"` | debe estar dentro de `adicciones` |
| `consumoDesde` | `"2015-03-01"` | `YYYY-MM-DD`, no puede ser futuro |
| `enTratamiento` | `true` | si ya está en tratamiento profesional |

Los mismos campos se editan luego con `PATCH /v1/users/me` y se leen en
`GET /v1/users/me`. Registrarse sin contestar nada es válido: el onboarding se
puede completar después, y hasta entonces `onboardingCompleto` es `false`.

**Catálogo** (`internal/addiction`, códigos estables sobre los que la app hace
switch):

```
alcohol · tabaco · cannabis · cocaina · metanfetamina
opioides · benzodiacepinas · inhalantes · juego · otra
```

Cuatro decisiones que explican por qué está así:

1. **`adicciones` es una lista, no un valor.** La comorbilidad es la norma:
   alcohol y tabaco juntos es el caso más frecuente. Obligar a elegir uno
   falsearía el dato desde el registro.
2. **`adiccionPrincipal` existe además de la lista** porque la racha, el ahorro
   y el tracker son de **una** cosa. Es la que rige lo que se cuenta. Si solo se
   declara una adicción, se toma como principal sin preguntar.
3. **`addiction.Parse` rechaza lo desconocido**, al revés que `risk.Parse` (que
   cae a verde). Aquí no hay valor por defecto seguro: mandarle a alguien el
   material de otra adicción es peor que pedirle que corrija el dato. El error
   nombra el valor: `"tipo de adicción desconocido: chela"`.
4. **Acepta sinónimos**: `cerveza`→`alcohol`, `nicotina`/`vape`→`tabaco`,
   `marihuana`→`cannabis`, `cristal`→`metanfetamina`, `apuestas`→`juego`. Es
   tolerancia de entrada, no un catálogo paralelo: lo que se guarda y lo que se
   devuelve es siempre el código canónico.

`consumo_desde` es **desde cuándo consume**, y no hay que confundirlo con
`sobriety_trackers.start_date`, que es desde cuándo lleva sobrio. El gasto diario
tampoco se duplica aquí: vive en `sobriety_trackers.daily_savings_rate`.

**Coherencia en el PATCH**: si se quita de la lista la que era principal, la
principal **se recalcula** (pasa a la primera que quede) en vez de rechazar la
petición — quien edita su lista está corrigiendo su perfil, no mandando un dato
inválido. Pero mandar explícitamente una `adiccionPrincipal` que no está en la
lista sí es un 400.

---

## 6. Rutas (inventario completo)

Todas exigen Bearer salvo las tres primeras. Errores en formato
`{"error":"<código-estable>","message":"<texto>"}`.

| Método y ruta | Qué hace |
|---|---|
| `POST /v1/auth/register` | pública; acepta el perfil de recuperación (§5.1) |
| `POST /v1/auth/login` \| `refresh` | públicas; devuelven tokens + `user` |
| `POST /v1/auth/logout` | revoca refresh tokens |
| `GET /healthz` | ping a Postgres; 503 si no hay base |
| `GET /v1/users/me` · `PATCH /v1/users/me` | perfil: datos, perfil de recuperación y contactos |
| `DELETE /v1/users/me` | borra la cuenta; exige `{"password"}`; cascada total (§11.1) |
| `PUT /v1/users/me/emergency-contacts` | reemplaza la lista completa |
| `POST /v1/check-ins` · `GET /v1/check-ins` · `GET /v1/check-ins/{id}` | check-ins; el POST en rojo dispara el protocolo |
| `GET /v1/tracker` · `PATCH /v1/tracker` | racha, ahorro, semáforo vigente |
| `POST /v1/relapses` · `GET /v1/relapses` | recaída: reinicia racha, conserva récord, deja `traffic_light='red'` |
| `POST /v1/journal` | guarda, analiza y **devuelve el veredicto** (§7.0) |
| `GET /v1/journal` · `GET /v1/journal/{id}` · `DELETE /v1/journal/{id}` | resto del diario |
| `POST /v1/mood-logs` | igual que el diario: guarda, analiza y devuelve veredicto |
| `GET /v1/mood-logs` | listado |
| `POST /v1/traffic-light` · `GET /v1/traffic-light` | registrar / listar semáforo |
| `GET /v1/alerts` · `PATCH /v1/alerts/{id}` | alertas; el PATCH marca `handled` |
| `GET /v1/events` | **SSE**, canal de avisos |
| `GET /v1/reminders` · `PUT /v1/reminders` | configuración del recordatorio |
| `GET /v1/stats/risk-trends` | informe agregado (`?days=`, `?tz=`) |
| `POST /v1/analysis/text` | veredicto **en seco**: puntúa y recupera apoyo, no guarda nada |
| `POST /v1/ai/chat` · `GET /v1/ai/messages` | asistente |
| `POST /v1/ai/retrieve` | diagnóstico del RAG; no llama al modelo |
| `GET /v1/community/me` · `PUT /v1/community/me` | alias y elegibilidad del muro (§11.2) |
| `GET /v1/community/stories` | muro; `?sort=racha\|recientes\|utiles`, `?cursor=` |
| `POST /v1/community/stories` · `DELETE /{id}` | publicar (filtro de contenido) y borrar la propia |
| `PUT /v1/community/stories/{id}/useful` | «Me ayudó», idempotente |
| `POST /v1/community/stories/{id}/reports` · `/block-author` | moderación desde la app |
| `GET /v1/me/therapists` · `POST` · `DELETE /{id}` · `GET /v1/me/sessions` | lado del paciente |
| `GET /v1/therapist/patients[...]`, `/sessions[...]` | lado clínico, todas con rol + vínculo |

Convenciones: listados devuelven `{"items":[...]}` y aceptan `?limit=` acotado
por `httpx.Limit(r, def, max)`. `httpx.Decode` limita el cuerpo a 1 MiB y
**rechaza campos desconocidos** (un typo del cliente es un 400 explícito).
Pedir el id de otro usuario da **404, no 403**: confirmar la existencia
filtraría información.

---

## 7. El pipeline de riesgo (el corazón del sistema)

**El recorrido completo de una entrada de diario, de principio a fin:**

```
  app ──POST /v1/journal {"content":"..."}────────────────────────────┐
                                                                      │
   1. INSERT journal_entries      el texto se guarda ANTES que nada    │
                                  más: si el análisis falla, la        │
                                  entrada ya está a salvo              │
                   │                                                   │
                   ▼                                                   │
   2. lexicon.Analyze(texto)      determinista · en proceso · sin red   │
      → score, nivel, categorías, acciones                              │
                   │                                                   │
                   ▼                                                   │
   3. rag.RetrieveLocal(texto)    SOLO BM25 · sin embeddings ·          │
      → hasta 3 pasajes de apoyo  sin red · el diario NO sale de aquí   │
                   │                                                   │
                   ▼                                                   │
   4. lights.Current(userID)      ¿en qué semáforo está la persona?     │
                   │                                                   │
                   ▼                                                   │
   5. ¿nivel > vigente? ──no──▶ no se registra nada                     │
             │ sí                                                       │
             ▼                                                          │
      trafficlight.Record  (UNA transacción)                            │
        a. INSERT traffic_light_logs  (reason = categorías, NO texto)    │
        b. UPDATE sobriety_trackers.traffic_light                        │
        c. si ROJO → alerts.RaiseTx                                      │
        COMMIT                                                           │
        d. después del commit: hub.Publish → SSE                         │
                   │                                                    │
                   ▼                                                    │
   6. 201 {id, content, createdAt, analisis:{…}} ───────────────────────┘
```

El paso 6 es la respuesta de la **misma** petición: la app no tiene que escribir,
esperar el evento SSE y releer el semáforo para saber qué pasó con lo que su
usuario acaba de escribir. El SSE sigue existiendo para cuando el semáforo cambia
por otra vía (check-in, asistente, otro dispositivo).

Un check-in en rojo entra directo al paso 5, por `onRedCheckIn` en `main.go`.

### 7.0 La forma de `analisis` (lo que recibe la app)

```json
{
  "nivel": "AMARILLO",
  "semaforo": "AMARILLO",
  "subioElSemaforo": true,
  "score": 7.5,
  "categorias": ["antojo", "intencion"],
  "resumen": "antojo, intencion",
  "acciones": ["Espera 15 minutos antes de decidir: el antojo baja solo"],
  "apoyo": [
    {
      "id": "antojo-ola",
      "titulo": "El antojo sube y baja solo",
      "texto": "Un antojo no crece para siempre…",
      "fuente": "Marlatt y Gordon, prevención de recaídas (urge surfing)"
    }
  ]
}
```

**`nivel` y `semaforo` son cosas distintas y las dos hacen falta.** `nivel` es lo
que dice el texto recién escrito; `semaforo` es el estado en que quedó la
persona. Difieren siempre que el análisis no suba: alguien en rojo que escribe
algo tranquilo obtiene `nivel: VERDE`, `semaforo: ROJO`, `subioElSemaforo: false`.
Devolver uno solo haría que la app mintiera en alguno de los dos sentidos.

`semaforo` es `null` cuando no se pudo leer el estado vigente. El veredicto se
devuelve igual: el texto sí se analizó, y un fallo de lectura no justifica dejar
a la app sin respuesta.

Quiénes lo devuelven, con el mismo formato:

| Ruta | Diferencia |
|---|---|
| `POST /v1/journal` | veredicto completo, con efectos |
| `POST /v1/mood-logs` | igual; para el léxico la etiqueta de ánimo es texto como cualquier otro |
| `POST /v1/analysis/text` | prueba en seco: `semaforo` siempre `null`, `subioElSemaforo` siempre `false`, no escribe nada |

El campo va **aplanado junto al recurso** (`id`, `content`, `createdAt`,
`analisis`), no envuelto: una app que todavía no lo lee no se entera del cambio.

### 7.1 `internal/lexicon` — el clasificador

Un diccionario con pesos, embebido en el binario (`lexicon.json`, versión 1).
**No usa el modelo, y es a propósito**: tiene que ser determinista (testeable con
una tabla de casos), funcionar sin red, y no mandar el diario de nadie a un
tercero.

- 14 categorías con peso: `ideacion` 8.0, `recaida` 8.0, `intencion` 5.0,
  `senales` 3.0, `antojo` 2.5, `abstinencia` 2.0, `pensamientos` 2.0,
  `consumo` 1.5, `consecuencias` 1.5, `sustancias` 1.0, `emociones` 1.0,
  `detonantes` 0.75, y dos **protectoras con peso negativo**:
  `recuperacion` −2.0 y `prevencion` −1.5.
- Las cuatro primeras están marcadas `critica: true`.
- Umbrales: amarillo ≥ 3.0, **rojo ≥ 8.0 Y alguna categoría crítica**. La
  conjunción es una decisión humana: un rojo llama al contacto de confianza de
  alguien; acumular tristeza y menciones de alcohol llega a amarillo, no a rojo.
- Mitigaciones al problema de "una lista plana no entiende contexto":
  1. **La frase larga gana** — `"quiero fumar"` (intención, 5.0) desplaza a
     `"fumar"` (consumo, 1.5); no se cuentan las dos.
  2. **Negación** — se buscan negadores en los 3 tokens anteriores al inicio de
     la coincidencia. Por eso `"no quiero fumar"` no cuenta y `"no aguanto más"`
     (que empieza por un negador) sí.
  3. **Tope por término**: máximo 3 repeticiones ponderadas.
  4. El score no baja de 0: un buen día no compra crédito contra el siguiente.
- Normalización: minúsculas, sin acentos, sin puntuación. `lexicon.Tokenizar`
  está exportada **para que el RAG parta el texto exactamente igual**.

`Result` nunca incluye el texto original: lleva score, nivel, matches,
categorías, resumen y acciones sugeridas.

### 7.2 `internal/analysis` — el puente

Cuatro decisiones que no se deben cambiar sin pensarlo:

1. **El análisis solo sube el semáforo, nunca lo baja.** Bajar requiere un
   check-in explícito, que es un acto consciente de la persona.
2. **El texto del diario no sale de este paquete.** Lo que se guarda como
   `reason` es `"diario: antojo, sustancias"` —nombres de categoría—, jamás lo
   que la persona escribió. El terapeuta lee el semáforo, no el diario.
3. **Un fallo del análisis no tumba la escritura.** Se registra en el log y la
   entrada queda guardada igual.
4. **El apoyo se recupera en modo local** (`rag.RetrieveLocal`): BM25 en proceso,
   sin embeddings. El diario no viaja a Gemini ni a ningún tercero. Hay un test
   con un embebedor espía (`TestElDiarioNuncaSeEmbebe`) que truena si alguien
   cambia esa llamada por `Retrieve`.

Tipos: `Outcome` (§7.0) y `Apoyo`. `OnText` devuelve el `Outcome`; `Preview` hace
lo mismo sin efectos.

El enganche se pasa por función, no por import:
`journal.NewHandler(..., analysisSvc.OnJournal)` y
`mood.NewHandler(..., analysisSvc.OnMood)`. La firma es
`func(ctx, userID, texto string) any` — devuelve `any` **a propósito**, para que
`journal` y `mood` no tengan que importar `analysis` ni saber qué es un semáforo:
solo reenvían a la respuesta lo que les den.

### 7.3 `internal/trafficlight` y `internal/alerts`

- `Record` hace las tres escrituras **en una transacción**: log, tracker y (si es
  rojo) alerta. Esa unidad evita el escenario "semáforo rojo en pantalla sin
  alerta detrás, o al revés".
- El contexto se desliga del de la petición (`context.WithoutCancel` + 5 s): un
  rojo no puede quedar a medias porque alguien cerró la app.
- **Se publica después del commit, nunca antes.**
- `alerts.Publish` mete en el mismo payload la alerta, el **contacto de
  confianza** y `deliveredOnline`, para que la app no tenga que pedirlos aparte
  justo en el peor momento.
- `Current` lee el nivel del **tracker**, no del último log: el tracker es lo que
  la persona tiene delante.

---

## 8. El RAG (`internal/rag`)

Resuelve el problema que el léxico no puede resolver. El léxico decide **qué tan
grave** es; el RAG decide **qué decirle a la persona**.

### 8.1 Corpus

`kb.json`, embebido: 28 pasajes curados en español (oleada del antojo, demora de
15 minutos, HALT, respiración cuadrada, anclaje 5-4-3-2-1, lapso vs. recaída,
primeras horas tras recaer, abstinencia, urgencias médicas, líneas de ayuda de
México, ideación, pensamientos permisivos, sueño, red de apoyo, grupos y
tratamiento, etc.).

Cada documento: `id`, `titulo`, `categorias` (**los mismos nombres que el
léxico**), `niveles` (semáforos en los que aplica), `fuente`, `sinonimos`, `texto`.

`sinonimos` es vocabulario de búsqueda: se indexa pero **no se le muestra al
modelo ni a la app**. Existe porque el corpus está redactado en registro clínico
y la gente escribe "quiero fumar", no "urgencia de consumo"; sin él, en modo
léxico no recuperaba nada.

### 8.2 Los dos modos (esto es lo que hay que tener claro)

| | `Retrieve(ctx, …)` | `RetrieveLocal(…)` |
|---|---|---|
| Quién lo usa | el chat (`internal/ai`) | el diario y el ánimo (`internal/analysis`) |
| Embeddings | sí, si el índice está listo | **nunca** |
| Sale a la red | sí (la consulta se embebe) | **no** |
| Por qué | el mensaje del chat ya viaja a Gemini; embeberlo no revela nada nuevo | el diario es el dato más íntimo de la app y no sale del servidor |

Los dos comparten puntuación, filtros y topes: la única diferencia es si se
calcula el componente semántico.

### 8.3 Puntuación

```
score = 0.60·semántico + 0.40·(BM25 normalizado por el máximo) + 0.15·solape_de_categorías
```

- **BM25** (k1=1.2, b=0.75) sobre título + sinónimos + texto, en proceso, siempre
  disponible. Un término repetido en la consulta no cuenta dos veces.
- **Semántico**: coseno entre embeddings normalizados. Por debajo de
  `minSemantico = 0.62` no suma (ni resta). En modo local vale siempre 0.
- **Categorías**: fracción de las categorías del documento que el léxico detectó
  en la consulta. Es la costura entre las dos capas.
- **Puerta de entrada**: un pasaje entra solo si `BM25 > 0` **o** `sem ≥ 0.62`.
- **Filtro por nivel duro**: en verde no sale el protocolo de sobredosis; en rojo
  no sale higiene del sueño.
- **Tope** `topK = 3` y **piso relativo** `minRelativo = 0.45`: se descarta lo que
  puntúe por debajo del 45 % del mejor pasaje, para no rellenar con paja.
- Desempate por `id` → el ranking es reproducible y por tanto ajustable.

### 8.4 Degradación

| Situación | Comportamiento |
|---|---|
| Sin `GEMINI_API_KEY` | `/v1/ai/*` no se monta; **el RAG sí se arma** y el diario sigue recibiendo apoyo |
| Índice calentándose | Recupera solo con BM25 |
| `Warmup` falla | Log de warning, `Listo()` = false, sigue en BM25 para siempre |
| `EmbedQuery` falla en pleno chat | Ese turno recupera solo con BM25; el chat responde igual |
| Corpus mal formado | **El servidor no arranca** (es un error de despliegue) |

`rag.New` se llama siempre en `main.go`, con embebedor o sin él. `Warmup` corre
en una goroutine y solo si hay API key. Los vectores viven en memoria y se
recalculan en cada arranque; no se persisten.

### 8.5 Inyección en el prompt del asistente

`Store.Prompt` devuelve un bloque que se **concatena a la instrucción de
sistema**, nunca al turno del usuario (si fuera al turno del usuario, un prompt
podría contradecirlo). El bloque le dice al modelo: úsalo si encaja, reformúlalo,
no lo cites textualmente, no menciones que existe, y **los datos concretos
—teléfonos, tiempos, nombres de técnicas— tómalos de aquí y no de tu memoria**.
Devuelve `""` cuando no hay nada relevante, y entonces no se inyecta nada.

La recuperación ocurre **una sola vez por turno**, antes del bucle de
herramientas.

Para el diario no hay prompt ni modelo de por medio: los pasajes se devuelven tal
cual en `analisis.apoyo` y los pinta la app.

### 8.6 Embeddings

`text-embedding-004` (configurable con `EMBEDDING_MODEL`), `batchEmbedContents`
para el corpus con `taskType: RETRIEVAL_DOCUMENT` y `embedContent` para la
consulta con `RETRIEVAL_QUERY` — usar el mismo tipo para ambos empeora el
ranking. Caché de consultas en memoria: 512 entradas, TTL 15 min, purga total al
llenarse. Timeout 10 s (más corto que el del chat: si embeber tarda diez
segundos, ya no vale lo que cuesta).

---

## 9. El asistente (`internal/ai`)

### 9.1 Secuencia de un turno

```
1. rate limit (20 msg/min por usuario, ventana fija en memoria)
2. validar prompt (no vacío, ≤ 4000 runas)
3. nivel de riesgo ← checkins.Latest  (NUNCA del cliente)
4. historial ← ai_messages, últimos 20 turnos  (NUNCA del cliente)
5. guardar el mensaje del usuario
6. RunTurn:
     systemPrompt(nivel) + rag.Prompt(prompt, nivel)  → systemInstruction
     bucle máx. 3 rondas de herramientas
7. guardar la respuesta
8. 200 {reply, riskLevel, alertIds}
```

Que el nivel y el historial se lean de la base tiene dos consecuencias: el
cliente **no puede mentir sobre su semáforo** (hallazgo #2), y la conversación
sobrevive a una reinstalación.

### 9.2 La secuencia de `contents` de Gemini

Esto fue el bug #3 del backend anterior y es fácil de volver a romper:

```
user → model(functionCall) → function(functionResponse) → model(texto)
```

El turno del **modelo con su `functionCall` tiene que conservarse** antes del
turno `function` con la respuesta. Mandar el turno `function` suelto hace que
toda respuesta con herramientas falle o alucine.

### 9.3 Herramientas

| Nombre | Args | Efecto |
|---|---|---|
| `leer_historial_reciente` | `limite` (1..10, def. 5) | Devuelve check-ins del usuario **del token** |
| `guardar_alerta` | `nivelRiesgo` (VERDE/AMARILLO/ROJO), `mensaje` | Pasa por `alerts.Raise` → persiste **y** emite por SSE con contacto de confianza |

**Ninguna herramienta recibe `userId` como parámetro.** El servidor lo toma del
token, así que el modelo no puede pedir datos ajenos aunque lo intente.

### 9.4 El prompt de sistema

Acompañante de UnDiaMas, español mexicano, segunda persona, frases cortas, sin
sermones. No es terapeuta ni médico: no diagnostica, no receta, no promete
resultados. Nunca minimiza una recaída ni felicita el consumo. El nivel del
semáforo se inyecta **aquí**, no en el mensaje del usuario, para que no se pueda
sobreescribir desde el prompt. Rojo → contención inmediata y contacto de
confianza; amarillo → nombrar el disparador y una acción para los próximos 10
minutos; verde → reforzar sin exagerar.

---

## 10. Tiempo real, recordatorios y estadísticas

**SSE (`internal/notify`)**: `GET /v1/events` con Bearer. Se anula el
`WriteTimeout` global solo para esa conexión, se manda `event: ready` inmediato y
un comentario `: ping` cada 20 s. El `Hub` es un mapa `userID → conexiones` con
canales de buffer 8; `Publish` **nunca bloquea** (si el canal está lleno descarta,
porque el dato se recupera de la base al reconectar). Tipos de evento emitidos:
`traffic_light`, `alert`, `check_in_reminder`.

Limitación honesta: **sin FCM no hay entrega con la app cerrada.** Se compensa
persistiendo todo aviso importante en `alerts` **antes** de emitirlo.

**Recordatorios (`internal/reminders`)**: ticker de 1 minuto en el proceso, sin
cron externo. La ventana de disparo es de 15 minutos (más ancha que el tick, para
sobrevivir a un reinicio corto). Un solo `UPDATE ... RETURNING` selecciona y marca
como enviados a la vez: dos réplicas no pueden mandar el mismo aviso dos veces.
No avisa a quien ya hizo su check-in de hoy. La hora se guarda partida
(hora+minuto+zona) porque lo que la persona elige es "las 9 de la noche", que
debe seguir siendo las 9 tras un cambio de horario.

**Estadísticas (`internal/stats`)**: cinco consultas agregadas en SQL (nunca
trayendo check-ins al proceso), acotadas por usuario y ventana. Corte del día en
`America/Mexico_City` por defecto. La **tendencia** (`mejorando` / `estable` /
`empeorando` / `sin-datos`) se calcula en Go porque es una decisión de producto y
conviene tenerla pura y testeable sin base.

---

## 11. La frontera clínica (`internal/therapist`)

Toda lectura clínica pasa por **dos** filtros: `RequireRole(therapist)` sobre el
token, y `EnsureLink` sobre `therapist_patients`. El vínculo lo crea el paciente
(`POST /v1/me/therapists`), nunca el terapeuta: el acceso a datos de recuperación
se concede, no se toma. Un paciente ajeno y un paciente inexistente se responden
igual, con 404.

**Lo que el terapeuta NO ve, aunque haya vínculo: el diario y la conversación con
el asistente.** Ninguna ruta de ese paquete los expone. Si añades una que lo
haga, estás rompiendo el diseño.

### 11.1 Borrado de cuenta (`DELETE /v1/users/me`)

Exige `{"password": "…"}` porque el token puede estar en un teléfono que la
persona dejó abierto, y esto no se deshace. Es un `DELETE` de una fila en
`users`: **toda** la cascada vive en el esquema, no en código, así que añadir una
tabla nueva no deja huérfanos por olvido.

Sin periodo de gracia ni papelera: quien borra su cuenta en una app de adicciones
suele necesitar que desaparezca ahora, no en treinta días. El access token sigue
siendo criptográficamente válido hasta que expira —es stateless— pero ya no hay
perfil detrás y todo responde 404.

### 11.2 El muro de comunidad (`internal/community`)

Historias de recuperación entre pares. Especificación completa en
[backend-comunidad.md](backend-comunidad.md); lo que no puedes ignorar:

1. **El `user_id` del autor nunca sale del servidor**, solo `alias_snapshot`.
   Poder cruzar un alias con una cuenta es lo peor que puede filtrar esta app. Si
   añades un campo a `Story`, comprueba que no sea eso.
2. **Cursor, no offset.** El muro ordena por racha y esa cifra crece cada día:
   con `OFFSET` salen historias repetidas al avanzar. El cursor es base64 opaco
   y pertenece al orden con el que se pidió.
3. **`alias_snapshot` y `streak_days` se congelan al publicar.** No los "arregles"
   con un JOIN al perfil actual: cambiar el alias solo desliga hacia adelante, y
   una historia que decía 214 días sigue diciéndolo aunque quien la escribió haya
   recaído después.
4. **Umbral de 30 días** para publicar (`MinDiasRacha`).
5. **3 reportes de personas distintas** → `EN_REVISION` y fuera del muro. La
   transición va en la misma sentencia que incrementa el contador.
6. **El filtro rechaza planes de consumo** (dosis, precios, dónde conseguir) y
   **avisa sin bloquear** de datos personales (correos, teléfonos, enlaces).
   `moderacion_test.go` fija diez frases sanas de recuperación que **no** pueden
   rechazarse; esa lista es tan importante como la de las que sí.

Bloquear se guarda contra el `user_id` y no contra el alias, para que no se
esquive cambiando de nombre.

---

## 12. Configuración, ejecución y pruebas

| Variable | Default | Obligatoria |
|---|---|---|
| `ADDR` | `:8080` | no |
| `DATABASE_URL` | — | **sí** |
| `JWT_SECRET` | — | **sí**, ≥ 32 bytes |
| `ACCESS_TOKEN_TTL` | `15m` | no |
| `REFRESH_TOKEN_TTL` | `720h` | no |
| `GEMINI_API_KEY` | vacío | no (sin ella `/v1/ai/*` no se monta; el RAG local sigue) |
| `GEMINI_MODEL` | `gemini-2.5-flash` | no |
| `EMBEDDING_MODEL` | `text-embedding-004` | no |
| `TEST_DATABASE_URL` | vacío | solo para tests de integración |

```sh
make run               # carga .env, migra y escucha
make test              # NO necesita Postgres: los tests de integración se saltan solos
make test-integration  # con base, -race, un esquema por test
make db-therapist EMAIL=alguien@dominio.mx
```

Convenciones de test: nombres en español descriptivos
(`TestRunTurnConservaElTurnoDelModeloAntesDeLaRespuestaDeLaTool`), los dobles
guardan lo que reciben para poder afirmar sobre la **forma real** de la petición
—el bug #3 se coló porque el mock escondía el cuerpo—, y `internal/testdb` da a
cada test su propio esquema Postgres.

---

## 13. Invariantes (no los rompas)

1. `userID` **siempre** del token. Nunca del cuerpo ni de argumentos del modelo.
2. Toda query lleva `WHERE user_id = $1`.
3. El análisis de texto **solo sube** el semáforo.
4. El texto del diario **nunca** sale de `internal/analysis`; lo que se persiste
   como motivo son nombres de categoría.
5. **El diario y el ánimo nunca se embeben.** Su material de apoyo se recupera
   con `rag.RetrieveLocal`, que no toca la red. Solo el mensaje del chat viaja a
   Gemini, porque ya iba de todos modos.
6. El diario y el chat **nunca** se exponen al terapeuta, y el **`user_id` del
   autor de una historia nunca sale del servidor**: solo el alias congelado
   (§11.2). Las dos son la misma regla aplicada a dos sitios distintos.
7. Semáforo, tracker y alerta se escriben **en la misma transacción**; se publica
   **después** del commit.
8. El nivel de riesgo del chat se lee de la base, no del cliente.
9. Un fallo de Gemini o del RAG **no puede** romper una escritura ni el chat.
10. `risk.Parse` cae a **verde** ante cualquier valor desconocido: un dato
    corrupto no puede inventar un rojo y despertar a la familia de alguien.
11. El clasificador de riesgo es determinista. Si algún día se le suma un modelo,
    se **suma** al `Result`, no lo sustituye.
12. El corpus del RAG es cerrado y revisable en el repositorio. Los datos
    concretos (teléfonos de ayuda) salen de ahí, no de la memoria del modelo.
13. Ante la duda entre 403 y 404 en un recurso ajeno: **404**.
14. Todo lo que cuelgue de un usuario lleva `ON DELETE CASCADE`: el borrado de
    cuenta es una sola sentencia y tiene que seguir siéndolo.

---

## 14. Lo que NO existe (no lo alucines)

- No hay Firebase de ningún tipo. Ni FCM, ni Firestore, ni reglas.
- No hay push con la app cerrada. Solo SSE mientras hay conexión.
- No hay verificación de correo (la columna `email_verified` existe y **nadie la
  usa**), ni recuperación de contraseña, ni OAuth.
- No hay endpoint para conceder el rol de terapeuta.
- No hay CORS configurado. La app es nativa (KMP), no navegador.
- No hay Docker, ni CI, ni cron externo, ni Redis.
- El rate limit del chat es **en memoria**: con varias réplicas hay que moverlo.
- Los embeddings **no se persisten**: se recalculan en cada arranque.
- El RAG **no indexa** contenido de usuarios; no hay base vectorial ni tabla de
  documentos. El corpus es un JSON embebido en el binario.
- El diario **no** se manda a Gemini en ningún flujo.
- **El tipo de adicción todavía no se usa para nada más que guardarlo y
  devolverlo.** No filtra el corpus del RAG, no cambia los pesos del léxico y no
  entra en el prompt del asistente. Son tres integraciones obvias y ninguna está
  hecha: si te preguntan "¿el RAG ya recomienda según la adicción?", la respuesta
  hoy es no.
- **No hay endpoints de moderación del muro.** La cola `EN_REVISION` existe y se
  llena sola con 3 reportes, pero hoy solo se mira y se vacía con SQL: no hay
  `GET /v1/admin/stories`, ni `PATCH` para retirar/restituir, ni rol `moderator`.
  Es lo que falta para cumplir del todo la política de Play sobre contenido de
  usuarios.
- No hay edición de historias, ni comentarios, ni mensajes directos entre
  personas del muro. Lo último es deliberado.
- No se avisa al autor cuando su historia entra en revisión; la ve en su muro.
- La columna `users.status` existe y no se usa.
- No hay paginación por cursor; solo `?limit=` acotado.

---

## 15. Glosario (el código mezcla español e inglés a propósito)

| En código | Significa |
|---|---|
| `risk.Level` / `Code()` / `String()` | interno · `green` `yellow` `red` (Postgres) · `VERDE` `AMARILLO` `ROJO` (app) |
| `semáforo` / `traffic light` | el nivel de riesgo vigente del usuario |
| `nivel` (en `analisis`) | lo que dice **ese texto**, distinto del semáforo vigente |
| `racha` / `streak` | segundos desde `start_date` sin consumir |
| `récord` | mejor racha histórica; **sobrevive a la recaída** |
| `antojo` / `craving` | impulso de consumir |
| `recaída` / `relapse` | volver a consumir; reinicia la racha |
| `detonante` / `trigger` | lo que precede al antojo |
| `adicciones` / `adiccionPrincipal` | de qué se recupera la persona; la principal rige racha y ahorro |
| `consumoDesde` | desde cuándo consume — **no** es `startDate`, que es desde cuándo lleva sobrio |
| `onboardingCompleto` | derivado: hay adicción principal declarada |
| `apoyo` | pasajes del corpus del RAG devueltos a la app |
| `contacto de confianza` | `emergency_contacts` con `position = 0` |
| `protocolo de emergencia` | persistir alerta + emitir por SSE + adjuntar contacto |
| `check-in` | registro periódico autoreportado |
| `léxico` | el clasificador determinista de `internal/lexicon` |
| `alias` / `alias_snapshot` | identidad pública en el muro; la segunda es la congelada al publicar |
| `muro` | el listado de historias de comunidad |
| `EN_REVISION` | historia retirada del muro por 3 reportes, a la espera de revisión |
| `hallazgo #N` | defecto del backend anterior; ver [analisis-backend.md](analisis-backend.md) |

---

## 16. Si vas a extender esto

- **Recurso nuevo**: calca `internal/checkins` (modelo → `Store` → `Handler` con
  `Routes`), añade la migración `000N_*.sql`, cablea en `main.go`.
- **Que un texto nuevo afecte al semáforo**: pásale el enganche
  `analysisSvc.OnText(ctx, userID, fuente, texto)` y adjunta su `Outcome` a la
  respuesta, como hacen `journal` y `mood`. No importes `analysis` desde el
  paquete del recurso.
- **Ampliar el corpus del RAG**: agrega el documento a `kb.json` con sus
  `categorias` (que deben existir en el léxico — hay un test que lo verifica),
  `niveles`, `fuente` y `sinonimos` en el registro en que escribe la gente.
  Verifica con `POST /v1/ai/retrieve` antes de darlo por bueno.
- **Cambiar de proveedor de IA**: implementa `ai.Client` y `rag.Embedder`. Nada
  más del sistema conoce a Gemini.
- **Añadir un tipo de adicción**: solo `internal/addiction` (`catalogo` y, si
  aplica, `sinonimos`). No hace falta migración: la columna es `TEXT[]`, no un
  enum de Postgres, precisamente para que crecer sea barato. Sí hace falta que la
  app conozca el código nuevo.
- **Usar el tipo de adicción para personalizar** (hoy no se hace, §14): lo más
  directo es etiquetar los pasajes de `kb.json` con las adicciones a las que
  aplican y filtrar en `rag.buscar`, igual que ya se filtra por nivel de
  semáforo. Segundo paso, mencionarlo en `systemPrompt`.
- **Ajustar el clasificador**: `lexicon.json` (pesos y umbrales) más casos en
  `lexicon_test.go`. Los pesos actuales están razonados, no medidos con datos
  reales; lo mismo los del RAG (`pesoSemantico`, `pesoLexico`, `pesoCategoria`,
  `minSemantico`, `minRelativo`).
