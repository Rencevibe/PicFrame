# PicFrame → Android APK: Execution Plan

**Status:** proposal for review. Nothing in the repository was changed.

**Baseline:** branch `claude/new-session-sonjj7`, the head of open PR 1 (commit `256419c`, 23 commits ahead of `main`). `main` holds only a one-line `README.md` and does **not** contain the app. Android work branches from the baseline, or from `main` once PR 1 is merged.

**Inspected:** 2026-10-16. Line references point to the baseline branch.

**Labels**

| Label | Meaning |
|---|---|
| **Fact** | Seen in the repository |
| **Rec** | Recommendation; can be changed |
| **Verify** | Check against current official documentation before relying on it. Any value shown is a candidate, not a decision. |
| G1–G9 | Confirmed gaps (§1) |
| D1–D4 | Blocking decisions (§8) |
| V1–V6 | Items to verify (§8) |
| R1–R7 | Risks (§8) |
| M0–M6 | Milestones (§7) |

**Roles:** Product owner (PO), Android lead (AL), QA lead (QA), Designer (DS).

---

## 1. Repository assessment (Fact)

**What it is:** a Raspberry Pi 3 touchscreen photo frame. The screen is 10.1", 1280×800, used in landscape. It runs unattended with no desktop and draws straight to the display through SDL KMS/DRM. The spec is `docs/superpowers/specs/2026-10-14-pi-picture-frame-design.md`; the mockups are `docs/design/*.png`.

| Aspect | Finding |
|---|---|
| Language and UI | Python 3.11 with pygame 2 (`frame/app.py`, `frame/ui/*`) |
| Libraries | Pillow (`frame/photos.py`), `tomllib`/`tomli_w` (`frame/config.py`), `urllib` (`frame/weather.py`) |
| Build | None. `pyproject.toml` only configures pytest. Packages come from apt (`deploy/setup.sh`). |
| Runtime | systemd service with `Restart=always` (`deploy/frame.service`); DS3231 real-time clock plus NTP |
| Size | About 2.55k lines in `frame/`; 127 pytest tests in `tests/` |
| Structure | Pure-logic modules with no pygame: `config`, `alarm`, `night`, `today`, `weather`, `photos`, `controller`. pygame layer: `app`, `ui/*`, `sound`, `display`. |
| Assets | Inter and Atkinson Hyperlegible fonts, each with its OFL license text; 44 icon PNGs; `assets/alarm.wav` |

**Features on the baseline:**
- **Dashboard:** clock, date, Today list, calendar, alarm row, weather, photo, banners.
- **Photo mode:** blurred letterbox behind the photo, corner chips, Prev/Next/Dashboard controls.
- **Slideshow:** shuffle and fade; the folder is rescanned every 5 min.
- **Alarm:** snooze and dismiss; a 1 s touch guard; stops after 10 min and shows a "missed" banner; test alarm.
- **Night mode:** black screen; a tap shows a dim clock.
- **Settings:** saved on Done or after 120 s idle; a failed save keeps the previous file as `.bak`.

Phase 2 (iCal, on-screen to-dos, RSS, stocks) exists only in the spec and is out of scope.

**Confirmed gaps**

| # | Gap | Evidence |
|---|---|---|
| G1 | No `LICENSE` file on either branch | Repository root |
| G2 | Icons and `alarm.wav` have no documented source or license | `assets/icons/`, `assets/alarm.wav` |
| G3 | Photos (`rsync`) and to-dos (editing `todo.toml`) can only be added over SSH | `README.md` |
| G4 | Screen power and brightness use Pi-only commands (`cec-ctl`, `vcgencmd`, `brightness_cmd`) | `frame/display.py:18` |
| G5 | Paths are hard-coded under `/home/pi/frame` | `frame/config.py:16`, `frame/app.py:37` |
| G6 | The alarm is checked every frame by a process that never stops | `frame/controller.py:112`, `frame/alarm.py:61` |
| G7 | Layout is absolute pixels for 1280×800 | `frame/app.py:30`, spec tables |
| G8 | Starting at boot and restarting after a crash depend on systemd | `deploy/frame.service` |
| G9 | Some settings exist only in the config file: `photo_dir`, `lat`/`lon`, `units`, `week_start`, `overlay_corner`, `wake_s`, `cache_mb` | `config.example.toml` |

---

## 2. Android approach

