"""Touch widgets. Each has draw(surf, pressed=False) and hit(pos) -> action | None.
Hit areas are at least 64×64 even when the visible shape is smaller (spec "Touch rules")."""
from __future__ import annotations

import pygame
from pygame import gfxdraw

from frame.ui import theme

MIN_HIT = 64


def hit_area(rect: pygame.Rect, min_w: int = MIN_HIT, min_h: int = MIN_HIT) -> pygame.Rect:
    r = pygame.Rect(rect)
    return r.inflate(max(0, min_w - r.width), max(0, min_h - r.height))


def aa_circle(surf, color, center, radius, width=0):
    """Anti-aliased circle; width 0 = filled, else an outline of that thickness."""
    x, y = round(center[0]), round(center[1])
    if width == 0:
        gfxdraw.filled_circle(surf, x, y, radius, color)
        gfxdraw.aacircle(surf, x, y, radius, color)
        return
    for i in range(width):
        gfxdraw.aacircle(surf, x, y, radius - i, color)


class Button:
    """style: raised | accent | outline (border only) | outline-raised | plain (no fill)."""

    def __init__(self, rect, label: str | None = None, icon: str | None = None, action: str = "",
                 style: str = "raised", enabled: bool = True, font=("ui", 24), radius: int = 16):
        self.rect = pygame.Rect(rect)
        self.label, self.icon, self.action = label, icon, action
        self.style, self.enabled, self.font, self.radius = style, enabled, font, radius
        self.hit_rect = hit_area(self.rect)

    def hit(self, pos) -> str | None:
        return self.action if self.enabled and self.hit_rect.collidepoint(pos) else None

    def colors(self, pressed: bool):
        """(fill or None, content color)."""
        if not self.enabled:
            return None, theme.SECONDARY
        if self.style == "accent":
            return (theme.ACCENT_PRESSED if pressed else theme.ACCENT), theme.BG
        if self.style in ("raised", "outline-raised"):
            return (theme.PRESSED if pressed else theme.RAISED), theme.TEXT
        return (theme.PRESSED if pressed else None), theme.TEXT

    def draw(self, surf, pressed: bool = False) -> None:
        fill, fg = self.colors(pressed)
        if fill:
            pygame.draw.rect(surf, fill, self.rect, border_radius=self.radius)
        if self.enabled and self.style in ("outline", "outline-raised"):
            pygame.draw.rect(surf, theme.TEXT, self.rect, width=2, border_radius=self.radius)
        self.draw_content(surf, fg)

    def draw_content(self, surf, fg) -> None:
        cx, cy = self.rect.center
        f = theme.font(*self.font)
        if self.icon and self.label:
            ic = theme.icon(self.icon, 32, fg)
            total = 32 + 12 + f.size(self.label)[0]
            x = cx - total // 2
            surf.blit(ic, (x, cy - 16))
            theme.text(surf, self.label, f, fg, (x + 44, cy), "lm")
        elif self.icon:
            size = 48 if min(self.rect.size) >= 64 else 32
            surf.blit(theme.icon(self.icon, size, fg), (cx - size // 2, cy - size // 2))
        elif self.label:
            theme.text(surf, self.label, f, fg, (cx, cy), "mm")


class Toggle:
    """Pill 88×48 (knob right = on) inside a 104×64 hit area."""

    def __init__(self, rect, on: bool, action: str, enabled: bool = True):
        self.rect, self.on, self.action, self.enabled = pygame.Rect(rect), on, action, enabled
        self.hit_rect = hit_area(self.rect, self.rect.width + 16)

    def hit(self, pos) -> str | None:
        return self.action if self.enabled and self.hit_rect.collidepoint(pos) else None

    def draw(self, surf, pressed: bool = False) -> None:
        r = self.rect
        if self.on:
            fill = theme.ACCENT_PRESSED if pressed else theme.ACCENT
            knob, kx = theme.BG, r.right - r.height // 2
        else:
            fill = theme.PRESSED if pressed else theme.RAISED
            knob, kx = theme.SECONDARY, r.left + r.height // 2
        pygame.draw.rect(surf, fill, r, border_radius=r.height // 2)
        aa_circle(surf, knob, (kx, r.centery), r.height // 2 - 6)


class Stepper:
    """[−] value [+]: 64×64 buttons around a value box (272×64 overall)."""

    def __init__(self, rect, value: str, minus_action: str, plus_action: str,
                 minus_enabled: bool = True, plus_enabled: bool = True, value_enabled: bool = True):
        self.rect = pygame.Rect(rect)
        self.value, self.value_enabled = value, value_enabled
        r = self.rect
        self.minus = Button((r.left, r.top, 64, 64), icon="minus", action=minus_action, enabled=minus_enabled)
        self.plus = Button((r.right - 64, r.top, 64, 64), icon="plus", action=plus_action, enabled=plus_enabled)
        self.hit_rect = r

    def hit(self, pos) -> str | None:
        return self.minus.hit(pos) or self.plus.hit(pos)

    def draw(self, surf, pressed: bool | str = False) -> None:
        """pressed: True/False, or the action id of the half being pressed."""
        self.minus.draw(surf, pressed in (True, self.minus.action))
        self.plus.draw(surf, pressed in (True, self.plus.action))
        color = theme.TEXT if self.value_enabled else theme.SECONDARY
        theme.text(surf, self.value, theme.font("inter-semibold", 28), color, self.rect.center, "mm")


class Segmented:
    """Side-by-side options; selected one in accent."""

    def __init__(self, rect, options: list[tuple[str, str]], selected: int):
        self.rect, self.options, self.selected = pygame.Rect(rect), options, selected
        w = self.rect.width // len(options)
        self.parts = [pygame.Rect(self.rect.left + i * w, self.rect.top, w, self.rect.height)
                      for i in range(len(options))]
        self.hit_rect = self.rect

    def hit(self, pos) -> str | None:
        for part, (_, action) in zip(self.parts, self.options):
            if part.collidepoint(pos):
                return action
        return None

    def draw(self, surf, pressed: bool | str = False) -> None:
        pygame.draw.rect(surf, theme.RAISED, self.rect, border_radius=16)
        for i, (part, (label, action)) in enumerate(zip(self.parts, self.options)):
            down = pressed in (True, action)
            if i == self.selected:
                pygame.draw.rect(surf, theme.ACCENT_PRESSED if down else theme.ACCENT, part, border_radius=16)
                theme.text(surf, label, theme.font("inter-semibold", 22), theme.BG, part.center, "mm")
            else:
                if down:
                    pygame.draw.rect(surf, theme.PRESSED, part, border_radius=16)
                theme.text(surf, label, theme.font("inter-medium", 22), theme.SECONDARY, part.center, "mm")


class DayChip:
    """64×64 round day toggle: on = accent fill, off = secondary outline."""

    def __init__(self, rect, label: str, on: bool, action: str):
        self.rect, self.label, self.on, self.action = pygame.Rect(rect), label, on, action
        self.hit_rect = hit_area(self.rect)

    def hit(self, pos) -> str | None:
        return self.action if self.hit_rect.collidepoint(pos) else None

    def draw(self, surf, pressed: bool = False) -> None:
        c, r = self.rect.center, self.rect.width // 2
        if self.on:
            aa_circle(surf, theme.ACCENT_PRESSED if pressed else theme.ACCENT, c, r)
            theme.text(surf, self.label, theme.font("ui-bold", 20), theme.BG, c, "mm")
        else:
            if pressed:
                aa_circle(surf, theme.PRESSED, c, r)
            aa_circle(surf, theme.SECONDARY, c, r - 1, width=2)
            theme.text(surf, self.label, theme.font("ui", 20), theme.SECONDARY, c, "mm")
