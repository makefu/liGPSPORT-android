# Changelog

All notable changes to this project are documented here. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Activities sub-section in Settings — list, download (FIT), delete recorded
  activities from the BSC200. Delete-all guarded by a confirmation dialog.
- adb harness actions: LIST_ACTIVITIES, DOWNLOAD_ACTIVITY, DELETE_ACTIVITY,
  DELETE_ALL_ACTIVITIES.

### Changed
- Settings: Routes-on-device moved out of the main list into a "Routes on
  device" sub-section that opens its own screen, alongside the new
  Activities one.

## [1.1.0] — 2026-05-15

Route editing on the map (Google-Maps-style), an in-place upload
button that morphs through Idle / Uploading / Success / Failed
without a screen change, an AGPS-token runtime override in
Settings, and a configurable hit-area for the draggable map
markers. Plus the first hands-off-keyboard regression: a
search-bar crash on certain Photon responses.

### Added

- **Drag-to-edit routing.** Long-press the map to drop an
  intermediate stop, drag any of Start / Stop / Destination to
  move it, tap an intermediate stop to remove it. Each edit
  triggers an automatic re-plan through the new sequence — the
  Polyline preview updates without an explicit "Re-plan" tap.
  Start defaults to the live GPS fix until the user drags it; the
  drag promotes it to a sticky override so subsequent fixes don't
  yank the planned origin away. Mirrors Google Maps' multi-stop
  editing UX. `RouteProvider.planGpx` now accepts an
  `intermediates: List<Point>` (default empty) and the three
  built-in providers (BRouter / OSRM / straight-line) thread the
  via points into their native multi-coord wire formats.
- **In-place upload** on the map. Tapping Upload no longer opens
  a separate screen — the button morphs through *Upload* →
  *Uploading…* → green *Uploaded ✓* / red *Retry — <reason>*.
  Picking a new destination during the upload doesn't unblock the
  button; once the upload settles, the button drops back to
  *Upload* if the destination changed in the meantime.
- **AGPS-token override in Settings.** A new card near the bottom
  reports *Custom token set* / *No custom token* — never the
  value — and offers Set / Change / Remove plus a *Test* button
  that fires a real AssistNow Online request and reports bytes
  received or the error inline. Persisted via
  `AgpsTokenStore`; upload-time resolution order is
  Settings → `BuildConfig.AGPS_TOKEN` → iGPSport backend
  (existing fallback).
- **Configurable marker hit-area.** Settings → Map markers
  exposes a 48–120 dp slider (default 80 dp) that controls how
  wide a touch around the visible pin counts as a grab. Underlying
  `WideHitMarker` overrides osmdroid's `hitTest` so the visible
  icon stays the stock pin while the touch target scales —
  Material-recommended 48 dp minimum is the floor.

### Fixed

- **Instant crash on typing in the search bar.** Photon's
  autocomplete legitimately returns the same `(name, lat, lon)`
  twice when one feature is indexed under multiple categories.
  The suggestion list's LazyColumn keyed off `name|lat|lon` and
  threw `IllegalArgumentException: Key … was already used` on the
  next layout pass, killing the app. The key now folds in the list
  index so two identical Photon entries can coexist. Pinned by
  `SearchSuggestionKeyTest`.

### Internal

- New `data/AgpsTokenStore` + `data/MarkerHitboxPreferences`
  SharedPreferences wrappers.
- `WideHitMarker` (osmdroid `Marker` subclass) — fixed-dp box
  around the bottom-anchored pin tip, with extra slack above
  (pin body) and a small strip below.

[1.1.0]: https://github.com/makefu/liGPSPORT-android/releases/tag/v1.1.0

## [1.0.0] — 2026-05-15

**MVP release.** A complete clean-room Android client for the iGPSPORT
BSC200 cycling computer: plan a bike route on an OSM map, tap a
destination, watch the polyline appear, press Upload, and the BSC200
switches itself into the navigation screen. No iGPSPORT cloud
account, no manual route-pick on the device, no separate
"connect/sync" dance.

End-to-end verified on hardware (paired BSC200, firmware 2024-05-14):
auto-plan → upload (FILE_OPERATION ADD) → auto-start navigation
(ROUTE_PLAN FILE_USE) → on-device navigation screen.
`scripts/e2e-test.sh` also exercises the
`DELETE_ALL_ROUTES → LIST_GET` round-trip to confirm bulk deletion
actually clears inactive routes from the device.

### Changed

