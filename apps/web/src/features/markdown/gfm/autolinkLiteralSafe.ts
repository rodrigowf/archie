/**
 * Lookbehind-free fork of `mdast-util-gfm-autolink-literal` 2.0.1 (spec 13 §2.4).
 *
 * vite.shared.ts aliases the bare specifier `mdast-util-gfm-autolink-literal` to this file on
 * BOTH builds, so `remark-gfm` → `mdast-util-gfm` picks it up and both builds render the same
 * output. The stock module's email pattern is a regex LITERAL with a lookbehind
 * (`/(?<=^|\s|\p{P}|\p{S})([-.\w+]+)@…/gu`), which Safari 12 rejects at parse time: one such
 * literal anywhere in a chunk is a white screen on the iPad mini 2.
 *
 * The only change: the email finder. Instead of the lookbehind it uses a leading boundary group,
 * `(^|[\s\p{P}\p{S}])`, inside a small RegExp-like object whose `exec` moves the match index past
 * the boundary character and drops that group, so `mdast-util-find-and-replace` sees exactly the
 * match (index, text and groups) the lookbehind version produces. Everything else is a straight
 * TypeScript port. `gfm/autolinkLiteralSafe.test.ts` checks the output against the stock module
 * (run on the main test project only, as an oracle) on the markdown corpus and a random corpus.
 *
 * Original: https://github.com/syntax-tree/mdast-util-gfm-autolink-literal (MIT)
 * Copyright (c) Titus Wormer <tituswormer@gmail.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the 'Software'), to deal in the Software without
 * restriction, including without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED 'AS IS', WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
import type { Link, PhrasingContent, Root } from 'mdast';
import { ccount } from 'ccount';
import { findAndReplace, type RegExpMatchObject } from 'mdast-util-find-and-replace';
import type {
  CompileContext,
  Extension as FromMarkdownExtension,
  Handle as FromMarkdownHandle,
  Token,
} from 'mdast-util-from-markdown';
import type { ConstructName, Options as ToMarkdownExtension } from 'mdast-util-to-markdown';
import { unicodePunctuation, unicodeWhitespace } from 'micromark-util-character';

const inConstruct: ConstructName = 'phrasing';
const notInConstruct: ConstructName[] = ['autolink', 'link', 'image', 'label'];

/** Extension for `mdast-util-from-markdown` that enables GFM autolink literals. */
export function gfmAutolinkLiteralFromMarkdown(): FromMarkdownExtension {
  return {
    transforms: [transformGfmAutolinkLiterals],
    enter: {
      literalAutolink: enterLiteralAutolink,
      literalAutolinkEmail: enterLiteralAutolinkValue,
      literalAutolinkHttp: enterLiteralAutolinkValue,
      literalAutolinkWww: enterLiteralAutolinkValue,
    },
    exit: {
      literalAutolink: exitLiteralAutolink,
      literalAutolinkEmail: exitLiteralAutolinkEmail,
      literalAutolinkHttp: exitLiteralAutolinkHttp,
      literalAutolinkWww: exitLiteralAutolinkWww,
    },
  };
}

/** Extension for `mdast-util-to-markdown` that enables GFM autolink literals (unchanged). */
export function gfmAutolinkLiteralToMarkdown(): ToMarkdownExtension {
  return {
    unsafe: [
      { character: '@', before: '[+\\-.\\w]', after: '[\\-.\\w]', inConstruct, notInConstruct },
      { character: '.', before: '[Ww]', after: '[\\-.\\w]', inConstruct, notInConstruct },
      { character: ':', before: '[ps]', after: '\\/', inConstruct, notInConstruct },
    ],
  };
}

function handle(handles: Record<string, FromMarkdownHandle>, name: string): FromMarkdownHandle {
  const fn = handles[name];
  if (!fn) throw new Error(`mdast-util-from-markdown has no "${name}" handler`);
  return fn;
}

function enterLiteralAutolink(this: CompileContext, token: Token): undefined {
  this.enter({ type: 'link', title: null, url: '', children: [] }, token);
}

function enterLiteralAutolinkValue(this: CompileContext, token: Token): undefined {
  handle(this.config.enter, 'autolinkProtocol').call(this, token);
}

function exitLiteralAutolinkHttp(this: CompileContext, token: Token): undefined {
  handle(this.config.exit, 'autolinkProtocol').call(this, token);
}

function exitLiteralAutolinkWww(this: CompileContext, token: Token): undefined {
  handle(this.config.exit, 'data').call(this, token);
  const node = this.stack[this.stack.length - 1];
  if (!node || node.type !== 'link') throw new Error('expected a link on the stack');
  node.url = 'http://' + this.sliceSerialize(token);
}

function exitLiteralAutolinkEmail(this: CompileContext, token: Token): undefined {
  handle(this.config.exit, 'autolinkEmail').call(this, token);
}

function exitLiteralAutolink(this: CompileContext, token: Token): undefined {
  this.exit(token);
}

/* -------------------------------------------------------------------------------------------- */
/* The lookbehind-free email finder.                                                             */
/* -------------------------------------------------------------------------------------------- */

