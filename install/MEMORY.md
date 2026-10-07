# Memory Index

Root index for `context/memory/`. This is a **two-level index**: this file holds the memory rules,
the folder ontology and each folder's key files; each folder's `INDEX.md` lists every file in it.
To find something, open the folder's `INDEX.md`.

## Ontology

| Folder | What goes there |
|---|---|
| `archie/` | **Archie's documentation** — how the assistant itself works. A symlink to `docs/` in the code repo (created by `shared/scripts/setup-context.sh`), versioned with the code. Start at [archie/INDEX.md](archie/INDEX.md). Public: no personal or secret content. |
| `people/` | People in the user's life, one folder each (start with the user: `people/<name>/`) |
| `projects/` | The user's projects, one folder each, plus `projects/INDEX.md` |
| `deployment/` | Private specifics of this installation: addresses, credential locations, backups |
| `references/` | External source material |

Create folders as you need them — folders are cheap, misplaced files are expensive — and add each
new folder to this table and give it an `INDEX.md`.

## Rules

Every note starts with YAML frontmatter (except `INDEX.md` files, this file and
`ORCHESTRATOR_*.md`):

```yaml
---
name: <filename without .md>
category: <folder path, e.g. people/alex>
tags: [<keyword>, ...]
created: <YYYY-MM-DD>
modified: <YYYY-MM-DD>
summary: <one line, ≤ 20 words>
source: <session UUID + title, OR "curated (<reason>)", OR external URL>
references:
  - <relative/path/to/another-note.md>
---
```

- Reuse before creating: extend the closest existing note.
- Cross-link with relative markdown links and list them in `references:`; keep links bidirectional.
- Add a one-line entry to the folder's `INDEX.md` in the same edit.
- `context/` is gitignored by the code repo, so a search from the repo root skips it — pass an
  explicit `context/memory/` path. Ripgrep doesn't follow the `archie/` symlink: grep `docs/` for
  the docs.

## Quick reference

1. Start the backend: `context/scripts/run.sh -m uvicorn api.app:create_app --factory --port 8765`
2. Start the frontend (new terminal): `cd apps/web && npm run dev`
3. Open https://localhost:5450 (http:// when no certificate is in `context/certs/`)

| Command | Description |
|---|---|
| `/recall <query>` | Search memory and conversation history |
| `/scaffold-skill` | Create a new skill |
| `/scaffold-agent` | Create a new agent |

## Key files

### archie/ → [index](archie/INDEX.md)
- [overview/archie.md](archie/overview/archie.md) — what the assistant is and how it is built.

### people/
_Add the user's folder (e.g. `people/<name>/<name>_context.md`) as you learn about them._

### projects/
_Add active projects here, one line each, with a link to the project's start file._
