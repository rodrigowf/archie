# Version verification (E-3) — A-01, 2026-10-03

Every toolchain and library version named in spec 14 (§1.2, §1.4) was checked against the published
artifacts before use: Google Maven (`dl.google.com/dl/android/maven2`), Maven Central
(`repo1.maven.org/maven2`), the Gradle plugin portal, and `services.gradle.org`. The check read each
artifact's `maven-metadata.xml` (and the POM for BOM contents).

## Result: no substitutions

All versions named in spec 14 exist and are used as written.

| Item | Spec 14 | Published? | Used | Notes |
|---|---|---|---|---|
| Gradle | 9.8.0 | yes (current) | 9.8.0 | wrapper pins `distributionSha256Sum=bafd5ce9…8e6c` |
| AGP | 9.4.1 | yes (latest stable) | 9.4.1 | declares KGP 2.2.10 as a runtime dep; the root `plugins {}` block puts 2.4.20 on the classpath (`buildEnvironment`: `2.2.10 -> 2.4.20`) |
| Kotlin (jvm, serialization, compose plugins) | 2.4.20 | yes (latest stable) | 2.4.20 | |
| Compose BOM | 2026.09.00 | yes | 2026.09.00 | POM maps ui/foundation 1.12.1, material3 1.4.0, adaptive 1.3.0, material3-adaptive-navigation-suite 1.4.0 (as spec) |
| navigation3 | 1.2.0 | yes | 1.2.0 | |
| adaptive-navigation3 | 1.3.0 | yes | 1.3.0 | |
| lifecycle-viewmodel-navigation3 | 2.11.0 | yes | 2.11.0 | |
| core-ktx (modern / legacy) | 1.19.1 / 1.17.0 | yes | same | AAR manifests: 1.17.0 `minSdk 21`, 1.18.0 `minSdk 23` (confirms the legacy pin) |
| lifecycle / activity | 2.11.0 / 1.13.0 | yes | same | |
| datastore (modern / legacy) | 1.2.1 / 1.1.7 | yes | same | 1.1.7 AARs declare `minSdk 19` |
| webkit / browser | 1.17.1 / 1.10.0 | yes | same | |
| splashscreen / profileinstaller | 1.2.0 / 1.4.1 | yes | same | |
| recyclerview (legacy) | 1.4.0 | yes | same | |
| okhttp (+ mockwebserver) | 4.12.0 | yes | 4.12.0 | pinned (latest is 5.5.0) |
| stream-webrtc-android | 1.1.1 | yes | 1.1.1 | pinned (latest 1.3.10) |
| vosk-android | 0.3.47 | yes | 0.3.47 | pinned (latest 0.3.75); JNA 5.13.0 transitive |
| coroutines / serialization | 1.11.0 / 1.11.0 | yes | same | |
| collections-immutable | 0.5.2 | yes | 0.5.2 | |
| commonmark (+ 5 extensions) | 0.30.0 | yes | 0.30.0 | |
| dev.snipme:highlights | 1.1.0 | yes | 1.1.0 | |
| java-diff-utils | 4.17 | yes | 4.17 | |
| desugar_jdk_libs | 2.1.5 | yes | 2.1.5 | |
| junit 4.13.2, turbine 1.2.1, mockk 1.14.11, robolectric 4.17 | — | yes | same | |
| Roborazzi (library + Gradle plugin) | 1.76.0 | yes | 1.76.0 | plugin on Maven Central and the plugin portal |
| androidx.test runner 1.7.0, ext-junit 1.3.0, espresso 3.7.0, uiautomator 2.4.0, benchmark 1.5.0 | — | yes | same | |
| compileSdk / targetSdk | 37 / 36 | yes | 37 / 36 | SDK package is `platforms;android-37.0` (rev 2); AGP resolves `compileSdk = 37` to it |
| NDK / CMake | 26.1.10909125 / 3.22.1 | installed | — | not used until A-07 adds the shim |

### Added to the catalog beyond the spec 14 excerpt (all verified to exist)

`androidx.compose.ui:ui-graphics`, `ui-tooling`, `ui-tooling-preview`, `ui-test-junit4`, `ui-test-manifest`,
`androidx.compose.foundation:foundation`, `androidx.compose.material3.adaptive:adaptive-layout` (all BOM-managed,
in BOM 2026.09.00); `navigation3-runtime` 1.2.0; `lifecycle-runtime-compose`, `lifecycle-viewmodel-compose`,
`lifecycle-process` 2.11.0; `kotlinx-coroutines-android` / `-test` 1.11.0; `roborazzi-compose`,
`roborazzi-junit-rule` 1.76.0; `androidx.test:core` 1.7.0; build-logic only: `com.android.tools.build:gradle`
9.4.1, `kotlin-gradle-plugin` and `compose-compiler-gradle-plugin` 2.4.20.

## Additions and deviations (not substitutions)

1. **JDK 21 for unit-test JVMs.** Robolectric 4.17 refuses SDK 35+ sandboxes on Java 17 ("Android SDK 36
   requires Java 21"), and spec 14 §6.3/§6.4 run Robolectric/Roborazzi at `sdk = 36`. The laptop has only
   JDK 17 (the Jetson too). Build and bytecode stay on JDK 17; Android `Test` tasks run on a JDK 21 toolchain
   (`ArchieSdk.TEST_JDK`), auto-provisioned by `org.gradle.toolchains.foojay-resolver-convention` **1.0.0**
   (latest; in `settings.gradle.kts`, the one version outside the catalog because a settings `plugins {}`
   block cannot read it). Test JVMs also get `--add-exports java.base/jdk.internal.access` and
   `--add-opens java.base/java.io` (Robolectric's FileDescriptor interceptor). Verified with a throwaway
   Roborazzi capture in `:core:design` (reverted).
2. **Roborazzi is AGP-9-ready** (risk X20): 1.76.0 applied to an AGP 9.4.1 module recorded a Compose
   golden under Robolectric `sdk = 36`, `GraphicsMode.NATIVE`. No AGP fallback needed. The plugin is in
   the catalog and loaded at the root (`apply false`); B-01 applies it to modules.
3. **Lite emulator ABIs are `x86` + `x86_64`** (spec 14 §1.6 says `x86`). The `POCO_X7` AVD's image
   (API 36) lists `x86_64,arm64-v8a`, and the API 21 x86 image has no ARM translation, so the old
   ARM-only APK installs on the POCO AVD only. Proving install-over there needs a lite ABI that AVD
   accepts. Emulator builds only (`-Parchie.emulatorAbis=true`); device builds stay `armeabi-v7a`.
   `src/emulator/jniLibs/{x86,x86_64}/libvosk.so` are patched copies (see below).
4. **Patched Vosk libs live in `:app-lite`** (spec 14 §1.9): `src/main/jniLibs/armeabi-v7a/libvosk.so` is
   the existing patched file from the old app's `android/app/src/main/jniLibs/` (now `legacy/android/app/src/main/jniLibs/`) (sha256 `e1f9b617…592a`). Running
   `tools/native/patch_vosk_weaken.py` on the vosk-android 0.3.47 AAR's stock libraries reproduces the
   checked-in armeabi-v7a and arm64-v8a copies **byte for byte**, so the x86/x86_64 copies were made the same
   way.

## SDK packages installed for A-01

The SDK had no `cmdline-tools`. Installed `cmdline-tools;latest` (16111833, "Android CLI" 1.0.16500706),
`platforms;android-37.0`, `build-tools;37.0.0`, `system-images;android-21;google_apis;x86` (r32).
