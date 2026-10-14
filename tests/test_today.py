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
