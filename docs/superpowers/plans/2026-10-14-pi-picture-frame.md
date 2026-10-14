# Pi Picture Frame Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A touch-controlled Raspberry Pi 3 photo frame with dashboard, photo mode, alarm, settings and night schedule (Phase 1 of the spec).

**Architecture:** Pure-logic modules (config, today, alarm, night, weather, photos) with no pygame imports, fully unit-tested on any PC. A thin pygame UI layer renders four screens from plain view-model dicts; `app.py` owns the loop, mode state and touch dispatch. Runs as a systemd service on Pi OS Lite via SDL KMS/DRM.

**Tech Stack:** Python 3.11, pygame 2, Pillow, tomllib / tomli-w, pytest. No other runtime dependencies.

**Spec:** `docs/superpowers/specs/2026-10-14-pi-picture-frame-design.md`

## Global Constraints

- Python 3.11 (Pi OS Bookworm); runtime deps only from apt: `python3-pygame python3-pil python3-tomli-w`
- Screen 1280×800; layout values, colors and font sizes exactly as in the spec
- Fonts (Inter, Atkinson Hyperlegible) and icons bundled in `assets/`; no runtime downloads
- Only network call: Open-Meteo, 10 s timeout, every 30 min
- Logic modules never import pygame; all time logic takes `now: datetime` as a parameter (no hidden `datetime.now()`)
- Config file `/home/pi/frame/config.toml`; photos `/home/pi/frame/photos`; cache `/home/pi/frame/cache`; to-dos `/home/pi/frame/todo.toml`
- Touch targets ≥ 64×64 px

## Review Focus

1. Night window crossing midnight (23:00–06:00) and equal start/end → `is_night` correct on both sides of midnight; equal = never night. *(Task 4)*
2. DST change day → alarm at 06:30 fires once at local 06:30, not twice or skipped. *(Task 3)*
3. Phone photos with EXIF rotation, huge files, corrupt files → shown upright, cached small, corrupt skipped. *(Task 6)*
4. `config.toml` missing or not valid TOML → app starts on defaults with a warning banner. *(Task 1)*
5. Alarm fires while Settings is open or display is in night black → alarm screen appears, screen wakes. *(Task 9)*

---

### Task 1: Repo scaffold + config

**Files:** Create `frame/__init__.py`, `frame/config.py`, `config.example.toml`, `tests/test_config.py`, `pyproject.toml` (pytest settings only), `.gitignore`

**Interfaces — Produces:**
- `@dataclass Config`: `photo_dir: Path`, `interval_s: int=30`, `fade_ms: int=1000`, `lat: float|None`, `lon: float|None`, `units: str="fahrenheit"`, `clock_24h: bool=False`, `week_start: str="sunday"`, `overlay_corner: str="bottom-left"`, `night_start: time|None=time(23,0)`, `night_end: time|None=time(6,0)`, `wake_s: int=60`, `alarm_enabled: bool=True`, `alarm_time: time=time(6,30)`, `alarm_days: set[int]={0,1,2,3,4}` (Mon=0), `snooze_min: int=9`, `cache_mb: int=500`, `todo_path: Path`
- `load_config(path: Path) -> tuple[Config, list[str], bool]` → config, warnings, `critical` (True if photo_dir, alarm or location invalid)
- `save_config(cfg: Config, path: Path) -> None` (atomic: write temp, rename)

- [ ] **Step 1:** `git init`; write tests:
  - `test_missing_file_gives_defaults_and_warning`: warnings non-empty, `critical is False` unless photo dir absent
  - `test_bad_toml_gives_defaults`: file `"x = ["` → defaults, warning contains "config.toml"
  - `test_rejected_value_logged_with_fallback`: `interval_s = -5` → `cfg.interval_s == 30`, warning mentions `interval_s` and `30`
  - `test_bad_alarm_time_is_critical`: `alarm_time = "25:99"` → `critical is True`
  - `test_save_roundtrip`: save then load → equal Config, no warnings
- [ ] **Step 2:** `pytest tests/test_config.py -v` → FAIL (import error)
- [ ] **Step 3:** Implement with `tomllib` read, `tomli_w` write; validate field by field, collecting warnings
- [ ] **Step 4:** `pytest tests/test_config.py -v` → PASS
- [ ] **Step 5:** Commit `feat: config load/validate/save`

### Task 2: Today list

**Files:** Create `frame/today.py`, `tests/test_today.py`

**Interfaces — Produces:**
- `@dataclass Item`: `title: str`, `kind: str` ("appointment"|"todo"), `at: time|None`, `day: date|None`, `done: bool=False`
- `load_items(path: Path) -> list[Item]` — `todo.toml` with `[[item]] title, kind, time="HH:MM", date="YYYY-MM-DD"`; missing/bad file → `[]`
- `visible_today(items: list[Item], now: datetime, limit: int=3) -> tuple[list[Item], int]` → shown items, count of more

