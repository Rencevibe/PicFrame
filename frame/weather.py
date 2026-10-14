"""Open-Meteo weather: the frame's only network call. No pygame imports; time comes in as `now`."""
from __future__ import annotations

import json
import logging
import math
import threading
import urllib.request
from dataclasses import dataclass
from datetime import datetime
from urllib.parse import urlencode

from frame.config import Config

log = logging.getLogger(__name__)

API = "https://api.open-meteo.com/v1/forecast"
TIMEOUT_S = 10
REFRESH_S = 30 * 60
STALE_S = 3 * 60 * 60

# (first code, last code, icon, label) — WMO weather interpretation codes
CODES = [
    (0, 0, "clear", "Clear"),
    (1, 1, "partly-cloudy", "Mostly clear"),
    (2, 2, "partly-cloudy", "Partly cloudy"),
    (3, 3, "cloudy", "Cloudy"),
    (45, 45, "fog", "Fog"),
    (48, 48, "fog", "Freezing fog"),
    (51, 55, "rain", "Drizzle"),
    (56, 57, "rain", "Freezing drizzle"),
    (61, 65, "rain", "Rain"),
    (66, 67, "rain", "Freezing rain"),
    (71, 77, "snow", "Snow"),
    (80, 82, "rain", "Showers"),
    (85, 86, "snow", "Snow showers"),
    (95, 99, "storm", "Thunderstorm"),
]


@dataclass
class Weather:
    temp: int
    code: int
    icon: str
    label: str
    high: int
    low: int
    fetched: datetime


def _lookup(code: int) -> tuple[str, str]:
    for first, last, icon, label in CODES:
        if first <= code <= last:
            return icon, label
    return "unknown", "Unknown"


def icon_for(code: int) -> str:
    return _lookup(code)[0]


def _round(x: float) -> int:
    return math.floor(x + 0.5)  # 63.5 → 64 and 64.5 → 65 (round() would give 64 for both)


def build_url(lat: float, lon: float, units: str) -> str:
    return API + "?" + urlencode({
        "latitude": lat,
        "longitude": lon,
        "current": "temperature_2m,weather_code",
        "daily": "temperature_2m_max,temperature_2m_min",
        "temperature_unit": units,
        "timezone": "auto",
        "forecast_days": 1,
    })


def parse(data: dict, fetched: datetime) -> Weather:
    """Raises KeyError/TypeError/ValueError/IndexError on a malformed response."""
    code = int(data["current"]["weather_code"])
    icon, label = _lookup(code)
    return Weather(
        temp=_round(float(data["current"]["temperature_2m"])),
        code=code,
        icon=icon,
        label=label,
        high=_round(float(data["daily"]["temperature_2m_max"][0])),
        low=_round(float(data["daily"]["temperature_2m_min"][0])),
        fetched=fetched,
    )


def _urlopen_fetch(url: str, timeout: float) -> dict:
    with urllib.request.urlopen(url, timeout=timeout) as resp:
        return json.load(resp)


def _thread_spawn(fn) -> None:
    threading.Thread(target=fn, name="weather", daemon=True).start()


class WeatherService:
    def __init__(self, cfg: Config, fetch=_urlopen_fetch, spawn=_thread_spawn):
        self.cfg = cfg
        self._fetch, self._spawn = fetch, spawn
        self._last: Weather | None = None
        self._last_attempt: float | None = None
        self._busy = False

    def update(self, now: datetime) -> None:
        """Start a background fetch if 30 min have passed since the last attempt.
        Safe to call every frame; never blocks the caller."""
        lat, lon = self.cfg.lat, self.cfg.lon
        if lat is None or lon is None or self._busy:
            return
        if self._last_attempt is not None and now.timestamp() - self._last_attempt < REFRESH_S:
            return
        self._last_attempt, self._busy = now.timestamp(), True
        url = build_url(lat, lon, self.cfg.units)

        def job():
            try:
                self._last = parse(self._fetch(url, TIMEOUT_S), now)
            except Exception as e:  # network, HTTP, JSON or shape errors: keep the last good result
                log.warning("weather: update failed (%s); keeping last result", e)
            finally:
                self._busy = False

        self._spawn(job)

    def current(self, now: datetime) -> Weather | None:
        w = self._last
        if w is None or now.timestamp() - w.fetched.timestamp() > STALE_S:
            return None
        return w
