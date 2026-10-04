/**
 * MCP servers (inv02 F-34). `enabled_mcps: []` means **all enabled** (CFG-4): every switch shows
 * on; switching one off writes the explicit list of the others; switching all on writes `[]`.
 * The old page showed every box unchecked for `[]` (inv02 §6.2). Shared with Session settings.
 */
import type { McpServers } from '@/services';
import { useServerConfig } from '@/stores';
import { Button, EmptyState, Switch } from '@/ui/controls';
import { Icon } from '@/ui/primitives';
import { saveSetting } from '../controller';
import { isAllEnabled, isMcpEnabled, mcpCommandLine, mcpOffBlockedReason, toggleMcp } from '../logic';
import { Notice, useFieldId } from '../parts';
import { useSaving, WithConfig } from './shared';
import styles from '../settings.module.css';

export function mcpNames(servers: McpServers | null): string[] {
  return servers ? Object.keys(servers.servers ?? {}) : [];
}

export interface McpServerListProps {
  servers: McpServers;
  /** The effective list (`[]` = all). */
  enabled: readonly string[];
  disabled?: boolean;
  onChange: (next: string[]) => void;
}

function McpRow({
  name,
  command,
  on,
  disabled,
  blocked,
  onToggle,
}: {
  name: string;
  command: string;
  on: boolean;
  disabled: boolean;
  blocked: string | null;
  onToggle: (on: boolean) => void;
}) {
  const id = useFieldId('mcp');
  return (
    <div className={styles.listRow}>
      <div className={styles.listRowMain}>
        <Icon name="hub" size={20} />
        <div className={styles.listRowText}>
          <span id={id} className={styles.listRowTitle}>
            {name}
          </span>
          <span className={styles.listRowSub} title={command}>
            {blocked && on ? blocked : command}
          </span>
        </div>
      </div>
      <div className={styles.listRowActions}>
        <Switch
          aria-labelledby={id}
          checked={on}
          disabled={disabled || (on && !!blocked)}
          onCheckedChange={onToggle}
        />
      </div>
    </div>
  );
}

/** One switch per server, with the "all enabled" semantics shown correctly. */
export function McpServerList({ servers, enabled, disabled = false, onChange }: McpServerListProps) {
  const names = mcpNames(servers);
  const all = isAllEnabled(enabled);
  const stale = enabled.filter((n) => names.indexOf(n) < 0);
  return (
    <>
      <p className={styles.help} role="status">
        {all
          ? 'All servers are on, including any added to .claude.json later.'
          : 'Only the servers switched on are used; servers added later start off.'}
      </p>
      <div className={`${styles.fieldStack} ${styles.listGap}`}>
        {names.map((n) => (
          <McpRow
            key={n}
            name={n}
            command={mcpCommandLine(servers.servers[n])}
            on={isMcpEnabled(enabled, n)}
            disabled={disabled}
            blocked={mcpOffBlockedReason(enabled, names, n)}
            onToggle={(on) => {
              onChange(toggleMcp(enabled, names, n, on));
            }}
          />
        ))}
      </div>
      {!all ? (
        <div className={styles.actionsRow}>
          <Button variant="text" icon="check" disabled={disabled} onClick={() => onChange([])}>
            Turn all on
          </Button>
        </div>
      ) : null}
      {stale.length ? (
        <p className={styles.help}>Also saved, but not in .claude.json: {stale.join(', ')}.</p>
      ) : null}
    </>
  );
}

export function McpServersPage() {
  return (
    <WithConfig>
      {(cfg) => <McpServersForm enabled={cfg.enabled_mcps ?? []} />}
    </WithConfig>
  );
}

function McpServersForm({ enabled }: { enabled: readonly string[] }) {
  const servers = useServerConfig((s) => s.mcpServers);
  const saving = useSaving();
  if (!servers) return <Notice title="Loading MCP servers…" />;
  if (mcpNames(servers).length === 0)
    return (
      <EmptyState icon="hub" title="No MCP servers" description={`None configured in .claude.json${servers.project_dir ? ` for ${servers.project_dir}` : ''}.`} />
    );
  return (
    <div className={styles.stackBlock}>
      <h3 className={styles.stackLabel}>Servers for new agent sessions</h3>
      <McpServerList
        servers={servers}
        enabled={enabled}
        disabled={saving}
        onChange={(next) => {
          void saveSetting({ enabled_mcps: next }, 'enabled_mcps');
        }}
      />
    </div>
  );
}
