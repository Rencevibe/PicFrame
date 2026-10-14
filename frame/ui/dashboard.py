"""Dashboard screen (spec "Dashboard"). render() draws from a plain view-model dict.

vm keys (all text pre-formatted by the controller):
  time "12:41", ampm "PM" | None (24 h), date "Wednesday, October 14"
  today_items [{time: "1:30 PM" | "", kind: "appointment" | "todo", title}], more int
  month_cells month_grid(...) result
  alarm_text "6:30 AM", alarm_days "Mon–Fri", alarm_on bool, alarm_state "idle"|"ringing"|"snoozed",
  snooze_until "6:39" | None, countdown "in 17h 49m" | ""
  weather Weather | None, weather_as_of "10:12" | None (set once older than 1 h),
  has_location bool (default True), photo Surface (664×600) | None, photo_dir str
  banner {text, kind: "missed" | "warning", more: int} | None
  pressed action id currently under the finger | None
"""
from __future__ import annotations

import calendar
from datetime import date

import pygame

from frame.ui import theme
from frame.ui.widgets import Button, aa_circle

LEFT = pygame.Rect(32, 32, 480, 736)
RIGHT = pygame.Rect(536, 32, 712, 736)
STRIP = pygame.Rect(560, 56, 576, 64)
TO_PHOTO = pygame.Rect(1160, 56, 64, 64)
PHOTO = pygame.Rect(560, 144, 664, 600)
BANNER = pygame.Rect(576, 664, 632, 64)
ALARM_ROW = pygame.Rect(56, 672, 432, 72)
ALARM_ROW_SNOOZED = pygame.Rect(56, 672, 336, 72)
STOP_SNOOZE = pygame.Rect(416, 672, 72, 72)
X0, X1 = 56, 488  # left panel content
WEEKDAYS = ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"]


def month_grid(today: date, week_start: str) -> dict:
    """6×7 calendar cells (day number or None), weekday labels in display order, today's day."""
    first = 6 if week_start == "sunday" else 0  # calendar module: Mon=0
    weeks = calendar.Calendar(firstweekday=first).monthdayscalendar(today.year, today.month)
    cells = [d or None for week in weeks for d in week]
    cells += [None] * (42 - len(cells))
    return {"weekdays": WEEKDAYS[first:] + WEEKDAYS[:first], "cells": cells, "today": today.day}


def render(surf: pygame.Surface, vm: dict) -> list[Button]:
    pressed = vm.get("pressed")
    surf.fill(theme.BG)
    pygame.draw.rect(surf, theme.PANEL, LEFT, border_radius=24)
    pygame.draw.rect(surf, theme.PANEL, RIGHT, border_radius=24)
    buttons = []

    _clock(surf, vm)
    _today(surf, vm)
    _calendar(surf, vm["month_cells"])
    buttons += _alarm_row(surf, vm, pressed)
    _weather(surf, vm)

    to_photo = Button(TO_PHOTO, icon="expand", action="to_photo")
    to_photo.draw(surf, pressed == "to_photo")
    buttons.append(to_photo)

    _photo(surf, vm)
    if vm.get("banner"):
        buttons += _banner(surf, vm["banner"], pressed)
    return buttons


def _clock(surf, vm):
    big = theme.font("inter-semibold", 96)
    baseline = 142
    img = theme.digits(vm["time"], big, theme.TEXT)
    surf.blit(img, (X0, baseline - big.get_ascent()))
    if vm.get("ampm"):
        theme.text(surf, vm["ampm"], theme.font("inter-medium", 28), theme.SECONDARY,
                   (X0 + img.get_width() + 12, baseline), "ls")
    theme.text(surf, theme.fit(vm["date"], theme.font("ui", 28), X1 - X0), theme.font("ui", 28),
               theme.TEXT, (X0, 196), "ls")


