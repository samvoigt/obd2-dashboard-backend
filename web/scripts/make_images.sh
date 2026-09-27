#!/bin/sh
# The site's images from the team's logo (M8.1): the whole logo for the landing
# page, and the centre bear for the browser tab.
#
#   web/scripts/make_images.sh [path/to/bnb-logo.pdf]
#
# The logo is the tablet app's docs/branding/bnb-logo.pdf, **only read**:
# nothing outside this repo is written. Needs macOS's `sips` (to render the
# PDF) and ImageMagick's `magick`, as the app's own icon script does. The
# output is committed; the build never runs this.
set -eu

web=$(cd "$(dirname "$0")/.." && pwd)
pdf=${1:-"$web/../../obd2-dashboard/docs/branding/bnb-logo.pdf"}
out="$web/src/assets"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The PDF renders on a transparent ground: its linework needs no cutting out.
# sips renders into Display P3, tagged with its profile; converted to sRGB here,
# or stripping the profile turns the logo's #FF0099 into a muted #EA3396.
sips -s format png -Z 2160 "$pdf" --out "$work/p3.png" >/dev/null
magick "$work/p3.png" -profile "/System/Library/ColorSync/Profiles/sRGB Profile.icc" "$work/logo.png"

# The logo, trimmed of its empty margin, 720 px wide (shown at 360, sharp on a
# high-density screen). WebP, not PNG: a fifth of the size for the same look.
magick "$work/logo.png" -trim +repage -resize 720x -strip -quality 88 "$out/logo.webp"

# The centre bear, as the app's launcher icon cuts it: in the logo's own 1080-
# unit square, its face at (577, 345), a circle of 160 around it.
scale=2
d=$((320 * scale))
x=$(((577 - 160) * scale))
y=$(((345 - 160) * scale))
half=$((d / 2))
magick "$work/logo.png" -crop "${d}x${d}+${x}+${y}" +repage \
    \( -size "${d}x${d}" xc:none -fill white -draw "circle $half,$half $half,0" \) \
    -compose DstIn -composite -resize 64x64 -strip "PNG32:$out/bear.png"

echo "Wrote $out/logo.webp and $out/bear.png"
