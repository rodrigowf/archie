import { describe, expect, it } from 'vitest';
import { expectNoAxeViolations } from '@/test/axe';
import { renderUi } from '@/test/render';
import { App } from './App';

describe('hello page (W-01 placeholder)', () => {
  it('renders build info and passes the regex check', async () => {
    const { getByRole, getByText, container } = renderUi(<App />);
    expect(getByRole('heading', { level: 1, name: 'Archie' })).toBeTruthy();
    expect(getByText(/^ok \(major \d+, sha /)).toBeTruthy();
    await expectNoAxeViolations(container);
  });
});
