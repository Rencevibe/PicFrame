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


def test_banner_close_icon_contrast_covered():
    pairs = {(tuple(fg), tuple(bg)) for fg, bg, _ in theme.ICON_PAIRS}
    assert (theme.SECONDARY, theme.PRESSED) in pairs
    assert (theme.SECONDARY, theme.BANNER_PRESSED_ON_WHITE) in pairs


def test_static_layers_cached_between_frames(monkeypatch):
    from frame.ui import dashboard
    layer_sizes = {(664, 600), (648, 80), (632, 64)}  # rounded photo, banner shadow, banner card
    made = []
    real = pygame.Surface
    surf = real((1280, 800))
    vm = base_vm(banner=dict(text="Missed alarm 6:30 AM", kind="missed", more=0))
    next_photo = landscape_photo()
    monkeypatch.setattr(dashboard.pygame, "Surface",
                        lambda *a, **k: (tuple(a[0]) in layer_sizes and made.append(a[0])) or real(*a, **k))
    render(surf, vm)
    first = len(made)
    for _ in range(5):  # same photo and banner on later frames: no new layers
        render(surf, vm)
    assert len(made) == first
    vm["photo"] = next_photo  # a new photo is rounded once, then reused
    render(surf, vm)
    after_new = len(made)
    render(surf, vm)
    assert after_new > first and len(made) == after_new


# --- Task 8: photo, alarm, night clock, settings ---

from dataclasses import replace
from datetime import time as dtime
from itertools import combinations

from frame.config import Config
from frame.ui import alarm_screen, night_clock, photo_mode, settings_screen


def gap(a, b):
    """Distance between two rects (0 if they touch or overlap)."""
    dx = max(b.left - a.right, a.left - b.right, 0)
    dy = max(b.top - a.bottom, a.top - b.bottom, 0)
    return max(dx, dy)


JOINED = {frozenset({"clock_12", "clock_24"})}  # spec: segmented control = two touching segments


def check_buttons(buttons, min_gap=16):
    for b in buttons:
        assert b.hit_rect.width >= 64 and b.hit_rect.height >= 64, b.action
    for a, b in combinations(buttons, 2):
        if frozenset({a.action, b.action}) in JOINED:
            assert not a.hit_rect.colliderect(b.hit_rect)
            continue
        assert not a.hit_rect.colliderect(b.hit_rect), (a.action, b.action)
        assert gap(a.hit_rect, b.hit_rect) >= min_gap, (a.action, b.action, gap(a.hit_rect, b.hit_rect))


def label_fits(b):
    if not b.label:
        return True
    w = theme.font(*b.font).size(b.label)[0] + (44 if b.icon else 0)
    return w <= b.rect.width - 16


def photo_vm(**over):
    full = pygame.Surface((1280, 800))
    full.fill((180, 140, 120))
    vm = dict(photo=full, time="12:41", ampm="PM", date="Wed, October 14",
              weather=Weather(64, 2, "partly-cloudy", "Partly cloudy", 72, 55, datetime(2026, 10, 14, 12, 30)),
              overlay_corner="bottom-left", missed=None, photo_dir="/home/pi/frame/photos", pressed=None)
    vm.update(over)
    return vm


@pytest.mark.parametrize("corner", ["bottom-left", "bottom-right", "top-left", "top-right"])
def test_photo_mode_controls_and_chips(corner):
    surf = pygame.Surface((1280, 800))
    vm = photo_vm(overlay_corner=corner, missed="Missed alarm 6:30 AM")
    b = photo_mode.render(surf, vm, controls_visible=True, hide_progress=0.4)
    r = rects(b)
    assert r["prev"] == (376, 688, 120, 80) and r["next"] == (512, 688, 120, 80)
    assert r["to_dashboard"] == (648, 688, 256, 80) and "clear_banner" in r
    check_buttons(b)
    chips = photo_mode.chip_rects(vm)
    bar = pygame.Rect(photo_mode.BAR)
    for name, c in chips.items():
        assert pygame.Rect(0, 0, 1280, 800).contains(c), name
        assert not c.colliderect(bar), name  # the bar never covers a corner chip
        assert c.width <= (320 if name == "clock" else 400), name
    for (n1, c1), (n2, c2) in combinations(chips.items(), 2):
        assert not c1.colliderect(c2), (n1, n2)
    assert all(label_fits(x) for x in b)
    pygame.image.save(surf, str(OUT / f"photo-{corner}.png"))


