# 22 — Progress log

Newest first. One entry per milestone event, accepted WP, or decision.

## 2026-10-03

- **M0 closed: Rodrigo approved the mockups and design tokens.** Design-dependent WPs unblocked (W-02 now; B-01/B-02 when an Android slot frees).
- Wave 1 started (design-independent): W-05 protocol client, W-08 markdown, A-02 model/reducer, A-04 voice parity tests.
- Commits (not pushed): a65eafd docs/specs/tokens/fixtures/mockups · 66249f4 backend changes · 75b7c6f W-01 · af16837 A-01.
- Accepted W-01 (`npm run verify` green, re-run by coordinator) and A-01 (22 modules, guards proven, lite install-over proven on emulator; A300M API level still to confirm on device).
- Backend changes accepted after independent review + 4 fixes (O-7 legacy ids, O-1 one-message-per-turn, O-4 race, current-web thinking render); suite 1165 passed. Not yet deployed to the Jetson.
- Rodrigo: commit per package approved (no push without OK); Archie text model = gpt-audio-mini, Settings wins.
- Mockups published for review: https://claude.ai/artifact/MDViCDBwzHDfnyqrcUuBLR
- Backend changes (BF-1/2, BX-1/2, O-1..O-7) in implementation (agent); BF-3 (nginx) to be applied at Jetson deploy.
- Rodrigo approved all backend changes; decided P-1 (explicit close closes for everyone; disconnect never closes) and P-2 (voice auto-restart with call-app-style audio + visual cues).
- Mockups produced (`mockups/archie-mockups.html`); reconnecting-state frames being added; pending Rodrigo review.
- Specs 12 (protocol, 31 fixtures), 13 (web architecture, W-01..15), 14 (Android architecture, A/B/C/D) written.
- IA (spec 11) approved: drawer + switcher on phones (no bottom bar), "N steps" tool groups, Visuals full-screen + Show on TV, main app "Archie" (`com.assistant.archie`).
- Design tokens built (`design/tokens/`), all contrast pairs pass.
- BF-1 found: live Claude tool results carry empty id/output (root cause of R7). Verified in `manager/claude/session.py:1116-1133`.
- Inventory chapters 01–04 written; visual audit of current apps done.
- Decisions D1–D8 recorded in the charter.
- Project started on branch `frontend-refactory`.
