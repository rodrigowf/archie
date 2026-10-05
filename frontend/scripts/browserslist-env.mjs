/**
 * Reads one environment section ([main] / [compat]) of .browserslistrc, for tools that take an
 * explicit query list (eslint-plugin-compat, stylelint-no-unsupported-browser-features).
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const RC = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '.browserslistrc');

/**
 * @param {'main' | 'compat'} env
 * @returns {string[]}
 */
export function browserslistEnv(env) {
  const lines = fs.readFileSync(RC, 'utf8').split(/\r?\n/);
  /** @type {string[]} */
  const out = [];
  let current = '';
  for (const raw of lines) {
    const line = raw.replace(/#.*/, '').trim();
    if (!line) continue;
    const section = /^\[(.+)\]$/.exec(line);
    if (section) {
      current = (section[1] ?? '').trim();
      continue;
    }
    if (current === env) out.push(line);
  }
  if (out.length === 0) throw new Error(`.browserslistrc has no [${env}] section`);
  return out;
}
