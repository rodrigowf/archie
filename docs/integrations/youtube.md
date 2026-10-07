---
name: youtube
category: archie/integrations
tags: [youtube, youtube-data-api, oauth, yt-dlp, download, fire-tv, content-creation, scripts]
created: 2026-02-23
modified: 2026-10-07
summary: YouTube Data API v3 scripts behind /youtube, OAuth token handling, the yt-dlp downloader, and the play-on-Fire-TV flow.
source: curated (consolidated from memory notes projects/content-creation/youtube_integration_project.md, projects/content-creation/content_creation_project.md (tooling parts), assistant/infrastructure/features_and_integrations_summary.md §7; verified against code 2026-10-06)
references:
  - skills.md
  - google-photos.md
  - visualizations-and-sharing.md
  - ../devices/fire-tv.md
---

# YouTube integration

Read and manage Rodrigo's YouTube account through the YouTube Data API v3, and download public
videos with yt-dlp. Entry point: the personal `/youtube` skill (`context/skills/youtube/SKILL.md`),
which routes a request to a pre-built script or, for anything else, to a direct API call through
the client module. Typical uses: find a video in his playlists and play it on the TV, pull a
reference clip or its audio into a content-creation project.

## Scripts

All are run through the venv: `context/scripts/run.sh context/scripts/<script> …`. Every listing
script takes `--json`.

| Script | Where | Purpose |
|---|---|---|
| `youtube_auth.py` | `context/scripts/` | OAuth2 flow: `generate` (opens the consent URL), `exchange <code>` |
| `youtube_client.py` | `context/scripts/` | Importable `get_youtube_client()`; run directly it prints account statistics (`/youtube info`) |
| `youtube_api.py` | `context/scripts/` | Generic caller: `youtube_api.py <resource> <method> --params k=v … [--body '<json>']` for any Data API operation |
| `youtube_search_playlists.py` | `context/scripts/` | Search every playlist for a keyword (`--sort date\|title\|playlist\|channel`, `--limit`, `--case-sensitive`) |
| `youtube_playlist.py` | `context/scripts/` | Videos of one playlist (partial name match, `--limit`) |
| `youtube_playlists.py` | `context/scripts/` | List playlists (`--filter`, `--sort name\|count`) |
| `youtube_subscriptions.py` | `context/scripts/` | List subscriptions (`--limit`, default 50) |
| `youtube_download.py` | `shared/scripts/` (linked into `context/scripts/`) | yt-dlp wrapper — no auth, any public URL |

Ad-hoc operations use the client directly, e.g.
`get_youtube_client().playlistItems().insert(part='snippet', body=…).execute()`; when one proves
useful, the skill says to turn it into a `youtube_<operation>.py` script and list it in the skill.

## Auth

- OAuth client: the shared Google client JSON at `GOOGLE_CREDENTIALS_PATH` (from `context/.env`),
  the same client as [Google Photos / Drive](google-photos.md); each service keeps its own token.
- Scopes: `youtube.readonly` and `youtube.force-ssl` (read and manage).
- Token file: `context/secrets/youtube_tokens.json`, written by `youtube_auth.py exchange` and read
  by `youtube_client.py`; the client refreshes the access token automatically. (Until 2026-10-07
  `youtube_auth.py` wrote to a stray `context/context/secrets/` folder, so a re-auth never reached
  the client.)
- Quota: about 10 000 units a day; `search` costs far more than `list`, so prefer playlist scans
  and list calls.

## Downloads (`youtube_download.py`)

```
youtube_download.py <url> [--mode video|audio|both] [--output-dir DIR]
                          [--audio-format wav|flac|mp3|opus]
                          [--video-quality best|2160p|1440p|1080p|720p|480p]
                          [--keep-source]
```

Defaults: `--mode both`, output to the current directory, `--audio-format wav` (PCM 16-bit 48 kHz
stereo, ready for a DAW), `--video-quality best`. `flac` is lossless and smaller, `mp3` is 320 kbps,
`opus` copies the source stream without re-encoding.

What the script handles:

- **ffmpeg version** — ffmpeg ≥ 4 can mux Opus into MP4, so the container is MP4; older ffmpeg
  (the Jetson has 3.4) gets MKV, so the best Opus audio is kept without re-encoding either way.
- **Quality caps** limit the **short** edge: `1080p` caps a landscape video's height and a
  portrait video's width (yt-dlp filters `[aspect_ratio>=1][height<=N]` / `[aspect_ratio<1][width<=N]`
  as alternatives), so a 1080×1920 portrait clip keeps its full resolution. `best` applies no cap.
  (Fixed 2026-10-07: the cap used to constrain both edges, so portrait clips were limited by
  their long edge.)
- **Output inside `context/`** — prints a warning: context-sync can lose files that are being
  written. Download into a project folder outside `context/` (e.g. `~/assistant/projects/<name>/`).

Prerequisites: `yt-dlp` on `PATH` or in `~/.local/bin` (`pipx install yt-dlp` on the laptop,
`pip install yt-dlp` on the Jetson) and `ffmpeg`.

## Playing on the Fire TV

1. Find the video: `youtube_search_playlists.py "<keyword>" --sort date --limit 5`.
2. Take its `https://www.youtube.com/watch?v=<id>` URL.
3. Play it with `/tv-remote`: launch the YouTube app
   (`adb -s <tv> shell am start -n com.amazon.firetv.youtube/dev.cobalt.app.MainActivity`) or open
   the URL in the TvServerHub WebView
   (`… -n com.example.tvserverhub/.WebPageViewActivity -e url "<url>"`).

The TV's adb address and the TvServerHub app are in [fire-tv](../devices/fire-tv.md). Through the
orchestrator, a request like "play my latest jazz video on the TV" chains `/youtube` and
`/tv-remote` in one agent session.

## Pitfalls

- **`invalid_grant` on any call** means the refresh token was revoked or expired (one common cause:
  Google expires the refresh tokens of OAuth clients left in "testing" mode after 7 days). Re-run `youtube_auth.py generate`
  and `exchange <code>`.
- Dynamic calls: put `context/scripts` on `sys.path` and `import youtube_client` (the skill's
  recipe does this and works from the repo root; it used to import `scripts.youtube_client`, which
  only resolved from inside `context/`).
