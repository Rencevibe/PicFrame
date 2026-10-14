from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo

from frame.config import Config
from frame.night import NightController, is_night

LA = ZoneInfo("America/Los_Angeles")
NIGHT = datetime(2026, 10, 14, 23, 30, tzinfo=LA)


def test_overnight_window():
    s, e = time(23, 0), time(6, 0)
    assert is_night(time(23, 30), s, e) is True
    assert is_night(time(5, 59), s, e) is True
    assert is_night(time(6, 0), s, e) is False
    assert is_night(time(12, 0), s, e) is False
    assert is_night(time(23, 0), s, e) is True


def test_same_day_window():
    s, e = time(13, 0), time(15, 0)
    assert is_night(time(14, 0), s, e) is True
    assert is_night(time(15, 0), s, e) is False
    assert is_night(time(12, 59), s, e) is False


def test_equal_or_none_never_night():
    for t in (time(0, 0), time(6, 0), time(23, 30)):
        assert is_night(t, time(6, 0), time(6, 0)) is False
        assert is_night(t, None, time(6, 0)) is False
        assert is_night(t, time(23, 0), None) is False


def test_disabled_night_mode_never_dark():
    nc = NightController(Config(night_enabled=False))
    assert nc.should_be_dark(NIGHT, "idle") is False


def test_tap_wakes_for_wake_s():
    nc = NightController(Config())
    assert nc.should_be_dark(NIGHT, "idle") is True
    nc.wake(NIGHT)
    assert nc.should_be_dark(NIGHT + timedelta(seconds=59), "idle") is False
    assert nc.should_be_dark(NIGHT + timedelta(seconds=60), "idle") is True


def test_wake_tap_again_restarts_timer():
    nc = NightController(Config())
    nc.wake(NIGHT)
    nc.wake(NIGHT + timedelta(seconds=50))
    assert nc.should_be_dark(NIGHT + timedelta(seconds=100), "idle") is False
    assert nc.should_be_dark(NIGHT + timedelta(seconds=110), "idle") is True


def test_ringing_alarm_forces_light():
    nc = NightController(Config())
    assert nc.should_be_dark(NIGHT, "ringing") is False
    assert nc.should_be_dark(NIGHT, "snoozed") is True
    assert nc.should_be_dark(NIGHT, "idle") is True


def test_settings_changes_apply_live():
    cfg = Config()
    nc = NightController(cfg)
    cfg.night_start = time(23, 45)
    assert nc.should_be_dark(NIGHT, "idle") is False