def test_photo_mode_hidden_controls_and_empty_states():
    surf = pygame.Surface((1280, 800))
    assert photo_mode.render(surf, photo_vm(), controls_visible=False, hide_progress=0) == []
    assert "weather" not in photo_mode.chip_rects(photo_vm(weather=None))
    b = photo_mode.render(surf, photo_vm(photo=None, weather=None), controls_visible=False, hide_progress=0)
    assert b == []
    pygame.image.save(surf, str(OUT / "photo-empty.png"))
    photo_mode.render(surf, photo_vm(pressed="next"), controls_visible=True, hide_progress=0.9)
    pygame.image.save(surf, str(OUT / "photo-controls-pressed.png"))


def alarm_vm(**over):
    vm = dict(time="6:30", ampm="AM", date="Thursday, October 15", snooze_min=9, is_test=False, pressed=None)
    vm.update(over)
    return vm


@pytest.mark.parametrize("state", ["ring", "test", "pressed-24h"])
def test_alarm_screen(state):
    surf = pygame.Surface((1280, 800))
    vm = {"ring": alarm_vm(), "test": alarm_vm(is_test=True),
          "pressed-24h": alarm_vm(time="06:30", ampm=None, pressed="snooze", snooze_min=30)}[state]
    b = alarm_screen.render(surf, vm, pulse=0.5)
    assert rects(b) == {"snooze": (400, 440, 480, 144), "dismiss": (520, 640, 240, 120)}
    assert [x.label for x in b] == [f"Snooze {vm['snooze_min']} min", "Dismiss"]
    check_buttons(b, min_gap=56)
    assert all(label_fits(x) for x in b)
    assert surf.get_at((640, 2))[:3] != theme.BG  # pulsing ring at the edge
    pygame.image.save(surf, str(OUT / f"alarm-{state}.png"))


def test_night_clock():
    surf = pygame.Surface((1280, 800))
    vm = dict(time="2:14", ampm="AM", alarm_label="Alarm 6:30 AM · Mon–Fri", pressed=None)
    b = night_clock.render(surf, vm)
    assert rects(b) == {"open_settings": (440, 520, 400, 72)}
    assert surf.get_at((5, 5))[:3] == (0, 0, 0)
    lit = {surf.get_at((x, y))[:3] for x in range(0, 1280, 8) for y in range(0, 800, 8)} - {(0, 0, 0)}
    assert max(max(c) for c in lit) <= max(theme.NIGHT)  # nothing brighter than the night color
    pygame.image.save(surf, str(OUT / "night-clock.png"))


SETTINGS_IDS = {"done", "alarm_toggle", "alarm_hour_minus", "alarm_hour_plus", "alarm_min_minus", "alarm_min_plus",
                *(f"day_{i}" for i in range(7)), "snooze_minus", "snooze_plus", "test_alarm",
                "interval_minus", "interval_plus", "fade_toggle", "clock_12", "clock_24", "night_toggle",
                "night_start_minus", "night_start_plus", "night_end_minus", "night_end_plus"}


def settings_cfg(**over):
    return replace(Config(), **over)


@pytest.mark.parametrize("state", ["default", "brightness", "save-error", "night-off-24h-limits"])
def test_settings_screen(state):
    surf = pygame.Surface((1280, 800))
    cfg = settings_cfg(night_enabled=False, clock_24h=True, snooze_min=1, interval_s=1800, week_start="monday") \
        if state == "night-off-24h-limits" else settings_cfg()
    b = settings_screen.render(surf, cfg, brightness_supported=(state == "brightness"),
                               save_error="Read-only file system" if state == "save-error" else None)
    ids = {x.action for x in b}
    expected = set(SETTINGS_IDS)
    if state == "brightness":
        expected |= {"brightness_minus", "brightness_plus"}
    if state == "save-error":
        expected |= {"keep_unsaved"}
    assert ids == expected
    r = rects(b)
    assert r["done"] == (1088, 32, 160, 64)
    if state == "save-error":
        assert r["keep_unsaved"] == (912, 32, 160, 64)
        assert next(x for x in b if x.action == "done").label == "Retry"
    check_buttons(b)
    assert all(label_fits(x) for x in b if isinstance(x, Button))
    if state == "night-off-24h-limits":
        disabled = {x.action for x in b if not getattr(x, "enabled", True)}
        assert {"night_start_minus", "night_start_plus", "night_end_minus", "night_end_plus",
                "snooze_minus", "interval_plus"} <= disabled
        assert "snooze_plus" not in disabled and "interval_minus" not in disabled
        assert next(x for x in b if x.action == "snooze_minus").hit((0, 0)) is None
    pygame.image.save(surf, str(OUT / f"settings-{state}.png"))


