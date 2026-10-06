import { createRef } from 'react';
import { describe, expect, it } from 'vitest';
import { renderUi } from '@/test/render';
import { expectNoAxeViolations } from '@/test/axe';
import { ScrollArea, type ScrollAreaHandle } from './ScrollArea';

describe('ScrollArea', () => {
  it('renders one labelled scroll region and exposes the imperative API', async () => {
    const ref = createRef<ScrollAreaHandle>();
    const { getByRole, container } = renderUi(
      <ScrollArea ref={ref} role="region" aria-label="Messages" tabIndex={0} className="extra">
        <p>Hello</p>
      </ScrollArea>,
    );
    const region = getByRole('region', { name: 'Messages' });
    expect(region.className).toContain('extra');
    expect(ref.current?.element).toBe(region);
    expect(ref.current?.isNearBottom()).toBe(true);
    await ref.current?.scrollToBottom();
    expect(ref.current?.isUserScrolling()).toBe(false);
    await expectNoAxeViolations(container);
  });

  it('runs a preserveAnchor mutation synchronously when idle', async () => {
    const ref = createRef<ScrollAreaHandle>();
    renderUi(
      <ScrollArea ref={ref}>
        <p>Old</p>
      </ScrollArea>,
    );
    let ran = false;
    await ref.current?.preserveAnchor(() => {
      ran = true;
    });
    expect(ran).toBe(true);
  });
});
