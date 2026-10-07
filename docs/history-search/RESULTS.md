# Conversation-history retrieval: results log

Eval set (frozen 2026-10-06): 110 sessions sampled from the 144 with ≥ 6 turns and ≥ 150 user
words, split once at random into **dev** (66 sessions, 130 questions) and **test** (44 sessions,
86 questions); 2 sessions skipped as mic tests. Questions were written by separate Claude
agents from the transcripts (titles hidden), as spoken requests made months later: 160 EN /
56 PT; types detail, topic, resume, temporal, summary. Plus 30 grep-verified **negatives**
(conversations that never happened; 15 dev / 15 test). Data: `context/evals/history_search/`
(private). Code: `shared/scripts/history_eval/`.

Retrieval metrics use the user's utterance verbatim as the query (an agent that just forwards
what it heard). Agent metrics run the real voice system prompt and tool definitions.

## Baseline (commit `9bff395`, all-MiniLM-L6-v2, before this plan)

Retrieval, dev / test:

| | hit@1 | hit@5 | MRR@10 | negatives falsely "strong" |
|---|---|---|---|---|
| dev | 0.48 | 0.77 | 0.60 | 0.40 |
| test | 0.57 | 0.80 | 0.67 | 0.27 |

Weak spots (dev hit@5): **Portuguese 0.46 vs English 0.89**; temporal 0.67. Most Portuguese
misses rank unrelated *Portuguese-language* sessions first: the English-only embedding
clusters by language, not topic.

Agent, dev (realtime: fixed 60-question sample + 15 negatives; gpt-4o-mini: all 130 + 15):

| model | located | surfaced@3 | opened right | opened wrong | negatives rejected | calls | s |
|---|---|---|---|---|---|---|---|
| gpt-realtime-1.5 | 0.63 | 0.77 | 0.52 | 0.20 | 0.93 | 2.0 | 7.7 |
| gpt-4o-mini | 0.55 | 0.65 | 0.32 | 0.29 | 0.67 | 2.2 | 10.3 |

## Stage 2 — time understanding (retrieval, dev)

Time phrase detected in the request (EN/PT), removed from the query, applied as:

| mode | hit@5 all | hit@5 temporal | MRR temporal |
|---|---|---|---|
| none (= baseline) | 0.77 | 0.67 | 0.49 |
| **filter** (fallback to all time when empty) | **0.80** | **0.78** | **0.54** |
| boost ×1.6 | 0.78 | 0.70 | 0.51 |

Kept: **filter**. The refactored searcher reproduces the baseline exactly with time off.
Later replaced by **prefer** (in-window first, then strong out-of-window matches, flagged): identical
on these author-checked dates, robust to misremembered ones (the agent run showed a "early April"
request for a mid-April session filtered away).

## Stage 2 — navigation kit, agent level (gpt-realtime-1.5, same 75 dev questions)

| run | located | surfaced@3 | opened wrong | negatives rejected | calls |
|---|---|---|---|---|---|
| baseline | 0.63 | 0.77 | 0.20 | 0.93 | 2.0 |
| kit, first version | 0.53 | 0.60 | 0.17 | 0.73 | 1.9 |
| **kit, fixed** | **0.73** | **0.80** | **0.07** | 0.87 | **1.4** |

First version regressed: the model set `kind` on most calls, often wrongly, and `kind` was a
hard filter; it also reopened sessions it was only asked to find, and presented weak matches.
Fixes: `kind` and remembered times became *preferences* (others still shown, flagged);
weak-only results come back as `weak_matches` with an explicit note; the prompt says finding
is not resuming. Calibration of "strong" moved negatives falsely strong 0.53 → 0.13 at a cost
of 0.91 → 0.81 strong-when-found (all-MiniLM-L6-v2).

## Embedding model (retrieval, dev, time=prefer)

Same chunks re-embedded; only the model changes.

| model | hit@1 | hit@5 | MRR | PT hit@5 | speed (laptop) |
|---|---|---|---|---|---|
| all-MiniLM-L6-v2 (baseline) | 0.48 | 0.80 | 0.62 | 0.51 | 383/s |
| **paraphrase-multilingual-MiniLM-L12-v2** | 0.49 | **0.92** | 0.67 | **0.81** | 275/s |
| multilingual-e5-small (+summaries) | 0.47 | 0.83 | 0.63 | 0.59 | 289/s |
| granite-embedding-107m-multilingual | — | — | — | — | 81/s: too slow for the Jetson, dropped |
| embeddinggemma-300m | — | — | — | — | gated repo, not tested |

Kept: **paraphrase-multilingual-MiniLM-L12-v2** (Jetson: 2.3 chunks/s, 152 ms per query).
Chunks need `max_seq_length = 256` (its default 128 would cut every chunk).

