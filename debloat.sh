#!/usr/bin/env bash
# Android TV Debloit - cuci bloatware & tukar home screen Android TV / Google TV
# melalui ADB dari komputer (Mac / Linux). Untuk Windows, guna debloat.bat.
#
# Semua perubahan direkod dalam folder backup/ dan boleh di-undo.
#
#   bash debloat.sh                                 menu penuh
#   bash debloat.sh --auto [--jenama sony] [--ip 192.168.1.20]
#                                                   setup penuh sekali jalan

DIR="$(cd "$(dirname "$0")" && pwd)"
LIST_DIR="$DIR/senarai"
BACKUP_DIR="$DIR/backup"
IP_FILE="$DIR/.tv-ip"

LAUNCHER_PKG="io.github.dynoz769.debloithome"
LAUNCHER_ACTIVITY="$LAUNCHER_PKG/.HomeActivity"
LAUNCHER_APK="$DIR/DebloitHome.apk"
LAUNCHER_URL="https://github.com/Dynoz769/AndroidTvDebloit/releases/latest/download/DebloitHome.apk"

ADB=""
SERIAL=""
BRAND=""
MODEL=""
ANDROID_VER=""
DEVICE="tv"   # tv / tablet

AUTO=0
FORCE_BRAND=""
PRESET_IP=""

# ---------------------------------------------------------------- utiliti

say()  { printf '%s\n' "$*"; }
ok()   { printf '  [OK]     %s\n' "$*"; }
fail() { printf '  [GAGAL]  %s\n' "$*"; }
info() { printf '  [-]      %s\n' "$*"; }

ask_yes() {
  local reply
  read -r -p "$1 (y/n): " reply || return 1
  case "$reply" in [yY]*) return 0 ;; *) return 1 ;; esac
}

pause() { read -r -p "Tekan Enter untuk teruskan..." _ || true; }

lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

# Baca fail senarai, keluarkan "pakej|penerangan" untuk setiap baris sah.
read_list() {
  local line pkg desc
  [ -f "$1" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    pkg="$(printf '%s' "${line%%#*}" | tr -d '[:space:]')"
    [ -z "$pkg" ] && continue
    desc=""
    case "$line" in *"#"*) desc="$(printf '%s' "${line#*#}" | sed 's/^[[:space:]]*//')" ;; esac
    printf '%s|%s\n' "$pkg" "$desc"
  done < "$1"
}

# App teras yang tak boleh disentuh walaupun ada dalam senarai.
is_protected() {
  case "$1" in
    android|com.android.systemui|com.android.settings|com.android.tv.settings|\
    com.android.vending|com.google.android.gms|com.google.android.gsf|\
    com.android.shell|com.android.bluetooth|com.android.phone|\
    com.android.packageinstaller|com.google.android.packageinstaller|\
    com.android.providers.*|com.android.inputmethod.*|com.google.android.inputmethod.*|\
    com.android.networkstack*|com.google.android.networkstack*|\
    com.google.android.tv.remote.service|com.google.android.tvsetup|\
    com.sec.android.app.launcher|com.miui.home|com.android.launcher|com.android.launcher3|\
    com.google.android.apps.nexuslauncher|com.huawei.android.launcher|com.oppo.launcher|\
    com.samsung.android.honeyboard|com.google.android.apps.tablet.launcher|\
    "$LAUNCHER_PKG")
      return 0 ;;
  esac
  return 1
}

# ---------------------------------------------------------------- ADB

find_adb() {
  [ -n "$ADB" ] && return 0
  if command -v adb >/dev/null 2>&1; then ADB="$(command -v adb)"; return 0; fi
  if [ -x "$DIR/platform-tools/adb" ]; then ADB="$DIR/platform-tools/adb"; return 0; fi

  local os
  case "$(uname -s)" in
    Darwin) os=darwin ;;
    Linux)  os=linux ;;
    *) fail "Sistem tak dikenali. Sila pasang ADB sendiri."; return 1 ;;
  esac
  say "ADB belum ada. Muat turun platform-tools rasmi dari Google (~10 MB)..."
  local zip="$DIR/platform-tools.zip"
  if ! curl -fL -o "$zip" "https://dl.google.com/android/repository/platform-tools-latest-$os.zip"; then
    rm -f "$zip"; fail "Muat turun ADB gagal. Semak internet."; return 1
  fi
  if ! unzip -q -o "$zip" -d "$DIR"; then
    rm -f "$zip"; fail "Gagal buka zip ADB."; return 1
  fi
  rm -f "$zip"
  ADB="$DIR/platform-tools/adb"
  ok "ADB siap dipasang dalam folder platform-tools/"
}

