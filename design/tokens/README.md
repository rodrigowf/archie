# Design tokens

The single source for color, type, shape, spacing, elevation, motion and state
layers, shared by the web apps (`frontend-next/`, compat build) and the Android
apps (`android-next/`, lite app). Decisions D3 and D6 in
`docs/frontend-refactor/README.md`.

```
design/tokens/
├── tokens.json      source of truth (inputs by hand + color.resolved, written by the generator)
├── generate.mjs     resolver + emitters + contrast audit
├── preview.html     visual check of every token (open over http, see below)
└── dist/            GENERATED: tokens.css, Tokens.kt, tokens.ts,
                     android/values{,-night}/archie_colors.xml + values/archie_dimens.xml
                     (Views lite app, decision E-8: archie_<role> themed, archie_dark_<role> /
                     archie_light_<role> fixed)
```

## Rule: never hand-edit `dist/` or `color.resolved`

Change the inputs in `tokens.json` and regenerate. `npm run check` fails if
`dist/` or `color.resolved` differ from what the inputs produce, so CI can catch
hand edits and forgotten rebuilds.

## Regenerate

```sh
cd design/tokens
npm install          # once; pins @material/material-color-utilities 0.3.0
npm run build        # node generate.mjs: writes color.resolved + dist/*
npm run check        # verify everything is up to date (exit 1 if not)
node generate.mjs --audit   # also print every contrast pair
```

The build exits with code 1 if any text pair is below 4.5:1, any non-text pair
(outline on surfaces) is below 3:1, or two tool-category hues are less than 15
degrees apart.

Version 0.4.0 of the color library has broken ESM imports in Node (missing `.js`
extensions), so it is pinned to 0.3.0.

Preview: `python3 -m http.server -d design/tokens 8791`, then open
`http://127.0.0.1:8791/preview.html` (`?theme=light` or `?theme=system` to pick a
theme). Screenshots are in `docs/frontend-refactor/audit/screenshots/tokens-preview-*.png`.

## How the color scheme is made

- **Seed** `#879dc9`: today's web accent `hsl(220 38% 66%)` (HCT 263.9 / 30.3 / 64.5).
- **Variant**: M3 **TonalSpot** tone mapping (the Android default: primary 80/40,
  containers 30/90, contrast level 0), with three changes:
  1. Primary keeps the seed's chroma (30.3, instead of TonalSpot's 36), so the
     accent stays soft and desaturated.
  2. Neutral and neutral-variant chroma are 3 and 6 (instead of 6 and 8), so greys
     stay almost colorless like today's.
  3. Dark surfaces are pinned to lower tones (`toneOverrides.dark`): surface T4
     (`#0d0e10`), container-low T6, container T8 (`#17171a`), container-high T11
     (`#1d1d20`), container-highest T15 (`#252628`), outline-variant T22 (quiet
     dividers). Light uses the standard M3 tones.
- **Error** comes from today's `--error` `#d45f5f` (hue 20, chroma 55) instead of M3's
  louder default.
