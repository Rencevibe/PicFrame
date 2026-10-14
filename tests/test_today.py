from datetime import date, datetime, time, timedelta

from frame.today import Item, load_items, visible_today

NOW = datetime(2026, 10, 14, 12, 41)


def appt(title, at=None, day=None, done=False):
    return Item(title, "appointment", at, day, done)


def todo(title, at=None, day=None, done=False):
    return Item(title, "todo", at, day, done)


def test_hides_past_timed_and_done():
    items = [appt("Standup", time(9, 30)), todo("Done thing", done=True), appt("Dentist", time(13, 30))]
    shown, more = visible_today(items, NOW)
    assert [i.title for i in shown] == ["Dentist"]
    assert more == 0


def test_untimed_today_items_after_timed():
    items = [todo("Plants"), appt("Dinner", time(18, 0)), todo("Mail"), appt("Dentist", time(13, 30))]
    shown, _ = visible_today(items, NOW, limit=10)
    assert [i.title for i in shown] == ["Dentist", "Dinner", "Plants", "Mail"]


def test_limit_and_more_count():
    items = [todo(f"t{n}") for n in range(5)]
    shown, more = visible_today(items, NOW)
    assert [i.title for i in shown] == ["t0", "t1", "t2"]
    assert more == 2


def test_other_days_excluded():
    tomorrow = NOW.date() + timedelta(days=1)
    items = [todo("Later", day=tomorrow), todo("Today", day=NOW.date()), todo("Undated")]
    shown, _ = visible_today(items, NOW)
    assert [i.title for i in shown] == ["Today", "Undated"]


def test_load_items_parses_file(tmp_path):
    path = tmp_path / "todo.toml"
    path.write_text(
        '[[item]]\ntitle = "Dentist"\nkind = "appointment"\ntime = "13:30"\ndate = "2026-10-14"\n\n'
        '[[item]]\ntitle = "Water the plants"\nkind = "todo"\n\n'
        '[[item]]\ntitle = "Bad time"\nkind = "todo"\ntime = "25:00"\n\n'
        '[[item]]\nkind = "todo"\n',
        encoding="utf-8")
    assert load_items(path) == [
        Item("Dentist", "appointment", time(13, 30), date(2026, 10, 14)),
        Item("Water the plants", "todo", None, None),
    ]


def test_bad_file_returns_empty(tmp_path):
    assert load_items(tmp_path / "missing.toml") == []
    bad = tmp_path / "todo.toml"
    bad.write_text("item = [", encoding="utf-8")
    assert load_items(bad) == []


def test_missing_kind_is_skipped(tmp_path):
    path = tmp_path / "todo.toml"
    path.write_text('[[item]]\ntitle = "No kind"\n\n[[item]]\ntitle = "Ok"\nkind = "todo"\n', encoding="utf-8")
    assert [i.title for i in load_items(path)] == ["Ok"]


def test_offset_or_seconds_time_is_skipped(tmp_path):
    path = tmp_path / "todo.toml"
    path.write_text(
        '[[item]]\ntitle = "Offset"\nkind = "todo"\ntime = "13:30+02:00"\n\n'
        '[[item]]\ntitle = "Seconds"\nkind = "todo"\ntime = "13:30:00"\n\n'
        '[[item]]\ntitle = "Ok"\nkind = "todo"\ntime = "13:30"\n', encoding="utf-8")
    items = load_items(path)
    assert [i.title for i in items] == ["Ok"]
    assert items[0].at.tzinfo is None
    assert [i.title for i in visible_today(items, NOW)[0]] == ["Ok"]


def test_aware_now_uses_local_wall_time():
    from zoneinfo import ZoneInfo
    now = datetime(2026, 10, 14, 12, 41, tzinfo=ZoneInfo("America/Los_Angeles"))
    items = [appt("Past", time(9, 30)), appt("Later", time(13, 30)), todo("Untimed")]
    shown, more = visible_today(items, now)
    assert [i.title for i in shown] == ["Later", "Untimed"]
    assert more == 0
