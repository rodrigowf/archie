---
name: refactor-methodology
category: archie/operations
tags: [methodology, refactor, parity-tests, tdd, source-fidelity, on-device-verification, working-agreement]
created: 2026-06-10
modified: 2026-10-06
summary: The working agreement for structural refactors of tuned code — source fidelity, parity tests first, on-device checks, detours, done.
source: curated (consolidated from memory notes assistant/architecture/refactor_methodology.md, feedback_use_adb_input_for_device_tests.md, feedback_dont_touch_wake_word_tuning.md, feedback_subagents_no_git_writes.md, docs/projects/frontend-refactor/README.md (D1, quality gates); verified against code 2026-10-06)
references:
  - working-rules.md
  - debugging.md
  - ../infrastructure/topology.md
  - ../voice/architecture.md
  - ../voice/wake-word.md
  - ../clients/android.md
  - ../clients/web.md
  - ../overview/decisions.md
---

# Refactor methodology

Several of Archie's subsystems — the voice stack, the wake word, the Android view-model layer — are
**fine-tuned despite their structure**: timing constants, RMS thresholds, retry cadences, HAL settle
delays and audio-mode ownership that nobody fully remembers the reason for, but that empirically
work. Naive cleanup of such code is destructive. This agreement governs any structural refactor of
them, and was the contract for the 2026-06 voice, wake-word and view-model refactors and the 2026-10
frontend/Android rebuild.

## When it applies

Use it when the code has any of: tuned constants or cadences; cross-component choreography where
the timing *is* the contract (200 ms HAL settles, mic-acquire retry loops, 400 ms pool-probe
retries); fixes for past incidents baked in; several consumers that can't absorb behaviour drift.
Don't use it for greenfield code or code whose behaviour isn't load-bearing — the overhead is the
point only where one broken voice reconnect costs more than the process.

## The rules

### 1. Source fidelity

Before writing new code for a step:

1. Read the existing source **in full** at HEAD (from `def`/`fun` to the closing brace), not the
   line references in a plan — those drift.
2. List the tuned behaviour that must survive and the invariants it relies on.
3. Cite the original location with a sha (`session.py:1284-1589 as of <sha>`).
4. Preserve **byte-identically** by default: timing constants, sleeps, retry counts, thresholds,
   error classifications, filesystem side effects (JSONL writes, recordings), broadcast event shapes,
   wire formats, preference keys, persisted ids, and log line text (tools grep for it).
5. A changed tuned behaviour needs explicit authorisation with a reason; anything else is reverted
   in review.

The refactor changes **who** does things, not **what** they do.

### 2. Test first

Each step: write the parity test for existing behaviour and watch it **pass on current HEAD**;
write the test for new behaviour and watch it **fail**; implement; both green; verify on device;
commit. If a test is hard to write because the boundary is wrong, redesign the boundary — don't
skip the test.

### 3. Parity tests before the change

