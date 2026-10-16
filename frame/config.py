"""Load, validate and atomically save config.toml. No pygame imports."""
from __future__ import annotations

import logging
import os
import shutil
import tomllib
from dataclasses import dataclass, field, fields
from datetime import time
from pathlib import Path

import tomli_w

log = logging.getLogger(__name__)

FRAME_HOME = Path("/home/pi/frame")
UNITS = ("fahrenheit", "celsius")
WEEK_STARTS = ("sunday", "monday")
CORNERS = ("top-left", "top-right", "bottom-left", "bottom-right")
SCREEN_POWER = ("black", "cec", "dpms")
CRITICAL_KEYS = {"photo_dir", "alarm_enabled", "alarm_time", "alarm_days", "lat", "lon"}


@dataclass
class Config:
    photo_dir: Path = FRAME_HOME / "photos"
    interval_s: int = 30
    fade_ms: int = 1000
    lat: float | None = None
    lon: float | None = None
    units: str = "fahrenheit"
    clock_24h: bool = False
    week_start: str = "sunday"
    overlay_corner: str = "bottom-left"
    night_enabled: bool = True
    night_start: time = time(23, 0)
    night_end: time = time(6, 0)
    wake_s: int = 60
    alarm_enabled: bool = True
    alarm_time: time = time(6, 30)
    alarm_days: set[int] = field(default_factory=lambda: {0, 1, 2, 3, 4})  # Mon=0
    snooze_min: int = 9
    cache_mb: int = 500
    todo_path: Path = FRAME_HOME / "todo.toml"
    screen_power: str = "black"  # "black" (always works), "cec" or "dpms": verify on the real screen first
    brightness_cmd: str | None = None  # e.g. "ddcutil setvcp 10 {value}"; None = no brightness control
    brightness: int = 100


# --- field parsers: return the parsed value or raise ValueError(reason) ---

def _int(lo: int, hi: int):
    def parse(v):
        if isinstance(v, bool) or not isinstance(v, int) or not lo <= v <= hi:
            raise ValueError(f"must be a whole number {lo}–{hi}")
        return v
    return parse


def _bool(v):
    if not isinstance(v, bool):
        raise ValueError("must be true or false")
    return v


def _choice(options: tuple[str, ...]):
    def parse(v):
        if v not in options:
            raise ValueError("must be one of " + ", ".join(options))
        return v
    return parse


def _path(v):
    if not isinstance(v, str) or not v.strip() or "\0" in v:
        raise ValueError("must be a non-empty path without NUL characters")
    return Path(v)


def _coord(limit: float):
    def parse(v):
        if isinstance(v, bool) or not isinstance(v, (int, float)) or not -limit <= v <= limit:
            raise ValueError(f"must be a number between -{limit:g} and {limit:g}")
        return float(v)
    return parse


def _time(v):
    try:
        hh, mm = v.split(":")
        return time(int(hh), int(mm))
    except (AttributeError, ValueError):
        raise ValueError('must be "HH:MM" (24 h)') from None


def _command(v):
    if not isinstance(v, str) or "{value}" not in v or "\0" in v:
        raise ValueError('must be a command containing "{value}"')
    return v


def _days(v):
    if (not isinstance(v, list) or not v
            or any(isinstance(d, bool) or not isinstance(d, int) or not 0 <= d <= 6 for d in v)):
        raise ValueError("must be a non-empty list of days 0–6 (Mon=0)")
    return set(v)


PARSERS = {
    "photo_dir": _path,
    "interval_s": _int(5, 3600),
    "fade_ms": _int(0, 5000),
    "lat": _coord(90),
    "lon": _coord(180),
    "units": _choice(UNITS),
    "clock_24h": _bool,
    "week_start": _choice(WEEK_STARTS),
    "overlay_corner": _choice(CORNERS),
    "night_enabled": _bool,
    "night_start": _time,
    "night_end": _time,
    "wake_s": _int(5, 3600),
    "alarm_enabled": _bool,
    "alarm_time": _time,
    "alarm_days": _days,
    "snooze_min": _int(1, 30),
    "cache_mb": _int(50, 10000),
    "todo_path": _path,
    "screen_power": _choice(SCREEN_POWER),
    "brightness_cmd": _command,
    "brightness": _int(10, 100),
}


