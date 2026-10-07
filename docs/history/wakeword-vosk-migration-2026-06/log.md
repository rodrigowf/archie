---
name: wakeword_vosk_migration_log_2026_06_09
category: archie/history
tags: [wake-word, refactor, android, vosk, on-device-stt, execution-log, increments, parity-tests, tdd, field-validation]
created: 2026-06-10
modified: 2026-10-05
summary: Execution log for the wake-word Vosk migration (companion to wakeword_vosk_migration_plan_2026_06_09.md). One entry per shipped increment citing the commit, source-fidelity HEAD sha, on-device verification, deviations from the plan, and implications for downstream increments.
source: curated (execution session for the Vosk migration plan, 2026-06-10)
references:
  - plan.md
  - ../../overview/repo-layout.md
---
# Wake-word Vosk migration — execution log

> **Repo layout note (2026-10-05):** this is a dated record of work on the **old** Android app; its `android/app/...` paths now live under `legacy/android/app/...`. The current Android code is the multi-module `apps/android/` project (Vosk/Whisper wake word in `apps/android/core/wakeword`, tuned constants ported verbatim). See [repo_layout_cutover_2026_10.md](../../overview/repo-layout.md).

Companion to [`wakeword_vosk_migration_plan_2026_06_09.md`](plan.md). The plan is forward-looking design; this log is backward-looking record: what shipped, against what HEAD, what the device showed, what we learned, what changed for downstream increments.

> **⚠️ This log stops before the 2026-07-21 two-layer rework.** After the Vosk increments here, the detector gained an **OpenAI Whisper confirmation gate** (called direct from Android) + a reworked same-mic talk-command path. That work shipped on branch `local` (commits `70283e3 ae1d958 2f5ecd7 072acf1 254c818 b710c8b 3bee23a 31c2fbf 3efbe55 f934d09`), field-validated on A300M + Xiaomi. Canonical record: [wakeword_subsystem.md](../../voice/wake-word.md) + the per-Claude `project_wakeword_turnbased_unified_loop_2026_07_21.md`.

One entry per shipped commit. Each entry self-contained — a future reader can read just one entry and understand it.

## Status board

| # | Title | Commit | Status | Notes |
|---|---|---|---|---|
| V1 | Vosk dependency + bundled model + ABI filters | `482a311` | ✅ shipped | dependency `com.alphacephei:vosk-android:0.3.47`, model `vosk-model-small-en-us-0.15` (~68 MB extracted), ABI filters `armeabi-v7a + arm64-v8a` only, `noCompress` for the model directory. |
| V2 | `VoskModelLoader` singleton + Lollipop polyfills | `ddfb53f` | ✅ shipped | extract from assets, cache `Model`, mutex-guarded; ABI-specific patched `libvosk.so` weakening `stderr/stdin/stdout` so BIND_NOW dlopen doesn't fail on API < 23; runtime `libvosk-stderr-shim.so` providing actual `__sF`-backed pointers so any fprintf doesn't SEGV. Polyfills no-op on API ≥ 23. |
| V3 | `VoskWakeWordEngine` replacing `SpeechRecognizer` | — | ⏳ pending | unified AudioRecord stream → RMS + `acceptWaveform`. FSM `Listening` replaces `SilenceMonitor + Recognizing`. |
| V4 | V4a (keep RMS gate) vs V4b (continuous) | — | ⏳ pending | Default V4a; promote to V4b only if residual misses persist. |
| V5 | Repurpose Inc 8 health check for Vosk signal | — | ⏳ pending | New signal: no Vosk output despite non-silent audio for 120 s. |
| V6 | Cleanup + final 60-min on-device soak | — | ⏳ pending | Drop V3 fallback path; remove Detour 6 dead constants; soak. |

## Per-commit entries

### V1 — Vosk dependency + bundled model + ABI filters

- **Commit**: `482a311` `android(wakeword): bundle Vosk dependency + small EN model — Vosk V1`
- **Plan citation**: §4 Increment V1; §5.1 (bundled), §5.6 (ABI filters)
- **Source-fidelity HEAD sha**: `b753ac5` (`Detour 6 — warm SpeechRecognizer + lifecycle safeguards`).

