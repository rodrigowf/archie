import { describe, expect, it } from 'vitest';
import { renderUi } from '@/test/render';
import { expectNoAxeViolations } from '@/test/axe';
import { FocusRing } from '../FocusRing/FocusRing';
import { StateLayer } from '../StateLayer/StateLayer';
import { VisuallyHidden } from '../VisuallyHidden/VisuallyHidden';
import { Spinner } from './Spinner';

describe('small primitives', () => {
  it('Spinner is decorative without a label and a status with one', async () => {
    const { container, getByRole } = renderUi(
      <div>
        <Spinner />
        <Spinner size={18} label="Working" />
      </div>,
    );
    const [plain] = Array.from(container.querySelectorAll('span'));
    expect(plain?.getAttribute('aria-hidden')).toBe('true');
    expect(plain?.style.getPropertyValue('--spinner-size')).toBe('14px');
    expect(getByRole('status', { name: 'Working' })).toBeTruthy();
    await expectNoAxeViolations(container);
  });

  it('StateLayer and FocusRing are hidden decorations inside their host', async () => {
    const { container } = renderUi(
      <button type="button" className="state-host focus-ring-host" data-state="hover">
        Save
        <StateLayer />
        <FocusRing radius="20px" />
      </button>,
    );
    const layer = container.querySelector('.state-layer');
    const ring = container.querySelector('.focus-ring') as HTMLElement;
    expect(layer?.getAttribute('aria-hidden')).toBe('true');
    expect(ring.getAttribute('aria-hidden')).toBe('true');
    expect(ring.style.getPropertyValue('--focus-ring-radius')).toBe('20px');
    await expectNoAxeViolations(container);
  });

  it('VisuallyHidden keeps text in the accessibility tree', () => {
    const { getByText } = renderUi(<VisuallyHidden>3 sessions open</VisuallyHidden>);
    expect(getByText('3 sessions open').className).toBe('visually-hidden');
  });
});
