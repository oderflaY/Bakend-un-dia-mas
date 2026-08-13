# Desplegar en un VPS con Docker

Todo el backend son tres contenedores: la base, el API y el proxy que pone el
HTTPS. La receta completa está en `docker-compose.yml`.

Probado sobre **Ubuntu 24.04** en un VPS de 4 GB.

---

## Antes de empezar

- Una clave SSH instalada en el servidor (`ssh-copy-id`). Con contraseña también
  funciona, pero el paso 2 la desactiva.
- Un dominio con un registro **A** apuntando a la IP. Sin él no hay certificado;
  para probar sin dominio, pon `DOMINIO=:80` y entra por HTTP a la IP.

## 1 · Tu usuario y cerrar SSH

Como `root`:

```bash
adduser --disabled-password --gecos "" oderflay
usermod -aG sudo oderflay
passwd oderflay

mkdir -p /home/oderflay/.ssh
cp /root/.ssh/authorized_keys /home/oderflay/.ssh/
chown -R oderflay:oderflay /home/oderflay/.ssh
chmod 700 /home/oderflay/.ssh && chmod 600 /home/oderflay/.ssh/authorized_keys
```

**Comprueba en otra terminal que entras como `oderflay` antes de seguir.**

```bash
cat > /etc/ssh/sshd_config.d/00-hardening.conf <<'EOF'
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
EOF
sshd -t && systemctl restart ssh
```

El nombre empieza por `00` a propósito: OpenSSH se queda con el primer valor que
encuentra, y las imágenes de nube traen un `50-cloud-init.conf` que reactiva las
contraseñas.

## 2 · Sistema

```bash
apt update && apt upgrade -y
apt install -y ufw fail2ban unattended-upgrades git

ufw allow OpenSSH && ufw allow 80/tcp && ufw allow 443/tcp && ufw --force enable

fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab

printf 'APT::Periodic::Update-Package-Lists "1";\nAPT::Periodic::Unattended-Upgrade "1";\n' \
  > /etc/apt/apt.conf.d/20auto-upgrades
```

**Deja el servidor en UTC.** El código maneja las zonas explícitamente —`stats`
corta el día en `America/Mexico_City`, `reminder_settings` guarda la de cada
usuario— y cambiar la del sistema solo introduce confusión.

## 3 · Docker

```bash
curl -fsSL https://get.docker.com | sh
usermod -aG docker oderflay
```

Cierra la sesión y vuelve a entrar **como `oderflay`** para que el grupo tome
efecto. El resto se hace sin `sudo`.

## 4 · El proyecto

```bash
git clone https://github.com/oderflaY/Bakend-un-dia-mas.git ~/undiamas
cd ~/undiamas

cp .env.produccion.example .env
echo "DB_PASSWORD=$(openssl rand -base64 24 | tr -d '/+=')" >> .env
echo "JWT_SECRET=$(openssl rand -base64 48)"                >> .env
nano .env    # pon tu DOMINIO y borra las dos líneas vacías de arriba
```

Los secretos se generan en el servidor y no salen de ahí. `.env` está en
`.gitignore` y en `.dockerignore`, así que no acaba ni en el repositorio ni
dentro de la imagen.

## 5 · Levantarlo

```bash
docker compose up -d --build
docker compose ps
docker compose logs -f api
```

En los logs del API deberías ver las cuatro migraciones aplicándose y después
`escuchando`. Las migraciones corren solas al arrancar; no hay paso aparte.

La primera compilación tarda unos minutos. Las siguientes reutilizan la capa de
dependencias y bajan a segundos.

## 6 · Comprobar

```bash
curl -s https://api.tudominio.mx/healthz
```

Si Caddy todavía no tiene certificado, mira `docker compose logs caddy`: casi
siempre es que el DNS aún no resuelve a esta IP.

Prueba de punta a punta:

```bash
curl -sX POST https://api.tudominio.mx/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"prueba@correo.mx","password":"12345678","displayName":"Prueba"}'
```

**No corras `make seed` aquí.** Las contraseñas de esas cuentas están en el
repositorio; son para tu máquina.

## 6 bis · El moderador del muro

El rol `admin` existe pero no se puede pedir al registrarse: se concede a mano,
y eso es a propósito. Es el único rol que ve identificadores de autores, así que
darlo tiene que costar entrar al servidor.

Regístrate primero desde la app con tu correo, y luego:

```bash
docker compose exec -T db psql -U undiamas -d undiamas \
  -c "UPDATE users SET role = 'admin' WHERE email = 'tu@correo.mx';"
```

Cierra sesión y vuelve a entrar: el rol viaja dentro del access token y el que
tienes ahora mismo todavía dice `patient`.

Con eso ya puedes ver la cola:

```bash
curl -s https://api.tudominio.mx/v1/admin/moderation/stories \
  -H "Authorization: Bearer $TOKEN"
```

Sin nadie con este rol, las historias denunciadas tres veces desaparecen del
muro y no hay forma de devolverlas.

## 7 · Respaldos

```bash
mkdir -p ~/respaldos
cat > ~/respaldo.sh <<'EOF'
#!/bin/bash
set -euo pipefail
cd ~/undiamas
docker compose exec -T db pg_dump -U undiamas -Fc undiamas \
  > ~/respaldos/undiamas-$(date +%F).dump
find ~/respaldos -name 'undiamas-*.dump' -mtime +14 -delete
EOF
chmod +x ~/respaldo.sh
~/respaldo.sh && ls -lh ~/respaldos

(crontab -l 2>/dev/null; echo "30 4 * * * $HOME/respaldo.sh") | crontab -
```

**Esto todavía no es un respaldo de verdad**: está en el mismo disco que la base.
Falta bajarte los `.dump` a otra parte con `scp` de vez en cuando. Si el servidor
muere, el diario de cada persona muere con él y eso no se recupera de ningún
lado.

---

## Operación diaria

```bash
docker compose logs -f api          # ver qué pasa
docker compose up -d --build        # desplegar cambios (tras git pull)
docker compose restart api          # reiniciar solo el API
docker compose down                 # parar todo (los datos se conservan)
```

**Nunca `docker compose down -v`.** La `-v` borra los volúmenes, y ahí vive la
base de datos entera.

## Si algo falla

| Síntoma | Dónde mirar |
|---|---|
| `502` desde el navegador | `docker compose logs api` — suele ser la cadena de `DATABASE_URL` |
| Caddy no saca certificado | `docker compose logs caddy` — casi siempre DNS que no resuelve |
| El API reinicia en bucle | `docker compose logs api` — falta `JWT_SECRET` o mide menos de 32 bytes |
| Los avisos SSE llegan a bloques | `flush_interval -1` en el `Caddyfile` |
| `/v1/auth/password/*` da 503 | falta `SMTP_HOST` en el `.env` |
| No llega el correo de recuperación | `docker compose logs api` — con Gmail suele ser que falta la contraseña **de aplicación** |
| `429` al probar el login varias veces | es el límite de 10 por minuto y por IP; espera un minuto |

Cuando el servidor responda, sigue con
[checklist-produccion.md](checklist-produccion.md): es lo que falta para poder
publicar en Play.

La imagen del API no tiene shell, así que `docker compose exec api sh` no
funciona: es a propósito, y por eso el diagnóstico va por los logs. Si necesitas
entrar, cambia la última etapa del `Dockerfile` a `alpine` temporalmente.
