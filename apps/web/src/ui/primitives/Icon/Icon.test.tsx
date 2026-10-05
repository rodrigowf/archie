import { describe, expect, it } from 'vitest';
import { renderUi } from '@/test/render';
import { expectNoAxeViolations } from '@/test/axe';
import { filledIconPaths, iconPaths } from '@/ui/icons';
import { Icon } from './Icon';

describe('Icon', () => {
  it('renders a decorative 24 px currentColor svg by default', () => {
    const { container } = renderUi(<Icon name="terminal" />);
    const svg = container.querySelector('svg') as SVGSVGElement;
    expect(svg.getAttribute('aria-hidden')).toBe('true');
    expect(svg.getAttribute('width')).toBe('24');
    expect(svg.getAttribute('viewBox')).toBe('0 -960 960 960');
    expect(svg.querySelector('path')?.getAttribute('d')).toBe(iconPaths.terminal);
  });

  it('uses the FILL=1 path when filled', () => {
    const { container } = renderUi(<Icon name="forum" filled size={20} />);
    const svg = container.querySelector('svg') as SVGSVGElement;
    expect(svg.querySelector('path')?.getAttribute('d')).toBe(filledIconPaths.forum);
    expect(svg.getAttribute('height')).toBe('20');
  });

  it('is an image with a name when labelled', async () => {
    const { getByRole, container } = renderUi(<Icon name="warning" label="Disconnected" />);
    expect(getByRole('img', { name: 'Disconnected' })).toBeTruthy();
    await expectNoAxeViolations(container);
  });

  it('rejects unknown names and fill variants at compile time', () => {
    // @ts-expect-error not in the manifest
    const bad = <Icon name="not_an_icon" />;
    // @ts-expect-error terminal has no FILL=1 variant in the manifest
    const badFill = <Icon name="terminal" filled />;
    expect(bad).toBeTruthy();
    expect(badFill).toBeTruthy();
  });
});
