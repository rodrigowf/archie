/** Bodies of the execute / script tools: Bash, run_script, evaluate_script, Skill, plan mode. */
import { Markdown } from '@/features/markdown';
import { arr, isRecord, parseExitCode, str, type ToolInput } from '../format';
import { OutputView } from '../OutputView';
import styles from '../ToolCard.module.css';
import { CodeView, Fields, Section } from './parts';
import type { ToolBodyProps } from './types';

/** "exit 0" / "exit 2" under a shell output; nothing while running or without a result. */
export function exitLabel(status: string, output: string | null): string | null {
  if (status !== 'done' && status !== 'error') return null;
  const code = output ? parseExitCode(output) : null;
  if (code !== null) return `exit ${code}`;
  return status === 'done' ? 'exit 0' : null;
}

/**
 * Bash (inv02 F-05 BashBlock): description, the full command (highlighted), then the output with
 * its exit code. Expanded before the result it shows "Running…" (fixes inv02 §6.2: it showed
 * nothing).
 */
export function BashBody({ block, input, out }: ToolBodyProps) {
  const command = str(input, 'command') ?? '';
  const exit = exitLabel(block.status, block.output);
  return (
    <Section>
      <Fields
        rows={[
          ['Description', str(input, 'description')],
          ['Directory', str(input, 'directory'), true],
          input.run_in_background === true || input.is_background === true ? ['Mode', 'Background'] : null,
        ]}
      />
      {command ? <CodeView code={command} lang="bash" label="Command" /> : null}
      <OutputView block={block} {...out} footer={exit ? <span className={block.status === 'error' ? styles.errText : styles.okText}>{exit}</span> : undefined} />
    </Section>
  );
}

/** Bash output follow-ups (BashOutput / KillShell): the shell id and the output. */
export function ShellIdBody({ block, input, out }: ToolBodyProps) {
  return (
    <Section>
      <Fields rows={[['Shell', str(input, 'bash_id') ?? str(input, 'shell_id'), true], ['Filter', str(input, 'filter'), true]]} />
      <OutputView block={block} {...out} />
    </Section>
  );
}

interface ScriptResult {
  readonly exitCode: number | null;
  readonly stdout: string;
  readonly stderr: string;
}

/** run_script returns JSON `{exit_code, stdout, stderr}` (orchestrator/tools/run_script.py). */
export function parseScriptResult(output: string): ScriptResult | null {
  const t = output.trim();
  if (!t.startsWith('{')) return null;
  try {
    const v: unknown = JSON.parse(t);
    if (!isRecord(v) || !('stdout' in v || 'exit_code' in v || 'stderr' in v)) return null;
    return {
      exitCode: typeof v.exit_code === 'number' ? v.exit_code : null,
      stdout: typeof v.stdout === 'string' ? v.stdout : '',
      stderr: typeof v.stderr === 'string' ? v.stderr : '',
    };
  } catch {
    return null;
  }
}

function renderScriptOutput(text: string, isError: boolean) {
  const r = parseScriptResult(text);
  if (!r) return <pre className={isError ? `${styles.pre} ${styles.errText}` : styles.pre}>{text}</pre>;
  return (
    <>
      {r.stdout ? <pre className={styles.pre}>{r.stdout.replace(/\n$/, '')}</pre> : null}
      {r.stderr ? <pre className={`${styles.pre} ${styles.errText}`}>{r.stderr.replace(/\n$/, '')}</pre> : null}
      {!r.stdout && !r.stderr ? <span className={styles.dim}>No output</span> : null}
      {r.exitCode !== null ? <span className={r.exitCode === 0 ? styles.okText : styles.errText}>{`exit ${r.exitCode}`}</span> : null}
    </>
  );
}

export function scriptArgs(input: ToolInput): string {
  return (arr(input, 'args') ?? []).map(String).join(' ');
}

export function RunScriptBody({ block, input, out }: ToolBodyProps) {
  return (
    <Section>
      <Fields rows={[['Script', str(input, 'script'), true], ['Args', scriptArgs(input), true]]} />
      <OutputView block={block} {...out} renderOutput={renderScriptOutput} />
    </Section>
  );
}

/** chrome-devtools evaluate_script: the function (JS, highlighted) and the element uids. */
export function EvaluateScriptBody({ block, input, out }: ToolBodyProps) {
  const fn = str(input, 'function');
  const args = (arr(input, 'args') ?? []).filter(isRecord).map((a) => String(a.uid ?? '')).filter(Boolean);
  return (
    <Section>
      {fn ? <CodeView code={fn} lang="javascript" label="Script" /> : null}
      <Fields rows={[['Args', args.join(', '), true]]} />
      <OutputView block={block} {...out} />
    </Section>
  );
}

export function SkillBody({ block, input, out }: ToolBodyProps) {
  return (
    <Section>
      <Fields rows={[['Skill', str(input, 'skill') ?? str(input, 'command'), true], ['Args', str(input, 'args'), true]]} />
      <OutputView block={block} {...out} />
    </Section>
  );
}

/** ExitPlanMode: the plan, rendered as Markdown. */
export function PlanBody({ block, input, out }: ToolBodyProps) {
  const plan = str(input, 'plan');
  return (
    <Section>
      {plan ? (
        <div className={styles.rich}>
          <Markdown source={plan} />
        </div>
      ) : null}
      <OutputView block={block} {...out} />
    </Section>
  );
}
