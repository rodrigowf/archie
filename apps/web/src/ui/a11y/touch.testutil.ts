/** Test helper: dispatch a synthetic touch event (jsdom has no Touch constructor). Tests only. */
export function touch(el: Element, type: 'touchstart' | 'touchmove' | 'touchend', x: number, y: number, timeStamp?: number): Event {
  const e = new Event(type, { bubbles: true, cancelable: true });
  const point = [{ clientX: x, clientY: y }];
  Object.defineProperty(e, 'touches', { value: type === 'touchend' ? [] : point });
  Object.defineProperty(e, 'changedTouches', { value: point });
  if (timeStamp !== undefined) Object.defineProperty(e, 'timeStamp', { value: timeStamp });
  el.dispatchEvent(e);
  return e;
}
