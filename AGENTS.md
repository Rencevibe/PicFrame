# Pi Picture Frame — agent instructions

Build Phase 1 of a Raspberry Pi 3 touchscreen photo frame.

- Spec (what to build): `docs/superpowers/specs/2026-10-14-pi-picture-frame-design.md`
- Plan (how to build it): `docs/superpowers/plans/2026-10-14-pi-picture-frame.md`
- Screen designs: `docs/design/*.png` at 1280×800 (Dashboard + snoozed/empty states, Photo, Alarm, Night clock, Settings)

## How to work
- Implement the plan one task at a time, in order. Ignore the plan header's mention of "superpowers" sub-skills; just follow the task steps.
- Test first: write the task's tests, run them and see them fail, implement, run them and see them pass.
- Run `pytest -q` before finishing each task; commit after each task with the message given in the plan.
- After each task, stop and give a 3-line summary: what was done, test result, anything that needs a decision.
- Follow the plan's Global Constraints exactly. Don't add dependencies, features or files not in the plan.
- Logic modules must not import pygame. UI render tests run with `SDL_VIDEODRIVER=dummy`.
- If the spec and plan disagree, the spec wins; note the conflict in your summary.
- Tasks 11–12 run on the real Pi; prepare the files and list the manual checks, don't pretend to run them.
