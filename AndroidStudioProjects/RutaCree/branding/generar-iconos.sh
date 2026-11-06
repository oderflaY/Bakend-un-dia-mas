#!/usr/bin/env bash
# Regenera todos los íconos (Android, iOS, Play Store) desde branding/*.svg. Uso: ./branding/generar-iconos.sh
cd "$(dirname "$0")/.."
resvg -w 1024 -h 1024 branding/icon.svg branding/icon-1024.png
set -e
B=branding; R=androidApp/src/main/res
declare -A L=( [mdpi]=48 [hdpi]=72 [xhdpi]=96 [xxhdpi]=144 [xxxhdpi]=192 )
declare -A A=( [mdpi]=108 [hdpi]=162 [xhdpi]=216 [xxhdpi]=324 [xxxhdpi]=432 )
resvg -w 1024 -h 1024 $B/fg.svg $B/fg-1024.png
resvg -w 1024 -h 1024 $B/bg.svg $B/bg-1024.png
resvg -w 1024 -h 1024 $B/mono.svg $B/mono-1024.png
magick $B/icon-1024.png \( -size 1024x1024 xc:black -fill white -draw "roundrectangle 0,0 1023,1023 180,180" \) -alpha off -compose CopyOpacity -composite -compose Over -resize 88% -gravity center -background none -extent 1024x1024 PNG32:$B/legacy-1024.png
magick $B/icon-1024.png \( -size 1024x1024 xc:black -fill white -draw "circle 512,512 512,0" \) -alpha off -compose CopyOpacity -composite -compose Over -resize 92% -gravity center -background none -extent 1024x1024 PNG32:$B/round-1024.png
for d in "${!L[@]}"; do
  mkdir -p $R/mipmap-$d
  magick $B/legacy-1024.png -resize ${L[$d]}x${L[$d]} PNG32:$R/mipmap-$d/ic_launcher.png
  magick $B/round-1024.png -resize ${L[$d]}x${L[$d]} PNG32:$R/mipmap-$d/ic_launcher_round.png
  magick $B/fg-1024.png -resize ${A[$d]}x${A[$d]} $R/mipmap-$d/ic_launcher_foreground.png
  magick $B/bg-1024.png -resize ${A[$d]}x${A[$d]} $R/mipmap-$d/ic_launcher_background.png
  magick $B/mono-1024.png -resize ${A[$d]}x${A[$d]} $R/mipmap-$d/ic_launcher_monochrome.png
done
for f in ic_launcher ic_launcher_round; do cat > $R/mipmap-anydpi-v26/$f.xml <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />
</adaptive-icon>
EOF
done
rm -f $R/drawable/ic_launcher_background.xml $R/drawable-v24/ic_launcher_foreground.xml; rmdir $R/drawable $R/drawable-v24 2>/dev/null || true
magick $B/icon-1024.png -background "#2F5DA8" -alpha remove -alpha off -type TrueColor PNG24:iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png
mkdir -p $B/tiendas && magick $B/icon-1024.png -resize 512x512 -alpha off PNG24:$B/tiendas/play-icono-512.png
