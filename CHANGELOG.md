# Changelog

All notable changes to this project are documented here. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

- **Corrupted activity downloads.** `BleTransport` accumulated
  notifications from all four channels into one shared buffer, on the
  assumption that only one logical frame is ever in flight. That holds
  for a two-second upload but not for an activity download, which
  streams for tens of seconds: a frame arriving on another channel got
  spliced into the middle of the file. Because reassembly counts bytes
  rather than tracking frames, the result was a FIT of exactly the
  right length with protocol header bytes where ride data should be —
  accepted silently, then rejected by Strava as malformed. Receive
  buffers are now per-channel, and a notification from an unrecognised
  characteristic is dropped with a warning instead of being attributed
  to whichever channel was seen last.
  - Diagnosed on a BSC300T: two downloads of one activity diverged at
    byte 210,572 of 296,853, where the first contained a valid 20-byte
    `TYPE_REQUEST` frame (service=3) and the second held ride data —
    the two files matching exactly at a 20-byte shift.
  - Frame removal now uses one `subList().clear()` instead of
    `removeAt(0)` in a loop, which was O(n²) on a 300 KB transfer.
- **Downloads no longer fail silently.** `UploadPipeline.downloadActivity`
  verifies the FIT's structure and both CRCs (new `route/FitFile.kt`)
  and reports `corrupt download: …` rather than saving a bad file that
  only fails much later, somewhere else.
- **Activity dates were twenty years out.** Device timestamps count
  from the FIT epoch (1989-12-31), not the Unix one, so the activities
  list and the saved filenames showed 2006 for a 2026 ride. Note the
  device's list timestamps read as local wall-clock rather than UTC —
  observed 12 h from the FIT's own `time_created` on a UTC+12 device —
  so filenames no longer carry a misleading `Z` suffix.

## [1.3.0] — 2026-08-14

Multi-device pairing and the Google-Maps-style multi-stop editor, plus
the BLE session rework that made uploading to an already-paired device
reliable again.

### Added

- **Pair several BSC200 cycling computers simultaneously**
  (`DeviceStore.MAX_DEVICES = 10`). Uploading a route fans out to
  every paired device in parallel — one shared GATT session per
  device, results come back as a per-device map, and the destination
  card surfaces "all OK" vs "2/3 OK — last failed: …" rather than
  committing to a single outcome.
- **Bottom-left status stack** with one pill per paired device.
  Each pill polls its device's nav status independently every 15 s
  and shows `<device> · Navigating: <route>` / `Idle` / `Connecting…`
  — implicitly doubling as a "which devices are reachable" signal.
- **Previous rides.** Every upload is snapshotted with the full set
  of editor inputs — start, vias, destination and their labels —
  alongside the resolved GPX, so re-opening a ride shows what the
  device actually received. Reachable from Settings; tapping an entry
  restores the whole route onto the map, and the auto-planner leaves
  the saved polyline alone until you edit a stop. Capped with FIFO
  eviction so the history blob can't stall a cold start.
- **Swap start and destination** from the top bar. Vias reverse with
  them so the new geometry doesn't double back.
- **Rename a paired device** from Settings. Nicknames survive
  unpairing and re-pairing.
