import { describe, expect, it, vi } from 'vitest';
import { createMemoryLinkResolver, memoryFileUrl, resolveMemoryHref } from './links';

const HERE = 'assistant/architecture/voice_subsystem.md';

describe('resolveMemoryHref', () => {
  it.each([
    ['wakeword_subsystem.md', 'assistant/architecture/wakeword_subsystem.md'],
    ['./wakeword_subsystem.md', 'assistant/architecture/wakeword_subsystem.md'],
    ['../infrastructure/ssh.md', 'assistant/infrastructure/ssh.md'],
    ['../infrastructure/ssh.md#setup', 'assistant/infrastructure/ssh.md'],
    ['../../MEMORY.md', 'MEMORY.md'],
    ['/memory/rodrigo/context.md', 'rodrigo/context.md'],
    ['my%20notes.md', 'assistant/architecture/my notes.md'],
    ['NOTES.MD', 'assistant/architecture/NOTES.MD'],
  ])('%s → %s', (href, expected) => {
    expect(resolveMemoryHref(href, HERE)).toBe(expected);
  });

  it.each([
    'https://example.com/a.md',
    'mailto:a@b.com',
    '//cdn.example.com/a.md',
    '#section',
    '/docs/a.md',
    '../../../escape.md',
    'script.py',
    'folder/',
    '',
  ])('ignores %j', (href) => {
    expect(resolveMemoryHref(href, HERE)).toBeNull();
  });

  it('resolves against the root for a top-level file', () => {
    expect(resolveMemoryHref('rodrigo/a.md', 'MEMORY.md')).toBe('rodrigo/a.md');
  });
});

describe('memoryFileUrl', () => {
  it('URL-encodes each path segment (fixes inv02 §6.2)', () => {
    expect(memoryFileUrl('a b/c#d?.md')).toBe('/memory/a%20b/c%23d%3F.md');
    expect(memoryFileUrl('MEMORY.md')).toBe('/memory/MEMORY.md');
  });
});

describe('createMemoryLinkResolver', () => {
  it('returns the raw-file href and opens the path in-app', () => {
    const open = vi.fn();
    const resolve = createMemoryLinkResolver(HERE, open);
    const link = resolve('../infra/a b.md');
    expect(link?.href).toBe('/memory/assistant/infra/a%20b.md');
    expect(link?.title).toBe('assistant/infra/a b.md');
    link?.onActivate();
    expect(open).toHaveBeenCalledWith('assistant/infra/a b.md');
    expect(resolve('https://x.y')).toBeNull();
  });
});
