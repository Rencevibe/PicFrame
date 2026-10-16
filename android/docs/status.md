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
| M2 Domain port | **Not accepted** | Kotlin `domain/` | Implemented ✔ · CI-verified ✔ · device-verified n/a · PO-accepted ✘. 87 of 91 Python cases have passing equivalents. Four don't: 2 adapted (first-run warning and folder check; `todo_path` per D3) and 2 directory-fsync cases (one substituted, one with no equivalent). The PO must accept or direct equivalents (`m2-domain.md`). |
| M3 Photos and UI | **Not done** | Photo source, `ImageCache`, slideshow, theme, canvas, all screens, Frame page, accessibility overlay | Implemented ✔ · CI-verified ✔ (screenshots within tolerance for all 8 mockups) · device-verified ✘ · designer/PO-accepted ✘. **Open:** the layout report must be *measured* on a phone and a tablet (now only calculated); designer review; D2 (`m3-m4.md`). |
| M4 Platform integration | **Not done; alarm path not started** | Non-alarm part only (PO authorization of 2026-10-16) | Implemented ✔ · CI-verified ✔ · device-verified ✘ · accepted ✘. **Gates:** M1 signed Go/fallback; D2; the §9 device checklist by QA. |
| M5–M6 | Not started; must not start | — | Blocked by M1, device validation, M4, and D4 rest (license, app ID, final icon). |

## Latest CI evidence

https://github.com/Rencevibe/PicFrame/actions/runs/38905505215 (commit `7ca32df`, 2026-10-16): `lintDebug testDebugUnitTest assembleDebug` passed from a clean checkout; 155 tests run, 0 failed, 0 skipped. No local build is possible here: Google Maven and Maven Central are blocked, and there is no Android SDK.