- **Share recorded activities.** The activity list gains a share
  button that downloads the FIT file from the device and hands it to
  Android's share sheet, exposed through a new FileProvider.
  (@mbeutelspacher, #2)
- **Per-device sub-menus in Settings.** "Paired devices (n / 10)" is
  the top section now; each device row has its own "Routes on
  device" and "Activities on device" launchers plus a forget button.
  The sub-screen Top App Bar reads the device name as a subtitle so
  you always know which device's files you're editing.
- **Pairing flow supports adding instead of replacing.** The
  pairing screen lists currently-paired devices at the top with X
  buttons; scan results below append rather than overwrite, capped
  at `DeviceStore.MAX_DEVICES`. Existing single-device installs
  migrate the legacy SharedPreferences keys into slot 0.

### Fixed

- **Route uploads no longer fail while devices stay paired** (#3).
  Every pipeline helper used to open its own GATT connection, so the
  15-second-per-device nav-status poll and a route upload could hold
  two links to the same computer at once. An upload runs well past
  15 s, so the collision hit almost every attempt: the poll's
  disconnect closed the upload's frame channel and the ack wait came
  back empty. The only way out was to unpair every device — which
  worked only because that silences the pollers. A new
  `BleSessionManager` keeps one shared connection per MAC and leases
  it exclusively, with connect retries and transparent reconnection
  of a link the stack dropped while the app was idle. Different MACs
  still upload in parallel.
- `BleTransport.close()` waits for the disconnect callback before
  releasing the GATT client, so an immediate reconnect to the same
  MAC no longer races the teardown into `status=133`.
- Cancelling a nav-status poll (leaving the map) is no longer
  reported as a BLE failure.
- The AGPS download and the GPS fix are resolved before the device
  link is taken instead of during it.
- **Sharing a GPX into the app actually uploads it.** The import
  screen parsed and previewed the file but had no way to send it; it
  now has an Upload button wired to the pipeline, with in-place
  progress and error states. (@mbeutelspacher, #1)
- **Scan results keep the device name after unpairing.** The last
  known label per MAC is persisted separately from the pairing slot,
  and the scanner now reads the advertisement's local name rather
  than relying on the OS name cache, so a just-removed device shows
  up by name instead of as a bare MAC address.

### Changed

- **Map screen now uses a Google-Maps-style multi-stop sheet** in
  place of the single destination search bar. With no route in
  progress the top shows one "Where to?" row; once a destination is
  set it expands into Start → vias → Destination, each row tappable
  to swap into inline Photon autocomplete. Start is now searchable
  (previously drag-only) and shows the reverse-geocoded place name
  instead of just "Your location" when the user picks a custom
  origin or drags the start marker.
- **Tapping the map after a destination is set** now opens a small
  popup anchored to the tap pixel with two actions —
  "Set as destination" / "Add stop" — instead of silently replacing
  the destination and wiping every existing intermediate stop. The
  first tap with no destination still picks one directly.
  Long-press remains a quick shortcut for "add a stop" (matches
  existing muscle memory) and now reverse-geocodes the added
  waypoint so its top-bar row shows the nearest place name.
- **Intermediate stops are now reorderable** by long-pressing any
  via row and dragging it through the list — the separate drag handle
  is gone. Each row also has an X button for removal; tapping the
  marker on the map no longer removes the stop, since stray taps used
  to drop stops by accident.
- **Vias reverse-geocode like start and destination** instead of
  reading "Stop N". Lookups are tracked per via, so dragging one
  doesn't fire a duplicate request for another still in flight.
- **The via glyph matches the other leading icons** in the stops
  column. The previous filled circle made vias read as a different
  kind of element from start / add / destination when all four are
  peers.
- **Map tiles survive between launches.** The osmdroid cache moved
  from `cacheDir` — which Android evicts under storage pressure, so
  the map appeared to re-download on every launch — to `filesDir`
  with a 300 MiB ceiling, and a 30-day TTL override replaces the
  ~1-day `max-age` Mapnik returns. The in-RAM bitmap cache goes from
  the ~9-tile default to 250, removing re-decode jank when panning
  within a recently-loaded area.
- Route distance is parsed once per planned GPX rather than on every
  recomposition.
- **AGPS data is only re-seeded when it has actually gone stale.**
  Every upload used to download a fresh AssistNow payload and push it
  over BLE first, which dominated the time an upload took — and bought
  nothing, since u-blox ephemeris data stays usable for hours. The
  last successful seed is now recorded per device (`AgpsSeedStore`)
  and both halves are skipped while it is younger than two hours.
  Only a payload the device accepted counts, so a rejected or dropped
  transfer still retries on the next upload; the record survives
  unpairing like the device nickname does; and a fan-out to several
  devices now downloads the payload once instead of once per device.
  Settings shows each device's last-seed time and remaining validity,
  with a **Seed now** button that ignores the freshness check for the
  case where a computer still refuses to get a fix.

## [1.2.0] — 2026-05-17

Recorded-activities management on top of v1.1.0's route editing,
plus two map-screen regression fixes that turned out to be very
visible on real hardware: the auto-planner thrashing on every GPS
heartbeat, and the upload button lying about whether the route on
screen had actually been sent.

### Added

- **Activities sub-section in Settings** — list, download (FIT),
  delete recorded activities from the BSC200. Delete-all is guarded
  by a confirmation dialog. Backed by new `FileTransfer`
  primitives: `listActivities` / `downloadActivity` /
  `deleteActivity` / `deleteAllActivities`.
- **adb harness actions** for the same operations:
  `LIST_ACTIVITIES`, `DOWNLOAD_ACTIVITY`, `DELETE_ACTIVITY`,
  `DELETE_ALL_ACTIVITIES`.

### Changed

- **Settings: Routes-on-device moved into its own sub-screen**,
  alongside the new Activities one. The main Settings list stays
  short; both device-files screens are reachable via tappable rows.

### Fixed

- **Auto-planner thrashed forever on stationary GPS jitter.** The
  location overlay pushes a fresh `Point` every 2 s even when the
  device is sitting still; the auto-plan `LaunchedEffect` keyed off
  `currentLocation` directly, so every drift cancelled the in-flight
  plan and started a new one. The status pill flickered between
  *Planning…* and *Route ready* indefinitely and the user could
  never tap Upload. Auto-plan is now extracted into an
  `AutoPlanEffect` composable keyed on a derived `hasInitialFix:
  Boolean` (null → non-null), so the first fix fires one plan and
  subsequent drift stays inert. Re-planning still happens on
  destination / via / start-override edits — and the effect body
  reads the latest `currentLocation` when it fires, so picking a
  destination after the GPS stabilises still routes from where the
  user is right now.
- **Upload button stuck on "Uploaded ✓" after editing the route.**
  Dragging an intermediate or the Start marker after a successful
  upload changed the planned GPX on screen but the button still
  read green — the user couldn't tell their new route hadn't been
  sent. Two fixes: a `RouteEditUploadReset` composable flips
  `Success` / `Failed` back to `Idle` on any route-input change
  (destination, vias, start override) without clobbering an
  in-flight `Uploading`; and the upload completion handler now
  snapshots the *full* route (destination + vias + start override)
  and surfaces `Idle` instead of `Success` if any of those changed
  while the BLE round-trip was in flight. Pinned by
  `MapScreenEffectsTest` (9 regression cases).

[1.2.0]: https://github.com/makefu/liGPSPORT-android/releases/tag/v1.2.0

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
