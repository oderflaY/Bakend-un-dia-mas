# Cómo se conecta todo

Guía práctica para enchufar la app contra este backend. No explica cómo está
hecho por dentro (eso es el [README](../README.md)), solo lo que hay que saber
desde fuera para que hablen.

---

## 1. La idea en una frase

La app le pide cosas al servidor por HTTP y le manda en cada petición un **token**
que dice quién es. Nada más. No hay SDK, no hay Firebase, no hay que inicializar
ninguna librería de Google.

```
   App (KMP)  ──HTTP + token──▶  Backend (Go)  ──▶  PostgreSQL
        ▲                             │
        └──────── SSE (avisos) ───────┘
```

---

## 2. La dirección del servidor

El servidor escucha en el puerto **8080**. Qué dirección escribir en la app
depende de dónde corra:

| Dónde corre la app | URL base |
|---|---|
| Emulador de Android | `http://10.0.2.2:8080` |
| Emulador de iOS / escritorio | `http://localhost:8080` |
| Teléfono físico en tu misma wifi | `http://LA-IP-DE-TU-PC:8080` |
| Servidor de verdad | `https://tu-dominio.mx` |

> **Lo que más falla al principio:** poner `localhost` en el emulador de Android.
> Para el emulador, `localhost` es él mismo, no tu computadora. Por eso existe la
> dirección especial `10.0.2.2`.

Tu IP en la wifi la ves con:

```fish
ip addr show | grep 'inet 192'
```

Y para que el teléfono llegue, el servidor tiene que escuchar en todas las
interfaces, no solo en la local. En tu `.env`:

```
ADDR=:8080
```

(que ya es el valor por defecto — así ya escucha en todas).

---

## 3. Las tres reglas de toda petición

**Regla 1 — El cuerpo va en JSON.**

```
Content-Type: application/json
```

**Regla 2 — Salvo login y registro, todo lleva el token.**

```
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
```

**Regla 3 — Nunca mandes el id del usuario.** El servidor lo saca del token. Si
mandas un `userId` en el cuerpo, lo ignora (y en la mayoría de rutas responde
400 por campo desconocido). Esto es a propósito: es lo que impide que alguien
lea los datos de otro cambiando un campo.

---

## 4. El flujo de entrada, paso a paso

### Registrarse

Lo mínimo son correo y contraseña (8 caracteres o más):

```
POST /v1/auth/register
{"email":"ana@correo.mx","password":"12345678","displayName":"Ana"}
```

Y si tu pantalla de alta ya pregunta de qué se está recuperando la persona,
mándalo aquí mismo. **Todo esto es opcional**, se puede completar después:

```
POST /v1/auth/register
{
  "email":"ana@correo.mx",
  "password":"12345678",
  "displayName":"Ana",
  "adicciones":["alcohol","tabaco"],
  "adiccionPrincipal":"alcohol",
  "consumoDesde":"2015-03-01",
  "enTratamiento":true
}
```

Responde:

```json
{
  "accessToken": "eyJhbGciOi...",
  "refreshToken": "kJ8s2...",
  "expiresIn": 900,
  "user": {
    "id": "...", "email": "ana@correo.mx", "displayName": "Ana", "role": "patient",
    "adicciones": ["alcohol", "tabaco"],
    "adiccionPrincipal": "alcohol",
    "consumoDesde": "2015-03-01",
    "enTratamiento": true,
    "onboardingCompleto": true
  }
}
```

### Entrar

```
POST /v1/auth/login
{"email":"ana@correo.mx","password":"12345678"}
```

Responde **lo mismo**, con el `user` completo. Por eso al entrar ya sabes si
enseñar el onboarding o ir directo al inicio: mira `onboardingCompleto`. No hace
falta llamar a `GET /v1/users/me` para eso.

### El perfil de recuperación

| Campo | Qué es | Reglas |
|---|---|---|
| `adicciones` | lista, porque muchas personas tienen más de una | cada valor del catálogo de abajo |
| `adiccionPrincipal` | la que manda: es de la que se cuenta la racha y el ahorro | tiene que estar en `adicciones` |
| `consumoDesde` | desde cuándo consume | `YYYY-MM-DD`, no futuro |
| `enTratamiento` | si ya lleva terapia o grupo | `true` / `false` |
| `onboardingCompleto` | te lo calcula el servidor | hay `adiccionPrincipal` |

Catálogo — usa estos códigos exactos:

```
alcohol · tabaco · cannabis · cocaina · metanfetamina
opioides · benzodiacepinas · inhalantes · juego · otra
```

