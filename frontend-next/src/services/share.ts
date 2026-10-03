/**
 * §6.15 Upload and share to Archie: upload (progress, nginx 413 mapping) → the Android-format
 * line → `inject_text` on the orchestrator socket (kept until `session_started` when not
 * subscribed). New on the web (spec 12 §6.15).
 */
import { getArchieRuntime } from './sessions/manager';
import { sharedFileText, sharedTextText, uploadFile, type UploadOptions } from './uploads';
import type { UploadResult } from './http/types';

export async function shareFile(file: Blob & { name?: string }, subject?: string, opts: UploadOptions = {}): Promise<UploadResult> {
  const r = await uploadFile(file, opts);
  const archie = getArchieRuntime();
  if (!archie) throw new Error('Open Archie to share a file');
  archie.inject(sharedFileText(r, subject));
  return r;
}

export function shareText(text: string, subject?: string): void {
  const archie = getArchieRuntime();
  if (!archie) throw new Error('Open Archie to share text');
  archie.inject(sharedTextText(text, subject));
}