## Stage 3 — session summaries, session scoring, calibration (retrieval, dev)

gpt-4.1-mini summaries for 204 sessions (title, summary, topics, names, decisions, EN+PT
keywords), indexed as an extra chunk per session (~$1 one-off).

| step | hit@1 | hit@5 | MRR |
|---|---|---|---|
| multilingual | 0.49 | 0.92 | 0.67 |
| + summaries | 0.50 | 0.94 | 0.69 |
| + session = best chunk only (sums favoured long sessions) | **0.57** | 0.92 | **0.73** |

Strength thresholds recalibrated per model (multilingual: sim ≥ 0.62, or share ≥ 0.60, or
share ≥ 0.40 & sim ≥ 0.50): negatives falsely strong 0.00, correct found strong 0.89.

## Stage 5 — LLM re-rank (moved ahead of stage 4; retrieval, dev, production path)

| re-ranker (top 8) | hit@1 | hit@3 | MRR | negatives rejected | real matches dropped |
|---|---|---|---|---|---|
| none | 0.57 | 0.89 | 0.73 | — | — |
| gpt-4o-mini | 0.35 | 0.41 | 0.38 | 1.00 | 0.55 |
| **gpt-4.1-mini** | **0.72** | 0.87 | **0.79** | **1.00** | 0.06 |

Production keeps every candidate (confirmed first and strong, the rest after and weak):

| production path, dev | hit@1 | hit@5 | MRR | PT hit@1 | strong when found | negatives falsely strong | latency |
|---|---|---|---|---|---|---|---|
| baseline | 0.48 | 0.77 | 0.60 | 0.30 | 0.90 | 0.40 | 0.1 s |
| **now** | **0.73** | **0.94** | **0.82** | **0.73** | 0.94 | **0.00** | 1.1 s |

## Memory (retrieval, dev: 76 questions on 38 notes)

| index | hit@1 | hit@5 | MRR | PT hit@5 |
|---|---|---|---|---|
| chroma `memory` collection (baseline) | 0.42 | 0.70 | 0.55 | 0.21 |
| SQLite, heading-aware chunks, all-MiniLM-L6-v2 | 0.53 | 0.75 | 0.63 | 0.37 |
| + multilingual | 0.54 | 0.86 | 0.67 | 0.79 |
| + note = best section only | **0.62** | **0.92** | **0.73** | — |

Chroma is retired: both indexes are SQLite files, the warm server only keeps the model loaded.

## Stage 4 — contextual chunk headers (retrieval, dev, production path without re-rank)

Header embedded in front of each excerpt (not keyword-indexed). Full re-embed per variant.

| variant | hit@1 | hit@5 | MRR | negatives falsely strong |
|---|---|---|---|---|
| none | 0.57 | 0.92 | 0.73 | 0.00 |
| A: `[title · date · speaker]` | 0.56 | 0.92 | 0.71 | 0.07 |

A not kept (no gain; the session summary already carries the session-level context).
| C: A + gist of the turn being answered | 0.60 | 0.92 | 0.74 | 0.20 |

C not kept either: +0.03 hit@1 (4 of 130 questions, within noise) for a full re-embed whenever a
title changes, and it pushes never-discussed topics over the "strong" line (would need
recalibration). Final configuration: multilingual model, summaries, best-chunk session score,
per-model strength thresholds, time/kind preferences, gpt-4.1-mini re-rank.

## Held-out test (scored once, final configuration)

**History** (86 questions + 15 negatives), production path:

| | hit@1 | hit@5 | MRR | PT hit@5 | negatives falsely strong |
|---|---|---|---|---|---|
| baseline | 0.57 | 0.80 | 0.67 | 0.26 | 0.27 |
| final, without re-rank | 0.55 | **0.91** | 0.70 | **0.89** | 0.27 |
| **final, with re-rank** | **0.70** | **0.92** | **0.80** | **0.89** | **0.13** |

Honest reading: recall and Portuguese generalise (hit@5 +0.11, PT +0.63). Two dev gains did
**not** generalise without the re-ranker: top-1 stays flat, and the strength thresholds that
gave 0.00 false "strong" on dev give 0.27 here — they were partly fitted to the 15 dev
negatives. The re-ranker (dev: top-1 0.57 → 0.73, every negative rejected) is the component
meant to fix both — and on test it does: top-1 0.57 → 0.70, MRR 0.67 → 0.80, Portuguese top-1
0.16 → 0.74, negatives falsely strong 0.27 → 0.13 (2 of 15). Cost of that run: ~$0.15
(gpt-4.1-mini). No parameter was changed after seeing test.

**Memory** (52 questions):

| | hit@1 | hit@5 | MRR |
|---|---|---|---|
| chroma baseline | 0.46 | 0.65 | 0.55 |
| final | **0.58** | **0.85** | **0.67** |
