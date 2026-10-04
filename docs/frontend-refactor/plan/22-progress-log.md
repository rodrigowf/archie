# 22 — Progress log

Newest first. One entry per milestone event, accepted WP, or decision.

## Open issues (tracked until fixed)

| # | Found | Issue | Owner |
|---|---|---|---|
| OI-1 | 2026-10-04 live B-04 check | Android dark theme draws dark status-bar icons (clock/battery nearly invisible); set light system-bar icons in dark theme (edge-to-edge insets controller) | B-03 shell follow-up or B-09 |
| OI-2 | 2026-10-04 B-04 | Agent approvals can only be answered while that agent's view is open (no temporary-socket path) | B-06 / B-09 |
| OI-3 | 2026-10-03 audit | Jetson session list shows nothing newer than Aug 28 in both old and new clients — verify backend listing | Rodrigo / backend |

## 2026-10-04

- Closed the stray pool session "twitter browser test" on the Jetson (opened by the audit click on 2026-10-03), with Rodrigo's OK. Pool now empty.
- P-2 retry budget set to 30 s (Rodrigo).
- Accepted + committed: A-05 (cfc532e), A-03 (9bc23b7), B-01 (922fa23), B-02 (3d54293), B-03 (23c302b), W-03 (8a04a63), W-04 (794264b), W-06 (9dfd468, eea37f1), W-07 (e9192d4), W-10 (26f61a6), A-06 (076ad1a), B-04 (29e576d), W-09 (45cd270), W-11 (ca26b76). Theme default changed to dark on both platforms (D3).
- Coordinator rulings: reconnect uses spec 12 T-13 backoff (fixed 3 s superseded; 30 s ping kept); voice command queue never drops (V-4 amended); A-06 harness timing fix approved.

## 2026-10-03

- 17:57 — All 6 running agents (W-03, W-04, W-06, A-03, A-04, B-01) were cut off by an API usage limit (reset 15:40). Resumed W-03, W-04, B-01, A-04 from their saved context; W-06 and A-03 queued. **Lesson:** 6 parallel agents exhausts the session limit; cap lowered to 4 (plan/21 §2).
- Accepted + committed: W-08 (1f62515), A-02 (0d23fe5, 9c64f0e), W-02 (ed4bffd), W-05 (b347d9f), spec 12 updates (78a9fd0, 51f6996). Gate G-P met on both platforms: 38/38 shared fixtures.
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
