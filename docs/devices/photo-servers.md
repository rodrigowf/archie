---
name: photo-servers
category: archie/devices
tags: [iphone, pythonista, photo-server, rest-api, media, ios, android, planned]
created: 2026-04-25
modified: 2026-10-07
summary: The iPhone photo server (single-file Pythonista REST API, /iphone-photos) and the planned Android photo server.
source: curated (consolidated from memory notes assistant/devices/ios_photo_server_project.md, assistant/android/android_photo_server_project.md; verified against code 2026-10-06)
references:
  - devices.md
  - ../integrations/google-photos.md
  - ../integrations/visualizations-and-sharing.md
  - ../integrations/skills.md
---

# Photo servers

Archie can pull photos and videos straight from a phone on the LAN. The working one runs on the
iPhone; an Android equivalent is planned. For media already in the cloud, use
[Google Photos](../integrations/google-photos.md) instead.

## iPhone photo server (Pythonista)

A REST API over the iOS photo library, written as **one Python file** so it can be dropped into
Pythonista 3 on the iPhone. The main consumer is agents (`/iphone-photos` skill); a small HTML
browse UI is secondary.

| Fact | Value |
|---|---|
| Script | `context/public/photo-server/copyparty_ios_combined.py` (private repo, ~1800 lines), version **1.3.4** |
| Distribution | Served by the backend from `context/public/`: `https://192.168.0.200/photo-server/copyparty_ios_combined.py`, download page `…/photo-server/iphone_photo_server.html` (setup + changelog), QR codes `iphone_photo_server_qr.png`, `copyparty_ios_combined_qr.png` |
| Runtime | Pythonista 3 on an iPhone 11 |
| Port | **3691** (`--port` to change; `--stub` runs it on a desktop without the photo library) |
| Address | DHCP; last seen `192.168.0.34` |
| Skill | `/iphone-photos` (`context/skills/iphone-photos/SKILL.md`) |

Editing the file under `context/public/` is what updates the copy the iPhone downloads.

### Architecture

Four parts in the single file:

1. **PhotoBridge** — wraps Pythonista's `photos` module and the `objc_util` bridge
   (`PHPhotoLibrary.authorizationStatus()`, `PHAssetResource` for original filenames,
   `PHAssetResourceManager` for video export, `PHAsset.fetchAssetsWithLocalIdentifiers_options_`
   for on-demand fetch).
2. **HTTP server** — `http.server`-based, JSON, CORS `*`, Range requests for video.
3. **Logging** — `_TeeWriter` tees stdout/stderr to a log file with a flush after every write;
   on startup the current log rotates to `previous.log` (logs in `Documents/photo_server_logs/`).
4. **Launcher** — permission check, server start, QR code.

Design rules (each came from a crash on the device):

- **Server first.** The HTTP server starts before the photo library is touched; enumeration runs
  in a background thread, so a crash in enumeration cannot take the server down.
- **Lazy everything.** Filenames are resolved via ObjC only per asset; `location` and
  `media_subtypes` only in the detail endpoint (they hide ObjC calls); asset objects are fetched
  on demand and never cached (cached assets pin ObjC memory).
- **Split enumeration** (images, then videos) halves peak memory; progress logged every 50 assets.
- **Every asset property access in its own try/except** — one bad asset cannot abort the scan.
- No `argparse` (Pythonista passes unexpected `sys.argv`; flags are parsed by hand) and no
  `signal.signal()` (unsupported on iOS).

### API

| Endpoint | Description |
|---|---|
| `GET /api/status` | Counts, `loading` + `progress` while enumerating, uptime |
| `GET /api/assets` | List: `type=photo\|video`, `album`, `favorite`, `after`/`before` (ISO date), `sort=date_desc\|date_asc\|size_desc\|size_asc`, `offset`/`limit` (max 500), `q`/`search` |
| `GET /api/assets/{id}` | One asset, plus lazily resolved `location`; a UUID prefix also matches |
| `GET /api/albums` | User and smart albums |
| `GET /api/search?q=` | Filename substring search |
| `GET /api/refresh` | Drop the cache and re-enumerate (after taking new photos) |
| `GET /api/logs`, `GET /api/logs/previous` | Current / previous session log (the previous one holds the crash log) |
| `GET /media/{id}/full`, `/thumb` (300 px), `/preview` (1200 px) | Downloads; `{id}` is the UUID part of the asset id, before `/L0/001` |
| `GET /`, `GET /browse` | HTML landing page and thumbnail grid |

