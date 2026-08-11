# El muro de comunidad · especificación del backend

Historias de recuperación entre pares. Es la parte del sistema donde el riesgo
deja de ser técnico y pasa a ser humano: aquí la gente cuenta que está en
recuperación, y todo lo demás se subordina a eso.

Estado: **implementado y en el binario**. Paquete `internal/community`,
migración `0004_comunidad.sql`.

---

## 1. Las seis decisiones

**1 · El servidor nunca devuelve el `user_id` del autor, solo el alias.**
`author_id` existe en la tabla porque hace falta para borrar, moderar y
bloquear, y no sale del paquete. Poder cruzar un alias con una cuenta es lo peor
que puede filtrar esta app.

**2 · Paginación por cursor, no por número.** El muro ordena por racha y esa
cifra crece cada día: con `OFFSET` las filas se desplazan entre peticiones y
salen historias repetidas al avanzar. El cursor es opaco (base64) y codifica la
clave del orden vigente, así que cambiar de pestaña lo invalida por construcción.

**3 · `alias_snapshot` y `streak_days` se congelan al publicar.** Si alguien
cambia su alias, las historias viejas conservan el de entonces — cambiar el
alias solo puede desligarte hacia adelante. Y la racha no se recalcula: contar
214 días y haber recaído después es justo la clase de experiencia que sirve leer.

**4 · El umbral son 30 días.** Suficiente para tener algo que contar, bajo para
que no sea un club de veteranos: alguien con dos meses le habla mucho mejor a
quien lleva dos días que alguien con cinco años.

**5 · 404 en vez de 403** para historias ajenas, igual que en el resto de la API.

**6 · Los datos personales avisan pero no bloquean.** El servidor detecta
correos, teléfonos y enlaces y lo dice. No lo impide: alguien puede querer dejar
su contacto a propósito, y esa decisión es suya. Lo que no puede es publicarlo
sin darse cuenta de que el muro es público.

---

## 2. Tablas

`0004_comunidad.sql`. Enum nuevo: `story_state('PUBLICADA','EN_REVISION','RETIRADA')`.

### `community_profiles`
| Columna | Tipo | Notas |
|---|---|---|
| `user_id` | `UUID` PK → `users` CASCADE | sin fila = no ha entrado al muro |
| `alias` | `TEXT NOT NULL` | único **sin distinguir mayúsculas** (índice sobre `lower(alias)`) |
| `created_at`, `updated_at` | `TIMESTAMPTZ` | |

### `community_stories`
| Columna | Tipo | Notas |
|---|---|---|
| `id` | `UUID` PK | |
| `author_id` | `UUID` → `users` CASCADE | **nunca sale del servidor** |
| `alias_snapshot` | `TEXT NOT NULL` | congelado (decisión 3) |
| `streak_days` | `INT` **nullable** | `NULL` = eligió no mostrarla; distinto de 0 |
| `titulo` | `TEXT` | ≤ 120 |
| `cuerpo` | `TEXT` | 80 … 4000 |
| `objetivo` | `TEXT` | ≤ 60, opcional |
| `estado` | `story_state` | por defecto `PUBLICADA` |
| `useful_count` | `INT` | desnormalizado; lo mantiene la transacción del voto |
| `reports_count` | `INT` | idem |
| `created_at` | `TIMESTAMPTZ` | |

Índices: uno por orden del muro (`streak_days DESC`, `created_at DESC`,
`useful_count DESC`), todos parciales `WHERE estado='PUBLICADA'` y **terminados
en `id DESC`** porque el cursor necesita ese desempate; más
`(author_id, created_at DESC)` y la cola `(estado, created_at DESC) WHERE
estado='EN_REVISION'`.

### `community_story_useful`
PK `(story_id, user_id)` — la regla de negocio es la clave primaria: un voto por
persona, sin comprobarlo antes de insertar.

### `community_reports`
`(id, story_id, reporter_id, motivo, detalle, created_at)` con
`UNIQUE (story_id, reporter_id)`: un reporte por persona, o tres toques del
mismo dedo mandarían a revisión la historia de cualquiera.

### `community_blocks`
PK `(user_id, blocked_id)`, `CHECK (user_id <> blocked_id)`. Se guarda contra el
`user_id` **y no contra el alias** para que sobreviva a un cambio de nombre.

Las cinco cuelgan de `users` con `ON DELETE CASCADE`.

---

## 3. Endpoints

Todos exigen `Authorization: Bearer <access>`.

### `GET /v1/community/me`
```json
{"alias":"Ana R","diasRacha":47,"elegible":true,"faltanDias":0,
 "misHistorias":1,"aliasDesde":"2026-06-25T10:11:04Z"}
```
`alias` vacío = todavía no eligió uno. `faltanDias` es lo que falta para los 30.

### `PUT /v1/community/me`
`{"alias":"Ana R"}` → mismo cuerpo que `GET`.
3–24 caracteres; letras (con acentos), números, espacios, `-` y `_`. **Sin
arrobas ni puntos**: un alias que parece un usuario de red social o un correo
invita a buscar a esa persona fuera del muro.
`400 invalid-argument` si no cumple · `409 alias-taken` si ya es de alguien.

### `GET /v1/community/stories?sort=&limit=&cursor=`
`sort`: `racha` (por defecto, «Más tiempo») · `recientes` · `utiles`.
`limit`: 1–50, por defecto 20. `cursor`: el `siguienteCursor` de la respuesta
anterior, tal cual.