| Option | Verdict |
|---|---|
| Package the Python (python-for-android, or Chaquopy with a native UI) | Rejected. Alarms, storage access and keep-awake still need native code plus a bridge, and it adds a Python runtime and a niche toolchain. |
| Wrap it in a WebView | Not applicable: there is no web UI to wrap. |
| **Native Kotlin + Jetpack Compose** | **Rec** |

**Why Kotlin + Compose:**
- The hard parts are platform APIs: exact alarms, storage, staying awake, starting at boot, audio.
- The logic is small, takes `now` as an input, and is already tested.
- The UI has to be redone for Android screens in any case.

**Reused:** the spec, the mockups (as screenshot references), the fonts, the alarm sound (once G2 is resolved), the color tokens, the Open-Meteo request, and the test cases (as a checklist for the port).

**Dropped:** `deploy/`, `display.py`, and the real-time-clock handling.

**Rec:** put the Android app in an `android/` folder in the same repository.

---

## 3. Feature mapping (Rec unless marked)

| Feature (Pi source) | Android behavior |
|---|---|
| Photo folder and `rsync` (G3) | The user picks a folder once through the Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) and the app keeps access to it. Rescan every 5 min while the app is visible. No storage permission. Final choice is **D3**. |
| EXIF rotation, oversized or corrupt photos | `androidx.exifinterface` for rotation; sampled decoding; a pixel-count cap (`photos.py:22`); corrupt files skipped and remembered |
| Fill-crop and blurred letterbox | Same algorithm as `photos.py:148`, decoded at the device's physical resolution; LRU disk cache in `cacheDir` |
| Slideshow | Preload the next photo, cross-fade, Prev/Next cut instantly; timing from `SystemClock.elapsedRealtime()` |
| Today list (G3) | Read `todo.toml` from the picked folder every 60 s, same format. Final choice is **D3**. |
| Weather | Same Open-Meteo URL; `HttpURLConnection` with `org.json`; refresh every 30 min while visible; keep the last result |
| Location | Latitude and longitude typed into Settings, rounded to 2 decimals; no location permission |
| `config.toml` | DataStore. Changes apply live and are saved on Done or idle close. |
| File-only settings (G9) | A "Frame" section in Settings, built from the existing controls. Needs a mockup. |
| Alarm (G6) | Port the state machine to `java.time`. Register each next alarm with `AlarmManager.setAlarmClock()`. A boot receiver re-registers it after reboot or a time change. Persist the ring state. **Depends on the M1 gate.** |
| Alarm sound | `MediaPlayer` on the alarm stream (`USAGE_ALARM`), looping. If playback fails, the alarm stays visual only, as in `sound.py`. |
| Alarm over the lock screen | A full-screen notification opens an alarm screen over the lock screen (**V3**, M1 gate) |
| Night mode and brightness (G4) | Black screen plus minimum window brightness (`screenBrightness`). The brightness stepper sets window brightness. |
| Always on | Keep the screen on; hide the system bars; lock to landscape and its 180° rotation |
| Start at boot and recover from a crash (G8) | Optional: offer PicFrame as the Home app (follow-up, §7) |
| Lifecycle | One activity and one ViewModel. Slideshow, weather and rescans pause when the app is hidden; the OS-registered alarm still fires. |

---

## 4. Architecture and dependencies

**Structure (Rec):** a single `:app` module.

```
domain/    pure Kotlin, no android.*  ← config, alarm, night, today, weather, PhotoQueue, controller, formatters
data/      SettingsRepository, AlarmStateStore (DataStore), PhotoSource, ImageCache, WeatherClient, TodoSource
platform/  AlarmScheduler, AlarmReceiver, BootReceiver, AlarmService, ScreenController
ui/        theme tokens, DesignCanvas, widgets, 5 screens, FrameActivity, AlarmActivity, FrameViewModel
```

**Data flow:**
1. Sources expose Flows to `FrameViewModel`.
2. A ticker calls `controller.onTick(now)`.
3. Screens render from one `StateFlow<UiState>`.
4. A tap sends its button id and finger-down time back to the controller.
5. Any alarm-state change re-registers the next alarm.

**Navigation:** a `when (mode)` with no navigation library. The controller already defines mode changes and alarm preemption.

**Dependencies (Rec):**

