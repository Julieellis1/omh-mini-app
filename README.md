# OMH Mini App — attendance (Phase 3)

A tiny native Android app for the Oluwatodupefun Maternity Home
facility phone (Itel P37, Android 10 Go, 2 GB RAM). Two jobs only:
attendance sign-in/out and, from Phase 4, handovers. It uses the
same OMH database as the full clinic app, through the `/api/mini/*`
endpoints.

## What it does (Phase 3)

1. **First-run setup** (super admin, one time): server address
   (default `https://omh.teta.dpdns.org`) and the kiosk token
   (`MINI_KIOSK_TOKEN`, set in Dokploy). Stored only on the phone,
   never in the repo, never logged.
2. **Roster name list**: today's roster only, downloaded 5 days ahead
   whenever the phone has internet. Staff not rostered today never
   appear. Works fully offline from the downloaded roster.
3. **Sign-in**: pick a name, see your duty and the people on it,
   front-camera selfie (640x480 JPEG 70, one retake), back-camera QR
   scan of the facility code, confirmation with your name and the
   real time.
4. **Sign-out**: same steps minus the selfie.
5. **Offline queue**: every record is stored on the phone in order
   with its selfie and its real sign-in time, and syncs when internet
   returns. The home screen shows how many records are waiting.
   Each record carries a unique `clientRef`, so a retry can never
   create a duplicate on the server. Once the server confirms
   receipt, the local copy (and the selfie) is deleted to save space.
   A record the server refuses for good is marked failed with the
   reason instead of blocking the queue.

The scanned QR value is never shown on screen and never logged:
it is the facility secret.

## Why native, not Expo

The full OMH app uses a heavy Expo stack and has an unresolved
instant-close crash on an Itel A60s. This app is a lightweight
native Kotlin build with one library (ZXing core, pure Java QR
decoding, no Google Play services), so the APK stays tiny.

- `minSdk 21` (Android 5.0) so it also covers older Android versions
- `targetSdk 34`, Kotlin, AGP 8.5.2, Gradle 8.7, Java 17
- Signing: a pinned debug keystore (`app/debug.keystore`, the
  standard Android debug identity, password "android") so every CI
  build shares one signature and installs as an update. The Phase 1
  test APK was signed with a one-off CI key, so uninstall that test
  build once before installing 0.2.0.

## Build (CI)

GitHub Actions builds the debug APK on every push to `main`
(`.github/workflows/build-test-apk.yml`) and uploads it as the
`omh-mini-test-apk` artifact (kept 7 days). Download it from the
Actions run.

The project ships no Gradle wrapper: CI installs Gradle 8.7 via
`gradle/actions/setup-gradle`. To build locally (not in this sandbox,
where the Gradle daemon IPC is broken) install JDK 17 and Gradle 8.7
and run `gradle assembleDebug`.

## Install on the Itel P37

1. On the phone, open Settings → Security and allow "Install unknown
   apps" for the browser or file manager you will use.
2. Download `app-debug.apk` onto the phone (or copy it over USB).
3. Tap the APK to install it. **Uninstall the Phase 1 "OMH Mini
   Test" build first** — it was signed with a different key, so an
   update install will be refused.
4. Open **OMH Mini**, allow the camera permission, and on first run
   enter the server address and the kiosk token. The roster
   downloads right away if the phone has internet.

## Acceptance test (Phase 3)

1. With the phone offline, three rostered staff sign in, one after
   another. Each confirmation shows their name and the real sign-in
   time. The home screen shows "3 record(s) waiting to sync."
2. Turn the internet back on (or tap Sync now). The count drops to
   zero.
3. On the server, exactly three attendance records appear for today,
   once each, with the real sign-in times — not the sync time — and
   each has its selfie in the web admin selfie view.