- [ ] **Step 1:** Tests:
  - `test_hides_past_timed_and_done`: 09:30 item at now 12:41 → excluded; `done=True` excluded
  - `test_untimed_today_items_after_timed`: order = timed ascending, then untimed by file order
  - `test_limit_and_more_count`: 5 eligible → 3 shown, more == 2
  - `test_other_days_excluded`: `date` tomorrow → excluded; no `date` → treated as today
  - `test_bad_file_returns_empty`
- [ ] **Step 2:** Run → FAIL
- [ ] **Step 3:** Implement
- [ ] **Step 4:** Run → PASS
- [ ] **Step 5:** Commit `feat: today list`

### Task 3: Alarm logic

**Files:** Create `frame/alarm.py`, `tests/test_alarm.py`

**Interfaces — Consumes:** `Config` (Task 1). **Produces:**
- `next_trigger(now: datetime, cfg: Config) -> datetime|None` (local aware datetimes via `zoneinfo`)
- `class Alarm(cfg)` with `state: str` ∈ `"idle"|"ringing"|"snoozed"`, `missed_at: datetime|None`
  - `tick(now) -> str` (returns state; idle→ringing at trigger; ringing → idle + `missed_at` set after 10 min; snoozed → ringing at snooze end)
  - `snooze(now)`, `dismiss(now)`, `test(now)` (rings 10 s then idle, no missed flag)
  - `countdown(now) -> str` e.g. `"in 17h 49m"`; `""` when disabled

- [ ] **Step 1:** Tests:
  - `test_next_trigger_skips_disabled_days`: Wed 12:41, days Mon–Fri, 06:30 → Thu 06:30
  - `test_fires_once`: tick at 06:30:00 and 06:30:30 → one transition to ringing
  - `test_snooze_rerings_after_9_min`
  - `test_unanswered_10_min_sets_missed`: state idle, `missed_at` == trigger time
  - `test_dst_spring_forward_and_fall_back`: America/Los_Angeles 2026-03-08 and 2026-11-01 → exactly one ring at local 06:30
  - `test_countdown_text`: Wed 12:41 → `"in 17h 49m"`
  - `test_disabled_never_rings`
- [ ] **Step 2:** Run → FAIL
- [ ] **Step 3:** Implement
- [ ] **Step 4:** Run → PASS
- [ ] **Step 5:** Commit `feat: alarm logic`

### Task 4: Night schedule

**Files:** Create `frame/night.py`, `tests/test_night.py`

**Interfaces — Produces:**
- `is_night(now: time, start: time|None, end: time|None) -> bool`
- `class NightController(cfg)`: `should_be_dark(now: datetime, alarm_state: str) -> bool`; `wake(now)` (touch keeps screen on `wake_s` seconds)

- [ ] **Step 1:** Tests:
  - `test_overnight_window`: 23:00–06:00 → 23:30 True, 05:59 True, 06:00 False, 12:00 False
  - `test_same_day_window`: 13:00–15:00 → 14:00 True
  - `test_equal_or_none_never_night`
  - `test_tap_wakes_for_wake_s`: dark → `wake` → light for 60 s → dark again
  - `test_ringing_alarm_forces_light`
- [ ] **Step 2:** Run → FAIL
- [ ] **Step 3:** Implement
- [ ] **Step 4:** Run → PASS
- [ ] **Step 5:** Commit `feat: night schedule`

### Task 5: Weather

**Files:** Create `frame/weather.py`, `tests/test_weather.py`, `tests/fixtures/open_meteo.json` (a saved real response)

**Interfaces — Produces:**
- `@dataclass Weather`: `temp: int`, `code: int`, `icon: str`, `label: str`, `high: int`, `low: int`, `fetched: datetime`
- `build_url(lat, lon, units) -> str` (current=temperature_2m,weather_code; daily=temperature_2m_max,temperature_2m_min; timezone=auto; forecast_days=1)
- `parse(data: dict, fetched: datetime) -> Weather`
- `icon_for(code: int) -> str` ∈ clear, partly-cloudy, cloudy, fog, rain, snow, storm, unknown (WMO: 0 clear; 1–2 partly; 3 cloudy; 45,48 fog; 51–67,80–82 rain; 71–77,85–86 snow; 95–99 storm)
- `class WeatherService(cfg, fetch=urllib-based)`: `update(now)` (every 30 min, background thread, 10 s timeout, keeps last good), `current(now) -> Weather|None` (None if > 3 h old or never fetched)

