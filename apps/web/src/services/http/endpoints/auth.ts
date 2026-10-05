/** Claude CLI credentials of the backend (inventory 01 §3.1). */
import { http } from '../client';
import type { AuthStatus } from '../types';

export const auth = {
  status: () => http.get<AuthStatus>('/api/auth/status', { timeoutMs: 20_000 }),
  /** Non-headless only; blocks until `claude setup-token` exits on the server. */
  login: () => http.post<AuthStatus>('/api/auth/login', { timeoutMs: 0 }),
  credentials: (credentialsJson: string) => http.post<AuthStatus>('/api/auth/credentials', { json: { credentials_json: credentialsJson } }),
};
