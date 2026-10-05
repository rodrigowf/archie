/**
 * M3 state layer (spec 13 §2.2): `currentColor` at the hover / focus / pressed / dragged opacity
 * from the tokens, over any container color. CSS in src/styles/primitives.css.
 *
 *   <button className={stateHostClass}>…<StateLayer /></button>   child element
 *   <button className={stateLayerClass}>…</button>                  the host's own ::before
 *
 * `data-state="hover|focus|pressed|dragged"` on the host forces a state (galleries, drag sources).
 * Disabled hosts (`disabled` / `aria-disabled="true"`) show no layer.
 */
export const stateHostClass = 'state-host';
export const stateLayerClass = 'has-state-layer';

export type ForcedState = 'hover' | 'focus' | 'pressed' | 'dragged';

export function StateLayer() {
  return <span className="state-layer" aria-hidden="true" />;
}
