# Pi Picture Frame — Design Spec

**Date:** 2026-10-14 · **Status:** Draft for review · **Rev 3:** contemporary navy and teal palette pass (see "Design review changes" at the end)

## Goal
A Raspberry Pi 3 wooden tabletop frame: photo slideshow + clock/date, calendar, weather and alarm, touch-controlled, runs unattended for weeks.

**Who and where:** a shared household device, glanced at from across a room and used half-asleep at arm's length (bedside alarm). On a 10.1" panel (149 ppi, 1 px ≈ 0.17 mm) that gives two reading distances: the **clock and photos** are for the room (2–3 m); the **date, Today list, calendar and controls** are for arm's length (≈ 0.5–0.7 m). See "Readability and touch check". It should feel like a picture frame first and a gadget second: calm, dark, no clutter, nothing that needs instructions.

## Hardware
- Raspberry Pi 3 (record B or B+), 16–32 GB A1 microSD, matching 5V PSU
- 10.1" IPS HDMI capacitive touchscreen, 1280×800 (~149 ppi), USB touch
- DS3231 RTC module (battery-backed time when offline)
- Small speaker (3.5 mm or USB) behind frame grille
- Wood frame with vent slots and safe cable routing

## Stack
Raspberry Pi OS Lite · Python 3 + pygame (SDL KMS/DRM, no desktop) · Open-Meteo (lat/lon, no key) · `config.toml` · systemd service (auto-start, restart, journal logs). Fonts and icons bundled locally.

## Visual system

### Colors
| Token | Hex | Use |
|---|---|---|
| `bg` | `#0E1726` | Midnight navy screen background |
| `panel` | `#172538` | Blue-slate panels and cards |
| `raised` | `#22364B` | Buttons and tappable rows on a panel |
| `pressed` | `#2D4862` | Any button while the finger is down |
| `divider` | `#2B4054` | 1 px row separators in Settings |
| `text` | `#F4F7FB` | Cool white primary text |
| `secondary` | `#B7C6D6` | Blue-gray labels and metadata |
| `accent` | `#43D9C3` | Vivid teal for Today, On states, and primary buttons; text on it is `bg` |
| `highlight` | `#F2B66D` | Warm amber to-do marker and warnings |
| `alarm` | `#FF766F` | Coral alarm label, ring pulse, and missed-alarm stripe. **Never** behind `text`; use `bg` for any alarm-colored fill |
| `night` | `#929DAA` | All night clock text including AM/PM, on pure black `#000000` (7.6:1) |

### Type scale
Inter for clock, numbers and headings; Atkinson Hyperlegible for UI text. Nothing smaller than 18 px.

**Font status:** the mockups use Inter for all text. Bundling Atkinson Hyperlegible (Regular + Bold, OFL) in `assets/fonts/` is a build-time task (plan Task 7). The visual design counts as final only after Atkinson is confirmed rendering on the Pi itself (plan Task 11), and after every label is re-checked for fit, because Atkinson runs wider than Inter.

| Role | Font | Size |
|---|---|---|
| Clock (dashboard) | Inter SemiBold | 96 |
| Clock (alarm, night clock) | Inter SemiBold / Medium | 160 |
| Clock chip (photo mode) | Inter SemiBold | 64 |
| Date, card titles | Atkinson / Inter SemiBold | 28 |
| Weather temp, stepper values | Inter SemiBold | 32 / 28 |
| Body (Today items, settings labels) | Atkinson | 24 |
| Secondary labels | Atkinson / Inter Medium | 20–22 |
| Caption, section headers (uppercase, +1.5 px tracking) | Inter SemiBold | 18 |

Clock digits use **fixed-width cells** (each digit centered in the width of "0") so the clock does not jiggle as minutes change. pygame cannot turn on Inter's tabular figures, so this is done at render time.

### Shape, spacing, depth
- Radii: panels 24, cards and buttons 16, chips 20, day chips and toggles fully round
- Spacing on an 8 px grid; screen margin 32, gutter 24, panel padding 24
- Text on photos: `bg` backing at 80% opacity, 20/24 px padding, soft 8 px shadow
- No gradients or glow except the alarm ring pulse

