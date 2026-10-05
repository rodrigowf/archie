## 4. Adaptive layout

### 4.1 Window size classes

`src/app/useWindowClass.ts` reads `matchMedia` with the breakpoints from IA §0: **compact**
< 600, **medium** 600–839, **expanded** ≥ 840 CSS px. It uses `addListener` on Safari 12 (§2.6).
CSS uses the same breakpoints through `@custom-media`. JS drives structure (rail vs. drawer);
CSS drives dimensions. The iPad mini 2 is **medium** in portrait (768) and **expanded** in
landscape (1024).

### 4.2 Shell structure (W-07)

```
<AuthGate>
  <AppShell data-wc={wc}>                                  fixed, full --app-height
    {wc !== 'compact' && <NavigationRail/>}                ＋ New (FAB→Menu) · Chats · Memory · Visuals · Settings
    {wc === 'expanded' && listPaneOpen && <ListPane/>}     320 dp, standard; HistoryPane / MemoryPane / VisualsPane
    <main class="workspace">                               ← ALWAYS the same element, same tree position
      <WorkspaceTopBar/>                                   expanded/medium: tab strip in the top app bar; compact: ☰ · title⌄ · status · voice · ⋮
      <PanelHost/>                                         every open tab's panel, mounted; active one visible
    </main>
    {wc === 'medium' && <ListPaneOverlay/>}                modal side sheet next to the rail
    {wc === 'compact' && <NavigationDrawer/>}              modal; header (Archie mark, connection), search, Open now, history, Memory/Visuals/Settings
    <ScreenLayer/>                                         compact full screens (Memory, Visuals, Settings, documents) stacked ABOVE the workspace
    <SessionSwitcherSheet/> <SnackbarHost/> <div id="overlay-root"/>
  </AppShell>
</AuthGate>
```

| Size | Navigation | List pane | Tabs | Memory / Visuals docs |
|---|---|---|---|---|
| Expanded | Rail | Standard, collapsible (toggle in top bar; remembered in prefs) | Tab strip in the top app bar | **Open as tabs** (IA §9.2, kept) |
| Medium | Rail | Modal overlay from the rail or a top-bar button | Tab strip; "All tabs" overflow menu kicks in earlier | As tabs |
| Compact | Drawer (☰) | Drawer body | **Session switcher bottom sheet** from the title (IA §9.1, approved; no bottom bar) | Detail screens in `ScreenLayer` (back arrow) |

Tab strip (fixes R3, W1): 40 dp tabs inside a 48 dp hit area, a leading kind icon (Archie mark /
provider / memory / visual), a status indicator (spinner working, dot idle, warning
disconnected; connection state is styled, fixing §6.1), title **derived from the session list**
(**[LOAD-BEARING]** §7 #9), close × always visible on the active tab and on hover, middle-click
close, double-click / context menu / long-press → Rename, drag to reorder (Archie pinned first),
**⌄ All tabs** menu with search when the strip overflows, an "unseen" badge for background
opens. Right side: status text ("Thinking…", "Using Bash…"; turns and cost in a tooltip) and the
⋮ session menu.

Compact top bar: the title button opens the switcher sheet; horizontal swipe on the title
switches open sessions (touch events, optional on web); the subtitle shows live status.

### 4.3 Keyboard (expanded)

The IA's Ctrl+Tab, Ctrl+Shift+Tab, Ctrl+W and Ctrl+1…9 are **reserved by browsers**. A page in
a normal Chrome tab never receives them, and Linux Chrome also uses Alt+1…8. Proposed bindings
(`src/app/keyboard/bindings.ts`, one table; §10 D-W5):

| Action | Browser tab | Installed PWA window |
|---|---|---|
| Next / previous tab | Ctrl+Alt+→ / Ctrl+Alt+← | also Ctrl+Tab / Ctrl+Shift+Tab when delivered |
| Close tab | Ctrl+Alt+W | also Ctrl+W when delivered |
| Go to tab N | Ctrl+Alt+1…9 | also Ctrl+1…9 when delivered |
| Focus composer | `/` (when not typing) | same |
| New Archie / agent | Ctrl+Alt+N / Ctrl+Alt+Shift+N | same |
| Escape | closes the topmost overlay | same |

### 4.4 All tabs stay mounted (must-preserve inv02 §7 #1)

- **Connections live outside React.** `SessionRuntime`, `ArchieRuntime` and `VoiceController`
  are services in the session registry. They survive any React re-render, layout change or
  panel visibility change. A runtime is disposed only when its tab is closed.
- **Panels stay mounted.** `PanelHost` renders one panel per open tab, keyed by tab id, and hides
  inactive ones with the `hidden` attribute (`display: none`). Scroll position, the composer
  draft, expanded/collapsed card state and **visual iframes** (**[LOAD-BEARING]** F-36) survive
  tab switches.
- **Stable tree position.** `<main class="workspace">` and `PanelHost` have the same parent and
  index in every window class. Conditional siblings render `false` placeholders, which keep the
  index stable. Rotating the iPad or resizing the desktop window **never remounts panels**. A
  component test asserts DOM-node identity of the panel and the iframe across a window-class
  change and across tab switches.
- **Compact screens overlay; they do not replace.** On Compact, Memory/Visuals/Settings open in
  `ScreenLayer` above the workspace. The workspace stays mounted beneath with `aria-hidden`.
- **Frozen while hidden.** A hidden panel receives `hidden=true`. Its store subscriptions return
  the last published snapshot, so background streaming costs reducer time only, not React render
  or layout. On show, one catch-up render happens; then the "tab activation" scroll rule applies
  (bottom if the user was near it, F-11). This replaces React 19's `<Activity>`.
- **Memory budget on compat:** each hidden panel keeps at most its DOM window (§5.2). A hidden
  panel's window is trimmed to 40 messages after 60 s hidden (the trim happens above the viewport
  and the anchor is restored on show). This bounds memory on the 1 GB iPad without unmounting.

### 4.5 Navigation state and Back

No router library. `src/app/navigation/` keeps a small store mirrored to the **URL hash**, which
works under any base path (`/`, `/compat/`, `/next/`) and needs no server fallback:
`#/` (workspace), `#/history`, `#/memory[/<path>]`, `#/visuals[/<path>]`,
`#/settings[/<page>]`, `#/dev/gallery`. The active tab is not in the URL. Overlays (drawer,
sheets, dialogs, Compact screens) push a history entry and close on `popstate`, so the Android
back gesture and the browser Back button close the topmost layer first. A history-stack helper
prevents double pops.

---