Cuatro cosas prácticas:

- **`consumoDesde` no es la fecha de sobriedad.** La fecha desde la que lleva sin
  consumir es `startDate` y se manda a `PATCH /v1/tracker`. Confundirlas hace que
  el contador de la pantalla de inicio marque diez años.
- Si mandas **una sola** adicción y no mandas `adiccionPrincipal`, esa se toma
  como principal. No tienes que repetirla.
- Se aceptan sinónimos al escribir (`cerveza`, `nicotina`, `vape`, `marihuana`,
  `cristal`, `apuestas`…), pero **el servidor siempre te devuelve el código
  canónico**. Si mandas `"nicotina"` recibes `"tabaco"`. Guarda lo que te
  devuelve, no lo que mandaste.
- Un tipo que no existe es un 400 con el valor dentro del mensaje:
  `{"error":"invalid-argument","message":"tipo de adicción desconocido: chela"}`.

Para completarlo o cambiarlo después, los mismos campos van a
`PATCH /v1/users/me`. Si quitas de la lista la que era principal, el servidor
elige otra en vez de fallarte.

### Guardar los dos tokens

| Token | Para qué | Dura |
|---|---|---|
| `accessToken` | va en la cabecera de cada petición | 15 minutos |
| `refreshToken` | sirve para conseguir otro `accessToken` | 30 días |

El `refreshToken` guárdalo donde no se borre al cerrar la app (DataStore,
Keychain). El `accessToken` puede vivir en memoria.

### Cuando el token caduca

Cualquier petición responde **401**. Entonces:

```
POST /v1/auth/refresh
{"refreshToken":"kJ8s2..."}
```

Y te devuelve un par nuevo. **Importante:** el `refreshToken` viejo deja de
servir en ese momento — guarda siempre el nuevo. Si el refresh también responde
401, es que la sesión murió: manda al usuario a la pantalla de login.

### Salir

```
POST /v1/auth/logout        (con Authorization)
```

Invalida todos los refresh tokens de esa cuenta, en todos los dispositivos.

---

## 5. Qué ruta usa cada pantalla

| Pantalla de la app | Qué llamar |
|---|---|
| Inicio / contador de racha | `GET /v1/tracker` |
| Configurar fecha de inicio y ahorro | `PATCH /v1/tracker` |
| Botón "recaí" | `POST /v1/relapses` |
| Historial de recaídas | `GET /v1/relapses` |
| Check-in diario | `POST /v1/check-ins` |
| Historial de check-ins | `GET /v1/check-ins?limit=20` |
| Semáforo (guardar evaluación) | `POST /v1/traffic-light` |
| Semáforo (estado e historial) | `GET /v1/traffic-light` |
| Diario (la respuesta trae el análisis, §5 bis) | `POST` / `GET` / `DELETE /v1/journal` |
| Ánimo (igual que el diario) | `POST` / `GET /v1/mood-logs` |
| Avisar antes de guardar, sin efectos | `POST /v1/analysis/text` |
| Gráficas y tendencias | `GET /v1/stats/risk-trends?days=30` |
| Chat con el asistente | `POST /v1/ai/chat` |
| Historial del chat | `GET /v1/ai/messages` |
| Diagnóstico del RAG (solo desarrollo) | `POST /v1/ai/retrieve` |
| Bandeja de alertas | `GET /v1/alerts` |
| Marcar alerta atendida | `PATCH /v1/alerts/{id}` |
| Onboarding (tipo de adicción, §4) | `POST /v1/auth/register` o `PATCH /v1/users/me` |
| Perfil | `GET` / `PATCH /v1/users/me` |
| Borrar mi cuenta | `DELETE /v1/users/me` con `{password}` |
| Muro de comunidad | `GET /v1/community/stories?sort=` |
| Publicar historia | `POST /v1/community/stories` |
| Mi alias del muro | `GET` / `PUT /v1/community/me` |
| «Me ayudó» · Reportar · No ver a esta persona | `PUT …/useful` · `POST …/reports` · `POST …/block-author` |
| Contactos de emergencia | `PUT /v1/users/me/emergency-contacts` |
| Recordatorio diario | `GET` / `PUT /v1/reminders` |
| Mi terapeuta | `GET` / `POST /v1/me/therapists` |
| Mis sesiones | `GET /v1/me/sessions` |

