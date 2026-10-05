import { describe, expect, it } from 'vitest';
import { bodyHtml, headHtml } from '../vite-plugin-html-target.ts';

describe('html target plugin', () => {
  it('main: manifest, apple-touch-icon, remote console (off by default)', () => {
    const head = headHtml({ target: 'main', base: '/', isBuild: true });
    expect(head).toContain('<link rel="manifest" href="/manifest.json" />');
    expect(head).toContain('<link rel="apple-touch-icon" href="/icon-192.png" />');
    expect(head).toContain('"defaultOn":false');
    expect(head).toContain('"prefix":""');
  });

  it('compat: no manifest; remote console on with the [compat] prefix; base-relative icon', () => {
    const head = headHtml({ target: 'compat', base: '/compat/', isBuild: true });
    expect(head).not.toContain('manifest');
    expect(head).toContain('href="/compat/icon.svg"');
    expect(head).toContain('"defaultOn":true');
    expect(head).toContain('"prefix":"[compat] "');
  });

  it('preloads the latin Roboto Flex woff2 once it is in the bundle', () => {
    const head = headHtml({
      target: 'compat',
      base: '/next-compat/',
      isBuild: true,
      bundleFiles: ['assets/index.js', 'assets/roboto-flex-latin-wght-normal-AbC123.woff2', 'assets/roboto-flex-latin-ext-wght-normal-x.woff2'],
    });
    expect(head).toContain('<link rel="preload" as="font" type="font/woff2" crossorigin href="/next-compat/assets/roboto-flex-latin-wght-normal-AbC123.woff2" />');
    expect(head.match(/rel="preload"/g)).toHaveLength(1);
  });

  it('registers the service worker only for the production main build at /', () => {
    expect(bodyHtml({ target: 'main', base: '/', isBuild: true })).toContain("register('/sw.js')");
    expect(bodyHtml({ target: 'main', base: '/next/', isBuild: true })).toBe('');
    expect(bodyHtml({ target: 'main', base: '/', isBuild: false })).toBe('');
    expect(bodyHtml({ target: 'compat', base: '/compat/', isBuild: true })).toBe('');
  });
});
