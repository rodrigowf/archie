/**
 * ESLint (spec 13 §6.3): typescript-eslint strict, react-hooks, jsx-a11y, es-x regex rules,
 * eslint-plugin-compat on the compat browserslist, Safari 12 / React-19-readiness bans (§1.3,
 * §2.6) and package boundaries (§3.9).
 *
 * `@eslint/js` is the copy that eslint 9.39.5 itself pins (no separate dependency).
 */
import js from '@eslint/js';
import { defineConfig, globalIgnores } from 'eslint/config';
import compat from 'eslint-plugin-compat';
import esX from 'eslint-plugin-es-x';
import jsxA11y from 'eslint-plugin-jsx-a11y';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import { browserslistEnv } from './scripts/browserslist-env.mjs';

/* -------------------------------------------------------------------------------------------- */
/* Restricted syntax. ESLint replaces (not merges) a rule's options when several config blocks   */
/* match a file, so the lists are composed here and each block passes the full set.              */
/* -------------------------------------------------------------------------------------------- */

const SAFARI12_SYNTAX = [
  {
    selector: 'JSXAttribute[name.name=/^onPointer/]',
    message: 'Pointer Events need Safari 13. Use mouse + touch events (src/ui/a11y/gestures.ts). Spec 13 §2.6.',
  },
  {
    selector: "CallExpression[callee.property.name='addEventListener'][arguments.0.value=/^pointer/]",
    message: 'Pointer Events need Safari 13. Use mouse + touch events. Spec 13 §2.6.',
  },
  {
    selector: "NewExpression[callee.name='EventTarget']",
    message: 'EventTarget is not constructible on Safari 12. Use Emitter from @/platform.',
  },
  {
    selector: "MemberExpression[object.name='Intl'][property.name='RelativeTimeFormat']",
    message: 'Intl.RelativeTimeFormat needs Safari 14. Use formatRelativeTime from @/platform.',
  },
  {
    selector: 'Property[key.name=/^(dateStyle|timeStyle)$/]',
    message: 'Intl dateStyle/timeStyle need Safari 14.1. Use explicit fields (day, month, …).',
  },
  {
    selector: "CallExpression[callee.property.name=/^(scrollTo|scrollBy|scrollIntoView)$/][arguments.0.type='ObjectExpression']",
    message: 'scrollTo/scrollBy/scrollIntoView(options) are unsupported on Safari 12. Assign scrollTop (ScrollArea).',
  },
  {
    selector: "CallExpression[callee.object.callee.property.name='matchMedia'][callee.property.name=/^(add|remove)EventListener$/]",
    message: 'MediaQueryList.addEventListener needs Safari 14. Use watchMedia from @/platform.',
  },
];

const FORM_FIELDS = {
  selector: 'JSXOpeningElement[name.name=/^(input|textarea|select)$/]',
  message:
    'Only TextField, Select, SearchField (src/ui/controls) and Composer (src/features/composer) render form fields: the 16px iOS rule. Spec 13 §2.6.',
};

const REACT19_READY = [
  { selector: "JSXAttribute[name.name='ref'][value.type='Literal']", message: 'String refs are removed in React 19.' },
  {
    selector: "AssignmentExpression[left.property.name='defaultProps']",
    message: 'defaultProps on function components is removed in React 19. Use default parameters.',
  },
  {
    selector: "Property[key.name=/^(contextTypes|childContextTypes)$/], MethodDefinition[key.name='getChildContext']",
    message: 'Legacy context is removed in React 19.',
  },
];

const BOUNDARY_MESSAGE =
  'Import another package only through its index.ts entry (spec 13 §3.9). Inside a package, use relative imports.';
const BOUNDARY_PATTERNS = [
  {
    group: ['@/protocol/*', '@/services/*', '@/stores/*', '@/voice/*', '@/platform/*', '!@/platform/polyfills', '@/ui/*/*'],
    message: BOUNDARY_MESSAGE,
  },
  {
    // Regex, because gitignore-style groups cannot re-include a path under an excluded parent.
    // The per-build alias `@/features/markdown/highlight/languages` (vite.shared.ts) stays allowed.
    regex: '^@/features/(?!markdown/highlight/languages$)[^/]+/',
    message: BOUNDARY_MESSAGE,
  },
];

const REACT_DOM_LEGACY = {
  name: 'react-dom',
  importNames: ['render', 'hydrate', 'findDOMNode', 'unmountComponentAtNode'],
  message: 'React-19-ready: use createRoot / hydrateRoot from react-dom/client (spec 13 §1.3).',
};

const PROTOCOL_PURITY = {
  paths: [
    { name: 'react', message: 'src/protocol is framework-free (spec 13 §3.2).' },
    { name: 'react-dom', message: 'src/protocol is framework-free (spec 13 §3.2).' },
    { name: 'zustand', message: 'src/protocol is framework-free (spec 13 §3.2).' },
  ],
  patterns: [
    ...BOUNDARY_PATTERNS,
    {
      group: ['@/services', '@/services/*', '@/stores', '@/stores/*', '@/ui', '@/ui/*', '@/features', '@/features/*', '@/app', '@/app/*'],
      message: 'src/protocol must not depend on services, stores, ui, features or app (spec 13 §3.2).',
    },
  ],
};

const COMPAT_BROWSERS = browserslistEnv('compat');

