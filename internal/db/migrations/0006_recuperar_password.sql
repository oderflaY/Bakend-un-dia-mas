-- Recuperar la contraseña.
--
-- Hasta aquí, quien olvidaba su contraseña perdía el diario entero: no había
-- forma de volver a entrar. En una app publicada eso no es una molestia, es
-- soporte manual todos los días y gente que pierde meses de registro.
--
-- Se hace con un código de seis dígitos al correo y no con un enlace, porque un
-- enlace necesita una página web que reciba el token, y aquí no hay frontend
-- donde ponerla. El código lo teclea la persona en la propia app.
--
-- Un código corto solo es seguro si se defiende de la fuerza bruta, y eso son
-- tres cosas a la vez, todas en esta tabla:
--
--   1. **Caduca pronto** (`expires_at`, 15 minutos).
--   2. **Se agota** (`intentos`): cinco fallos y el código muere, así que no
--      hay millón de combinaciones que recorrer.
--   3. **Se usa una vez** (`usado_en`).
--
-- Y el código no se guarda: se guarda su hash bcrypt. Seis dígitos en claro en
-- una base filtrada son seis dígitos regalados; con bcrypt, probarlos cuesta.

CREATE TABLE password_resets (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash  TEXT        NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    intentos   INT         NOT NULL DEFAULT 0,
    usado_en   TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- La verificación busca el código vivo más reciente de esa persona.
CREATE INDEX ON password_resets (user_id, created_at DESC);