La lista completa, con los campos de cada una, está en el
[README](../README.md#rutas).

---

## 5 bis. El diario te contesta

Cuando guardas una entrada de diario o un ánimo, el servidor **analiza el texto y
te devuelve el resultado en la misma respuesta**. No hace falta volver a pedir el
semáforo ni esperar el evento SSE.

```
POST /v1/journal
{"content":"Tengo muchas ganas de fumar y estoy solo en la casa"}
```

```json
{
  "id": "…",
  "content": "…",
  "createdAt": "2026-08-04T21:14:00Z",
  "analisis": {
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
}
```

**Lo que tienes que entender de aquí son dos campos que parecen el mismo y no lo son:**

- `nivel` es lo que dice **ese texto** que acabas de mandar.
- `semaforo` es **cómo quedó la persona**.

Son distintos cuando el análisis no sube nada. Si alguien está en rojo y escribe
un día tranquilo, recibes `nivel: "VERDE"` y `semaforo: "ROJO"`: el análisis solo
puede subir el semáforo, nunca bajarlo. Para bajarlo hace falta un check-in, que
es un acto consciente de la persona. **Pinta `semaforo`, no `nivel`.**

`subioElSemaforo` te dice si este texto cambió algo; sirve para decidir si animas
la transición en pantalla o no dices nada.

`apoyo` son hasta tres tarjetas de material de apoyo que puedes mostrar
directamente: ya vienen elegidas para el nivel de la persona. Si no hay nada
relevante, llega vacío — no inventes contenido para rellenar.

`semaforo` puede llegar en `null` si el servidor no pudo leer el estado vigente.
En ese caso muestra el resto del análisis y deja el semáforo como lo tenías.

Mismo formato en `POST /v1/mood-logs`. Y si quieres avisar **antes** de guardar
("esto que escribiste va a poner tu semáforo en amarillo"), manda el texto a
`POST /v1/analysis/text`: responde igual pero no guarda nada y `semaforo` viene
siempre en `null`.

El texto del diario **no sale del servidor**: se analiza ahí mismo y ni el
terapeuta ni Gemini lo ven nunca.

---

## 6. El chat con el asistente

Solo manda el texto:

```
POST /v1/ai/chat
{"prompt":"tengo muchas ganas de tomar"}
```

No mandes el historial ni el nivel de riesgo: **el servidor ya los tiene**. Los
lee de la base con el id del token. Por eso la conversación sobrevive a que el
usuario reinstale la app.

Respuestas que hay que manejar:

| Código | Qué pasó | Qué hacer |
|---|---|---|
| 200 | todo bien | mostrar `reply` |
| 429 | escribió demasiado rápido (20 mensajes/minuto) | "espera un momento" |
| 400 | mensaje vacío o de más de 4000 caracteres | avisar antes de mandarlo |
| 502 | Gemini no responde | "el asistente no está disponible" |

Si el servidor arranca sin `GEMINI_API_KEY`, estas rutas **no existen** y
responden 404. El resto del backend funciona igual.

### El material de apoyo (RAG)

Antes de responder, el servidor busca en un corpus propio los pasajes que tengan
que ver con lo que la persona escribió —técnicas para el antojo, qué hacer tras
una recaída, líneas de ayuda— y se los pasa al modelo. La app no hace nada para
esto: es transparente. Lo único que cambia es que el asistente ya no inventa
números de teléfono ni técnicas.

La búsqueda mezcla dos cosas: coincidencia de palabras (siempre disponible, en el
proceso) y similitud de significado (con embeddings de Gemini). Si los embeddings
fallan, la búsqueda sigue funcionando con lo primero y el chat responde igual.

Para depurar hay una ruta que enseña qué se habría recuperado, sin llamar al
modelo ni guardar nada:

```
POST /v1/ai/retrieve
{"query":"tengo muchas ganas de fumar"}
```

Devuelve los pasajes con su puntuación (`score`, `semantico`, `lexico`,
`categorias`). Sirve para afinar el corpus; la app no necesita llamarla.

---

## 6 bis. El muro de comunidad

Ocho rutas, todas con Bearer. La especificación completa —tablas, validaciones y
cada respuesta— está en [backend-comunidad.md](backend-comunidad.md); aquí va lo
que necesitas para conectarlo.

### Antes de publicar: alias y elegibilidad

```
GET /v1/community/me
→ {"alias":"Ana R","diasRacha":47,"elegible":true,"faltanDias":0,"misHistorias":1}
```

`alias` vacío = todavía no eligió uno; mándalo con `PUT /v1/community/me
{"alias":"Ana R"}`. **Hacen falta 30 días de racha para publicar**: si
`elegible` es `false`, `faltanDias` te dice cuántos quedan y con eso pintas el
estado vacío en vez de dejar que intente y falle.

Reglas del alias: 3–24 caracteres, letras (con acentos), números, espacios, `-` y
`_`. Sin arrobas ni puntos. `409 alias-taken` si ya es de alguien.

### El muro

```
GET /v1/community/stories?sort=racha&limit=20
GET /v1/community/stories?sort=racha&cursor=MTMxMHwxYzJk…
```

`sort`: `racha` («Más tiempo», por defecto) · `recientes` · `utiles`.

```json
{"items":[{"id":"…","alias":"Beto","diasRacha":1310,"titulo":"…","cuerpo":"…",
           "objetivo":"…","estado":"PUBLICADA","utiles":5,
           "meAyudo":false,"esMia":false,"createdAt":"…"}],
 "siguienteCursor":"MTMxMHwxYzJk…"}
```

**Pagina con `siguienteCursor`, no con números de página.** Guárdalo tal cual y
devuélvelo en la siguiente petición; cuando no venga, llegaste al final. Es
opaco: no lo construyas ni lo interpretes. Y **pertenece al orden con el que
pediste**: si el usuario cambia de pestaña, empieza de cero sin cursor.

`diasRacha` puede ser `null`: esa persona eligió no mostrar su racha. No pintes
"0 días", pinta la tarjeta sin ese dato. En el orden por racha esas historias van
al final.

`esMia` te dice cuáles llevan el menú de borrar en vez del de reportar. **No hay
ningún id de usuario en la respuesta, ni el tuyo ni el de nadie**: solo el alias.

### Publicar

```
POST /v1/community/stories
{"titulo":"…","cuerpo":"…","objetivo":"un día más","compartirRacha":true}
```

`cuerpo` entre 80 y 4000 caracteres (pon el contador en la pantalla), `titulo`
≤ 120, `objetivo` ≤ 60 y opcional. `compartirRacha: false` publica sin el número.

La respuesta es la historia **más un array `avisos`**:

```json
{"id":"…","alias":"Ana R","…":"…",
 "avisos":[{"tipo":"correo","mensaje":"Dejaste un correo visible. El muro es público…"}]}
```

Los avisos llegan **con la historia ya publicada**: detectan correos, teléfonos y
enlaces, y avisan sin bloquear. Enséñalos después de guardar, con la opción de
borrarla. La decisión de dejar su contacto es de quien escribe; lo que no puede
es que se le escape sin enterarse.

Lo que **sí** bloquea:

| Código | Qué pasó | Qué enseñar |
|---|---|---|
| `422 unsafe-content` | dosis, precios o dónde conseguir | el `message` del servidor, tal cual |
| `403 not-eligible` | menos de 30 días | cuántos le faltan |
| `409 alias-required` | no ha elegido alias | mándalo al `PUT /v1/community/me` |
| `400 invalid-argument` | tamaños | el contador ya debería haberlo evitado |

El `422` trae `motivo` (`suministro` · `dosis` · `precio`) por si quieres un
texto propio, pero el `message` ya viene escrito para leerse tal cual.

### Moderación desde la app

```
PUT    /v1/community/stories/{id}/useful        {"util":true}   → {"utiles":6,"meAyudo":true}
POST   /v1/community/stories/{id}/reports       {"motivo":"…","detalle":"…"}
POST   /v1/community/stories/{id}/block-author
DELETE /v1/community/stories/{id}
```

`useful` es idempotente: puedes reintentar sin red sin miedo a contar doble.

`motivo` ∈ `contenido-peligroso · datos-personales · acoso · spam · otro`. La
respuesta trae `enRevision`: con tres reportes de personas distintas la historia
se retira sola del muro. **No te decimos cuántos reportes lleva** — eso le diría
a cualquiera lo cerca que está de tumbar una historia.

`block-author` esconde todo lo de esa persona, para siempre y aunque cambie de
alias. No hay pantalla para deshacerlo todavía.

Una historia ajena responde `404` al borrarla, igual que una que no existe.

---

## 7. Los avisos en tiempo real (esto sustituye a las notificaciones push)

Es una conexión HTTP que **se queda abierta** y por la que el servidor va
mandando líneas cuando pasa algo. Se llama SSE.

```
GET /v1/events        (con Authorization)
```

Lo que llega tiene esta forma:

```
event: ready
data: {}

data: {"type":"traffic_light","payload":{...},"createdAt":"..."}

data: {"type":"alert","payload":{"alert":{...},"trustedContact":{...}}}

: ping
```

- `event: ready` llega al conectar: ya está el canal abierto.
- Las líneas que empiezan con `:` son latidos para mantener viva la conexión.
  **Ignóralas.**
- Los tipos de evento son `alert`, `traffic_light` y `check_in_reminder`.

**Lo que tienes que saber:** si la app está cerrada, el aviso no llega. No se
pierde —queda guardado— pero no aparece hasta que el usuario vuelve. Por eso, al
abrir la app, llama siempre a `GET /v1/alerts`: ahí está todo lo que pasó
mientras no estaba conectada.

Reconecta con reintentos si la conexión se cae (2s, 4s, 8s… hasta un máximo).

---

## 8. Cómo se ve un error

Siempre igual, en todas las rutas:

```json
{"error":"invalid-argument","message":"cravingLevel debe estar entre 0 y 10"}
```

Usa `error` para decidir qué hacer (es un código estable). El `message` es para
que tú lo leas mientras desarrollas, **no para enseñárselo al usuario**.

| Código HTTP | `error` típico | Significa |
|---|---|---|
| 400 | `invalid-argument` | el cuerpo va mal |
| 401 | `unauthenticated` | falta el token o caducó → refresca |
| 403 | `forbidden` | tu rol no puede hacer eso |
| 404 | `not-found` | no existe, **o no es tuyo** |
| 409 | `email-taken` | ese correo ya está registrado |
| 429 | `rate-limited` | demasiadas peticiones |
| 502 | `ai-unavailable` | falló Gemini |

Un detalle: pedir algo que existe pero es de otro usuario responde **404**, no
403. Es a propósito — un 403 confirmaría que ese dato existe.

---

## 9. Ejemplo mínimo en Kotlin (Ktor)

```kotlin
// Una sola vez, al arrancar la app.
val http = HttpClient {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    defaultRequest {
        url("http://10.0.2.2:8080")     // ← la dirección de la tabla del punto 2
        contentType(ContentType.Application.Json)
    }
    install(Auth) {
        bearer {
            loadTokens { BearerTokens(accessToken, refreshToken) }
            refreshTokens {
                // Ktor llama a esto solo cuando algo responde 401.
                val nuevos: TokenResponse = client.post("/v1/auth/refresh") {
                    markAsRefreshTokenRequest()
                    setBody(mapOf("refreshToken" to oldTokens?.refreshToken))
                }.body()
                guardar(nuevos)          // ¡guarda el refreshToken nuevo!
                BearerTokens(nuevos.accessToken, nuevos.refreshToken)
            }
        }
    }
}

// A partir de aquí, cada llamada ya va autenticada sola.
suspend fun tracker(): Tracker = http.get("/v1/tracker").body()

suspend fun checkIn(nivel: String, craving: Int) =
    http.post("/v1/check-ins") {
        setBody(mapOf("riskLevel" to nivel, "cravingLevel" to craving))
    }
```

---

## 10. Antes de decir "no funciona"

Recórrelo en este orden. Cada paso descarta el anterior.

1. **¿El servidor está vivo?** En la terminal de tu computadora:
   ```fish
   curl -s localhost:8080/healthz
   ```
   Tiene que responder `{"status":"ok"}`. Si no, el servidor no está corriendo
   (`make run`) o Postgres está caído (`systemctl status postgresql`).

2. **¿Llega desde el dispositivo?** Si es un teléfono, abre en su navegador
   `http://LA-IP-DE-TU-PC:8080/healthz`. Si ahí no carga, es el cortafuegos o la
   wifi, no tu código.

3. **¿Es problema de HTTP sin cifrar?** Android bloquea `http://` por defecto.
   Para desarrollo, en `AndroidManifest.xml`:
   ```xml
   <application android:usesCleartextTraffic="true">
   ```
   En producción esto se quita y se usa `https://`.

4. **¿Mandas el token?** Un 401 en una ruta que debería funcionar casi siempre es
   la cabecera `Authorization` mal escrita. Tiene que decir `Bearer ` con espacio
   antes del token.

5. **¿Un 400 raro?** El servidor rechaza campos que no conoce. Si mandas
   `{"userId":"..."}` o `{"user_id":"..."}` en el cuerpo, responde 400. Manda
   solo los campos que aparecen en el README, con esos nombres exactos.

6. **Mira el log del servidor.** Cada petición deja una línea con método, ruta y
   código. Si tu petición no aparece ahí, nunca llegó: el problema es de red o de
   dirección, no del backend.
