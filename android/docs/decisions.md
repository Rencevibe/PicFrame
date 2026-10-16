# Decision log (plan §8)

The product owner decides these, not the Android lead. Plan: `android/docs/plan.md`.

| ID | Decision | Choice | Decided by | Date | Reason given |
|---|---|---|---|---|---|
| D1 | Distribution | **Sideload APK first.** Play deferred to follow-up. | Product owner | 2026-10-16 | Took the plan's recommended default. |
| D2 | Devices: tablets only, or phones too | *Open.* Needed by the end of M3, informed by the layout report. | — | — | — |
| D3 | Photo and to-do source | **Picked folder** (Storage Access Framework); `todo.toml` is read from the same folder. | Product owner | 2026-10-16 | Took the plan's recommended default. |
| D4 (assets, partial) | Launcher icon, alarm sound, UI icons (G2) | **Launcher icon:** an original vector icon drawn for the project, used until the PO supplies the final icon. **Alarm sound:** the device's default alarm tone, plus a Settings option to choose a different system alarm tone. `assets/alarm.wav` is not used. **UI icons:** redrawn as original vector drawables; the 44 PNGs are not used. | Product owner | 2026-10-16 | Chose the plan defaults, plus a request to make the alarm sound changeable. |
| D4 (rest) | License (G1), final app ID, final launcher icon | *Open.* Needed before M5. | — | — | — |

## What D1 and D3 mean for later milestones

- **D1 = sideload:**
  - M1 pass condition 4 is judged against platform rules for sideloaded installs, not Play policy.
  - The plan §6 Play row and the Data safety form are not part of launch.
- **D3 = picked folder:**
  - No storage permission.
  - V4 (the `READ_MEDIA_IMAGES` policy) is not needed.

## Milestone authorizations

| Date | By | Authorization |
|---|---|---|
| 2026-10-16 | Product owner | **M4 starts before its gates are met, limited to non-alarm work:** weather, Today, night mode and brightness, first-run setup, and lifecycle. The production alarm path (AlarmManager, receivers, ringing service, lock-screen alarm) waits for the M1 result. M4 cannot be marked done until the M1 result is in and the §9 device checklist has been run. |
