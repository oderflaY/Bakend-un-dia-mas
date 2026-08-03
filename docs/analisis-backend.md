# Análisis del backend — UnDiaMas

> Revisión del estado real del sistema al **3 de agosto de 2026** (rama `main`, commit `38a5b98`).
> Alcance: Cloud Functions (8 archivos), `firestore.rules`, `firestore.indexes.json`, `firebase.json`,
> capa de datos KMP completa (`shared/src/commonMain/.../core/data/firebase/`, 13 archivos) y su consumo desde `AppState`.
>
> **Estado de tests al momento de la revisión:** `functions` 15/15 en verde · `:shared:allTests` en verde.

---

## 1. Arquitectura general

No hay servidor propio. Todo el "backend" son tres piezas:

```
App KMP (Compose)
   │
   ├─ AppState ──── repositorios ────► Firestore (colecciones planas en raíz)
   │                                        │
   │                                        ├─ trigger onCreate ─► Cloud Functions
   │                                        │
   └─ CloudFunctionsAiProvider ──callable──► agentChat ──► Gemini 2.5 Flash
                                                 │
                                                 └─ Admin SDK ─► alerts / ai_logs
```

Decisión estructural clave: **todas las colecciones son raíz, no subcolecciones**. El aislamiento
entre usuarios sale del campo `userId` **dentro del documento**, no de la ruta. Esto tiene una
consecuencia que atraviesa todo el código: *toda consulta del cliente debe filtrar por `userId`*, o
Firestore rechaza la consulta entera con `PERMISSION_DENIED` aunque el usuario tenga documentos propios.

---

## 2. Estructura de la base de datos

### 2.1 Mapa de colecciones

Nombres canónicos en `ClinicalRepositories.kt` (objeto `Colecciones`) y espejados en `functions/src/index.ts`.

| Colección | Doc ID | Escribe | Lee | Estado real |
|---|---|---|---|---|
| `users` | uid | app (sin `role`) | dueño + terapeuta | ✅ activa |
| `sobriety_trackers` | uid | app | dueño + terapeuta | ✅ activa (`PerfilRepositoryImpl`) |
| `check_ins` | auto | app | dueño + terapeuta + CF | ✅ activa |
| `ai_messages` | auto | app | **solo dueño** | ✅ activa |
| `journal_entries` | auto | app | dueño + terapeuta | ✅ activa |
| `mood_logs` | auto | app | dueño + terapeuta | ✅ activa |
| `ai_logs` | auto | **solo Cloud Function** | dueño + terapeuta | ✅ activa |
| `alerts` | auto | **solo Cloud Function** | solo dueño | ✅ activa |
| `traffic_light_logs` | auto | app | dueño + terapeuta | ⚠️ repo existe, nadie lo llama |
| `relapse_events` | auto | app | dueño + terapeuta | ❌ sin repositorio |
| `sobriety_logs` | auto | app | dueño + terapeuta | ❌ sin repositorio |
| `sessions` | auto | terapeuta | paciente + terapeuta | ❌ doc modelado, sin repo |
| `clinical_notes` | auto | terapeuta | paciente + terapeuta | ❌ doc modelado, sin repo |

### 2.2 Esquema por colección

Fuente de verdad: `ClinicalDocs.kt`. Todo campo tiene default (documentos creados en momentos
distintos no traen los mismos campos) y toda fecha es `Timestamp` de Firestore nullable, nunca `Long`.

#### `/users/{uid}`

