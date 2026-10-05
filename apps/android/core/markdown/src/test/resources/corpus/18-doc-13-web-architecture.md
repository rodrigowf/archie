## 6. Testing

### 6.1 Unit tests (Vitest 5.0.3)

`vitest.config.ts` defines three projects:

| Project | Environment | Covers |
|---|---|---|
| `protocol` | node | `src/protocol/**`: **fixture conformance suite** (every fixture, no skips), reducer invariants (property-style tests for "tool result never lost" R7 and arrival order R4), history conversion, `drop_last_n`, checkpoint logic |
| `dom` | jsdom 30.1.1 | services (fake WebSocket + fake timers for reconnect 2 s × 10, visibility pause and `start` re-send), stores, `voice/core` (**voice signaling state**: every rule of inv02 §7 #11 as a named test; passive-viewer status stays `off`; command queue; 5 s ending timeout; 30 s connection-info timeout), `useMessageWindow` + freeze buffer, `splitBlocks`, components (§6.2) |
| `compat` | jsdom, **compat aliases** (`languages.compat.ts`) + `regexpGuard.ts` | renders the markdown corpus (`src/features/markdown/__fixtures__/*.md`: tables, inline formatting in paragraphs, autolinks, task lists, every compat language) under the RegExp guard; asserts inline formatting survives (fixes §6.3 #12) |

Coverage gate (v8): `src/protocol` ≥ 95 % lines, `src/voice/core` ≥ 90 %, `src/services` ≥ 80 %.

### 6.2 Component tests

`@testing-library/react` 16.3.3 + `user-event` 14.6.7 in the `dom` project:

- Every kit component: roles, keyboard (Tab, arrows, Home/End, Escape, Enter/Space), focus trap
  and return focus for overlays, `aria-*` states, and an `axe-core` 4.13.0 run with no violations.
- Feature components against stores seeded from protocol fixtures: tool cards per tool type
  (snapshot of the summary line + expanded body), StepGroup live → collapsed, permission card
  flows, termination card (tab stays), composer morphing button states, settings save → snackbar
  (verbatim server error), memory frontmatter + relative links, viz sandbox tokens.
- Must-preserve tests: panel and iframe DOM identity across tab switch and window-class change
  (§4.4); history init not re-run on `resumeSdkId`; derived tab titles.

### 6.3 Gates

| Gate | Command | Fails on |
|---|---|---|
| Type-check | `npm run typecheck` | Any TS error in any project (strict, `noUncheckedIndexedAccess`). No tolerated-error filters (unlike today's compat script) |
| ESLint | `npm run lint` | typescript-eslint strict; react-hooks; jsx-a11y; `es-x` regex rules; `eslint-plugin-compat` (compat browserslist; polyfilled APIs declared); restricted syntax/properties of §2.6; `no-restricted-imports` boundaries (protocol purity; features import other features only via `index.ts`) |
| Stylelint | `npm run lint:css` | `stylelint-config-standard`; `property-disallowed-list` (`gap`, `row-gap`, `column-gap`, `aspect-ratio`); `selector-pseudo-class-disallowed-list` (`is`, `where`, `has`); `function-disallowed-list` (`color-mix`, `clamp`); `unit-disallowed-list` (`dvh`, `svh`, `lvh`); `at-rule-disallowed-list` (`layer`, `container`); `stylelint-no-unsupported-browser-features` (compat browserslist) as a warning pass |
| Tokens | `npm run gate:tokens` | Stale token dist; unknown `var(--md-…)`/`var(--app-…)` |
| Compat scanner | `npm run gate:compat` | §2.3 |
| Budgets | `npm run gate:budgets` | §5.4 |

### 6.4 Visual QA with the chrome-devtools MCP

- **One browser at a time** (charter working rules): one chrome-devtools page, against one dev or
  preview server; `close_page` when done; never two agents driving browsers concurrently (the
  coordinator serializes QA slots). No Playwright or Vitest browser mode in this project.
- **Data source:** the mock server (§6.5) by default, so screenshots contain no private
  conversations and are reproducible. The live Jetson only read-only, and **never** open Archie
  or voice sessions there without telling Rodrigo.
- **Viewports:**

| Name | Size | Emulation | Build |
|---|---|---|---|
| desktop | 1440 × 900, DPR 1 | — | main |
| tablet | 768 × 1024, DPR 2, touch, iOS 12 Safari UA, `?caps=compat` | iPad mini 2 portrait | **compat** |
| phone | 412 × 915, DPR 2.625, touch, Android Chrome UA | — | main |

  The tablet set is also taken in landscape (1024 × 768) for screens with layout changes.
- `?caps=compat` forces the compat capability set in Chrome (no AudioWorklet, no MediaRecorder, no
  Clipboard API), so hidden or fallback UI shows in screenshots. Chrome cannot reproduce Safari
  12's parser or CSS gaps; those are covered by gates (§6.3) and the device checklist.
- Screenshots go to `fn/qa/screenshots/<W-id>/<viewport>-<screen>-<state>.png` (gitignored),
  listed in the work package's QA note `fn/qa/<W-id>.md` with what was checked.
- **Device checklist** (`fn/qa/device-checklist.md`, run on the real iPad mini 2 and a phone via
  `/next-compat/` and `/next/` or Option A): load, rotate, scroll a long conversation with
  momentum, prepend history, streaming, open/close every overlay, code copy, audio message (WAV),
  voice start/stop (when in scope), visuals iframe, memory links, remote console shows no
  errors (`GET /api/debug/log`).

### 6.5 Mock backend (W-05)

`fn/mock-server/server.mjs` (Node + `ws` 8.22.0, port 8799): serves REST fixtures (sessions,
pool/live, messages pages, config, models, memory tree and files, visuals, auth status) from
`fn/mock-server/data/` (synthetic content), and the chat and orchestrator WebSockets. On `start`
it replays a chosen `apps/protocol-fixtures` transcript, selected by `?scenario=` or by
`local_id` prefix, with real timing or `--fast`. It emits **binary** frames and accepts text
frames only, like the backend (G-2). Used by dev, QA screenshots and the `dom` tests' fake
socket fixtures.

### 6.6 Definition of done (every work package)

1. `npm run verify` green (lint, CSS lint, type-check, all tests, both builds, compat scanner,
   budgets).
2. Tests listed in the package's DoD exist and pass.
3. Screenshots at the three viewports for every UI state the package adds, with the QA note.
4. `git diff --stat` shows changes only inside the package's boundary.
5. Every ported load-bearing behaviour carries a source citation comment
   (`// LOAD-BEARING inv02 F-11 (frontend/src/components/MessageList.tsx:36-90)`).
6. No new dependency without coordinator approval.

---

