---
name: prompt-budget
category: archie/voice
tags: [voice, openai-realtime, token-budget, system-prompt, summarizer, summary-cache, history, prompt-sizing, 16384-cap]
created: 2026-07-21
modified: 2026-10-07
summary: How the voice system prompt stays under OpenAI Realtime's 16,384-token cap without truncating anything; Rodrigo's two sizing rules.
source: curated (consolidated from memory notes assistant/architecture/voice_prompt_budget.md, assistant/architecture/feedback_dont_hard_cap_or_force_lossless_summary.md, assistant/voice/voice_lifecycle_and_wake_after_stop.md; verified against code 2026-10-06)
references:
  - architecture.md
  - lifecycle.md
  - openai-realtime.md
  - ../architecture/orchestrator.md
  - ../architecture/memory-and-search.md
---

# Voice system-prompt token budget

On every voice start the orchestrator builds a new system prompt from the
conversation JSONL (`OrchestratorSession.get_session_update()` in
`backend/orchestrator/session.py`). OpenAI Realtime sends that prompt as
`session.instructions`, and the API **rejects anything over 16,384 tokens**
with `Instructions cannot be longer than 16384 tokens, you have provided N`.
This doc explains how the prompt stays under that limit. Text mode has no
such cap but uses the same `build_system_prompt` and `token_budget` code.
Qwen and Gemini get the same prompt (Qwen also gets extra voice directives,
see [qwen-omni.md](qwen-omni.md)).

## Where it lives

| File | What |
|---|---|
| `backend/orchestrator/prompt.py` | `build_system_prompt()` joins the sections: role, datetime, self-reference, active sessions, MCP, memory (+ `ORCHESTRATOR_MEMORY_<provider>.md` in voice only), scripts (`run_script` allowlist), guidelines, history |
| `backend/orchestrator/token_budget.py` | the two budget constants, `estimate_tokens`, `estimate_message_tokens`, `truncate_tool_results`, `split_by_token_budget`, `summary_target_word_range` |
| `backend/orchestrator/session.py` | `_build_history_for_prompt()` (split + summary + cache), `_summarize_history()` (the summarizer prompt), `refresh_summary_cache_if_stale()`, `_resolve_summarizer_model()` |
| `backend/orchestrator/summary_cache.py` | sibling `<jsonl-stem>.summary.json` cache: `read`, `read_any`, `is_fresh`, `write` |
| `context/scripts/measure_voice_prompt.py` | measures the real prompt with tiktoken (private script, allowlisted for `run_script`) |

## The three cost buckets

The prompt is made of **static sections + verbatim history + summary**.
Measured on 2026-07-21 with tiktoken `o200k_base` on a live session of about
200 messages:

| Bucket | Sections | ~tokens |
|---|---|---|
| static | role, MCP, memory (injected `MEMORY.md` + `ORCHESTRATOR_MEMORY.md`), scripts, guidelines, active sessions | 5.5–6.0k |
| verbatim | newest history messages, rendered turn by turn | ~5.3k (at the old 6k budget) |
| summary | digest of everything older than the verbatim window | ~4.5k |
| total | | ~15.3k (about 1k under the cap) |

In the static bucket, memory is the largest section and guidelines come
second. Each injected memory file is cut at `MAX_MEMORY_CHARS = 12000`
characters (`prompt.py`).

## Constants (`token_budget.py`)

| Constant | Value | Meaning |
|---|---|---|
| `RECENT_VERBATIM_TOKENS` | 5,000 (estimated) | newest messages kept word for word; about 4k real tokens |
| `SUMMARY_SOFT_TARGET_TOKENS` | 3,500 | upper end of the summarizer's word *steering* range; not a cap |
| scaling factor | 0.18 of the prefix tokens | in `summary_target_word_range`; `max_words = min(3500*0.75, max(400, 0.18*prefix))`, `min_words = max(100, max_words // 3)` |
| `TOOL_RESULT_TRUNCATE_CHARS` | 700 | tool results in history are clipped, with a "re-read the file" hint |

