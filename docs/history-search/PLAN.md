# Conversation-history retrieval: improvement plan

Started 2026-10-06, after the SQLite rebuild of `search_history` (commit `ab2f224`).

## Goal

The orchestrator, and above all the **voice** orchestrator, must locate past conversations
(orchestrator, Claude, Qwen/Gemini sessions) by what the user means, say what they were about,
and jump into them: read them, or reopen agent sessions with `open_agent_session(resume_sdk_id)`.
A voice turn can afford one or two quick tool calls; deep recall is delegated to a Claude
session that can read every file.

## Method: measure, change one thing, keep it only if it wins

- **Eval data is private** (it quotes Rodrigo's conversations): `context/evals/history_search/`.
  The code is public: `shared/scripts/history_eval/`.
- **No overfitting.** Questions are written by a separate model from a sample of sessions,
  *before* any tuning, phrased the way a person recalls something months later (paraphrased,
  partly misremembered, PT and EN, vague and specific, temporal, "open the session where…").
  Sessions are split once, at random: **dev** (tuning) and **test** (held out; scored only at
  the end of each stage, never used to choose a parameter). Negative questions (things never
  discussed) check that the system says "not found" instead of returning noise.
- **Two levels of evaluation.**
  1. *Retrieval*: session-level recall@1/@5, MRR@10, per question type; negatives: share of
     queries whose top result is `weak`/empty; latency.
  2. *Agent*: an OpenAI tool-calling model plays the voice orchestrator with the real tool
     definitions and a small call budget; success = it names (or would open) the right
     session. Measures what actually matters for voice.
- Every stage: unit tests, eval on dev, keep/revert decision recorded in `RESULTS.md`.

## Stages

1. **Eval harness** — question generation, dev/test split, retrieval runner, agent runner,
   baseline numbers for the current system.
2. **Navigation kit** — what the voice agent needs to find and enter conversations:
   - `search_history`: several phrasings in one call (fused server-side), natural-language
     time (`when: "last week"`, "ontem") resolved on the server, `kind` filter, session scope;
     each result carries kind, working directory, file path, and how to open it.
   - `list_conversations`: browse by date range / title / kind, newest first, paged.
   - `grep_conversation`: exact words or a pattern inside one session → turn numbers.
   - `read_conversation`: explicit ranges, full text of long turns (paged), "more" hints.
   - Searches cover every kind of conversation (orchestrator, Claude, Qwen, Gemini) by default;
     `kind` narrows it (`orchestrator`, `agent`).
2b. **Memory joins in** — the voice agent should always learn, in the same call, whether a
   conversation was already extracted into memory:
   - each conversation result lists the memory files whose frontmatter `source:` names it
     ("digested into"), and each memory hit lists the conversations it came from;
   - `search_history` also returns the best memory matches for the same query (one call);
   - memory moves off chroma onto the same SQLite design (FTS5 + embeddings, heading- and
     frontmatter-aware chunks), with memory questions added to the eval;
   - memory navigation: browse folders/INDEX files, grep across memory, read any line range.
3. **Session summaries** (write-time, cheap API model): title, short summary, topics, names,
   decisions, bilingual keywords per session, indexed as their own documents and shown in
   results and listings.
4. **Contextual chunk headers**: session title · date · speaker · one-line summary prepended
   to each excerpt before embedding and keyword indexing.
5. **Gated re-rank** of the top candidates by a cheap LLM — only if the eval shows a gain
   worth the latency.
6. **Multilingual embedding model** — only if Portuguese recall still lags on the eval.
7. **Held-out test, deploy, docs.**
