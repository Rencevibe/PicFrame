"""Alarm schedule and ring state. No pygame imports; time always comes in as `now`.

All comparisons and arithmetic use real elapsed time (UTC timestamps), so DST changes
neither skip nor repeat an alarm. Triggers are local wall times in now's time zone."""
from __future__ import annotations

import math
from datetime import datetime, timedelta, timezone

from frame.config import Config

RING_LIMIT_S = 10 * 60
TEST_RING_S = 10


def _ts(dt: datetime) -> float:
    return dt.timestamp()


def _add(dt: datetime, seconds: float) -> datetime:
    """dt + seconds of real time, expressed in dt's time zone."""
    return (dt.astimezone(timezone.utc) + timedelta(seconds=seconds)).astimezone(dt.tzinfo)


def _first_after(t: datetime, cfg: Config) -> datetime | None:
    """First alarm trigger strictly after instant t, or None if the alarm can't ring."""
    if not cfg.alarm_enabled or not cfg.alarm_days:
        return None
    for offset in range(8):
        day = t.date() + timedelta(days=offset)
        if day.weekday() in cfg.alarm_days:
            cand = datetime.combine(day, cfg.alarm_time, tzinfo=t.tzinfo)
            # Normalize nonexistent DST wall times to the first valid local instant.
            cand = cand.astimezone(timezone.utc).astimezone(t.tzinfo)
            if _ts(cand) > _ts(t):
                return cand
    return None


def next_trigger(now: datetime, cfg: Config) -> datetime | None:
    return _first_after(now, cfg)


class Alarm:
    def __init__(self, cfg: Config):
        self.cfg = cfg  # read live each tick, so unsaved Settings changes apply at once
        self.state = "idle"  # "idle" | "ringing" | "snoozed"
        self.missed_at: datetime | None = None
        self.trigger_at: datetime | None = None  # trigger that started this ring cycle
        self.snooze_until: datetime | None = None
        self.is_test = False
        self._ring_started: datetime | None = None
        self._last_tick: datetime | None = None

    def _ring(self, now: datetime) -> None:
        self.state, self._ring_started = "ringing", now

    def _stop(self) -> None:
        self.state, self.snooze_until, self._ring_started, self.is_test = "idle", None, None, False

    def tick(self, now: datetime) -> str:
        last = self._last_tick or _add(now, -1)
        self._last_tick = now
        if self.state == "ringing":
            limit = TEST_RING_S if self.is_test else RING_LIMIT_S
            if _ts(now) - _ts(self._ring_started) >= limit:
                if not self.is_test:
                    self.missed_at = self.trigger_at
                self._stop()
        elif self.state == "snoozed":
            if _ts(now) >= _ts(self.snooze_until):
                self.snooze_until = None
                self._ring(now)
        else:
            trig = _first_after(last, self.cfg)
            if trig is not None and _ts(trig) <= _ts(now):
                self.trigger_at = trig
                if _ts(now) - _ts(trig) >= RING_LIMIT_S:  # clock jumped past the whole ring
                    self.missed_at = trig
                else:
                    self._ring(now)
        return self.state

    def snooze(self, now: datetime) -> None:
        if self.state != "ringing":
            return
        if self.is_test:
            self._stop()
            return
        self.state, self._ring_started = "snoozed", None
        self.snooze_until = _add(now, self.cfg.snooze_min * 60)

    def dismiss(self, now: datetime) -> None:
        """Stops a ring or cancels a pending snooze."""
        if self.state != "idle":
            self._stop()

    def test(self, now: datetime) -> None:
        self.is_test = True
        self._ring(now)

    def countdown(self, now: datetime) -> str:
        trig = next_trigger(now, self.cfg)
        if trig is None:
            return ""
        minutes = math.ceil((_ts(trig) - _ts(now)) / 60)
        days, rem = divmod(minutes, 24 * 60)
        hours, mins = divmod(rem, 60)
        if days:
            return f"in {days}d {hours}h"
        if hours:
            return f"in {hours}h {mins}m"
        return f"in {mins}m"
