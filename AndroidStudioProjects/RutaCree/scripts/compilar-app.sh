#!/usr/bin/env bash
# Compila la app apuntando a tu servidor y deja el APK en dist/.
#
#   ./scripts/compilar-app.sh http://203.0.113.10:8080            # pruebas con IP (APK de depuración)
#   ./scripts/compilar-app.sh https://api.rutacree.mx pk.xxxxx    # producción (APK firmado)
#
# El segundo argumento (opcional) es el token público de Mapbox; sin él se ve el mapa esquemático.
set -euo pipefail
cd "$(dirname "$0")/.."
url="${1:-}"
token="${2:-}"
if [[ ! "$url" =~ ^https?:// ]]; then
	echo "uso: $0 <http(s)://servidor[:puerto]> [token-mapbox-pk]" >&2
	exit 1
fi
url="${url%/}"

set_prop() { # clave valor en local.properties (ignorado por git)
	touch local.properties
	if grep -q "^$1=" local.properties; then
		sed -i "s|^$1=.*|$1=$2|" local.properties
	else
		echo "$1=$2" >>local.properties
	fi
}
set_prop API_BASE_URL "$url"
[ -n "$token" ] && set_prop MAPBOX_ACCESS_TOKEN "$token"

# Android release solo permite HTTPS; una URL http:// (pruebas por IP) va en el APK de depuración.
if [[ "$url" == https://* && -f keystore.properties ]]; then
	variant=release
	task=:androidApp:assembleRelease
	apk=androidApp/build/outputs/apk/release/androidApp-release.apk
else
	variant=debug
	task=:androidApp:assembleDebug
	apk=androidApp/build/outputs/apk/debug/androidApp-debug.apk
	[[ "$url" == https://* ]] && echo "Aviso: no hay keystore.properties (scripts/crear-llave.sh); se genera APK de depuración."
fi

./gradlew -q "$task"
mkdir -p dist
out="dist/RutaCree-$variant-$(date +%Y%m%d-%H%M).apk"
cp "$apk" "$out"
grep -q "^MAPBOX_ACCESS_TOKEN=pk\." local.properties || echo "Aviso: sin token de Mapbox la app muestra el mapa esquemático."
echo "APK listo: $out  (servidor: $url)"
echo "Instalar por cable:  adb install -r $out   — o pásalo al teléfono y ábrelo."
