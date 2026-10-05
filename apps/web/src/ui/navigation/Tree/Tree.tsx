/**
 * Tree view (spec 13 §3.5, for Memory; WAI-ARIA APG tree pattern). `role="tree"` with nested
 * `role="group"` lists and `role="treeitem"` items carrying `aria-level`, `aria-setsize`,
 * `aria-posinset`, `aria-expanded` (folders) and `aria-selected`. One tab stop (roving).
 *
 * Keyboard: ↓/↑ next/previous visible item · → expand a folder, or go to its first child ·
 * ← collapse, or go to the parent · Home/End · Enter/Space activate (a folder toggles, a leaf
 * selects) · `*` expands all siblings · type-ahead on labels.
 */
import { useCallback, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import { createTypeahead, focusElement, isTypeaheadKey } from '@/ui/a11y';
import { Icon, type IconName } from '@/ui/primitives';
import styles from './Tree.module.css';

export interface TreeNode {
  id: string;
  label: string;
  /** Leaf icon (default `description`); folders use folder / folder_open. */
  icon?: IconName;
  /** Present (even empty) = a folder. */
  children?: TreeNode[];
  /** Trailing content (count, badge). */
  meta?: ReactNode;
}

export interface TreeProps {
  nodes: TreeNode[];
  'aria-label': string;
  /** Controlled expanded folder ids. */
  expanded?: readonly string[];
  defaultExpanded?: readonly string[];
  onExpandedChange?: (ids: string[]) => void;
  selectedId?: string | null;
  /** A leaf was activated (click, Enter, Space). */
  onSelect?: (node: TreeNode) => void;
  className?: string;
}

interface Visible {
  node: TreeNode;
  level: number;
  parentId: string | null;
  setSize: number;
  posInSet: number;
}

export function flattenVisible(nodes: TreeNode[], expanded: ReadonlySet<string>, level = 1, parentId: string | null = null, out: Visible[] = []): Visible[] {
  nodes.forEach((node, i) => {
    out.push({ node, level, parentId, setSize: nodes.length, posInSet: i + 1 });
    if (node.children && expanded.has(node.id)) flattenVisible(node.children, expanded, level + 1, node.id, out);
  });
  return out;
}

export function Tree({ nodes, expanded: controlled, defaultExpanded = [], onExpandedChange, selectedId = null, onSelect, className, ...rest }: TreeProps) {
  const [uncontrolled, setUncontrolled] = useState<readonly string[]>(defaultExpanded);
  const expandedList = controlled ?? uncontrolled;
  const expanded = useMemo(() => new Set(expandedList), [expandedList]);
  const [focusId, setFocusId] = useState<string | null>(null);
  const items = useRef(new Map<string, HTMLLIElement>());
  const typeahead = useRef(createTypeahead());

  const visible = useMemo(() => flattenVisible(nodes, expanded), [nodes, expanded]);
  const tabStop = visible.some((v) => v.node.id === focusId)
    ? focusId
    : visible.some((v) => v.node.id === selectedId)
      ? selectedId
      : (visible[0]?.node.id ?? null);

  const setExpanded = useCallback(
    (next: Set<string>) => {
      const list = Array.from(next);
      if (!controlled) setUncontrolled(list);
      onExpandedChange?.(list);
    },
    [controlled, onExpandedChange],
  );

  const toggle = (id: string, open?: boolean): void => {
    const next = new Set(expanded);
    const want = open ?? !next.has(id);
    if (want) next.add(id);
    else next.delete(id);
    setExpanded(next);
  };

  const moveFocus = (id: string | null | undefined): void => {
    if (!id) return;
    setFocusId(id);
    focusElement(items.current.get(id));
  };

  const activate = (node: TreeNode): void => {
    if (node.children) toggle(node.id);
    else onSelect?.(node);
  };

  const onKeyDown = (e: KeyboardEvent<HTMLUListElement>): void => {
    const idx = visible.findIndex((v) => v.node.id === tabStop);
    const cur = visible[idx];
    if (!cur) return;
    const node = cur.node;
    const isFolder = !!node.children;
    const isOpen = expanded.has(node.id);
    let handled = true;
    switch (e.key) {
      case 'ArrowDown':
        moveFocus(visible[idx + 1]?.node.id);
        break;
      case 'ArrowUp':
        moveFocus(visible[idx - 1]?.node.id);
        break;
      case 'ArrowRight':
        if (isFolder && !isOpen) toggle(node.id, true);
        else if (isFolder && isOpen) moveFocus(node.children?.[0]?.id);
        break;
      case 'ArrowLeft':
        if (isFolder && isOpen) toggle(node.id, false);
        else moveFocus(cur.parentId);
        break;
      case 'Home':
        moveFocus(visible[0]?.node.id);
        break;
      case 'End':
        moveFocus(visible[visible.length - 1]?.node.id);
        break;
      case 'Enter':
      case ' ':
        activate(node);
        break;
      case '*': {
        const siblings = visible.filter((v) => v.parentId === cur.parentId && v.node.children).map((v) => v.node.id);
        const next = new Set(expanded);
        siblings.forEach((id) => next.add(id));
        setExpanded(next);
        break;
      }
      default:
        if (isTypeaheadKey(e)) {
          const i = typeahead.current.next(
            e.key,
            visible.map((v) => v.node.label),
            idx,
          );
          if (i >= 0) moveFocus(visible[i]?.node.id);
          else handled = false;
        } else handled = false;
    }
    if (handled) {
      e.preventDefault();
      e.stopPropagation();
    }
  };

  const render = (list: TreeNode[], level: number): ReactNode =>
    list.map((node, i) => {
      const isFolder = !!node.children;
      const isOpen = isFolder && expanded.has(node.id);
      const selected = node.id === selectedId;
      const icon: IconName = isFolder ? (isOpen ? 'folder_open' : 'folder') : (node.icon ?? 'description');
      return (
        <li
          key={node.id}
          ref={(el) => {
            if (el) items.current.set(node.id, el);
            else items.current.delete(node.id);
          }}
          role="treeitem"
          aria-level={level}
          aria-setsize={list.length}
          aria-posinset={i + 1}
          aria-expanded={isFolder ? isOpen : undefined}
          aria-selected={selected}
          tabIndex={node.id === tabStop ? 0 : -1}
          className={styles.item}
          onFocus={(e) => {
            if (e.target === e.currentTarget) setFocusId(node.id);
          }}
        >
          {/* Mouse target; the treeitem itself handles the keyboard (tree onKeyDown). */}
          {/* eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions */}
          <div
            className={`${styles.row} ${selected ? styles.selected : ''} has-state-layer`}
            style={{ paddingLeft: 8 + (level - 1) * 20 }}
            onClick={() => {
              setFocusId(node.id);
              activate(node);
            }}
          >
            <span className={styles.chevron} aria-hidden="true">
              {isFolder ? <Icon name={isOpen ? 'keyboard_arrow_down' : 'chevron_right'} size={18} /> : null}
            </span>
            <Icon name={icon} size={20} className={styles.icon} />
            <span className={styles.label}>{node.label}</span>
            {node.meta ? <span className={styles.meta}>{node.meta}</span> : null}
          </div>
          {isFolder && isOpen && node.children && node.children.length > 0 ? (
            <ul role="group" className={styles.group}>
              {render(node.children, level + 1)}
            </ul>
          ) : null}
        </li>
      );
    });

  return (
    <ul {...rest} role="tree" className={[styles.tree, className].filter(Boolean).join(' ')} onKeyDown={onKeyDown}>
      {render(nodes, 1)}
    </ul>
  );
}
