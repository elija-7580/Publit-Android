# Publit for Android

## iOS and Android

Publit supports iOS and Android. **iOS uses Apple Maps; Android uses MapLibre.**

[Download the Android APK (development build)](https://github.com/elija-7580/Publit-Android/releases/tag/native-latest) · [Android source](https://github.com/elija-7580/Publit-Android/tree/native-android) · [iOS source](https://github.com/elija-7580/Publit) · [Website](https://publit.eu)

Android remains in development. APK availability does not imply completed device QA or a Google Play release.

**Public transport, without the noise.** The Android version of [Publit](https://github.com/elija-7580/Publit), the free, open-source transit app built on the open [Transitous](https://transitous.org) routing service. No ads, no account, no tracking.

![Android 8+](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-black)
![target 36](https://img.shields.io/badge/targetSdk-36-black)
![Swift](https://img.shields.io/badge/Swift-SwiftUI%20%E2%86%92%20Compose-orange)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](upstream/LICENSE)

> **Status: debug builds pass; runtime/UI QA is not complete.** Current ARM64 and x86_64 phone APKs
> and the Wear OS debug APK build successfully. The native-services bridge regression tests pass (2/2),
> as do seven live backend smoke checks. Public Overpass category search remains unreliable; failures now
> show a retry state instead of zero results. These are test builds, not a release approval.
> See [the QA report](docs/ANDROID_QA_2026-10-29.md) and [handoff](docs/ANDROID_HANDOFF.md).

## What this is

A 1:1 port of the iOS app (same screens, same Ink & Dot design, same 15 languages), based on iOS commit
[`3bb9c11`](https://github.com/elija-7580/Publit/commit/3bb9c112498d1413f58c3770f31a692cb5e44bd9) (v0.6.0, build 59, see `upstream/IOS_COMMIT`).

It uses [Skip Fuse](https://skip.dev): the iOS app's Swift code is **compiled natively for Android** (Swift 6 Android SDK),
and its SwiftUI views are rendered with **Jetpack Compose** through SkipUI. So the routing, models, caching, live
updates and nearly all views are the same Swift source as on iOS. Where Android differs, the shared files have small
`#if os(Android)` branches; everything Android-only lives in two folders:

| Folder | What |
| --- | --- |
| `Sources/Publit/Android/` | Swift: app entry, SwiftUI shims for what Skip does not bridge (button/label styles, Grid, ContentUnavailableView, styled text, …), Apple API stand-ins (MapKit/CoreLocation types), map, notifications, tickets, OSM places, widget data (`DroidJobs.swift`) |
| `Sources/Publit/Skip/` | Kotlin: `Droid.kt` (system services: location, notifications, TTS, calendar, files, brightness, shortcuts), `PublitMap.kt` (MapLibre), `PublitCompose.kt` (Compose views), `DroidTickets.kt`, `DroidWidget.kt` (Glance widget + stop picker), `DroidBackground.kt` (WorkManager, crash reports, Health Connect), `DroidNotifications.kt`; `skip.yml` (Gradle dependencies) |
| `Android/` | Gradle app project (manifest, `Main.kt` activity, resources, icons) |
| `upstream/` | Reference material from the iOS repo (README, changelog, docs, Watch/Widget/Share sources that are not ported) |

### iOS frameworks and their Android counterparts

| iOS | Android |
| --- | --- |
| MapKit (Apple Maps) | MapLibre with OpenStreetMap vector tiles (OpenFreeMap, Ink & Dot styles, light/dark) |
| Apple Maps points of interest ("Find nearby") | OpenStreetMap via Overpass API; free-text search via the Transitous geocoder |
| CoreLocation | `LocationManager` (fused provider on Android 12+), compass via rotation sensor |
| UserNotifications | `NotificationManager` + exact alarms (departure reminders, alerts) |
| BGAppRefreshTask | WorkManager: saved lines are checked every 30 minutes in the background |
| WidgetKit (departures widget) | Jetpack Glance home screen widget with a stop picker, refreshed every 15 minutes |
| CoreMotion pedometer / Apple Health walking speed | Health Connect: steps and distance of the last 7 days (only after you allow it) |
| MetricKit crash reports | `ApplicationExitInfo` (crashes, ANRs, low memory) + the last uncaught exception, written into the diagnostics |
| Live Activities / Dynamic Island | Ongoing notification for the live trip |
| App Intents / Shortcuts | Launcher shortcuts (home, work, recent trips) |
| VisionKit / Vision (ticket import) | ML Kit text + barcode recognition, ML Kit document scanner, `PdfRenderer`, ZXing |
| PhotosPicker / file importer | Android photo picker / document picker |
| EventKit | Calendar insert intent |
| AVSpeechSynthesizer | `TextToSpeech` |
| Share extension | Share target in the activity (text, links, files) |

### Latest additions and remaining platform differences

- A standalone [Wear OS app](Android/wear) implements nearby departures; its debug build passes. Real-watch testing is still pending.
- Google Wallet export is optional and hidden by default. It needs your issuer account and deployed
  [signing service](services/google-wallet-signer), and asks before sending ticket details. Binary ticket codes stay in Publit.
- Native text selection and pressed feedback are implemented; latest app UI validation is pending.
- Android 16 Live Update promotion is requested for ongoing live trips; the OS/user decides whether it is promoted.
- The departures widget advertises lock-screen support only where the Android device/launcher supports widgets there.
  There is no universal 1:1 replacement for Apple's lock-screen/live-trip widgets.
- Rolling-digit number transitions remain unsupported. Search scopes are a segmented control over the map, not an iOS bar.

## Automated checks

```sh
bash scripts/qa/bridge-regression.sh   # pinned SkipBridge reflection, JVM fixture regression
python3 scripts/qa/osm-regression.py   # real OSM source, local test server/model stubs
python3 scripts/qa/backend-smoke.py    # live APIs, fixed public Vienna coordinates
node --test services/google-wallet-signer/worker.test.mjs
```

The production-class bridge tests live in `Android/app/src/test/kotlin/publit/module/DroidBridgeTest.kt`.
See the QA report for the Gradle command and the limits of these checks.

## Building

### Requirements

- Skip (`skip` CLI) with the Swift 6.4 toolchain and the Swift SDK for Android
- Android SDK + NDK, JDK 21, Gradle 9
- The string catalog from the iOS repo (not duplicated here):

```sh
scripts/fetch-ios-strings.sh      # downloads Localizable.xcstrings of the iOS commit this port is based on
```

### macOS

Open `Project.xcworkspace` in Xcode and run the "Publit App" target, or use `skip android build` / `skip export`.
The Xcode build runs skipstone, which generates the Kotlin/Gradle project from the Swift sources.

### Linux (how the APK is built here)

```sh
scripts/linux/install.sh          # JDK 21, Gradle, Android SDK/NDK, Swift 6.4 + Swift SDK for Android, Skip (idempotent)
scripts/linux/apk.sh debug        # Swift → native .so, skipstone → Kotlin, Gradle → APK (log: /data/apk.log)
GRADLE_EXTRA=bundleRelease scripts/linux/apk.sh release   # signed release APK + AAB
```

`apk.sh` runs the three steps: `skip android build` (Swift for Android), skipstone again without `SKIP_BRIDGE`
so the Kotlin side is generated (Xcode does this on macOS, `scripts/linux/kotlinize.sh`), then Gradle.
`ARCHS=x86_64` builds for the emulator. On a 4 GB builder, use `GRADLE_XMX=2g GRADLE_WORKERS=1`. Outputs: `.build/Android/app/outputs/apk/…` and `…/bundle/release/app-release.aab`.

### Release signing

Release builds are signed with `Android/app/keystore.jks` as described in `Android/app/keystore.properties`
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Both files are git-ignored; without them the release
build falls back to the debug key. Use Play App Signing: this key is only the upload key.

### Google Play

Store texts (en-US, de-DE) are in `fastlane/metadata/android` (fastlane `supply` layout). For the Play Console:
- **Health Connect**: Publit reads steps and distance (`READ_STEPS`, `READ_DISTANCE`) only to estimate your walking pace
  for smart routing, on the device, after you allow it. This needs the Health Connect declaration form in the Play Console.
- **Data safety**: no data collected or shared by the developer; location, photos/files and health data are processed
  on the device only. Requests go to Transitous, OpenFreeMap and the Overpass API without an account.
- **Exact alarms**: `USE_EXACT_ALARM` is for leave and get-off reminders (alarm-clock-like, user-set).

## Data and privacy

Same as the iOS app: routing and departures from [Transitous](https://transitous.org) (MOTIS API), no account, no tracking.
Map tiles come from [OpenFreeMap](https://openfreemap.org), nearby places from the [Overpass API](https://overpass-api.de)
(OpenStreetMap). Map data © OpenStreetMap contributors.

## License

MIT, like the iOS app ([LICENSE](upstream/LICENSE)).