**Change**:
- `android/app/build.gradle.kts`:
  - `defaultConfig.ndk { abiFilters += setOf("armeabi-v7a", "arm64-v8a") }`
  - `androidResources { noCompress += "vosk-model-small-en-us-0.15" }` (critical — Vosk reads model files directly; AAPT compression would break extraction).
  - `dependencies { implementation("com.alphacephei:vosk-android:0.3.47") }`.
- `android/app/proguard-rules.pro`: `-keep class org.vosk.** { *; }` and `-keep class org.kaldi.** { *; }`.
- `android/app/src/main/assets/vosk-model-small-en-us-0.15/`: bundled the extracted model (40 MB zip → 68 MB on disk).
  - Downloaded from `https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip`.
  - Structure verified: `am/`, `conf/`, `graph/`, `ivector/`, `README` — matches plan §2.2.

**Latest Vosk version verification**: queried Maven Central solr — `com.alphacephei:vosk-android` latest `0.3.47` (matches plan default, no version bump needed).

**No code logic changes** — pure dependency + asset additions.

**TDD outcome**: No parity test added (no code logic changes per plan §4 V1). Existing 45/45 parity tests still green after the build configuration change (`./gradlew :app:testDebugUnitTest --tests "com.assistant.peripheral.voice.parity.*"` UP-TO-DATE, then explicit rerun on a sibling change confirmed clean).

**Build verification**:
- `./gradlew assembleDebug` succeeded (3m 49s on cold cache).
- APK size: 74 MB (up from ~28 MB pre-Vosk — `74196342` bytes for `app-debug.apk`).
- APK contents inspected via `unzip -l`:
  - `lib/armeabi-v7a/libvosk.so` 8.3 MB present.
  - `lib/arm64-v8a/libvosk.so` 8.9 MB present.
  - `lib/armeabi-v7a/libjnidispatch.so` 122 KB (JNA dependency — used by Vosk for native dispatch).
  - `lib/arm64-v8a/libjnidispatch.so` 168 KB.
  - No `x86`/`x86_64` natives — ABI filters worked.
  - All `assets/vosk-model-small-en-us-0.15/*` model files present uncompressed.

**On-device verification** (A300M, serial `06e4f224`, Android 5.0.2):
- Install: `Performing Push Install ... Success` at 7.1 MB/s.
- Launch: `am start -n com.assistant.peripheral/.MainActivity` → `Displayed com.assistant.peripheral/.MainActivity: +2s119ms`.
- Process started successfully: `Start proc com.assistant.peripheral for activity ... pid=8616 uid=10134 ... abi=armeabi-v7a` — confirms the right ABI loaded.
- No `FATAL` or `AndroidRuntime` lines.
- No `UnsatisfiedLinkError`, no `dlopen` failure for `libvosk.so` (we haven't loaded Vosk yet — that's V2 — but the natives are packaged correctly and the install/launch is clean).
- Pre-existing `Rejecting re-init on previously-failed class ... VoiceManager$registerDeviceCallback$cb$1` warnings unchanged — unrelated to this commit.

**Tuned behaviors preserved**: N/A (no functional changes).

**Strip warning**: `Unable to strip the following libraries, packaging them as they are: libjnidispatch.so, libvosk.so` — benign; the local NDK strip toolchain isn't available in this build environment, so the libs ship un-stripped. They still load; only side effect is a slightly larger APK (libvosk.so contains debug symbols). Not worth fighting; can be addressed later by installing the NDK strip tools or configuring `packagingOptions.jniLibs.useLegacyPackaging` if APK size becomes a concern.

**Implication for downstream increments**: 
- V2 can now reference `org.vosk.Model` and `org.vosk.Recognizer` from Kotlin — the JNI library is in place. 
- The model assets path inside the APK is `assets/vosk-model-small-en-us-0.15/` — V2's extraction code will read from there via `context.assets.open(...)` (or recursively walk via `context.assets.list(...)`).
- The `noCompress` decision was critical and not in the original plan — without it, AAPT would have gzipped the model files, breaking Vosk's `Model(path)` constructor which expects raw files on disk. Worth noting for future cargo-cult readers.