tv() { "$ADB" -s "$SERIAL" shell "$@" 2>&1 | tr -d '\r'; }

prop() { tv getprop "$1"; }

pkgs_installed() { tv pm list packages | sed 's/^package://'; }
pkgs_enabled()   { tv pm list packages -e | sed 's/^package://'; }
pkgs_disabled()  { tv pm list packages -d | sed 's/^package://'; }
# Pakej yang dibuang untuk user ini tapi masih ada dalam sistem.
pkgs_removed() {
  local all inst
  all="$(tv pm list packages -u | sed 's/^package://' | sort)"
  inst="$(pkgs_installed | sort)"
  comm -23 <(printf '%s\n' "$all") <(printf '%s\n' "$inst") | sed '/^$/d'
}

in_list() { printf '%s\n' "$2" | grep -qxF "$1"; }

is_google_tv() { in_list com.google.android.apps.tv.launcherx "$(pkgs_installed)"; }

load_tv_info() {
  local maker brand
  maker="$(prop ro.product.manufacturer)"
  brand="$(prop ro.product.brand)"
  BRAND="$maker"
  [ "$(lower "$brand")" != "$(lower "$maker")" ] && BRAND="$maker $brand"
  MODEL="$(prop ro.product.model)"
  ANDROID_VER="$(prop ro.build.version.release)"
  DEVICE="tablet"
  case "$(tv pm list features)" in *android.software.leanback*) DEVICE="tv" ;; esac
}

device_kind() {
  if [ "$DEVICE" = "tablet" ]; then printf 'Tablet'
  elif is_google_tv; then printf 'Google TV'
  else printf 'Android TV'; fi
}

# Peranti yang disambung dengan kabel USB (siri tanpa ':').
usb_device() {
  "$ADB" devices 2>/dev/null | tr -d '\r' | awk 'NR > 1 && $2 == "device" && $1 !~ /:/ { print $1; exit }'
}

adb_connect() { # ip:port
  local out
  out="$("$ADB" connect "$1" 2>&1 | tr -d '\r')"
  case "$out" in *"connected to"*) return 0 ;; esac
  fail "$out"
  return 1
}

# Untuk TV yang guna "Wireless debugging" (Android 11+ / Google TV baru).
pair_tv() {
  local addr code out
  say "Di TV: Developer options > Wireless debugging > Pair device with pairing code"
  read -r -p "IP:port untuk pairing (contoh 192.168.1.20:37123): " addr || return 1
  read -r -p "Kod pairing 6 digit: " code || return 1
  out="$("$ADB" pair "$(printf '%s' "$addr" | tr -d '[:space:]')" "$(printf '%s' "$code" | tr -d '[:space:]')" 2>&1 | tr -d '\r')"
  case "$out" in *"Successfully paired"*) ok "Pairing berjaya"; return 0 ;; esac
  fail "$out"
  return 1
}

