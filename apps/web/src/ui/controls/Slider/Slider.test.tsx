import { fireEvent } from '@testing-library/react';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { expectNoAxeViolations } from '@/test/axe';
import { renderUi } from '@/test/render';
import { Slider } from './Slider';

function drag(input: HTMLElement, values: number[]): void {
  fireEvent.mouseDown(input);
  for (const v of values) fireEvent.input(input, { target: { value: String(v) } });
  // Release: the browser fires `change` once, then mouseup.
  fireEvent.change(input);
  fireEvent.mouseUp(window);
}

describe('Slider', () => {
  it('is a native range input with min/max/step, a name and a value text; passes axe', async () => {
    const { getByRole, container } = renderUi(
      <Slider aria-label="VAD threshold" min={0} max={1} step={0.01} defaultValue={0.3} formatValue={(x) => x.toFixed(2)} />,
    );
    const input = getByRole('slider', { name: 'VAD threshold' }) as HTMLInputElement;
    expect(input.type).toBe('range');
    expect([input.min, input.max, input.step, input.value]).toEqual(['0', '1', '0.01', '0.3']);
    expect(input.getAttribute('aria-valuetext')).toBe('0.30');
    await expectNoAxeViolations(container);
  });

  it('commits once on release, never per tick (inv02 §6.2)', () => {
    const onCommit = vi.fn();
    const onValueChange = vi.fn();
    const { getByRole } = renderUi(<Slider aria-label="Level" defaultValue={10} onCommit={onCommit} onValueChange={onValueChange} />);
    const input = getByRole('slider');
    drag(input, [20, 30, 40, 55]);
    expect(onValueChange.mock.calls.map((c) => c[0] as number)).toEqual([20, 30, 40, 55]);
    expect(onCommit).toHaveBeenCalledTimes(1);
    expect(onCommit).toHaveBeenCalledWith(55);
  });

  it('controlled: shows the live draft while dragging, then the committed value', () => {
    const saved: number[] = [];
    function Demo() {
      const [v, setV] = useState(10);
      return (
        <Slider
          aria-label="Level"
          value={v}
          onCommit={(x) => {
            saved.push(x);
            setV(x);
          }}
        />
      );
    }
    const { getByRole, container } = renderUi(<Demo />);
    const input = getByRole('slider') as HTMLInputElement;
    const root = container.firstElementChild as HTMLElement;
    fireEvent.mouseDown(input);
    fireEvent.input(input, { target: { value: '70' } });
    expect(input.value).toBe('70');
    expect(root.style.getPropertyValue('--slider-f')).toBe('0.7');
    expect(root.className).toMatch(/dragging/);
    expect(saved).toEqual([]);
    fireEvent.change(input);
    fireEvent.mouseUp(window);
    expect(saved).toEqual([70]);
    expect(input.value).toBe('70');
    expect(root.className).not.toMatch(/dragging/);
  });

  it('a controlled parent that rejects the commit snaps the slider back', () => {
    const { getByRole } = renderUi(<Slider aria-label="Level" value={10} onCommit={() => undefined} />);
    const input = getByRole('slider') as HTMLInputElement;
    drag(input, [80]);
    expect(input.value).toBe('10');
  });

  it('keyboard steps commit per step', async () => {
    const onCommit = vi.fn();
    const { user, getByRole } = renderUi(<Slider aria-label="Level" defaultValue={10} onCommit={onCommit} />);
    const input = getByRole('slider') as HTMLInputElement;
    input.focus();
    // jsdom has no range keyboard behaviour: emulate the browser (input then change per key).
    await user.keyboard('{ArrowRight}');
    fireEvent.input(input, { target: { value: '11' } });
    fireEvent.change(input);
    expect(onCommit).toHaveBeenLastCalledWith(11);
  });

  it('disabled: the input is disabled', () => {
    const { getByRole } = renderUi(<Slider aria-label="Level" defaultValue={10} disabled />);
    expect((getByRole('slider') as HTMLInputElement).disabled).toBe(true);
  });
});
