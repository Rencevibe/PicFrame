from datetime import datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

from frame.alarm import Alarm, next_trigger
from frame.config import Config

LA = ZoneInfo("America/Los_Angeles")
WED = datetime(2026, 10, 14, 12, 41, tzinfo=LA)  # a Wednesday


def cfg(**kw):
    return Config(**kw)


def at(h, m, s=0, day=WED):
    return day.replace(hour=h, minute=m, second=s)


def run(alarm, start, minutes, step_s=30):
    """Tick in real (UTC) time steps; return the local times of each transition to ringing."""
    rings, t = [], start.astimezone(timezone.utc)
    end = t + timedelta(minutes=minutes)
    prev = alarm.state
    while t <= end:
        state = alarm.tick(t.astimezone(LA))
        if state == "ringing" and prev != "ringing":
            rings.append(t.astimezone(LA))
            alarm.dismiss(t.astimezone(LA))
            state = alarm.state
        prev = state
        t += timedelta(seconds=step_s)
    return rings


def test_next_trigger_skips_disabled_days():
    assert next_trigger(WED, cfg()) == datetime(2026, 10, 15, 6, 30, tzinfo=LA)
    fri_night = datetime(2026, 10, 16, 22, 0, tzinfo=LA)
    assert next_trigger(fri_night, cfg()) == datetime(2026, 10, 19, 6, 30, tzinfo=LA)


def test_fires_once():
    thu = WED + timedelta(days=1)
    alarm = Alarm(cfg())
    assert alarm.tick(at(6, 29, 50, thu)) == "idle"
    assert alarm.tick(at(6, 30, 0, thu)) == "ringing"
    assert alarm.tick(at(6, 30, 30, thu)) == "ringing"
    alarm.dismiss(at(6, 30, 40, thu))
    assert alarm.tick(at(6, 30, 50, thu)) == "idle"
    assert alarm.tick(at(6, 45, 0, thu)) == "idle"


def test_snooze_rerings_after_9_min():
    thu = WED + timedelta(days=1)
    alarm = Alarm(cfg())
    alarm.tick(at(6, 29, 59, thu))
    assert alarm.tick(at(6, 30, 0, thu)) == "ringing"
    alarm.snooze(at(6, 31, 0, thu))
    assert alarm.state == "snoozed"
    assert alarm.snooze_until == at(6, 40, 0, thu)
    assert alarm.tick(at(6, 39, 59, thu)) == "snoozed"
    assert alarm.tick(at(6, 40, 0, thu)) == "ringing"


def test_dismiss_while_snoozed_cancels_snooze():
    thu = WED + timedelta(days=1)
    alarm = Alarm(cfg())
    alarm.tick(at(6, 29, 59, thu))
    alarm.tick(at(6, 30, 0, thu))
    alarm.snooze(at(6, 30, 5, thu))
    alarm.dismiss(at(6, 31, 0, thu))
    assert alarm.tick(at(6, 45, 0, thu)) == "idle"
    assert alarm.missed_at is None


def test_unanswered_10_min_sets_missed():
    thu = WED + timedelta(days=1)
    alarm = Alarm(cfg())
    alarm.tick(at(6, 29, 59, thu))
    alarm.tick(at(6, 30, 0, thu))
    assert alarm.tick(at(6, 39, 59, thu)) == "ringing"
    assert alarm.tick(at(6, 40, 0, thu)) == "idle"
    assert alarm.missed_at == at(6, 30, 0, thu)


def test_unanswered_snooze_ring_reports_original_time():
    thu = WED + timedelta(days=1)
    alarm = Alarm(cfg())
    alarm.tick(at(6, 29, 59, thu))
    alarm.tick(at(6, 30, 0, thu))
    alarm.snooze(at(6, 30, 0, thu))
    alarm.tick(at(6, 39, 0, thu))
    assert alarm.tick(at(6, 49, 0, thu)) == "idle"
    assert alarm.missed_at == at(6, 30, 0, thu)


def test_dst_spring_forward_and_fall_back():
    every_day = cfg(alarm_days=set(range(7)))
    for day in (datetime(2026, 3, 8, tzinfo=LA), datetime(2026, 11, 1, tzinfo=LA)):
        rings = run(Alarm(every_day), day, minutes=12 * 60)
        assert [(r.hour, r.minute) for r in rings] == [(6, 30)]


def test_dst_gap_and_repeated_hour_ring_once():
    # 02:30 doesn't exist on 2026-03-08; 01:30 happens twice on 2026-11-01
    for day, t in ((datetime(2026, 3, 8, tzinfo=LA), time(2, 30)),
                   (datetime(2026, 11, 1, tzinfo=LA), time(1, 30))):
        rings = run(Alarm(cfg(alarm_days=set(range(7)), alarm_time=t)), day, minutes=6 * 60)
        assert len(rings) == 1


def test_dst_gap_next_trigger_is_normalized_to_first_valid_local_time():
    spring = datetime(2026, 3, 8, 0, 0, tzinfo=LA)
    trigger = next_trigger(spring, cfg(alarm_days=set(range(7)), alarm_time=time(2, 30)))
    assert trigger == datetime(2026, 3, 8, 3, 30, tzinfo=LA)


def test_changed_alarm_time_does_not_ring_for_past_time():
    alarm = Alarm(cfg(alarm_days=set(range(7))))
    alarm.tick(at(12, 0))
    alarm.cfg.alarm_time = time(7, 0)  # already passed today
    assert alarm.tick(at(12, 0, 1)) == "idle"


def test_test_rings_10s_without_missed_flag():
    alarm = Alarm(cfg(alarm_enabled=False))
    alarm.test(at(12, 0))
    assert alarm.state == "ringing"
    assert alarm.tick(at(12, 0, 9)) == "ringing"
    assert alarm.tick(at(12, 0, 10)) == "idle"
    assert alarm.missed_at is None


def test_countdown_text():
    alarm = Alarm(cfg())
    assert alarm.countdown(WED) == "in 17h 49m"
    assert alarm.countdown(at(6, 0, 0, WED + timedelta(days=1))) == "in 30m"
    fri_night = datetime(2026, 10, 16, 22, 0, tzinfo=LA)
    assert alarm.countdown(fri_night) == "in 2d 8h"


def test_disabled_never_rings():
    off = cfg(alarm_enabled=False)
    assert next_trigger(WED, off) is None
    alarm = Alarm(off)
    assert run(alarm, WED, minutes=3 * 24 * 60, step_s=60) == []
    assert alarm.countdown(WED) == ""