connect_tv() {
  find_adb || return 1
  local last="" ip state i
  [ -f "$IP_FILE" ] && last="$(tr -d '[:space:]' < "$IP_FILE")"
  local usb
  usb="$(usb_device)"
  if [ -z "$PRESET_IP" ] && [ -n "$usb" ]; then
    if [ "$AUTO" -eq 1 ] || ask_yes "Jumpa peranti melalui kabel USB ($usb). Guna peranti ini?"; then
      SERIAL="$usb"
      load_tv_info
      ok "Bersambung (USB): $BRAND / $MODEL (Android $ANDROID_VER)"
      info "Jenis: $(device_kind)"
      return 0
    fi
  fi
  say ""
  say "Sambung tablet dengan kabel USB, atau guna Wi-Fi."
  say "Cari IP di: Settings > Network & Internet (atau Wi-Fi) > rangkaian anda > IP address"
  if [ -n "$PRESET_IP" ]; then
    ip="$PRESET_IP"
    PRESET_IP=""
    say "Alamat IP TV: $ip"
  elif [ -n "$last" ]; then
    read -r -p "Alamat IP TV [$last]: " ip || return 1
    ip="${ip:-$last}"
  else
    read -r -p "Alamat IP TV (contoh 192.168.1.20): " ip || return 1
  fi
  ip="$(printf '%s' "$ip" | tr -d '[:space:]')"
  [ -z "$ip" ] && { fail "Alamat IP kosong."; return 1; }
  case "$ip" in *:*) ;; *) ip="$ip:5555" ;; esac
  printf '%s\n' "$ip" > "$IP_FILE"

  if ! adb_connect "$ip"; then
    say "  Pastikan: TV & komputer guna Wi-Fi sama, dan 'USB debugging' sudah"
    say "  dihidupkan dalam Developer options TV."
    ask_yes "TV anda hanya ada 'Wireless debugging' (perlu kod pairing)? Cuba pairing" || return 1
    pair_tv || return 1
    read -r -p "IP:port di skrin utama Wireless debugging (contoh 192.168.1.20:41235): " ip || return 1
    ip="$(printf '%s' "$ip" | tr -d '[:space:]')"
    printf '%s\n' "$ip" > "$IP_FILE"
    adb_connect "$ip" || return 1
  fi

  for i in $(seq 1 45); do
    state="$("$ADB" -s "$ip" get-state 2>&1 | tr -d '\r')"
    case "$state" in
      device)
        SERIAL="$ip"
        load_tv_info
        ok "Bersambung: $BRAND / $MODEL (Android $ANDROID_VER)"
        info "Jenis: $(device_kind)"
        return 0 ;;
      *unauthorized*)
        [ "$i" = 1 ] && say "  >> Lihat skrin TV: tick 'Always allow' dan tekan 'Allow' / 'Benarkan'." ;;
    esac
    sleep 2
  done
  fail "TV tak benarkan sambungan. Cuba lagi dan tekan 'Allow' di TV."
  return 1
}

require_tv() {
  if [ -n "$SERIAL" ] && [ "$("$ADB" -s "$SERIAL" get-state 2>/dev/null | tr -d '\r')" = "device" ]; then
    return 0
  fi
  SERIAL=""
  connect_tv
}

# ---------------------------------------------------------------- matikan / pulihkan

new_backup() { # jenis -> cetak laluan fail backup
  mkdir -p "$BACKUP_DIR"
  local f
  f="$BACKUP_DIR/$(date +%Y%m%d-%H%M%S)_$1.txt"
  {
    printf '# Backup Android TV Debloit\n'
    printf '# Jenis: %s\n' "$1"
    printf '# TV: %s / %s (%s)\n' "$BRAND" "$MODEL" "$SERIAL"
    printf '# Tarikh: %s\n' "$(date '+%Y-%m-%d %H:%M')"
  } > "$f"
  printf '%s' "$f"
}

disable_pkg() { # pakej fail_backup
  local out
  out="$(tv pm disable-user --user 0 "$1")"
  case "$out" in
    *"new state: disabled"*)
      printf 'disable %s\n' "$1" >> "$2"; ok "$1 (dimatikan)"; return 0 ;;
  esac
  # Sesetengah app sistem tak boleh dimatikan; buang untuk user ini sahaja
  # (fail asal kekal dalam sistem, boleh dipulihkan).
  out="$(tv pm uninstall -k --user 0 "$1")"
  case "$out" in
    *Success*)
      printf 'uninstall %s\n' "$1" >> "$2"; ok "$1 (disembunyikan dari sistem)"; return 0 ;;
  esac
  fail "$1: $(printf '%s' "$out" | tail -n 1)"
  return 1
}

