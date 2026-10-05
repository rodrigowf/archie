/**
 * highlight.js language registry, MAIN build (spec 13 §2.4), selected by the alias
 * `@/features/markdown/highlight/languages` (vite.shared.ts). The core set (same as compat) plus
 * extra languages, each its own lazy chunk loaded the first time a code block names it. Several
 * extras use lookbehind (swift, scala, haskell, fsharp, r, gcode): they must never be reachable
 * from compat, which does not import this file (the compat scanner would fail).
 */
import type { LanguageFn } from 'highlight.js';
import { coreLanguages } from './languages.core';
import type { LanguageRegistry } from './types';

type GrammarModule = { default: LanguageFn };

/** Extra language name → lazy chunk. */
export const EXTRA_LANGUAGE_LOADERS: Record<string, () => Promise<GrammarModule>> = {
  apache: () => import('highlight.js/lib/languages/apache'),
  applescript: () => import('highlight.js/lib/languages/applescript'),
  arduino: () => import('highlight.js/lib/languages/arduino'),
  armasm: () => import('highlight.js/lib/languages/armasm'),
  autohotkey: () => import('highlight.js/lib/languages/autohotkey'),
  awk: () => import('highlight.js/lib/languages/awk'),
  clojure: () => import('highlight.js/lib/languages/clojure'),
  cmake: () => import('highlight.js/lib/languages/cmake'),
  coffeescript: () => import('highlight.js/lib/languages/coffeescript'),
  crystal: () => import('highlight.js/lib/languages/crystal'),
  d: () => import('highlight.js/lib/languages/d'),
  dart: () => import('highlight.js/lib/languages/dart'),
  delphi: () => import('highlight.js/lib/languages/delphi'),
  django: () => import('highlight.js/lib/languages/django'),
  dos: () => import('highlight.js/lib/languages/dos'),
  elixir: () => import('highlight.js/lib/languages/elixir'),
  elm: () => import('highlight.js/lib/languages/elm'),
  erb: () => import('highlight.js/lib/languages/erb'),
  erlang: () => import('highlight.js/lib/languages/erlang'),
  fortran: () => import('highlight.js/lib/languages/fortran'),
  fsharp: () => import('highlight.js/lib/languages/fsharp'),
  gcode: () => import('highlight.js/lib/languages/gcode'),
  gherkin: () => import('highlight.js/lib/languages/gherkin'),
  glsl: () => import('highlight.js/lib/languages/glsl'),
  gradle: () => import('highlight.js/lib/languages/gradle'),
  graphql: () => import('highlight.js/lib/languages/graphql'),
  groovy: () => import('highlight.js/lib/languages/groovy'),
  haml: () => import('highlight.js/lib/languages/haml'),
  handlebars: () => import('highlight.js/lib/languages/handlebars'),
  haskell: () => import('highlight.js/lib/languages/haskell'),
  http: () => import('highlight.js/lib/languages/http'),
  julia: () => import('highlight.js/lib/languages/julia'),
  latex: () => import('highlight.js/lib/languages/latex'),
  less: () => import('highlight.js/lib/languages/less'),
  lisp: () => import('highlight.js/lib/languages/lisp'),
  llvm: () => import('highlight.js/lib/languages/llvm'),
  matlab: () => import('highlight.js/lib/languages/matlab'),
  nim: () => import('highlight.js/lib/languages/nim'),
  nix: () => import('highlight.js/lib/languages/nix'),
  objectivec: () => import('highlight.js/lib/languages/objectivec'),
  ocaml: () => import('highlight.js/lib/languages/ocaml'),
  perl: () => import('highlight.js/lib/languages/perl'),
  pgsql: () => import('highlight.js/lib/languages/pgsql'),
  prolog: () => import('highlight.js/lib/languages/prolog'),
  properties: () => import('highlight.js/lib/languages/properties'),
  protobuf: () => import('highlight.js/lib/languages/protobuf'),
  r: () => import('highlight.js/lib/languages/r'),
  reasonml: () => import('highlight.js/lib/languages/reasonml'),
  scala: () => import('highlight.js/lib/languages/scala'),
  scheme: () => import('highlight.js/lib/languages/scheme'),
  smalltalk: () => import('highlight.js/lib/languages/smalltalk'),
  stylus: () => import('highlight.js/lib/languages/stylus'),
  swift: () => import('highlight.js/lib/languages/swift'),
  tcl: () => import('highlight.js/lib/languages/tcl'),
  vbnet: () => import('highlight.js/lib/languages/vbnet'),
  verilog: () => import('highlight.js/lib/languages/verilog'),
  vhdl: () => import('highlight.js/lib/languages/vhdl'),
  vim: () => import('highlight.js/lib/languages/vim'),
  wasm: () => import('highlight.js/lib/languages/wasm'),
  x86asm: () => import('highlight.js/lib/languages/x86asm'),
};

/** Aliases of the extra languages (from each grammar's `aliases`; none clash with the core set). */
export const EXTRA_LANGUAGE_ALIASES: Record<string, string> = {
  ahk: 'autohotkey',
  apacheconf: 'apache',
  arm: 'armasm',
  bat: 'dos',
  batch: 'dos',
  clj: 'clojure',
  'cmake.in': 'cmake',
  cmd: 'dos',
  coffee: 'coffeescript',
  cr: 'crystal',
  cson: 'coffeescript',
  dfm: 'delphi',
  dpr: 'delphi',
  edn: 'clojure',
  erl: 'erlang',
  ex: 'elixir',
  exs: 'elixir',
  'f#': 'fsharp',
  f90: 'fortran',
  f95: 'fortran',
  feature: 'gherkin',
  fs: 'fsharp',
  gql: 'graphql',
  hbs: 'handlebars',
  hs: 'haskell',
  'html.handlebars': 'handlebars',
  'html.hbs': 'handlebars',
  htmlbars: 'handlebars',
  https: 'http',
  iced: 'coffeescript',
  ino: 'arduino',
  jinja: 'django',
  ml: 'ocaml',
  mm: 'objectivec',
  nc: 'gcode',
  nixos: 'nix',
  'obj-c': 'objectivec',
  'obj-c++': 'objectivec',
  objc: 'objectivec',
  'objective-c++': 'objectivec',
  osascript: 'applescript',
  pas: 'delphi',
  pascal: 'delphi',
  pl: 'perl',
  pm: 'perl',
  postgres: 'pgsql',
  postgresql: 'pgsql',
  proto: 'protobuf',
  re: 'reasonml',
  scm: 'scheme',
  st: 'smalltalk',
  styl: 'stylus',
  sv: 'verilog',
  svh: 'verilog',
  tex: 'latex',
  tk: 'tcl',
  v: 'verilog',
  vb: 'vbnet',
};

function extraNameOf(lang: string): string | null {
  if (lang in EXTRA_LANGUAGE_LOADERS) return lang;
  return EXTRA_LANGUAGE_ALIASES[lang] ?? null;
}

export const languageRegistry: LanguageRegistry = {
  target: 'main',
  core: coreLanguages,
  extraNames: [...Object.keys(EXTRA_LANGUAGE_LOADERS), ...Object.keys(EXTRA_LANGUAGE_ALIASES)],
  loadExtra: (lang) => {
    const name = extraNameOf(lang);
    const load = name ? EXTRA_LANGUAGE_LOADERS[name] : undefined;
    if (!name || !load) return null;
    return load().then((mod) => ({ [name]: mod.default }));
  },
};
