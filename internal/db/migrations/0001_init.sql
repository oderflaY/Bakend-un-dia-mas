-- Esquema inicial. Traduce las colecciones de Firestore a tablas relacionales.
--
-- No existe fcm_token: la entrega de avisos ya no pasa por Firebase Cloud
-- Messaging, sino por el canal SSE de internal/notify. Lo que garantiza que un
-- aviso no se pierda es que se escribe en `alerts` antes de emitirse.
--
-- Dos decisiones que rompen con el modelo anterior:
--   1. Un solo vocabulario para el semáforo: green | yellow | red (enum de Postgres).
--      Se traduce a VERDE/AMARILLO/ROJO solo al serializar hacia la app.
--   2. El aislamiento entre usuarios ya no depende de un campo del documento:
--      es una FK con ON DELETE CASCADE y un WHERE user_id = $1 en cada query.

CREATE TYPE risk_level AS ENUM ('green', 'yellow', 'red');
CREATE TYPE user_role  AS ENUM ('patient', 'therapist');
CREATE TYPE ai_role    AS ENUM ('user', 'assistant');

CREATE TABLE users (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email              TEXT        NOT NULL UNIQUE,
    password_hash      TEXT        NOT NULL,
    display_name       TEXT        NOT NULL DEFAULT '',
    role               user_role   NOT NULL DEFAULT 'patient',
    status             TEXT        NOT NULL DEFAULT 'active',
    email_verified     BOOLEAN     NOT NULL DEFAULT FALSE,
    por_que_personal   TEXT        NOT NULL DEFAULT '',
    record_racha_secs  BIGINT      NOT NULL DEFAULT 0,
    last_login_at      TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- El primer contacto (position = 0) es el de confianza que lee el protocolo de emergencia.
CREATE TABLE emergency_contacts (
    id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id  UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    position INT  NOT NULL,
    nombre   TEXT NOT NULL,
    telefono TEXT NOT NULL,
    rol      TEXT NOT NULL DEFAULT '',
    UNIQUE (user_id, position)
);

-- Relación paciente ↔ terapeuta. Sustituye a esTerapeuta() de firestore.rules:
-- un terapeuta ya no lee a "cualquier paciente", solo a los suyos.
CREATE TABLE therapist_patients (
    therapist_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    patient_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (therapist_id, patient_id)
);

CREATE TABLE refresh_tokens (
    token_hash TEXT PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON refresh_tokens (user_id);

CREATE TABLE sobriety_trackers (
    user_id             UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    start_date          TIMESTAMPTZ,
    daily_savings_rate  NUMERIC(12,2) NOT NULL DEFAULT 0,
    currency            TEXT          NOT NULL DEFAULT 'MXN',
    traffic_light       risk_level    NOT NULL DEFAULT 'green',
    last_status_update  TIMESTAMPTZ
);

CREATE TABLE check_ins (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    risk_level    risk_level NOT NULL DEFAULT 'green',
    craving_level INT        NOT NULL DEFAULT 0 CHECK (craving_level BETWEEN 0 AND 10),
    mood          TEXT       NOT NULL DEFAULT '',
    triggers      TEXT[]     NOT NULL DEFAULT '{}',
    note          TEXT       NOT NULL DEFAULT '',
    answers       JSONB      NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ai_messages (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role              ai_role    NOT NULL,
    content           TEXT       NOT NULL,
    risk_level_context risk_level,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE alerts (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    risk_level risk_level NOT NULL DEFAULT 'green',
    message    TEXT       NOT NULL,
    handled    BOOLEAN    NOT NULL DEFAULT FALSE,   -- ahora sí se puede marcar (#12)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE journal_entries (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content    TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE mood_logs (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    mood       TEXT NOT NULL DEFAULT 'NEUTRAL',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE traffic_light_logs (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status            risk_level NOT NULL,
    reason            TEXT       NOT NULL DEFAULT '',
    trigger_level     INT        NOT NULL DEFAULT 1 CHECK (trigger_level BETWEEN 1 AND 5),
    suggested_actions TEXT[]     NOT NULL DEFAULT '{}',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- #6: las recaídas por fin se persisten.
CREATE TABLE relapse_events (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    note                  TEXT   NOT NULL DEFAULT '',
    triggers              TEXT[] NOT NULL DEFAULT '{}',
    previous_streak_secs  BIGINT NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE sessions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    therapist_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status       TEXT NOT NULL DEFAULT 'scheduled',
    scheduled_at TIMESTAMPTZ,
    notes        TEXT NOT NULL DEFAULT ''
);

CREATE TABLE clinical_notes (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    therapist_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content      TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- #15: los seis índices compuestos que en Firestore había que declarar a mano.
CREATE INDEX ON check_ins          (user_id, created_at DESC);
CREATE INDEX ON ai_messages        (user_id, created_at DESC);
CREATE INDEX ON alerts             (user_id, created_at DESC);
CREATE INDEX ON journal_entries    (user_id, created_at DESC);
CREATE INDEX ON mood_logs          (user_id, created_at DESC);
CREATE INDEX ON traffic_light_logs (user_id, created_at DESC);
CREATE INDEX ON relapse_events     (user_id, created_at DESC);
