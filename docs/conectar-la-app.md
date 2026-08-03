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

```
POST /v1/auth/register
{"email":"ana@correo.mx","password":"12345678","displayName":"Ana"}
```

Responde:

```json
{
  "accessToken": "eyJhbGciOi...",
  "refreshToken": "kJ8s2...",
  "expiresIn": 900,
  "user": {"id":"...","email":"ana@correo.mx","displayName":"Ana","role":"patient"}
}
```

### Entrar

```
POST /v1/auth/login
{"email":"ana@correo.mx","password":"12345678"}
```

Responde lo mismo.

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
| Diario | `POST` / `GET` / `DELETE /v1/journal` |
| Ánimo | `POST` / `GET /v1/mood-logs` |
| Gráficas y tendencias | `GET /v1/stats/risk-trends?days=30` |
| Chat con el asistente | `POST /v1/ai/chat` |
| Historial del chat | `GET /v1/ai/messages` |
| Bandeja de alertas | `GET /v1/alerts` |
| Marcar alerta atendida | `PATCH /v1/alerts/{id}` |
| Perfil | `GET` / `PATCH /v1/users/me` |
| Contactos de emergencia | `PUT /v1/users/me/emergency-contacts` |
| Recordatorio diario | `GET` / `PUT /v1/reminders` |
| Mi terapeuta | `GET` / `POST /v1/me/therapists` |
| Mis sesiones | `GET /v1/me/sessions` |

La lista completa, con los campos de cada una, está en el
[README](../README.md#rutas).

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
