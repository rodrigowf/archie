# frontend-next

The new Archie web app: one codebase, two builds (spec `docs/frontend-refactor/spec/13-web-architecture.md`).

| Build | Config | Output | Served at | Targets |
|---|---|---|---|---|
| main | `vite.config.main.ts` | `dist/` | `/` | Chrome/Edge 109+, Firefox 115+, Safari/iOS 15.4+, Samsung 21+ |
| compat | `vite.config.compat.ts` | `dist-compat/` | `/compat/` | Safari 12 / iOS 12 (iPad mini 2), legacy SystemJS chunks only |

Builds run on the laptop only (Node 22.21.1). The Jetson cannot run this toolchain.

## Version substitutions (E-3)

All 53 versions named in spec 13 §1.2 exist on the npm registry (checked 2026-10-03 with
`npm view <pkg>@<ver> version`). Changes:

| Package | Spec | Installed | Why |
|---|---|---|---|
| `jsdom` | 30.1.1 | **29.1.1** | jsdom 30.x requires Node `^22.22.2 \|\| ^24.15.0`; the laptop runs Node 22.21.1, the version spec 13 §1.2 names. 29.1.1 is the latest release that supports `^22.13.0`. |
| `@types/node` | (not listed) | **22.20.5** (added) | Needed to type-check `vite.*.ts`, `vitest.config.ts` and `scripts/*.ts` (`tsconfig.node.json`). Matches the Node 22 runtime and Vitest 5's peer range. |

Notes:
- `highlight.js` is deduped to 11.12.0 through `overrides`, because `lowlight` 3.3.0 declares `~11.11.0`.
- `autoprefixer` is not a direct dependency: it is bundled by `postcss-preset-env` (as the spec says).
- `@eslint/js` (used by `eslint.config.js`) is the copy that `eslint` 9.39.5 pins; it is not a separate dependency.

## Scripts

| Script | Does |
|---|---|
| `dev` / `dev:compat` | Vite dev server on 5450 / 5451 (HTTPS when `context/certs/` has key + cert; `FN_HTTPS=0` forces HTTP) |
| `preview` / `preview:compat` | Serve the built `dist/` / `dist-compat/` on the same ports, with the same API proxy |
| `build` | `typecheck` → `gate:tokens` → `build:main` → `build:compat` → `gate:compat` → `gate:budgets` |
| `build:preview` | Both builds with `FN_BASE=/next/` and `/next-compat/` → `dist-preview/{main,compat}` (+ scanner) |
| `typecheck`, `lint`, `lint:css`, `test`, `test:coverage` | `tsc -b`, ESLint, Stylelint, Vitest |
| `gate:compat` | `scripts/scan-compat-bundle.mjs dist-compat` (spec 13 §2.3) |
| `gate:budgets` | `scripts/check-budgets.mjs` against `scripts/budgets.json` (spec 13 §5.4) |
| `gate:tokens` | token dist up to date + every `var(--md-…)`/`var(--app-…)` in `src/**/*.css` exists |
| `verify` | `lint && lint:css && typecheck && test && build`: the done-gate for every work package |

API proxy target: `ARCHIE_BACKEND` (default `http://localhost:8765`). Proxied paths: `/api` (+ WS),
`/memory`, `/uploads`, `/projects`.

## What W-01 provides

- **Builds**: `vite.shared.ts` (aliases, `define`, PostCSS, dev/preview server), the two configs,
  and `scripts/vite-plugin-html-target.ts`, which fills the `<!--archie:head-->` / `<!--archie:body-->`
  markers in `index.html` per target (remote console, manifest, SW registration, font preload).
- **Remote console** (`scripts/remote-console.js`): hand-written ES5, inlined untranspiled. Console
  mirroring is off by default on main and on for compat; localStorage key `archie.remoteConsole`
  (`'1'`/`'0'`) overrides it. Window `error` and `unhandledrejection` are always sent. Limits: 60
  messages per 10 s, then one "dropped N" line; 4 KB per message. App API: `@/platform` `remoteLog`,
  `setRemoteLogEnabled`.
- **`src/platform/`**: `capabilities` (`?caps=compat` simulation), `lowEnd`, `uuid`, `clipboard`,
  `media`, `storage`, `emitter`, `time`, `remoteLog`, `debug` (`?debug=`, `window.__archie`),
  `polyfills.compat.ts` (ResizeObserver, focus-visible). Main resolves `@/platform/polyfills` to a no-op.
- **`src/test/`**: jsdom setup, `regexpGuard.ts` (installed by the `compat` project), `renderUi`,
  `expectNoAxeViolations`.
- **Gates**: the compat scanner (+ allowlist), budgets, token vars, ESLint and Stylelint bans. Tests
  for all of them are in `scripts/__tests__/`.