def _today(surf, vm):
    theme.text(surf, "TODAY", theme.font("inter-semibold", 18), theme.SECONDARY, (X0, 246), "ls", tracking=1.5)
    items = vm["today_items"][:3]
    if not items:
        theme.text(surf, "Nothing else today", theme.font("ui", 24), theme.SECONDARY, (X0, 282), "lm")
    title_font = theme.font("ui", 24)
    for i, item in enumerate(items):
        cy = 260 + i * 44 + 22
        if item.get("time"):
            theme.text(surf, item["time"], theme.font("inter-medium", 22), theme.SECONDARY, (X0, cy), "lm")
        if item["kind"] == "appointment":
            aa_circle(surf, theme.ACCENT, (168, cy), 8)
        else:
            pygame.draw.rect(surf, theme.HIGHLIGHT, (160, cy - 8, 16, 16), width=2, border_radius=4)
        theme.text(surf, theme.fit(item["title"], title_font, X1 - 188), title_font, theme.TEXT, (188, cy), "lm")
    if vm.get("more"):
        theme.text(surf, f"+{vm['more']} more", theme.font("ui", 20), theme.SECONDARY, (X0, 404), "lm")


def _calendar(surf, grid):
    col = (X1 - X0) / 7
    for i, wd in enumerate(grid["weekdays"]):
        theme.text(surf, wd, theme.font("inter-medium", 18), theme.SECONDARY, (X0 + col * (i + 0.5), 454), "mm")
    for idx, day in enumerate(grid["cells"]):
        if day is None:
            continue
        r, c = divmod(idx, 7)
        center = (X0 + col * (c + 0.5), 468 + r * 30 + 15)
        if day == grid["today"]:
            aa_circle(surf, theme.ACCENT, center, 16)
            theme.text(surf, str(day), theme.font("inter-semibold", 20), theme.BG, center, "mm")
        else:
            theme.text(surf, str(day), theme.font("inter", 20), theme.TEXT, center, "mm")


def _split_ampm(s: str) -> tuple[str, str]:
    head, _, tail = s.rpartition(" ")
    return (head, tail) if tail in ("AM", "PM") else (s, "")


def _alarm_row(surf, vm, pressed) -> list[Button]:
    cy = ALARM_ROW.centery
    if vm["alarm_state"] == "snoozed":
        row = Button(ALARM_ROW_SNOOZED, action="open_settings")
        row.draw(surf, pressed == "open_settings")
        surf.blit(theme.icon("bell", 32, theme.ALARM), (68, cy - 16))
        label = f"Snoozed · rings {vm['snooze_until']}"
        f = theme.font("inter-semibold", 22)
        theme.text(surf, theme.fit(label, f, ALARM_ROW_SNOOZED.right - 44 - 108), f, theme.TEXT, (108, cy), "lm")
        surf.blit(theme.icon("chevron-forward", 32, theme.SECONDARY), (ALARM_ROW_SNOOZED.right - 42, cy - 16))
        stop = Button(STOP_SNOOZE, icon="close", action="stop_snooze")
        stop.draw(surf, pressed == "stop_snooze")
        return [row, stop]

    row = Button(ALARM_ROW, action="open_settings")
    row.draw(surf, pressed == "open_settings")
    surf.blit(theme.icon("chevron-forward", 32, theme.SECONDARY), (ALARM_ROW.right - 42, cy - 16))
    if not vm["alarm_on"]:
        surf.blit(theme.icon("bell", 32, theme.SECONDARY), (68, cy - 16))
        r = theme.text(surf, "Alarm off", theme.font("inter-semibold", 24), theme.SECONDARY, (112, cy), "lm")
        aa_circle(surf, theme.SECONDARY, (r.right + 16, cy), 6, width=2)
        return [row]

    surf.blit(theme.icon("bell", 32, theme.TEXT), (68, cy - 16))
    hhmm, ampm = _split_ampm(vm["alarm_text"])
    r = theme.text(surf, hhmm, theme.font("inter-semibold", 28), theme.TEXT, (112, cy), "lm")
    if ampm:
        r = theme.text(surf, ampm, theme.font("inter-medium", 18), theme.SECONDARY, (r.right + 6, cy + 2), "lm")
    right = ALARM_ROW.right - 52
    if vm.get("countdown"):
        c = theme.text(surf, vm["countdown"], theme.font("ui", 20), theme.SECONDARY, (right, cy), "rm")
        right = c.left
    days_font = theme.font("ui", 20)
    days = theme.fit(vm["alarm_days"], days_font, max(0, right - 16 - 22 - (r.right + 14)))
    d = theme.text(surf, days, days_font, theme.SECONDARY, (r.right + 14, cy), "lm")
    aa_circle(surf, theme.ACCENT, (d.right + 16, cy), 6)
    return [row]


