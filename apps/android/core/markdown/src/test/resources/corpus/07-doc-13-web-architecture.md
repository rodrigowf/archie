## 5. Performance

### 5.1 Streaming render cost

1. **Coalesced notifications:** reducer per event; React notified once per frame (or per
   100 ms on low-end) (§3.3).
2. **Structural sharing + narrow selectors:** only the last message re-renders during a stream.
   Each block component is `React.memo` on its block object identity.
3. **Incremental markdown (`StreamingMarkdown`):** while `streaming`, the source is split into
   top-level chunks by `splitBlocks()` (blank-line boundaries outside open code fences ``` and
   ~~~, and never inside a table or list run). Every chunk except the last is a memoized
   `<Markdown>` keyed by `index + hash(source)`, so a delta re-parses only the tail chunk. When
   the block completes (`text_complete` is authoritative), one full parse replaces the chunks,
   so the final output is exact CommonMark (for example reference-style links that the split
   view cannot resolve).
4. **Highlighting** only for closed fences, after streaming (§2.4). Open fences render as plain
   monospace.
5. **Auto-scroll keyed on content height, not block count** (fixes inv02 §6.2): `MessageList`
   observes the tail's height (ResizeObserver, polyfilled on compat) and pins to bottom only when
   near the bottom and no touch/momentum is active.
6. **Tool output** is truncated (§3.6); collapsed cards do not render their bodies.

### 5.2 Long conversations: a bounded DOM window, no virtualizer

`@tanstack/react-virtual` is **not used**. Variable-height items that grow while streaming,
prepend pagination, the freeze buffer and iOS 12 momentum scroll (programmatic `scrollTop`
changes during momentum jump or are ignored) together make virtualizer anchoring fragile on the
one device that needs it most.

Instead, `useMessageWindow` (W-09) renders a **slice** `[start, end)` of the session's messages:

- `end` is the tail, except while the **freeze buffer** holds (user scrolled up; new messages
  are buffered, not rendered, **[LOAD-BEARING]** F-11).
- When the slice exceeds the cap (200 main, 80 low-end) **and** the user is at the bottom,
  `start` advances (trimming from the top) and the list re-pins to the bottom in the same frame,
  with no visible jump.
- Scrolling up past the top of the slice first **re-expands from memory**, then pages from REST
  (`before=start_index`). Both use the **same** `ScrollArea.preserveAnchor()` path (hide-for-frame
  + offset restore under momentum-safe scrolling). One code path, already load-bearing.
- On main, `content-visibility: auto; contain-intrinsic-size: auto 120px` on message rows reduces
  paint further. Safari 12 ignores it harmlessly.

If the performance budgets (§5.4) fail on main with very long sessions, a virtualizer may be
added to main later behind the same `MessageList` props. Compat keeps the window.

### 5.3 Other costs

- Code splitting (`React.lazy`): Settings, the Memory document view, the Visual viewer, `DiffView`
  + `diff`, highlight languages, the voice engine, the dev gallery (excluded from production).
  On compat, plugin-legacy turns these into SystemJS chunks, which still load lazily.
- `Markdown` and tool renderers do no work during render beyond parsing; derived data
  (summaries, grouping) is memoized selectors.
- Remote logging is rate-limited (§1.4).

### 5.4 Budgets (enforced by `gate:budgets`)

`scripts/check-budgets.mjs` reads each build's Vite manifest, gzips every emitted file
(`zlib`, level 9) and compares with `scripts/budgets.json`. Over budget fails the build. A budget
change needs a one-line justification in the file.

| Budget (gzip) | main | compat |
|---|---|---|
| Initial JS (entry + static imports) | ≤ 190 KB | ≤ 270 KB (legacy transpile + SystemJS) |
| Polyfills chunk | — | ≤ 60 KB |
| Initial CSS | ≤ 35 KB | ≤ 40 KB |
| Fonts on first paint (woff2, uncompressed) | ≤ 80 KB | ≤ 80 KB |
| Largest lazy chunk | ≤ 60 KB | ≤ 75 KB |
| Total JS | ≤ 480 KB | ≤ 560 KB |

Runtime targets (measured in W-15 with the chrome-devtools MCP performance trace; compat
proxied in Chrome with 6× CPU throttling, plus a real iPad timing beacon):

| Scenario | Target |
|---|---|
| iPad mini 2 cold load of `/compat/` to first rendered conversation (LAN) | ≤ 4 s (logged by a `perf` remote-console line at first render) |
| Laptop cold load of `/` | ≤ 1.5 s to first conversation render |
| Streaming 30 deltas/s into a 2,000-word reply | main: ≤ 25 % main-thread busy at 4× throttle; compat proxy: ≤ 50 % at 6× throttle |
| Open a session with 50 history messages | ≤ 300 ms scripting (laptop, unthrottled) |
| 5 open sessions × 200 messages | ≤ 150 MB JS heap (Chrome heap snapshot) |
| Tab switch | ≤ 100 ms to visible (no remount) |

---

