"""PicFrame main loop: pygame display, touch, slideshow, and wiring of the logic modules.

Run: python3 -m frame.app [--config /home/pi/frame/config.toml] [--windowed]"""
from __future__ import annotations

import argparse
import logging
import math
import sys
import threading
import time as _time
from datetime import datetime
from pathlib import Path

import pygame

from frame.alarm import Alarm
from frame.config import load_config, save_config
from frame import sound
from frame.controller import Controller, days_label, fmt_clock
from frame.display import Brightness, DisplayPower
from frame.night import NightController
from frame.photos import ImageCache, PhotoLibrary
from frame.today import load_items, visible_today
from frame.ui import alarm_screen, dashboard, night_clock, photo_mode, settings_screen
from frame.weather import WeatherService

log = logging.getLogger("frame")

SIZE = (1280, 800)
DASH_PHOTO = (664, 600)
IDLE_FPS, FADE_FPS = 10, 30
PRELOAD_S = 5
RESCAN_S = 5 * 60
TODO_RELOAD_S = 60
HISTORY = 50
DEFAULT_CONFIG = Path("/home/pi/frame/config.toml")


def local_now() -> datetime:
    return datetime.now().astimezone()


def split_time(t, clock_24h: bool) -> tuple[str, str | None]:
    s = fmt_clock(t, clock_24h)
    return (s, None) if clock_24h else tuple(s.split(" "))


class Slideshow:
    """Current photo as dashboard (fill) and fullscreen (fit) surfaces. The next photo is
    prepared on a background thread 5 s before the switch; only current + next are in RAM."""

    def __init__(self, library: PhotoLibrary, cache: ImageCache, clock):
        self.lib, self.cache, self.clock = library, cache, clock
        self.lib_lock = threading.Lock()
        self.current: tuple[Path, pygame.Surface, pygame.Surface] | None = None
        self.history: list[Path] = []
        self.switched_at = clock()
        self._thread: threading.Thread | None = None
        self._ready: tuple | None = None
        self._done = threading.Event()
        self.fade_from: tuple | None = None
        self.fade_start = 0.0
        self.fade_s = 0.0
        self._buf = {"dash": pygame.Surface(DASH_PHOTO), "full": pygame.Surface(SIZE)}
        self.photo_count = 0
        self._scan: threading.Thread | None = None

    def rescan(self) -> int:
        with self.lib_lock:
            self.photo_count = self.lib.scan()
        return self.photo_count

    def rescan_async(self) -> None:
        """Periodic folder rescan on a worker thread, one at a time (os.walk on SD can be slow)."""
        if self._scan is not None and self._scan.is_alive():
            return
        self._scan = threading.Thread(target=self.rescan, name="rescan", daemon=True)
        self._scan.start()

    def wait_rescan(self, timeout: float) -> None:
        if self._scan is not None:
            self._scan.join(timeout)

    def _prepare(self, path: Path):
        """Resize (cache) and decode both sizes. Safe on a worker thread; only convert() is left
        for the UI thread."""
        dash = self.cache.get(path, DASH_PHOTO, "fill")
        full = self.cache.get(path, SIZE, "fit") if dash else None
        if not full:
            return None
        try:
            return path, pygame.image.load(str(dash)), pygame.image.load(str(full))
        except (pygame.error, OSError) as e:  # cache file evicted or damaged meanwhile
            log.warning("slideshow: could not load %s (%s)", path, e)
            return None

    def _prepare_next(self, tries: int = 10):
        """Next usable photo (unreadable files are skipped), or None."""
        for _ in range(tries):
            with self.lib_lock:
                path = self.lib.next()
            if path is None:
                return None
            prepared = self._prepare(path)
            if prepared:
                return prepared
        return None

    def _show(self, prepared, fade_s: float, record: bool = True) -> None:
        path, dash, full = prepared
        loaded = (path, dash.convert(), full.convert())  # fast; must run on the display thread
        if fade_s > 0 and self.current:
            self.fade_from, self.fade_start, self.fade_s = self.current, self.clock(), fade_s
        else:
            self.fade_from = None
        self.current = loaded
        if record:
            self.history = (self.history + [path])[-HISTORY:]
        self.switched_at = self.clock()

    def _start_preload(self) -> None:
        self._done.clear()

        def work():
            self._ready = self._prepare_next()
            self._done.set()

        self._thread = threading.Thread(target=work, name="preload", daemon=True)
        self._thread.start()

    def update(self, interval_s: int, fade_ms: int, step: int) -> None:
        t = self.clock()
        if self.fade_from and t - self.fade_start >= self.fade_s:
            self.fade_from = None
        if step:
            self._jump(step)
            return
        if self.current is None:
            if t - self.switched_at >= 0:  # first photo (or after an empty folder): load now
                prepared = self._prepare_next()
                if prepared:
                    self._show(prepared, 0)
                else:
                    self.switched_at = t + 10  # nothing usable yet; look again in 10 s
            return
        due = self.switched_at + interval_s
        if self._thread is None and t >= due - PRELOAD_S:
            self._start_preload()
        if self._thread is not None and t >= due and self._done.is_set():
            prepared, self._ready, self._thread = self._ready, None, None
            if prepared:
                self._show(prepared, fade_ms / 1000)
            else:
                self.switched_at = t

    def _jump(self, step: int) -> None:
        """Prev / Next: a cut (no fade) that also restarts the interval."""
        if step > 0:
            prepared = None
            if self._thread is not None:
                # A preload is queued: show that photo (finishing it if needed) so the shuffle
                # order is kept, instead of skipping past it and showing it later
                self._done.wait(10)
                if self._done.is_set():
                    prepared = self._ready
                self._ready, self._thread = None, None
            prepared = prepared or self._prepare_next()
            if prepared:
                self._show(prepared, 0)
        elif len(self.history) >= 2:
            self.history.pop()
            prepared = self._prepare(self.history[-1])
            if prepared:
                self._show(prepared, 0, record=False)

    @property
    def fading(self) -> bool:
        return self.fade_from is not None

    def surface(self, which: str) -> pygame.Surface | None:
        """"dash" or "full" for this frame, cross-faded while a fade runs (no per-frame allocation)."""
        if self.current is None:
            return None
        idx = 1 if which == "dash" else 2
        new = self.current[idx]
        if not self.fade_from:
            return new
        a = min(1.0, (self.clock() - self.fade_start) / self.fade_s)
        buf = self._buf[which]
        buf.blit(self.fade_from[idx], (0, 0))
        new.set_alpha(round(255 * a))
        buf.blit(new, (0, 0))
        new.set_alpha(None)
        return buf


