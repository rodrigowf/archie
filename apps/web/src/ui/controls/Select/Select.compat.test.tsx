import { describe, expect, it } from 'vitest';
import { renderUi } from '@/test/render';
import { Select, prefersNativeSelect } from './Select';

// The compat project builds with __TARGET__ = 'compat' (Safari 12 / iPad): always the native picker.
describe('Select on the compat build', () => {
  it('is native even on a fine pointer', () => {
    expect(prefersNativeSelect()).toBe(true);
    const { container, queryByRole } = renderUi(<Select label="Model" options={[{ value: 'a', label: 'Alpha' }]} />);
    expect(container.querySelector('select')).not.toBeNull();
    expect(queryByRole('combobox', { name: 'Model' })?.tagName).toBe('SELECT');
  });
});
