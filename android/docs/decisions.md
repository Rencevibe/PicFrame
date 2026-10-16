# Decision log (plan §8)

The product owner decides these, not the Android lead. Plan: `android/docs/plan.md`.

| ID | Decision | Choice | Decided by | Date | Reason given |
|---|---|---|---|---|---|
| D1 | Distribution | **Sideload APK first.** Play deferred to follow-up. | Product owner | 2026-10-16 | Took the plan's recommended default. |
| D2 | Devices: tablets only, or phones too | *Open.* Needed by the end of M3, informed by the layout report. | — | — | — |
| D3 | Photo and to-do source | **Picked folder** (Storage Access Framework); `todo.toml` is read from the same folder. | Product owner | 2026-10-16 | Took the plan's recommended default. |
| D4 | License, icon and `alarm.wav` provenance, app ID, launcher icon | *Open.* The asset ruling is needed before the M3 icon work; everything else before M5. | — | — | — |

## What D1 and D3 mean for later milestones

- **D1 = sideload:**
  - M1 pass condition 4 is judged against platform rules for sideloaded installs, not Play policy.
  - The plan §6 Play row and the Data safety form are not part of launch.
- **D3 = picked folder:**
  - No storage permission.
  - V4 (the `READ_MEDIA_IMAGES` policy) is not needed.