| Need | Choice |
|---|---|
| UI | Compose BOM (`ui`, `foundation`), `activity-compose`, lifecycle-compose |
| Storage | `datastore-preferences` |
| Images | `exifinterface` |
| Async | `kotlinx-coroutines-android` |
| HTTP, JSON, TOML | None: platform HTTP client, `org.json`, a small parser for `[[item]]` |
| Tests | JUnit, `coroutines-test`, Compose UI tests, Roborazzi or Paparazzi |
| Excluded | Coil/Glide, Hilt, WorkManager |

**Versions:** not chosen yet. They must be checked against each other (**V5**) and are pinned in M0.

---

## 5. UI plan

- **Keep:** the navy and teal tokens, Inter for numbers, Atkinson Hyperlegible for UI text, the radii and spacing, the contrast rules (port the contrast test), 64-unit touch targets, and actions that fire on release.
- **Screens:** Dashboard, Photo, Alarm, Night clock and Settings, as in `docs/design/`.
- **Additions:**
  - First-run setup: pick a folder, allow exact alarms, allow notifications. Each step can be skipped; a skipped step shows a banner.
  - The Settings "Frame" section (G9).
- **Small changes:**
  - The empty-folder message drops the `rsync` hint.
  - Use Inter's `tnum` feature in place of the digit-cell workaround (`frame/ui/theme.py:151-157`).
  - Icons become vector drawables, once G2 is resolved.
- **Layout (proposal to validate in M3):**
  - Lay out every screen in a fixed 1280×800 design space and scale it uniformly to the window.
  - It should match the spec on 16:10 tablets.
  - On phones, targets may fall below 48 dp and the screen would letterbox.
  - M3 measures this on a phone and on a tablet, and the result feeds **D2**.

---

## 6. Build and release plan

| Item | Plan |
|---|---|
| Gradle | Kotlin DSL, version catalog, committed wrapper. JDK version follows AGP (**V5**). |
| `compileSdk`/`targetSdk` | Latest level that Google Play requires (**V1**) |
| `minSdk` | Candidate 26 (native `java.time`). Provisional in M0; confirmed when D2 is decided (**V1**). |
| App ID | Placeholder in M0 (candidate `io.github.rencevibe.picframe`); final by **D4**. It can't change after a Play release. Debug builds add a `.debug` suffix. |
| Versioning | `versionCode` from CI or the tag; `versionName` from the tag |
| Release build | R8 and resource shrinking |
| Signing | Keystore kept outside the repository. Credentials come from environment variables or `~/.gradle/gradle.properties`. Release builds fail if they're missing. `.gitignore` covers `*.jks`, `*.keystore`, `keystore.properties` and `local.properties`. CI decodes a base64 secret into a temp file. |
| APK | `./gradlew :app:assembleRelease`, then `apksigner verify`, then a GitHub Release |
| Play (only if D1 = Play) | AAB from `bundleRelease`; Play App Signing; Data safety form; privacy policy; permission declarations (**V2**, **V3**); internal testing track |
| CI | Every PR: lint, unit tests, screenshot tests, debug APK. Tags: signed APK, plus an AAB if D1 = Play. |

---

## 7. Roadmap

### Milestones

| M | Owner | Output | Depends on | Gate | Done when |
|---|---|---|---|---|---|
| **M0 Setup** | AL | `android/` skeleton (landscape, stays on); pinned version catalog; CI; verification note covering V1, V5 | Plan approved | Needs **D1** and **D3** recorded | A CI run on a clean clone shows `assembleDebug` and tests passing, and the versions are recorded with their official sources |
| **M1 Alarm feasibility** (throwaway, about 2–3 days) | AL; QA runs the tests | Spike APK and a results table (below); verification note for V2, V3 | M0, D1 | **Go/no-go for M4 and the alarm parts of §9** | The results table is signed off by the PO with "Go" or "No-go + chosen fallback" |
| **M2 Domain port** | AL | Kotlin `domain/` and tests ported from `tests/test_{alarm,night,today,weather,config,controller,photos}.py` | M0 | — | CI shows every listed Python case passing in Kotlin, with the mapping table in the PR |
| **M3 Photos and UI** | AL; DS for the Frame-section mockup and visual review | Photo source, `ImageCache`, slideshow, theme, `DesignCanvas`, all screens, screenshot tests, layout report on a phone and a tablet | M2, D3; D4 asset ruling for the icon conversion | Its layout report triggers **D2** | Screenshot tests pass against `docs/design/` within tolerance, and the layout report is attached for D2 |
| **M4 Platform integration** | AL | Production alarm path per the M1 outcome, plus weather, Today, night/brightness, first-run setup, and pause/resume | M1 (Go, or a fallback chosen), M2, M3, D2 | — | QA runs the §9 behavior checklist on one device and every applicable box is ticked |
| **M5 Release** | AL; PO for licensing | Signing, R8, tagged CI release, licenses screen, privacy policy if D1 = Play | M4, D4 | Needs **D4** | An APK built from a tag installs on a clean device and passes `apksigner verify`; a secret scan of git history is clean |
| **M6 Device validation** | QA | §9 run on two devices, including a non-Pixel; 48 h soak adapted from the README | M5 | Launch decision (PO) | The §9 checklist and the soak table are complete, and the PO signs the launch |

