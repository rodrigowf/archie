/**
 * Background inertness for modals (spec 13 §2.6: no native `inert` or `<dialog>` on Safari 12).
 * Marks every sibling of the overlay and of each of its ancestors up to <body> with
 * `aria-hidden="true"`, and undoes exactly that on release. Reference-counted per element, so
 * nested modals compose: the inner modal hides the outer one, and releasing in any order restores
 * the original attributes.
 *
 * Elements marked `data-a11y-keep` (the shared live region, the snackbar host) stay exposed, so
 * announcements still reach screen readers while a modal is open.
 */

const counts = new Map<Element, number>();
const SKIP = new Set(['SCRIPT', 'STYLE', 'TEMPLATE', 'LINK', 'META']);

function hide(el: Element, touched: Element[]): void {
  if (SKIP.has(el.tagName) || el.hasAttribute('data-a11y-keep')) return;
  const n = counts.get(el);
  if (n === undefined) {
    if (el.getAttribute('aria-hidden') === 'true') return; // hidden by its owner: not ours
    el.setAttribute('aria-hidden', 'true');
    counts.set(el, 1);
  } else {
    counts.set(el, n + 1);
  }
  touched.push(el);
}

export function hideOthers(target: Element): () => void {
  const touched: Element[] = [];
  let node: Element | null = target;
  while (node && node !== document.body && node.parentElement) {
    const parent: Element = node.parentElement;
    for (const sib of Array.from(parent.children)) {
      if (sib !== node && !sib.contains(target)) hide(sib, touched);
    }
    node = parent;
  }
  let released = false;
  return () => {
    if (released) return;
    released = true;
    for (const el of touched) {
      const n = counts.get(el) ?? 0;
      if (n <= 1) {
        counts.delete(el);
        el.removeAttribute('aria-hidden');
      } else {
        counts.set(el, n - 1);
      }
    }
  };
}
