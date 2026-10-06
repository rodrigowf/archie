/** Remote console log (inventory 01 §3.9). Writes go through `@/platform` `remoteLog`. */
import { http } from '../client';

export const debug = {
  readLog: () => http.get<string>('/api/debug/log', { as: 'text' }),
};
