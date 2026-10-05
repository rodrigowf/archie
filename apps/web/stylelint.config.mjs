/**
 * Stylelint (spec 13 §6.3). Bans what PostCSS cannot lower for Safari 12; the compat bundle
 * scanner re-checks the built CSS. `plugin/no-unsupported-browser-features` runs on the compat
 * browserslist as a warning pass (it cannot know which features PostCSS lowers).
 */
import { browserslistEnv } from './scripts/browserslist-env.mjs';

/** @type {import('stylelint').Config} */
export default {
  extends: ['stylelint-config-standard'],
  plugins: ['stylelint-no-unsupported-browser-features'],
  rules: {
    // Flex gap needs Safari 14.1: use the layout primitives (spec 13 §2.1). grid-gap stays allowed.
    'property-disallowed-list': [
      ['gap', 'row-gap', 'column-gap', 'aspect-ratio'],
      { message: (p) => `"${p}" is not supported on Safari 12 (spec 13 §2.1, §2.6)` },
    ],
    'selector-pseudo-class-disallowed-list': [['is', 'where', 'has']],
    'function-disallowed-list': [['color-mix', 'clamp']],
    'unit-disallowed-list': [['dvh', 'svh', 'lvh', 'dvw', 'svw', 'lvw']],
    'at-rule-disallowed-list': [['layer', 'container']],
    'plugin/no-unsupported-browser-features': [
      true,
      {
        severity: 'warning',
        browsers: browserslistEnv('compat'),
        // Lowered by postcss-preset-env / autoprefixer, or handled by a polyfill.
        ignore: ['css-nesting', 'css-logical-props', 'css-focus-visible', 'css-sticky', 'css-backdrop-filter', 'css-masks', 'css-media-range-syntax'],
        ignorePartialSupport: true,
      },
    ],
    // CSS Modules: camelCase class names, :global(), composes.
    'selector-class-pattern': [
      '^[a-z][a-zA-Z0-9]*(?:-[a-zA-Z0-9]+)*$',
      { message: (s) => `class "${s}" should be camelCase or kebab-case` },
    ],
    'selector-pseudo-class-no-unknown': [true, { ignorePseudoClasses: ['global', 'local'] }],
    'property-no-unknown': [true, { ignoreProperties: ['composes'] }],
    'at-rule-no-unknown': [true, { ignoreAtRules: ['custom-media'] }],
    'custom-media-pattern': null,
  },
};
