#!/usr/bin/env bash
# LayanTv - pemasang satu-arahan untuk Mac & Linux.
# Muat turun Android TV Debloit ke ~/AndroidTvDebloit, kemudian jalankan setup penuh.
#
#   curl -fsSL https://raw.githubusercontent.com/Dynoz769/AndroidTvDebloit/main/web/setup.sh \
#     | bash -s -- --jenama sony --ip 192.168.1.20

set -e

REPO="Dynoz769/AndroidTvDebloit"
BRANCH="${LAYAN_BRANCH:-main}"
DEST="$HOME/AndroidTvDebloit"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "Memuat turun Android TV Debloit..."
curl -fsSL "https://github.com/$REPO/archive/refs/heads/$BRANCH.tar.gz" | tar -xz -C "$TMP"
SRC="$(find "$TMP" -mindepth 1 -maxdepth 1 -type d | head -n 1)"

mkdir -p "$DEST/senarai"
# Senarai sendiri dan folder backup/ pengguna dikekalkan.
[ -f "$DEST/senarai/saya.txt" ] && cp "$DEST/senarai/saya.txt" "$TMP/saya.txt"
cp "$SRC/debloat.sh" "$DEST/"
cp "$SRC"/senarai/*.txt "$DEST/senarai/"
[ -f "$TMP/saya.txt" ] && cp "$TMP/saya.txt" "$DEST/senarai/saya.txt"
chmod +x "$DEST/debloat.sh"
echo "Dipasang di $DEST"

# Arahan ini biasanya dijalankan melalui paip (curl | bash), jadi soalan
# dibaca terus dari papan kekunci.
if [ -t 0 ] || ! { : < /dev/tty; } 2>/dev/null; then
  bash "$DEST/debloat.sh" --auto "$@"
else
  bash "$DEST/debloat.sh" --auto "$@" < /dev/tty
fi