- **Placeholder** `src/app/App.tsx` (+ `App.module.css`, `App.test.tsx`): the hello page. W-07 replaces all three.

## Notes for later work packages

- **Compat CSS lives inside the JS.** With `renderModernChunks: false`, `@vitejs/plugin-legacy` emits no
  `.css` file: Vite inlines each chunk's CSS as `<style>.textContent = "…"`, as the current compat build
  does. The scanner and the budget gate extract that CSS from the JS. (`build.cssCodeSplit: false`
  drops the CSS completely in legacy-only mode, so do not use it.)
- **Scanner parses at ES2019, not ES2018.** Babel leaves optional catch binding (`catch {}`) untranspiled
  for `safari >= 12`, and Safari 11.1+ supports it. ES2020+ syntax still fails.
- **highlight.js 11.12.0 `gcode`** has its lookbehind only in a comment (`gcode.js:66`). Spec 13 §2.3
  lists it as a real one. The scanner passes it, and the tests pin this.
- **`@custom-media`**: `vite.shared.ts` prepends the definitions from `src/styles/media.css` (W-02) to
  every stylesheet, so CSS Modules can use `@media (--compact)`.
- **PostCSS browsers are passed explicitly.** postcss-preset-env's `env` option looks for `.browserslistrc`
  relative to each stylesheet, so `design/tokens/dist/tokens.css` would get browserslist defaults.
- **W-02** needs a one-line edit in `src/main.tsx` (W-01's file, via the coordinator) to import
  `@/styles`. Until then `main.tsx` imports `@tokens/tokens.css` directly.
- **Allowlist** (`scripts/compat-scan-allowlist.json`): one entry, core-js's `regexp-unsupported-ncg`
  feature probe, which builds `(?<a>b)` inside `fails()` (try/catch).
- **Vitest projects**: `protocol`, `dom` and `compat` as in spec 13 §6.1, plus `scripts` (node) for the gate
  tests. `passWithNoTests` is on until W-05 adds protocol tests.
- `tsconfig.protocol.json` lists `src/env.d.ts`, so it is valid before `src/protocol/` exists (W-05 owns the content).

## iPad mini 2 spike (pending; run by the coordinator with Rodrigo)

Go/no-go for Vite 8 + plugin-legacy 8 on the real device (spec 13 §7 W-01, risk K1). The `/next-compat/`
route is not deployed yet, so this uses Option A (the laptop serves the build).

1. Laptop (192.168.0.28), with the laptop backend running on 8765 (preferred, so beacons land on the laptop):
   ```sh
   cd ~/assistant/frontend-next
   npm run build:compat
   FN_HTTPS=0 npx vite preview -c vite.config.compat.ts
   ```
   This serves `http://192.168.0.28:5451/compat/` with `/api` proxied to `ARCHIE_BACKEND`. Plain HTTP
   avoids trusting the self-signed certificate on the iPad. If only the Jetson backend is available, add
   `ARCHIE_BACKEND=https://192.168.0.200`. The beacons then append to the Jetson's `remote_console.log`
   (a write: tell Rodrigo first). If the iPad cannot connect, check the laptop firewall for port 5451.
2. iPad mini 2, Safari: open `http://192.168.0.28:5451/compat/`.
3. Expected in portrait:
   - an "Archie" header and three cards (Build, Device, Capabilities), on a dark surface, with no
     white screen;
   - Build: `target compat`, `regex check ok (major 0, sha …)`;
   - Device: `ResizeObserver width ≈ 736px (medium)` (polyfilled observer), `low-end mode on`, a UUID
     (the `getRandomValues` fallback), `remote console on`;
   - Capabilities: `audioWorklet no`, `mediaRecorder no`, `clipboardApi no`, `resizeObserver yes` (the polyfill), `webAudio yes`.
4. Rotate to landscape. The width must update to ≈ 992px (expanded) without a reload.
5. Check the beacon on the laptop:
   `curl -s http://localhost:8765/api/debug/log | tail -5`. Expected lines are
   `[INFO] [compat] [hello] rendered target=compat …` and `[PERF] [compat] hello first render …ms`.
   No `[UNCAUGHT]` lines.
6. **Go:** steps 3–5 pass. Record "W-01 spike passed" in `plan/22-progress-log.md`.
   **No-go** (white screen, `uncaught` SyntaxError, or no beacon): read the remote log for the error,
   then switch to `vite` 7.3.6 + `@vitejs/plugin-legacy` 7.2.1 + `@vitejs/plugin-react` 5.x, update
   spec 13 §1.2, and repeat.
7. Stop the preview server (Ctrl+C).
