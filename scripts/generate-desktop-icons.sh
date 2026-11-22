#!/usr/bin/env bash
# Regenerates the desktop installer icons from desktopApp/packaging/icon.svg. Needs macOS (iconutil), rsvg-convert and
# ImageMagick (brew install librsvg imagemagick). The results are committed: CI doesn't run this.
set -euo pipefail
cd "$(dirname "$0")/../desktopApp/packaging"
rsvg-convert -w 512 -h 512 icon.svg -o icon.png
set_dir=$(mktemp -d)/icon.iconset
mkdir -p "$set_dir"
for size in 16 32 128 256 512; do
  rsvg-convert -w "$size" -h "$size" icon.svg -o "$set_dir/icon_${size}x${size}.png"
  rsvg-convert -w "$((size * 2))" -h "$((size * 2))" icon.svg -o "$set_dir/icon_${size}x${size}@2x.png"
done
iconutil -c icns "$set_dir" -o icon.icns
magick icon.png -define icon:auto-resize=256,128,64,48,32,16 icon.ico
rm -rf "$(dirname "$set_dir")"
