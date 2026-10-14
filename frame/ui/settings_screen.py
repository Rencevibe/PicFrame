"""Settings screen (spec "Settings", "Saving and recovery"). Draws cfg; the controller applies taps."""
from __future__ import annotations

from datetime import time

import pygame

from frame.config import Config
from frame.ui import theme
from frame.ui.widgets import Button, DayChip, Segmented, Stepper, Toggle

CARD_W, CARD_H, CARD_Y = 596, 648, 120
ALARM_X, DISPLAY_X = 32, 652
ROW_Y = CARD_Y + 64  # first row
ROW_H, REPEAT_H = 80, 128
STEPPER_W, TOGGLE_W, SEGMENT_W, TEST_W, DAYS_W = 272, 104, 192, 200, 544

INTERVAL_STEPS = [10, 15, 30, 60, 120, 300, 600, 1800]
SNOOZE_RANGE = (1, 30)
BRIGHTNESS_RANGE = (10, 100)
DAY_LABELS = ["Mo", "Tu", "We", "Th", "Fr", "Sa", "Su"]  # index = weekday, Mon=0

# (label, control width) per row, top to bottom; Repeat's chips sit below its label
ALARM_ROWS = [("Alarm", TOGGLE_W), ("Hour", STEPPER_W), ("Minute", STEPPER_W), ("Repeat", 0),
              ("Snooze", STEPPER_W), ("Test alarm", TEST_W)]
DISPLAY_ROWS = [("Photo interval", STEPPER_W), ("Fade", TOGGLE_W), ("Clock", SEGMENT_W),
                ("Night mode", TOGGLE_W), ("Night starts", STEPPER_W), ("Night ends", STEPPER_W),
                ("Brightness", STEPPER_W)]


def fmt_hour(h: int, clock_24h: bool) -> str:
    return f"{h:02d}" if clock_24h else f"{h % 12 or 12} {'AM' if h < 12 else 'PM'}"


def fmt_clock(t: time, clock_24h: bool) -> str:
    if clock_24h:
        return f"{t.hour:02d}:{t.minute:02d}"
    return f"{t.hour % 12 or 12}:{t.minute:02d} {'AM' if t.hour < 12 else 'PM'}"


def fmt_interval(s: int) -> str:
    return f"{s} s" if s < 60 else f"{s // 60} min" if s % 60 == 0 else f"{s // 60} min {s % 60} s"


def render(surf: pygame.Surface, cfg: Config, brightness_supported: bool,
           save_error: str | None = None, pressed: str | None = None) -> list:
    surf.fill(theme.BG)
    out: list = []
    _header(surf, save_error, pressed, out)
    _card(surf, ALARM_X, "Alarm")
    _card(surf, DISPLAY_X, "Display")
    _alarm_card(surf, cfg, pressed, out)
    _display_card(surf, cfg, brightness_supported, pressed, out)
    return out


def _header(surf, save_error, pressed, out):
    theme.text(surf, "Settings", theme.font("inter-semibold", 32), theme.TEXT, (32, 74), "ls")
    if save_error:
        strip = pygame.Rect(200, 32, 696, 64)
        pygame.draw.rect(surf, theme.PANEL, strip, border_radius=16)
        pygame.draw.rect(surf, theme.HIGHLIGHT, (strip.left, strip.top, 6, strip.height), border_radius=3)
        f1, f2 = theme.font("ui", 20), theme.font("ui", 18)
        msg = theme.fit(f"Couldn't save settings: {save_error}.", f1, strip.width - 48)
        theme.text(surf, msg, f1, theme.TEXT, (strip.left + 24, strip.top + 20), "lm")
        theme.text(surf, "Your previous settings are still on disk.", f2, theme.SECONDARY,
                   (strip.left + 24, strip.top + 46), "lm")
        keep = Button((912, 32, 160, 64), "Keep for now", action="keep_unsaved", font=("ui", 20))
        keep.draw(surf, pressed == "keep_unsaved")
        out.append(keep)
    else:
        theme.text(surf, "Saved when you tap Done", theme.font("ui", 18), theme.SECONDARY, (32, 100), "ls")
    done = Button((1088, 32, 160, 64), "Retry" if save_error else "Done", action="done", style="accent",
                  font=("inter-semibold", 24))
    done.draw(surf, pressed == "done")
    out.append(done)


def _card(surf, x, title):
    pygame.draw.rect(surf, theme.PANEL, (x, CARD_Y, CARD_W, CARD_H), border_radius=24)
    theme.text(surf, title, theme.font("inter-semibold", 28), theme.TEXT, (x + 24, CARD_Y + 52), "ls")