### Contrast (verified)
Rule: text ≥ 4.5:1 against its actual background in every state; icons and control outlines ≥ 3:1. Measured with the WCAG 2.1 formula (worst case shown):

| Pair | Ratio |
|---|---|
| `text` on `bg` / `panel` / `raised` / `pressed` | 16.7 / 14.4 / 11.5 / 8.8 |
| `secondary` on `bg` / `panel` / `raised` / `pressed` | 10.3 / 8.9 / 7.1 / 5.5 |
| `bg` on `accent` / pressed accent `#61E5D2` | 10.2 / 11.7 |
| `alarm` on `bg` (ALARM caption only) | 6.9 |
| `night` on black | 7.6 |
| `text` / `secondary` on a photo chip over a pure-white photo (`bg` 80% opacity) | Both exceed 4.5:1 |
| Icon `secondary` on `raised` (toggle-off knob, disabled stepper) | 7.1 |

Not allowed: `alarm` as text on buttons or as a background behind `text`. `raised` buttons on `panel` are only 1.25:1, so a button is identified by its icon or label, never by its fill alone.

**Disabled state** (stepper at the end of its range, night start/end while night mode is off): the button loses its `raised` fill and its icon turns `secondary`, and values stay `secondary`. That is still at least 7:1, readable but clearly not active. Disabled buttons ignore taps.

### Icons
Outlined, 2 px rounded strokes, pre-rendered PNGs in 32 and 48 px.
- Weather: clear, partly-cloudy, cloudy, rain, snow, storm, fog, unknown
- UI: expand (to Photo), dashboard, chevron-left, chevron-right, chevron-forward (row affordance), bell, close, minus, plus, photo (empty state)

### Touch rules (all screens)
- Every hit area ≥ 64×64 px; the visible shape may be smaller (for example the toggle pill is 88×48 inside a 104×64 hit area)
- **Pressed feedback:** the button repaints in `pressed` (or a lighter accent) on finger-down, within one frame
- **Actions fire on finger-up inside the same button.** Sliding off cancels the action, so a brush of the frame while moving it does nothing
- Taps that hit no button do nothing, except for the cases listed per screen

## Modes

### Dashboard
1280×800, 32 px margins, 24 px gutter, 24 px panel padding.

