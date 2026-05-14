# AGPS token

The `BuildConfig.AGPS_TOKEN` constant compiled into the APK is a
**u-blox AssistNow Online developer token**. It authorises HTTP GETs
to u-blox's AssistNow Online endpoint, which returns ~2.5 KB of
UBX-MGA assistance frames that the BSC200's GNSS chip ingests to
drop cold-start TTFF from ~30–90 s to ~5–10 s.

The token used by the released APK is the same one the official
iGPSPORT app uses. The official app doesn't ship it as a hardcoded
constant — it fetches it from the iGPSPORT backend at startup.
`AgpsClient.kt` either uses an explicit override
(`BuildConfig.AGPS_TOKEN`, set via the `LIGPSPORT_AGPS_TOKEN` env var
at build time) or replicates the official runtime fetch.

## Usage

### Default: nothing to do

The shipped release APK already has a valid token baked in, and the
runtime auto-fetch fallback works too. Plug in a paired BSC200,
upload a route, and AGPS happens silently as a piggyback on every
upload. Look for `agps_bytes=<N>` on the RESULT line:

```
RESULT action=PLAN_AND_UPLOAD … agps_bytes=2464 cnx_bytes=… device_status=0
```

A standalone health-check broadcast is also available:

```sh
adb shell am broadcast \
  -n de.syntaxfehler.ligpsport.debug/de.syntaxfehler.ligpsport.cli.AdbCliReceiver \
  -a de.syntaxfehler.ligpsport.action.SEND_AGPS \
  --es req_id "$RANDOM"
adb logcat -s LigpsportAdb:I | grep SEND_AGPS
```

### Bake your own u-blox token into the build

If you have your own AssistNow developer token (free from u-blox at
<https://www.u-blox.com/en/assistnow-service-evaluation-token-request>),
or one recovered from the official APK by the procedure below, the
build will pick it up from one of two sources, in order:

1. **`app/agps.properties`** — gitignored, the primary place for a
   developer or CI machine to keep a persistent token. Format is a
   single-line java-properties file:
   ```properties
   token=YOUR-TOKEN-HERE
   ```
   See `app/agps.properties.example` for the template; copy it to
   `app/agps.properties` and fill in the value.
2. **`LIGPSPORT_AGPS_TOKEN`** env var — for one-off CI builds or
   when you don't want the value on disk at all:
   ```sh
   LIGPSPORT_AGPS_TOKEN=<your-token> nix run .#build-release
   ```

Either way the string is written into
`app/build/generated/source/buildConfig/release/.../BuildConfig.java`
as `public static final String AGPS_TOKEN = "…"` and used directly
by `AgpsClient.fetchOnline`.

Empty token = the runtime fetch path is used. Both paths produce the
same wire output to u-blox.

## How to find the endpoint URLs yourself

The exact URLs (u-blox AssistNow + iGPSPORT config endpoint) are not
printed in this doc on purpose — the only durable source is the
decompiled APK. Both URLs are easy to recover (as of 2026-05-15).

### Prerequisites

1. The official iGPSPORT APK
   (`iGPSPORT_*.apk` — APKPure has the global flavour).
2. A smali dump (`apktool d <apk> -o smali-out/`) or, equivalently,
   the dex-to-Java decompilation from `jadx`.

### Step 1 — find the u-blox AssistNow URL

The string appears verbatim in the smali of the BLE manager that
issues the request:

```sh
rg -i 'u-?blox' smali-out/
```

The hits cluster in `…/DeviceBleManagerHandler.smali` around the
`sendAGPS` / `sendOfflineAGPS` methods — those are the canonical
u-blox endpoints. The online (live ephemeris) and offline (multi-day
almanac) URLs are right next to each other; they only differ in the
path component.

### Step 2 — find the iGPSPORT config endpoint

The official app fetches the AGPS token (and several other
per-environment URLs) from its own backend via a single retrofit
interface. Find the interface, the path, and the base URL in turn:

```sh
# 1. Locate the small utility class that maps a key like "AGPS"
#    to a backend URL request. The keys are public static int
#    fields on this class — the AGPS one is the lowest-numbered.
rg 'class.*GetUrlByTypeUtil[^$]*$' smali-out/

# 2. From that class, follow `getUrl(I)` to the retrofit interface
#    method it calls. `NewApiService.smali` then has the @GET
#    annotation with the relative path and any @Header parameters
#    (or lack thereof — that's how you know the endpoint is open).
rg 'class.*NewApiService[^$]*$' smali-out/

# 3. Walk back from the Retrofit builder to the base URL. Every
#    candidate base URL the app knows about lives in:
rg 'const-string.*"https://' smali-out/**/Constants.smali
```

`GetUrlByTypeUtil.smali` declares the integer keys (AGPS,
OFFLINE_TOKEN, ROUTE, ACTIVITY, ANNOUNCEMENT, MANUAL,
COMMON_PROBLEM, WEB_LOGIN_STRAVA, `user_terms`, `privacy_statement`).
Each maps to the same backend endpoint with a different `?type=N`
query parameter.

`Constants.smali` lists every base URL the app knows about — pick
the one matching your APK's flavour (search for the `app_*Release`
tag in any `Lkotlin/Metadata;` annotation in the smali dump; the
global APKPure build is `app_globalRelease`).

