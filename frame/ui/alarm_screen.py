"""Alarm screen (spec "Alarm"): pulsing ring, big time, primary Snooze, smaller Dismiss.

vm: time "6:30", ampm "AM" | None, date, snooze_min int, is_test bool, pressed action | None.
pulse: 0..1 within the 1 s cycle (ring opacity 40–100 %)."""
from __future__ import annotations

import pygame

from frame.ui import theme
from frame.ui.widgets import Button

SNOOZE = pygame.Rect(400, 440, 480, 144)
DISMISS = pygame.Rect(520, 640, 240, 120)


def _mix(fg, bg, a: float):
    return tuple(round(f * a + b * (1 - a)) for f, b in zip(fg, bg))


def render(surf: pygame.Surface, vm: dict, pulse: float) -> list[Button]:
    surf.fill(theme.BG)
    w, h = surf.get_size()
    # Ring on a solid background: blend the color instead of allocating an alpha layer each frame
    ring = _mix(theme.ALARM, theme.BG, 0.4 + 0.6 * max(0.0, min(1.0, pulse)))
    pygame.draw.rect(surf, ring, (0, 0, w, h), width=6, border_radius=18)

    caption = "TEST ALARM" if vm.get("is_test") else "ALARM"
    theme.text(surf, caption, theme.font("inter-semibold", 24), theme.ALARM, (w / 2, 120), "mm", tracking=3)

    big = theme.font("inter-semibold", 160)
    clock = theme.digits(vm["time"], big, theme.TEXT)
    ampm_f = theme.font("inter-medium", 40)
    ampm_w = ampm_f.size(vm["ampm"])[0] + 16 if vm.get("ampm") else 0
    x = (w - clock.get_width() - ampm_w) // 2
    baseline = 312
    surf.blit(clock, (x, baseline - big.get_ascent()))
    if vm.get("ampm"):
        theme.text(surf, vm["ampm"], ampm_f, theme.SECONDARY, (x + clock.get_width() + 16, baseline), "ls")
    theme.text(surf, vm["date"], theme.font("ui", 28), theme.SECONDARY, (w / 2, 362), "mm")

    snooze = Button(SNOOZE, f"Snooze {vm['snooze_min']} min", action="snooze", style="accent",
                    font=("inter-semibold", 36), radius=24)
    dismiss = Button(DISMISS, "Dismiss", action="dismiss", style="outline-raised",
                     font=("inter-semibold", 32), radius=24)
    for b in (snooze, dismiss):
        b.draw(surf, vm.get("pressed") == b.action)
    return [snooze, dismiss]
