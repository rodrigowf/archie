/**
 * Copy text to the clipboard. Uses the async Clipboard API when it exists (secure context,
 * Safari 13.1+); otherwise a hidden textarea + document.execCommand('copy'), which is the only
 * path on Safari 12 and on plain-HTTP origins (spec 13 §2.4).
 */
import { getCapabilities } from './capabilities';

export function copyWithTextarea(text: string, doc: Document = document): boolean {
  const ta = doc.createElement('textarea');
  ta.value = text;
  ta.setAttribute('readonly', '');
  ta.setAttribute('aria-hidden', 'true');
  // 16px avoids the iOS focus zoom; off-screen, not display:none (which cannot be selected).
  ta.style.cssText = 'position:fixed;top:0;left:-9999px;opacity:0;font-size:16px;';
  doc.body.appendChild(ta);
  const active = doc.activeElement as HTMLElement | null;
  let ok = false;
  try {
    ta.focus();
    ta.select();
    ta.setSelectionRange(0, text.length); // iOS ignores select() alone
    ok = doc.execCommand('copy');
  } catch {
    ok = false;
  } finally {
    doc.body.removeChild(ta);
    if (active && typeof active.focus === 'function') active.focus();
  }
  return ok;
}

export async function copyText(text: string): Promise<boolean> {
  if (getCapabilities().clipboardApi) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch {
      // Permission denied or not focused: try the fallback.
    }
  }
  return copyWithTextarea(text);
}
