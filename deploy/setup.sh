#!/usr/bin/env bash
# PicFrame one-time setup on Raspberry Pi OS Lite (Bookworm), run from the repo checkout:
#   sudo ./deploy/setup.sh America/Los_Angeles
# Safe to re-run. DRY_RUN=1 prints what it would do without changing anything.
set -euo pipefail

TZ_NAME="${1:-}"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_USER="pi"
FRAME_HOME="/home/${APP_USER}/frame"
BOOT_CONFIG="/boot/firmware/config.txt"
[ -f "$BOOT_CONFIG" ] || BOOT_CONFIG="/boot/config.txt"   # older Pi OS layout

run() { if [ "${DRY_RUN:-0}" = 1 ]; then echo "+ $*"; else "$@"; fi; }
say() { echo "==> $*"; }

if [ "${DRY_RUN:-0}" != 1 ] && [ "$(id -u)" -ne 0 ]; then
  echo "Run with sudo: sudo $0 <Region/City timezone>" >&2; exit 1
fi
if [ -z "$TZ_NAME" ] || [ ! -e "/usr/share/zoneinfo/$TZ_NAME" ]; then
  echo "Give a valid timezone, e.g.: sudo $0 America/Los_Angeles" >&2; exit 1
fi

say "Packages (runtime deps from apt only)"
run apt-get update
run apt-get install -y python3-pygame python3-pil python3-tomli-w cec-utils i2c-tools

say "RTC (DS3231 on I2C) in $BOOT_CONFIG"
for line in "dtparam=i2c_arm=on" "dtoverlay=i2c-rtc,ds3231"; do
  if grep -qxF "$line" "$BOOT_CONFIG" 2>/dev/null; then echo "   already set: $line"
  else run sh -c "echo '$line' >> '$BOOT_CONFIG'"; fi
done

say "Use the RTC instead of fake-hwclock"
run apt-get purge -y fake-hwclock
# Debian's udev helper skips setting the clock from the RTC under systemd; let it run at boot.
HWCLOCK_SET="/lib/udev/hwclock-set"
if [ -f "$HWCLOCK_SET" ] && grep -qE '^if \[ -e /run/systemd/system \]' "$HWCLOCK_SET"; then
  run sed -i '/^if \[ -e \/run\/systemd\/system \]/,/^fi/ s/^/#/' "$HWCLOCK_SET"
else
  echo "   $HWCLOCK_SET already allows the RTC (or is absent)"
fi

say "Network time, written back to the RTC"
run systemctl enable --now systemd-timesyncd
run timedatectl set-ntp true
# The kernel may not copy NTP time to the DS3231 by itself; do it hourly, only once NTP has synced,
# so a wrong system clock is never written to the RTC.
RTC_SYNC="/etc/cron.hourly/picframe-rtc-sync"
run sh -c "printf '%s\n' '#!/bin/sh' 'if [ \"\$(timedatectl show -p NTPSynchronized --value)\" = yes ]; then hwclock -w; fi' > '$RTC_SYNC'"
run chmod 755 "$RTC_SYNC"

say "Timezone: $TZ_NAME"
run timedatectl set-timezone "$TZ_NAME"

say "Folders and config in $FRAME_HOME"
run install -d -o "$APP_USER" -g "$APP_USER" "$FRAME_HOME" "$FRAME_HOME/photos" "$FRAME_HOME/cache"
if [ -e "$FRAME_HOME/config.toml" ]; then echo "   keeping existing config.toml"
else run install -o "$APP_USER" -g "$APP_USER" -m 644 "$REPO/config.example.toml" "$FRAME_HOME/config.toml"; fi

say "Service (runs from $REPO)"
run sh -c "sed 's#^WorkingDirectory=.*#WorkingDirectory=$REPO#' '$REPO/deploy/frame.service' > /etc/systemd/system/frame.service"
run systemctl daemon-reload
run systemctl enable frame.service

say "Done. Edit $FRAME_HOME/config.toml (lat/lon, clock), then: sudo reboot"
