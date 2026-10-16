import logging
import os
import subprocess
import wave

os.environ.setdefault("SDL_AUDIODRIVER", "dummy")

import pygame
import pytest

from frame import sound
from frame.config import Config, load_config, save_config
from frame.display import Brightness, DisplayPower


class FakeRun:
    def __init__(self, fail=None):
        self.calls, self.fail = [], fail

    def __call__(self, cmd, **kw):
        self.calls.append(cmd)
        if self.fail:
            raise self.fail
        return subprocess.CompletedProcess(cmd, 0)


@pytest.mark.parametrize("error", [FileNotFoundError("cec-ctl"),
                                   subprocess.CalledProcessError(1, "cec-ctl"),
                                   subprocess.TimeoutExpired("cec-ctl", 5)])
def test_failing_backend_falls_back_to_black(error, caplog):
    run = FakeRun(fail=error)
    d = DisplayPower("cec", run=run)
    with caplog.at_level(logging.WARNING):
        d.set(False)
        d.set(True)
        d.set(False)
    assert d.method == "black"
    assert len(run.calls) == 1  # tried once, then black only
    assert len([r for r in caplog.records if "display" in r.name]) == 1


def test_cec_commands_only_on_change():
    run = FakeRun()
    d = DisplayPower("cec", run=run)
    d.set(True)  # already on at start: nothing to do
    d.set(False)
    d.set(False)
    d.set(True)
    assert run.calls == [["cec-ctl", "--to", "0", "--standby"], ["cec-ctl", "--to", "0", "--image-view-on"]]


def test_dpms_commands():
    run = FakeRun()
    d = DisplayPower("dpms", run=run)
    d.set(False)
    d.set(True)
    assert run.calls == [["vcgencmd", "display_power", "0"], ["vcgencmd", "display_power", "1"]]


def test_black_and_unknown_methods_run_nothing(caplog):
    run = FakeRun()
    for method in ("black", "laser"):
        d = DisplayPower(method, run=run)
        d.set(False)
        d.set(True)
        assert d.method == "black"
    assert run.calls == []


def test_brightness_command():
    run = FakeRun()
    b = Brightness("ddcutil setvcp 10 {value}", run=run)
    assert b.supported
    b.set(70)
    b.set(70)  # unchanged: not run again
    assert run.calls == [["ddcutil", "setvcp", "10", "70"]]
    assert not Brightness(None, run=run).supported


def test_brightness_failure_disables(caplog):
    b = Brightness("ddcutil setvcp 10 {value}", run=FakeRun(fail=FileNotFoundError("ddcutil")))
    with caplog.at_level(logging.WARNING):
        b.set(50)
        b.set(60)
    assert not b.supported
    assert len([r for r in caplog.records if "brightness" in r.getMessage()]) == 1


def test_missing_mixer_does_not_crash(monkeypatch, caplog):
    sound._reset()

    def no_audio(*a, **k):
        raise pygame.error("No available audio device")

    monkeypatch.setattr(pygame.mixer, "get_init", lambda: None)
    monkeypatch.setattr(pygame.mixer, "init", no_audio)
    with caplog.at_level(logging.WARNING):
        assert sound.play_alarm() is False
        assert sound.play_alarm() is False
        sound.stop()
    assert len([r for r in caplog.records if "sound" in r.name]) == 1
    sound._reset()


def test_alarm_plays_and_stops_with_dummy_audio():
    sound._reset()
    assert sound.play_alarm(loop=True) is True
    assert sound.playing()
    sound.stop()
    assert not sound.playing()
    sound._reset()
    pygame.mixer.quit()


def test_alarm_wav_is_short_and_loopable():
    with wave.open(str(sound.ALARM_WAV)) as w:
        seconds = w.getnframes() / w.getframerate()
        assert w.getnchannels() == 1 and w.getsampwidth() == 2
        assert 0.5 <= seconds <= 3.0


def test_config_display_fields_roundtrip(tmp_path):
    photos = tmp_path / "photos"
    photos.mkdir()
    cfg = Config(photo_dir=photos, screen_power="cec", brightness_cmd="ddcutil setvcp 10 {value}", brightness=60)
    path = tmp_path / "config.toml"
    assert save_config(cfg, path) is None
    loaded, warnings, _ = load_config(path)
    assert loaded == cfg and warnings == []
    path.write_text(f'photo_dir = "{photos}"\nscreen_power = "laser"\nbrightness = 5\nbrightness_cmd = "no placeholder"\n')
    loaded, warnings, _ = load_config(path)
    assert (loaded.screen_power, loaded.brightness, loaded.brightness_cmd) == ("black", 100, None)
    assert len(warnings) == 3


def test_playback_failure_after_init_stays_visual(monkeypatch, caplog):
    sound._reset()

    class BrokenSound:
        def __init__(self, *a):
            pass

        def play(self, *a, **k):
            raise pygame.error("ALSA: underrun / device unplugged")

        def stop(self):
            raise pygame.error("device gone")

        def get_num_channels(self):
            return 0

    monkeypatch.setattr(pygame.mixer, "get_init", lambda: (22050, -16, 1))
    monkeypatch.setattr(pygame.mixer, "Sound", BrokenSound)
    with caplog.at_level(logging.WARNING):
        assert sound.play_alarm() is False
        sound.stop()
        assert sound.play_alarm() is False
    assert len([r for r in caplog.records if "sound" in r.name]) == 1
    sound._reset()


def test_background_commands_never_block_the_caller():
    import threading
    import time as _t
    started, release = threading.Event(), threading.Event()
    calls = []

    def slow_run(cmd, **kw):
        started.set()
        release.wait(5)
        calls.append(cmd)
        return subprocess.CompletedProcess(cmd, 0)

    d = DisplayPower("cec", run=slow_run, background=True)
    b = Brightness("ddcutil setvcp 10 {value}", run=slow_run, background=True)
    t0 = _t.monotonic()
    d.set(False)
    d.set(True)
    b.set(40)
    assert _t.monotonic() - t0 < 0.1  # returned at once although the command is still running
    assert started.wait(2)
    release.set()
    d.wait(5)
    b.wait(5)
    assert calls == [["cec-ctl", "--to", "0", "--standby"], ["cec-ctl", "--to", "0", "--image-view-on"],
                     ["ddcutil", "setvcp", "10", "40"]]


def test_background_failure_still_falls_back_to_black():
    d = DisplayPower("cec", run=FakeRun(fail=FileNotFoundError("cec-ctl")), background=True)
    d.set(False)
    d.wait(5)
    assert d.method == "black"
