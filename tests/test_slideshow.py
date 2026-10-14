import os

os.environ.setdefault("SDL_VIDEODRIVER", "dummy")

import threading
import time

import pygame
import pytest
from PIL import Image

from frame.app import DASH_PHOTO, SIZE, Slideshow


@pytest.fixture(scope="module", autouse=True)
def display():
    pygame.init()
    pygame.display.set_mode(SIZE)
    yield
    pygame.quit()


class FakeLibrary:
    def __init__(self, paths):
        self.paths, self.i, self.scans = paths, 0, 0

    def next(self):
        p = self.paths[self.i % len(self.paths)]
        self.i += 1
        return p

    def scan(self):
        self.scans += 1
        time.sleep(0.3)  # a slow SD card
        return len(self.paths)


class FakeCache:
    """Returns small real JPEGs; get() for one chosen photo blocks until released."""

    def __init__(self, tmp, block=None):
        self.tmp, self.block, self.release = tmp, block, threading.Event()

    def get(self, src, size, mode):
        if src.name == self.block:
            self.release.wait(5)
        out = self.tmp / f"{src.stem}-{size[0]}.jpg"
        if not out.exists():
            Image.new("RGB", size, (100, 100, 100)).save(out)
        return out


def make(tmp, block=None):
    paths = [tmp / f"p{i}.jpg" for i in range(4)]
    clock = [0.0]
    s = Slideshow(FakeLibrary(paths), FakeCache(tmp, block), lambda: clock[0])
    return s, clock


def test_next_during_preload_keeps_the_queued_photo(tmp_path):
    s, clock = make(tmp_path, block="p1.jpg")
    s.update(30, 0, 0)
    assert s.current[0].name == "p0.jpg"
    clock[0] = 26  # preload of p1 starts (5 s before the switch) and is still running
    s.update(30, 0, 0)
    threading.Timer(0.2, s.cache.release.set).start()
    s.update(30, 0, 1)  # Next while the preload is unfinished
    assert s.current[0].name == "p1.jpg"  # the queued photo, not one further on
    assert s.lib.i == 2


def test_rescan_runs_off_the_ui_thread(tmp_path):
    s, _ = make(tmp_path)
    t0 = time.monotonic()
    s.rescan_async()
    s.rescan_async()  # one at a time
    assert time.monotonic() - t0 < 0.1
    s.wait_rescan(5)
    assert s.lib.scans == 1
    assert s.photo_count == 4


def test_preload_decodes_off_the_ui_thread(tmp_path):
    s, clock = make(tmp_path)
    s.update(30, 0, 0)
    clock[0] = 26
    s.update(30, 0, 0)
    s._done.wait(5)
    ready = s._ready
    assert ready is not None and isinstance(ready[1], pygame.Surface)  # already decoded in the worker
    assert ready[1].get_size() == DASH_PHOTO and ready[2].get_size() == SIZE
