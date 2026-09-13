# Strava upload

Sends a recorded activity straight from the BSC200 to Strava, without
a computer in the middle.

Strava's Android app cannot receive a shared FIT file — its only
`ACTION_SEND` filters are `text/plain` and `image/*` — so the share
sheet is a dead end for this and the v3 API is the only route.

The feature is **off by default** and lives entirely in
**Settings → Strava**. Turn the switch on, paste three values, and a
cloud-upload button appears on every row under *Activities on device*.
Leave it off and nothing about the app changes.

## Why you have to bring your own credentials

The upload API needs OAuth credentials, and Strava requires
`client_secret` for the token refresh with no PKCE alternative. In a
native app the secret can only live where the app can read it, which is
also where anyone with the phone can read it. That's acceptable for a
personal setup and unacceptable for a shared one — so this repo ships
no credentials and expects you to register your own API application.

## Setup

The step-by-step walkthrough — registering the API application,
minting a write-scoped refresh token, and entering the three values —
lives in the README under **Enabling Strava upload**. It is the one
place it should be written down; the rest of this document is the
reasoning behind it and the parts of the API that surprise people.

### Optional: bake the credentials into your own build

For a personal build you can skip the typing. Values found at build
time pre-fill the Settings fields on first run and switch the feature
on; they are only defaults, and editing or forgetting them in Settings
wins from then on.

```sh
cp app/strava.properties.example app/strava.properties
# then fill in:
#   client_id=12345
#   client_secret=…
#   refresh_token=…        # optional, per-athlete
```

`LIGPSPORT_STRAVA_CLIENT_ID`, `LIGPSPORT_STRAVA_CLIENT_SECRET` and
`LIGPSPORT_STRAVA_REFRESH_TOKEN` env vars work too, for CI. The file is
gitignored; the secret still ends up readable inside the APK, so don't
hand that build to anyone.

## Visibility: what the API can and can't do

Uploads are muted from followers' feeds (`hide_from_home` on
`PUT /activities/{id}`), so they raise no notifications.

**This is not the same as private.** Strava's three-tier visibility
(Everyone / Followers / Only You) is not settable through the API at
all — the old `private` upload flag is defunct and silently ignored. A
muted activity is still reachable at its URL and in your profile,
according to your account's default activity visibility.

If you want uploads to be genuinely private, set the account default
once, on Strava: **Settings → Privacy Controls → Activities → Only
You**. Uploads inherit it. Note it does not apply retroactively.

## How an upload flows

```
Settings → Activities on device → ☁ button
   │
   ├─→ FIT already downloaded?  → reuse it
   │   else UploadPipeline.downloadActivity (BLE, tens of seconds)
   ├─→ access token expired?    → StravaClient.refresh (rotates the
   │                               refresh token; persist the response)
   ├─→ POST /api/v3/uploads     (multipart, data_type=fit,
   │                             external_id=<filename>)
   ├─→ poll GET /api/v3/uploads/:id every 1.5 s until `activity_id`
   │   or `error` (Strava's mean processing time is under 2 s;
   │   we give up waiting after 90 s and report "still processing")
   └─→ PUT /api/v3/activities/:id  (hide_from_home=true)
```

`external_id` is the FIT filename, which is derived from the activity
timestamp and therefore stable. That's the duplicate guard: uploading
the same ride twice gets rejected by Strava instead of creating a
twin.

## Tokens

Access tokens last six hours. The app caches one and mints a
replacement from the refresh token when it's within two minutes of
expiry — which is why a bare access token isn't enough to configure,
and all three values are required.

Strava **rotates the refresh token** on every refresh grant, so the
value you pasted is superseded the first time the app talks to Strava.
It's stored in app-private `SharedPreferences` (`ligpsport.strava`),
the same treatment the paired device MAC gets. No encryption — assume
a rooted or shared phone can read it.

**Settings → Strava → Forget credentials** only wipes them locally. To
revoke the grant itself, use <https://www.strava.com/settings/apps>.

## Rate limits

Strava's default is 100 requests per 15 minutes and 1000 per day. One
upload costs roughly 3–5 requests (upload, a few polls, the mute), so
the limit isn't reachable in normal use.
