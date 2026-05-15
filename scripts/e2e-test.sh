#!/usr/bin/env bash
# End-to-end test against a real iGPSPORT device, driven entirely via
# adb. Builds + installs the APK, grants runtime permissions, pairs
# with the first iGPSPORT BLE device found, then exercises each of
# the three routing backends in turn:
#     SET_ROUTER → MOCK_LOCATION → PLAN_AND_UPLOAD (no start coords
#     — the service resolves "current location" from MockLocationStore
#     or FusedLocationProvider) → DELETE_ROUTE
# Preflight DELETE_ALL_ROUTES wipes any leftover routes from prior
# runs. Exits 0 only on a complete pass.
#
# Required adb permissions/state on the phone:
#   - USB debugging on, host authorised
#   - Bluetooth enabled (script attempts `svc bluetooth enable` if off)
#   - iGPSPORT device powered on and in range
#
# Configure via env:
#   LIGPSPORT_SKIP_BUILD=1    — reuse last APK
#   LIGPSPORT_ROUTERS="..."   — space-separated router ids to test
#                               (default: "straightline brouter osrm")
#   LIGPSPORT_NO_CLEANUP=1    — skip preflight DELETE_ALL_ROUTES
#
# Destination is fixed at 48.8339°N, 9.2293°E (Esslingen near Stuttgart);
# mock start at 48.7700°N, 9.1800°E (central Stuttgart).

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="de.syntaxfehler.ligpsport.debug"
RECEIVER="${PKG}/de.syntaxfehler.ligpsport.cli.AdbCliReceiver"
ACTIVITY="${PKG}/de.syntaxfehler.ligpsport.MainActivity"
TAG="LigpsportAdb"

# Hardcoded geometry for the test (cyclable in the real world).
START_LAT="48.7700"
START_LON="9.1800"
END_LAT="48.8339"
END_LON="9.2293"

ROUTERS="${LIGPSPORT_ROUTERS:-straightline brouter osrm}"

mkdir -p "${REPO_ROOT}/tmp"
LOG_FILE="${REPO_ROOT}/tmp/e2e-$(date +%Y%m%dT%H%M%S).log"

# ---- adb helpers -------------------------------------------------------

require_one_device() {
    local devs
    devs=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
    local count
    count=$(printf '%s\n' "$devs" | grep -c . || true)
    if [ "$count" -ne 1 ]; then
        echo "ERROR: expected exactly 1 connected adb device, found $count" >&2
        echo "$devs" >&2
        exit 2
    fi
    echo "==> using device $devs"
}

start_logcat() {
    adb logcat -T 1 -s "${TAG}:I" >"$LOG_FILE" &
    LOGCAT_PID=$!
    sleep 0.5
}

stop_logcat() {
    if [ -n "${LOGCAT_PID:-}" ]; then
        kill "$LOGCAT_PID" 2>/dev/null || true
        wait "$LOGCAT_PID" 2>/dev/null || true
    fi
}

cleanup() {
    stop_logcat
}
trap cleanup EXIT

