/**
 * Device-local pages (spec 12 §8.2: never sent to the backend): Appearance and About (with the
 * remote-console flag, F-38 **[LOAD-BEARING]** for devices without devtools).
 */
import { checkAuth } from '@/features/auth';
import { getEnv } from '@/services';
import { useConnection, usePrefs, type TextSizePref, type ThemePref } from '@/stores';
import { Button, Disclosure, SegmentedButton, Switch, type SegmentOption } from '@/ui/controls';
import { refreshSettings } from '../controller';
import { Field, FieldStack, useFieldId } from '../parts';
import { setDevicePref } from './shared';
import styles from '../settings.module.css';

export const THEME_OPTIONS: readonly SegmentOption<ThemePref>[] = [
  { value: 'system', label: 'System', icon: 'brightness_auto' },
  { value: 'dark', label: 'Dark', icon: 'dark_mode' },
  { value: 'light', label: 'Light', icon: 'light_mode' },
];

/** No "small": text below 16 px in fields makes iOS Safari zoom on focus (spec 13 §2.6). */
export const TEXT_SIZE_OPTIONS: readonly SegmentOption<TextSizePref>[] = [
  { value: 'default', label: 'Default' },
  { value: 'large', label: 'Large' },
  { value: 'xlarge', label: 'Larger' },
];

export const THEME_LABELS: Record<ThemePref, string> = { system: 'System theme', dark: 'Dark', light: 'Light' };
export const TEXT_SIZE_LABELS: Record<TextSizePref, string> = {
  small: 'small text',
  default: 'default text size',
  large: 'large text',
  xlarge: 'larger text',
};

export function AppearancePage() {
  const theme = usePrefs((p) => p.theme);
  const textSize = usePrefs((p) => p.textSize);
  const reduceMotion = usePrefs((p) => p.reduceMotion);
  const syntax = usePrefs((p) => p.syntaxHighlighting);
  const grouping = usePrefs((p) => p.toolStepGrouping);
  const themeId = useFieldId('theme');
  const sizeId = useFieldId('size');
  const motionId = useFieldId('motion');
  const syntaxId = useFieldId('syntax');
  const groupId = useFieldId('group');
  return (
    <FieldStack>
      <Field label="Theme" labelId={themeId} help="Dark is the default. System follows this device.">
        <SegmentedButton
          aria-labelledby={themeId}
          options={THEME_OPTIONS}
          value={theme}
          fullWidth
          onChange={(v) => {
            setDevicePref('theme', v);
          }}
        />
      </Field>
      <Field label="Text size" labelId={sizeId} help="Scales all text in the app.">
        <SegmentedButton
          aria-labelledby={sizeId}
          options={TEXT_SIZE_OPTIONS}
          value={textSize === 'small' ? 'default' : textSize}
          fullWidth
          onChange={(v) => {
            setDevicePref('textSize', v);
          }}
        />
      </Field>
      <Field
        label="Reduce motion"
        labelId={motionId}
        help="Turns off animations and blur."
        info="Slow devices (2 cores or less, 1 GB of memory or less) get this automatically."
        trailing={<Switch aria-labelledby={motionId} checked={reduceMotion} onCheckedChange={(v) => setDevicePref('reduceMotion', v)} />}
      />
      <Field
        label="Syntax highlighting"
        labelId={syntaxId}
        help="Colours code blocks in replies."
        trailing={<Switch aria-labelledby={syntaxId} checked={syntax} onCheckedChange={(v) => setDevicePref('syntaxHighlighting', v)} />}
      />
      <Field
        label="Group tool steps"
        labelId={groupId}
        help={'Shows runs of tool calls as one "N steps" line.'}
        trailing={<Switch aria-labelledby={groupId} checked={grouping} onCheckedChange={(v) => setDevicePref('toolStepGrouping', v)} />}
      />
    </FieldStack>
  );
}

export const LICENSES: readonly { name: string; license: string }[] = [
  { name: 'React, React DOM 18.3', license: 'MIT' },
  { name: 'Zustand 5', license: 'MIT' },
  { name: 'react-markdown, remark-gfm, hast-util-to-jsx-runtime, mdast-util-gfm-autolink-literal', license: 'MIT' },
  { name: 'highlight.js 11', license: 'BSD-3-Clause' },
  { name: 'lowlight 3', license: 'MIT' },
  { name: 'jsdiff (diff) 9', license: 'BSD-3-Clause' },
  { name: 'Floating UI', license: 'MIT' },
  { name: 'tabbable', license: 'MIT' },
  { name: 'focus-visible polyfill', license: 'W3C' },
  { name: '@juggle/resize-observer', license: 'Apache-2.0' },
  { name: 'Roboto Flex, JetBrains Mono (Fontsource)', license: 'OFL-1.1' },
  { name: 'Material Symbols', license: 'Apache-2.0' },
];

function backendLabel(): string {
  try {
    const base = getEnv().baseUrl;
    if (base) return new URL(base).host;
  } catch {
    // fall through
  }
  return typeof location !== 'undefined' ? location.host : '';
}

export function AboutPage() {
  const remote = usePrefs((p) => p.remoteLogging);
  const backend = useConnection((s) => s.backend);
  const remoteId = useFieldId('remote');
  const build = __TARGET__ === 'compat' ? 'compat (Safari 12)' : 'main';
  let built = __BUILD_TIME__;
  try {
    built = new Date(__BUILD_TIME__).toLocaleString();
  } catch {
    // keep the raw string
  }
  return (
    <>
      <FieldStack>
        <Field label="Archie">
          <dl className={styles.kv}>
            <div>
              <dt>App version</dt>
              <dd>{__APP_VERSION__}</dd>
            </div>
            <div>
              <dt>Build</dt>
              <dd>{build}</dd>
            </div>
            <div>
              <dt>Built</dt>
              <dd>{built}</dd>
            </div>
            <div>
              <dt>Backend</dt>
              <dd>
                {backendLabel()} · {backend === 'online' ? 'online' : backend === 'offline' ? 'offline' : 'checking'}
              </dd>
            </div>
            <div>
              <dt>Backend version</dt>
              <dd>Not reported by the server</dd>
            </div>
          </dl>
          <div className={styles.actionsRow}>
            <Button
              variant="text"
              icon="refresh"
              onClick={() => {
                void refreshSettings();
                void checkAuth();
              }}
            >
              Check the server again
            </Button>
          </div>
        </Field>
      </FieldStack>
      <FieldStack label="This device">
        <Field
          label="Remote logging"
          labelId={remoteId}
          help="Sends this browser's console to the server log."
          info={
            <>
              <p>
                For devices without developer tools (iPad, old phones). Lines go to <code>remote_console.log</code> on the server
                (<code>GET /api/debug/log</code>).
              </p>
              <p>On by default in the compat build. Rate-limited; applies at once.</p>
            </>
          }
          trailing={<Switch aria-labelledby={remoteId} checked={remote} onCheckedChange={(v) => setDevicePref('remoteLogging', v)} />}
        />
      </FieldStack>
      <FieldStack>
        <div className={styles.field}>
          <Disclosure summary="Open-source licenses" icon="description">
            <ul className={styles.licenses}>
              {LICENSES.map((l) => (
                <li key={l.name}>
                  <b>{l.name}</b> · {l.license}
                </li>
              ))}
            </ul>
          </Disclosure>
        </div>
      </FieldStack>
    </>
  );
}