// Stock: /(?<=^|\s|\p{P}|\p{S})([-.\w+]+)@([-\w]+(?:\.[-\w]+)+)/gu
// The boundary is a real (consumed) group here. `^` comes first so that, at index 0, the match
// that starts at 0 wins, as it does with the lookbehind (leftmost start).
const EMAIL_AT_START = /(^|[\s\p{P}\p{S}])([-.\w+]+)@([-\w]+(?:\.[-\w]+)+)/gu;
// Used when the search starts after index 0: the boundary must be a real character.
const EMAIL_AFTER_START = /([\s\p{P}\p{S}])([-.\w+]+)@([-\w]+(?:\.[-\w]+)+)/gu;

/**
 * Emulates the stock lookbehind regex for `findAndReplace`, which only uses `lastIndex`,
 * `global` and `exec()`. The result is the leftmost match that starts at or after `lastIndex`
 * and is preceded by the start of the input, whitespace, punctuation or a symbol.
 */
class EmailFinder {
  lastIndex = 0;
  readonly global = true;

  exec(input: string): RegExpExecArray | null {
    const from = this.lastIndex;
    // A lookbehind may look at the character just before `from`, so start one earlier and let
    // the (consumed) boundary group take that character.
    const re = from === 0 ? EMAIL_AT_START : EMAIL_AFTER_START;
    re.lastIndex = from === 0 ? 0 : from - 1;
    const m = re.exec(input);
    if (!m) {
      this.lastIndex = 0;
      return null;
    }
    const boundary = m[1] ?? '';
    const atext = m[2] ?? '';
    const label = m[3] ?? '';
    const full = m[0].slice(boundary.length);
    const result = [full, atext, label] as unknown as RegExpExecArray;
    result.index = m.index + boundary.length;
    result.input = input;
    this.lastIndex = result.index + full.length;
    return result;
  }
}

function transformGfmAutolinkLiterals(tree: Root): undefined {
  findAndReplace(
    tree,
    [
      [/(https?:\/\/|www(?=\.))([-.\w]+)([^ \t\r\n]*)/gi, findUrl],
      [new EmailFinder() as unknown as RegExp, findEmail],
    ],
    { ignore: ['link', 'linkReference'] },
  );
}

function findUrl(
  _: string,
  protocol: string,
  domain: string,
  path: string,
  match: RegExpMatchObject,
): PhrasingContent[] | Link | false {
  let prefix = '';

  // Not an expected previous character.
  if (!previous(match)) return false;

  // Treat `www` as part of the domain.
  if (/^w/i.test(protocol)) {
    domain = protocol + domain;
    protocol = '';
    prefix = 'http://';
  }

  if (!isCorrectDomain(domain)) return false;

  const parts = splitUrl(domain + path);

  if (!parts[0]) return false;

  const result: Link = {
    type: 'link',
    title: null,
    url: prefix + protocol + parts[0],
    children: [{ type: 'text', value: protocol + parts[0] }],
  };

  if (parts[1]) return [result, { type: 'text', value: parts[1] }];

  return result;
}

function findEmail(_: string, atext: string, label: string, match: RegExpMatchObject): Link | false {
  if (
    // Not an expected previous character.
    !previous(match, true) ||
    // Label ends in not allowed character.
    /[-\d_]$/.test(label)
  ) {
    return false;
  }

  return {
    type: 'link',
    title: null,
    url: 'mailto:' + atext + '@' + label,
    children: [{ type: 'text', value: atext + '@' + label }],
  };
}

function isCorrectDomain(domain: string): boolean {
  const parts = domain.split('.');
  const last = parts[parts.length - 1];
  const beforeLast = parts[parts.length - 2];

  if (
    parts.length < 2 ||
    (last && (/_/.test(last) || !/[a-zA-Z\d]/.test(last))) ||
    (beforeLast && (/_/.test(beforeLast) || !/[a-zA-Z\d]/.test(beforeLast)))
  ) {
    return false;
  }

  return true;
}

function splitUrl(url: string): [string, string | undefined] {
  const trailExec = /[!"&'),.:;<>?\]}]+$/.exec(url);

  if (!trailExec) return [url, undefined];

  url = url.slice(0, trailExec.index);

  let trail = trailExec[0];
  let closingParenIndex = trail.indexOf(')');
  const openingParens = ccount(url, '(');
  let closingParens = ccount(url, ')');

  while (closingParenIndex !== -1 && openingParens > closingParens) {
    url += trail.slice(0, closingParenIndex + 1);
    trail = trail.slice(closingParenIndex + 1);
    closingParenIndex = trail.indexOf(')');
    closingParens++;
  }

  return [url, trail];
}

function previous(match: RegExpMatchObject, email?: boolean): boolean {
  const code = match.input.charCodeAt(match.index - 1);

  return (
    (match.index === 0 || unicodeWhitespace(code) || unicodePunctuation(code)) &&
    // If it’s an email, the previous character should not be a slash.
    (!email || code !== 47)
  );
}
