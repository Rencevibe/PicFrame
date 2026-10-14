"""Photo folder scanning, shuffle order and the on-disk resize cache. No pygame imports.

Pillow decodes every source photo; pygame only ever loads the cached JPEGs this writes."""
from __future__ import annotations

import hashlib
import logging
import os
import random
import time
from pathlib import Path

from PIL import Image, ImageEnhance, ImageFilter, ImageOps, features

log = logging.getLogger(__name__)

BASE_EXTS = {".jpg", ".jpeg", ".png"}
BACKDROP_SCALE = 8      # backdrop is made at 1/8 size …
BACKDROP_BLUR = 24 / BACKDROP_SCALE  # … so radius 3 there ≈ radius 24 at full size
BACKDROP_BRIGHTNESS = 0.4


class PhotoLibrary:
    def __init__(self, folder: Path, rng: random.Random | None = None, webp: bool | None = None):
        self.folder = Path(folder)
        self.rng = rng or random.Random()
        webp = features.check("webp") if webp is None else webp
        self.exts = BASE_EXTS | ({".webp"} if webp else set())
        self._files: set[Path] = set()
        self._queue: list[Path] = []  # still to show this round, popped from the end
        self._last: Path | None = None

    def scan(self) -> int:
        """Re-read the folder (subfolders included, hidden names skipped)."""
        found = set()
        if self.folder.is_dir():
            for root, dirs, names in os.walk(self.folder):
                dirs[:] = [d for d in dirs if not d.startswith(".")]
                for n in names:
                    if not n.startswith(".") and os.path.splitext(n)[1].lower() in self.exts:
                        found.add(Path(root) / n)
        new = found - self._files
        self._queue = [p for p in self._queue if p in found]
        for p in sorted(new):  # new photos join the current round at random places
            self._queue.insert(self.rng.randint(0, len(self._queue)), p)
        self._files = found
        return len(found)

    def next(self) -> Path | None:
        if not self._files:
            return None
        if not self._queue:
            self._queue = sorted(self._files)
            self.rng.shuffle(self._queue)
            if len(self._queue) > 1 and self._queue[-1] == self._last:  # no repeat across rounds
                self._queue[0], self._queue[-1] = self._queue[-1], self._queue[0]
        self._last = self._queue.pop()
        return self._last


class ImageCache:
    def __init__(self, dir: Path, limit_mb: int):
        self.dir = Path(dir)
        self.limit_bytes = limit_mb * 1024 * 1024
        self._bad: set[tuple[str, int]] = set()
        self._stamp = 0

    def _touch(self, path: Path) -> None:
        """Mark as most recently used (strictly increasing, even within one clock tick)."""
        self._stamp = max(time.time_ns(), self._stamp + 1)
        os.utime(path, ns=(self._stamp, self._stamp))

    def get(self, src: Path, size: tuple[int, int], mode: str) -> Path | None:
        """Display-ready JPEG of src at exactly `size`: "fill" crops, "fit" letterboxes onto a
        blurred backdrop. None if the source is missing or unreadable (logged once)."""
        try:
            mtime = src.stat().st_mtime_ns
        except OSError:
            return None
        key = f"{src.resolve()}|{mtime}|{size[0]}x{size[1]}|{mode}"
        out = self.dir / (hashlib.sha1(key.encode()).hexdigest() + ".jpg")
        if out.exists():
            self._touch(out)
            return out
        if (str(src), mtime) in self._bad:
            return None
        try:
            im = _render(src, size, mode)
        except (OSError, ValueError, SyntaxError, Image.DecompressionBombError) as e:
            self._bad.add((str(src), mtime))
            log.warning("photos: skipping unreadable %s (%s)", src, e)
            return None
        self.dir.mkdir(parents=True, exist_ok=True)
        tmp = out.with_suffix(".tmp")
        try:
            im.save(tmp, "JPEG", quality=90)
            os.replace(tmp, out)
        except OSError as e:
            log.error("photos: cache write failed (%s)", e)
            tmp.unlink(missing_ok=True)
            return None
        self._touch(out)
        self._evict(keep=out)
        return out

    def _evict(self, keep: Path) -> None:
        files = [(f.stat().st_mtime_ns, f.stat().st_size, f) for f in self.dir.glob("*.jpg")]
        total = sum(size for _, size, _ in files)
        for _, size, f in sorted(files):  # least recently used first
            if total <= self.limit_bytes:
                break
            if f != keep:
                f.unlink(missing_ok=True)
                total -= size


def _open(src: Path, size: tuple[int, int]) -> Image.Image:
    im = Image.open(src)
    if im.format == "JPEG":
        # Decode at reduced scale (still ≥ target) to save RAM and time on huge phone photos.
        # draft works on stored pixels, so swap the target if EXIF will rotate by 90°.
        w, h = size
        if im.getexif().get(0x0112) in (5, 6, 7, 8):
            w, h = h, w
        im.draft("RGB", (w, h))
    im = ImageOps.exif_transpose(im)
    return im.convert("RGB")


def _fill(im: Image.Image, size: tuple[int, int], portrait_top: bool) -> Image.Image:
    w, h = size
    scale = max(w / im.width, h / im.height)
    rw, rh = max(w, round(im.width * scale)), max(h, round(im.height * scale))
    im = im.resize((rw, rh), Image.LANCZOS)
    x = (rw - w) // 2
    # Portrait: start at the top, the only crop that keeps the photo's whole upper third
    # (where faces usually are) in view; landscape: centered.
    y = 0 if portrait_top else (rh - h) // 2
    return im.crop((x, y, x + w, y + h))


def _render(src: Path, size: tuple[int, int], mode: str) -> Image.Image:
    im = _open(src, size)
    if mode == "fill":
        return _fill(im, size, portrait_top=im.height > im.width)
    if mode != "fit":
        raise ValueError(f"unknown mode {mode!r}")
    w, h = size
    small = _fill(im, (max(1, w // BACKDROP_SCALE), max(1, h // BACKDROP_SCALE)), portrait_top=False)
    small = small.filter(ImageFilter.GaussianBlur(BACKDROP_BLUR))
    canvas = ImageEnhance.Brightness(small.resize(size, Image.BICUBIC)).enhance(BACKDROP_BRIGHTNESS)
    scale = min(w / im.width, h / im.height)
    fw, fh = min(w, round(im.width * scale)), min(h, round(im.height * scale))
    canvas.paste(im.resize((fw, fh), Image.LANCZOS), ((w - fw) // 2, (h - fh) // 2))
    return canvas
