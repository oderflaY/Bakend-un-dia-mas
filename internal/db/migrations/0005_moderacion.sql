-- La cola de moderación del muro y el rol que la atiende.
--
-- 0004 dejó el mecanismo automático montado —a los tres reportes la historia
-- pasa a EN_REVISION y desaparece del muro— pero nadie podía mirar esa cola.
-- Sin este paso, tres personas coordinadas esconden cualquier historia para
-- siempre y no hay forma de devolverla. Play pide moderación real, no solo un
-- botón de denunciar, así que esto es requisito para publicar.
--
-- Tres decisiones:
--
--   1. **El rol vive en el enum, no en una tabla aparte.** El rol viaja dentro
--      del access token, así que autorizar a un moderador no puede costar una
--      lectura a la base. Es el mismo razonamiento que ya justificaba
--      'therapist'.
--   2. **Los reportes se resuelven, no se borran.** Un reporte es la prueba de
--      por qué se retiró algo; borrarlo al aprobar dejaría la decisión sin
--      respaldo. Se marcan con `resuelto_en` y dejan de contar.
--   3. **La auditoría sobrevive al moderador.** `moderator_id` va con SET NULL
--      y no con CASCADE: si quien moderó se da de baja, la decisión se conserva
--      aunque se pierda quién la tomó. Es lo contrario que en el resto de
--      tablas de comunidad, y a propósito.

-- Postgres 12+ admite esto dentro de una transacción mientras el valor nuevo no
-- se USE en la misma transacción. Aquí solo se declara; el primer 'admin' lo
-- pone el operador a mano después (ver docs/desplegar.md).
ALTER TYPE user_role ADD VALUE IF NOT EXISTS 'admin';

-- ------------------------------------------------------- reportes resolubles

ALTER TABLE community_reports ADD COLUMN resuelto_en TIMESTAMPTZ;

-- La unicidad pasa a ser "un reporte VIVO por persona e historia". Con la
-- restricción anterior, quien ya había reportado una historia que luego se
-- aprobó no podía volver a reportarla nunca, ni aunque el autor la editara
-- después para empeorarla. Resolver un reporte devuelve ese derecho.
ALTER TABLE community_reports DROP CONSTRAINT community_reports_story_id_reporter_id_key;
CREATE UNIQUE INDEX ON community_reports (story_id, reporter_id) WHERE resuelto_en IS NULL;

-- La cola lee los reportes vivos de cada historia.
CREATE INDEX ON community_reports (story_id) WHERE resuelto_en IS NULL;

-- ------------------------------------------------------------- auditoría

CREATE TYPE moderation_action AS ENUM ('APROBADA', 'RETIRADA');

CREATE TABLE moderation_actions (
    id           UUID              PRIMARY KEY DEFAULT gen_random_uuid(),
    story_id     UUID              NOT NULL REFERENCES community_stories(id) ON DELETE CASCADE,
    moderator_id UUID              REFERENCES users(id) ON DELETE SET NULL,
    accion       moderation_action NOT NULL,
    -- Por qué. Obligatorio en la práctica (el handler lo exige): una retirada
    -- sin motivo es indistinguible de un error.
    motivo       TEXT              NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ       NOT NULL DEFAULT now()
);

CREATE INDEX ON moderation_actions (story_id, created_at DESC);
CREATE INDEX ON moderation_actions (created_at DESC);