```bash
curl -s --connect-timeout 3 http://192.168.0.34:3691/api/status
curl -s 'http://192.168.0.34:3691/api/assets?type=photo&limit=1'
curl -s -o photo.heic http://192.168.0.34:3691/media/<uuid>/full
```

### iOS limits

- **Works only while Pythonista is in the foreground.** iOS suspends a backgrounded app within
  ~5 s. The process is frozen, not killed: bringing Pythonista back resumes the server instantly
  (uptime keeps counting). Keep it on screen while pulling files.
- Photos permission must be "Full Access" for Pythonista (Settings → Privacy & Security → Photos).
- **Videos over 100 MB are refused with HTTP 413.** Video export uses
  `writeDataForAssetResource_toFile_`, which buffers the whole file in RAM; larger files get the
  app killed by iOS (jetsam). A pre-flight size check (`PHAssetResource` `fileSize` via KVC)
  rejects them. Use `/thumb` or `/preview`, or trim the video in Photos.
- **Pythonista's ObjC bridge only handles single-argument blocks** (`^(NSError *)`) passed as
  raw Python functions. Streaming export with a multi-shot data handler, `ObjCBlock`-wrapped
  handlers, and `AVAssetExportSession`'s multi-arg result handler all crashed Pythonista with
  SIGSEGV. Keep callbacks single-argument and hold a strong reference to them for the request's
  lifetime.
- The IP changes between sessions; ask the user for the address shown in Pythonista, or scan the
  /24 for port 3691.

### Version history (condensed)

| Version | Change |
|---|---|
| 1.0.0 | First implementation (modular, then combined single file) |
| 1.1.0 | OOM fixes (no eager library load or filename resolution, no cached assets); removed `argparse`/`signal` — still crashed on device |
| 1.2.0 | Defensive rewrite: server first, background split enumeration, per-property try/except — stable; video downloads still crashed |
| 1.2.1 | Video export via `PHAssetResourceManager` single callback, pinned against GC |
| 1.3.0 | Persistent tee logging + `/api/logs`, `/api/logs/previous`; download page; videos up to ~96 MB work |
| 1.3.1 – 1.3.3 | Streaming export attempts (data handler, ObjCBlock wrapping, AVAssetExportSession) — all SIGSEGV in Pythonista |
| **1.3.4** (current) | Back to the 1.3.0 export path plus the 100 MB cap → HTTP 413 |

`/api/status` reports `server_version` from the script's `__version__` (`iOSPhotoServer/1.3.4`;
fixed 2026-10-07). A copy downloaded to the iPhone before that still reports
`iOSPhotoServer/1.2`; its startup log line shows the real version.

Why not CopyParty: the project started as a port of the CopyParty file server, abandoned because
its deep filesystem integration, subprocess/signal/multiprocessing use and self-extracting format
don't fit iOS. The filename `copyparty_ios_combined.py` is a leftover of that start.

## Android photo server (planned)

Not built yet. The idea is an Android app that exposes a phone's MediaStore over the LAN with an
API mirroring the iPhone server's, so the same agent workflow works for both. Android removes the
iPhone's main limits:

- a **background/foreground service** keeps the server reachable while the app is not on screen;
- APKs are easy to sideload (no Pythonista sandbox);
- direct LAN transfer beats round-tripping large videos through cloud backup.

Planned shape: MediaStore enumeration with metadata (date, size, format, EXIF), an HTTP server
(FTP considered as an option) with list / detail / full / thumbnail endpoints, runtime media
permissions, optional auth or a trusted-device restriction, and mDNS or scan-based discovery.
First step: a minimal prototype listing a few media files over HTTP.

## Possible improvements to the iPhone server

Automatic IP discovery (mDNS/Bonjour), an upload endpoint (push media to the phone), EXIF-based
search.
