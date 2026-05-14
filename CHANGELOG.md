# Changelog

All notable changes to this project are documented here. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.2] — 2026-05-15

### Added

- Build-time AGPS token can now be loaded from a gitignored
  `app/agps.properties` file (`token=…`). Persists across shells
  without re-exporting `LIGPSPORT_AGPS_TOKEN`. See `docs/AGPS_TOKEN.md`
  and `app/agps.properties.example` for the template.

### Changed

- `app/build.gradle.kts` resolution order for `BuildConfig.AGPS_TOKEN`
  is now: `app/agps.properties` → `LIGPSPORT_AGPS_TOKEN` env var →
  empty (runtime auto-fetch). Previously only the env var was
  consulted.

## [0.1.1] — 2026-05-14

### Fixed

- **693 km off-by-default bug on `de_DE` phones.** `CnxEncoder.formatCoord`
  used the JVM default locale, so the first track record's absolute
  lat/lon came out as `48,7561529` (comma decimal). The CNX
  `<Tracks>` field uses commas as field separators, so the BSC200
  parser mis-aligned every record from the second onward and the
  on-device "distance to goal" showed ~693 km for a 9 km route.
  Now pinned to `Locale.ROOT`. Tracked by
  `CnxEncoderTest.coordinates_use_period_decimal_under_de_de_locale`.

## [0.1.0] — 2026-05-14

Initial public release. A clean-room Android client for the iGPSPORT
BSC200 cycling computer, ported from the Python reverse-engineering
library at [`makefu/ligpsport`](https://github.com/makefu/ligpsport).

### Added

- BLE pairing + persistent paired-device store (SharedPreferences).
- Three pluggable route providers (BRouter / OSRM / offline
  straight-line) selectable from Settings.
- Compose UI: map + docked search bar with Photon type-ahead
  geocoding, destination card with separate Plan / Upload buttons,
  reverse-geocoded destination names that propagate into the
  saved-route file name on the device.
- 50 MiB app-private OSM tile cache so re-opening the map on a
  recently-visited area is offline-fast.
- "Jump to my location" FAB on the map; hidden until a GPS fix is
  available.
- AGPS pre-seeding via u-blox AssistNow Online — every route upload
  piggybacks ~2.5 KB of UBX-MGA ephemeris (`file_type=AGPS(7)`)
  before the route. Token is auto-resolved from the iGPSport backend
  the same way the official app does it, or overridden via the
  `LIGPSPORT_AGPS_TOKEN` build-time env var. Cleartext HTTP to
  `online-live1.services.u-blox.com` whitelisted via
  `network_security_config.xml`.
- Position-prior injection via the FACTORY `GPS_COORDINATE_SET` op
  (service 11, op 8). Piggybacked between AGPS and the route upload
  on every `PLAN_AND_UPLOAD` / `UPLOAD`. Surfaces as `seed_lat=` +
  `seed_lon=` on RESULT lines.
- Headless adb broadcast harness: `PAIR`, `UNPAIR`, `STATUS`,
  `UPLOAD`, `PLAN_AND_UPLOAD`, `LIST_ROUTES`, `DELETE_ROUTE`,
  `DELETE_ALL_ROUTES`, `SET_ROUTER`, `LIST_ROUTERS`, `MOCK_LOCATION`,
  `SEND_AGPS`, `SEND_LOCATION`. Every broadcast emits one structured
  `LigpsportAdb: RESULT …` logcat line keyed by `req_id`.
- Self-contained Nix flake: `build-debug`, `build-release`, `install`,
  `test-unit`, `run-instrumented-tests`, `emulator`, `gui-emulator`,
  `e2e-test`.

[0.1.2]: https://github.com/makefu/liGPSPORT-android/releases/tag/v0.1.2
[0.1.1]: https://github.com/makefu/liGPSPORT-android/releases/tag/v0.1.1
[0.1.0]: https://github.com/makefu/liGPSPORT-android/releases/tag/v0.1.0
