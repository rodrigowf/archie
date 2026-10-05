## 7. Load-bearing behaviours checklist (must preserve)

1. All open sessions stay live in the background (WS, voice, scroll, draft). Hidden panels are not unmounted (F-01).
2. `start` is re-sent on every WS open and on every visibility-visible event with an OPEN socket. Reconnect is 2s × 10 and paused while the page is hidden (F-01).
3. The per-tab sessionStorage `(stream_id, seq)` checkpoint, `resume_from`, `resume_state` seeding and the `replay_overflow` REST fallback (F-24).
4. The history-init effect must not re-run when `resumeSdkId` is assigned after the first turn (F-01).
5. The message ordering algorithm of §4.2: per-kind streaming-block extension, authoritative `*_complete`, and tool result merged by id.
6. The bottom-relative `dropLastN` contract and the rewind order close → truncate → reopen (F-08, F-09).
7. The freeze buffer while scrolled up, prepend scroll restoration, and the 150px / 80px thresholds (F-11).
8. Permission request_id matching. Typing a message counts as deny-with-feedback. The ExitPlanMode plan is rendered inline (F-14).
9. Tab titles derived from the session list (F-19, §1.6).
10. Pool sync on mount and visibility, plus the `agent_session_opened`/`closed` pushes (F-23).
11. Voice:
    - queue provider commands until the transport or data channel is ready;
    - forward `voice_session_update` only when initiator and WebRTC;
    - 30s connection-info wait;
    - send `response.cancel` only while a response is in flight;
    - local audio flush on barge-in;
    - Gemini transcript coalescing;
    - empty-text `turnComplete` finalisation;
    - 5s ending timeout;
    - passive viewers ignore lifecycle events and act only on `voice_owner_active` (F-26 to F-29).
12. Google voice model auto-correct (F-31).
13. Memory frontmatter split into a collapsed block (F-37). Viz iframe sandbox tokens and remount-to-reload (F-36).
14. The `generateUUID` fallback for non-secure origins. The low-end class and reduced-motion handling. Remote console logging for devtools-less devices.
15. Compat: margin-based gap shims (including the text-node trap), no lookbehind or named groups, a GFM table fallback that keeps inline markdown, safe programmatic scrolling under momentum scroll, explicit offsets instead of `inset`.
