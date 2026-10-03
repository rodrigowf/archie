/**
 * Text-node trap guard (spec 13 §2.1; inv02 §5.3, commit 8b8fb9c, memory note
 * project_compat_gap_shim_text_node_trap.md). Owl selectors (`> * + *`) never match text nodes, so
 * `<Icon/> label` would get no spacing. Every string/number child becomes a <span>, and fragments
 * are flattened so their children are spaced like direct children.
 */
import { Children, Fragment, cloneElement, isValidElement, type ReactElement, type ReactNode } from 'react';

function flatten(children: ReactNode, prefix: string, out: ReactNode[]): void {
  Children.toArray(children).forEach((child, i) => {
    if (typeof child === 'string' || typeof child === 'number') {
      out.push(<span key={`${prefix}t${i}`}>{child}</span>);
      return;
    }
    if (!isValidElement(child)) return;
    const el = child as ReactElement<{ children?: ReactNode }>;
    if (el.type === Fragment) {
      flatten(el.props.children, `${prefix}${String(el.key)}/`, out);
      return;
    }
    out.push(prefix ? cloneElement(el, { key: `${prefix}${String(el.key)}` }) : el);
  });
}

export function wrapTextChildren(children: ReactNode): ReactNode[] {
  const out: ReactNode[] = [];
  flatten(children, '', out);
  return out;
}
