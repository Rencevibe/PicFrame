# Milestone status (plan §7)

Baseline: `claude/new-session-sonjj7` @ `256419c`. Work branches: `android/m0-setup` → `android/m1-alarm-spike` → `android/m2-domain` → `android/m3-ui` (each built on the previous). No PRs opened yet.

## Execution checklist

| # | Action | Status | Evidence |
|---|---|---|---|
| 1 | Record D1 and D3 | Done 2026-10-16 | `android/docs/decisions.md` |
| 2 | Verification note for V1 and V5; V2 and V3 ahead of M1 | Done 2026-10-16 | `android/docs/verification.md` |
| 3 | M0 skeleton and CI | Done 2026-10-16 | CI run 38863455375 (green) |

## Milestones

| M | Status | Output | Evidence / blocker |
|---|---|---|---|
| M0 Setup | **Done** 2026-10-16 | Skeleton (landscape, screen kept on, system bars hidden); pinned catalog; CI (lint, unit tests, debug APK); verification note | Green on a clean checkout: https://github.com/Rencevibe/PicFrame/actions/runs/38863455375. Note: `testDebugUnitTest` had no test sources yet, because the first real tests arrive in M2. Open follow-up: `distributionSha256Sum` (see the verification note). |
| M1 Alarm feasibility | **Spike built; result PENDING.** Gate met (M0 done; D1 = sideload). | Spike module `android/spike-alarm`; protocol and results table in `android/docs/m1-alarm-spike.md` | Spike builds and passes lint: https://github.com/Rencevibe/PicFrame/actions/runs/38864034438. **Blocked:** QA has to run it on a physical Android 14+ device and an older device, and the product owner signs off Go/No-go. No device is available in the authoring environment, so no pass condition has been evaluated. |
| M2 Domain port | **Implemented; acceptance pending** (2026-10-16). Deferred cases reconciled in M3/M4: 89 of 91 Python cases now have Kotlin equivalents; the 2 directory-fsync cases are replaced by an atomic-write check. Needs PO acceptance. | Kotlin `domain/` (config, formatters, alarm, night, today with a TOML subset parser, weather with a JSON reader, photo rules, controller); 100 JVM tests. Details and the Python→Kotlin mapping: `android/docs/m2-domain.md` | CI green, "100 run, 0 failed, 0 skipped": https://github.com/Rencevibe/PicFrame/actions/runs/38866985819. **Open:** 7 config file-persistence cases are deferred to M4 (DataStore), and the pixel/file parts of 10 photo-cache cases to M3 (`ImageCache`). The Android lead and PO need to accept this mapping, or keep M2 open. No local build: Google Maven and Maven Central are blocked here. |
| M3 Photos and UI | **Done-when met in CI; PO acceptance pending** (2026-10-16) | Photo source, `ImageCache`, slideshow, theme, design canvas, all screens, Frame settings page, accessibility overlay. Details: `android/docs/m3-m4.md` | All 8 mockups within tolerance; 149 tests pass: https://github.com/Rencevibe/PicFrame/actions/runs/38873615983. **Caveats:** the layout report is calculated, not measured on devices; no designer sign-off by eye; no mockup exists for the Frame page or setup screen. |
| M4 Platform integration | **Non-alarm part implemented; not done, blocked** | Settings storage, weather, Today, night mode and brightness, first-run (folder step), pause/resume, alarm-tone choice | Tests in CI run 38873615983. **Blocked:** the alarm path waits for M1. D2 is open. The §9 device checklist hasn't been run (no device). |
| M5–M6 | Not started | — | D4 rest (license, app ID, final icon); M4 |