- [ ] **Step 1:** Tests:
  - `test_parse_fixture` values match fixture
  - `test_icon_mapping_table` (one assert per range, unknown for 1234)
  - `test_failure_keeps_last_good`: fake fetch raises → `current` still returns previous
  - `test_stale_after_3h_returns_none`
  - `test_no_location_never_fetches`
- [ ] **Step 2:** Run → FAIL
- [ ] **Step 3:** Implement (fetch injected so tests never hit the network)
- [ ] **Step 4:** Run → PASS
- [ ] **Step 5:** Commit `feat: weather service`

### Task 6: Photos library + resize cache

**Files:** Create `frame/photos.py`, `tests/test_photos.py`

**Interfaces — Produces:**
- `class PhotoLibrary(folder: Path, rng=random.Random)`: `scan() -> int` (count; JPG/PNG always, WebP only if `PIL.features.check("webp")`), `next() -> Path|None` (shuffled, no repeat until all shown, picks up new files on rescan)
- `class ImageCache(dir: Path, limit_mb: int)`: `get(src: Path, size: tuple[int,int], mode: str) -> Path|None` (`mode` "fill"=crop, "fit"=letterbox); uses `Image.draft` for JPEG, `ImageOps.exif_transpose`; key = hash(path, mtime, size, mode); evicts least-recently-used when over limit; corrupt → None and logged once

- [ ] **Step 1:** Tests (generate images with Pillow in `tmp_path`):
  - `test_scan_counts_supported_only` (.txt ignored)
  - `test_shuffle_covers_all_before_repeat`
  - `test_new_file_found_on_rescan`
  - `test_empty_folder_next_is_none`
  - `test_cache_output_size_exact` for fill 664×600 and fit 1280×800
  - `test_exif_rotated_image_upright` (orientation 6 → output is landscape when source stored portrait)
  - `test_corrupt_file_returns_none`
  - `test_eviction_under_limit`
- [ ] **Step 2:** Run → FAIL
- [ ] **Step 3:** Implement
- [ ] **Step 4:** Run → PASS
- [ ] **Step 5:** Commit `feat: photo library and cache`

### Task 7: Theme, widgets, dashboard screen

**Files:** Create `frame/ui/theme.py`, `frame/ui/widgets.py`, `frame/ui/dashboard.py`, `assets/fonts/*`, `assets/icons/*.png` (8 weather + ui icons, 2 sizes), `tests/test_ui_render.py`

**Interfaces — Produces:**
- `theme`: color constants from spec, `font(name: str, size: int) -> pygame.font.Font` (cached)
- `widgets`: `Button(rect, label|icon, action: str)`, `Toggle`, `Stepper`; each `draw(surf)`, `hit(pos) -> str|None`
- `dashboard.render(surf, vm: dict) -> list[Button]` — `vm` keys: `time, ampm, date, today_items, more, month_cells, alarm_text, alarm_on, countdown, weather (Weather|None), photo (Surface|None), photo_dir, banner (str|None)`; returned buttons: `"to_photo"`, `"open_settings"`

- [ ] **Step 1:** Test (run with `SDL_VIDEODRIVER=dummy`): `test_dashboard_renders_all_states` renders normal / weather None / photo None / empty today / banner onto 1280×800 surface, asserts the two buttons exist at spec rects (`to_photo` = (1160,56,64,64)), saves PNGs to `tests/out/`
- [ ] **Step 2:** Run → FAIL
- [ ] **Step 3:** Implement to the spec layout table
- [ ] **Step 4:** Run → PASS; open PNGs and compare with the canvas
- [ ] **Step 5:** Commit `feat: dashboard screen`

### Task 8: Photo, alarm, settings screens

**Files:** Create `frame/ui/photo_mode.py`, `frame/ui/alarm_screen.py`, `frame/ui/settings_screen.py`; extend `tests/test_ui_render.py`

**Interfaces — Produces:**
- `photo_mode.render(surf, vm, controls_visible: bool) -> list[Button]` → `"prev"`, `"next"`, `"to_dashboard"` (only when visible)
- `alarm_screen.render(surf, vm) -> list[Button]` → `"snooze"`, `"dismiss"` (240×120 each)
- `settings_screen.render(surf, cfg: Config) -> list[Button]` → `"done"`, `"alarm_toggle"`, `"alarm_minus"`, `"alarm_plus"`, `"day_0"…"day_6"`, `"snooze_minus/plus"`, `"test_alarm"`, `"interval_minus/plus"`, `"fade_toggle"`, `"clock_12"`, `"clock_24"`, `"night_edit"`, `"brightness"` (only if supported)

- [ ] **Step 1:** Tests: each screen renders without error, expected button ids present, all buttons ≥ 64×64; save PNGs
- [ ] **Step 2:** Run → FAIL
- [ ] **Step 3:** Implement
- [ ] **Step 4:** Run → PASS; review PNGs
- [ ] **Step 5:** Commit `feat: photo, alarm and settings screens`

