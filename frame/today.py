"""Today list from todo.toml. No pygame imports."""
from __future__ import annotations

import logging
import re
import tomllib
from dataclasses import dataclass
from datetime import date, datetime, time
from pathlib import Path

log = logging.getLogger(__name__)

KINDS = ("appointment", "todo")


@dataclass
class Item:
    title: str
    kind: str  # "appointment" | "todo"
    at: time | None
    day: date | None
    done: bool = False


def _hhmm(v) -> time:
    """Naive local "HH:MM" only: offsets or seconds would break comparisons with now."""
    if not isinstance(v, str) or not re.fullmatch(r"\d{2}:\d{2}", v):
        raise ValueError('time must be "HH:MM"')
    return time(int(v[:2]), int(v[3:]))


def _parse(raw: dict) -> Item:
    title, kind = raw.get("title"), raw.get("kind")
    if not isinstance(title, str) or not title.strip():
        raise ValueError("missing title")
    if kind not in KINDS:
        raise ValueError(f"kind must be one of {', '.join(KINDS)}")
    at = _hhmm(raw["time"]) if "time" in raw else None
    day = date.fromisoformat(raw["date"]) if "date" in raw else None
    done = raw.get("done", False)
    if not isinstance(done, bool):
        raise ValueError("done must be true or false")
    return Item(title.strip(), kind, at, day, done)


def load_items(path: Path) -> list[Item]:
    """Read [[item]] entries; a missing or invalid file gives [], a bad entry is skipped."""
    try:
        with path.open("rb") as f:
            entries = tomllib.load(f).get("item", [])
    except FileNotFoundError:
        return []
    except (OSError, tomllib.TOMLDecodeError) as e:
        log.warning("today: %s unreadable (%s)", path, e)
        return []
    items = []
    for n, raw in enumerate(entries if isinstance(entries, list) else [], 1):
        try:
            items.append(_parse(raw))
        except (AttributeError, TypeError, ValueError) as e:
            log.warning("today: %s item %d skipped (%s)", path.name, n, e)
    return items


def visible_today(items: list[Item], now: datetime, limit: int = 3) -> tuple[list[Item], int]:
    """Today's open items: timed ones still ahead (ascending), then untimed in file order."""
    minute = now.time().replace(second=0, microsecond=0)
    today = [i for i in items if not i.done and i.day in (None, now.date())]
    timed = sorted((i for i in today if i.at is not None and i.at >= minute), key=lambda i: i.at)
    untimed = [i for i in today if i.at is None]
    eligible = timed + untimed
    return eligible[:limit], max(0, len(eligible) - limit)
