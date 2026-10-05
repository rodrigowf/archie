/** Bodies of the file tools (inv02 F-05 `renderToolInput`): Read, Write, Edit, NotebookEdit, Glob, Grep, … */
import { arr, bool, isRecord, languageFromPath, num, str } from '../format';
import { OutputView } from '../OutputView';
import { DiffView } from './DiffView';
import { CodeView, Fields, Section } from './parts';
import type { ToolBodyProps } from './types';

/** "lines a–b", "from line a", "first N lines" (inv02 F-05; `offset` is 0-based like Claude's Read). */
export function readRange(offset: number | undefined, limit: number | undefined): string | undefined {
  if (offset !== undefined && limit !== undefined) return `lines ${offset + 1}–${offset + limit}`;
  if (offset !== undefined) return `from line ${offset + 1}`;
  if (limit !== undefined) return `first ${limit} lines`;
  return undefined;
}

/** Orchestrator read_file: 1-based inclusive start_line/end_line. */
export function lineRange(start: number | undefined, end: number | undefined): string | undefined {
  if (start !== undefined && end !== undefined) return `lines ${start}–${end}`;
  if (start !== undefined) return `from line ${start}`;
  if (end !== undefined) return `first ${end} lines`;
  return undefined;
}

export function ReadBody({ block, input, out }: ToolBodyProps) {
  return (
    <Section>
      <Fields
        rows={[
          ['File', str(input, 'file_path'), true],
          ['Range', readRange(num(input, 'offset'), num(input, 'limit'))],
          ['Pages', str(input, 'pages')],
        ]}
      />
      <OutputView block={block} {...out} />
    </Section>
  );
}

/** The orchestrator's own read_file (`path`, `start_line`, `end_line`): fixes inv02 §6.3 #15. */
export function OrchestratorReadBody({ block, input, out }: ToolBodyProps) {
  return (
    <Section>
      <Fields
        rows={[
          ['File', str(input, 'path'), true],
          ['Range', lineRange(num(input, 'start_line'), num(input, 'end_line'))],
        ]}
      />
      <OutputView block={block} {...out} />
    </Section>
  );
}

function WriteContent({ path, content }: { path: string; content: string | undefined }) {
  return (
    <>
      <Fields rows={[['File', path, true]]} />
      {content !== undefined ? <CodeView code={content} lang={languageFromPath(path)} label="Content" /> : null}
    </>
  );
}

export function WriteBody({ block, input, out }: ToolBodyProps) {
  return (
    <Section>
      <WriteContent path={str(input, 'file_path') ?? ''} content={str(input, 'content')} />
      <OutputView block={block} {...out} />
    </Section>
  );
}

export function OrchestratorWriteBody({ block, input, out }: ToolBodyProps) {
  return (
    <Section>
      <WriteContent path={str(input, 'path') ?? ''} content={str(input, 'content')} />
      <OutputView block={block} {...out} />
    </Section>
  );
}

export function EditBody({ block, input, out }: ToolBodyProps) {
  const oldText = str(input, 'old_string');
  const newText = str(input, 'new_string');
  return (
    <Section>
      <Fields
        rows={[
          ['File', str(input, 'file_path'), true],
          bool(input, 'replace_all') ? ['Mode', 'Replace all occurrences'] : null,
        ]}
      />
      {oldText !== undefined && newText !== undefined ? <DiffView oldText={oldText} newText={newText} /> : null}
      <OutputView block={block} {...out} />
    </Section>
  );
}

/** Claude's MultiEdit: one diff per edit. */
export function MultiEditBody({ block, input, out }: ToolBodyProps) {
  const edits = (arr(input, 'edits') ?? []).filter(isRecord);
  return (
    <Section>
      <Fields rows={[['File', str(input, 'file_path'), true], ['Edits', String(edits.length)]]} />
      {edits.map((e, i) => {
        const o = str(e, 'old_string');
        const n = str(e, 'new_string');
        return o !== undefined && n !== undefined ? <DiffView key={i} oldText={o} newText={n} /> : null;
      })}
      <OutputView block={block} {...out} />
    </Section>
  );
}

export function NotebookEditBody({ block, input, out }: ToolBodyProps) {
  const source = str(input, 'new_source');
  return (
    <Section>
      <Fields
        rows={[
          ['Notebook', str(input, 'notebook_path'), true],
          ['Cell', str(input, 'cell_id'), true],
          ['Mode', str(input, 'edit_mode')],
          ['Type', str(input, 'cell_type')],
        ]}
      />
      {source !== undefined ? <CodeView code={source} lang={str(input, 'cell_type') === 'markdown' ? 'markdown' : 'python'} label="Source" /> : null}
      <OutputView block={block} {...out} />
    </Section>
  );
}

export function GlobBody({ block, input, out }: ToolBodyProps) {
  return (
    <Section>
      <Fields rows={[['Pattern', str(input, 'pattern'), true], ['Path', str(input, 'path'), true]]} />
      <OutputView block={block} {...out} />
    </Section>
  );
}

/** Grep options line (inv02 F-05): output mode unless the default, "line numbers", "±N context". */
export function grepOptions(input: Record<string, unknown>): string | undefined {
  const mode = str(input, 'output_mode');
  const n = bool(input, '-n');
  const ctx = num(input, 'context') ?? num(input, '-C');
  if (mode === undefined && n === undefined && ctx === undefined) return undefined;
  const parts = [mode && mode !== 'files_with_matches' ? mode : null, n ? 'line numbers' : null, ctx !== undefined ? `±${ctx} context` : null];
  return parts.filter(Boolean).join(', ') || 'default';
}

export function GrepBody({ block, input, out }: ToolBodyProps) {
  return (
    <Section>
      <Fields
        rows={[
          ['Pattern', str(input, 'pattern'), true],
          ['Path', str(input, 'path'), true],
          ['Glob', str(input, 'glob'), true],
          ['Type', str(input, 'type')],
          ['Options', grepOptions(input)],
        ]}
      />
      <OutputView block={block} {...out} />
    </Section>
  );
}

export function ListFilesBody({ block, input, out }: ToolBodyProps) {
  const paths = arr(input, 'paths');
  return (
    <Section>
      <Fields
        rows={[
          ['Path', str(input, 'path'), true],
          ['Files', paths ? paths.map(String).join('\n') : undefined, true],
        ]}
      />
      <OutputView block={block} {...out} />
    </Section>
  );
}