export default defineConfig([
  globalIgnores([
    'node_modules/',
    'dist/',
    'dist-compat/',
    'dist-preview/',
    'dist-gallery/',
    'coverage/',
    '.scan-test-*/',
    'public-main/',
    'public-compat/',
    'qa/',
  ]),

  /* Plain JS everywhere (scripts, configs). */
  {
    files: ['**/*.{js,mjs,cjs}'],
    extends: [js.configs.recommended],
    languageOptions: { ecmaVersion: 2024, sourceType: 'module', globals: { ...globals.node } },
  },

  /* The inline remote console is hand-written ES5, inlined into index.html untranspiled. */
  {
    files: ['scripts/remote-console.js'],
    languageOptions: {
      ecmaVersion: 5,
      sourceType: 'script',
      globals: { ...globals.browser, __REMOTE_CONSOLE_CONFIG__: 'readonly' },
    },
    // ES5 has no optional catch binding, so `catch (e) {}` is required.
    rules: { 'no-unused-vars': ['error', { caughtErrors: 'none' }] },
  },

  /* TypeScript: strict, for app code, configs and script tests. */
  {
    files: ['**/*.{ts,tsx}'],
    extends: [tseslint.configs.strict],
    rules: {
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
    },
  },
  {
    files: ['*.ts', 'scripts/**/*.ts'],
    languageOptions: { globals: { ...globals.node } },
  },

  /* App source. */
  {
    files: ['src/**/*.{ts,tsx}'],
    extends: [reactHooks.configs.flat['recommended-latest'], jsxA11y.flatConfigs.recommended],
    plugins: { 'es-x': esX, compat },
    languageOptions: { globals: { ...globals.browser } },
    settings: {
      browsers: COMPAT_BROWSERS,
      // Provided by @vitejs/plugin-legacy (core-js, usage-based) or src/platform/polyfills.compat.ts.
      polyfills: [
        'ResizeObserver',
        'Promise.allSettled',
        'Promise.prototype.finally',
        'Object.fromEntries',
        'Array.prototype.at',
        'Array.prototype.flat',
        'Array.prototype.flatMap',
        'String.prototype.matchAll',
        'String.prototype.replaceAll',
        'globalThis',
        'queueMicrotask',
      ],
    },
    rules: {
      // Regex features Safari 12 cannot parse (spec 13 §2.3, source-level complement).
      'es-x/no-regexp-lookbehind-assertions': 'error',
      'es-x/no-regexp-named-capture-groups': 'error',
      'es-x/no-regexp-d-flag': 'error',
      'es-x/no-regexp-v-flag': 'error',
      'es-x/no-regexp-modifiers': 'error',
      'es-x/no-regexp-duplicate-named-capturing-groups': 'error',
      // Browser APIs missing on Safari 12 (compat browserslist), minus declared polyfills.
      'compat/compat': 'error',
      'no-restricted-syntax': ['error', ...SAFARI12_SYNTAX, FORM_FIELDS, ...REACT19_READY],
      'no-restricted-properties': [
        'error',
        { object: 'crypto', property: 'randomUUID', message: 'Use generateUUID from @/platform (missing on Safari 12 and plain HTTP).' },
        { object: 'window', property: 'requestIdleCallback', message: 'Not on Safari. Use setTimeout chunks.' },
        { object: 'navigator', property: 'clipboard', message: 'Use copyText from @/platform (Safari 12 / HTTP fallback).' },
      ],
      'no-restricted-globals': [
        'error',
        { name: 'structuredClone', message: 'Safari 15.4+. Copy explicitly.' },
        { name: 'requestIdleCallback', message: 'Not on Safari. Use setTimeout chunks.' },
        { name: 'WeakRef', message: 'Safari 14.1+.' },
        { name: 'FinalizationRegistry', message: 'Safari 14.1+.' },
      ],
      'no-restricted-imports': ['error', { paths: [REACT_DOM_LEGACY], patterns: BOUNDARY_PATTERNS }],
    },
  },

  /* Form fields are allowed only in the input components. */
  {
    files: ['src/ui/controls/**/*.{ts,tsx}', 'src/features/composer/**/*.{ts,tsx}'],
    rules: { 'no-restricted-syntax': ['error', ...SAFARI12_SYNTAX, ...REACT19_READY] },
  },

  /* src/platform implements the wrappers the bans point to. */
  {
    files: ['src/platform/**/*.{ts,tsx}'],
    rules: { 'no-restricted-properties': 'off' },
  },

  /* src/protocol: framework-free. */
  {
    files: ['src/protocol/**/*.ts'],
    rules: { 'no-restricted-imports': ['error', PROTOCOL_PURITY] },
  },

  /* Tests may use jsdom/test-only APIs and reach into packages. */
  {
    files: ['src/**/*.test.{ts,tsx}', 'src/test/**/*.{ts,tsx}'],
    rules: {
      'compat/compat': 'off',
      'no-restricted-properties': 'off',
      'no-restricted-imports': ['error', { paths: [REACT_DOM_LEGACY] }],
    },
  },

  /* The RegExp guard's own test builds unsafe patterns on purpose. */
  {
    files: ['src/test/regexpGuard*.test.ts'],
    rules: {
      'es-x/no-regexp-lookbehind-assertions': 'off',
      'es-x/no-regexp-named-capture-groups': 'off',
      'es-x/no-regexp-d-flag': 'off',
      'es-x/no-regexp-v-flag': 'off',
    },
  },
]);
