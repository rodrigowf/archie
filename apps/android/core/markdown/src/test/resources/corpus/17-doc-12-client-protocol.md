## 9. Visualizations and Memory

These flows exist on the web today and are new on Android (charter goal 5). Neither has push events; both are pull-only.

### 9.1 Visualizations

```
list:    GET /api/visualizations → [{path, url, title, created, modified, size}]  (sorted by modified desc)
refresh: when the section opens; manual Refresh / pull-to-refresh; after any view's endTurn (debounced 2 s,
         compat included, 02 §5.2 regression); on agent_session_opened/closed
open:    view key "viz:<path>"; load <origin> + encodePath(url) in an iframe (web) / WebView (Android)
rename:  optimistic title → PATCH /api/visualizations/rename {path, title} (204; 404 tolerated) → refresh
```
- **VZ-1.** `url` is not percent-encoded (G-38): encode each path segment with `encodeURIComponent`.
- **VZ-2.** Unknown paths return `200` + the SPA `index.html` (G-38). Before showing a stale entry, a client that needs to know whether a file still exists MUST `GET` it and check `Content-Type` and that the body is not the app shell. `HEAD` is not supported (405).
- **VZ-3.** Web iframe sandbox: `allow-scripts allow-same-origin allow-popups allow-forms allow-modals` (no top navigation, no downloads). Android WebView: JavaScript and DOM storage enabled, same origin as the backend, navigation outside the visualization opens the external browser, file access disabled, the self-signed certificate accepted only for the configured backend host.
- **VZ-4.** A visualization view keeps its frame/WebView alive while hidden (state survives view switches). Reload = remount (web) / `reload()` (Android). "Open in browser" opens `<origin><url>`.
- **VZ-5.** Item meta: relative `modified` time (parsed with its UTC offset, A-8.4), parent folder name or "public". Rename only; no delete.

### 9.2 Memory

```
tree:   GET /api/memory/tree → MemoryNode[] {name, path, is_dir, children|null}   (dirs first, alphabetical)
file:   GET /memory/<encodePath(path)> → raw markdown (text/markdown), 404 for dirs/missing
root:   GET /memory/ → MEMORY.md
refresh: when the section opens; manual Refresh. (No refresh on turn end.)
```
- **MEM-1.** Split leading frontmatter with `/^---\r?\n([\s\S]*?)\r?\n---\r?\n?/` and show it verbatim in a collapsed "Frontmatter" section; render the rest as markdown (02 §7.13).
- **MEM-2.** Relative links (`[x](../folder/y.md)`, `#anchor`) MUST resolve against the directory of the current file, normalising `.` and `..`. A result inside the memory root that ends in `.md` opens a memory view (`memory:<path>`) in the app; a result escaping the root, or an absolute `http(s)` link, opens externally (fixes 02 F-37).
- **MEM-3.** Paths are percent-encoded per segment when fetched (02 §6.2).
- **MEM-4.** Folder expand state is per view and local; top-level folders start expanded.
- **MEM-5.** Read-only: no write, rename or search endpoints exist.

---

