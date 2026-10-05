# Q8 evidence — the raw `/dev/input` recents monitor (A-08, 2026-10-04)

**Question (spec 14 §9 Q8, §5.1):** does the lite app need the old raw recents-key monitor
(`AssistantService.startRecentsMonitor`, `old/service/AssistantService.kt:613-675`), which reads
`/dev/input/event2` (A300M `sec_touchkey`) for a 600 ms `KEY_APPSWITCH` press?

## What the old monitor needs

It opens the character device with a plain `FileInputStream` **as the app's uid**, so it needs:

1. **DAC read access.** API 21 `ueventd.rc:42`: `/dev/input/*  0660  root  input`. The app uid must be
   in group `input` (gid 1004).
2. **SELinux read access.** API 21 `file_contexts:57`: `/dev/input(/.*)  u:object_r:input_device:s0`;
   third-party apps run as `untrusted_app` (`seapp_contexts:10`).

## What a third-party app can get

- Supplementary gids come only from `platform.xml` permission mappings. On API 21 the only
  permission mapped to `gid="input"` is `android.permission.DIAGNOSTIC`
  (`/system/etc/permissions/platform.xml:97-99`), whose `protectionLevel` is `0x2` = **signature**
  (platform key) in `framework-res.apk`. The old manifest does not request it, and could not get it.
- So DAC alone denies the open: `open failed: EACCES (Permission denied)`, caught by the old code and
  logged as `Recents monitor error: …` (`AssistantService.kt:668-670`). The SELinux side was not
  checked against the binary policy (no `setools` on the host); it is moot once DAC denies.

Sources: the `system-images/android-21/google_apis/x86` image (ramdisk `ueventd.rc`,
`file_contexts`, `seapp_contexts`; `system.img` `platform.xml` and `framework-res.apk`, read with
`debugfs` / `aapt2`). The AVD itself could not boot during A-08 (host disk 100 % full), so there is no
runtime capture.

## Conclusion

On a non-rooted device the monitor cannot open the node; the working recents trigger on the A300M
has been the accessibility service. Samsung's kernel/ueventd could differ, so one read-only check
settles it on the real device (the old app is still installed there):

```
adb logcat -d | grep -E "Recents monitor (started|error|stopped)|KEY_APPSWITCH released"
```

`Recents monitor error: … EACCES` ⇒ drop the monitor (set `HostConfig.rawRecentsMonitor = false`).
`KEY_APPSWITCH released after …ms` lines ⇒ it works; keep it (it is now behind `button_trigger_enabled`, B5).

## What A-08 ships meanwhile (decision E-7: keep for parity until the field says otherwise)

`DevInputRecentsMonitor` (same path, struct and 600 ms rule) runs in the lite `VoiceHostService`
when `HostConfig.rawRecentsMonitor` is true (default for `HostConfig.lite`), fires through the single
`TriggerIngress` (so it honours the toggle), and logs the same greppable `Recents monitor error:` marker.
