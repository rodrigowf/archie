## 5. Compat build (`frontend-compat/`, served at `/compat/`)

### 5.1 How it builds and loads

- React **18.3**, Vite 5, `@vitejs/plugin-legacy` with `targets: ['safari >= 12','ios >= 12'], modernPolyfills: true, renderModernChunks: false` (legacy chunks only), and `base: '/compat/'` (`fc/vite.config.ts:6-15`). The dev port is 5433.
- **Code sharing**: the alias `@ → ../frontend/src`. `fc/src/App.tsx` is a hand-maintained copy of `fe/App.tsx` that imports shared components through `@/…`.
- **Module-replacement aliases** (`fc/vite.config.ts:16-25`):
  - `diff` → `node_modules/diff/libesm/index.js` (forces the ESM build; commit 8d9eee2).
  - `react-syntax-highlighter[/dist/{esm,cjs}/styles/prism]` → the shims.
  - `remark-gfm` → the shim.
  - `@/components/MessageList` → the shim. This one **does not take effect** (§5.3).
- Type-check gate (`fc/scripts-typecheck.sh`): runs `tsc --noEmit` and **fails only on errors under `frontend-compat/src/`**. Shared-tree errors from the older TS lib are tolerated (commit 58d19ae). `fc/tsconfig.json:20-26` excludes the voice hook and voice components from type-checking; they are still bundled.
- `fc/index.html`:
  - No manifest, no service worker, no PWA meta beyond `theme-color`, `mobile-web-app-capable` and the status-bar style.
  - Favicon `/compat/icon.svg`.
  - The same remote-console script with a `[compat]` prefix (lines 11-32).
- `fc/src/main.tsx` imports `@/index.css` plus `./gap-compat.css` and has the same low-end detection.
- The backend serves `frontend-compat/dist` at `/compat`, `/compat/` and `/compat/{path}` with an SPA fallback, and a no-cache `index.html` (`api/app.py:182-197`). **Both dists must be rebuilt and deployed together** (per memory).

### 5.2 Differences from the main `App.tsx`

| Main (`fe/App.tsx`) | Compat (`fc/src/App.tsx`) |
|---|---|
| `ConfigPage` lazy plus Suspense | imported eagerly (line 8) |
| `onSessionChange` = refresh sessions **and** visualizations | `onSessionChange={refresh}` (sessions only, line 157). Visualizations do not refresh on `turn_complete` |
| `window.__setShowConfig` debug hook | absent |
| everything else | equivalent: same sidebar props, pool sync, rewind and fork, busy overlay, orchestrator modal, delete confirm |

### 5.3 Shims and why

| Shim | Problem in Safari 12 | What it does | State |
|---|---|---|---|
| `fc/src/shims/react-syntax-highlighter.tsx` (+ `-style.ts`) | Prism grammars use **named capture groups**, which Safari 12 cannot parse | `Prism`/`Light`/default render a plain `<pre><code>` with `customStyle`. `oneDark` is `{}` | Works. **No syntax highlighting on compat** |
| `fc/src/shims/remark-gfm.ts` | remark-gfm uses **lookbehind regexes** | A table-only GFM implementation: it walks every top-level paragraph, rebuilds its markdown source from the inline AST (`reconstructInline`, lines 253-295, commit 95f25c6), finds `header | sep | rows` blocks, and builds `table`/`tableRow`/`tableCell` mdast nodes with an inline parser for code, bold, italic, links, `<br>` and escapes. Strikethrough, autolinks and task lists are unsupported | **Regression**: **every non-table paragraph is re-emitted as a single plain `text` node of reconstructed markdown** (lines 328-349), so `**bold**`, `` `code` `` and `[links](…)` in ordinary paragraphs show as **literal markup** on compat. The rebuild must keep the inline formatting of non-table paragraphs |
| `fc/src/shims/MessageList.tsx` | `-webkit-overflow-scrolling: touch` momentum makes programmatic `scrollTop` unreliable. `overflow-anchor` is unsupported, which blinks on prepend | `iosScrollTo()` sets `webkitOverflowScrolling='auto'`, assigns `scrollTop`, and restores `touch` on the next frame. `hideForFrame()` sets visibility hidden for one frame around the prepend (lines 29-46) (commit 5411f6f) | **Not wired**: `fe/components/ChatPanel.tsx:1` imports `./MessageList` **relatively**, so the `@/components/MessageList` alias never matches. The built `fc/dist` contains no `webkitOverflowScrolling`. Compat currently runs the main `MessageList` while `gap-compat.css` turns momentum scrolling **on** for `.message-list` (`fc/src/gap-compat.css:23-34`), the exact combination the shim was written to make safe |
| `fc/src/gap-compat.css` (335 lines) | **Flexbox `gap` is unsupported** in Safari before 14.1 | For every `gap:` rule in `App.css`, sets `gap:0` and adds `> * + *` margins: `margin-left` for rows, `margin-top` for columns. **[LOAD-BEARING]** gotcha: `* + *` does not match **text nodes**, so `<svg> + raw text` pairs need explicit `> svg { margin-right }` rules (commit 8b8fb9c; memory note `project_compat_gap_shim_text_node_trap.md`). Examples: `.sidebar-config-btn`, `.session-menu-item`, `.message-actions-item`, `.compact-divider-label`. Wrapping rows (`.model-dropdowns`) accept the extra margin. It also enables `-webkit-overflow-scrolling: touch` on scroll containers | Must be kept in sync by hand whenever `App.css` gains a `gap:`. `App.css` has 71 `gap:` declarations |

### 5.4 Safari 12 / iOS 12 constraints the rebuild must respect

These are for the iPad mini 2 (iOS 12.5 max).
1. **No flexbox `gap`** (Safari 14.1+). Use margins, or ship a compat stylesheet. This includes the M3 component library's internal gaps.
2. **No regex lookbehind and no named capture groups.** This rules out stock remark-gfm and Prism grammars, and many modern markdown or highlight libraries. Audit every dependency's regexes.
3. **CSS `inset` is unsupported** (Safari 14.1+), and there is no PostCSS step in compat. `App.css` uses `inset: 0` for `.modal-overlay`, `.busy-overlay`, `.config-overlay`, `.session-list-overlay` and `.sidebar-backdrop` (lines 248, 2516, 2530, 2842, 2938, 3618). On Safari 12 these overlays likely collapse because no `top/left/right/bottom` is set (§6.3; verify on the device). Use explicit `top:0;right:0;bottom:0;left:0`.
4. `backdrop-filter` needs the `-webkit-` prefix. `.modal-overlay` has only the unprefixed property. `mask-image` needs `-webkit-mask-image` (present for the user-text fold).
5. `overflow-anchor` is unsupported; manual scroll restoration is required. Momentum scrolling has the problem described in §5.3.
6. **No AudioWorklet** (Safari 14.1+). The WS voice transport (`audioWorklet.addModule`) cannot work on compat. WebRTC exists but uses older APIs. `MediaRecorder` is absent in Safari 12, so audio messages are unavailable. `content-visibility` and `dvh` are unsupported and ignored.
7. `crypto.randomUUID` is unavailable (also on HTTP origins). The `generateUUID` fallback is required.
8. iOS needs `font-size >= 16px` on inputs to avoid focus zoom (see §6.2: the ≤640px override breaks this).
9. Taps on bare inline elements inside momentum-scroll containers need `cursor:pointer; touch-action:manipulation` and positioning hints (`.md-link`, commit e6f2f53).
10. Legacy-only chunks (no modern ES modules) and the full polyfill set. Bundle size matters on an A7 CPU and 1GB of RAM, and the `low-end` class disables animations.

---

