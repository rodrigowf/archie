"""Tests for utils/timewords.py — spoken time references → date windows (EN + PT)."""
from __future__ import annotations

from datetime import date

import pytest

from utils.timewords import find_time_expression, resolve_when

TODAY = date(2026, 10, 6)


def window(text: str):
    w, rest = find_time_expression(text, TODAY)
    return (w.after.isoformat(), w.before.isoformat()) if w else None, rest


@pytest.mark.parametrize("text, contains", [
    ("Back in February, when we moved the server", "2026-02-15"),
    ("Lá em abril, quando eu tava no Android velho", "2026-04-15"),
    ("Lá por junho teve uma conversa", "2026-06-15"),
    ("em dezembro do ano passado", "2025-12-15"),
    ("in November last year", "2025-11-15"),
    ("late August the twitter thing", "2026-08-25"),
    ("in mid-May we did the wiki", "2026-05-15"),
    ("Uns quatro meses atrás, numa noite", "2026-06-07"),
    ("about two months ago we talked about lamps", "2026-08-06"),
    ("há duas semanas", "2026-09-22"),
    ("a few weeks ago", "2026-09-15"),
    ("what did we do yesterday?", "2026-10-05"),
    ("o que a gente fez ontem", "2026-10-05"),
    ("the chat from last week about the tablet", "2026-09-29"),
    ("semana passada", "2026-09-29"),
    ("this morning the wake word", "2026-10-06"),
    ("early this year we set up the TV", "2026-02-01"),
])
def test_windows_contain_the_intended_day(text, contains):
    (after, before), _ = window(text)
    assert after <= contains < before


def test_bare_future_month_means_last_year():
    (after, before), _ = window("in November we did it")
    assert after.startswith("2025-10") and before.startswith("2025-12")


@pytest.mark.parametrize("text", [
    "the May release notes", "we may have talked about it", "the conversation about the server",
    "march the files into the folder",
])
def test_no_false_time_expressions(text):
    assert window(text)[0] is None


def test_time_words_are_removed_from_the_query():
    assert window("about two months ago we talked about lamps")[1] == "we talked about lamps"
    assert window("Lá por junho teve uma conversa")[1] == "teve uma conversa"


def test_resolve_when_accepts_iso_and_phrases():
    assert resolve_when("2026-06", TODAY).iso() == ("2026-05-28", "2026-07-05")
    assert resolve_when("2026-06-21", TODAY).iso() == ("2026-06-20", "2026-06-23")
    assert resolve_when("semana passada", TODAY).label == "last week"
    assert resolve_when("sometime", TODAY) is None
    assert resolve_when("", TODAY) is None
