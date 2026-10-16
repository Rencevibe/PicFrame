"""Night schedule: when the screen goes black. No pygame imports; time comes in as `now`."""
from __future__ import annotations

from datetime import datetime, time

from frame.config import Config


def is_night(now: time, start: time | None, end: time | None) -> bool:
    """True inside [start, end); the window may cross midnight. Equal or None = never night."""
    if start is None or end is None or start == end:
        return False
    if start < end:
        return start <= now < end
    return now >= start or now < end


class NightController:
    def __init__(self, cfg: Config):
        self.cfg = cfg  # read live, so Settings changes apply at once
        self._awake_until: float | None = None

    def wake(self, now: datetime) -> None:
        """A tap keeps the screen lit for wake_s seconds of real time (restarts on each tap)."""
        self._awake_until = now.timestamp() + self.cfg.wake_s

    def should_be_dark(self, now: datetime, alarm_state: str) -> bool:
        if alarm_state == "ringing":
            return False
        if self._awake_until is not None and now.timestamp() < self._awake_until:
            return False
        enabled = self.cfg.night_enabled
        return is_night(now.time(),
                        self.cfg.night_start if enabled else None,
                        self.cfg.night_end if enabled else None)
