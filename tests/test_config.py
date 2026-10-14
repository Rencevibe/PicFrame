import os
from datetime import time
from pathlib import Path

import pytest

from frame.config import Config, load_config, save_config


def write(path: Path, text: str) -> Path:
    path.write_text(text, encoding="utf-8")
    return path


def test_missing_file_gives_defaults_and_warning(tmp_path):
    cfg, warnings, critical = load_config(tmp_path / "config.toml")
    assert cfg == Config()
    assert warnings and "config.toml" in warnings[0]
    # Only critical when the default photo folder is absent on this machine
    assert critical is (not Config().photo_dir.is_dir())


def test_bad_toml_gives_defaults(tmp_path):
    cfg, warnings, _ = load_config(write(tmp_path / "config.toml", "x = ["))
    assert cfg == Config()
    assert any("config.toml" in w for w in warnings)


def test_rejected_value_logged_with_fallback(tmp_path):
    cfg, warnings, _ = load_config(write(tmp_path / "config.toml", "interval_s = -5\n"))
    assert cfg.interval_s == 30
    assert any("interval_s" in w and "30" in w for w in warnings)


def test_bad_alarm_time_is_critical(tmp_path):
    photos = tmp_path / "photos"
    photos.mkdir()
    text = f'photo_dir = "{photos}"\nalarm_time = "25:99"\n'
    cfg, warnings, critical = load_config(write(tmp_path / "config.toml", text))
    assert critical is True
    assert cfg.alarm_time == time(6, 30)
    assert any("alarm_time" in w for w in warnings)


def sample_config(tmp_path) -> Config:
    photos = tmp_path / "photos"
    photos.mkdir()
    return Config(photo_dir=photos, interval_s=60, fade_ms=0, lat=47.6, lon=-122.3,
                  units="celsius", clock_24h=True, week_start="monday",
                  night_start=time(22, 30), alarm_time=time(7, 15), alarm_days={5, 6},
                  snooze_min=5, todo_path=tmp_path / "todo.toml")


def test_save_roundtrip(tmp_path):
    cfg = sample_config(tmp_path)
    path = tmp_path / "config.toml"
    assert save_config(cfg, path) is None
    loaded, warnings, critical = load_config(path)
    assert loaded == cfg
    assert warnings == []
    assert critical is False


def test_save_failure_keeps_previous_file(tmp_path, monkeypatch):
    path = tmp_path / "config.toml"
    cfg = sample_config(tmp_path)
    assert save_config(cfg, path) is None
    before = path.read_bytes()

    def boom(*args, **kwargs):
        raise OSError("Read-only file system")

    monkeypatch.setattr(os, "replace", boom)
    cfg.interval_s = 120
    reason = save_config(cfg, path)
    assert reason and "Read-only" in reason
    assert path.read_bytes() == before
    assert not (tmp_path / "config.toml.tmp").exists()


def test_invalid_main_uses_backup(tmp_path):
    path = tmp_path / "config.toml"
    cfg = sample_config(tmp_path)
    assert save_config(cfg, path) is None
    cfg.snooze_min = 12
    assert save_config(cfg, path) is None  # first save becomes the .bak
    write(path, "x = [")
    loaded, warnings, _ = load_config(path)
    assert loaded.snooze_min == 5
    assert loaded.photo_dir == cfg.photo_dir
    assert any("last saved" in w for w in warnings)


def test_partial_location_is_critical(tmp_path):
    photos = tmp_path / "photos"
    photos.mkdir()
    text = f'photo_dir = "{photos}"\nlat = 47.6\n'
    _, warnings, critical = load_config(write(tmp_path / "config.toml", text))
    assert critical is True
    assert any("lon" in w for w in warnings)