def _show(v) -> str:
    if isinstance(v, time):
        return v.strftime("%H:%M")
    if isinstance(v, set):
        return str(sorted(v))
    return str(v)


def _read_toml(path: Path) -> tuple[dict | None, str | None]:
    try:
        with path.open("rb") as f:
            return tomllib.load(f), None
    except FileNotFoundError:
        return None, f"{path.name} not found"
    except (OSError, UnicodeDecodeError, tomllib.TOMLDecodeError) as e:
        return None, f"{path.name} is not valid UTF-8 TOML ({e})"


def _validate(data: dict) -> tuple[Config, list[str], bool]:
    cfg, warnings, critical = Config(), [], False
    for key, raw in data.items():
        if key not in PARSERS:
            warnings.append(f"{key}: unknown setting, ignored")
            continue
        try:
            setattr(cfg, key, PARSERS[key](raw))
        except ValueError as e:
            warnings.append(f"{key}: {raw!r} {e}; using {_show(getattr(cfg, key))}")
            critical |= key in CRITICAL_KEYS
    if (cfg.lat is None) != (cfg.lon is None):
        missing = "lon" if cfg.lon is None else "lat"
        warnings.append(f"{missing}: missing; set both lat and lon to show weather")
        cfg.lat = cfg.lon = None
        critical = True
    if not cfg.photo_dir.is_dir():
        warnings.append(f"photo_dir: folder {cfg.photo_dir} does not exist")
        critical = True
    return cfg, warnings, critical


def load_config(path: Path) -> tuple[Config, list[str], bool]:
    """Return (config, warnings, critical). Never raises; falls back to .bak, then defaults."""
    data, problem = _read_toml(path)
    warnings: list[str] = []
    if data is None:
        backup, _ = _read_toml(path.with_name(path.name + ".bak"))
        if backup is not None:
            data = backup
            warnings.append(f"{problem} — using last saved settings")
        else:
            data = {}
            warnings.append(f"{problem} — using defaults")
    cfg, field_warnings, critical = _validate(data)
    warnings += field_warnings
    for w in warnings:
        log.warning("config: %s", w)
    return cfg, warnings, critical


def _to_toml(cfg: Config) -> dict:
    out = {}
    for f in fields(cfg):
        v = getattr(cfg, f.name)
        if v is None:
            continue
        if isinstance(v, Path):
            v = str(v)
        elif isinstance(v, time):
            v = v.strftime("%H:%M")
        elif isinstance(v, set):
            v = sorted(v)
        out[f.name] = v
    return out


def _fsync_dir(folder: Path) -> None:
    """Make the rename durable across power loss. The new file is already in place,
    so a failure here is logged, not reported as a failed save."""
    try:
        fd = os.open(folder, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
    except OSError as e:
        log.warning("config: saved, but syncing directory %s failed: %s", folder, e)


def save_config(cfg: Config, path: Path) -> str | None:
    """Atomically write cfg. Returns None on success, else a short reason; on failure
    the existing config.toml is left untouched."""
    tmp = path.with_name(path.name + ".tmp")
    try:
        with tmp.open("wb") as f:
            tomli_w.dump(_to_toml(cfg), f)
            f.flush()
            os.fsync(f.fileno())
        written, problem = _read_toml(tmp)
        if written is None or _validate(written)[0] != cfg:
            return problem or "written file did not match the settings"
        if _read_toml(path)[0] is not None:  # keep the last good file as .bak
            shutil.copy2(path, path.with_name(path.name + ".bak"))
        os.replace(tmp, path)
        _fsync_dir(path.parent)
        return None
    except (OSError, ValueError, TypeError) as e:
        log.error("config: save failed: %s", e)
        return getattr(e, "strerror", None) or str(e)
    finally:
        tmp.unlink(missing_ok=True)
