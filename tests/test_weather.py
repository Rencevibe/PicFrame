import json
import threading
from datetime import datetime, timedelta
from pathlib import Path
from urllib.parse import parse_qs, urlparse
from zoneinfo import ZoneInfo

import pytest

from frame.config import Config
from frame.weather import WeatherService, build_url, icon_for, parse

LA = ZoneInfo("America/Los_Angeles")
T0 = datetime(2026, 10, 14, 12, 30, tzinfo=LA)
FIXTURE = json.loads((Path(__file__).parent / "fixtures" / "open_meteo.json").read_text(encoding="utf-8"))


class FakeFetch:
    def __init__(self, *results):
        self.results, self.urls, self.timeouts = list(results), [], []

    def __call__(self, url, timeout):
        self.urls.append(url)
        self.timeouts.append(timeout)
        r = self.results.pop(0)
        if isinstance(r, Exception):
            raise r
        return r


class FakeClock:
    """Monotonic clock the tests advance by hand."""
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t

    def advance(self, minutes):
        self.t += minutes * 60


def service(fetch, clock=None, **cfg):
    cfg.setdefault("lat", 47.6)
    cfg.setdefault("lon", -122.33)
    return WeatherService(Config(**cfg), fetch=fetch, spawn=lambda fn: fn(), clock=clock or FakeClock())


def test_build_url():
    q = parse_qs(urlparse(build_url(47.6, -122.33, "celsius")).query)
    assert urlparse(build_url(1, 2, "fahrenheit")).netloc == "api.open-meteo.com"
    assert q["latitude"] == ["47.6"] and q["longitude"] == ["-122.33"]
    assert q["current"] == ["temperature_2m,weather_code"]
    assert q["daily"] == ["temperature_2m_max,temperature_2m_min"]
    assert q["timezone"] == ["auto"] and q["forecast_days"] == ["1"]
    assert q["temperature_unit"] == ["celsius"]


def test_parse_fixture():
    w = parse(FIXTURE, T0)
    assert (w.temp, w.code, w.icon, w.label, w.high, w.low) == (64, 2, "partly-cloudy", "Partly cloudy", 72, 55)
    assert w.fetched == T0


def test_icon_mapping_table():
    assert icon_for(0) == "clear"
    assert icon_for(1) == icon_for(2) == "partly-cloudy"
    assert icon_for(3) == "cloudy"
    assert icon_for(45) == icon_for(48) == "fog"
    assert icon_for(51) == icon_for(67) == "rain"
    assert icon_for(80) == icon_for(82) == "rain"
    assert icon_for(71) == icon_for(77) == "snow"
    assert icon_for(85) == icon_for(86) == "snow"
    assert icon_for(95) == icon_for(99) == "storm"
    assert icon_for(1234) == "unknown"
    assert icon_for(4) == icon_for(46) == icon_for(68) == "unknown"


def test_fetch_uses_10s_timeout_and_config_units():
    fetch = FakeFetch(FIXTURE)
    service(fetch, units="celsius").update(T0)
    assert fetch.timeouts == [10]
    assert "temperature_unit=celsius" in fetch.urls[0]


def test_updates_at_most_every_30_min():
    fetch, clock = FakeFetch(FIXTURE, FIXTURE), FakeClock()
    svc = service(fetch, clock)
    svc.update(T0)
    clock.advance(29)
    svc.update(T0 + timedelta(minutes=29))
    assert len(fetch.urls) == 1
    clock.advance(1)
    svc.update(T0 + timedelta(minutes=30))
    assert len(fetch.urls) == 2


def test_failure_keeps_last_good():
    bad = dict(FIXTURE, current={})
    fetch, clock = FakeFetch(FIXTURE, OSError("network down"), bad), FakeClock()
    svc = service(fetch, clock)
    for m in (0, 30, 60):
        svc.update(T0 + timedelta(minutes=m))
        clock.advance(30)
    assert len(fetch.urls) == 3
    w = svc.current(T0 + timedelta(minutes=70))
    assert w is not None and w.fetched == T0 and w.temp == 64


def test_stale_after_3h_returns_none():
    svc = service(FakeFetch(FIXTURE))
    assert svc.current(T0) is None  # never fetched
    svc.update(T0)
    assert svc.current(T0 + timedelta(hours=3)) is not None
    assert svc.current(T0 + timedelta(hours=3, seconds=1)) is None


def test_no_location_never_fetches():
    fetch = FakeFetch()
    svc = WeatherService(Config(), fetch=fetch, spawn=lambda fn: fn())
    for h in range(5):
        svc.update(T0 + timedelta(hours=h))
    assert fetch.urls == []
    assert svc.current(T0) is None


