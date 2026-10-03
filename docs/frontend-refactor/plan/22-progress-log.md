# 22 — Progress log

Newest first. One entry per milestone event, accepted WP, or decision.

## 2026-10-03

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