def test_labels_fit_their_boxes():
    """Every settings label and the widest possible value render inside their boxes in the real fonts."""
    label_font = theme.font("ui", 24)
    for card in (settings_screen.ALARM_ROWS, settings_screen.DISPLAY_ROWS):
        for label, control_w in card:
            room = settings_screen.CARD_W - 48 - control_w - 16
            assert label_font.size(label)[0] <= room, label
    value_font = theme.font("inter-semibold", 28)
    worst = [settings_screen.fmt_hour(h, c) for h in range(24) for c in (False, True)]
    worst += [settings_screen.fmt_clock(dtime(h, m), c) for h in range(24) for m in (0, 30) for c in (False, True)]
    worst += [settings_screen.fmt_interval(s) for s in settings_screen.INTERVAL_STEPS]
    worst += [f"{n} min" for n in range(1, 31)] + [f"{n} %" for n in range(10, 101, 10)] + [":55"]
    for v in worst:
        assert value_font.size(v)[0] <= 144 - 8, v
    day_font = theme.font("ui-bold", 20)
    for d in settings_screen.DAY_LABELS:
        assert day_font.size(d)[0] <= 64 - 16, d


def test_photo_chip_icons_contrast_covered():
    icon_pairs = {(tuple(fg), tuple(bg)) for fg, bg, _ in theme.ICON_PAIRS}
    assert (theme.ALARM, theme.CHIP_ON_WHITE) in icon_pairs  # missed-alarm bell
    assert (theme.SECONDARY, theme.CHIP_ON_WHITE) in icon_pairs  # chip close icon
    assert (theme.ALARM, theme.PRESSED) in icon_pairs and (theme.SECONDARY, theme.PRESSED) in icon_pairs


def test_digits_width_matches_render_without_allocating(monkeypatch):
    f = theme.font("inter-semibold", 64)
    expected = {s: theme.digits(s, f, theme.TEXT).get_width() for s in ("12:41", "1:05", "23:59", "7:30")}
    vm = photo_vm()
    made = []
    real = pygame.Surface
    monkeypatch.setattr(pygame, "Surface", lambda *a, **k: made.append(a) or real(*a, **k))
    for s, w in expected.items():
        assert theme.digits_width(s, f) == w
    photo_mode.chip_rects(vm)
    assert made == []


@pytest.mark.parametrize("progress,expected_w", [(0.0, 528), (0.5, 264), (1.0, 0)])
def test_photo_progress_line_width(progress, expected_w):
    surf = pygame.Surface((1280, 800))
    surf.fill((255, 255, 255))
    photo_mode.render(surf, photo_vm(photo=None), controls_visible=True, hide_progress=progress)
    y = photo_mode.BAR[1] + photo_mode.BAR[3] - 4
    teal = [x for x in range(photo_mode.BAR[0], photo_mode.BAR[0] + photo_mode.BAR[2])
            if surf.get_at((x, y))[:3] == theme.ACCENT]
    assert abs(len(teal) - expected_w) <= 2


def test_night_clock_pressed_stays_dark():
    surf = pygame.Surface((1280, 800))
    night_clock.render(surf, dict(time="2:14", ampm="AM", alarm_label="Alarm 6:30 AM · Mon–Fri",
                                  pressed="open_settings"))
    assert surf.get_at((450, 530))[:3] == theme.NIGHT_PRESSED  # pressed fill is drawn …
    lit = {surf.get_at((x, y))[:3] for x in range(0, 1280, 4) for y in range(0, 800, 4)}
    assert max(max(c) for c in lit) <= max(theme.NIGHT)  # … and nothing is brighter than night text
