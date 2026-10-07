# projects/

Workstreams on Archie itself — larger efforts with their own charter, plans, inventories, progress
logs and results. They record how a change was planned and verified; the resulting system is
described in the topic docs (`architecture/`, `clients/`, `specs/`, …). Personal projects that are
not about Archie live in private memory, not here.

| Project | Dates | Status | Start with |
|---|---|---|---|
| [frontend-refactor/](frontend-refactor/README.md) | 2026-10-03 → | Cut over 2026-10-05; field tests and remaining bugs tracked in the handoff | [README.md](frontend-refactor/README.md) (charter, decisions D1–D8), [plan/23-coordinator-handoff.md](frontend-refactor/plan/23-coordinator-handoff.md) (resume point) |
| [history-search/](history-search/RESULTS.md) | 2026-10-06 | Shipped; open items in RESULTS | [PLAN.md](history-search/PLAN.md), [RESULTS.md](history-search/RESULTS.md) |

## frontend-refactor/ — the 2026-10 web + Android rebuild
- [frontend-refactor/README.md](frontend-refactor/README.md) — Charter: goals, issues R1–R7, decisions D1–D8, document map, phases, working rules.
- [frontend-refactor/audit/00-visual-audit.md](frontend-refactor/audit/00-visual-audit.md) — Screenshot audit of the old apps (screenshots gitignored).
- [frontend-refactor/inventory/](frontend-refactor/inventory/01-backend-api.md) — Feature inventories of the old system: 01 backend API, 02 web frontend, 03 Android app, 04 Android voice and device (constants, state machines, 46 regression scenarios).
- [frontend-refactor/mockups/archie-mockups.html](frontend-refactor/mockups/archie-mockups.html) — Approved high-fidelity mockups of every key screen.
- [frontend-refactor/plan/](frontend-refactor/plan/23-coordinator-handoff.md) — 20 decisions and backend changes, 21 work plan, 22 progress log, 23 coordinator handoff, field-test notes for the A300M and the POCO.
- The four specs it produced are in [../specs/](../specs/INDEX.md).

## history-search/ — the 2026-10-06 search rebuild
- [history-search/PLAN.md](history-search/PLAN.md) — Goal, eval method and the improvement plan for conversation + memory search.
- [history-search/RESULTS.md](history-search/RESULTS.md) — Frozen eval set, baseline and measured results per change, held-out test, open items.
