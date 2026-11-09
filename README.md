# OMH Mini App — Phase 1 device proof

A bare native Android test app that proves the device approach for the
Oluwatodupefun Maternity Home mini attendance app on the facility phone
(Itel P37, Android 10 Go, 2 GB RAM).

It does exactly two things, nothing else:

1. **Front camera selfie** — modest resolution, with a retake option,
   and the captured photo shown on screen.
2. **Back camera QR scan** — ZXing core (pure Java, no Google Play
   services, works offline), and the scanned value shown on screen.

No login, no splash delay, no roster, no backend, no handover yet.
Those come in later phases.

## Why native, not Expo

The full OMH app uses a heavy Expo stack and has an unresolved
instant-close crash on an Itel A60s. This test app is a lightweight
native Kotlin build with one library (ZXing core) and no AppCompat,
so the APK should stay well under 10 MB.

- `minSdk 21` (Android 5.0) so it also covers older Android versions
- `targetSdk 34`, Kotlin, AGP 8.5.2, Gradle 8.7, Java 17

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
3. Tap the APK to install it. If an older test build is already
   installed, uninstall it first: each CI run signs with a fresh
   debug key, so an update install can be refused.
4. Open **OMH Mini Test** and allow the camera permission.

## What to test on the phone

1. **Install** — it installs without an error.
2. **Open speed** — it opens straight to the selfie screen, no long
   splash or wait.
3. **Front selfie** — tap "Take selfie (front camera)". The photo
   appears. Tap "Retake selfie" and take another one.
4. **Back QR scan** — tap "Continue to QR scan (back camera)" and
   point it at any QR code (for example the facility attendance QR).
   The scanned value appears on screen.
5. **No crash** — going through selfie → QR → scan again does not
   close the app. Note the time from tap to open, and anything that
   feels slow or wrong.