restore_pkg() { # tindakan pakej [nilai]
  local out
  case "$1" in
    setting)
      if [ "${3:-null}" = "null" ]; then tv settings delete global "$2" >/dev/null; else tv settings put global "$2" "$3" >/dev/null; fi
      ok "Tetapan $2 dipulihkan (${3:-null})"
      return 0 ;;
    disable)
      out="$(tv pm enable "$2")"
      case "$out" in *"new state: enabled"*|*"new state: default"*) ok "$2 (dihidupkan)"; return 0 ;; esac ;;
    uninstall)
      out="$(tv cmd package install-existing "$2")"
      case "$out" in *installed*|*Success*) ok "$2 (dipulihkan)"; return 0 ;; esac
      out="$(tv pm install-existing "$2")"
      case "$out" in *installed*|*Success*) ok "$2 (dipulihkan)"; return 0 ;; esac ;;
  esac
  fail "$2: $(printf '%s' "$out" | tail -n 1)"
  return 1
}

restore_backup() { # fail_backup ; pulihkan dari bawah ke atas
  local f="$1" action pkg value errors=0
  while read -r action pkg value; do
    case "$action" in disable|uninstall|setting) restore_pkg "$action" "$pkg" "$value" || errors=$((errors + 1)) ;; esac
  done < <(awk '{ a[NR] = $0 } END { for (i = NR; i > 0; i--) print a[i] }' "$f")
  if [ "$errors" -eq 0 ]; then
    mkdir -p "$BACKUP_DIR/dipulihkan"
    mv "$f" "$BACKUP_DIR/dipulihkan/"
  else
    say "  $errors app gagal dipulihkan. Fail backup dikekalkan: $f"
  fi
}

# ---------------------------------------------------------------- menu

do_debloat() { # [auto]
  require_tv || return
  local auto="${1:-}" prefix="" extra="YouTube Kids, YouTube Music, Ambient mode, Meet" f key brandlc enabled
  [ "$DEVICE" = "tablet" ] && { prefix="tablet-"; extra="YouTube Music, Meet, OneDrive, Office"; }
  local files=("$LIST_DIR/${prefix}umum.txt")
  brandlc="$(lower "$BRAND $FORCE_BRAND")"
  for f in "$LIST_DIR/${prefix}"jenama-*.txt; do
    [ -e "$f" ] || continue
    key="$(basename "$f" .txt)"; key="${key#"${prefix}"jenama-}"
    case "$brandlc" in *"$key"*) files+=("$f"); info "Guna senarai jenama: $key" ;; esac
  done
  files+=("$LIST_DIR/saya.txt")
  if [ -z "$auto" ] && ask_yes "Termasuk senarai TAMBAHAN ($extra)?"; then
    files+=("$LIST_DIR/${prefix}tambahan.txt")
  fi

  enabled="$(pkgs_enabled)"
  local pkgs=() descs=() seen=" " pkg desc
  while IFS='|' read -r pkg desc; do
    case "$seen" in *" $pkg "*) continue ;; esac
    seen="$seen$pkg "
    if is_protected "$pkg"; then info "$pkg dilindungi, dilangkau"; continue; fi
    in_list "$pkg" "$enabled" || continue
    pkgs+=("$pkg"); descs+=("$desc")
  done < <(for f in "${files[@]}"; do read_list "$f"; done)

  if [ "${#pkgs[@]}" -eq 0 ]; then
    say ""; say "Tiada bloatware dalam senarai yang masih aktif di TV ini."
    say "Guna menu 'Imbas senarai app' untuk cari lagi."
    return
  fi

  say ""; say "App yang akan dimatikan (${#pkgs[@]}):"
  local i
  for i in "${!pkgs[@]}"; do printf '  %2d. %-48s %s\n' "$((i + 1))" "${pkgs[$i]}" "${descs[$i]}"; done
  say ""
  local reply="y" chosen=()
  [ -z "$auto" ] && { read -r -p "Teruskan? [y] ya semua / [p] pilih satu-satu / [n] batal: " reply || return; }
  case "$reply" in
    [yY]*) chosen=("${pkgs[@]}") ;;
    [pP]*)
      for i in "${!pkgs[@]}"; do
        ask_yes "  Matikan ${pkgs[$i]} (${descs[$i]})?" && chosen+=("${pkgs[$i]}")
      done ;;
    *) say "Dibatalkan."; return ;;
  esac
  [ "${#chosen[@]}" -eq 0 ] && { say "Tiada app dipilih."; return; }

  local bfile
  bfile="$(new_backup debloat)"
  for pkg in "${chosen[@]}"; do disable_pkg "$pkg" "$bfile"; done
  say ""; say "Siap. Backup disimpan: ${bfile#$DIR/}"
  [ -z "$auto" ] && say "Nak undo? Pilih menu 'Undo'."
}

