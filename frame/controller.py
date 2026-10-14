"""Mode controller: which screen is up, what a tap does, settings changes, banners.

No pygame. Two time sources: `now` (aware local datetime) for the alarm and night schedule,
and `clock()` (monotonic seconds) for UI timers, so a wall-clock change can't stall them."""
from __future__ import annotations

import logging
from datetime import datetime, time

from frame.alarm import Alarm
from frame.config import Config
from frame.night import NightController, is_night

log = logging.getLogger(__name__)

CONTROLS_S = 5.0
SETTINGS_IDLE_S = 120.0
ALARM_GUARD_S = 1.0
INTERVAL_STEPS = [10, 15, 30, 60, 120, 300, 600, 1800]
SNOOZE_RANGE = (1, 30)
BRIGHTNESS_RANGE = (10, 100)
DAY_NAMES = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]


def fmt_clock(t: time, clock_24h: bool) -> str:
    if clock_24h:
        return f"{t.hour:02d}:{t.minute:02d}"
    return f"{t.hour % 12 or 12}:{t.minute:02d} {'AM' if t.hour < 12 else 'PM'}"


def fmt_hour(h: int, clock_24h: bool) -> str:
    return f"{h:02d}" if clock_24h else f"{h % 12 or 12} {'AM' if h < 12 else 'PM'}"


def days_label(days: set[int]) -> str:
    """{0..4} → "Mon–Fri"; runs of 3+ days use a dash, otherwise a comma list."""
    d = sorted(days)
    if len(d) == 7:
        return "Every day"
    if d == [5, 6]:
        return "Weekends"
    if len(d) >= 3 and d == list(range(d[0], d[-1] + 1)):
        return f"{DAY_NAMES[d[0]]}–{DAY_NAMES[d[-1]]}"
    return ", ".join(DAY_NAMES[i] for i in d)


