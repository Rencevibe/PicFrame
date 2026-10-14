"""Night clock (spec "Night clock"): dim time on pure black, one way into Settings.

vm: time "2:14", ampm "AM" | None, alarm_label "Alarm 6:30 AM · Mon–Fri" | "Alarm off", pressed | None."""
from __future__ import annotations

import pygame

from frame.ui import theme
from frame.ui.widgets import Button

OPEN_SETTINGS = pygame.Rect(440, 520, 400, 72)


def render(surf: pygame.Surface, vm: dict) -> list[Button]:
    surf.fill(theme.BLACK)
    w = surf.get_width()
    big = theme.font("inter-medium", 160)
    clock = theme.digits(vm["time"], big, theme.NIGHT)
    ampm_f = theme.font("inter-medium", 40)
    ampm_w = ampm_f.size(vm["ampm"])[0] + 16 if vm.get("ampm") else 0
    x = (w - clock.get_width() - ampm_w) // 2
    baseline = 392
    surf.blit(clock, (x, baseline - big.get_ascent()))
    if vm.get("ampm"):
        theme.text(surf, vm["ampm"], ampm_f, theme.NIGHT, (x + clock.get_width() + 16, baseline), "ls")

    button = Button(OPEN_SETTINGS, action="open_settings", style="plain")
    r = OPEN_SETTINGS
    if vm.get("pressed") == "open_settings":
        pygame.draw.rect(surf, theme.NIGHT_PRESSED, r, border_radius=16)
    f = theme.font("ui", 24)
    label = theme.fit(vm["alarm_label"], f, r.width - 64)
    lw = f.size(label)[0]
    left = r.centerx - (lw + 40) // 2
    theme.text(surf, label, f, theme.NIGHT, (left, r.centery), "lm")
    surf.blit(theme.icon("chevron-forward", 32, theme.NIGHT), (left + lw + 8, r.centery - 16))
    return [button]
