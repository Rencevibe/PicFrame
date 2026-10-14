import json
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


def service(fetch, **cfg):
    cfg.setdefault("lat", 47.6)
    cfg.setdefault("lon", -122.33)
    return WeatherService(Config(**cfg), fetch=fetch, spawn=lambda fn: fn())


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
    fetch = FakeFetch(FIXTURE, FIXTURE)
    svc = service(fetch)
    svc.update(T0)
    svc.update(T0 + timedelta(minutes=29))
    assert len(fetch.urls) == 1
    svc.update(T0 + timedelta(minutes=30))
    assert len(fetch.urls) == 2


def test_failure_keeps_last_good():
    bad = dict(FIXTURE, current={})
    fetch = FakeFetch(FIXTURE, OSError("network down"), bad)
    svc = service(fetch)
    svc.update(T0)
    svc.update(T0 + timedelta(minutes=30))
    svc.update(T0 + timedelta(minutes=60))
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