# broadcast_and_wait <ACTION> <TIMEOUT_S> [extra-am-args...]
# Echoes the matched RESULT line on success.
# Sets STATUS_FIELD=OK|FAIL global on return; LAST_LINE = the matched line.
# Returns 0 on OK, 1 on FAIL, 124 on timeout.
broadcast_and_wait() {
    local action="$1"; shift
    local timeout_s="$1"; shift
    local req_id="$RANDOM-$RANDOM"
    local actstr="de.syntaxfehler.ligpsport.action.${action}"
    echo "==> ${action} req_id=${req_id}"
    # --receiver-foreground asks the system to schedule the broadcast
    # with foreground priority. Crucially, it also flips the calling
    # context into a state where the receiver's `startForegroundService`
    # is permitted under Android 14's `mAllowStartForeground` rule even
    # when the launching Activity isn't strictly RESUMED. Without this,
    # the second or third FGS-bound action in a session fails with
    # `mAllowStartForeground=false`.
    adb shell am broadcast \
        --receiver-foreground \
        -n "$RECEIVER" \
        -a "$actstr" \
        --es req_id "$req_id" \
        "$@" >/dev/null

    local deadline=$(( $(date +%s) + timeout_s ))
    local line=""
    while [ "$(date +%s)" -lt "$deadline" ]; do
        line=$(grep -E "RESULT action=${action}.*req_id=${req_id}([[:space:]]|$)" "$LOG_FILE" 2>/dev/null | tail -n 1 || true)
        if [ -n "$line" ]; then
            echo "    ${line#*${TAG}: }"
            if [[ "$line" == *" status=OK"* ]]; then
                STATUS_FIELD=OK
                LAST_LINE="$line"
                return 0
            elif [[ "$line" == *" status=FAIL"* ]]; then
                STATUS_FIELD=FAIL
                LAST_LINE="$line"
                return 1
            fi
        fi
        sleep 0.5
    done
    STATUS_FIELD=TIMEOUT
    echo "    !! TIMEOUT waiting for ${action} after ${timeout_s}s" >&2
    return 124
}

field() {
    # Match `key=...` only when `key` is preceded by whitespace or the
    # start of the buffer — otherwise `field "id"` would happily
    # collide with the `id` suffix inside `req_id=...` etc.
    local key="$1"
    if [[ "$LAST_LINE" =~ (^|[[:space:]])${key}=([^[:space:]]+) ]]; then
        echo "${BASH_REMATCH[2]}"
    fi
}

# ---- main --------------------------------------------------------------

require_one_device

if [ "${LIGPSPORT_SKIP_BUILD:-0}" != "1" ]; then
    echo "==> building debug APK"
    (cd "$REPO_ROOT" && nix run .#build-debug)
fi

APK="${REPO_ROOT}/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK" ]; then
    echo "ERROR: APK not found at $APK" >&2
    exit 2
fi
echo "==> installing $APK"
adb install -r "$APK" >/dev/null

echo "==> granting runtime permissions"
for perm in \
    android.permission.BLUETOOTH_SCAN \
    android.permission.BLUETOOTH_CONNECT \
    android.permission.ACCESS_FINE_LOCATION \
    android.permission.ACCESS_COARSE_LOCATION ; do
    adb shell pm grant "$PKG" "$perm" 2>/dev/null || true
done

BT=$(adb shell settings get global bluetooth_on | tr -d '\r' || echo 0)
if [ "$BT" != "1" ]; then
    echo "==> Bluetooth is off, attempting to enable"
    adb shell svc bluetooth enable >/dev/null 2>&1 || true
    sleep 2
    BT=$(adb shell settings get global bluetooth_on | tr -d '\r' || echo 0)
    if [ "$BT" != "1" ]; then
        echo "ERROR: could not enable Bluetooth on the device" >&2
        echo "       enable it once via the phone's quick-settings panel and retry." >&2
        exit 2
    fi
fi

echo "==> launching MainActivity (warms permission state)"
adb shell am start -n "$ACTIVITY" >/dev/null
sleep 1

start_logcat

# 1. Ensure we have a paired device. SharedPreferences persist across
#    app upgrades and runs, so on the second+ run we expect STATUS to
#    return a non-empty paired_mac and we can skip the BLE scan
#    entirely — saves ~10 s and avoids racing the user's BSC200 with
#    other adverts. Force a fresh pair by setting LIGPSPORT_FORCE_PAIR=1.
broadcast_and_wait STATUS 5 || true
EXISTING_MAC=$(field "paired_mac")
if [ "${LIGPSPORT_FORCE_PAIR:-0}" = "1" ] || [ -z "$EXISTING_MAC" ] || [ "$EXISTING_MAC" = "null" ]; then
    broadcast_and_wait PAIR 30
    MAC=$(field "mac")
    NAME=$(field "name")
    echo "    paired: name=${NAME:-?} mac=${MAC:-?}"
