# Devices

The physical side of the reference deployment: which machines and devices exist, what each one
runs, how agents reach it, and the device-specific integrations (the Fire TV launcher and the
phone photo servers). The client apps themselves are documented in `../clients/`; the two
computers' setup and how code and context travel between them is in `../infrastructure/`.

- [devices.md](devices.md) — reference deployment table (Jetson, laptop, A300M, POCO, iPad mini 2, Fire TV, iPhone): role, OS, LAN address, client, access, skill; per-device notes; the living-room voice setup
- [fire-tv.md](fire-tv.md) — Fire TV: TvServerHub launcher, ScreensaverMonitorService, ADB discovery, `/connect-tv` `/tv-remote` `/tv-dev` `/create-viz`, the "Show on TV" endpoints
- [photo-servers.md](photo-servers.md) — iPhone Pythonista photo server (architecture, API, iOS limits, versions) behind `/iphone-photos`, and the planned Android photo server
