---
name: android-device
category: archie/clients
tags: [android, companion, com.assistant.device, a300m, watchdog, boot, wifi-adb, dnd, api-21]
created: 2026-04-15
modified: 2026-10-06
summary: apps/android-device — companion app com.assistant.device that keeps the A300M voice terminal alive (watchdog, boot launch, WiFi ADB, DND).
source: curated (consolidated from memory notes assistant/android/android_device_project.md, assistant/devices/peripheral_devices.md; verified against code 2026-10-06)
references:
  - android.md
  - ../devices/devices.md
  - ../voice/wake-word.md
---

# Companion app `com.assistant.device` (`apps/android-device/`)

A small, separate Android app that turns the stock Samsung A300M into a dedicated assistant
terminal. It configures the OS around the assistant app — it has no assistant logic itself.
Keeping these system-level tweaks out of the assistant app means either can change without
touching the other.

- Package `com.assistant.device`, minSdk 21, targetSdk 34, versionName 1.0.
- Its own Gradle project (not part of `apps/android/`), code unchanged since 2026-04.
- Runs only on the A300M. The POCO phone has no companion (no watchdog, no boot launch).
- Native `android.app.Activity` + `Theme.Material`, no AppCompat/Compose: the APK is tiny and adds
  almost nothing to the 888 MB device's memory pressure.

## What it does

| Feature | Implementation | Works? |
|---|---|---|
| Watchdog | `service/WatchdogService.kt`: foreground service, polls every **30 s** (`POLL_INTERVAL_MS = 30_000L`); relaunches the assistant app if the package has no running service | Yes |
| Boot launch | `receiver/BootReceiver.kt` on `BOOT_COMPLETED`: records boot time, re-enables WiFi ADB, tries the CPU governor, enables DND, starts the watchdog, then launches the assistant app after **3 s** (`LAUNCH_DELAY_MS`), under a 15 s partial wake lock | Yes |
| WiFi ADB | `util/AdbUtil.kt` writes `Settings.Secure` keys on boot | Partial — see below |
| CPU governor | `util/CpuUtil.kt` writes `performance` to sysfs | Needs root; the UI reports "root required" |
| Do Not Disturb | `util/DndUtil.kt`: `Settings.Global.zen_mode` on API 21, `NotificationManager` on 23+ | Yes |
| Status UI | `MainActivity.kt`: ADB address, last boot, feature toggles (`util/Prefs.kt`); a button opens the "default home app" chooser so the assistant app can be set as launcher | Yes |

## The hard-coded dependency on `com.assistant.peripheral`

`BootReceiver` and `WatchdogService` hard-code `ASSISTANT_PACKAGE = "com.assistant.peripheral"`
and fall back to the explicit component `com.assistant.peripheral.MainActivity` when the launch
intent cannot be resolved. This is why the new lite app ([android.md](android.md)) kept the old
package name and that exact activity class: any assistant app on the A300M must

1. use the applicationId `com.assistant.peripheral`;
2. have a LAUNCHER activity, or the FQCN `com.assistant.peripheral.MainActivity`;
3. keep a long-running service alive whenever it is healthy (the lite app's `VoiceHostService`,
   restarted by every process start) — the watchdog considers the app alive when *any* service
   of the package is running;
4. play its cues on `STREAM_MUSIC`, because the companion turns DND on (total silence silences
   notification streams).

## Why it is built this way

- **`getRunningServices()`, not `getRunningAppProcesses()`.** On API 21,
  `getRunningAppProcesses()` returns only processes visible to the caller's uid, so the
  assistant app (another uid) looked dead and the watchdog relaunched it constantly.
  `getRunningServices()` sees services of every package.
- **WiFi ADB can't be made persistent without root.** `setprop persist.adb.tcp.port` is
  writable by the shell uid but not by an app uid on Samsung 5.0.2, and the `Settings.Secure`
  keys the app writes work on some AOSP builds but not this Samsung. After every reboot: plug USB
  → `adb tcpip 5555` → unplug. The device's **static IP `192.168.0.225`** (set on the device in
  2026-07) fixes only the address; the `tcpip` re-arm is still needed.
- **`WRITE_SECURE_SETTINGS`** must be granted once over USB. It survives app updates, not
  reinstalls.

## Build and setup after a (re)install

```bash
cd apps/android-device
./gradlew assembleDebug

# 1. Install (USB)
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 2. Grant the privileged permission (once per install)
adb shell pm grant com.assistant.device android.permission.WRITE_SECURE_SETTINGS
# 3. Enable WiFi ADB
adb tcpip 5555
# 4. Launch once: starts the watchdog and applies all settings
adb shell am start -n com.assistant.device/.MainActivity
```

On the laptop, wrap the Gradle build in the shared lock rule from [android.md](android.md)
(`flock /tmp/archie-locks/gradle.lock nice -n 15 …`).

## Checks

```bash
adb -s 192.168.0.225:5555 logcat -v time BootReceiver:* WatchdogService:* *:S   # relaunch lines = assistant died
adb -s 192.168.0.225:5555 shell "dumpsys activity services com.assistant.peripheral" | head
```

A healthy assistant app produces no watchdog relaunch lines; after installing a new assistant
build, watch for ten minutes that the watchdog stays quiet. Reboot test: the assistant app
should be in front and wake word armed within about a minute.