else
    NAME=$(field "paired_name")
    MAC="$EXISTING_MAC"
    echo "    reusing existing pairing: name=${NAME:-?} mac=$MAC"
fi

# 2. Preflight: nuke any leftover routes from prior failed runs
#    (e.g., the "e2e" route the user mentioned). Tolerate any error —
#    the device may legitimately have nothing to delete.
if [ "${LIGPSPORT_NO_CLEANUP:-0}" != "1" ]; then
    set +e
    broadcast_and_wait DELETE_ALL_ROUTES 30 --es confirm true
    cleanup_rc=$?
    set -e
    if [ "$cleanup_rc" -ne 0 ]; then
        echo "    (cleanup non-fatal — continuing)"
    fi
fi

# 2b. Standalone AGPS seed. AgpsClient auto-fetches the token from
#     iGPSport's prod config endpoint when LIGPSPORT_AGPS_TOKEN is
#     unset, so this works out of the box. Skipped via
#     LIGPSPORT_TEST_AGPS=0 if you want to save the FGS quota for
#     route uploads only.
if [ "${LIGPSPORT_TEST_AGPS:-1}" = "1" ]; then
    set +e
    broadcast_and_wait SEND_AGPS 60
    agps_rc=$?
    set -e
    if [ "$agps_rc" -ne 0 ]; then
        echo "    (SEND_AGPS non-fatal — continuing)"
    fi
fi

# 2c. Standalone SEND_LOCATION test. Uses the mock location set
#     below (or whatever MockLocationStore currently holds). The
#     FACTORY GPS_COORDINATE_SET wire path is independent of AGPS —
#     this is a fast, network-free sanity check that the device
#     accepts position priors.
if [ "${LIGPSPORT_TEST_LOCATION:-1}" = "1" ]; then
    # Seed a mock location first so resolveCurrentLocation has
    # something to return on a headless test rig with no real fix.
    broadcast_and_wait MOCK_LOCATION 5 --ef lat "$START_LAT" --ef lon "$START_LON" >/dev/null
    set +e
    broadcast_and_wait SEND_LOCATION 15
    loc_rc=$?
    set -e
    if [ "$loc_rc" -ne 0 ]; then
        echo "    (SEND_LOCATION non-fatal — continuing)"
    fi
fi

# 3. Sanity: enumerate routers and check we have at least 3.
broadcast_and_wait LIST_ROUTERS 5
COUNT=$(field "count")
if [ -z "$COUNT" ] || [ "$COUNT" -lt 3 ]; then
    echo "ERROR: expected >=3 routers, got ${COUNT:-?}" >&2
    exit 3
fi

# 4. For each router: SET_ROUTER → MOCK_LOCATION → PLAN_AND_UPLOAD → DELETE_ROUTE.
i=0
for ROUTER_ID in $ROUTERS; do
    echo
    echo "============================================="
    echo "  Router: $ROUTER_ID"
    echo "============================================="

    broadcast_and_wait SET_ROUTER 5 --es id "$ROUTER_ID"
    if [ "$(field "id")" != "$ROUTER_ID" ]; then
        echo "ERROR: SET_ROUTER didn't echo expected id=$ROUTER_ID" >&2
        exit 4
    fi

    broadcast_and_wait MOCK_LOCATION 5 --ef lat "$START_LAT" --ef lon "$START_LON"

    FILE_ID=$(( $(date +%s) + i ))
    i=$(( i + 1 ))
    echo "    file_id=$FILE_ID"

    # No start_lat / start_lon → service uses MockLocationStore.
    # 240s leaves headroom for the larger CNX uploads that real
    # routers produce (BRouter trekking ~10km ≈ 30-60 KB CNX).
    broadcast_and_wait PLAN_AND_UPLOAD 240 \
        --ef end_lat "$END_LAT" \
        --ef end_lon "$END_LON" \
        --el file_id "$FILE_ID" \
        --es name "e2e-$ROUTER_ID"

    PROVIDER=$(field "provider")
    if [ "$PROVIDER" != "$ROUTER_ID" ]; then
        echo "WARN: PLAN_AND_UPLOAD used provider=$PROVIDER (expected $ROUTER_ID)" >&2
    fi

    broadcast_and_wait DELETE_ROUTE 30 --el file_id "$FILE_ID" --es ext cnx
