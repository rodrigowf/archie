import { describe, expect, it } from 'vitest';
import { renderUi } from '@/test/render';
import { expectNoAxeViolations } from '@/test/axe';
import { Icon } from '../Icon/Icon';
import { Box, Center, Cluster, Inline, Split, Stack } from './Layout';

describe('layout primitives', () => {
  it('Inline wraps text nodes so the owl spaces icon + label (text-node trap)', () => {
    const { container } = renderUi(
      <Inline space="3">
        <Icon name="terminal" />
        New agent {2}
      </Inline>,
    );
    const root = container.firstElementChild as HTMLElement;
    expect(root.className).toBe('inline');
    expect(root.style.getPropertyValue('--inline-space')).toBe('var(--app-space-3)');
    const kids = Array.from(root.childNodes);
    // svg + one span per text child; no bare text nodes left
    expect(kids.every((n) => n.nodeType === Node.ELEMENT_NODE)).toBe(true);
    expect(kids.map((n) => (n as Element).tagName.toLowerCase())).toEqual(['svg', 'span', 'span']);
  });

  it('flattens fragments and drops null/false children', () => {
    const show = false;
    const { container } = renderUi(
      <Split>
        <>
          <b>a</b>
          text
        </>
        {show && <i>hidden</i>}
        {null}
        <button type="button">act</button>
      </Split>,
    );
    const root = container.firstElementChild as HTMLElement;
    expect(Array.from(root.children).map((n) => n.tagName.toLowerCase())).toEqual(['b', 'span', 'button']);
  });

  it('Cluster puts the negative margin on an inner element; lists keep <li> as direct children', async () => {
    const { container } = renderUi(
      <Cluster as="ul" space="4" aria-label="Chips">
        <li>One</li>
        <li>Two</li>
      </Cluster>,
    );
    const outer = container.firstElementChild as HTMLElement;
    expect(outer.tagName).toBe('DIV');
    expect(outer.className).toBe('cluster');
    expect(outer.style.getPropertyValue('--cluster-space')).toBe('var(--app-space-4)');
    const inner = outer.firstElementChild as HTMLElement;
    expect(inner.tagName).toBe('UL');
    expect(inner.className).toBe('cluster-inner');
    expect(inner.children).toHaveLength(2);
    await expectNoAxeViolations(container);
  });

  it('Stack, Center and Box set their custom properties and element', () => {
    const { container } = renderUi(
      <Center as="main" max={600} gutter="4">
        <Stack as="section" space="6" align="start" aria-label="s">
          <Box padX="5" padY="2">x</Box>
          <Box>y</Box>
        </Stack>
      </Center>,
    );
    const center = container.querySelector('main') as HTMLElement;
    expect(center.className).toBe('center');
    expect(center.style.getPropertyValue('--center-max')).toBe('600px');
    expect(center.style.getPropertyValue('--center-gutter')).toBe('var(--app-space-4)');
    const stack = container.querySelector('section') as HTMLElement;
    expect(stack.style.getPropertyValue('--stack-space')).toBe('var(--app-space-6)');
    expect(stack.style.alignItems).toBe('flex-start');
    const [b1, b2] = Array.from(stack.children) as HTMLElement[];
    expect(b1?.style.getPropertyValue('--box-padding')).toBe('var(--app-space-2) var(--app-space-5)');
    expect(b2?.style.getPropertyValue('--box-padding')).toBe('');
  });

  it('forwards refs and extra attributes', () => {
    let el: HTMLElement | null = null;
    renderUi(
      <Stack
        ref={(n) => {
          el = n;
        }}
        id="s1"
        data-x="1"
      >
        <div />
      </Stack>,
    );
    expect(el).not.toBeNull();
    expect((el as unknown as HTMLElement).id).toBe('s1');
  });
});