- Pick the smallest pure unit that shows the behaviour (a provider event sequence, streaming-block
  ordering, a recovery state machine's single-flight contract).
- Write the expected output **by tracing the current code**, with a comment citing the lines that
  produce it — not a blind snapshot — so the next reader knows why the assertion holds.
- For sequences, capture the full ordered trace: "renders at the same moments" is part of the
  contract.
- Extract predicates to pure functions (companion objects in Kotlin) so they test in plain JUnit
  instead of Robolectric/instrumented tests (e.g. `shouldDedupeWakeStart`,
  `shouldShortCircuitStart`).

Where they live now:

| Area | Path |
|---|---|
| Backend | `backend/tests/parity/test_<area>_<step>_parity.py` (voice reconnect/goAway, handle invalidation, error classification, VAD defaults, persister) |
| Android | `apps/android/core/{audio,voice,voice-host,wakeword}/src/test/…/parity/` |
| Client protocol (web + Android) | conformance fixtures in `apps/protocol-fixtures/` (recorded transcripts incl. interleaved text/tool streams), run by both test suites |

### 4. On-device verification

Every step includes a real-device check driven by the agent over adb — no human taps
([working-rules.md](working-rules.md#testing-on-devices)): `adb shell input`, `uiautomator dump`,
logcat with marker lines (`log -t INC<N>_MARKER 'phase X'`, or temporary
`[REFACTOR_VERIFY]` lines removed before the final commit). Each step lists its tap sequence and
expected log pattern. Verify against the **laptop** backend; don't redeploy or restart the Jetson
mid-cycle. Practical notes: on Lollipop, swipes ≥ 300 ms are absorbed by Compose, ≤ 200 ms fling
reliably (`input swipe 270 800 270 150 100`); re-dump after every scroll; bookend `logcat -c` /
`logcat -d` with markers rather than backgrounding a streamer.

### 5. Public-surface preservation

Every public method and flow of the class being decomposed keeps its name, type and behaviour
through the whole chain; internally it delegates to the new components, so consumers see no diff and
steps land one at a time. A reflection-based surface test (`PublicSurfaceStabilityTest` in the
view-model refactor) catches accidental drift. Only the final step audits and removes what became
redundant.

### 6. Detour discipline

A detour is a commit **between** plan steps because a device test surfaced something real the plan
didn't foresee: a genuine bug, a structural cause that must be fixed first, an evidence-driven
re-evaluation of a banned constant, an atomic rename. Detours get their own commit and approval, are
referenced (not absorbed) in the plan log so step numbering stays intact, and a detour that changes
a previously banned constant needs **empirical justification** — a captured log showing the symptom
and the value. A deeper structural discovery gets written down where the next planning pass will
read it.

### 7. Enforcement

- Every commit names its step (`Inc 3`, `Detour 5`) and carries: plan citation, source-fidelity sha,
  preserved-behaviour list, test method, on-device result, relevant field observations.
- A step that lacks its required passing parity test doesn't ship.
- Rodrigo approves each step before the next starts.
- Real logcat / journalctl capture after every step.

### 8. Working-tree hygiene

- Feature branches off `local`; push after each step. The two machines may be on different branches
  ([topology.md](../infrastructure/topology.md#branches)); context-sync never moves code.
- Don't `git stash` to check whether a failure pre-exists — `git stash pop` can apply a foreign stash
  from another branch (it happened in the view-model refactor and in 2026-10). Use a separate
  `git worktree`. Subagents never run git writes.

### 9. Definition of done

- The god-class is replaced by coherent components with clean boundaries, each with a parity-test
  file pinning its tuned behaviour.
- The load-bearing bugs the refactor targeted no longer reproduce.
- The most-tuned subsystem behaves identically to the old HEAD on the reference device.
- A log records each step's commits, source shas, preserved behaviour and deviations; a boundaries
  document captures any new cross-component event flow.
- Afterwards the consolidated architecture doc is the starting point; plans and logs can be deleted
  (git keeps them).

## Applied: the 2026-10 frontend and Android rebuild

The 2026-10 rebuild (charter: `docs/projects/frontend-refactor/README.md`) replaced both clients rather than
refactoring them in place, and adapted the agreement to a rewrite:

- **D1 (2026-10-03, Rodrigo):** the Android voice, audio and wake-word stack was **rewritten from
  scratch**, with **every tuned constant and timing ported verbatim** and covered by parity tests.
  The constants were inventoried with their sources (`inventory/04-android-voice-and-device.md`: 46
  regression scenarios), the parity suites in `apps/android/core/*/…/parity/` pin them, and the
  tuned values now live in named holders such as `VoiceTuning.kt`, `HostTuning.kt` and
  `NetworkTuning.kt`.
- Every non-obvious ported behaviour cites its old `file:line` in the spec, so load-bearing and
  accidental behaviour can be told apart.
- The client protocol got a normative spec (`docs/specs/12-client-protocol.md`) and
  shared conformance fixtures run by both the web and Android suites.
- New code was built side by side (`frontend-next/`, `android-next/`) so the old apps kept working
  until acceptance; the old code is frozen in `legacy/`.
- Field tests on the real devices (POCO X7 Pro, A300M) were the acceptance gate, with hands-on
  checks before instrumented suites ([working-rules.md](working-rules.md#testing-on-devices)).
- Each work package had a written brief, one owner, explicit file boundaries and a definition of
  done; ≤ 4 parallel implementation agents; one emulator/browser at a time; coordinator-only commits.

See [wake-word.md](../voice/wake-word.md), [architecture.md](../voice/architecture.md),
[android.md](../clients/android.md) and [decisions.md](../overview/decisions.md).

## History

- 2026-06-09/10: agreement written for the voice + wake-word refactors and the Android view-model
  refactor (detours such as the gain-corruption fix `d6181b1`, single-ingress `9200d50`, wake-word
  RMS retune `c60cd08` with logcat evidence, naming swap `d226027`).
- 2026-10-03 → 10-05: applied to the full client rebuild (D1); cut over 2026-10-05.