| Area | x, y | Size |
|---|---|---|
| Left panel | 32, 32 | 480×736 |
| Right photo panel | 536, 32 | 712×736 |
| Weather strip (inside right panel, above photo, no overlay) | 560, 56 | 576×64 |
| Mode button `to_photo` (expand icon, `raised` fill) | 1160, 56 | 64×64 |
| Photo content (16 px corner radius) | 560, 144 | 664×600 |
| Banner (over the photo's bottom edge, when present) | 576, 664 | 632×64 |

**Left panel**: content x 56–488 (432 wide). Top to bottom:

| Element | y | Height | Detail |
|---|---|---|---|
| Clock | 56 | 104 | 96 px; AM/PM 28 px `secondary` sits on the same baseline, 12 px after the digits; hidden in 24 h mode |
| Date | 168 | 36 | 28 px, e.g. "Wednesday, October 14" |
| "TODAY" header | 228 | 24 | Caption style, `secondary` |
| Today items | 260 | 3 × 44 | See below |
| "+N more" | 392 | 24 | 20 px `secondary`; blank when nothing more |
| Calendar weekday row | 440 | 28 | Two-letter initials ("Su Mo Tu…"), 18 px `secondary`, 7 equal columns |
| Calendar day rows | 468 | 6 × 30 | 20 px digits; always 6 rows so nothing below moves month to month |
| Alarm row (button) | 672 | 72 | See below |

- **Today items:** columns are time (x 56, 96 wide, Inter Medium 22 `secondary`), marker (x 160, 16×16), title (x 188 to 488, Atkinson 24 `text`, ellipsis when too long). Markers differ in shape *and* color: appointment = filled `accent` circle, to-do = 2 px `highlight` outlined rounded square. Untimed items leave the time column blank. The type word is dropped; the marker shape carries it, and the title gets the room.
  - Empty: "Nothing else today" in 24 px `secondary` on the first row.
  - Phase 1 source: local `todo.toml` (items with optional time) edited over SSH. Phase 2 merges iCal appointments.
- **Calendar:** the date line above already names the month, so there is no month title. Today = 32 px `accent` circle with `bg` SemiBold digit. Days outside the month are blank. Week start follows `week_start`.
- **Alarm row:** a `raised` card, radius 16, which is the `open_settings` button. Contents from left: bell icon 32, time (Inter SemiBold 28), days (e.g. "Mon–Fri", 20 px `secondary`), status dot (12 px: filled `accent` when on, `secondary` outline when off), countdown ("in 17h 49m", 20 px `secondary`, right-aligned), chevron-forward. The chevron tells people the row opens something.
  - Alarm off: "Alarm off" in `secondary`, outline dot, no countdown.
  - **Snoozed:** the row narrows to `open_settings` (56, 672, 336, 72) and shows "Snoozed · rings 6:39" (22 px SemiBold) in `text` with the bell icon in `alarm`. Alarm red is never used for text on `raised`. A `stop_snooze` button (416, 672, 72, 72, close icon) cancels the snooze; the 24 px gap keeps "open Settings" and "cancel snooze" from being hit by the same finger. Without it, someone who snoozed and got up has no way to stop the next ring except waiting for it.
- **Photo content:** shows the current slideshow photo, cropped to fill. Landscape photos crop around the center; portrait photos keep the **upper third** in view, so faces aren't cut off. Empty folder: photo icon, "No photos yet" (28 px), the folder path (Inter 20 `secondary`, e.g. `/home/pi/frame/photos`) and "Add photos with rsync over SSH" (20 px `secondary`), centered.

**Weather strip** (576×64): icon 48 · temp 32 px · condition label 22 px · "H 72°  L 55°" 22 px `secondary`, right-aligned.
- Result older than 1 h: the label becomes "Partly cloudy · as of 10:12", so people can tell how fresh it is
- Older than 3 h or never fetched: unknown icon + "Weather unavailable" in `secondary`
- No location configured: "Set location in config.toml" in `secondary`

**Banners** (one at a time, highest priority shown, "· 1 more" appended when others wait):
1. "Missed alarm 6:30 AM": 6 px `alarm` left stripe; the whole banner is `clear_banner`, with a close icon at the right
2. "Time not set": `highlight` stripe
3. "Settings not saved" (save failed, see Saving and recovery): `highlight` stripe
4. Config warning, e.g. "config.toml has errors — using defaults": `highlight` stripe

The banner is a `bg` 90% card, radius 16, with 22 px Atkinson text. Only the missed-alarm banner is dismissible; the others clear when their cause is fixed.

### Photo
- Fullscreen. Photos are **fitted, not cropped**; the letterbox area is filled with a blurred (radius 24 on a 1/8 downscale), 40%-brightness copy of the same photo. Portrait phone photos then fill the frame instead of sitting in two black bars. The composite is made once and cached on disk.
- **Clock chip:** time 64 px + AM/PM 24, date 24 px `secondary` below; corner from `overlay_corner` (default bottom-left), 32 px from the edges, max 320 wide
- **Weather chip** (300×80): icon 48, temp 32, "H 72° L 55°" 20 px; in the diagonally opposite corner; hidden when weather is older than 3 h
- **Missed-alarm chip:** shown in a free top corner; tapping it clears it (`clear_banner`). Someone who leaves the frame in Photo mode still sees the missed alarm.
- **Tap anywhere** shows the control bar for 5 s: a `bg` 80% bar (360, 680, 560, 96), radius 24, with `prev` (376, 688, 120, 80), `next` (512, 688, 120, 80) and `to_dashboard` (648, 688, 256, 80; dashboard icon + "Dashboard" label). A 4 px `accent` line along the bar's bottom edge shrinks over the 5 s as the "hides soon" cue. Any tap on the bar restarts the 5 s. The bar is centered, so it never overlaps a corner chip.
- Prev/Next change the photo with a cut (no fade) and restart the slideshow interval. Only `to_dashboard` switches mode.
- Empty folder: Photo mode shows the dashboard's empty-state message centered, with the clock chip.

### Alarm
`bg` background. Nothing else is tappable.
- A 6 px `alarm` ring around the screen edge pulses (opacity 40–100%, 1 s cycle), so it is visible from across the room
- "ALARM" (or "TEST ALARM") caption in `alarm`, centered at y 120
- Time 160 px `text`, centered, y 160–330; date 28 px `secondary` at y 350
- `snooze`: **primary**, (400, 440, 480, 144), `accent` fill, "Snooze 9 min" in 36 px `bg`. This is the button a half-asleep hand should land on.
- `dismiss`: secondary, (520, 640, 240, 120), `raised` fill with a 2 px `text` outline, "Dismiss" in Inter SemiBold 32 `text` (11.4:1). It is half as wide as Snooze and sits 56 px below it, so dismissing takes a deliberate tap, but the solid fill and bold label keep it easy to find. Both labels spell out the action; there are no icon-only alarm buttons.
- **Touch guard:** the alarm screen ignores any touch whose finger-down happened before the screen appeared, and all touches for its first 1 s. A tap meant to wake the night screen, or to adjust Settings, can't land on Snooze or Dismiss by accident.
- Test alarm: same screen, caption "TEST ALARM", closes itself after 10 s

### Night clock (new)
Shown when someone taps the black night screen, for `wake_s` seconds (default 60), then back to black.
- Pure black `#000000`, time 160 px Inter Medium in `night` color, centered at y 250–410
- Below it, the `open_settings` button (440, 520, 400, 72) with no fill: "Alarm 6:30 AM · Mon–Fri" in 24 px `night` + chevron-forward. This is for checking or changing the alarm at bedtime without being lit up by the full dashboard.
- The alarm itself still uses the normal Alarm screen at night
- **Wake rules:**
  - The tap that wakes the black screen is consumed: it only shows the night clock and never activates a button, even when it lands where `open_settings` will appear
  - While the night clock shows, taps outside `open_settings` only restart the `wake_s` timer, and nothing changes mode
  - The dimness comes from color (`night` on black), so it works on every screen whether or not backlight control exists
  - If the alarm fires while the night clock or black screen is showing, the Alarm screen replaces it at once (touch guard above). After Dismiss or the 10-min stop it returns to black if it is still night, otherwise to the mode it interrupted

### Settings
Opened from the dashboard alarm row (or the night clock). `bg` background.

- **Header:** "Settings" Inter SemiBold 32 at (32, 44); hint "Saved when you tap Done" 18 px `secondary` below it; `done` (1088, 32, 160, 64), `accent` fill, "Done" 24 px `bg`
- **Cards:** Alarm (32, 120, 596, 648) and Display (652, 120, 596, 648), `panel` fill, radius 24, padding 24. Card title 28 px at card top + 24. Rows start at card top + 64 and are 80 px tall (64 px control + 8 px above and below, so stacked controls are 16 px apart), separated by 1 px `divider`. Label is 24 px Atkinson on the left, control right-aligned.
- **Controls:**
  - Toggle: pill 88×48 (on = `accent` with `bg` knob on the right, off = `raised` with `secondary` knob on the left) in a 104×64 hit area; the knob position carries the state, not only the color
  - Stepper (272 wide): `[−]` 64×64 · value 144 wide, Inter SemiBold 28, centered · `[+]` 64×64, both `raised`; a button greys out (`secondary` icon at 40%) at the end of its range
  - Segmented: two 96×64 segments, the selected one `accent` with `bg` text
  - Day chips: 64×64 circles with 16 px gaps (7 × 64 + 6 × 16 = 544); selected = `accent` fill + `bg` text, off = 2 px `secondary` outline. Two-letter labels (Mo Tu We Th Fr Sa Su), because single letters (T/T, S/S) are ambiguous. Order follows `week_start`

| Alarm card | Control | Button ids |
|---|---|---|
| Alarm | Toggle | `alarm_toggle` |
| Hour | Stepper "6 AM" / "06", ±1 h, wraps | `alarm_hour_minus`, `alarm_hour_plus` |
| Minute | Stepper ":30", ±5 min, wraps within the hour | `alarm_min_minus`, `alarm_min_plus` |
| Repeat (row 128 tall: label above, chips below) | 7 day chips | `day_0`…`day_6` (Mon=0) |
| Snooze | Stepper "9 min", 1–30 | `snooze_minus`, `snooze_plus` |
| Test alarm | Outlined button 200×64, bell icon + "Play 10 s" | `test_alarm` |

| Display card | Control | Button ids |
|---|---|---|
| Photo interval | Stepper over 10 s, 15 s, 30 s, 1 min, 2 min, 5 min, 10 min, 30 min | `interval_minus`, `interval_plus` |
| Fade | Toggle (`fade_ms` 1000 ↔ 0) | `fade_toggle` |
| Clock | Segmented 12 h / 24 h | `clock_12`, `clock_24` |
| Night mode | Toggle | `night_toggle` |
| Night starts | Stepper "11:00 PM", ±30 min (dimmed when night mode is off) | `night_start_minus`, `night_start_plus` |
| Night ends | Stepper "6:00 AM", ±30 min | `night_end_minus`, `night_end_plus` |
| Brightness (only if supported) | Stepper 10–100 %, ±10 | `brightness_minus`, `brightness_plus` |

- Separate hour and minute steppers replace the single ±5 min stepper: moving 6:30 AM to 6:30 PM took 144 taps, and now takes 12
- Changes apply live, including alarm time and days, so an alarm always follows what is on screen even before saving. `config.toml` is written once on Done (or idle close), to spare the SD card
- **Idle close:** after 120 s with no touch, Settings saves and returns to the previous mode, so the frame never sits on Settings all day
- **Settings never covers or delays an alarm.** Alarm checks run every tick in every mode. When an alarm (or snooze end) fires while Settings is open, the Alarm screen replaces it immediately; unsaved changes stay in memory. The idle timer is paused while the Alarm screen shows. After Dismiss/Snooze the frame returns to Settings with the idle timer restarted at 120 s. Test alarm works the same way.

### Saving and recovery
- **Atomic save:** write `config.toml.tmp`, flush and fsync, copy the current file to `config.toml.bak`, then rename the temp file over `config.toml`. A crash or power cut at any point leaves either the old or the new file intact, never a half-written one
- **Save fails** (disk full, read-only SD card, permission): Settings stays open and the header shows an error strip (200, 32, 696, 64) in place of the hint: "Couldn't save settings: <reason>." (20 px `text`) / "Your previous settings are still on disk." (18 px `secondary`), `panel` fill, 6 px `highlight` stripe. Done becomes "Retry" (`done`), and "Keep for now" (`keep_unsaved`, 912, 32, 160, 64, `raised`) closes Settings: the new values stay in effect until restart, and the dashboard shows a "Settings not saved" banner (priority below Time not set). If the idle close hits a failed save, it takes the "Keep for now" path
- **Startup recovery:** if `config.toml` is missing or invalid but `config.toml.bak` is valid, the frame loads the backup and shows the config-warning banner "config.toml was invalid — using last saved settings". Only if both fail does it fall back to defaults
- Settings never writes an unvalidated value: every value comes from a stepper range or toggle, and the saved file is reloaded and compared once after writing

### Readability and touch check
Panel: 10.1" at 1280×800 = 149 ppi, so 1 px ≈ 0.17 mm. Reading distance is estimated from x-height (cap height for numerals): comfortable at ≥ 0.3° of visual angle, minimum 0.2°.

| Element | Size on glass | Comfortable / minimum distance |
|---|---|---|
| Alarm and night clock (160 px) | 19.8 mm | 3.8 / 5.7 m |
| Dashboard clock (96 px) | 11.9 mm | 2.3 / 3.4 m |
| Photo-mode clock chip (64 px) | 7.9 mm | 1.5 / 2.3 m |
| Weather temp (32 px) | 4.0 mm | 0.8 / 1.1 m |
| Date (28), Today items (24), calendar (20) | 2.2–2.7 mm | 0.4–0.5 / 0.6–0.8 m |
| Captions, weekday initials (18) | 1.7 mm | 0.3 / 0.5 m |

- Readable across a room: the clock, on every screen. The date, list and calendar are for arm's length; making them readable from a sofa would need a different dashboard (for example, no calendar and a larger list), which is a product decision, not a tweak
- Touch: the smallest target is 64 px = 10.9 mm square, above the common 9–10 mm fingertip guideline. Snooze is 82 × 25 mm and Dismiss 41 × 20 mm. Adjacent targets are ≥ 16 px (2.7 mm) apart, and Snooze/Dismiss 56 px (9.5 mm). Actions fire on release inside the same button, which absorbs near-misses
- The mockups were checked at 1:1 (no scaling) on a monitor. Distances and targets still need confirming on the real panel (plan Task 11)

## Behavior
- **Slideshow:** shuffle, 30 s default, fade `fade_ms` default 1000, 0 = cut (use if it stutters); JPG/PNG guaranteed, WebP only if pygame's image library reports support; unreadable files skipped; empty folder shows "No photos yet" with the folder path (default `/home/pi/frame/photos`); folder rescanned every 5 min. The dashboard photo and Photo mode share one sequence.
- **Mode switches** are instant cuts; only photo-to-photo changes fade
- **Image memory:** resized copies cached on disk (default limit 500 MB, oldest-used evicted first); only current + next display-sized surfaces in RAM
- **Weather:** current temp, icon, today's high/low; refresh every 30 min, 10 s timeout; last good result cached; older than 1 h → "as of HH:MM"; older than 3 h → dashboard strip shows "Weather unavailable", Photo-mode chip hides
- **Time:** DS3231 via `dtoverlay=i2c-rtc,ds3231`, `fake-hwclock` removed, `systemd-timesyncd` for NTP; RTC time used at boot, corrected by NTP when online; app logs an error and shows a "Time not set" banner if system time is before 2026-01-01
- **Alarm:** time + repeat days; sound + fullscreen alert; snooze default 9 min (configurable); a snooze can be cancelled from the dashboard alarm row; after 10 min unanswered, sound stops, alert closes, and a "Missed alarm HH:MM" banner (or chip in Photo mode) stays on screen until tapped; next occurrence follows the normal schedule. During night hours the alarm turns the screen on and plays sound, then returns to night mode after dismiss/stop.
- **Night schedule:** guaranteed behavior = full black screen at configured times (`night_enabled`, start, end; equal start/end = never night); backlight/DPMS/HDMI-CEC off are optional, enabled only after verifying with the exact touchscreen; a tap shows the dim Night clock for 60 s (configurable)
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
- Touch is reliable; mode switches only via the mode buttons; every tap shows pressed feedback
- Settings changes survive reboot; Test alarm plays sound and shows the alarm screen
- Alarm fires on set days; dismiss, snooze, cancel-snooze and 10-min stop work, including during night mode
- Tapping the night screen shows the dim Night clock, not the full dashboard
- Boot-to-display measured (target < 45 s)

## 48 h soak test
Log memory (RSS) every 10 min — no steady growth. During the trial: unplug network (weather hides, frame continues), pass night on/off transitions, fire/dismiss/snooze/ignore an alarm (incl. at night), reboot once, add photos while running.

## Before implementation
Confirm exact Pi 3 model and touchscreen model (touch interface, display controls, supported resolution). Also: photo folder path, city lat/lon, 12/24 h clock.

---

## Design review changes (Rev 2)

| # | Problem in Rev 1 | Change |
|---|---|---|
| 1 | Left panel had no vertical layout; clock + date + Today + 6-row calendar + alarm row overflowed 688 px | Exact y table; calendar month title dropped (the date line names the month); fixed 6 calendar rows |
| 2 | ±5 min was the only way to set the alarm time (144 taps for 12 h) | Separate hour and minute steppers |
| 3 | Settings had no layout, and `night_edit` opened an undefined editor | Two-card layout with row specs; night mode toggle + start/end steppers inline |
| 4 | White text on the alarm red is 2.96:1 (fails WCAG AA) | `alarm` color only for accents and rings, with `bg` text if used as a fill |
| 5 | Snooze and Dismiss were identical twins, easy to confuse half-asleep | Snooze large and primary; Dismiss smaller, outlined, 56 px away |
| 6 | No way to cancel a snooze | `stop_snooze` on the dashboard alarm row |
| 7 | Tapping the night screen lit the whole bedroom with the dashboard | Dim Night clock on pure black |
| 8 | Portrait photos: heads cropped on the dashboard, tiny between black bars in Photo mode | Top-third crop anchor; blurred backdrop letterbox |
| 9 | "Hides in 5 s" text is static and adds clutter | Shrinking progress line; bar placed so it never covers the corner chips |
| 10 | Banner placement, priority, and how to clear them undefined; missed alarm invisible in Photo mode | Banner zone, priority list, missed-alarm chip in Photo mode |
| 11 | Alarm row didn't look tappable, and it was the only way into Settings | Raised card + chevron |
| 12 | Single-letter weekday labels ambiguous (T/T, S/S) | Two-letter labels |
| 13 | No touch feedback; brush-taps when moving the frame would fire actions | Pressed state; actions fire on finger-up inside the button |
| 14 | Proportional digits make the clock jiggle each minute | Fixed-width digit cells |
| 15 | Settings could be left open all day; each tap wrote to the SD card | 120 s idle close; save once on Done |
| 16 | Weather staleness was invisible until it vanished | "as of HH:MM" after 1 h |
| 17 | Today rows spent width on the type word ("Appointment"), truncating titles | Marker shape carries the type; title gets the width |

**Review follow-up (Rev 2.1):**

| # | Check | Result / change |
|---|---|---|
| 18 | Contrast of final colors, including pressed and disabled | 3 failures found and fixed: snoozed text in `alarm` on `raised` (3.8:1) → `text`; night AM/PM `#4E5951` (2.9:1) → `night`; disabled grey (2.2–3.2:1) → no fill + `secondary`. Table under Visual system |
| 19 | Mockups at 1:1, targets and readability | Snooze-stop gap 8 → 24 px; Settings rows 76 → 80 px; day-chip gap 8 → 16 px; Dismiss gets a solid fill and a bold 32 px label. The date, list and calendar are arm's-length text (≤ 0.7 m), not ≤ 1 m as Rev 2 said |
| 20 | Save failure and recovery | Atomic save + `.bak`, error strip with Retry / Keep for now, backup used at startup |
| 21 | Alarm vs Settings idle timeout | Alarm preempts on the same tick, idle timer paused, live (unsaved) alarm settings used |
| 22 | Night clock wake | Wake tap consumed; alarm touch guard (finger-down before screen or < 1 s ignored) |
| 23 | Atkinson Hyperlegible | Build-time task (plan Task 7 Step 0) + on-Pi visual sign-off (Task 11 Step 4b) |

**Mockups:** `docs/design/` (dashboard, dashboard-snoozed, dashboard-empty, photo, alarm, night-clock, settings, settings-save-error). They use Inter for all text because Atkinson Hyperlegible wasn't available to the renderer; the build uses Atkinson for UI text as specified.

**Considered, deferred** (not Phase 1): pause button in Photo mode; day/night variants of the clear icon; ±2 px hourly layout shift against IPS image retention; hold-to-repeat on steppers.


### Rev 3 — Contemporary navy and teal palette

The forest-green palette is replaced with midnight navy, blue-slate surfaces, a bright teal action color, and warm amber details. This gives the frame a cleaner, more current look while retaining the calm low-light character. Coral remains reserved for alarms. All palette contrast ratios above are recalculated for these tokens; text remains at or above 4.5:1 in each listed state. The updated mockups are in `docs/design/`.
