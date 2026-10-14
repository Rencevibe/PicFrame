from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo

import pytest

from frame.alarm import Alarm
from frame.config import Config
from frame.controller import Controller, days_label, fmt_clock
from frame.night import NightController

LA = ZoneInfo("America/Los_Angeles")


class Rig:
    """Controller with a fake monotonic clock that advances together with `now`."""

    def __init__(self, start: datetime, save_result=None, **cfg):
        cfg.setdefault("alarm_days", set(range(7)))
        self.cfg = Config(**cfg)
        self.alarm = Alarm(self.cfg)
        self.night = NightController(self.cfg)
        self.t = 1000.0
        self.now = start
        self.saved = []

        def save(c):
            self.saved.append(c)
            return save_result

        self.c = Controller(self.cfg, self.alarm, self.night, lambda: self.t, save=save)
        self.c.on_tick(self.now)

    def advance(self, seconds: float, step: float = 1.0):
        left = seconds
        while left > 1e-9:
            d = min(step, left)
            self.t += d
            self.now += timedelta(seconds=d)
            self.c.on_tick(self.now)
            left -= d

    def tap(self, button=None, down_ago: float = 0.1):
        self.c.on_tap(button, self.now, down_at=self.t - down_ago)


def day(h, m=0, s=0):
    return datetime(2026, 10, 14, h, m, s, tzinfo=LA)


def test_photo_tap_shows_controls_then_hides_after_5s():
    r = Rig(day(12))
    r.tap("to_photo")
    assert r.c.mode == "photo" and not r.c.controls_visible
    r.tap(None)
    assert r.c.controls_visible and r.c.hide_progress == pytest.approx(0)
    r.advance(4)
    assert r.c.controls_visible and r.c.hide_progress == pytest.approx(0.8)
    r.tap("next")  # any tap on the bar restarts the 5 s
    assert r.c.take_photo_step() == 1 and r.c.take_photo_step() == 0
    r.advance(4.5)
    assert r.c.controls_visible
    r.advance(0.5)
    assert not r.c.controls_visible and r.c.mode == "photo"


def test_only_mode_buttons_switch_mode():
    r = Rig(day(12))
    r.tap(None)
    assert r.c.mode == "dashboard"
    r.tap("to_photo")
    r.tap(None)
    r.tap("prev")
    assert r.c.mode == "photo" and r.c.take_photo_step() == -1
    r.tap("to_dashboard")
    assert r.c.mode == "dashboard"


@pytest.mark.parametrize("start_mode", ["settings", "dark", "night_clock"])
def test_alarm_preempts_settings_and_dark(start_mode):
    if start_mode == "settings":
        r = Rig(day(6, 29, 50), night_end=time(6, 0))
        r.tap("open_settings")
    else:
        r = Rig(day(4, 59, 50), alarm_time=time(5, 0))
        assert r.c.mode == "dark"
        if start_mode == "night_clock":
            r.tap(None)
    assert r.c.mode == start_mode
    r.advance(10)
    assert r.c.mode == "alarm"  # on the same tick the alarm fired
    r.advance(2)
    r.tap("dismiss")
    assert r.c.mode == ("settings" if start_mode == "settings" else "dark")


def test_settings_idle_paused_during_alarm():
    r = Rig(day(6, 28, 10))
    r.tap("open_settings")
    r.tap("snooze_plus")  # unsaved change
    r.advance(110)
    assert r.c.mode == "alarm"
    r.advance(300)
    r.tap("dismiss")
    assert r.c.mode == "settings"
    assert r.c.settings_idle_left() == pytest.approx(120)
    assert r.cfg.snooze_min == 10 and r.saved == []


def test_alarm_uses_live_unsaved_settings():
    r = Rig(day(12, 29))
    r.tap("open_settings")
    for _ in range(6):
        r.tap("alarm_hour_plus")  # 6:30 → 12:30, not saved yet
    assert r.cfg.alarm_time == time(12, 30) and r.saved == []
    r.advance(59)
    assert r.c.mode == "settings"
    r.advance(1)
    assert r.c.mode == "alarm"


def test_alarm_ignores_early_touch():
    r = Rig(day(6, 29, 59))
    r.advance(1)
    assert r.c.mode == "alarm"
    r.advance(1.5)
    r.c.on_tap("dismiss", r.now, down_at=r.t - 2.0)  # finger went down before the alarm appeared
    assert r.alarm.state == "ringing"
    r2 = Rig(day(6, 29, 59))
    r2.advance(1)
    r2.advance(0.5, step=0.5)
    r2.tap("snooze", down_ago=0.1)  # within the first second
    assert r2.alarm.state == "ringing"
    r2.advance(1)
    r2.tap("snooze", down_ago=0.1)
    assert r2.alarm.state == "snoozed" and r2.c.mode == "dashboard"


def test_wake_tap_is_consumed():
    r = Rig(day(23, 30))
    assert r.c.mode == "dark"
    r.tap("open_settings")  # lands where the button will be: only wakes
    assert r.c.mode == "night_clock"
    r.advance(50)
    r.tap(None)  # elsewhere: restarts wake_s, no mode change
    r.advance(50)
    assert r.c.mode == "night_clock"
    r.advance(11)
    assert r.c.mode == "dark"
    r.tap(None)
    r.tap("open_settings")
    assert r.c.mode == "settings"
    r.tap("done")
    assert r.c.mode == "dark"  # still night


