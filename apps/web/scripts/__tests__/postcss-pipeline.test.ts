/**
 * The PostCSS pipeline of vite.shared.ts: size classes from one media.css reach every file,
 * and the compat output is Safari-12-safe (spec 13 §2.2).
 */
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import postcss from 'postcss';
import { afterAll, describe, expect, it } from 'vitest';
import { postcssPlugins } from '../../vite.shared.ts';
import { scanCss } from '../scan-compat-bundle.mjs';

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'fn-postcss-'));
const mediaFile = path.join(dir, 'media.css');
fs.writeFileSync(
  mediaFile,
  '@custom-media --compact (max-width: 599.98px);\n@custom-media --expanded (min-width: 840px);\n',
);
afterAll(() => {
  fs.rmSync(dir, { recursive: true, force: true });
});

async function run(target: 'main' | 'compat', css: string): Promise<string> {
  const result = await postcss(postcssPlugins(target, mediaFile)).process(css, { from: path.join(dir, 'x.module.css') });
  return result.css;
}

const SOURCE = `
.panel {
  position: fixed;
  inset: 0;
  margin-inline: auto;
  padding-block: 8px;
  & .title { color: rgb(1 2 3); }
  @media (--compact) { padding-inline-start: 4px; }
}
.ring:focus-visible { outline: 2px solid; }
.scrim { backdrop-filter: blur(4px); }
`;

describe('PostCSS pipeline', () => {
  it('resolves @custom-media from media.css in every file', async () => {
    const out = await run('main', '@media (--expanded) { .a { margin: 0 } }');
    expect(out).toContain('@media (min-width: 840px)');
    expect(out).not.toContain('--expanded');
  });

  it('compat output passes the bundle scanner (inset, logical props, nesting, :focus-visible lowered)', async () => {
    const out = await run('compat', SOURCE);
    expect(out).toContain('max-width: 599.98px');
    expect(out).toContain('.focus-visible');
    expect(out).toContain('-webkit-backdrop-filter');
    expect(scanCss(out, { file: 'compat.css' })).toEqual([]);
  });
});
