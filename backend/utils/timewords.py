"""Resolve spoken time references (English and Brazilian Portuguese) into date windows.

Voice models are bad at turning "back in June" or "uns quatro meses atrás" into ISO dates, so
the history tools do it on the server: ``resolve_when("last week", today)`` →
``Window(after, before, label)``; ``find_time_expression(query, today)`` also returns the query
with the time words removed (they are noise for keyword search).

Windows are deliberately a little wider than the literal phrase: people misremember dates by
days ("last week" might have been ten days ago) and months ("in June" might be early July).
Unparseable phrases return None — the caller searches without a date constraint.
"""
from __future__ import annotations

import calendar
import re
import unicodedata
from dataclasses import dataclass
from datetime import date, timedelta


@dataclass(frozen=True)
class Window:
    after: date   # inclusive
    before: date  # exclusive
    label: str

    def iso(self) -> tuple[str, str]:
        return self.after.isoformat(), self.before.isoformat()


def _fold(text: str) -> str:
    text = unicodedata.normalize("NFKD", text.lower())
    return "".join(c for c in text if not unicodedata.combining(c))


_NUM = {
    "a": 1, "an": 1, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7,
    "eight": 8, "nine": 9, "ten": 10, "eleven": 11, "twelve": 12, "couple": 2, "a couple": 2,
    "a couple of": 2, "few": 3, "a few": 3, "several": 4,
    "um": 1, "uma": 1, "dois": 2, "duas": 2, "tres": 3, "quatro": 4, "cinco": 5, "seis": 6,
    "sete": 7, "oito": 8, "nove": 9, "dez": 10, "onze": 11, "doze": 12, "alguns": 3, "algumas": 3,
    "uns dois": 2, "umas duas": 2, "uns": 3, "umas": 3, "poucos": 3, "poucas": 3,
}
_UNIT_DAYS = {"day": 1, "dia": 1, "week": 7, "semana": 7, "month": 30, "mes": 30, "meses": 30, "year": 365, "ano": 365}

_MONTHS = {
    "january": 1, "jan": 1, "janeiro": 1, "february": 2, "feb": 2, "fevereiro": 2, "march": 3,
    "marco": 3, "april": 4, "apr": 4, "abril": 4, "may": 5, "maio": 5, "june": 6, "junho": 6,
    "july": 7, "julho": 7, "august": 8, "aug": 8, "agosto": 8, "september": 9, "sept": 9,
    "setembro": 9, "october": 10, "outubro": 10, "november": 11, "novembro": 11,
    "december": 12, "dezembro": 12,
}
# "may" and "march" are also common words; only treat them as months next to a month cue.
_AMBIGUOUS_MONTHS = {"may", "march", "jan", "feb", "apr", "aug", "sept"}

_NUM_WORDS = "|".join(sorted((re.escape(k) for k in _NUM), key=len, reverse=True))
_UNIT_WORDS = r"days?|weeks?|months?|years?|dias?|semanas?|mes|meses|anos?"

_FILLER = r"(?:(?:about|around|like|roughly|some|maybe|uns|umas|tipo|mais ou menos|cerca de|uns|ha|faz|back|la)\s+)*"

_RELATIVE = [
    # (pattern, function(today) -> (after, before, label))
    (r"\b(?:the\s+)?day before yesterday\b|\banteontem\b", lambda t: (t - timedelta(days=3), t - timedelta(days=1), "the day before yesterday")),
    (r"\byesterday\b|\bontem\b", lambda t: (t - timedelta(days=2), t, "yesterday")),
    (r"\b(?:today|this morning|this afternoon|tonight|earlier today|hoje(?: de manha| cedo| mais cedo)?|agora ha pouco)\b",
     lambda t: (t - timedelta(days=1), t + timedelta(days=1), "today")),
    (r"\b(?:last|past|previous) week\b|\bsemana passada\b|\bna outra semana\b", lambda t: (t - timedelta(days=16), t - timedelta(days=2), "last week")),
    (r"\bthis week\b|\b(?:essa|esta|nessa|nesta) semana\b", lambda t: (t - timedelta(days=t.weekday() + 2), t + timedelta(days=1), "this week")),
    (r"\b(?:last|past|previous) month\b|\bmes passado\b", lambda t: _prev_month(t)),
    (r"\bthis month\b|\b(?:esse|este|nesse|neste) mes\b", lambda t: (t.replace(day=1) - timedelta(days=3), t + timedelta(days=1), "this month")),
    (r"\b(?:early|start of|beginning of) (?:this|the) year\b|\b(?:comeco|inicio) (?:do|deste|desse) ano\b",
     lambda t: (date(t.year, 1, 1), date(t.year, 4, 15), "early this year")),
    (r"\bthis year\b|\b(?:esse|este|nesse|neste) ano\b", lambda t: (date(t.year, 1, 1), t + timedelta(days=1), "this year")),
    (r"\blast year\b|\bano passado\b", lambda t: (date(t.year - 1, 1, 1), date(t.year, 1, 1), "last year")),
    (r"\brecently\b|\bthe other day\b|\brecentemente\b|\boutro dia\b|\bdia desses\b",
     lambda t: (t - timedelta(days=21), t + timedelta(days=1), "recently")),
]


def _prev_month(t: date) -> tuple[date, date, str]:
    first = t.replace(day=1)
    prev_last = first - timedelta(days=1)
    return prev_last.replace(day=1) - timedelta(days=4), first + timedelta(days=4), "last month"