### Step 3 — fetch and verify

Combine the base URL with the path from `NewApiService.smali` and
the right `?type=N` value from `GetUrlByTypeUtil.smali`, then `curl`
it. The endpoint is **unauthenticated** at the time of writing — no
`@Header(...)` parameters appear on this method in `NewApiService` —
so a plain `curl -sS <base-url><path>?type=<key>` returns a JSON
envelope whose `data` field is the entire `?token=…` suffix to
append to the u-blox URL from Step 1.

Verify the recovered string by GETting the u-blox URL with it. A
working token responds with `Content-Type: application/ubx` and a
body starting `b5 62 13 40` (UBX sync chars + MGA class + INI-TIME_UTC
ID). An expired or invalid token typically responds 403 with an
empty body.

## How the recovery trace was done

For future agents who need to recover any of the other server-issued
secrets the iGPSPORT app fetches (Strava OAuth, Aliyun OSS bucket
credentials, offline-AGPS URL, etc.) — the recipe generalises. The
endpoints are keyed by the small `GetUrlByTypeUtil` integers above.

1. **Trace the consumer back to the source.** Start at the call site
   that uses the secret. For AGPS that's `DeviceBleManagerHandler`
   in `classes5.dex` — it ends up calling
   `UserIdentity().getDefaultConfig().getAgpsToken()`.
2. **Follow the disk-cache layer.** `UserIdentity.getDefaultConfig`
   reads `<filesDir>/default_config.json` and deserialises via Gson.
   The companion `setDefaultConfig` writes the same file. Search for
   `setDefaultConfig` to find the producer.
3. **Follow the producer to the network call.** The only writer is
   `MainActivity$getDefaultConfig$1.invokeSuspend` (classes5), which
   calls `GetUrlByTypeUtil.Companion.getUrl(<key>, …)`.
4. **Read the retrofit interface.** `getUrl` ends up at one of
   `NewApiService`'s `@GET`-annotated methods. Check for
   `@Header("Authorization")` / `@Header("X-Token")` annotations on
   the method parameters. There are none on the AGPS config endpoint
   at present — that's why the runtime auto-fetch works without any
   device session token.
5. **Find the base URL.** Same `Constants.smali` lookup as in Step 2
   above. Pick the URL matching the build flavour you decompiled.
6. **`curl` the endpoint.** Returns the secret directly. Verify by
   feeding it to whichever downstream service consumes it.

If a future iGPSport release moves these behind auth, `NewApiService`
will gain `@Header`-annotated parameters and the recipe needs the
bonded device's session token from the BLE handshake (which is its
own investigation, currently not exercised by `ligpsport-android`).

## See also

- `/home/makefu/r/ligpsport/docs/PROTOCOL.md` §12 — the AGPS wire
  shape from the device's side (`file_type=AGPS(7)`, payload
  conventions, the BSC200's unsolicited request frame).
- `app/src/main/kotlin/de/syntaxfehler/ligpsport/agps/AgpsClient.kt`
  — the implementation of both paths (explicit-token override and
  the runtime auto-fetch). Both URLs live in this file as
  `companion object` constants; that's the durable source of truth
  alongside the decompiled APK.
- `app/src/main/kotlin/de/syntaxfehler/ligpsport/ble/UploadPipeline.kt#uploadAgpsBestEffort`
  — the piggyback that fires AGPS before every route upload.