- **Map UX: tap-to-plan replaces the two-step Plan/Upload flow.**
  Picking a destination (search-bar or map-tap) now auto-runs the
  configured `RouteProvider` immediately; the destination card
  shows a single **Upload** button that displays a "Planning…"
  spinner until the route is ready, then enables. Removes the
  Plan button entirely.

### Added

- **"Delete all routes" button in Settings → Routes on device.**
  Guard dialog notes that the active navigation route is
  firmware-protected (PROTOCOL.md §7.4) and will remain on the
  device even after a successful `FILES_DEL` bulk wipe.
- **E2E verification of `DELETE_ALL_ROUTES`.** The harness now
  uploads two routes, lists them to confirm both landed, issues
  `DELETE_ALL_ROUTES`, then re-lists to assert the *inactive*
  route is gone (active route is firmware-protected — expected to
  stay). Gated by `LIGPSPORT_TEST_DELETE_ALL=0` if you want to skip.

[1.0.0]: https://github.com/makefu/liGPSPORT-android/releases/tag/v1.0.0

## [0.1.3] — 2026-05-15

Auto-start navigation on the BSC200 after every route upload — the
device flips into navigation mode without the rider having to pick
the route on the bike computer. Adds an on-screen nav-status pill
and a route-management section in Settings. Tracks the protocol
fixes from `ligpsport` Python v1.2.0 (gen-4 single merged write +
`name` / `total_distance` fields on `FILE_USE`).

### Added

- **Auto-navigation after upload**: every `UPLOAD` /
  `PLAN_AND_UPLOAD` (button or adb) issues a follow-up
  `ROUTE_PLAN FILE_USE` once the upload acks, activating the route
  on the BSC200 and switching the device into navigation mode. The
  `RESULT` line gains `nav_started=true|false`. Mirrors the
  iGPSPORT app's "send and use" flow. PROTOCOL.md §7.2.
- **`NAV_STATUS` adb action** + bottom-left **nav-status pill** on
  the map: polls `ROUTE_PLAN LIST_GET` every ~15 s, scans for the
  `enum_USED_STATUS` entry, and shows one of
  *Pair device first* / *Connecting…* / *Navigating: <route>* /
  *No active route*. PROTOCOL.md §7.3. The pill keeps showing the
  previous value while a poll is in flight so transient BLE
  failures don't flicker the UI.
- **Routes-on-device section in Settings**: lists every route the
  BSC200 holds (id, name, distance, active flag) and lets the user
  delete inactive routes individually. Active route gets a guard
  dialog noting it's firmware-protected. Uses the new
  `DELETE_ROUTE_BY_ID` adb action under the hood.
- **`DELETE_ROUTE_BY_ID` adb action**: single-id wrapper around
  `ROUTE_PLAN FILES_DEL` (op = 6) with `line_id` + full
  `route_plan_info_msg` (PROTOCOL.md §7.4 — sending only one or
  the other is silently no-op'd).
- `FileTransfer.deviceStatusName` — wire-byte → name lookup for
  `DeviceReturnStatus`, including the Navigation block (65, 66 =
  `NavigationRouteDoesNotExist`). Surfaces the right name in
  `RESULT … reason=` for FILE_USE refusals.

### Fixed

- **`FILE_USE` wire format (gen-4 merged write)**: the BSC200
  reports `getGeneration() == 4` and takes the
  `setRoutePlanFile`/`send$lambda-135` merged-write branch —
  *one* write of (20-byte head ‖ protobuf body) on the FOURTH
  characteristic, *not* the body/header split across two
  characteristics. The earlier two-write path was silently
  dropped by the firmware (`nav_started=false`). Live-verified
  against the iGPSPORT app's snoop_start.log capture in the
  Python reference repo.
- **`FILE_USE` protobuf**: the nested `route_plan_info_msg` now
  carries the required `name` and `total_distance` fields. BSC200
  firmware validates `name` and drops requests that omit it; the
  captured app fills `str(file_id)` for unnamed routes.
- **`ROUTE_PLAN LIST_GET` returns the routes the device holds**:
  the request now includes a `route_list_get_msg` index range
  (fields 3 + 4). Without it the BSC200 silently returned an
  empty list — `LIST_ROUTES` always reported `count=0`. Fixes the
  routes-on-device section + the nav-status scan.
- **`FILES_DEL` for the bulk-delete path** populates both
  `line_id` and `route_plan_info_msg` per target and uses the
  gen-4 single merged write. The earlier wire format (only
  `line_id`, on control channel) was no-op'd by the firmware.

[0.1.3]: https://github.com/makefu/liGPSPORT-android/releases/tag/v0.1.3

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
