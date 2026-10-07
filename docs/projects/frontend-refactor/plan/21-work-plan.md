# 21 — Master work plan

Sources: web WBS (`spec/13` §7, W-xx), Android WBS (`spec/14` §7, A/B/C/D-xx), backend (`plan/20` §A).
Owner of this plan: coordinator (Claude). Progress is tracked in `22-progress-log.md`.

## 1. Milestones & Rodrigo checkpoints

| Milestone | Content | Rodrigo checkpoint |
|---|---|---|
| **M0 Specs** | inventory, specs 11–14, tokens, fixtures, mockups, decisions | ✅ structure, mockups and tokens approved (2026-10-03) |
| **M1 Foundations** | backend changes; web W-01/02/05/08; Android A-01/02/04, B-01/02 | review backend diff before Jetson deploy |
| **M2 Web preview** ✅ deployed 2026-10-04 | web waves 2–4 → new web app live at `/next/` + `/next-compat/` on the Jetson | **try it on desktop, phone, iPad** |
| **M3 Android main** | Android waves 2–5 (main app) after the voice-parity gate G-01 | **install Archie on the POCO** |
| **M4 Lite** | C-01 + field tests on the A300M (48 h soak) | **live with it on the A300M** |
| **M5 Cutover** | W-15 / D-01: replace `frontend/`, `frontend-compat/`, `android/`; update skills/docs | **sign-off** |

## 2. Waves (merged)

```
Wave 0  backend changes (BF/BX/O) · W-01 scaffold · A-01 gradle skeleton
Wave 1  W-02 styles · W-05 protocol client · W-08 markdown · A-02 model/reducer · A-04 voice parity tests · B-01 design · B-02 markdown
Wave 2  W-03 UI kit I · W-04 UI kit II · W-06 services/stores · A-03 network · A-05 audio · A-07 wake word · B-03 data+shell
Wave 3  W-07 shell · W-10 tool cards · W-13 settings · W-14 history/memory/visuals · W-12 voice
        A-06 voice session · B-04 chat · B-05 tool cards · B-06 sessions · B-07 memory/visuals · B-08 settings · C-01 lite (fakes)
Wave 4  W-09 conversation · W-11 composer → M2 web preview · A-08 voice host → G-01 gate
Wave 5  B-09 system integration · C-01 wiring → D-01 field tests · W-15 hardening → M5 cutover
```

Concurrency cap: **at most 4 implementation agents at once** (hard limit: 6 parallel agents exhausted the API session limit on 2026-10-03) (laptop: 8 cores / 15 GB), with at
most **2 Android agents** at a time (Gradle is memory-heavy). Web and Android lanes interleave.
Within a wave, the protocol cores go first (web W-05 and Android A-02), because everything
downstream depends on the reducer passing the shared fixtures.

## 3. Operating model

**Briefs.** Every work package (WP) is launched from a written brief with: goal, inputs (spec
sections), **file boundary** (the only paths it may create or edit), dependencies, definition of
done (tests and screenshots), and a ≤200-word report format. Agents never edit outside their
boundary. Requests to change shared files (version catalog, port interfaces, the fixtures, tokens)
go through the coordinator. Android catalog changes go through `android-next/gradle/catalog-requests.md`.

**Resource locks** (mandatory for every agent):
- Test environment (any emulator *or* the Chrome DevTools browser): `flock /tmp/archie-locks/testenv.lock <cmd>`.
  Only one exists at a time. The holder shuts it down when finished.
- Gradle (`android-next/`): `flock /tmp/archie-locks/gradle.lock ./gradlew …`, one build at a time.
- npm installs in `frontend-next/`: `flock /tmp/archie-locks/npm.lock …`.

**Git is coordinator-only.** Agents MUST NOT run any git command that writes (stash, checkout, switch, reset, restore, rebase, merge, cherry-pick, commit, rm, clean). Read-only git (status, diff, log, show) is fine. *Why:* on 2026-10-04 an agent's `git stash push/pop` applied an unrelated April stash in the shared tree and overwrote the gitignored `assistant_config.json`.

**Review.** Each WP is reviewed by the coordinator before it is accepted: spec conformance, the
tests actually run and green, screenshots checked, and boundary respected (`git status` limited to
the boundary). Correctness-critical WPs (W-05, A-02, the voice WPs, backend BF-1) also get an
independent review agent.

**Gates.**
- G-P (protocol): web and Android reducers pass 100% of `shared/protocol-fixtures`.
- G-01 (voice parity): spec 14 §6.2; no device time before it passes.
- G-C (compat): the bundle scanner is clean, plus an iPad / Safari 12 smoke.
- G-V (visual): side-by-side screenshots web vs Android at the same size class match the mockups.

**Commits.** One commit per accepted WP on `frontend-refactory`, made by the coordinator
(pending Rodrigo's OK of this policy).

**Live backend etiquette.** Test against the Jetson read-only where possible. Never open
orchestrator or voice sessions on it, and never restart it, without telling Rodrigo first.

## 4. Version verification (E-3)

W-01 and A-01 must check every library and toolchain version named in specs 13/14 against
published artifacts (npm registry, Google Maven, Maven Central, Gradle plugin portal). If a
version doesn't exist, use the latest stable version and record the substitution in the progress log.
