# Pi Picture Frame — Design Spec

**Date:** 2026-10-14 · **Status:** Draft for review

## Goal
A Raspberry Pi 3 wooden tabletop frame: photo slideshow + clock/date, calendar, weather and alarm, touch-controlled, runs unattended for weeks.

## Hardware
- Raspberry Pi 3 (record B or B+), 16–32 GB A1 microSD, matching 5V PSU
- 10.1" IPS HDMI capacitive touchscreen, 1280×800, USB touch
- DS3231 RTC module (battery-backed time when offline)
- Small speaker (3.5 mm or USB) behind frame grille
- Wood frame with vent slots and safe cable routing

## Stack
Raspberry Pi OS Lite · Python 3 + pygame (SDL KMS/DRM, no desktop) · Open-Meteo (lat/lon, no key) · `config.toml` · systemd service (auto-start, restart, journal logs). Fonts and icons bundled locally.

## Modes (tap to switch)
**Dashboard** (1280×800, 32 px margins, 24 px gutter, 24 px panel padding)

| Area | x, y | Size |
|---|---|---|
| Left panel | 32, 32 | 480×736 |
| Right photo panel | 536, 32 | 712×736 |
| Weather strip (inside right panel, above photo, no overlay) | 560, 56 | 576×64 |
| Mode button | 1160, 56 | 64×64 |
| Photo content | 560, 144 | 664×600 |

Left panel, top to bottom: clock (96 px), date (28 px), **Today** list, month calendar (19 px, 30 px rows), next-alarm row.
- Today list (read-only): next 3 items for today, each `time · marker · title · type`; markers differ in shape and color (appointment = filled sage dot, to-do = amber outlined square); "+N more" when more remain; "Nothing else today" when empty. Phase 1 source: local `todo.toml` (items with optional time) edited over SSH; Phase 2 merges iCal appointments.
- Calendar: today highlighted; week start configurable (`week_start`). Below calendar: next-alarm row (time, days, On/Off dot, countdown e.g. "in 17h 49m"); tap opens Settings.

**Photo** — fullscreen image; clock/date and weather chips in configurable corners (`overlay_corner`; default clock bottom-left, weather top-right).

**Touch targets (min 64×64 px)**
- Dashboard: only the mode button switches to Photo mode; other taps do nothing.
- Photo: a tap anywhere shows a control bar for 5 s (Previous, Next, Dashboard) with a "Hides in 5 s" cue; only the Dashboard button switches mode.
- Alarm: Dismiss and Snooze buttons only (each 240×120 px); no long-press.

**Settings** — opened from the dashboard alarm row; Done returns to the frame; changes saved to `config.toml`.
- Alarm card: on/off, time (±5 min steppers), repeat days, snooze length, Test alarm (plays 10 s and shows the alarm screen)
- Display card: photo interval, fade on/off, clock 12/24 h, night hours, brightness (shown only if the screen supports it)

## Visual style
- Colors: bg `#101914`, panel `#1B2821`, text `#F2F0E8`, secondary `#B7C2B8`, accent `#A8C686`, highlight `#E6B86A`, alarm `#D96B5F`
- Fonts: Inter (clock, numbers, headings), Atkinson Hyperlegible (UI text); secondary labels 18–20 px; weather temp 32 px
- Text on photos: `#101914` backing at ~80% opacity, padded, light shadow
- Weather icons: outlined, rounded strokes, pre-rendered PNGs: clear, partly-cloudy, cloudy, rain, snow, storm, fog, unknown

## Behavior
- **Slideshow:** shuffle, 30 s default, fade `fade_ms` default 1000, 0 = cut (use if it stutters); JPG/PNG guaranteed, WebP only if pygame's image library reports support; unreadable files skipped; empty folder shows "No photos yet" with the folder path (default `/home/pi/frame/photos`); folder rescanned every 5 min
- **Image memory:** resized copies cached on disk (default limit 500 MB, oldest-used evicted first); only current + next display-sized surfaces in RAM
- **Weather:** current temp, icon, today's high/low; refresh every 30 min, 10 s timeout; last good result cached; older than 3 h → dashboard strip shows "Weather unavailable", Photo-mode chip hides
- **Time:** DS3231 via `dtoverlay=i2c-rtc,ds3231`, `fake-hwclock` removed, `systemd-timesyncd` for NTP; RTC time used at boot, corrected by NTP when online; app logs an error and shows a "Time not set" banner if system time is before 2026-01-01
- **Alarm:** time + repeat days; sound + fullscreen alert; snooze default 9 min (configurable); after 10 min unanswered, sound stops, alert closes, and a "Missed alarm HH:MM" banner stays on screen until tapped; next occurrence follows the normal schedule. During night hours the alarm turns the screen on and plays sound, then returns to night mode after dismiss/stop.
- **Night schedule:** guaranteed behavior = full black screen at configured times; backlight/DPMS/HDMI-CEC off are optional, enabled only after verifying with the exact touchscreen; a tap wakes the display for 60 s (configurable)
- **Config:** single `config.toml`, validated at start; each rejected value is logged with the fallback used; critical settings (photo folder path, alarm time/days, location) failing validation also show an on-screen warning banner
- **Photo upload:** `rsync` over SSH

## Modules
`app.py` (main loop, modes, touch) · `slideshow.py` · `dashboard.py` · `overlay.py` · `weather.py` · `alarm.py` · `night.py` · `settings.py` · `config.py` · `assets/` (fonts, icons, alarm sound) · `frame.service`

## Phase 2 (after core is stable, one at a time)
1. Calendar events from Google Calendar private iCal (read-only)
2. Add / check off to-dos on screen
3. RSS headlines
4. Stock ticker

## Phase 1 acceptance
- Starts on boot and recovers from a crash
- Photos, date and time display; fade at set interval; new photos appear without restart
- Clock correct after full power-off with no internet
- Weather updates online and disappears cleanly offline
- Touch is reliable; mode switches only via the mode buttons
- Settings changes survive reboot; Test alarm plays sound and shows the alarm screen
- Alarm fires on set days; dismiss, snooze and 10-min stop work, including during night mode
- Boot-to-display measured (target < 45 s)

## 48 h soak test
Log memory (RSS) every 10 min — no steady growth. During the trial: unplug network (weather hides, frame continues), pass night on/off transitions, fire/dismiss/snooze/ignore an alarm (incl. at night), reboot once, add photos while running.

## Before implementation
Confirm exact Pi 3 model and touchscreen model (touch interface, display controls, supported resolution). Also: photo folder path, city lat/lon, 12/24 h clock.
