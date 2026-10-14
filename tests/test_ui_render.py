import os

os.environ.setdefault("SDL_VIDEODRIVER", "dummy")
os.environ.setdefault("SDL_AUDIODRIVER", "dummy")

from datetime import date, datetime
from pathlib import Path

import pygame
import pytest

from frame.ui import theme
from frame.ui.dashboard import month_grid, render
from frame.ui.widgets import Button, DayChip, Segmented, Stepper, Toggle
from frame.weather import Weather

OUT = Path(__file__).parent / "out"


@pytest.fixture(scope="module", autouse=True)
def pg():
    pygame.init()
    OUT.mkdir(exist_ok=True)
    yield
    pygame.quit()


# --- Step 0: fonts and contrast ---

def test_bundled_fonts_load():
    default_w = pygame.font.Font(None, 24).size("Hyperlegible 0123")[0]
    for name, path in theme.FONT_FILES.items():
        assert path.is_file(), path
        assert path.parent == theme.ASSETS / "fonts"
        f = theme.font(name, 24)
        assert isinstance(f, pygame.font.Font)
        assert f.size("Hyperlegible 0123")[0] != default_w  # not pygame's built-in font
    assert (theme.ASSETS / "fonts" / "OFL-Inter.txt").is_file()
    assert (theme.ASSETS / "fonts" / "OFL-AtkinsonHyperlegible.txt").is_file()
    assert theme.font("ui", 24) is theme.font("ui", 24)  # cached


def test_theme_contrast():
    assert theme.contrast("#000000", "#FFFFFF") == pytest.approx(21.0)
    for fg, bg, what in theme.TEXT_PAIRS:
        assert theme.contrast(fg, bg) >= 4.5, what
    for fg, bg, what in theme.ICON_PAIRS:
        assert theme.contrast(fg, bg) >= 3.0, what


def test_palette_matches_spec():
    assert theme.BG == theme.rgb("#0E1726") and theme.ACCENT == theme.rgb("#43D9C3")
    assert theme.ALARM == theme.rgb("#FF766F") and theme.NIGHT == theme.rgb("#929DAA")


def test_icons_exist_in_both_sizes():
    for name in theme.WEATHER_ICONS + theme.UI_ICONS:
        for size in (32, 48):
            icon = theme.icon(name, size)
            assert icon.get_size() == (size, size), name


def test_digits_fixed_width():
    f = theme.font("inter-semibold", 96)
    assert theme.digits("11:11", f, theme.TEXT).get_width() == theme.digits("00:00", f, theme.TEXT).get_width()


# --- widgets ---

def test_widget_hit_areas_at_least_64():
    toggle = Toggle(pygame.Rect(400, 100, 88, 48), True, "fade_toggle")
    assert toggle.hit_rect.width >= 64 and toggle.hit_rect.height >= 64
    assert toggle.hit((toggle.hit_rect.left + 2, toggle.hit_rect.top + 2)) == "fade_toggle"
    assert toggle.hit((0, 0)) is None
    tiny = Button(pygame.Rect(10, 10, 20, 20), "x", action="tiny")
    assert tiny.hit_rect.width >= 64 and tiny.hit_rect.height >= 64
    stepper = Stepper(pygame.Rect(100, 300, 272, 64), "9 min", "snooze_minus", "snooze_plus")
    assert stepper.hit((110, 330)) == "snooze_minus" and stepper.hit((360, 330)) == "snooze_plus"
    assert stepper.hit((236, 330)) is None
    off = Stepper(pygame.Rect(100, 400, 272, 64), "100 %", "b_minus", "b_plus", plus_enabled=False)
    assert off.hit((360, 430)) is None  # disabled buttons ignore taps
    seg = Segmented(pygame.Rect(0, 500, 192, 64), [("12 h", "clock_12"), ("24 h", "clock_24")], 0)
    assert seg.hit((20, 530)) == "clock_12" and seg.hit((180, 530)) == "clock_24"
    chip = DayChip(pygame.Rect(0, 600, 64, 64), "Mo", True, "day_0")
    assert chip.hit((32, 632)) == "day_0"
    surf = pygame.Surface((800, 700))
    for w in (toggle, tiny, stepper, off, seg, chip):
        w.draw(surf)
        w.draw(surf, pressed=True)


