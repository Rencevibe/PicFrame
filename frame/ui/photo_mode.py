"""Photo mode (spec "Photo"): fullscreen photo, corner chips, control bar on tap.

vm: photo Surface 1280×800 (cache "fit" output) | None, time, ampm | None, date "Wed, October 14",
    weather Weather | None (None hides the chip), overlay_corner, missed "Missed alarm 6:30 AM" | None,
    photo_dir, pressed | None.
hide_progress: 0 when the bar appears … 1 when it hides (the accent line shrinks to nothing)."""
from __future__ import annotations

from functools import lru_cache

import pygame

from frame.ui import theme
from frame.ui.widgets import Button

W, H, MARGIN = 1280, 800, 32
BAR = (360, 680, 560, 96)
PREV, NEXT, TO_DASH = (376, 688, 120, 80), (512, 688, 120, 80), (648, 688, 256, 80)
CLOCK_MAX_W, CLOCK_H = 320, 128
WEATHER_SIZE = (300, 80)
MISSED_MAX_W, MISSED_H = 400, 64
OPPOSITE = {"top-left": "bottom-right", "bottom-right": "top-left",
            "top-right": "bottom-left", "bottom-left": "top-right"}


def _place(corner: str, w: int, h: int) -> pygame.Rect:
    x = MARGIN if corner.endswith("left") else W - MARGIN - w
    y = MARGIN if corner.startswith("top") else H - MARGIN - h
    return pygame.Rect(x, y, w, h)


def _clock_width(vm) -> int:
    big, small = theme.font("inter-semibold", 64), theme.font("ui", 24)
    w = theme.digits(vm["time"], big, theme.TEXT).get_width()
    if vm.get("ampm"):
        w += 10 + theme.font("inter-medium", 24).size(vm["ampm"])[0]
    return min(CLOCK_MAX_W, max(w, small.size(vm["date"])[0]) + 48)


def _missed_text(vm) -> str:
    return theme.fit(vm["missed"], theme.font("ui", 22), MISSED_MAX_W - 20 - 32 - 12 - 16 - 32 - 16)


def chip_rects(vm: dict) -> dict[str, pygame.Rect]:
    """Where each corner chip goes for this vm (also used by tests)."""
    corner = vm.get("overlay_corner", "bottom-left")
    out = {"clock": _place(corner, _clock_width(vm), CLOCK_H)}
    if vm.get("weather") is not None:
        out["weather"] = _place(OPPOSITE[corner], *WEATHER_SIZE)
    if vm.get("missed"):
        free_top = next(c for c in ("top-left", "top-right") if c not in (corner, OPPOSITE[corner]))
        tw = theme.font("ui", 22).size(_missed_text(vm))[0]
        out["missed"] = _place(free_top, min(MISSED_MAX_W, 20 + 32 + 12 + tw + 16 + 32 + 16), MISSED_H)
    return out


@lru_cache(maxsize=8)
def _backing(w: int, h: int, radius: int) -> pygame.Surface:
    """bg at 80 % with a soft shadow; cached per size (spec "Text on photos")."""
    s = pygame.Surface((w + 16, h + 16), pygame.SRCALPHA)
    for i in range(8, 0, -2):
        pygame.draw.rect(s, (0, 0, 0, 14), pygame.Rect(0, 2, w + 16, h + 14).inflate(-i * 2 + 2, -i * 2 + 2),
                         border_radius=radius + 8)
    pygame.draw.rect(s, (*theme.BG, 204), (8, 8, w, h), border_radius=radius)
    return s


def _card(surf, r: pygame.Rect, radius: int):
    surf.blit(_backing(r.width, r.height, radius), (r.left - 8, r.top - 8))


def render(surf: pygame.Surface, vm: dict, controls_visible: bool, hide_progress: float) -> list[Button]:
    photo = vm.get("photo")
    if photo is not None:
        surf.blit(photo, (0, 0))
    else:
        _empty(surf, vm)
    chips = chip_rects(vm)
    buttons: list[Button] = []
    _clock_chip(surf, vm, chips["clock"])
    if "weather" in chips:
        _weather_chip(surf, vm["weather"], chips["weather"])
    if "missed" in chips:
        buttons.append(_missed_chip(surf, vm, chips["missed"]))
    if controls_visible:
        buttons += _controls(surf, vm.get("pressed"), hide_progress)
    return buttons


def _empty(surf, vm):
    surf.fill(theme.BG)
    cx = W // 2
    surf.blit(theme.icon("photo", 48, theme.SECONDARY), (cx - 24, 300))
    theme.text(surf, "No photos yet", theme.font("ui", 28), theme.TEXT, (cx, 384), "mm")
    f = theme.font("inter", 20)
    theme.text(surf, theme.fit(vm["photo_dir"], f, 800), f, theme.SECONDARY, (cx, 424), "mm")
    theme.text(surf, "Add photos with rsync over SSH", theme.font("ui", 20), theme.SECONDARY, (cx, 456), "mm")


def _clock_chip(surf, vm, r):
    _card(surf, r, 20)
    big = theme.font("inter-semibold", 64)
    img = theme.digits(vm["time"], big, theme.TEXT)
    baseline = r.top + 20 + 58
    surf.blit(img, (r.left + 24, baseline - big.get_ascent()))
    if vm.get("ampm"):
        theme.text(surf, vm["ampm"], theme.font("inter-medium", 24), theme.SECONDARY,
                   (r.left + 24 + img.get_width() + 10, baseline), "ls")
    f = theme.font("ui", 24)
    theme.text(surf, theme.fit(vm["date"], f, r.width - 48), f, theme.SECONDARY, (r.left + 24, r.top + 108), "ls")


def _weather_chip(surf, w, r):
    _card(surf, r, 20)
    surf.blit(theme.icon(w.icon, 48), (r.left + 20, r.centery - 24))
    theme.text(surf, f"{w.temp}°", theme.font("inter-semibold", 32), theme.TEXT, (r.left + 80, r.centery), "lm")
    theme.text(surf, f"H {w.high}° L {w.low}°", theme.font("inter-medium", 20), theme.SECONDARY,
               (r.right - 24, r.centery), "rm")


def _missed_chip(surf, vm, r) -> Button:
    _card(surf, r, 20)
    if vm.get("pressed") == "clear_banner":
        pygame.draw.rect(surf, theme.PRESSED, r, border_radius=20)
    surf.blit(theme.icon("bell", 32, theme.ALARM), (r.left + 20, r.centery - 16))
    theme.text(surf, _missed_text(vm), theme.font("ui", 22), theme.TEXT, (r.left + 64, r.centery), "lm")
    surf.blit(theme.icon("close", 32, theme.SECONDARY), (r.right - 48, r.centery - 16))
    return Button(r, action="clear_banner", style="plain")


def _controls(surf, pressed, hide_progress) -> list[Button]:
    bar = pygame.Rect(BAR)
    _card(surf, bar, 24)
    buttons = [Button(PREV, icon="chevron-left", action="prev"),
               Button(NEXT, icon="chevron-right", action="next"),
               Button(TO_DASH, "Dashboard", icon="dashboard", action="to_dashboard")]
    for b in buttons:
        b.draw(surf, pressed == b.action)
    left = max(0.0, min(1.0, 1.0 - hide_progress))
    full = bar.width - 32
    if left > 0:
        pygame.draw.rect(surf, theme.ACCENT, (bar.left + 16, bar.bottom - 6, round(full * left), 4), border_radius=2)
    return buttons