class App:
    def __init__(self, config_path: Path, windowed: bool = False):
        self.config_path = config_path
        self.cfg, warnings, critical = load_config(config_path)
        banner = None
        if any("last saved" in w for w in warnings):
            banner = "config.toml was invalid — using last saved settings"
        elif critical:
            banner = "config.toml has errors — using defaults"

        pygame.init()
        flags = 0 if windowed else pygame.FULLSCREEN
        self.screen = pygame.display.set_mode(SIZE, flags)
        pygame.display.set_caption("PicFrame")
        pygame.mouse.set_visible(windowed)
        self.windowed = windowed

        self.clock = _time.monotonic
        self.alarm = Alarm(self.cfg)
        self.night = NightController(self.cfg)
        self.ctrl = Controller(self.cfg, self.alarm, self.night, self.clock,
                               save=lambda c: save_config(c, config_path))
        self.ctrl.config_banner = banner
        self.weather = WeatherService(self.cfg)
        self.display = DisplayPower(self.cfg.screen_power, background=True)
        self.brightness = Brightness(self.cfg.brightness_cmd, background=True)
        self.brightness.set(self.cfg.brightness)
        self.sounding = False
        self.slides = Slideshow(PhotoLibrary(self.cfg.photo_dir),
                                ImageCache(config_path.parent / "cache", self.cfg.cache_mb), self.clock)
        log.info("photos: %d in %s", self.slides.rescan(), self.cfg.photo_dir)
        self.last_rescan = self.clock()
        self.items, self.items_loaded = [], -TODO_RELOAD_S
        self.buttons: list = []
        self.pressed: str | None = None
        self.down: tuple | None = None  # (action at finger-down, monotonic time)
        self.fps = pygame.time.Clock()

    # --- input ---

    def _hit(self, pos) -> str | None:
        for b in self.buttons:
            action = b.hit(pos)
            if action:
                return action
        return None

    def handle(self, event, now: datetime) -> bool:
        if event.type == pygame.QUIT:
            return False
        if event.type == pygame.KEYDOWN and event.key == pygame.K_ESCAPE and self.windowed:
            return False
        if event.type == pygame.MOUSEBUTTONDOWN and event.button == 1:
            action = self._hit(event.pos)
            self.down, self.pressed = (action, self.clock()), action  # pressed state on finger-down
        elif event.type == pygame.MOUSEMOTION and self.down:
            self.pressed = self.down[0] if self._hit(event.pos) == self.down[0] else None
        elif event.type == pygame.MOUSEBUTTONUP and event.button == 1 and self.down:
            action, down_at = self.down
            self.down, self.pressed = None, None
            if self._hit(event.pos) == action:  # fires only on release inside the same button
                self.ctrl.on_tap(action, now, down_at=down_at)
        return True

    # --- view models ---

    def _today(self, now):
        t = self.clock()
        if t - self.items_loaded >= TODO_RELOAD_S:  # todo.toml is edited over SSH
            self.items, self.items_loaded = load_items(self.cfg.todo_path), t
        shown, more = visible_today(self.items, now)
        return [dict(time=fmt_clock(i.at, self.cfg.clock_24h) if i.at else "", kind=i.kind, title=i.title)
                for i in shown], more

    def _weather(self, now):
        w = self.weather.current(now)
        as_of = None
        if w and (now - w.fetched).total_seconds() > 3600:
            as_of = fmt_clock(w.fetched.time(), self.cfg.clock_24h)
        return w, as_of

    def dashboard_vm(self, now):
        c = self.cfg
        hhmm, ampm = split_time(now.time(), c.clock_24h)
        items, more = self._today(now)
        w, as_of = self._weather(now)
        snooze = self.alarm.snooze_until
        return dict(
            time=hhmm, ampm=ampm, date=f"{now:%A}, {now:%B} {now.day}",
            today_items=items, more=more, month_cells=dashboard.month_grid(now.date(), c.week_start),
            alarm_text=fmt_clock(c.alarm_time, c.clock_24h), alarm_days=days_label(c.alarm_days),
            alarm_on=c.alarm_enabled, alarm_state=self.alarm.state,
            snooze_until=fmt_clock(snooze.time(), c.clock_24h) if snooze else None,
            countdown=self.alarm.countdown(now), weather=w, weather_as_of=as_of,
            has_location=c.lat is not None, photo=self.slides.surface("dash"), photo_dir=str(c.photo_dir),
            banner=self.ctrl.banner(now), pressed=self.pressed)

    def photo_vm(self, now):
        hhmm, ampm = split_time(now.time(), self.cfg.clock_24h)
        b = self.ctrl.banner(now)
        return dict(photo=self.slides.surface("full"), time=hhmm, ampm=ampm, date=f"{now:%a}, {now:%B} {now.day}",
                    weather=self.weather.current(now), overlay_corner=self.cfg.overlay_corner,
                    missed=b["text"] if b and b["kind"] == "missed" else None,
                    photo_dir=str(self.cfg.photo_dir), pressed=self.pressed)

    # --- one frame ---

    def step(self) -> bool:
        now = local_now()
        for event in pygame.event.get():
            if not self.handle(event, now):
                return False
        self.ctrl.on_tick(now)
        self.display.set(self.ctrl.mode != "dark")
        self.brightness.set(self.cfg.brightness)  # runs only when Settings changed it
        ringing = self.alarm.state == "ringing"
        if ringing != self.sounding:
            if ringing:
                sound.play_alarm(loop=True)
            else:
                sound.stop()
            self.sounding = ringing
        self.weather.update(now)
        t = self.clock()
        if t - self.last_rescan >= RESCAN_S:
            self.slides.rescan_async()
            self.last_rescan = t
        self.slides.update(self.cfg.interval_s, self.cfg.fade_ms, self.ctrl.take_photo_step())
        self.render(now)
        pygame.display.flip()
        return True

    def render(self, now: datetime) -> None:
        mode, c, s = self.ctrl.mode, self.cfg, self.screen
        if mode == "dashboard":
            self.buttons = dashboard.render(s, self.dashboard_vm(now))
        elif mode == "photo":
            self.buttons = photo_mode.render(s, self.photo_vm(now), self.ctrl.controls_visible,
                                             self.ctrl.hide_progress)
        elif mode == "settings":
            self.buttons = settings_screen.render(s, c, brightness_supported=self.brightness.supported,
                                                  save_error=self.ctrl.save_error, pressed=self.pressed)
        elif mode == "alarm":
            hhmm, ampm = split_time(now.time(), c.clock_24h)
            pulse = 0.5 + 0.5 * math.cos(2 * math.pi * self.clock())
            self.buttons = alarm_screen.render(s, dict(
                time=hhmm, ampm=ampm, date=f"{now:%A}, {now:%B} {now.day}", snooze_min=c.snooze_min,
                is_test=self.alarm.is_test, pressed=self.pressed), pulse)
        elif mode == "night_clock":
            hhmm, ampm = split_time(now.time(), c.clock_24h)
            label = (f"Alarm {fmt_clock(c.alarm_time, c.clock_24h)} · {days_label(c.alarm_days)}"
                     if c.alarm_enabled else "Alarm off")
            self.buttons = night_clock.render(s, dict(time=hhmm, ampm=ampm, alarm_label=label,
                                                      pressed=self.pressed))
        else:  # dark: full black screen, a tap anywhere wakes it
            s.fill((0, 0, 0))
            self.buttons = []

    def run(self) -> None:
        log.info("started")
        while self.step():
            busy = self.slides.fading or self.ctrl.mode == "alarm"
            self.fps.tick(FADE_FPS if busy else IDLE_FPS)
        pygame.quit()


def main(argv=None) -> None:
    p = argparse.ArgumentParser(description="PicFrame photo frame")
    p.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    p.add_argument("--windowed", action="store_true", help="run in a window (PC testing)")
    args = p.parse_args(argv)
    logging.basicConfig(stream=sys.stdout, level=logging.INFO,
                        format="%(levelname)s %(name)s: %(message)s")  # journald adds timestamps
    App(args.config, windowed=args.windowed).run()


if __name__ == "__main__":
    main()
