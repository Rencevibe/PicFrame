# Milestone status (plan §7)

Baseline: `claude/new-session-sonjj7` @ `256419c`. Work branches: `android/m0-setup`, then `android/m1-alarm-spike` (built on top of it).

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
| M2 Domain port | Not started. Unblocked by M0; M1 and M2 can run in parallel. | — | — |
| M3–M6 | Not started | — | D2 and D4 still open; M4 waits on the M1 result |