# --- dashboard ---

def test_month_grid():
    g = month_grid(date(2026, 10, 14), "sunday")
    assert g["weekdays"] == ["Su", "Mo", "Tu", "We", "Th", "Fr", "Sa"]
    assert len(g["cells"]) == 42 and g["cells"][4] == 1 and g["cells"][3] is None
    assert g["cells"][34] == 31 and g["today"] == 14
    m = month_grid(date(2026, 10, 14), "monday")
    assert m["weekdays"][0] == "Mo" and m["cells"][3] == 1


def landscape_photo():
    s = pygame.Surface((664, 600))
    for y in range(600):
        t = y / 600
        s.fill((int(245 - 120 * t), int(170 - 40 * t), int(130 + 20 * t)), (0, y, 664, 1))
    pygame.draw.circle(s, (250, 210, 160), (450, 260), 50)
    pygame.draw.polygon(s, (40, 70, 80), [(0, 600), (0, 380), (200, 330), (420, 400), (664, 340), (664, 600)])
    return s


def base_vm(**over):
    vm = dict(
        time="12:41", ampm="PM", date="Wednesday, October 14",
        today_items=[dict(time="1:30 PM", kind="appointment", title="Dentist — Dr. Okafor"),
                     dict(time="4:00 PM", kind="todo", title="Pick up dry cleaning before they close"),
                     dict(time="", kind="todo", title="Water the plants")],
        more=2, month_cells=month_grid(date(2026, 10, 14), "sunday"),
        alarm_text="6:30 AM", alarm_days="Mon–Fri", alarm_on=True, alarm_state="idle",
        snooze_until=None, countdown="in 17h 49m",
        weather=Weather(64, 2, "partly-cloudy", "Partly cloudy", 72, 55, datetime(2026, 10, 14, 12, 30)),
        weather_as_of=None, has_location=True, photo=landscape_photo(), photo_dir="/home/pi/frame/photos",
        banner=None, pressed=None,
    )
    vm.update(over)
    return vm


STATES = {
    "normal": {},
    "weather-none": dict(weather=None),
    "weather-stale": dict(weather_as_of="10:12"),
    "no-location": dict(weather=None, has_location=False),
    "photo-none": dict(photo=None),
    "empty-today": dict(today_items=[], more=0, time="21:05", ampm=None),
    "snoozed": dict(alarm_state="snoozed", snooze_until="6:39"),
    "alarm-off": dict(alarm_on=False, countdown=""),
    "missed-banner": dict(banner=dict(text="Missed alarm 6:30 AM", kind="missed", more=1)),
    "warning-banner": dict(banner=dict(text="config.toml has errors — using defaults", kind="warning", more=0)),
    "pressed": dict(pressed="open_settings"),
}


def rects(buttons):
    return {b.action: tuple(b.rect) for b in buttons}


@pytest.mark.parametrize("state", STATES)
def test_dashboard_renders_all_states(state):
    surf = pygame.Surface((1280, 800))
    vm = base_vm(**STATES[state])
    r = rects(render(surf, vm))
    assert r["to_photo"] == (1160, 56, 64, 64)
    if state == "snoozed":
        assert r["open_settings"] == (56, 672, 336, 72) and r["stop_snooze"] == (416, 672, 72, 72)
    else:
        assert r["open_settings"] == (56, 672, 432, 72) and "stop_snooze" not in r
    assert ("clear_banner" in r) == (state == "missed-banner")
    if state == "missed-banner":
        assert r["clear_banner"] == (576, 664, 632, 64)
    assert surf.get_at((5, 5))[:3] == theme.BG  # screen margin
    assert surf.get_at((40, 400))[:3] == theme.PANEL
    pygame.image.save(surf, str(OUT / f"dashboard-{state}.png"))
