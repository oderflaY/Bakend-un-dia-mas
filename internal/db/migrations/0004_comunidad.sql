-- El muro de comunidad: historias de recuperación entre pares.
--
-- Es la parte del sistema donde el riesgo ya no es técnico sino humano. Aquí la
-- gente cuenta que está en recuperación, y eso obliga a tres cosas que el
-- esquema tiene que sostener por sí mismo:
--
--   1. **El autor no se expone.** `author_id` existe porque hace falta para
--      borrar, moderar y bloquear, pero NINGUNA consulta de lectura lo
--      devuelve: hacia fuera solo viaja `alias_snapshot`. Poder cruzar un alias
--      con una cuenta es lo peor que puede filtrar esta app.
--   2. **Lo publicado se congela.** `alias_snapshot` y `streak_days` se copian
--      en el INSERT y no se recalculan nunca. Cambiar el alias solo desliga
--      hacia adelante, y una historia que decía "214 días" sigue diciéndolo
--      aunque quien la escribió haya recaído después: esa es justamente la
--      experiencia que sirve leer.
--   3. **Se puede moderar.** Play exige reportar, bloquear y revisar contenido
--      de usuarios. `estado` y `reports_count` son lo que sostiene eso.
--
-- Las cuatro tablas cuelgan de users con ON DELETE CASCADE, para que
-- DELETE /v1/users/me se lleve todo por delante sin código extra.

CREATE TYPE story_state AS ENUM ('PUBLICADA', 'EN_REVISION', 'RETIRADA');

-- El alias es la identidad pública, separada de la cuenta a propósito. Un
-- usuario sin fila aquí simplemente no ha entrado al muro todavía.
CREATE TABLE community_profiles (
    user_id    UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    alias      TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Unicidad sin distinguir mayúsculas: "Ana" y "ana" son el mismo alias para
-- quien lee, así que no pueden coexistir.
CREATE UNIQUE INDEX ON community_profiles (lower(alias));

CREATE TABLE community_stories (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    author_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- Congelados al publicar. Ver la decisión 2 de la cabecera.
    alias_snapshot TEXT        NOT NULL,
    -- NULL cuando la persona eligió no mostrar su racha: es distinto de 0 días.
    streak_days    INT,
    titulo         TEXT        NOT NULL,
    cuerpo         TEXT        NOT NULL,
    objetivo       TEXT        NOT NULL DEFAULT '',
    estado         story_state NOT NULL DEFAULT 'PUBLICADA',
    -- Desnormalizados: el muro los ordena y no puede pagar un COUNT por fila.
    -- Los mantienen las escrituras de useful y reports, en su transacción.
    useful_count   INT         NOT NULL DEFAULT 0,
    reports_count  INT         NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Un índice por cada orden del muro, y los tres terminan en id porque la
-- paginación es por cursor: el id desempata cuando racha, fecha o votos
-- coinciden, y sin ese desempate el cursor se saltaría filas.
CREATE INDEX ON community_stories (streak_days DESC NULLS LAST, id DESC) WHERE estado = 'PUBLICADA';
CREATE INDEX ON community_stories (created_at  DESC, id DESC)            WHERE estado = 'PUBLICADA';
CREATE INDEX ON community_stories (useful_count DESC, id DESC)           WHERE estado = 'PUBLICADA';
-- "Mis historias", y el borrado en cascada.
CREATE INDEX ON community_stories (author_id, created_at DESC);
-- La cola de moderación.
CREATE INDEX ON community_stories (estado, created_at DESC) WHERE estado = 'EN_REVISION';

-- "Me ayudó". La PK compuesta es la regla de negocio: un voto por persona e
-- historia, sin necesidad de comprobarlo antes de insertar.
CREATE TABLE community_story_useful (
    story_id   UUID        NOT NULL REFERENCES community_stories(id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (story_id, user_id)
);
CREATE INDEX ON community_story_useful (user_id);

CREATE TABLE community_reports (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    story_id    UUID        NOT NULL REFERENCES community_stories(id) ON DELETE CASCADE,
    reporter_id UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    motivo      TEXT        NOT NULL,
    detalle     TEXT        NOT NULL DEFAULT '',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Un reporte por persona: si no, tres toques del mismo dedo mandarían a
    -- revisión la historia de cualquiera.
    UNIQUE (story_id, reporter_id)
);

-- Bloqueo de autor. Se guarda contra el user_id porque tiene que sobrevivir a
-- que esa persona cambie de alias: bloquear a alguien y que reaparezca con otro
-- nombre sería peor que no tener bloqueo.
CREATE TABLE community_blocks (
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    blocked_id UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, blocked_id),
    CHECK (user_id <> blocked_id)
);