done

# 5. DELETE_ALL_ROUTES verification. Upload two routes, then issue
#    DELETE_ALL_ROUTES and confirm the inactive route is actually gone
#    from the device (LIST_GET no longer returns its id). PROTOCOL.md
#    §7.4 firmware-protects the *active* route — the last upload's
#    auto-FILE_USE makes that one stick around, but the earlier upload
#    is the canonical "inactive route" the FILES_DEL wipe is supposed
#    to clear.
if [ "${LIGPSPORT_TEST_DELETE_ALL:-1}" = "1" ]; then
    echo
    echo "============================================="
    echo "  DELETE_ALL_ROUTES verification"
    echo "============================================="

    # Use whichever router was tested last — it's already configured.
    broadcast_and_wait MOCK_LOCATION 5 --ef lat "$START_LAT" --ef lon "$START_LON"

    DA_FID1=$(( $(date +%s) + 100 ))
    DA_FID2=$(( DA_FID1 + 1 ))
    echo "    seeding 2 routes for delete-all test (ids=$DA_FID1, $DA_FID2)"

    broadcast_and_wait PLAN_AND_UPLOAD 240 \
        --ef end_lat "$END_LAT" \
        --ef end_lon "$END_LON" \
        --el file_id "$DA_FID1" \
        --es name "del-all-1"
    broadcast_and_wait PLAN_AND_UPLOAD 240 \
        --ef end_lat "$END_LAT" \
        --ef end_lon "$END_LON" \
        --el file_id "$DA_FID2" \
        --es name "del-all-2"

    # Sanity: both routes show up in LIST_GET. The BSC200 returns an
    # empty list when the request omits the index range — we depend
    # on the v1.2.0 LIST_GET wire format here.
    broadcast_and_wait LIST_ROUTES 15
    LIST_LINE="$LAST_LINE"
    if [[ "$LIST_LINE" != *"$DA_FID1"* ]] || [[ "$LIST_LINE" != *"$DA_FID2"* ]]; then
        echo "ERROR: LIST_ROUTES missing seeded ids ($DA_FID1 and/or $DA_FID2)" >&2
        echo "       line: $LIST_LINE" >&2
        exit 5
    fi
    echo "    seeded ids visible in LIST_GET ✓"

    # Wipe.
    broadcast_and_wait DELETE_ALL_ROUTES 30 --es confirm true

    # Verify the *inactive* route (DA_FID1) is gone. The active route
    # (DA_FID2 — last upload's FILE_USE made it active) is firmware-
    # protected; FILES_DEL acks status=0 but the route stays.
    broadcast_and_wait LIST_ROUTES 15
    LIST_AFTER="$LAST_LINE"
    if [[ "$LIST_AFTER" == *"$DA_FID1"* ]]; then
        echo "ERROR: inactive route $DA_FID1 still present after DELETE_ALL_ROUTES" >&2
        echo "       line: $LIST_AFTER" >&2
        exit 6
    fi
    echo "    inactive route $DA_FID1 gone ✓"
    if [[ "$LIST_AFTER" == *"$DA_FID2"* ]]; then
        echo "    active route $DA_FID2 retained (firmware-protected; expected) ✓"
    fi
fi

# 6. Activities verification — list, download, delete one. The
#    BSC200 only records activities when the user actually presses
#    Start/Stop on the device; on a freshly-paired test fixture the
#    list may legitimately be empty. We tolerate that — the steps
#    below only run when LIST_ACTIVITIES returns count>0.
echo
echo "============================================="
echo "  Activities verification"
echo "============================================="