def _shift(t: time, minutes: int) -> time:
    total = (t.hour * 60 + t.minute + minutes) % (24 * 60)
    return time(total // 60, total % 60)


class Controller:
    def __init__(self, cfg: Config, alarm: Alarm, night: NightController, clock, save=lambda cfg: None):
        self.cfg, self.alarm, self.night, self.clock, self.save = cfg, alarm, night, clock, save
        self.mode = "dashboard"
        self.day_mode = "dashboard"  # dashboard or photo: where to go when night ends
        self.controls_until: float | None = None
        self.settings_return = "dashboard"
        self.settings_deadline: float | None = None
        self.settings_idle_paused: float | None = None  # seconds left while an alarm covers Settings
        self.save_error: str | None = None
        self.unsaved = False
        self.config_banner: str | None = None
        self.alarm_return = "dashboard"
        self.alarm_shown_at = 0.0
        self._photo_step = 0

    # --- queries for the app ---

    @property
    def controls_visible(self) -> bool:
        return self.mode == "photo" and self.controls_until is not None

    @property
    def hide_progress(self) -> float:
        if not self.controls_visible:
            return 1.0
        return min(1.0, max(0.0, 1 - (self.controls_until - self.clock()) / CONTROLS_S))

    def settings_idle_left(self) -> float | None:
        if self.settings_idle_paused is not None:
            return self.settings_idle_paused
        return None if self.settings_deadline is None else self.settings_deadline - self.clock()

    def take_photo_step(self) -> int:
        """-1 / +1 when Prev / Next was tapped since the last call, else 0."""
        step, self._photo_step = self._photo_step, 0
        return step

    def banner(self, now: datetime) -> dict | None:
        """Highest-priority banner (spec "Banners") plus how many others wait."""
        items = []
        if self.alarm.missed_at is not None:
            items.append(("missed", f"Missed alarm {fmt_clock(self.alarm.missed_at.time(), self.cfg.clock_24h)}"))
        if now.year < 2026:
            items.append(("warning", "Time not set"))
        if self.unsaved:
            items.append(("warning", "Settings not saved"))
        if self.config_banner:
            items.append(("warning", self.config_banner))
        if not items:
            return None
        kind, text = items[0]
        return {"text": text, "kind": kind, "more": len(items) - 1}

    def _night_now(self, now: datetime) -> bool:
        on = self.cfg.night_enabled
        return is_night(now.time(), self.cfg.night_start if on else None, self.cfg.night_end if on else None)

    # --- time passes ---

    def on_tick(self, now: datetime) -> None:
        t = self.clock()
        state = self.alarm.tick(now)
        if state == "ringing" and self.mode != "alarm":
            self._enter_alarm(t)
        elif self.mode == "alarm" and state != "ringing":  # 10-min stop or test ring over
            self._leave_alarm(now)

        if self.mode == "photo" and self.controls_until is not None and t >= self.controls_until:
            self.controls_until = None
        if self.mode == "settings" and self.settings_deadline is not None and t >= self.settings_deadline:
            self._close_settings(now, idle=True)

        if self.mode in ("dashboard", "photo") and self.night.should_be_dark(now, state):
            self.day_mode, self.mode, self.controls_until = self.mode, "dark", None
        elif self.mode == "night_clock" and self.night.should_be_dark(now, state):
            self.mode = "dark"
        if self.mode in ("dark", "night_clock") and not self._night_now(now):
            self.mode = self.day_mode

    def _enter_alarm(self, t: float) -> None:
        self.alarm_return = self.mode
        if self.mode == "settings" and self.settings_deadline is not None:
            self.settings_idle_paused = max(0.0, self.settings_deadline - t)
            self.settings_deadline = None
        self.mode, self.alarm_shown_at, self.controls_until = "alarm", t, None

    def _leave_alarm(self, now: datetime) -> None:
        back = self.alarm_return
        if back == "settings":
            self.settings_idle_paused = None
            self.settings_deadline = self.clock() + SETTINGS_IDLE_S
        elif back in ("dark", "night_clock"):
            back = "dark" if self._night_now(now) else self.day_mode
        self.mode = back

    # --- taps ---

    def on_tap(self, button_id: str | None, now: datetime, down_at: float | None = None) -> None:
        t = self.clock()
        down_at = t if down_at is None else down_at
        mode = self.mode
        if mode == "alarm":
            # Touch guard: ignore any finger that went down before the alarm showed or during its
            # first second, even if it lifts later
            if down_at < self.alarm_shown_at + ALARM_GUARD_S:
                return
            if button_id == "snooze":
                self.alarm.snooze(now)
            elif button_id == "dismiss":
                self.alarm.dismiss(now)
            else:
                return
            self._leave_alarm(now)
        elif mode == "dark":
            self.night.wake(now)  # the waking tap is consumed
            self.mode = "night_clock"
        elif mode == "night_clock":
            if button_id == "open_settings":
                self._open_settings("dark")
            else:
                self.night.wake(now)
        elif mode == "dashboard":
            if button_id == "to_photo":
                self.mode, self.day_mode, self.controls_until = "photo", "photo", None
            elif button_id == "open_settings":
                self._open_settings("dashboard")
            elif button_id == "stop_snooze":
                self.alarm.dismiss(now)
            elif button_id == "clear_banner":
                self.alarm.missed_at = None
        elif mode == "photo":
            if button_id == "to_dashboard":
                self.mode, self.day_mode, self.controls_until = "dashboard", "dashboard", None
                return
            if button_id == "clear_banner":
                self.alarm.missed_at = None  # and, like any tap in Photo mode, show the bar
            if button_id in ("prev", "next"):
                self._photo_step = -1 if button_id == "prev" else 1
            self.controls_until = t + CONTROLS_S  # any other tap shows / keeps the bar
        elif mode == "settings":
            self.settings_deadline = t + SETTINGS_IDLE_S
            if button_id == "done":
                self._close_settings(now)
            elif button_id == "keep_unsaved":
                self._keep_unsaved(now)
            elif button_id == "test_alarm":
                self.alarm.test(now)
                self._enter_alarm(t)
            elif button_id:
                self.apply_setting(button_id)

    # --- settings ---

    def _open_settings(self, came_from: str) -> None:
        self.settings_return = came_from
        self.mode, self.save_error = "settings", None
        self.settings_deadline, self.settings_idle_paused = self.clock() + SETTINGS_IDLE_S, None

    def _exit_settings(self, now: datetime) -> None:
        back = self.settings_return
        if back == "dark":
            back = "dark" if self._night_now(now) else self.day_mode
        self.mode, self.settings_deadline, self.settings_idle_paused = back, None, None

    def _close_settings(self, now: datetime, idle: bool = False) -> None:
        err = self.save(self.cfg)
        if err:
            log.error("settings: save failed: %s", err)
            self.save_error = err
            if idle:
                self._keep_unsaved(now)
            return
        self.save_error, self.unsaved = None, False
        self._exit_settings(now)

    def _keep_unsaved(self, now: datetime) -> None:
        self.save_error, self.unsaved = None, True
        self._exit_settings(now)

    def apply_setting(self, button_id: str) -> Config:
        """Change cfg in place (the alarm and night schedule read it live) and return it."""
        c = self.cfg
        if button_id == "alarm_toggle":
            c.alarm_enabled = not c.alarm_enabled
        elif button_id in ("alarm_hour_minus", "alarm_hour_plus"):
            c.alarm_time = _shift(c.alarm_time, 60 if button_id.endswith("plus") else -60)
        elif button_id in ("alarm_min_minus", "alarm_min_plus"):
            m = (c.alarm_time.minute + (5 if button_id.endswith("plus") else -5)) % 60
            c.alarm_time = time(c.alarm_time.hour, m)  # wraps within the hour
        elif button_id.startswith("day_"):
            d = int(button_id[4:])
            if d in c.alarm_days:
                if len(c.alarm_days) > 1:  # an empty repeat set isn't a valid config
                    c.alarm_days = c.alarm_days - {d}
            else:
                c.alarm_days = c.alarm_days | {d}
        elif button_id in ("snooze_minus", "snooze_plus"):
            lo, hi = SNOOZE_RANGE
            c.snooze_min = max(lo, min(hi, c.snooze_min + (1 if button_id.endswith("plus") else -1)))
        elif button_id == "interval_plus":
            c.interval_s = next((s for s in INTERVAL_STEPS if s > c.interval_s), c.interval_s)
        elif button_id == "interval_minus":
            c.interval_s = next((s for s in reversed(INTERVAL_STEPS) if s < c.interval_s), c.interval_s)
        elif button_id == "fade_toggle":
            c.fade_ms = 0 if c.fade_ms > 0 else 1000
        elif button_id in ("clock_12", "clock_24"):
            c.clock_24h = button_id == "clock_24"
        elif button_id == "night_toggle":
            c.night_enabled = not c.night_enabled
        elif button_id.startswith(("night_start_", "night_end_")) and c.night_enabled:
            delta = 30 if button_id.endswith("plus") else -30
            if button_id.startswith("night_start_"):
                c.night_start = _shift(c.night_start, delta)
            else:
                c.night_end = _shift(c.night_end, delta)
        elif button_id in ("brightness_minus", "brightness_plus") and hasattr(c, "brightness"):
            lo, hi = BRIGHTNESS_RANGE
            c.brightness = max(lo, min(hi, c.brightness + (10 if button_id.endswith("plus") else -10)))
        else:
            log.debug("settings: ignored %s", button_id)
        return c