- **No dynamic color**: both platforms use exactly these values.
- **Extended colors** (`success`, `warning`, `info` and 12 tool categories) are M3
  custom colors: the source is harmonized toward the seed (`Blend.harmonize`, at most
  15 degrees), then given the M3 custom-color tones (dark 80/20/30/90, light
  40/100/90/10). The palette keeps the source chroma. Tool sources are written in HCT
  so the hues after harmonization are spread out (at least 20 degrees apart).
  `warning` is not harmonized, so it stays amber.
  Each one has `color`, `on-color`, `container`, `on-container` and a translucent
  `tint` (8% alpha, the replacement for today's `--tool-*-bg`).

## Naming

| Kind | CSS custom property | Kotlin | TS (`tokens.…`) |
|---|---|---|---|
| M3 color role | `--md-sys-color-on-primary-container` | `MaterialTheme.colorScheme.onPrimaryContainer` | `color.dark.onPrimaryContainer` |
| RGB channels | `--md-sys-color-primary-rgb` (`r, g, b`) | – | – |
| State layer | `--md-sys-color-on-surface-hover` / `-focus` / `-pressed` / `-dragged` | `StateLayer.Hover` (alpha) | `state.hover` |
| Disabled | `--md-sys-color-on-surface-disabled`, `…-disabled-container` | `StateLayer.DisabledContent` | `state.disabledContent` |
| Semantic | `--md-ext-color-success`, `--md-ext-color-on-success`, `--md-ext-color-success-container`, `--md-ext-color-on-success-container`, `--md-ext-color-success-tint` | `LocalExtendedColors.current.success.color` … `.tint` | `extendedColor.dark.semantic.success` |
| Tool category | `--md-ext-color-tool-read` (+ `on-`, `-container`, `-tint` as above) | `LocalExtendedColors.current.tool(ToolCategory.Read)` | `extendedColor.dark.tool.read` |
| Type | `--md-sys-typescale-body-large-{font,size,line-height,weight,tracking}`; code: `--md-sys-typescale-code-*` | `MaterialTheme.typography.bodyLarge`, `codeTextStyle(…)` | `typography.scale.bodyLarge` |
| Shape | `--md-sys-shape-corner-{none,extra-small,small,medium,large,extra-large,full}` | `MaterialTheme.shapes.medium`, `Corner.Medium`, `ShapeFull` | `shape.medium` |
| Spacing (4dp grid) | `--app-space-4` (= 16px; key × 4) | `Spacing.Space4` | `spacing["4"]` |
| Elevation | `--md-sys-elevation-level2` (shadow), `--md-sys-elevation-level2-surface` (tonal) | `Elevation.Level2` | `elevation.level2` |
| Motion | `--md-sys-motion-duration-medium2`, `--md-sys-motion-easing-emphasized-decelerate` | `Motion.DurationMedium2`, `Motion.EasingEmphasizedDecelerate` | `motion.duration.medium2` |

`--md-sys-*` follows Material's own token names, so M3 docs and material-web
examples carry over directly. `--md-ext-*` and `--app-*` are this project's additions.
In `tokens.json`, keys are camelCase; CSS uses kebab-case; Kotlin uses
PascalCase/camelCase.

## Consuming the output

### Web (`frontend-next/` and the Safari 12 compat build)

Import `design/tokens/dist/tokens.css` once at the root (copy it in a build step or
import it by relative path). Theme selection is an attribute on `<html>`:

| `data-theme` | Result |
|---|---|
| none / `"dark"` | dark (default; matches `theme-color #0d0d0f`) |
| `"light"` | light |
| `"system"` | follows `prefers-color-scheme` (Safari 12.1+; Safari 12.0 stays dark) |

The CSS is Safari 12 safe: plain custom properties only, no `color-mix`, `oklch`,
`@layer`, `:is`/`:where` or nesting. Any alpha you need is precomputed as `rgba()`
(state layers, tints, scrim), or use `rgba(var(--md-sys-color-primary-rgb), 0.2)`.
Do not define new variables that reference color variables at `:root`
(`--x: var(--md-sys-color-surface)`): they resolve once, against the dark values,
and don't follow a theme switch on a child. Use the color variables directly.

Fonts: bundle Roboto Flex (variable) and JetBrains Mono (woff2) locally with
`@font-face` named exactly `"Roboto Flex"` and `"JetBrains Mono"`. The app is
local-first, so it loads no fonts from a CDN at runtime. Icons: Material Symbols
Rounded, also bundled locally (a subset is fine).

For JS (charts, canvas, inline styles), import `dist/tokens.ts`:
`tokens.color.dark.primary`, `cssVar.tool('read', 'tint')`,
`cssVar.type('bodyLarge', 'size')`.

### Android (`android-next/`, lite app)

Copy or source-set-link `dist/Tokens.kt` into the shared design module (package
`com.assistant.design`). It needs Compose Material3 1.3+ and has been compiled
against Material3 1.3 (Compose Multiplatform 1.7.3).

```kotlin
@Composable
fun AssistantTheme(dark: Boolean = true, uiFont: FontFamily = RobotoFlex, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtendedColors provides if (dark) DarkExtendedColors else LightExtendedColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkColorScheme else LightColorScheme,   // never dynamicColorScheme()
            typography = appTypography(uiFont),
            shapes = AppShapes,
            content = content,
        )
    }
}
```

The lite app (API 21) calls `appTypography()` with no argument (the default is
`FontFamily.Default`), so it does not need the bundled font. Read code text with
`codeTextStyle(JetBrainsMono)`. Spacing, elevation, motion and state alphas are in
`Spacing`, `Elevation`, `Motion` and `StateLayer`.

## Changing things

- **Accent / overall hue**: change `color.input.seed`. Everything else, including the
  harmonized tool colors, follows.
- **Darker or lighter dark surfaces**: change `color.input.toneOverrides.dark`.
- **A tool color**: change its `source` (HCT `hct(hue chroma tone)` is easiest; tone is
  ignored). The build fails if two tool hues end up less than 15 degrees apart.
- **Type, shape, spacing, motion**: edit the matching section of `tokens.json`.

Then run `npm run build`, look at `preview.html` in both themes, and commit
`tokens.json` and `dist/` together.
