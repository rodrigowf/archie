---
name: recall
description: Search memory and conversation history for relevant information. Use when you need to find past decisions, patterns, or context.
argument-hint: "<query> [--n COUNT]"
allowed-tools: Bash(context/scripts/run.sh *), Read
---

# Recall: $ARGUMENTS

Search the memory files and the past conversations for information related to the query.

## Arguments

- `$0`: The search query (required)
- `--n COUNT`: Number of results per source (default: 5); for history this counts sessions

## What Gets Searched

- **memory**: the memory files under context/memory/ (semantic search)
- **history**: past conversation transcripts from every harness (Claude Code, orchestrator,
  Qwen, Gemini) — hybrid keyword + semantic search, grouped by session

The indexes are updated automatically:
- Memory: indexed immediately when files change (file watcher)
- History: indexed every 5 minutes by the API server (incremental; only new messages are embedded)

## Steps

1. Parse arguments. Extract the query and optional `--n` value from `$ARGUMENTS`.
   Default to 5 results if not specified. Increase for broader searches, decrease for focused lookups.

2. Run the search script against both collections:

   For memory: context/scripts/run.sh context/scripts/search.py <query> --collection memory --n <count>
   For history: context/scripts/run.sh context/scripts/search.py <query> --collection history --n <count>

   History options: `--after YYYY-MM-DD`, `--before YYYY-MM-DD`, `--exclude <session_id>` (pass
   ${CLAUDE_SESSION_ID} to leave out the current conversation), `--json`.

3. Review the results.
   - Memory results: `text`, `file_path`, `start_line` / `end_line`, `distance` (lower = more relevant).
     Read the file at those lines for full context.
   - History results: one block per session with title, session id, date, `relevance`
     (`strong` or `weak`) and up to 3 matching excerpts, each with its turn number and
     whether it matched by keyword, meaning, or both. To read around an excerpt:
     context/scripts/run.sh -c "import sys; sys.path.insert(0,'backend'); from utils.history_index import read_turns; import json; print(json.dumps(read_turns('<session_id>', turn=<turn>), indent=1, ensure_ascii=False))"

4. Synthesize and present the findings to the user, citing memory files or session titles/dates.
   If every history result is `weak`, say nothing clearly matching was found.

## Notes

- If the index is empty, run: context/scripts/run.sh context/scripts/index-memory.py
- If the history index is missing, run: context/scripts/run.sh context/scripts/index-memory.py --history-only
- Memory distance interpretation: < 0.3 = highly relevant, 0.3-0.7 = relevant, > 1.0 = likely noise
- Memory files are at: context/memory/
- Session files are at: context/*.jsonl and context/chats/*.jsonl
