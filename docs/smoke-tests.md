# Manual smoke-test matrix

The Espresso suite in `app/src/androidTest/` uses a `LoopbackTransport`
plus an in-process `FakeIgpsportSimulator`, so CI can run hermetically.
The following tests need a real BSC200 / iGS device and must be run by hand.

## Plan & upload flow

1. `nix run .#emulator` (or install the APK on a real Android phone with BLE).
2. Pair phone with the iGPSPORT device in system Bluetooth settings.
3. Open the ligpsport app, allow location permission.
4. Tap a destination on the map (~5 km from current location).
5. Tap "Upload route".
6. Expected: route appears in the device's "Saved Routes" menu within ~30 s.

## OsmAnd share flow

1. In OsmAnd, plan or load any track.
2. Tap "Share as → GPX file".
3. Pick "ligpsport" from the share sheet.
4. Confirm the preview, tap upload.
5. Expected: route appears in the iGPSPORT device.

## Edge cases to cover

- Empty GPX (no `<trkpt>`): app should show "GPX contains no <trkpt>".
- BRouter unreachable: app should display routing error, not crash.
- BLE disconnect mid-upload: app should re-try once, then fail gracefully.
- Device with full route storage: device returns status≠0; app should surface
  the error.
