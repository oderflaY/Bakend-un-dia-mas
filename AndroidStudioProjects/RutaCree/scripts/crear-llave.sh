#!/usr/bin/env bash
# Crea la llave con la que se firma la app para publicarla (una sola vez).
# Queda en ~/.rutacree/ (fuera del repositorio) y keystore.properties apunta a ella.
# RESPALDA ~/.rutacree: sin esta llave no puedes publicar actualizaciones de la app.
set -euo pipefail
cd "$(dirname "$0")/.."
dir="$HOME/.rutacree"
jks="$dir/rutacree-release.jks"
if [ -f keystore.properties ] || [ -f "$jks" ]; then
	echo "Ya existe una llave ($jks o keystore.properties). No se sobrescribe." >&2
	exit 1
fi
command -v keytool >/dev/null || { echo "Falta keytool (viene con el JDK de Android Studio)." >&2; exit 1; }
mkdir -p "$dir" && chmod 700 "$dir"
pass="$(head -c 24 /dev/urandom | base64 | tr -d '/+=\n' | head -c 32)"
keytool -genkeypair -v -keystore "$jks" -alias rutacree -keyalg RSA -keysize 4096 -validity 10000 \
	-storepass "$pass" -keypass "$pass" -dname "CN=RutaCree, O=RutaCree, C=MX" >/dev/null 2>&1
umask 077
cat > keystore.properties <<PROPS
storeFile=$jks
storePassword=$pass
keyAlias=rutacree
keyPassword=$pass
PROPS
cp keystore.properties "$dir/keystore.properties"
echo "Llave creada: $jks"
echo "Contraseña guardada en keystore.properties y en $dir/keystore.properties."
echo "Respalda la carpeta $dir en un lugar seguro."