Order: M0 → M1 and M2 in parallel → M3 → M4 → M5 → M6.

### M1 go/no-go criteria

Test on one Android 14+ device and one older device. The result is **Go** only if all four hold:

1. The alarm rings within 5 s of the set time in each case: app in the background, process killed with `am kill`, Doze forced on with `dumpsys deviceidle force-idle`, and after a reboot.
2. The sound plays on the alarm stream while media volume is at 0.
3. The alarm screen appears over the lock screen, or a documented heads-up-notification fallback works.
4. The permissions it needs can be obtained through the D1 distribution path, per current official policy (V2, V3).

A user force-stopping the app cancels its alarms by Android design. That case is out of scope.

**If M1 is No-go, the PO picks one of these before M4 starts, and the plan changes to match:**

| Fallback | Plan changes |
|---|---|
| A. Alarm only while the frame is open | §3 Alarm: no `setAlarmClock`, no boot receiver. §9: drop the background, killed-process and reboot alarm checks, and add "the limitation is shown in Settings". M4 gets smaller. |
| B. No full-screen alarm (criterion 3 failed) | §3: heads-up notification only. §9: "the alarm screen appears over the lock screen" becomes "a notification appears". |
| C. Revisit D1 (criterion 4 failed under Play) | Ship as a sideloaded APK first. §6 Play row deferred. |

### Launch scope vs follow-up

| Launch (required) | Follow-up (optional, after launch) |
|---|---|
| All §1 features on Android; G3–G9 handled as in §3 | Opt-in Home-app mode (G8) |
| First-run setup and the Settings "Frame" section | Phone-specific layout, if D2 calls for it |
| Alarm per the M1 outcome | Play Store release, if D1 chose sideload first |
| G1 and G2 resolved; licenses screen | Burn-in pixel shift (R4) |
| Signed APK via CI | Phase 2 spec features |

---

## 8. Risks and open questions

### Blocking decisions

The PO makes each decision, with input from the AL where noted. This plan does not make them.