def test_tap_in_dark_shows_night_clock():
    r = Rig(day(23, 30))
    r.tap(None)
    assert r.c.mode == "night_clock"
    r.advance(59)
    assert r.c.mode == "night_clock"
    r.advance(1)
    assert r.c.mode == "dark"


def test_night_ends_returns_to_day_mode():
    r = Rig(day(5, 59, 50), alarm_enabled=False)
    r.c.mode = "dark"
    r.c.day_mode = "photo"
    r.advance(10)
    assert r.c.mode == "photo"


def test_save_failure_keeps_settings_open():
    r = Rig(day(12), save_result="Read-only file system")
    r.tap("open_settings")
    r.tap("snooze_plus")
    r.tap("done")
    assert r.c.mode == "settings" and r.c.save_error == "Read-only file system"
    r.tap("keep_unsaved")
    assert r.c.mode == "dashboard" and r.c.save_error is None
    assert r.cfg.snooze_min == 10
    assert r.c.banner(r.now)["text"] == "Settings not saved"
    r.tap("open_settings")
    r.advance(120)  # idle close with a failing save takes the same path
    assert r.c.mode == "dashboard" and r.c.banner(r.now)["text"] == "Settings not saved"


def test_settings_change_returns_new_config():
    r = Rig(day(12), alarm_time=time(23, 30))
    assert r.c.apply_setting("alarm_min_plus").alarm_time == time(23, 35)
    r.c.apply_setting("alarm_min_minus")
    assert r.c.apply_setting("alarm_hour_plus").alarm_time == time(0, 30)
    r.cfg.alarm_time = time(6, 55)
    assert r.c.apply_setting("alarm_min_plus").alarm_time == time(6, 0)  # wraps within the hour
    r.cfg.alarm_days = {2}
    assert r.c.apply_setting("day_2").alarm_days == {2}  # never empties the repeat days
    assert r.c.apply_setting("day_5").alarm_days == {2, 5}
    assert r.c.apply_setting("interval_plus").interval_s == 60
    assert r.c.apply_setting("interval_minus").interval_s == 30
    r.cfg.interval_s = 45  # off-step value from config.toml
    assert r.c.apply_setting("interval_plus").interval_s == 60
    assert r.c.apply_setting("fade_toggle").fade_ms == 0
    assert r.c.apply_setting("fade_toggle").fade_ms == 1000
    assert r.c.apply_setting("clock_24").clock_24h is True
    assert r.c.apply_setting("night_start_plus").night_start == time(23, 30)
    assert r.c.apply_setting("night_end_minus").night_end == time(5, 30)
    r.cfg.night_start = time(23, 30)
    assert r.c.apply_setting("night_start_plus").night_start == time(0, 0)
    assert r.c.apply_setting("night_toggle").night_enabled is False
    r.cfg.snooze_min = 30
    assert r.c.apply_setting("snooze_plus").snooze_min == 30
    assert r.c.apply_setting("alarm_toggle").alarm_enabled is False


def test_stop_snooze_returns_idle():
    r = Rig(day(6, 29, 59))
    r.advance(1)
    r.advance(1.5)
    r.tap("snooze")
    assert r.alarm.state == "snoozed" and r.c.mode == "dashboard"
    r.tap("stop_snooze")
    assert r.alarm.state == "idle"
    r.advance(15 * 60, step=5)
    assert r.c.mode == "dashboard" and r.alarm.state == "idle"


def test_settings_idle_120s_closes_and_saves():
    r = Rig(day(12))
    r.tap("to_photo")
    r.tap("to_dashboard")
    r.tap("open_settings")
    r.tap("snooze_plus")
    r.advance(119)
    assert r.c.mode == "settings"
    r.advance(1)
    assert r.c.mode == "dashboard" and r.saved == [r.cfg]


def test_test_alarm_returns_to_settings():
    r = Rig(day(12))
    r.tap("open_settings")
    r.tap("test_alarm")
    assert r.c.mode == "alarm" and r.alarm.is_test
    r.advance(10)
    assert r.c.mode == "settings" and r.alarm.missed_at is None


def test_missed_alarm_banner_and_clear():
    r = Rig(day(6, 29, 59))
    r.advance(1)
    r.advance(600, step=5)
    assert r.c.mode == "dashboard"
    b = r.c.banner(r.now)
    assert b == {"text": "Missed alarm 6:30 AM", "kind": "missed", "more": 0}
    r.c.config_banner = "config.toml has errors — using defaults"
    assert r.c.banner(r.now)["more"] == 1
    r.tap("clear_banner")
    assert r.c.banner(r.now) == {"text": "config.toml has errors — using defaults", "kind": "warning", "more": 0}


def test_time_not_set_banner():
    r = Rig(datetime(2025, 1, 1, 12, tzinfo=LA))
    assert r.c.banner(r.now)["text"] == "Time not set"


def test_formatting_helpers():
    assert days_label({0, 1, 2, 3, 4}) == "Mon–Fri"
    assert days_label(set(range(7))) == "Every day"
    assert days_label({5, 6}) == "Weekends"
    assert days_label({0, 2, 4}) == "Mon, Wed, Fri"
    assert days_label({1, 2, 3}) == "Tue–Thu"
    assert fmt_clock(time(6, 30), False) == "6:30 AM" and fmt_clock(time(0, 5), True) == "00:05"
    assert fmt_clock(time(12, 0), False) == "12:00 PM"
