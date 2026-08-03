-- Cierra las dos piezas que el esquema inicial dejó a medias.
--
--   #8: no había recordatorios de check-in. Firebase los habría resuelto con una
--       función onSchedule; aquí basta una tabla con la hora local de cada
--       usuario y un ticker en el proceso. Sin cron externo y sin Cloud Scheduler.
--   La vista del terapeuta ya tenía tablas (`sessions`, `clinical_notes`,
--   `therapist_patients`) pero ningún índice: toda lectura suya habría sido un
--   seq scan, que es exactamente el hallazgo #15 repetido en el otro extremo.

-- La hora se guarda partida (hora + minuto + zona) y no como TIMESTAMPTZ porque
-- lo que el usuario elige es "las 9 de la noche", no un instante: tiene que
-- seguir siendo las 9 después de un cambio de horario de verano.
CREATE TABLE reminder_settings (
    user_id      UUID     PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    enabled      BOOLEAN  NOT NULL DEFAULT TRUE,
    hour_local   SMALLINT NOT NULL DEFAULT 21 CHECK (hour_local   BETWEEN 0 AND 23),
    minute_local SMALLINT NOT NULL DEFAULT 0  CHECK (minute_local BETWEEN 0 AND 59),
    timezone     TEXT     NOT NULL DEFAULT 'America/Mexico_City',
    -- Día local del último aviso: es lo que impide repetirlo cada vez que el
    -- ticker pasa dentro de la misma ventana.
    last_sent_on DATE
);

-- El planificador barre esta tabla cada minuto; sin este índice el barrido
-- crecería con el total de usuarios, no con los que tienen aviso activo.
CREATE INDEX ON reminder_settings (enabled) WHERE enabled;

ALTER TABLE sessions ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Un terapeuta lista sus pacientes; un paciente consulta quién le atiende.
-- La PK (therapist_id, patient_id) solo sirve al primer sentido.
CREATE INDEX ON therapist_patients (patient_id);

CREATE INDEX ON sessions       (therapist_id, scheduled_at DESC);
CREATE INDEX ON sessions       (patient_id,   scheduled_at DESC);
CREATE INDEX ON clinical_notes (patient_id,   created_at   DESC);
