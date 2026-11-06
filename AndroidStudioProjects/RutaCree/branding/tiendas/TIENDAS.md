# Publicar RutaCree en Google Play y App Store

Archivos en esta carpeta:

| Archivo | Para qué |
|---|---|
| `play-icono-512.png` | Ícono de la ficha de Play (512×512) |
| `play-destacada-1024x500.png` | Imagen destacada de Play (obligatoria) |
| `privacidad.html` | Aviso de privacidad: debe estar publicado en una URL pública (ambas tiendas lo piden) |

El ícono se regenera desde `branding/*.svg` con `./branding/generar-iconos.sh`.

## Ya resuelto en el código

- Ícono propio en Android (adaptativo + monocromo para Android 13+, redondo y clásico), iOS (1024 sin transparencia) y notificaciones (silueta blanca).
- Android: `targetSdk 37`, firmado con la llave de `keystore.properties`, librerías nativas alineadas a 16 KB, solo HTTPS, sin respaldo en la nube del token.
- `scripts/compilar-app.sh https://2.25.68.28` genera el APK **y el `.aab`** que pide Play, con `versionCode` = fecha y hora (siempre mayor que el anterior). Versión visible: `VERSION=1.1 ./scripts/compilar-app.sh …`.
- iOS: bundle ID `com.eter.rutacree`, solo iPhone (no pide capturas de iPad), `PrivacyInfo.xcprivacy` (manifiesto de privacidad obligatorio), `ITSAppUsesNonExemptEncryption = NO` (no pregunta por cifrado en cada subida).
- Eliminar cuenta dentro de la app (Perfil → Eliminar mi cuenta), obligatorio en ambas tiendas.

## Google Play (cuenta de desarrollador: 25 USD, pago único)

1. `./scripts/compilar-app.sh https://2.25.68.28` → sube `dist/RutaCree-<fecha>.aab`.
2. **Guarda la llave** `*.jks` + `keystore.properties` en un lugar seguro: sin ella no puedes publicar actualizaciones (activa también *Play App Signing*, que viene por defecto).
3. Ficha: nombre «RutaCree», ícono y destacada de esta carpeta, mínimo **2 capturas de teléfono** (sácalas del celular).
4. **Política de privacidad**: URL de `privacidad.html` publicada.
5. **Seguridad de los datos**: ubicación precisa (solo choferes, funcionalidad de la app), nombre y correo (cuenta, opcional), salud/accesibilidad (perfil, opcional), contenido del usuario (reportes). Cifrado en tránsito: sí. Se puede pedir el borrado: sí. Sin compartir con terceros.
6. **Eliminación de cuenta**: pide una URL web → la sección «Borrar tu cuenta» de `privacidad.html`.
7. **Permisos especiales** (Contenido de la app → Declaraciones):
   - *Servicio en primer plano de ubicación* (`FOREGROUND_SERVICE_LOCATION`): explica «Los choferes transmiten la posición del camión y los pasajeros reciben el aviso de bajada con la pantalla apagada» y sube un video corto (YouTube no listado) mostrando la notificación «RutaCree» mientras hay un viaje o un recorrido abierto.
   - *Mostrar sobre otras apps* (`SYSTEM_ALERT_WINDOW`): solo se usa para que la alerta de rampa le aparezca al chofer.
8. **Acceso a la app**: da al revisor una cuenta de chofer de prueba (correo y contraseña) creada en el panel.
9. Clasificación de contenido: cuestionario (todo «no») → apta para todos. Público objetivo: 18+ (evita requisitos de apps para niños).
10. Cuentas **personales** nuevas deben pasar una **prueba cerrada con 12 testers durante 14 días** antes de producción. Con una cuenta de **organización** (DIF, con D-U-N-S) no aplica.

## App Store (Apple Developer Program: 99 USD al año)

Se compila **solo en una Mac** con Xcode (no se puede desde Linux).

1. En la Mac: clona el repo, crea `iosApp/Configuration/Secrets.xcconfig` con `TEAM_ID = <tu Team ID>`.
2. Abre `iosApp/iosApp.xcodeproj`, elige *Any iOS Device* → **Product → Archive** → *Distribute App* → *App Store Connect*. (La fase de Gradle compila el módulo Kotlin sola; necesita JDK 17+ en la Mac.)
3. Antes de cada nueva subida, sube `CURRENT_PROJECT_VERSION` en `Config.xcconfig` (1, 2, 3…).
4. En App Store Connect crea la app con bundle ID `com.eter.rutacree`.
5. Capturas obligatorias de **iPhone 6.9"** (1320×2868), sácalas del simulador iPhone 17 Pro Max.
6. **URL de privacidad** = `privacidad.html` publicada. **Privacidad de la app**: lo mismo que en Play (ubicación precisa, nombre, correo, salud, contenido del usuario; todo «funcionalidad de la app», nada de rastreo).
7. **Notas para el revisor** (muy importante para que no la rechacen):
   - Cuenta de chofer de prueba (correo y contraseña).
   - «La app usa ubicación en segundo plano (modo `location`) solo mientras el pasajero tiene un viaje activo (aviso de bajada) o el chofer tiene un recorrido abierto (posición del camión). Se muestra el indicador azul y se detiene al terminar.»
   - «Es el servicio de transporte accesible Ruta Azul del DIF Estatal Durango.»
8. **Apps de gobierno**: Apple y Google suelen exigir que la app de una institución pública la publique **la propia institución** (o que presentes un oficio que te autorice). Con una cuenta personal y el nombre del DIF en la ficha es motivo común de rechazo; lo ideal es que el DIF tenga su cuenta de desarrollador (organización) y te agregue como desarrollador.

## Pendiente fuera del código

- Publicar `privacidad.html` (puede servirse desde el VPS en `https://2.25.68.28/privacidad`).
- Capturas de pantalla de ambas tiendas.
- Cuentas de desarrollador (idealmente a nombre del DIF).
- Cuenta de chofer de prueba para los revisores.
