---
name: google-photos
category: archie/integrations
tags: [google-photos, picker-api, google-drive, drive-api, oauth, media, download, content-creation]
created: 2026-05-14
modified: 2026-10-06
summary: Google Photos Picker + Drive v3 behind /google-photos — why Picker, the two-step pattern, scripts, scopes, baseUrl rules, where downloads go.
source: curated (consolidated from memory notes projects/content-creation/google_photos_integration.md, projects/content-creation/content_creation_project.md (tooling parts), assistant/infrastructure/features_and_integrations_summary.md §7a; verified against code 2026-10-06)
references:
  - skills.md
  - youtube.md
  - visualizations-and-sharing.md
  - ../devices/photo-servers.md
  - ../infrastructure/context-sync.md
---

# Google Photos and Google Drive

How Archie gets phone-recorded photos and videos (and anything in Drive) into a local project
folder. Entry point: the personal `/google-photos` skill (`context/skills/google-photos/SKILL.md`).
Two APIs under one OAuth client:

1. **Google Photos Picker API** — the user selects items in Google Photos (app or web); the script
   downloads the originals. This is the only supported way to read a consumer Photos library.
2. **Google Drive API v3** — search, browse and download files already in Drive (Meet recordings,
   uploads, known folders). No human in the loop.

For media on the iPhone itself there is also the local photo server
([photo servers](../devices/photo-servers.md)).

## Why Picker

The Photos **Library** API was shut off for consumer accounts on 2025-03-31: every read-only scope
(`photoslibrary.readonly`, …) returns 403 regardless of setup. Drive only sees Photos content if the
account backs it up into Drive, which phone auto-backups generally do not. Google's replacement is
the Picker API, and there is no fully headless path to a consumer Photos library any more — a
person has to tap "Done" in the picker.

## The two-step pattern (assistant-driven)

A picker session waits up to 30 minutes for the selection, so an agent must not block on it:

1. **Create the session and show the URL**
   `context/scripts/run.sh context/scripts/google_photos_picker.py` → JSON with `id` and
   `pickerUri`. Give the user the `pickerUri`; they open it on any device signed into the account,
   pick items, press Done.
2. **Resume and download** once they confirm
   `context/scripts/run.sh context/scripts/google_photos_pick.py --session-id <id> --output <dir>` —
   polls until `mediaItemsSet`, lists the items, downloads each at full resolution, deletes the
   session.

One-step (user at the terminal): `google_photos_pick.py [--output DIR] [--no-browser] [--qr]
[--dry-run] [--json] [--timeout 1800]` opens the browser, waits, downloads. `--qr` prints the URL
as a terminal QR code (needs the `qrcode` package). Ctrl-C leaves the session intact for
`--session-id`.

`PhotosPickerClient` (`google_photos_picker.py`) does the REST calls against
`https://photospicker.googleapis.com/v1`: `create_session()`, `get_session()`,
`wait_for_selection()` (honours the server's `pollingConfig.pollInterval` / `timeoutIn`),
`list_picked_items()`, `download_item()` / `download_items()`, `delete_session()`.

## Download rules

- `mediaFile.baseUrl` expires **60 minutes** after the session resolves — download in the same run.
- Append **`=d`** for photo bytes or **`=dv`** for video to the `baseUrl`, and send
  `Authorization: Bearer <token>`; anonymous fetches get 403. The client does both.
- Picker downloads keep the original `filename` and overwrite an existing file of that name. (The
  legacy Drive downloader skips files that already exist with the same size.)
- **Where to download.** The default output is `context/public/downloads/google-photos/`, which is
  inside the synced `context/`. context-sync mirrors `context/` in real time and can delete
  in-flight bytes, and multi-GB media inside `context/` once crashed the Jetson. Pass `--output`:
  large media go to a repo-level project folder, `~/assistant/projects/<name>/` (gitignored, not
  synced — the backend serves it at `/projects/<path>`), or anywhere outside `context/`.
  `context/projects/` is gitignored but **still synced**, so it is not a safe place for big files.
  Small review copies the user must see from other devices go under
  `context/memory/projects/<project>/` ([visualizations and sharing](visualizations-and-sharing.md)).

## Drive

```
context/scripts/run.sh context/scripts/google_drive_api.py <command> [--json]
```

| Command | Does |
|---|---|
| `list [--type video\|image\|audio\|document\|folder\|pdf\|<mime>] [--limit N]` | Recent files |
| `search "<text>"` | Full-text (`fullText contains` — token-based, covers name, description, content) |
| `find "<name>"` | File-name prefix match (`name contains` is prefix/token based, not substring) |
| `find-folder "<name>"`, `folder <id>`, `root` | Browse folders |
| `starred`, `shared`, `since "<YYYY-MM-DD>"` | Filters |
| `download <file_id> --output <dir>` | Download by id |
| `query "<raw Drive query>"` | Raw query, e.g. `name contains 'intro' and mimeType = 'video/mp4'` |
| `info` | Account and storage quota |

`GoogleDriveClient` (`google_drive_client.py`) exposes the same operations to Python
(`search`, `search_by_name`, `list_videos`, `list_folder`, `find_folder`, `search_by_date`,
`raw_query`, `download`, `about`) and a `build_query()` helper that turns keyword filters (name,
MIME types, parent, starred, trashed, owner, date ranges, shared-with-me, visibility, raw) into a
Drive query string.

## Scripts and auth

| Script (`context/scripts/`) | Purpose |
|---|---|
| `google_drive_auth.py` | OAuth2 flow for Drive **and** Picker: `generate`, then `exchange <code>` (PKCE; both steps must use the same saved flow state) |
| `google_photos_picker.py` | `PhotosPickerClient`; run directly it creates a session (step 1) |
| `google_photos_pick.py` | Picker CLI (create / resume / download) |
| `google_drive_client.py` | `GoogleDriveClient` |
| `google_drive_api.py` | Drive CLI |
| `google_photos_download.py` | Legacy: videos via Drive (`latest`, `favorites`, `search`); only useful when backups land in Drive |
| `google_photos_auth.py`, `google_photos_client.py` | Older Drive-based Photos client with its own token file; superseded by the Picker pair |

- OAuth client: the shared Google client JSON at `GOOGLE_CREDENTIALS_PATH`, same as
  [YouTube](youtube.md).
- Scopes: `drive.readonly` + `photospicker.mediaitems.readonly`.
- Token file: `context/secrets/google_drive_tokens.json`, shared by Drive and Picker, refreshed
  automatically.
- Python deps: `google-auth`, `google-auth-oauthlib`, `google-api-python-client`, `requests`.

## Typical requests

| Request | Path |
|---|---|
| "Grab the video I just recorded into my project" | Picker, two-step, `--output ~/assistant/projects/<name>` |
| "Download the latest photos from my phone" | Picker, two-step |
| "Find last Tuesday's Meet recording" | Drive `search` / `since` / `find-folder "Meet Recordings"` |
| "How much Drive storage is left?" | Drive `info` |

Content-creation flow: phone recording → Picker → project folder → `/music-video-editor` or
`/remotion` ([skills](skills.md)).
