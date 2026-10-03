/**
 * Render helper for component tests: React Testing Library + a user-event instance.
 *   const { user, getByRole } = renderUi(<Button>Hi</Button>);
 */
import { render, type RenderOptions, type RenderResult } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';

export type UserEventInstance = ReturnType<typeof userEvent.setup>;

export function renderUi(ui: ReactElement, options?: RenderOptions): RenderResult & { user: UserEventInstance } {
  const user = userEvent.setup();
  return { user, ...render(ui, options) };
}