**Open questions / deferred**: None for V1. V2 is unblocked.

---

### V2 — `VoskModelLoader` singleton + Lollipop polyfills

- **Commit**: `ddfb53f` `android(wakeword): VoskModelLoader + Lollipop polyfill — Vosk V2`
- **Plan citation**: §4 V2; §5.5 (eager load).
- **Source-fidelity HEAD sha**: `482a311` (V1).

**Change**:
- `android/app/src/main/java/com/assistant/peripheral/voice/VoskModelLoader.kt` (new): object singleton. Pure helpers (`shouldExtract`, `extractTree`, `assetFileList`) for unit testing; suspend `getModel(context)` with mutex-guarded init. Caches `Model` for the process lifetime. Returns null on failure (sticky — no retry within the process).
- `android/app/src/main/cpp/vosk_stderr_shim.c` (new): tiny C library — exports `stderr/stdin/stdout` as global pointers into the legacy Bionic `__sF[]` array (still present on API 21–22 libc); provides two JNI methods (`publishStderrShimGlobally`, `preloadVoskGlobally`) used to dlopen with `RTLD_GLOBAL`.
- `android/app/src/main/cpp/CMakeLists.txt` (new): builds the shim. Linked against `liblog` + `libdl`.
- `android/app/build.gradle.kts`: `externalNativeBuild { cmake { ... } }`, `ndkVersion = "26.1.10909125"` (NDK 26 was the first version to re-add Bionic compat stubs and links cleanly against the API 21 sysroot's `__sF`).
- `android/app/src/main/jniLibs/{armeabi-v7a,arm64-v8a}/libvosk.so` (new): the AAR's prebuilt `libvosk.so` with `stderr/stdin/stdout` symbols rewritten from `STB_GLOBAL` to `STB_WEAK` in `.dynsym`. Gradle's packaging prefers `jniLibs/` over the AAR's bundled libs, so this is a transparent override.
- `android/app/scripts/patch_vosk_weaken.py` (new): the Python script that produced the patched libs from the original AAR. Reproducible — re-run on any Vosk version bump.
- `android/app/src/main/java/com/assistant/peripheral/service/AssistantService.kt`: import + service-scoped `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, fire `VoskModelLoader.getModel(applicationContext)` in `onCreate`, cancel scope in `onDestroy`.

**Why the Lollipop polyfill exists** (the V2 blocker):

Vosk's prebuilt `libvosk.so` is `BIND_NOW` and references `stderr/stdin/stdout` as `STB_GLOBAL UND` symbols. Pre-M (API < 23) Bionic does not export these as symbols — they were `#define stderr (&__sF[2])` macros. So dlopen fails at load time with `cannot locate symbol "stderr" referenced by "libvosk.so"`. Userspace tricks (RTLD_GLOBAL bootstrap, preloading the shim) do NOT help because pre-N Bionic's strict per-soinfo symbol scope doesn't honor the global namespace for cross-lib resolution.

The fix that actually works:

1. **Binary-patch libvosk.so** (`patch_vosk_weaken.py`): rewrite `stderr/stdin/stdout` `.dynsym` entries from `STB_GLOBAL` to `STB_WEAK`. With `BIND_NOW`, weak undefineds are tolerated — they get NULL at load time instead of failing. dlopen succeeds.
2. **C shim** (`vosk_stderr_shim.c`): exports actual `stderr/stdin/stdout` pointers backed by `__sF[]`. When Vosk's code paths call `fprintf(stderr, ...)`, they get a valid FILE* instead of NULL. Without this, any logging path inside Vosk would SEGV.
3. **JNI bootstrap with `RTLD_GLOBAL`**: the shim re-dlopens itself + libvosk.so via JNI with `RTLD_GLOBAL` so the symbols are in the global namespace. (After (1) this is technically redundant for load — but (2) needs the symbols to be findable when JNA's later `System.loadLibrary("vosk")` re-resolves Vosk's references.)

All three steps are gated by `Build.VERSION.SDK_INT < 23`. On M+ devices the polyfill is never loaded, never costs anything — true polyfill.

**TDD outcome**: 8 new parity tests in `VoskModelLoaderParityTest` (RED on the missing `VoskModelLoader` class, GREEN after V2 implementation). Pure-Kotlin coverage of the extraction contract (`shouldExtract`, `extractTree`, `assetFileList`). 45 + 8 = **53/53 parity tests green**.

**Tuned behaviors preserved**: N/A — V2 adds a new code path; doesn't touch the existing wake-word logic.

**Build verification**:
- `./gradlew assembleDebug` succeeds; APK 77 MB (V1 was 74 MB; +3 MB from added native libs + tiny shim).
- APK contents include both ABI variants of `libvosk.so` (the patched override, not the AAR's original) and `libvosk-stderr-shim.so`.

**On-device verification** (A300M, Android 5.0.2):
```
06-10 02:34:03  AssistantService: Service created
06-10 02:34:03  VoskModelLoader: Vosk model already extracted at /data/data/com.assistant.peripheral/files/vosk-model
06-10 02:34:03  WakeWordDetector: Silence monitor started (threshold=70.0, gain=1.3, effective=53)
06-10 02:34:03  VoskModelLoader: Stderr shim loaded (SDK_INT=21, publishGlobal rc=0, preloadVosk[/data/app/com.assistant.peripheral-2/lib/arm/libvosk.so] rc=0)
06-10 02:34:04  WakeWordDetector: Recognizer warmed and ready
06-10 02:34:07  VoskModelLoader: Vosk model loaded in 3753 ms (total 3805 ms since start)
```
- `Vosk model loaded` line never appeared before this commit on this device. Mission accomplished.
- 3.75s model load on A300M (Cortex-A53 @ 1.2 GHz) — within plan §2.5's estimate.
- Eager load completes during service startup; SpeechRecognizer + wake-word pipeline (still V1's behavior) runs in parallel and is unaffected.
- No FATAL, no SIGSEGV in 15s post-load soak.

**Why the patched libs go in `jniLibs/` not `cpp/CMakeLists.txt`**: gradle's variant packaging merges all `jniLibs/<abi>/*.so` with AAR-bundled libs from `dependencies`, and `jniLibs` wins on filename collision. So `jniLibs/armeabi-v7a/libvosk.so` transparently replaces the AAR's `jni/armeabi-v7a/libvosk.so` in the final APK without modifying the AAR file.

**Reproducibility** (re-running the patch from scratch):
```bash
cp ~/.gradle/caches/modules-2/files-2.1/com.alphacephei/vosk-android/0.3.47/*/vosk-android-0.3.47.aar /tmp/vosk.aar
cd /tmp && unzip -p vosk.aar jni/armeabi-v7a/libvosk.so > /tmp/libvosk-armv7.so
unzip -p vosk.aar jni/arm64-v8a/libvosk.so > /tmp/libvosk-arm64.so
python3 /home/rodrigo/assistant/android/app/scripts/patch_vosk_weaken.py /tmp/libvosk-armv7.so /tmp/libvosk-arm64.so
cp /tmp/libvosk-armv7.so /home/rodrigo/assistant/android/app/src/main/jniLibs/armeabi-v7a/libvosk.so
cp /tmp/libvosk-arm64.so /home/rodrigo/assistant/android/app/src/main/jniLibs/arm64-v8a/libvosk.so
```

**Implications for downstream increments**:
- V3 can call `VoskModelLoader.getModel(context)` from any coroutine and either get a cached `Model` (fast path: eager-load completed) or suspend until the load finishes (slow path: arm raced with load). Either way the API is async.
- V3 must check for `null` (load failed) and fall back to SpeechRecognizer per plan §4 V3 escape-hatch.
- The polyfill is sticky: once `loadFailed`, no retry within the process. V5 health check can request a process restart if needed.

**Open questions / deferred**: 
- ndk-version is now pinned to `26.1.10909125` — affects ALL native builds in the module. If we add other native code later that needs a different NDK, revisit.
- The patch script is reproducible but assumes Vosk's AAR layout (`jni/<abi>/libvosk.so`). Upstream changes could break this; bumping `vosk-android` requires re-running the patch.
