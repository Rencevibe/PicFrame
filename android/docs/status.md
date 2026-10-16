# Milestone status (plan §7)

Baseline: `claude/new-session-sonjj7` @ `256419c`. Work branch: `android/m0-setup`.

## Execution checklist

| # | Action | Status | Evidence |
|---|---|---|---|
| 1 | Record D1 and D3 | Done 2026-10-16 | `android/docs/decisions.md` |
| 2 | Verification note for V1 and V5; V2 and V3 ahead of M1 | Done 2026-10-16, with provisional items | `android/docs/verification.md` |
| 3 | M0 skeleton and CI | Files done; waiting on CI | `android/`, `.github/workflows/android.yml`; CI run: *pending* |

## Milestones

| M | Status | Output | Evidence / blocker |
|---|---|---|---|
| M0 Setup | **In progress.** Gate met (D1 and D3 recorded). | Skeleton (landscape, keeps screen on, hides system bars), pinned catalog, CI workflow, verification note | Done-when needs a green CI run from a clean clone. Not built locally: Google Maven is blocked in the authoring environment. |
| M1 Alarm feasibility | Not started (depends on M0) | — | Needs a physical Android 14+ device and an older device (QA). The authoring environment has no devices. |
| M2 Domain port | Not started (depends on M0) | — | — |
| M3–M6 | Not started | — | D2 and D4 still open |