broadcast_and_wait LIST_ACTIVITIES 30
ACT_COUNT=$(field "count")
if [ -z "$ACT_COUNT" ] || [ "$ACT_COUNT" = "0" ]; then
    echo "    no activities to verify, skipping"
else
    ACT_TS=$(field "a0_ts")
    if [ -z "$ACT_TS" ]; then
        echo "ERROR: LIST_ACTIVITIES count=$ACT_COUNT but no a0_ts present" >&2
        echo "       line: $LAST_LINE" >&2
        exit 7
    fi
    echo "    first activity ts=$ACT_TS"

    broadcast_and_wait DOWNLOAD_ACTIVITY 120 --el timestamp "$ACT_TS"
    BYTES=$(field "bytes")
    SAVED=$(field "saved_path")
    if [ -z "$BYTES" ] || [ "$BYTES" = "0" ] || [ -z "$SAVED" ]; then
        echo "ERROR: DOWNLOAD_ACTIVITY missing bytes/saved_path (bytes=$BYTES saved=$SAVED)" >&2
        echo "       line: $LAST_LINE" >&2
        exit 8
    fi
    echo "    downloaded ${BYTES}B → ${SAVED}"

    broadcast_and_wait DELETE_ACTIVITY 30 --el timestamp "$ACT_TS"
    DEL_STATUS=$(field "device_status")
    if [ "$DEL_STATUS" != "0" ]; then
        echo "ERROR: DELETE_ACTIVITY status=$DEL_STATUS (expected 0)" >&2
        echo "       line: $LAST_LINE" >&2
        exit 9
    fi
    echo "    deleted ts=$ACT_TS ✓"

    # Verify the timestamp is gone from the next LIST_ACTIVITIES.
    broadcast_and_wait LIST_ACTIVITIES 30
    if [[ "$LAST_LINE" == *" a0_ts=${ACT_TS} "* ]] || \
       [[ "$LAST_LINE" == *" a0_ts=${ACT_TS}"$'\n'* ]] || \
       [[ "$LAST_LINE" == *" a0_ts=${ACT_TS}" ]]; then
        echo "ERROR: timestamp $ACT_TS still present after DELETE_ACTIVITY" >&2
        echo "       line: $LAST_LINE" >&2
        exit 10
    fi
    echo "    ts=$ACT_TS gone from LIST_ACTIVITIES ✓"
fi

# 7. Destructive: wipe every activity. Opt-in via env knob — the user
#    rarely wants their real ride history nuked by a regression run.
if [ "${LIGPSPORT_RUN_DESTRUCTIVE:-0}" = "1" ]; then
    echo "    running destructive DELETE_ALL_ACTIVITIES"
    broadcast_and_wait DELETE_ALL_ACTIVITIES 30 --es confirm true
    broadcast_and_wait LIST_ACTIVITIES 30
    AFTER_COUNT=$(field "count")
    if [ "$AFTER_COUNT" != "0" ]; then
        echo "ERROR: DELETE_ALL_ACTIVITIES left count=$AFTER_COUNT (expected 0)" >&2
        echo "       line: $LAST_LINE" >&2
        exit 11
    fi
    echo "    activity list empty ✓"
fi

# Note: we deliberately do NOT broadcast UNPAIR here. The pairing is
# persisted in SharedPreferences and the user expects it to survive
# across e2e runs (and across app restarts). To wipe it explicitly,
# run `adb shell am broadcast -a de.syntaxfehler.ligpsport.action.UNPAIR ...`
# manually, or set LIGPSPORT_FORCE_PAIR=1 on the next run.

stop_logcat

echo
echo "============================================="
echo "PASS — all $(echo "$ROUTERS" | wc -w) routers"
echo "log: $LOG_FILE"
echo "============================================="
