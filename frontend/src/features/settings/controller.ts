/**
 * Settings data flow (W-13, spec 12 §8.1). Config is not broadcast (G-27), so every Settings
 * open refetches config and catalogs (CFG-3). Each control saves on its own with a partial PUT
 * whose answer replaces the local copy (CFG-1, CFG-5); `saveServerConfig` shows "Saved" or the
 * backend `detail` verbatim with Retry (CFG-2). After every load or voice save the Google
 * auto-correct runs (CFG-6, F-31 **[LOAD-BEARING]**).
 */
import { useEffect } from 'react';
import { useStore } from 'zustand';
import { createStore } from 'zustand/vanilla';
import {
  errorMessage,
  loadConfigCatalogs,
  loadGoogleVoiceModels,
  loadServerConfig,
  saveServerConfig,
  type ServerConfig,
  type ServerConfigUpdate,
} from '@/services';
import { serverConfigStore, showSnackbar } from '@/stores';
import { googleAutoCorrect, type AutoCorrect } from './logic';

export interface SettingsUiState {
  /** The dismissible notice after an auto-correct ("…is no longer available… Switched to …"). */
  readonly autoCorrected: AutoCorrect | null;
  /** Saved model ids already corrected this session (the PUT happens once per stale id). */
  readonly correctedFrom: readonly string[];
}

export const settingsUiStore = createStore<SettingsUiState>(() => ({ autoCorrected: null, correctedFrom: [] }));

export function useSettingsUi<T>(selector: (s: SettingsUiState) => T): T {
  return useStore(settingsUiStore, selector);
}

export function dismissAutoCorrect(): void {
  settingsUiStore.setState({ autoCorrected: null });
}

export function resetSettingsUi(): void {
  settingsUiStore.setState({ autoCorrected: null, correctedFrom: [] }, true);
}

function googleList(cfg: ServerConfig) {
  return serverConfigStore.getState().googleVoiceModels[cfg.default_voice_endpoint];
}

/** CFG-6: PUT the discovered default once and show the notice. Resolves to the correction made, if any. */
export async function maybeAutoCorrectVoice(): Promise<AutoCorrect | null> {
  const cfg = serverConfigStore.getState().config;
  if (!cfg) return null;
  const fix = googleAutoCorrect(cfg, googleList(cfg));
  if (!fix || settingsUiStore.getState().correctedFrom.indexOf(fix.from) >= 0) return null;
  settingsUiStore.setState((s) => ({ correctedFrom: s.correctedFrom.concat([fix.from]) }));
  try {
    await saveServerConfig(fix.patch, { key: 'voice', snackbar: false });
    settingsUiStore.setState({ autoCorrected: fix });
    return fix;
  } catch (err) {
    showSnackbar(`Couldn't switch the Gemini Live model: ${errorMessage(err)}`, { tone: 'error' });
    return null;
  }
}

/** Load the Google catalog for the saved endpoint (the dynamic list replaces the static one). */
async function loadGoogleFor(cfg: ServerConfig | null): Promise<void> {
  if (cfg?.default_voice_endpoint) await loadGoogleVoiceModels(cfg.default_voice_endpoint);
}

/** CFG-3: refetch everything a settings surface shows. Never rejects. */
export async function refreshSettings(): Promise<void> {
  await Promise.all([loadServerConfig(), loadConfigCatalogs()]);
  await loadGoogleFor(serverConfigStore.getState().config);
  await maybeAutoCorrectVoice();
}

/** Refetch on mount (each Settings open). */
export function useSettingsRefresh(enabled = true): void {
  useEffect(() => {
    if (enabled) void refreshSettings();
  }, [enabled]);
}

/**
 * Save one control (CFG-1). Resolves to the new config, or null on failure (the snackbar already
 * shows the server message + Retry). Voice changes re-run the Google catalog + auto-correct.
 */
export async function saveSetting(patch: ServerConfigUpdate, key: string): Promise<ServerConfig | null> {
  try {
    const cfg = await saveServerConfig(patch, { key });
    if ('default_voice_endpoint' in patch || 'default_voice_provider' in patch) await loadGoogleFor(cfg);
    if (key === 'voice') await maybeAutoCorrectVoice();
    return serverConfigStore.getState().config ?? cfg;
  } catch {
    return null;
  }
}