### Task 9: Mode controller + main loop

**Files:** Create `frame/controller.py`, `frame/app.py`, `tests/test_controller.py`

**Interfaces — Consumes:** Tasks 1–8. **Produces:**
- `class Controller(cfg, alarm, night, clock)`: `mode` ∈ `"dashboard"|"photo"|"settings"|"alarm"|"dark"`; `on_tap(button_id: str|None, now)`; `on_tick(now)`; `apply_setting(button_id) -> Config` (returns changed config; caller saves)
- `app.main()`: init pygame fullscreen 1280×800, mouse hidden; 10 fps idle loop, 30 fps during fades; pre-loads next photo 5 s before switch; fade via alpha blit, cut if `fade_ms == 0`

- [ ] **Step 1:** Tests:
  - `test_photo_tap_shows_controls_then_hides_after_5s`
  - `test_only_mode_buttons_switch_mode`: tap with `None` on dashboard → stays
  - `test_alarm_preempts_settings_and_dark`: ringing from `"settings"` and `"dark"` → `"alarm"`; after dismiss returns to previous mode (dark if night)
  - `test_tap_in_dark_wakes`
  - `test_settings_change_returns_new_config`: `alarm_plus` → alarm_time +5 min
- [ ] **Step 2:** Run → FAIL
- [ ] **Step 3:** Implement controller, then `app.py` wiring (config warnings → banner; weather update thread; photo rescan every 5 min; logging to stdout for journald)
- [ ] **Step 4:** Run tests → PASS; run `python -m frame.app` on a PC window with a sample folder and click through every screen
- [ ] **Step 5:** Commit `feat: controller and main loop`

### Task 10: Display power + audio

**Files:** Create `frame/display.py`, `frame/sound.py`, `assets/alarm.wav`, `tests/test_display.py`

**Interfaces — Produces:**
- `class DisplayPower(method: str)` ∈ `"black"` (default, draws nothing extra), `"cec"` (`cec-ctl --standby/--image-view-on`), `"dpms"`; `set(on: bool)`; failures logged once, falls back to `"black"`
- `sound.play_alarm(loop=True)`, `sound.stop()` via `pygame.mixer`; missing audio device → log, visual alarm only
- Config gains `screen_power: str="black"`, `brightness_cmd: str|None`

- [ ] **Step 1:** Tests: `test_failing_backend_falls_back_to_black` (fake subprocess raising), `test_missing_mixer_does_not_crash`
- [ ] **Step 2:** Run → FAIL
- [ ] **Step 3:** Implement
- [ ] **Step 4:** Run → PASS
- [ ] **Step 5:** Commit `feat: display power and alarm sound`

### Task 11: Pi deployment

**Files:** Create `deploy/setup.sh`, `deploy/frame.service`, `deploy/memlog.sh`, `README.md`

- [ ] **Step 1:** `setup.sh`: apt install deps + `cec-utils`; add `dtoverlay=i2c-rtc,ds3231` and `dtparam=i2c_arm=on` to `/boot/firmware/config.txt`; `apt purge fake-hwclock`; enable `systemd-timesyncd`; set timezone; create `/home/pi/frame/{photos,cache}`; copy example config
- [ ] **Step 2:** `frame.service`: `User=pi`, `Environment=SDL_VIDEODRIVER=kmsdrm`, `ExecStart=/usr/bin/python3 -m frame.app`, `Restart=always`, `RestartSec=3`, `After=time-sync.target`
- [ ] **Step 3:** On the Pi: run setup, reboot. Verify: `hwclock -r` shows correct time; `systemctl status frame` active; frame visible within 45 s (measure with `systemd-analyze` + stopwatch)
- [ ] **Step 4:** Verify touch on all screens, alarm sound via Test alarm, each `screen_power` method on this screen; set the one that works
- [ ] **Step 5:** Commit `chore: Pi deployment`

### Task 12: 48 h soak test

**Files:** `deploy/memlog.sh` (from Task 11): logs `date, RSS of frame` every 10 min to `~/frame/memlog.csv`

- [ ] **Step 1:** Start memlog; set alarm 5 min ahead and a night window 1 h ahead
- [ ] **Step 2:** During 48 h: unplug network for 4 h; add 20 photos via `rsync`; let one alarm go unanswered, snooze one, dismiss one at night; `sudo reboot` once; `kill -9` the app once
- [ ] **Step 3:** Pass = no crash besides the forced kill (auto-restart < 10 s), RSS growth < 10 % after first hour, all spec Phase 1 acceptance items checked
- [ ] **Step 4:** Commit results summary to `README.md`
