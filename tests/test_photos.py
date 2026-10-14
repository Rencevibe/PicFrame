import logging
import random
from pathlib import Path

from PIL import Image

from frame.photos import ImageCache, PhotoLibrary


def make(path: Path, size=(800, 600), color=(200, 120, 60), exif_orientation=None, fmt=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    im = Image.new("RGB", size, color)
    kw = {}
    if exif_orientation:
        exif = Image.Exif()
        exif[0x0112] = exif_orientation
        kw["exif"] = exif
    im.save(path, fmt, **kw)
    return path


def noise(path: Path, size):
    Image.frombytes("RGB", size, random.Random(path.name).randbytes(size[0] * size[1] * 3)).save(path)
    return path


# --- library ---

def test_scan_counts_supported_only(tmp_path):
    for name in ("a.jpg", "b.JPEG", "c.png", "sub/d.jpg"):
        make(tmp_path / name)
    (tmp_path / "notes.txt").write_text("hi")
    make(tmp_path / ".hidden.jpg")
    make(tmp_path / "e.webp")
    assert PhotoLibrary(tmp_path, webp=False).scan() == 4
    assert PhotoLibrary(tmp_path, webp=True).scan() == 5


def test_shuffle_covers_all_before_repeat(tmp_path):
    names = {f"p{i}.jpg" for i in range(7)}
    for n in names:
        make(tmp_path / n, size=(8, 8))
    lib = PhotoLibrary(tmp_path, rng=random.Random(1))
    lib.scan()
    for _ in range(3):  # three full rounds
        shown = [lib.next().name for _ in range(7)]
        assert set(shown) == names


def test_no_immediate_repeat_between_rounds(tmp_path):
    for i in range(3):
        make(tmp_path / f"p{i}.jpg", size=(8, 8))
    for seed in range(30):
        lib = PhotoLibrary(tmp_path, rng=random.Random(seed))
        lib.scan()
        seq = [lib.next() for _ in range(30)]
        assert all(a != b for a, b in zip(seq, seq[1:]))


def test_new_file_found_on_rescan(tmp_path):
    make(tmp_path / "a.jpg", size=(8, 8))
    lib = PhotoLibrary(tmp_path, rng=random.Random(2))
    assert lib.scan() == 1
    assert lib.next().name == "a.jpg"
    make(tmp_path / "b.jpg", size=(8, 8))
    (tmp_path / "a.jpg").unlink()
    assert lib.scan() == 1
    assert [lib.next().name for _ in range(3)] == ["b.jpg"] * 3


def test_empty_folder_next_is_none(tmp_path):
    lib = PhotoLibrary(tmp_path / "missing")
    assert lib.scan() == 0
    assert lib.next() is None


# --- cache ---

def test_cache_output_size_exact(tmp_path):
    cache = ImageCache(tmp_path / "cache", 500)
    for src in (make(tmp_path / "land.jpg", (1600, 900)), make(tmp_path / "port.png", (900, 1600))):
        for size, mode in (((664, 600), "fill"), ((1280, 800), "fit")):
            out = cache.get(src, size, mode)
            assert out is not None and Image.open(out).size == size


def test_cache_hit_reuses_file_and_source_change_misses(tmp_path):
    cache = ImageCache(tmp_path / "cache", 500)
    src = make(tmp_path / "a.jpg")
    first = cache.get(src, (664, 600), "fill")
    assert cache.get(src, (664, 600), "fill") == first
    assert cache.get(src, (1280, 800), "fit") != first
    import os
    os.utime(src, ns=(1, 1))  # edited photo → new key
    assert cache.get(src, (664, 600), "fill") != first


def test_exif_rotated_image_upright(tmp_path):
    # Stored 600x800 (portrait) with orientation 6 → displays as 800x600 landscape.
    src = make(tmp_path / "phone.jpg", (600, 800), (255, 255, 255), exif_orientation=6)
    out = Image.open(ImageCache(tmp_path / "cache", 500).get(src, (1280, 800), "fit"))
    # Landscape 800x600 fits as 1067x800 (bars ~107 px); portrait would fit as 600x800 (bars 340 px).
    assert out.getpixel((200, 400))[0] > 240  # photo, not backdrop


def test_fill_portrait_keeps_top(tmp_path):
    src = tmp_path / "portrait.png"
    im = Image.new("RGB", (600, 1200), (128, 128, 128))
    im.paste((255, 0, 0), (0, 100, 600, 300))  # marker band in the top third
    im.save(src)
    out = Image.open(ImageCache(tmp_path / "cache", 500).get(src, (664, 600), "fill"))
    r, g, b = out.getpixel((332, 200))  # band (scaled to rows 111–332) is in view
    assert r > 200 and g < 60 and b < 60
    assert out.getpixel((332, 0)) == (128, 128, 128)  # crop starts at the photo's top edge


def test_fill_landscape_is_centered(tmp_path):
    src = tmp_path / "wide.png"
    im = Image.new("RGB", (2000, 600), (128, 128, 128))
    im.paste((0, 0, 255), (990, 0, 1010, 600))  # centre stripe
    im.save(src)
    out = Image.open(ImageCache(tmp_path / "cache", 500).get(src, (664, 600), "fill"))
    assert out.getpixel((332, 300))[2] > 200


def test_fit_backdrop_not_black(tmp_path):
    src = make(tmp_path / "port.jpg", (600, 900), (40, 160, 90))
    out = Image.open(ImageCache(tmp_path / "cache", 500).get(src, (1280, 800), "fit"))
    corner = out.getpixel((5, 5))
    assert corner != (0, 0, 0)
    assert sum(corner) < sum(out.getpixel((640, 400)))  # backdrop is darker than the photo


def test_corrupt_file_returns_none(tmp_path, caplog):
    bad = tmp_path / "bad.jpg"
    bad.write_bytes(b"\xff\xd8\xff not really a jpeg")
    truncated = tmp_path / "half.jpg"
    good = make(tmp_path / "good.jpg", (800, 600))
    truncated.write_bytes(good.read_bytes()[:400])
    cache = ImageCache(tmp_path / "cache", 500)
    with caplog.at_level(logging.WARNING):
        for _ in range(3):
            assert cache.get(bad, (664, 600), "fill") is None
            assert cache.get(truncated, (664, 600), "fill") is None
    assert len([r for r in caplog.records if "bad.jpg" in r.getMessage()]) == 1
    assert cache.get(tmp_path / "gone.jpg", (664, 600), "fill") is None
    assert not list((tmp_path / "cache").glob("*.tmp"))


def test_eviction_under_limit(tmp_path):
    cache = ImageCache(tmp_path / "cache", 500)
    srcs = [noise(tmp_path / f"n{i}.png", (664, 600)) for i in range(4)]
    one = cache.get(srcs[0], (664, 600), "fill").stat().st_size
    cache.limit_bytes = int(one * 2.5)  # room for two outputs
    outs = [cache.get(s, (664, 600), "fill") for s in srcs[1:3]]
    cache.get(srcs[1], (664, 600), "fill")  # touch: n1 is now more recent than n2
    last = cache.get(srcs[3], (664, 600), "fill")
    files = list((tmp_path / "cache").iterdir())
    assert sum(f.stat().st_size for f in files) <= cache.limit_bytes
    assert last.exists() and outs[0].exists()  # newest + recently used survive
    assert not outs[1].exists()  # least recently used went first