**Which limit is real.** The binding limit is **16,384 tokens**. OpenAI
Realtime enforces it on `session.instructions` and rejects a longer prompt
with the error quoted at the top. No backend code checks it: the backend
never measures the assembled prompt or refuses to send it (and must not clamp
it, see the rules below). The cap is described in the header comment of
`token_budget.py` and appears as `CAP = 16_384` in `measure_voice_prompt.py`.
The two constants above, `RECENT_VERBATIM_TOKENS` and
`SUMMARY_SOFT_TARGET_TOKENS`, are sized against 16,384. (Fixed 2026-10-07:
three older design constants, `MODEL_CONTEXT_TOKENS` = 32k,
`MAX_VOICE_PROMPT_TOKENS` = 24k and `HISTORY_SECTION_TOKENS` = 18k, were
defined but never read, and predated the 16,384 cap. They were removed, along
with a `summary_target_word_range` docstring that still quoted the old
10,000-token target and a comment that still described the "every user
message" digest.) Qwen and Gemini
take the same prompt; no separate instruction cap has been seen on them.

## Two estimators

- `token_budget.estimate_tokens()` counts about 3.5 characters per token. All
  internal budgeting uses it. It **over-counts** compared with tiktoken, which
  is the safe direction.
- The API counts with **tiktoken `o200k_base`**. To check whether a prompt
  fits, measure with tiktoken:

```
context/scripts/run.sh context/scripts/measure_voice_prompt.py [session.jsonl] [--regen]
```

With no arguments it uses the newest orchestrator session and its cached
summary. `--regen` deletes the summary and builds it again through the live
summarizer (one LLM call) so you see the true fresh size. The script prints
each section with real token counts and says whether the prompt is over or
under the cap. Run it whenever a voice session fails with the 16,384 error.
The Jetson venv has tiktoken; the laptop venv may not.

## How history is split (`_build_history_for_prompt`)

1. Load the JSONL with `HistoryLoader` and clip big tool results
   (`truncate_tool_results`), so a 50 KB file dump does not push out real turns.
2. `split_by_token_budget(clipped, RECENT_VERBATIM_TOKENS)` walks back from the
   newest message and keeps messages verbatim until the budget runs out.
   Everything older is the *prefix* to summarise.
3. Get the summary of the prefix:
   - **HIT**: `summary_cache.read()` is fresh. The key is the JSONL's
     `(size, mtime_ns)`. No LLM call.
   - **STALE-REUSE**: the cache is stale but `read_any()` returns a summary whose
     `input_message_count <= len(older)`. Reuse it. If the prefix grew, move the
     new messages back into the verbatim window
     (`recent = older[stale.input_message_count:] + recent`), so nothing is lost.
   - **MISS**: call `_summarize_history()` synchronously and write the result to
     the cache.

The log lines `history summary cache HIT / STALE-REUSE / MISS` show which path
ran.

The summary also covers recent turns in substance. So **lowering the
verbatim budget never loses information**: fewer messages are kept word for
word, and the rest are in the digest. In steady state the two do not overlap
(the cached `input_message_count == len(older)`).

### Keeping the cache warm

A synchronous summary on a long session takes 15–90 s. It used to sit between
`voice_start` and `session_started`, so the second wake word after a call
froze for 60–90 s. Three triggers keep the cache warm now:

- `end_voice` starts a background `refresh_summary_cache_if_stale()` task (see
  [lifecycle.md](lifecycle.md)).
- A text `start` that opens a new orchestrator session (`_handle_start` in
  `backend/api/routes/orchestrator.py`) starts the same refresh, so a wake word
  a few seconds later finds it warm.
- STALE-REUSE covers a restart that comes before the refresh finishes.

When the cache is not fresh at voice start, the route sends
`voice_status: summarizing` to the initiator only. The UI then shows
"Preparing conversation…".

### The stale-reuse spike

If voice is stopped and restarted before the background refresh finishes, the
stale-reuse path moves every message added since the last summary back into
the verbatim window. On a very long session this can push the prompt over the
cap until the refresh catches up. This happened on 2026-07-22 with a session of
about 420 messages. The promotion is **lossless on purpose**. Don't "fix" the
spike by truncating it. Handle it with **margin**: the verbatim budget is 5k,
not the ~6k a steady-state prompt could afford.

## The summarizer must summarise, not transcribe

`_summarize_history()` builds a digest with these sections: Conversation arc ·
Topics covered · Notable user asks & moments · Decisions & conclusions · Open
threads · Key entities · User preferences & context · Handling voice
transcription errors. It sends `target_words` as a *soft* range. The API call
has no output cap (no `max_tokens` on OpenAI; 64k on Anthropic).

The model comes from `summarizer_model` in `assistant_config.json`, which is
re-read on every call. The default is `DEFAULT_SUMMARIZER_MODEL = "gpt-5.1"`.
If the id can't be classified, the active orchestrator model is used.

**The trap, fixed in `3b0671d`.** An earlier summarizer prompt (`dc34265`) had
a section titled "Every user message (short)", which required one bullet per
user line with nothing dropped. That made the digest an almost full
transcript that grew linearly with the number of turns. On a long session it
grew past ~6.5k tokens and pushed the whole prompt over the cap. Tuning the
target size could not fix it, because the rule itself forced the growth. Now
the section is "Notable user asks & moments": real requests and how intent
changed, with repetition merged and filler skipped. A digest now scales with
the **number of topics, not the number of turns**. A 185-turn session and a
500-turn session on the same topics give digests of similar size.

## Rodrigo's two prompt-sizing rules

These two rules are separate, and both apply to any budgeted prompt, not only
voice. Mixing them up once caused a wrong fix.

1. **Never hard-cap or truncate the assembled output.** No clamp after the
   prompt is built, no "drop the oldest verbatim turn until it fits", no
   truncating the summary text after it is generated. Nothing that can
   silently drop information the model would otherwise see. In his words: "I
   don't want any hard caps! This is my rule for avoiding any silently missed
   information at all costs."
2. **Never force the summarizer to be lossless.** Rule 1 does not mean the
   summary must reproduce every turn. "My rule was for never hard capping,
   never to force the summarizer in that way! This just defeats its purpose as
   a summarizer." A summary distils: it covers every distinct topic,
   decision and open thread, and merges repetition.

**When the prompt needs more room**, use only lossless levers:

- trim the **static** sections, especially the injected memory index. It is a
  two-level index, so the root should point to the sub-`INDEX.md` files, not
  copy them;
- lower the **verbatim** budget, because recent turns are in the summary too;
- make the summarizer **distil harder**: denser prose, but the same topics.

## History of fixes

| Commit | Change | Effect |
|---|---|---|
| `2f8c8ac` (2026-06-04) | STALE-REUSE path + `summary_cache.read_any()` + pre-warm on `end_voice` | wake after stop: 60–90 s → 130 ms |
| `e2c7868` (2026-07-21) | verbatim 7k→6k; compact scripts section; slimmer root `MEMORY.md` | 16.7k → 15.3k |
| `bdc82aa` | `SUMMARY_SOFT_TARGET_TOKENS` 10k→3.5k, scaling 0.30→0.18 | summary target sized to the real cap |
| `3b0671d` | summarizer: removed the "every user message" rule | root fix: summary size no longer grows with turn count |
| `0888f13` (2026-07-22) | verbatim 6k→5k | margin for the stale-reuse spike; the 420-message session went from 15.7k to 14.9k |