| Campo | Tipo | Notas |
|---|---|---|
| `uid` | String | Duplicado del doc ID (ver hallazgo #5) |
| `displayName` | String | |
| `email` | String | |
| `role` | String | `patient` \| `therapist` — **el cliente nunca lo escribe** |
| `authProvider` | String | |
| `status` | String | default `active` |
| `isEmailVerified` | Boolean | |
| `lastLogin` / `lastLoginAt` | Timestamp? | Docs antiguos usan el primero; se leen ambos |
| `createdAt` | Timestamp? | |
| `contactosEmergencia` | List\<ContactoEmergencia\> | `{nombre, telefono, rol}`. **El primero es el contacto de confianza que lee el protocolo de emergencia** |
| `porQuePersonal` | String | "Mi por qué" |
| `recordRachaSegundos` | Long | Récord histórico, se conserva tras recaída |
| `fcmToken` | String? | Para la notificación de emergencia — **hoy siempre `null`, ver hallazgo #1** |

#### `/sobriety_trackers/{uid}`

`userId`, `startDate: Timestamp?`, `dailySavingsRate: Double`, `currency: String = "MXN"`,
`trafficLightStatus: String = "GREEN"`, `lastStatusUpdate: Timestamp?`

#### `/check_ins/{auto}`

`userId`, `riskLevel: String = "GREEN"`, `cravingLevel: Int`, `mood: String`,
`triggers: List<String>`, `note: String`, `answers: Map<String,String>`, `timestamp: Timestamp?`

#### `/ai_messages/{auto}`

`userId`, `role: String` (`USUARIO`\|`ASISTENTE`), `content: String`,
`riskLevelContext: String?`, `timestamp: Timestamp?`

#### `/alerts/{auto}` — escrito solo por `agentChat`

`userId`, `riskLevel: String = "VERDE"`, `message: String`, `handled: Boolean = false`, `timestamp: Timestamp?`

#### `/ai_logs/{auto}` — escrito solo por `onAiMessageCreated`

`userId`, `checkInId: String`, `aiMessageId: String`, `riskLevel: String`, `createdAt: Timestamp?`

#### Resto

- `/traffic_light_logs`: `userId`, `status`, `reason`, `triggerLevel: Int (1..5)`, `suggestedActions: List<String>`, `timestamp`
- `/relapse_events`: `userId`, `note`, `triggers: List<String>`, `previousStreakSeconds: Long`, `timestamp`
- `/sobriety_logs`: `userId`, `streakSeconds: Long`, `savedAmount: Double`, `timestamp`
- `/journal_entries`: `userId`, `content`, `createdAt`
- `/mood_logs`: `userId`, `mood: String = "NEUTRAL"`, `timestamp`
- `/sessions`: `patientId`, `therapistId`, `status = "scheduled"`, `scheduledAt`, `notes`
- `/clinical_notes`: `patientId`, `therapistId`, `content`, `createdAt`

### 2.3 Convención del semáforo

Hay **tres vocabularios** para el mismo valor:

| Capa | Valores |
|---|---|
| Firestore (persistencia) | `GREEN` / `YELLOW` / `RED` |
| Dominio Kotlin | `VERDE` / `AMARILLO` / `ROJO` |
| Cloud Functions (`types.ts`) | `VERDE` / `AMARILLO` / `ROJO` |

La traducción está **duplicada** en `RiskLevelCodes.kt` y `firestoreMappers.ts:toRiskLevel()`. Ambas
aceptan los dos idiomas y ambas caen a **verde** ante un código desconocido — decisión deliberada y
correcta: inventar un `ROJO` por dato corrupto dispararía el protocolo de emergencia de alguien sin motivo.

### 2.4 Índices

`firestore.indexes.json` declara **uno solo**:

```
check_ins: (userId ASC, timestamp DESC)
```

Sirve a las dos queries de Cloud Functions. Los repositorios del cliente no necesitan más
**porque ordenan en memoria** — lo cual es a su vez el hallazgo #14.

---

## 3. Endpoints (Cloud Functions)

### 3.1 `onCheckInCreated` — trigger Firestore

- **Disparo:** `check_ins/{checkInId}` onCreate
- **Flujo:** lee `userId` del documento → carga `users/{userId}` → `buildEmergencyAction()` →
  si `riskLevel == ROJO`, envía push FCM y registra el contacto de confianza sugerido
- **Lógica pura:** `emergencyProtocol.ts` (testeada sin emulador)
- **Salidas silenciosas:** documento sin `userId`, o perfil inexistente → `return` sin log

### 3.2 `onAiMessageCreated` — trigger Firestore

- **Disparo:** `ai_messages/{aiMessageId}` onCreate
- **Flujo:** busca el check-in más reciente del usuario (el chat no está atado a un check-in concreto
  en la UI, así que se usa el vigente como contexto de riesgo) → `buildLogIaTrace()` → escribe en `ai_logs`
- **Lógica pura:** `logIaTrace.ts`. Lanza si el `userId` del mensaje no coincide con el del check-in
- **Salida silenciosa:** usuario sin ningún check-in → no se escribe traza

### 3.3 `agentChat` — HTTPS callable

```
Request  (lo que envía la app):  { prompt: String, riskLevel: String, history: [{role, content}] }
Request  (lo que la función lee): { prompt: String }          ← ver hallazgo #2
Response: { reply: String, savedAlertId?: String }
Errores:  unauthenticated | invalid-argument
```

- **uid:** siempre de `request.auth.uid`, **nunca del payload** — el modelo no puede pedir datos ajenos
  aunque lo intente vía argumentos de una tool. Diseño correcto.
- **Secreto:** `GEMINI_API_KEY` vía `defineSecret`. La app nunca la lleva.
- **Modelo:** `gemini-2.5-flash` (REST)
- **Tools expuestas al modelo:**

| Tool | Parámetros | Efecto |
|---|---|---|
| `leer_historial_reciente` | `limite: number = 5` | Lee check-ins del **uid autenticado** (no parametrizable) |
| `guardar_alerta` | `nivelRiesgo`, `mensaje` | Inserta en `/alerts` con Admin SDK |

- **Orquestación:** `runAgentTurn()` — primer turno → ejecuta tools → segundo turno con resultados.
  Puro y testeado, con el cliente Gemini inyectado.

### 3.4 Lógica sin endpoint

`aggregateRiskTrends()` (`statsAggregation.ts`) está implementada y testeada pero **no la exporta
ninguna función**. Ver hallazgo #7.

---

## 4. Modelo de seguridad

Regla base: cada paciente solo ve lo suyo. Excepción explícita del proyecto: las cuentas con
`role == "therapist"` pueden **leer** datos clínicos de pacientes, nunca escribirlos.

Aciertos del diseño actual:

- El campo `role` está **prohibido de escritura desde el cliente en todos los casos**
  (`noTocaRol()` / `noTraeRolNuevo()`). Sin esto, cualquier paciente se ascendería a terapeuta.
- `ai_messages` (la conversación con el asistente) **no se abre al terapeuta**. Correcto: es el
  dato más íntimo de la app.
- `ai_logs` y `alerts` son `allow write: if false` — solo Admin SDK.
- `match /{document=**} { allow read, write: if false; }` cierra todo lo no declarado.

Debilidades: ver hallazgos #4, #5, #10, #16.

---

## 5. Hallazgos

### 🔴 Críticos

**#1 — El protocolo de emergencia nunca envía la notificación.**
Es la pieza más crítica del sistema según `CLAUDE.md`, y está rota de punta a punta: no existe ningún
`FirebaseMessaging`, ningún `FirebaseMessagingService` en el manifiesto, ninguna escritura de `fcmToken`.
El campo existe en `ClinicalDocs.kt:61` y la función lo lee en `index.ts:49`, pero **siempre vale `null`**,
así que `getMessaging().send()` no se ejecuta jamás. El único aviso que llega al usuario es la
notificación *local* de biometría (`NotificationHelper`), que es otra cosa.

**#2 — `agentChat` ignora el nivel de riesgo y el historial de conversación.**
El cliente envía `{prompt, riskLevel, history}` (`CloudFunctionsAiProvider.kt:43-47`) pero la función
solo lee `request.data.prompt` (`index.ts:108`). Consecuencias:
- el asistente **no tiene memoria** entre turnos;
- **no adapta el tono al semáforo**, que es justamente lo que pide la Fase 2·05;
- no hay *system prompt*: el modelo no sabe que habla con alguien en recuperación de una adicción.

**#3 — El segundo turno de function-calling está mal formado.**
`geminiClient.ts:29-36` envía `role: "function"` sin incluir antes el turno del modelo con su
`functionCall`. La API de Gemini exige la secuencia `user → model(functionCall) → function(functionResponse)`.
Los tests de `aiAgent` no lo detectan porque mockean el `GeminiClient`. En producción, cualquier
respuesta que use tools falla o alucina.

**#4 — Agujero en las reglas de `ai_messages`.**
`firestore.rules:109-113` usa un `OR`: basta con que `request.resource.data.userId == mi uid` para pasar.
Un usuario que conozca un doc ID ajeno puede **sobrescribir el mensaje de otra persona** poniéndose a sí
mismo como `userId`. El `update` debe exigir *ambas* condiciones (dueño del documento existente **y** del entrante).

**#5 — Inconsistencia de doc ID que rompe el protocolo de emergencia.**
`onCheckInCreated` busca el perfil con `.doc(userId)` (`index.ts:42`) y hace `return` si no existe.
Pero `UsersRepository` está escrito asumiendo que **conviven documentos con ID autogenerado**
(`ClinicalRepositories.kt:42-49`). Para esos usuarios el semáforo rojo se queda en silencio absoluto.
Peor aún: esos documentos tampoco son legibles por su propio dueño, porque la regla `esDuenio(userId)`
compara contra el **ID del documento**, no contra el campo `uid`. Hay que fijar una sola convención
(doc ID == uid) y migrar los documentos que no la cumplan.

### 🟡 Importantes

**#6 — Las recaídas no se persisten.** `RelapseEvent` existe en dominio, `relapse_events` tiene reglas
y esquema… y ningún repositorio. El reinicio de racha conservando el récord (Fase 2·04, Fase 4·10) no
deja rastro en la base.

**#7 — `aggregateRiskTrends` es código muerto.** Testeada, no exportada por ninguna función. No hay
endpoint de estadísticas: la Fase 2·06 queda sin cerrar.

**#8 — No hay recordatorios de check-in.** La Fase 3·09 los pide; no existe ninguna función `onSchedule`.

**#9 — Falta Google Sign-In.** La Fase 3·07 exige Google + anónimo; solo hay anónimo + email/password
(`AuthRepositoryImpl`).

**#10 — `agentChat` sin App Check ni límite de uso.** Un callable autenticado que gasta tokens de Gemini,
sin `enforceAppCheck`, sin rate limit por usuario, sin tope de longitud de `prompt`. Con sesión anónima
habilitada, crear cuentas nuevas es gratis e ilimitado.

**#11 — Storage no está configurado.** La Fase 3·07 lo menciona para adjuntos del diario; no hay
`storage.rules` ni sección `storage` en `firebase.json`.

**#12 — Las alertas no se pueden marcar como atendidas.** `AlertDoc.handled` existe, pero las reglas son
`allow write: if false` y `AlertaRepositoryImpl` no tiene escritura. El campo queda congelado en `false`.

**#13 — Datos que se pierden al reinstalar.** Cápsulas del tiempo, hábitos, anclas y
`urgeSessionsCompleted` viven solo en `mutableStateListOf` dentro de `AppState` (`AppState.kt:155-158`).
Sin colección, sin reglas, sin sincronización entre dispositivos.

### 🟢 Menores

**#14 — Lecturas sin `limit()`.** Todos los repositorios traen la colección completa del usuario y
ordenan/recortan **en memoria** (`.sortedByDescending{}.take(limit)`). Con un año de check-ins diarios
son cientos de documentos descargados por cada snapshot. Hay que mover `orderBy`/`limit` a la query.

**#15 — Índices insuficientes.** Solo existe `check_ins(userId, timestamp)`. Al corregir #14 harán falta
5-6 índices compuestos más (`ai_messages`, `journal_entries`, `mood_logs`, `alerts`, `ai_logs`,
`traffic_light_logs`), todos con la forma `(userId ASC, timestamp DESC)`.

**#16 — `esTerapeuta()` cuesta una lectura de Firestore por evaluación de regla.** El propio comentario
del archivo lo admite. Migrar a *custom claims* en el token elimina el coste y rompe la dependencia
circular con `/users`.

**#17 — `UsersRepository.listPatients()` falla para pacientes.** Un paciente que la llame recibe
`PERMISSION_DENIED` sobre la consulta entera. Correcto desde seguridad, pero la UI debe filtrar antes
de invocarla.

---

## 6. Orden de trabajo sugerido

| # | Acción | Desbloquea |
|---|---|---|
| 1 | FCM completo: obtener token → `users.fcmToken` → `FirebaseMessagingService` | Hallazgo #1 — el semáforo rojo deja de ser decorativo |
| 2 | Unificar doc ID de `users` a uid + migración; corregir regla de `ai_messages` | #4, #5, #17 |
| 3 | Pasar `riskLevel` + `history` + system prompt a `agentChat`; corregir la secuencia de turnos en `RestGeminiClient`, con test contra emulador (no mockeado) | #2, #3 |
| 4 | `RelapseEventRepository` + escritura de `traffic_light_logs` en cada evaluación del semáforo | #6, y cierra Fase 2·04 |
| 5 | `limit()`/`orderBy` en query + índices compuestos | #14, #15 |
| 6 | Callable de estadísticas sobre `aggregateRiskTrends` + `onSchedule` de recordatorios | #7, #8 — cierra Fase 2·06 y 3·09 |
| 7 | App Check + rate limit en `agentChat`; Google Sign-In; `storage.rules` | #9, #10, #11 |