| ID | Decision | Decision-maker | Needed by | Blocks | Rec default |
|---|---|---|---|---|---|
| **D1** | Distribution: sideloaded APK, or Google Play? | PO | Before M1 starts | M1 criterion 4, the §6 Play row, Data safety form, privacy policy | Sideloaded APK first |
| **D2** | Devices: tablets only, or phones too? | PO, with the AL's M3 layout report | End of M3 | M4 layout work, final `minSdk` | Tablet-first |
| **D3** | Photo and to-do source (G3): picked folder, all device photos via MediaStore (needs a sensitive permission, V4), or the Photo Picker (doesn't pick up new photos)? | PO, with the AL | Before M3 starts (recorded in M0) | Data layer, permissions | Picked folder |
| **D4** | License (G1), icon and `alarm.wav` provenance (G2), final app ID, launcher icon | PO | Asset ruling before the M3 icon work; everything else before M5 | Icon conversion; any public release | Choose a license; replace any asset whose source can't be confirmed |

### Verify before relying on these (current official sources)

| ID | Item | Checked in |
|---|---|---|
| V1 | Google Play target API requirement; possible `minSdk` values | M0 |
| V2 | `SCHEDULE_EXACT_ALARM` vs `USE_EXACT_ALARM`, and Play eligibility for a frame that has an alarm | M1 |
| V3 | `USE_FULL_SCREEN_INTENT` limits on Android 14+; foreground service type for alarm playback | M1 |
| V4 | Play policy on `READ_MEDIA_IMAGES` (only if D3 chooses MediaStore) | Before D3 |
| V5 | Kotlin, Compose compiler, AGP, Gradle, Compose BOM, screenshot tool, and JDK all compatible with each other | M0 |
| V6 | Open-Meteo terms (believed to be non-commercial free tier with CC BY 4.0 attribution) | Before M5 |

### Risks

| ID | Risk | Type | Mitigation | Checked in |
|---|---|---|---|---|
| R1 | Vendor-specific background killing delays the alarm | Technical | Include a non-Pixel device | M1, M6 |
| R2 | Slow scans of large or cloud-backed folders | Performance | Measure with 5k photos; reconsider D3 if too slow | M3 |
| R3 | Bitmap memory on 2 GB tablets | Performance | Keep only the current and next photo; reuse buffers | M3, M6 |
| R4 | Burn-in on an always-on screen | Product | Follow-up pixel shift for OLED screens | Follow-up |
| R5 | Battery wear from charging 24/7 | Safety | Document it; outside the app's control | M5 docs |
| R6 | Location sent to a third party | Privacy | Round to 2 decimals; no other network calls; exclude the cache and folder grants from backup | M4 |
| R7 | Pixel differences between pygame mockups and Compose rendering | Testing | Compare with a tolerance; DS signs off by eye | M3 |

---

## 9. Acceptance criteria

The alarm items depend on the M1 outcome; see the M1 fallback table for how they change.

**Build and release**
- [ ] A signed release APK builds from a clean clone plus the signing environment variables, and `apksigner verify` passes
- [ ] No keystore, password or `local.properties` anywhere in git history
- [ ] Versions pinned (V5); CI green on lint, unit tests and screenshot tests
- [ ] Runs on the chosen `minSdk` and `targetSdk` (V1, D2)

**Logic parity**
- [ ] Every ported Python test case passes in Kotlin

**Behavior on a device**
- [ ] First run: folder, exact-alarm and notification steps work; each skipped step shows a banner
- [ ] Photos: fade at the set interval; Prev/Next cut; correct EXIF rotation; portrait shows the top third; corrupt files skipped; new photos appear within 5 min
- [ ] Clock, date, calendar, week start and 12/24 h are correct; clock digits don't shift
- [ ] `todo.toml` changes show within 60 s
- [ ] Weather updates while online; shows "as of HH:MM" after 1 h and "unavailable" after 3 h offline
- [ ] Every press shows feedback; actions fire on release inside the button; sliding off cancels
- [ ] The alarm rings on the set days: in the foreground, in the background, after the process is killed, after a reboot, and on a DST change day. Snooze, dismiss, cancel-snooze, the 10-min stop with its "missed" banner, and the touch guard all work, including at night.
- [ ] Settings survive a reboot after Done or idle close; the test alarm stops after 10 s
- [ ] Night mode is black at minimum brightness; a tap shows the night clock; the alarm breaks through and then returns to black
- [ ] The screen stays on in landscape; 180° rotation works

**Quality and compliance**
- [ ] Visual sign-off against `docs/design/`; contrast test passes; touch targets ≥ 48 dp on the D2 devices
- [ ] 48 h soak: no fatal errors in `logcat`; memory grows < 10 % after the first hour; 4 h offline is handled; 20 photos added while running; night-time alarm cases; one reboot
- [ ] The licenses screen lists the fonts, the asset sources and Open-Meteo attribution (V6); a LICENSE file exists (D4)
- [ ] Only the permissions needed by the M1 outcome and D3 are declared

**Follow-up only (not required for launch):** Home mode makes the frame visible within 45 s of power-on and recovers it after a crash.

---

## Execution checklist (first three actions after approval)

| # | Action | Owner | Evidence produced |
|---|---|---|---|
| 1 | Record D1 and D3 (D4 asset ruling, if it's ready) | PO | A dated decision log in the planning PR or `android/docs/decisions.md`, giving each choice and its reason |
| 2 | Check V1 and V5 against official sources, and V2 and V3 ahead of M1 | AL | A verification note listing each item, its source link, the date checked, and the provisional values to pin |
| 3 | Build the M0 skeleton and CI on a branch from the baseline | AL | A green CI run link showing `assembleDebug` and tests passing, plus the pinned `libs.versions.toml` |

After these, start M1 and M2 in parallel.
