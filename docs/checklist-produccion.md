# Checklist para publicar en Play Store

Lo que tiene que estar listo antes de subir la app, separado en tres bloques: lo
que ya está hecho, lo que falta en el backend y lo que no es código.

Google no revisa tu servidor. Pero varias de sus reglas obligan a que el
servidor sepa hacer ciertas cosas, y esas son las que aparecen aquí.

---

## 1 · Lo que el backend ya hace

Marcado porque son, justamente, los motivos de rechazo más comunes.

- [x] **Borrar la cuenta desde la app.** `DELETE /v1/users/me` se lleva en
      cascada diario, ánimo, check-ins, semáforo, alertas, recaídas, sesiones y
      todo el rastro de comunidad. Google lo exige desde 2024.
- [x] **Denunciar contenido.** `POST /v1/community/stories/{id}/reports`.
- [x] **Bloquear a otra persona.** `POST /v1/community/stories/{id}/block-author`,
      guardado contra el `user_id` para que sobreviva a un cambio de alias.
- [x] **Retirada automática.** Tres denuncias de personas distintas sacan la
      historia del muro sin esperar a nadie.
- [x] **Moderación humana.** `GET /v1/admin/moderation/stories` y las rutas de
      aprobar y retirar. Play pide que exista alguien que revise, no solo un
      botón de denunciar.
- [x] **Recuperar la contraseña.** Código de seis dígitos por correo, con
      caducidad, intentos contados y un solo uso.
- [x] **Fuerza bruta contenida.** 10 intentos por minuto y por IP en todo
      `/v1/auth/*`.
- [x] **Todo por HTTPS.** Caddy saca y renueva el certificado solo.
- [x] **Datos sensibles fuera del alcance.** La base no se publica a internet;
      solo la alcanza el API por la red interna de Docker.

## 2 · Lo que falta en el servidor

- [ ] **Un usuario con rol `admin`.** El rol existe pero nadie lo tiene. Sin
      esto la cola de moderación está construida y vacía de gente. Se concede a
      mano, entrando al servidor:

      ```bash
      docker compose exec -T db psql -U undiamas -d undiamas \
        -c "UPDATE users SET role = 'admin' WHERE email = 'tu@correo.mx';"
      ```

      Quien reciba ese rol tiene que volver a entrar en la app: el rol viaja
      dentro del token y el suyo todavía dice `patient`.

- [ ] **SMTP configurado.** Sin `SMTP_HOST`, `/v1/auth/password/*` responde 503.
      Ver el bloque de correo en `.env.produccion.example`.

- [ ] **Respaldos fuera del servidor.** El cron del punto 7 de
      [desplegar.md](desplegar.md) deja los `.dump` en el mismo disco que la
      base. Falta copiarlos a otra máquina. Mientras eso no exista, un disco
      muerto se lleva el diario de todo el mundo.

- [ ] **Aviso si el servidor se cae.** Hoy te enteras porque alguien te escribe.
      Con un monitor externo apuntando a `/healthz` cada cinco minutos basta;
      hay servicios gratuitos que mandan correo.

- [ ] **Revisar la cola.** No es código: es alguien mirándola. Una app con muro
      público y sin nadie revisando denuncias es un problema de Play y, antes
      que eso, de las personas que publican ahí.

## 3 · Lo que no es código

Esto se prepara en paralelo y no depende de programar.

- [ ] **Política de privacidad, en una URL pública.** Tiene que decir qué se
      recoge (diario, ánimo, nivel de riesgo, contactos de emergencia), que el
      chat viaja a Google (Gemini), cuánto se guarda y cómo se borra.

      Di explícitamente que **el diario se analiza en el propio servidor y no se
      manda a ningún tercero**; solo el chat sale a Gemini. Es cierto —está en
      `internal/analysis`— y es un punto a favor, no un detalle técnico.

- [ ] **Página web de borrado de cuenta, en otra URL pública.** El `DELETE` de
      la app no basta: Google exige poder pedir el borrado **sin instalar la
      app**. Una página con un formulario o un correo de contacto sirve.

- [ ] **Formulario de Data Safety.** Declaras datos de salud y bienestar.
      Categoría sensible: revisión más lenta y más estricta.

- [ ] **Declaración de app de salud + contenido sensible.** Una app que detecta
      riesgo suicida entra en la categoría más vigilada de Play. Hace falta:

      - Líneas de crisis reales y visibles en la app. En México, la **Línea de
        la Vida: 800 911 2000**, 24 horas.
      - Decir en la ficha, sin ambigüedad, que la app **no sustituye atención
        profesional**.

- [ ] **Icono y gráficos de la ficha.** El icono está en
      [../branding/](../branding/): `ic_launcher-playstore.png` es el de 512×512
      que pide Play. Falta el gráfico de cabecera (1024×500) y las capturas.

- [ ] **Cuenta de prueba para el revisor.** Correo y contraseña de una cuenta ya
      creada, en las notas de la revisión. Sin eso, el revisor se queda en la
      pantalla de registro y rechaza la app por "no se puede probar".

      **No uses las cuentas de `make seed`**: sus contraseñas están en el
      repositorio. Crea una a mano para esto.

---

## Instalar el icono en la app

Los archivos de [../branding/android/](../branding/android/) van al módulo de
Android de la app (no a este repositorio), respetando los nombres de carpeta:

```
composeApp/src/androidMain/res/
├── drawable/
│   ├── ic_launcher_background.xml
│   ├── ic_launcher_foreground.xml
│   └── ic_launcher_monochrome.xml
├── mipmap-anydpi-v26/
│   └── ic_launcher.xml
├── mipmap-mdpi/ … mipmap-xxxhdpi/
│   ├── ic_launcher.png
│   └── ic_launcher_round.png
```

Y en `AndroidManifest.xml`:

```xml
<application
    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round"
    ...>
```

Borra el `ic_launcher` que generó la plantilla, incluidos los
`mipmap-anydpi-v26` viejos: si se quedan, ganan ellos y sigues viendo el
robot verde.

Para regenerar los PNG tras cambiar el logo: `./branding/generar-iconos.sh`.