def _weather(surf, vm):
    cy = STRIP.centery
    w = vm.get("weather")
    if w is None:
        surf.blit(theme.icon("unknown", 48, theme.SECONDARY), (STRIP.left, cy - 24))
        msg = "Weather unavailable" if vm.get("has_location", True) else "Set location in config.toml"
        theme.text(surf, msg, theme.font("ui", 22), theme.SECONDARY, (STRIP.left + 64, cy), "lm")
        return
    surf.blit(theme.icon(w.icon, 48), (STRIP.left, cy - 24))
    t = theme.text(surf, f"{w.temp}°", theme.font("inter-semibold", 32), theme.TEXT, (STRIP.left + 64, cy), "lm")
    hl = theme.text(surf, f"H {w.high}°  L {w.low}°", theme.font("inter-medium", 22), theme.SECONDARY,
                    (STRIP.right, cy), "rm")
    label = w.label + (f" · as of {vm['weather_as_of']}" if vm.get("weather_as_of") else "")
    f = theme.font("ui", 22)
    theme.text(surf, theme.fit(label, f, hl.left - 16 - (t.right + 14)), f, theme.TEXT, (t.right + 14, cy), "lm")


def _rounded(img: pygame.Surface, radius: int) -> pygame.Surface:
    out = img.convert_alpha() if pygame.display.get_init() and pygame.display.get_surface() else img.copy()
    if out.get_flags() & pygame.SRCALPHA == 0:
        tmp = pygame.Surface(out.get_size(), pygame.SRCALPHA)
        tmp.blit(out, (0, 0))
        out = tmp
    mask = pygame.Surface(out.get_size(), pygame.SRCALPHA)
    pygame.draw.rect(mask, (255, 255, 255, 255), mask.get_rect(), border_radius=radius)
    out.blit(mask, (0, 0), special_flags=pygame.BLEND_RGBA_MIN)
    return out


def _photo(surf, vm):
    photo = vm.get("photo")
    if photo is not None:
        surf.blit(_rounded(photo, 16), PHOTO.topleft)
        return
    pygame.draw.rect(surf, theme.BG, PHOTO, border_radius=16)
    cx = PHOTO.centerx
    surf.blit(theme.icon("photo", 48, theme.SECONDARY), (cx - 24, 356))
    theme.text(surf, "No photos yet", theme.font("ui", 28), theme.TEXT, (cx, 440), "mm")
    theme.text(surf, theme.fit(vm["photo_dir"], theme.font("inter", 20), PHOTO.width - 48),
               theme.font("inter", 20), theme.SECONDARY, (cx, 480), "mm")
    theme.text(surf, "Add photos with rsync over SSH", theme.font("ui", 20), theme.SECONDARY, (cx, 512), "mm")


def _banner(surf, banner, pressed) -> list[Button]:
    r = BANNER
    shadow = pygame.Surface((r.width + 16, r.height + 16), pygame.SRCALPHA)
    for i in range(8, 0, -2):  # soft shadow
        pygame.draw.rect(shadow, (0, 0, 0, 14), shadow.get_rect().inflate(-i * 2 + 2, -i * 2 + 2), border_radius=24)
    surf.blit(shadow, (r.left - 8, r.top - 6))
    card = pygame.Surface(r.size, pygame.SRCALPHA)
    missed = banner.get("kind") == "missed"
    fill = theme.PRESSED if (missed and pressed == "clear_banner") else theme.BG
    pygame.draw.rect(card, (*fill, 230), card.get_rect(), border_radius=16)
    pygame.draw.rect(card, theme.ALARM if missed else theme.HIGHLIGHT, (0, 0, 6, r.height), border_radius=3)
    surf.blit(card, r.topleft)
    label = banner["text"] + (f" · {banner['more']} more" if banner.get("more") else "")
    f = theme.font("ui", 22)
    room = r.width - 26 - (72 if missed else 24)
    theme.text(surf, theme.fit(label, f, room), f, theme.TEXT, (r.left + 26, r.centery), "lm")
    if not missed:
        return []
    surf.blit(theme.icon("close", 48, theme.SECONDARY), (r.right - 56, r.centery - 24))
    return [Button(r, action="clear_banner", style="plain")]