```json
{"items":[{"id":"…","alias":"Beto","diasRacha":1310,
  "titulo":"…","cuerpo":"…","objetivo":"…","estado":"PUBLICADA",
  "utiles":5,"meAyudo":false,"esMia":false,"createdAt":"…"}],
 "siguienteCursor":"MTMxMHwxYzJk…"}
```
Sin `siguienteCursor` = última página. En `racha`, quien no comparte la suya
(`diasRacha: null`) va **al final**, no al principio.

Tres filtros implícitos: se ocultan los autores bloqueados; se ocultan las
historias no publicadas **salvo las propias** (si una tuya entra en revisión
tienes que poder verlo); y el cursor pertenece al orden con el que se pidió.

### `POST /v1/community/stories`
`{"titulo":"…","cuerpo":"…","objetivo":"…","compartirRacha":true}`
→ `201` con la historia **más `avisos`**:
```json
{"id":"…","alias":"Ana R","diasRacha":47,"…":"…",
 "avisos":[{"tipo":"correo","mensaje":"Dejaste un correo visible…"}]}
```
- `403 not-eligible` — menos de 30 días.
- `409 alias-required` — no ha elegido alias.
- `400 invalid-argument` — tamaños.
- `422 unsafe-content` — filtro de contenido, con `motivo` y `message` (§4).

### `DELETE /v1/community/stories/{id}`
`204` · `404` si no es tuya o no existe (decisión 5).

### `PUT /v1/community/stories/{id}/useful`
`{"util":true}` → `{"utiles":6,"meAyudo":true}`. Idempotente: la app puede
reintentar sin red. El contador nunca baja de 0.

### `POST /v1/community/stories/{id}/reports`
`{"motivo":"contenido-peligroso","detalle":"…"}`
`motivo` ∈ `contenido-peligroso · datos-personales · acoso · spam · otro`.
→ `201 {"reportada":true,"estado":"EN_REVISION","enRevision":true}`

**No se devuelve cuántos reportes lleva**: eso le diría a cualquiera lo cerca que
está de tumbar una historia. Reportar la propia es `400`.

### `POST /v1/community/stories/{id}/block-author`
→ `{"bloqueado":true}`. Esconde **todo** lo de esa persona, presente y futuro.
Bloquearse a uno mismo es `400`.

---

## 4. Moderación

**Automática.** Con **3 reportes de personas distintas** la historia pasa sola a
`EN_REVISION` y desaparece del muro. La transición la hace la misma sentencia que
incrementa el contador, así que dos reportes simultáneos no pueden dejarla en 3
sin revisar. Tres y no uno para que nadie pueda silenciar a otro; tres y no diez
porque el muro es pequeño.

**Filtro de contenido**, antes de escribir: lo rechazado no llega a existir.

| Motivo | Qué caza |
|---|---|
| `suministro` | «dónde conseguir», «me lo vendía», «mi dealer», «te paso el contacto», «escríbeme al» |
| `dosis` | «2 gramos», «500mg», «media pastilla», «tres líneas» |
| `precio` | «$800», «300 pesos» |

Quien entra al muro está en un momento vulnerable, y «yo lo conseguía en X» no es
una experiencia, es un plan de consumo.

El filtro es **deliberadamente simple** (expresiones regulares) y por tanto
imperfecto. La decisión de fondo: es preferible dejar pasar algo dudoso que
censurar a alguien contando su recuperación. Por eso los patrones son
específicos («donde conseguir») y no genéricos («conseguir»): en este dominio
«conseguí ayuda» y «conseguir un grupo» son frases sanas y frecuentes.
`moderacion_test.go` fija diez frases de recuperación que **no** pueden
rechazarse; es tan importante como la lista de las que sí.

**Lo que falta para Play** (§6).

---

## 5. Borrado de cuenta

`DELETE /v1/users/me` con `{"password":"…"}` → `204`.

Se exige la contraseña porque el token puede estar en un teléfono que la persona
dejó abierto, y esto no se deshace. Es un `DELETE` de una fila en `users`: la
cascada del esquema se lleva check-ins, diario, ánimo, semáforo, alertas,
recaídas, tracker, recordatorios, notas clínicas **y las cinco tablas de
comunidad**. Que la cascada esté en el esquema y no en código es lo que hace que
añadir una tabla nueva no deje huérfanos por olvido —
`TestBorrarLaCuentaSeLlevaLaComunidadEntera` lo verifica tabla por tabla.

Sin periodo de gracia ni papelera: quien borra su cuenta en una app de adicciones
suele necesitar que desaparezca ahora, no en treinta días.

Detalle a tener en cuenta: el access token sigue siendo criptográficamente
válido hasta que expira (es stateless), pero ya no hay perfil detrás, así que
todo responde `404`.

---

## 6. Lo que NO está hecho

- **No hay endpoints de moderación.** Play exige poder revisar el contenido
  reportado, y hoy la cola `EN_REVISION` solo se puede mirar y vaciar con SQL.
  Falta un `GET /v1/admin/stories?estado=EN_REVISION` y un `PATCH` para retirar
  o restituir, detrás de un rol `moderator` que tampoco existe.
- **No hay notificación al autor** cuando su historia entra en revisión: la ve en
  su propio muro, y ya.
- **No hay edición** de historias. Solo publicar y borrar.
- **No hay comentarios ni mensajes directos**, y es deliberado: un hilo de
  comentarios en un muro de recuperación necesita una moderación que este
  proyecto no tiene hoy.
- El filtro de contenido **no mira imágenes** porque no se pueden subir.