def _rows(surf, x, rows):
    """Draw labels + dividers; yield (control right edge, control top) per row."""
    y = ROW_Y
    label_font = theme.font("ui", 24)
    for i, (label, _) in enumerate(rows):
        h = REPEAT_H if label == "Repeat" else ROW_H
        if i:
            pygame.draw.line(surf, theme.DIVIDER, (x + 24, y), (x + CARD_W - 25, y))
        label_y = y + 28 if label == "Repeat" else y + h // 2
        theme.text(surf, label, label_font, theme.TEXT, (x + 24, label_y), "lm")
        yield x + CARD_W - 24, (y + 56 if label == "Repeat" else y + 8)
        y += h


def _stepper(surf, right, top, value, minus, plus, pressed, out, lo_ok=True, hi_ok=True, enabled=True):
    st = Stepper((right - STEPPER_W, top, STEPPER_W, 64), value, minus, plus,
                 minus_enabled=enabled and lo_ok, plus_enabled=enabled and hi_ok, value_enabled=enabled)
    st.draw(surf, pressed)
    out += [st.minus, st.plus]


def _toggle(surf, right, top, on, action, pressed, out):
    t = Toggle((right - 96, top + 8, 88, 48), on, action)
    t.draw(surf, pressed == action)
    out.append(t)


def _alarm_card(surf, cfg, pressed, out):
    rows = _rows(surf, ALARM_X, ALARM_ROWS)
    right, top = next(rows)
    _toggle(surf, right, top, cfg.alarm_enabled, "alarm_toggle", pressed, out)
    right, top = next(rows)
    _stepper(surf, right, top, fmt_hour(cfg.alarm_time.hour, cfg.clock_24h),
             "alarm_hour_minus", "alarm_hour_plus", pressed, out)
    right, top = next(rows)
    _stepper(surf, right, top, f":{cfg.alarm_time.minute:02d}", "alarm_min_minus", "alarm_min_plus", pressed, out)
    right, top = next(rows)
    order = list(range(7)) if cfg.week_start == "monday" else [6, 0, 1, 2, 3, 4, 5]
    for i, day in enumerate(order):
        chip = DayChip((right - DAYS_W + i * 80, top, 64, 64), DAY_LABELS[day], day in cfg.alarm_days, f"day_{day}")
        chip.draw(surf, pressed == chip.action)
        out.append(chip)
    right, top = next(rows)
    lo, hi = SNOOZE_RANGE
    _stepper(surf, right, top, f"{cfg.snooze_min} min", "snooze_minus", "snooze_plus", pressed, out,
             lo_ok=cfg.snooze_min > lo, hi_ok=cfg.snooze_min < hi)
    right, top = next(rows)
    test = Button((right - TEST_W, top, TEST_W, 64), "Play 10 s", icon="bell", action="test_alarm",
                  style="outline", font=("ui", 22))
    test.draw(surf, pressed == "test_alarm")
    out.append(test)


def _display_card(surf, cfg, brightness_supported, pressed, out):
    rows = _rows(surf, DISPLAY_X, DISPLAY_ROWS if brightness_supported else DISPLAY_ROWS[:-1])
    right, top = next(rows)
    _stepper(surf, right, top, fmt_interval(cfg.interval_s), "interval_minus", "interval_plus", pressed, out,
             lo_ok=cfg.interval_s > INTERVAL_STEPS[0], hi_ok=cfg.interval_s < INTERVAL_STEPS[-1])
    right, top = next(rows)
    _toggle(surf, right, top, cfg.fade_ms > 0, "fade_toggle", pressed, out)
    right, top = next(rows)
    seg = Segmented((right - SEGMENT_W, top, SEGMENT_W, 64), [("12 h", "clock_12"), ("24 h", "clock_24")],
                    1 if cfg.clock_24h else 0)
    seg.draw(surf, pressed)
    out += seg.buttons
    right, top = next(rows)
    _toggle(surf, right, top, cfg.night_enabled, "night_toggle", pressed, out)
    for start in (True, False):
        right, top = next(rows)
        t = cfg.night_start if start else cfg.night_end
        name = "night_start" if start else "night_end"
        _stepper(surf, right, top, fmt_clock(t, cfg.clock_24h), f"{name}_minus", f"{name}_plus", pressed, out,
                 enabled=cfg.night_enabled)
    if brightness_supported:
        right, top = next(rows)
        b = getattr(cfg, "brightness", 100)  # Config gains `brightness` in Task 10
        lo, hi = BRIGHTNESS_RANGE
        _stepper(surf, right, top, f"{b} %", "brightness_minus", "brightness_plus", pressed, out,
                 lo_ok=b > lo, hi_ok=b < hi)
