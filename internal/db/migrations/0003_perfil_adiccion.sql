-- El perfil de recuperación: qué está dejando la persona.
--
-- Hasta ahora el backend no sabía de qué adicción se estaba recuperando nadie.
-- Funcionaba —el semáforo y la racha son iguales para todos— pero dejaba fuera
-- lo que la app necesita para hablarle a alguien de lo suyo: no es lo mismo
-- acompañar a quien deja de fumar que a quien deja de apostar.
--
-- Tres decisiones:
--
--   1. `adicciones` es un arreglo, no una columna sola. La comorbilidad es la
--      norma y no la excepción: alcohol y tabaco juntos son el caso más común
--      de todos. Obligar a elegir una sola falsearía el dato desde el registro.
--   2. `adiccion_principal` existe además del arreglo porque el tracker, la
--      racha y el ahorro son de UNA cosa. Es la que rige lo que se cuenta.
--   3. Es TEXT y no un enum de Postgres, al revés que risk_level. El catálogo de
--      adicciones va a crecer —y cada valor nuevo en un enum es una migración
--      con ALTER TYPE— mientras que el del semáforo lleva tres valores desde el
--      principio y no va a tener un cuarto. La validación vive en
--      internal/addiction, que es el único sitio por el que se entra.
--
-- Todo es opcional: alguien puede registrarse y completar esto después. La app
-- decide con `onboardingCompleto`, que se calcula al vuelo y no se guarda.

ALTER TABLE users
    ADD COLUMN adicciones         TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN adiccion_principal TEXT   NOT NULL DEFAULT '',
    -- Desde cuándo consume, no desde cuándo lleva sobrio: eso es
    -- sobriety_trackers.start_date y no hay que confundirlos.
    ADD COLUMN consumo_desde      DATE,
    ADD COLUMN en_tratamiento     BOOLEAN NOT NULL DEFAULT FALSE;

-- Para las estadísticas por tipo de adicción, que es la primera pregunta que va
-- a hacer cualquiera que mire estos datos en conjunto.
CREATE INDEX ON users (adiccion_principal) WHERE adiccion_principal <> '';
