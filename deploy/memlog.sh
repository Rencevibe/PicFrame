#!/usr/bin/env bash
# Soak-test memory log: every 10 min append "date,pid,rss_kb" of the frame service to
# ~/frame/memlog.csv (pid changes after a restart). Survives reboots via cron:
#   crontab -e   →   @reboot /home/pi/PicFrame/deploy/memlog.sh
# Report:  deploy/memlog.sh --report   (RSS growth after each run's first hour; pass < 10 %)
set -euo pipefail
LOG="${MEMLOG:-$HOME/frame/memlog.csv}"
INTERVAL="${MEMLOG_INTERVAL:-600}"

if [ "${1:-}" = "--report" ]; then
  # Per process run: baseline = first sample ≥ 1 h after it started; growth = max later / baseline
  awk -F, 'NR > 1 && $2 != "" && $3 != "" {
      cmd = "date -d \"" $1 "\" +%s"; cmd | getline t; close(cmd)
      if (!($2 in start)) { start[$2] = t; order[++n] = $2 }
      if (t - start[$2] >= 3600) {
        if (!($2 in base)) base[$2] = $3
        if ($3 > peak[$2]) peak[$2] = $3
      }
      last[$2] = t
    }
    END {
      if (n == 0) { print "no samples yet"; exit 1 }
      fail = 0
      for (i = 1; i <= n; i++) {
        p = order[i]; hours = (last[p] - start[p]) / 3600
        if (!(p in base)) { printf "pid %s: %.1f h, too short for a baseline\n", p, hours; continue }
        g = (peak[p] - base[p]) * 100 / base[p]
        printf "pid %s: %.1f h, RSS after 1 h %d kB, peak %d kB, growth %.1f %% %s\n", p, hours, base[p], peak[p], g, (g < 10 ? "PASS" : "FAIL")
        if (g >= 10) fail = 1
      }
      exit fail
    }' "$LOG"
  exit $?
fi

mkdir -p "$(dirname "$LOG")"
[ -s "$LOG" ] || echo "date,pid,rss_kb" > "$LOG"
while true; do
  pid="$(systemctl show -p MainPID --value frame.service 2>/dev/null || true)"
  rss=""
  if [ -n "$pid" ] && [ "$pid" != 0 ] && [ -r "/proc/$pid/status" ]; then
    rss="$(awk '/^VmRSS:/ {print $2}' "/proc/$pid/status")"
  else
    pid=""
  fi
  echo "$(date '+%Y-%m-%d %H:%M:%S'),$pid,$rss" >> "$LOG"
  sleep "$INTERVAL"
done
