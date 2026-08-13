#!/bin/bash
# Genera los PNG del icono a partir de branding/logo.svg.
#
# Solo hacen falta para Android 7 y anteriores, que no entienden el icono
# adaptativo, y para la ficha de Play. De Android 8 en adelante manda
# mipmap-anydpi-v26/ic_launcher.xml, que es vectorial y no pasa por aquí.
#
# Se ejecuta a mano cuando cambie el logo, no en cada compilación: los PNG
# están versionados justamente para que compilar la app no dependa de tener
# rsvg-convert e ImageMagick instalados.
#
#   ./branding/generar-iconos.sh
#
# Necesita: rsvg-convert (librsvg) e ImageMagick 7 (magick).
set -euo pipefail

cd "$(dirname "$0")"
[ -f logo.svg ] || { echo "no encuentro logo.svg" >&2; exit 1; }

for cmd in rsvg-convert magick; do
    command -v "$cmd" >/dev/null || { echo "falta $cmd" >&2; exit 1; }
done

# Los cinco tamaños del lanzador, uno por densidad de pantalla.
declare -A densidades=(
    [mdpi]=48 [hdpi]=72 [xhdpi]=96 [xxhdpi]=144 [xxxhdpi]=192
)

for dpi in "${!densidades[@]}"; do
    px=${densidades[$dpi]}
    dir="android/mipmap-$dpi"
    mkdir -p "$dir"

    # Se renderiza al cuádruple y se reduce después. Rasterizar directo a 48px
    # deja los rayos dentados; reducir desde 192 los deja limpios.
    grande=$((px * 4))
    rsvg-convert -w "$grande" -h "$grande" logo.svg -o /tmp/undiamas-icono.png

    # Cuadrado con esquinas redondeadas: el radio al 22% del lado es lo que
    # usa Android para los iconos heredados.
    radio=$((grande * 22 / 100))
    magick /tmp/undiamas-icono.png \
        \( -size "${grande}x${grande}" xc:none \
           -draw "roundrectangle 0,0,$((grande-1)),$((grande-1)),$radio,$radio" \) \
        -alpha set -compose DstIn -composite \
        -resize "${px}x${px}" "$dir/ic_launcher.png"

    # Y la variante redonda, para los lanzadores que la piden.
    magick /tmp/undiamas-icono.png \
        \( -size "${grande}x${grande}" xc:none \
           -draw "circle $((grande/2)),$((grande/2)) $((grande/2)),0" \) \
        -alpha set -compose DstIn -composite \
        -resize "${px}x${px}" "$dir/ic_launcher_round.png"

    echo "  $dir  ${px}x${px}"
done

rm -f /tmp/undiamas-icono.png

# El de la ficha de Play: 512x512, PNG de 32 bits, SIN transparencia y sin
# esquinas redondeadas. Google aplica la máscara por su cuenta; si se la das
# redondeada, la redondea otra vez y el borde queda mordido.
rsvg-convert -w 512 -h 512 logo.svg -o /tmp/undiamas-play.png
magick /tmp/undiamas-play.png -background "#0D1526" -alpha remove -alpha off \
    ic_launcher-playstore.png
rm -f /tmp/undiamas-play.png
echo "  ic_launcher-playstore.png  512x512"

echo "Listo."
