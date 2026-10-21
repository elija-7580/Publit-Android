# Publit for Android

**Public transport, without the noise.** The Android version of [Publit](https://github.com/elija-7580/Publit), the free, open-source transit app built on the open [Transitous](https://transitous.org) routing service. No ads, no account, no tracking.

![Android 8+](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-black)
![target 36](https://img.shields.io/badge/targetSdk-36-black)
![Swift](https://img.shields.io/badge/Swift-SwiftUI%20%E2%86%92%20Compose-orange)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](upstream/LICENSE)

> **Status: feature-complete port, not tested on a device yet.** The whole app compiles for Android, debug and signed
> release builds (APK + AAB) work, but nobody has run it on a phone or emulator so far. Expect crashes and layout bugs
> on first launch.

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

### Not available on Android (yet)

- Apple Wallet (hidden), Apple Watch app (no Wear OS app), lock screen widgets and the live-trip widget
- Some SwiftUI details: custom pressed states of buttons, rolling-digit number transitions, text selection,
  search scopes as a native bar (shown as a segmented control over the map instead)

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
`ARCHS=x86_64` builds for the emulator. Outputs: `.build/Android/app/outputs/apk/…` and `…/bundle/release/app-release.aab`.

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