def _month_window(month: int, year: int, part: str | None) -> tuple[date, date]:
    last = calendar.monthrange(year, month)[1]
    if part == "early":
        a, b = date(year, month, 1), date(year, month, min(12, last))
    elif part == "mid":
        a, b = date(year, month, 8), date(year, month, min(23, last))
    elif part == "late":
        a, b = date(year, month, 18), date(year, month, last)
    else:
        a, b = date(year, month, 1), date(year, month, last)
    return a - timedelta(days=4), b + timedelta(days=5)


_PART = {
    "early": "early", "beginning of": "early", "start of": "early", "comeco de": "early", "inicio de": "early",
    "mid": "mid", "mid-": "mid", "middle of": "mid", "meados de": "mid", "meio de": "mid",
    "late": "late", "end of": "late", "final de": "late", "fim de": "late", "finalzinho de": "late",
}
_PART_WORDS = "|".join(sorted((re.escape(k) for k in _PART), key=len, reverse=True))
_MONTH_WORDS = "|".join(sorted(_MONTHS, key=len, reverse=True))

_AGO_RE = re.compile(
    rf"{_FILLER}\b(?P<n>\d+|{_NUM_WORDS})\s+(?:of\s+)?(?P<unit>{_UNIT_WORDS})\s+(?:ago|back|atras)\b"
    rf"|\b(?:ha|faz)\s+(?:uns\s+|umas\s+|mais ou menos\s+)?(?P<n2>\d+|{_NUM_WORDS})\s+(?P<unit2>{_UNIT_WORDS})\b"
)
_MONTH_RE = re.compile(
    rf"(?P<cue>\b(?:in|back in|around|during|em|no|na|la em|la por|por|desde|since)\s+)?"
    rf"(?:(?P<part>{_PART_WORDS})\s*)?(?P<month>\b(?:{_MONTH_WORDS})\b)(?:\s+(?:of\s+|de\s+)?(?P<year>20\d\d)|\s+(?P<rel>(?:of\s+|do\s+|de\s+)?(?:last year|ano passado|this year|deste ano|desse ano|este ano)))?"
)


def _ago(n: int, unit_days: int, today: date) -> tuple[date, date]:
    center = today - timedelta(days=n * unit_days)
    if unit_days == 1:
        slack = 1 + n // 3
    elif unit_days == 7:
        slack = 4 + 2 * n
    elif unit_days == 30:
        slack = 15 + 5 * n
    else:
        slack = 120
    return center - timedelta(days=slack), min(today + timedelta(days=1), center + timedelta(days=slack + 1))


def find_time_expression(text: str, today: date) -> tuple[Window | None, str]:
    """Return (window, text without the time words). Matching is on accent-folded lowercase."""
    folded = _fold(text)
    for m in _MONTH_RE.finditer(folded):
        name = m.group("month")
        if name in _AMBIGUOUS_MONTHS and not (m.group("cue") or m.group("part") or m.group("year") or m.group("rel")):
            continue
        month = _MONTHS[name]
        rel = m.group("rel") or ""
        if m.group("year"):
            year = int(m.group("year"))
        elif "last year" in rel or "passado" in rel:
            year = today.year - 1
        elif rel:
            year = today.year
        else:  # a bare month is the most recent one that has started
            year = today.year if month <= today.month else today.year - 1
        part = _PART.get((m.group("part") or "").strip()) if m.group("part") else None
        a, b = _month_window(month, year, part)
        label = " ".join(x for x in (part, calendar.month_name[month], str(year)) if x)
        return Window(a, b, label), _cut(text, folded, m.start(), m.end())
    for pattern, fn in _RELATIVE:
        m = re.search(pattern, folded)
        if m:
            a, b, label = fn(today)
            return Window(a, b, label), _cut(text, folded, m.start(), m.end())
    m = _AGO_RE.search(folded)
    if m:
        n_raw = m.group("n") or m.group("n2")
        unit = (m.group("unit") or m.group("unit2")).rstrip("s") if not (m.group("unit") or m.group("unit2")).startswith("mes") else "mes"
        n = int(n_raw) if n_raw.isdigit() else _NUM.get(n_raw.strip(), 3)
        unit_days = _UNIT_DAYS.get(unit, _UNIT_DAYS.get(unit.rstrip("s"), 30))
        a, b = _ago(n, unit_days, today)
        return Window(a, b, m.group(0).strip()), _cut(text, folded, m.start(), m.end())
    return None, text


def resolve_when(phrase: str, today: date) -> Window | None:
    """Window for an explicit `when` argument; ISO dates and 'YYYY-MM' are accepted too."""
    phrase = (phrase or "").strip()
    if not phrase:
        return None
    iso = re.fullmatch(r"(\d{4})-(\d{2})(?:-(\d{2}))?", phrase)
    if iso:
        y, mo, d = int(iso.group(1)), int(iso.group(2)), iso.group(3)
        if d:
            day = date(y, mo, int(d))
            return Window(day - timedelta(days=1), day + timedelta(days=2), phrase)
        a, b = _month_window(mo, y, None)
        return Window(a, b, phrase)
    window, _ = find_time_expression(phrase, today)
    return window


def _cut(original: str, folded: str, start: int, end: int) -> str:
    # Folding can change lengths (e.g. "ç" → "c" keeps length, but NFKD ligatures may not), so
    # map back by position only when the lengths agree; otherwise keep the original text.
    if len(original) != len(folded):
        return original
    rest = (original[:start] + " " + original[end:]).strip()
    return re.sub(r"\s{2,}", " ", rest)