def test_runs_in_background_thread():
    started = []
    svc = WeatherService(Config(lat=1.0, lon=2.0), fetch=FakeFetch(FIXTURE), spawn=started.append)
    svc.update(T0)
    assert len(started) == 1 and svc.current(T0) is None  # nothing ran on the caller's thread
    svc.update(T0 + timedelta(minutes=45))
    assert len(started) == 1  # still in flight: no second fetch
    started[0]()
    assert svc.current(T0) is not None


REAL_RESPONSE_KEYS = {"latitude", "longitude", "generationtime_ms", "utc_offset_seconds", "timezone",
                      "timezone_abbreviation", "elevation", "current_units", "current", "daily_units", "daily"}


def test_fixture_matches_open_meteo_format_and_default_units():
    # Shape of a real /v1/forecast response for the URL build_url makes
    assert set(FIXTURE) == REAL_RESPONSE_KEYS
    assert set(FIXTURE["current"]) == {"time", "interval", "temperature_2m", "weather_code"}
    assert set(FIXTURE["current_units"]) == set(FIXTURE["current"])
    assert set(FIXTURE["daily"]) == {"time", "temperature_2m_max", "temperature_2m_min"}
    assert set(FIXTURE["daily_units"]) == set(FIXTURE["daily"])
    assert all(len(v) == 1 for v in FIXTURE["daily"].values())  # forecast_days=1
    datetime.fromisoformat(FIXTURE["current"]["time"])
    # Units in the fixture are what Config's default units request
    symbol = {"fahrenheit": "°F", "celsius": "°C"}[Config().units]
    assert FIXTURE["current_units"]["temperature_2m"] == symbol
    assert FIXTURE["daily_units"]["temperature_2m_max"] == symbol
    assert FIXTURE["daily_units"]["temperature_2m_min"] == symbol
    assert "temperature_unit=" + Config().units in build_url(1, 2, Config().units)


def test_spawn_failure_does_not_leave_service_busy():
    def no_threads(fn):
        raise RuntimeError("can't start new thread")

    clock = FakeClock()
    svc = WeatherService(Config(lat=1.0, lon=2.0), fetch=FakeFetch(FIXTURE), spawn=no_threads, clock=clock)
    svc.update(T0)  # must not raise
    assert svc.current(T0) is None
    svc._spawn = lambda fn: fn()
    clock.advance(30)
    svc.update(T0 + timedelta(minutes=30))
    assert svc.current(T0 + timedelta(minutes=30)) is not None


def test_refresh_interval_ignores_wall_clock_jumps():
    fetch, clock = FakeFetch(FIXTURE, FIXTURE), FakeClock()
    svc = service(fetch, clock)
    svc.update(T0)
    clock.advance(31)
    svc.update(T0 - timedelta(hours=2))  # NTP set the wall clock back: still refreshes
    assert len(fetch.urls) == 2
    clock.advance(1)
    svc.update(T0 + timedelta(days=1))  # wall clock jumped ahead: no extra fetch
    assert len(fetch.urls) == 2


def test_concurrent_updates_and_reads_during_fetch():
    release, entered = threading.Event(), threading.Event()
    second = dict(FIXTURE, current=dict(FIXTURE["current"], temperature_2m=50.2))
    responses = [FIXTURE, second]

    def slow_fetch(url, timeout):
        entered.set()
        release.wait(5)
        return responses.pop(0)

    clock = FakeClock()
    svc = WeatherService(Config(lat=1.0, lon=2.0), fetch=slow_fetch, clock=clock)
    svc.update(T0)
    assert entered.wait(5)
    for _ in range(100):  # UI keeps calling while the fetch is in flight
        svc.update(T0)
        assert svc.current(T0) is None
    release.set()

    seen, later = [], T0 + timedelta(minutes=31)

    def reader():  # reads continuously until it sees the second result (or 5 s pass)
        for _ in range(500_000):
            w = svc.current(later)
            seen.append(None if w is None else w.temp)
            if seen[-1] == 50:
                return

    for _ in range(500):  # first fetch has completed
        if svc.current(T0) is not None and not svc._busy:
            break
        threading.Event().wait(0.01)
    clock.advance(31)
    t = threading.Thread(target=reader)
    t.start()
    svc.update(later)  # second fetch, completing while the reader runs
    t.join(5)
    assert responses == []  # exactly two fetches despite 100+ update calls
    assert set(seen) <= {None, 64, 50}  # every read saw a whole result
    assert seen[-1] == 50