do_undo() {
  require_tv || return
  local files=() f i reply
  while IFS= read -r f; do files+=("$f"); done < <(ls -1t "$BACKUP_DIR"/*.txt 2>/dev/null)
  if [ "${#files[@]}" -eq 0 ]; then say "Tiada backup untuk dipulihkan."; return; fi
  say ""; say "Backup yang ada (terbaru di atas):"
  for i in "${!files[@]}"; do
    printf '  %d. %s  [%s, %s perubahan]\n' "$((i + 1))" "$(basename "${files[$i]}")" \
      "$(grep -m1 '^# TV:' "${files[$i]}" | sed 's/^# TV: //')" \
      "$(grep -cE '^(disable|uninstall|setting) ' "${files[$i]}")"
  done
  read -r -p "Pilih nombor backup untuk dipulihkan [1], atau 'a' untuk semua: " reply || return
  reply="${reply:-1}"
  if [ "$reply" = "a" ] || [ "$reply" = "A" ]; then
    for f in "${files[@]}"; do say "> $(basename "$f")"; restore_backup "$f"; done
  elif [ "$reply" -ge 1 ] 2>/dev/null && [ "$reply" -le "${#files[@]}" ]; then
    restore_backup "${files[$((reply - 1))]}"
  else
    say "Pilihan tak sah."; return
  fi
  say "Siap."
}

do_install_launcher() { # [auto]
  require_tv || return
  local auto="${1:-}" apk="$LAUNCHER_APK" out
  if [ "$DEVICE" = "tablet" ] && [ -z "$auto" ]; then
    ask_yes "Pasang home screen LayanTv pada tablet ini?" || return
  fi
  if [ ! -f "$apk" ]; then
    say "Muat turun DebloitHome.apk dari GitHub..."
    if ! curl -fL -o "$apk" "$LAUNCHER_URL"; then
      rm -f "$apk"
      fail "Muat turun gagal. Letak fail DebloitHome.apk dalam folder ini secara manual."
      return
    fi
  fi
  say "Memasang home screen baru ke TV..."
  out="$("$ADB" -s "$SERIAL" install -r "$apk" 2>&1 | tr -d '\r')"
  case "$out" in
    *Success*) ok "LayanTv dipasang" ;;
    *) fail "$(printf '%s' "$out" | tail -n 1)"; return ;;
  esac

  local enabled stock=() descs=() pkg desc i
  enabled="$(pkgs_enabled)"
  while IFS='|' read -r pkg desc; do
    in_list "$pkg" "$enabled" && { stock+=("$pkg"); descs+=("$desc"); }
  done < <(read_list "$LIST_DIR/launcher-asal.txt")

  if [ "${#stock[@]}" -gt 0 ]; then
    say ""; say "Home screen asal yang dijumpai:"
    for i in "${!stock[@]}"; do printf '   - %-44s %s\n' "${stock[$i]}" "${descs[$i]}"; done
    say "Supaya butang Home pada remote terus buka home baru, home asal perlu dimatikan."
    say "(Boleh dipulihkan bila-bila masa dengan menu 'Buang home screen baru'.)"
    if [ -n "$auto" ] || ask_yes "Matikan home screen asal? (disyorkan)"; then
      local bfile
      bfile="$(new_backup launcher)"
      for pkg in "${stock[@]}"; do disable_pkg "$pkg" "$bfile"; done
    fi
  fi

  out="$(tv cmd package set-home-activity "$LAUNCHER_ACTIVITY")"
  case "$out" in *Success*) ok "LayanTv ditetapkan sebagai home" ;; *) info "Pilih LayanTv jika TV tanya home mana nak guna." ;; esac
  tv am start -n "$LAUNCHER_ACTIVITY" --ez intro true >/dev/null
  if [ "$DEVICE" = "tablet" ]; then
    info "Home asal tablet tidak dimatikan. Tukar balik bila-bila masa di Settings > Apps > Default apps > Home."
  fi
  say ""; say "Siap! Tengok skrin - intro LayanTv sedang dimainkan."
}

ANIM_KEYS="window_animation_scale transition_animation_scale animator_duration_scale"

# Animasi 0.5x buat peranti terasa lebih laju. Nilai asal disimpan untuk undo.
do_tweaks() { # [auto]
  require_tv || return
  local auto="${1:-}" key old bfile
  if [ -z "$auto" ]; then
    say "Lajukan animasi sistem kepada 0.5x (peranti terasa lebih pantas)."
    ask_yes "Teruskan?" || return
  fi
  bfile="$(new_backup tweak)"
  for key in $ANIM_KEYS; do
    old="$(tv settings get global "$key" | tr -d '[:space:]')"
    printf 'setting %s %s\n' "$key" "${old:-null}" >> "$bfile"
    tv settings put global "$key" 0.5 >/dev/null
    ok "$key: ${old:-null} -> 0.5"
  done
}

do_intro() {
  require_tv || return
  if ! in_list "$LAUNCHER_PKG" "$(pkgs_installed)"; then
    fail "Home screen baru belum dipasang. Pilih menu 3 dulu."
    return
  fi
  tv am start -n "$LAUNCHER_ACTIVITY" --ez intro true >/dev/null
  ok "Intro LayanTv dimainkan di TV."
}

do_remove_launcher() {
  require_tv || return
  local f found=0
  while IFS= read -r f; do
    if grep -q '^# Jenis: launcher' "$f"; then
      found=1; say "> Pulihkan home asal dari $(basename "$f")"; restore_backup "$f"
    fi
  done < <(ls -1t "$BACKUP_DIR"/*.txt 2>/dev/null)
  [ "$found" -eq 0 ] && info "Tiada home asal yang perlu dipulihkan."

  if in_list "$LAUNCHER_PKG" "$(pkgs_installed)"; then
    case "$(tv pm uninstall "$LAUNCHER_PKG")" in
      *Success*) ok "LayanTv dibuang" ;;
      *) fail "Gagal buang LayanTv" ;;
    esac
  fi
  tv input keyevent KEYCODE_HOME >/dev/null
  say "Siap. TV kembali guna home screen asal."
}

do_scan() {
  require_tv || return
  local name out gtv="tidak"
  is_google_tv && gtv="ya"
  name="$(printf '%s' "$MODEL" | tr -c 'A-Za-z0-9._-' '_')"
  out="$DIR/imbas-${name}-$(date +%Y%m%d-%H%M).txt"
  {
    printf '# Android TV Debloit - hasil imbas\n'
    printf '# Jenama: %s | Model: %s | Android: %s | Google TV: %s\n' "$BRAND" "$MODEL" "$ANDROID_VER" "$gtv"
    printf '\n## App sistem yang aktif\n';      tv pm list packages -s -e | sed 's/^package://' | sort
    printf '\n## App pihak ketiga (dipasang)\n'; tv pm list packages -3 | sed 's/^package://' | sort
    printf '\n## App yang sudah dimatikan\n';    pkgs_disabled | sort
    printf '\n## App yang disembunyikan (uninstall --user 0)\n'; pkgs_removed
  } > "$out"
  ok "Disimpan: ${out#$DIR/}"
  say ""
  say "Langkah seterusnya: hantar fail ini kepada AI (contoh Claude) dan minta:"
  say "  \"Senaraikan bloatware dalam TV ini yang selamat dimatikan,"
  say "   dalam format: nama.pakej   # penerangan\""
  say "Kemudian salin jawapan ke dalam senarai/saya.txt dan jalankan menu 'Cuci bloatware'."
}

do_emergency() {
  require_tv || return
  say ""
  say "Pulih kecemasan: hidupkan semula SEMUA app yang ada dalam senarai/ dan backup/,"
  say "walaupun fail backup sudah hilang atau rosak."
  ask_yes "Teruskan?" || return
  local disabled removed pkg desc seen=" " count=0
  disabled="$(pkgs_disabled)"
  removed="$(pkgs_removed)"
  while IFS='|' read -r pkg desc; do
    case "$seen" in *" $pkg "*) continue ;; esac
    seen="$seen$pkg "
    if in_list "$pkg" "$disabled"; then restore_pkg disable "$pkg"; count=$((count + 1))
    elif in_list "$pkg" "$removed"; then restore_pkg uninstall "$pkg"; count=$((count + 1))
    fi
  done < <(
    for f in "$LIST_DIR"/*.txt; do read_list "$f"; done
    cat "$BACKUP_DIR"/*.txt "$BACKUP_DIR"/dipulihkan/*.txt 2>/dev/null \
      | awk '$1 == "disable" || $1 == "uninstall" { print $2 "|" }'
  )
  say "Siap. $count app diperiksa & dipulihkan."
}

# Setup penuh sekali jalan, untuk arahan dari laman web.
do_auto() {
  say ""
  say "================== Setup LayanTv ==================="
  say " Skrip ini akan:"
  say "   1. Sambung ke TV atau tablet anda"
  say "   2. Matikan bloatware & app iklan"
  say "   3. Lajukan animasi sistem"
  say "   4. Pasang home screen LayanTv + intro"
  say " Semua boleh di-undo dari menu penuh."
  say "===================================================="
  ask_yes "Mula sekarang?" || { main_menu; return; }
  until connect_tv; do
    ask_yes "Cuba sambung lagi?" || { say "Dibatalkan."; return; }
  done
  do_debloat auto
  do_tweaks auto
  do_install_launcher auto
  say ""
  say "===================================================="
  say " Setup selesai! Selamat menonton dengan LayanTv."
  say " Nak undo atau ubah? Jalankan semula:"
  say "   bash \"$DIR/debloat.sh\""
  say "===================================================="
}

main_menu() {
  local choice
  while true; do
    say ""
    say "================ Android TV Debloit ================"
    if [ -n "$SERIAL" ]; then say " TV: $BRAND / $MODEL ($SERIAL)"; else say " TV: belum bersambung"; fi
    say "----------------------------------------------------"
    say "  1) Sambung ke TV"
    say "  2) Cuci bloatware"
    say "  3) Pasang home screen baru (gaya Apple TV)"
    say "  4) Undo - pulihkan dari backup"
    say "  5) Buang home screen baru & kembali ke asal"
    say "  6) Imbas senarai app (untuk tanya AI)"
    say "  7) Pulih kecemasan"
    say "  8) Main intro LayanTv"
    say "  9) Lajukan animasi sistem"
    say "  0) Keluar"
    read -r -p "Pilihan: " choice || exit 0
    case "$choice" in
      1) SERIAL=""; connect_tv ;;
      2) do_debloat ;;
      3) do_install_launcher ;;
      4) do_undo ;;
      5) do_remove_launcher ;;
      6) do_scan ;;
      7) do_emergency ;;
      8) do_intro ;;
      9) do_tweaks ;;
      0|q|Q) exit 0 ;;
      *) say "Pilihan tak sah." ;;
    esac
  done
}

while [ $# -gt 0 ]; do
  case "$1" in
    --auto) AUTO=1 ;;
    --jenama) FORCE_BRAND="${2:-}"; shift ;;
    --ip) PRESET_IP="${2:-}"; shift ;;
  esac
  shift
done

if [ "$AUTO" -eq 1 ]; then do_auto; else main_menu; fi
