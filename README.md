# Publit for Android

**Public transport, without the noise.** The Android version of [Publit](https://github.com/elija-7580/Publit), the free, open-source transit app built on the open [Transitous](https://transitous.org) routing service. No ads, no account, no tracking.

![Android 8+](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-black)
![target 36](https://img.shields.io/badge/targetSdk-36-black)
![Swift](https://img.shields.io/badge/Swift-SwiftUI%20%E2%86%92%20Compose-orange)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](upstream/LICENSE)

> **Status: work in progress, not tested on a device yet.** The whole app compiles for Android and a debug APK
> builds, but nobody has run it on a phone or emulator so far. Expect crashes and layout bugs on first launch.

## What this is

A 1:1 port of the iOS app (same screens, same Ink & Dot design, same 15 languages), based on iOS commit
[`3bb9c11`](https://github.com/elija-7580/Publit/commit/3bb9c112498d1413f58c3770f31a692cb5e44bd9) (v0.6.0, build 59, see `upstream/IOS_COMMIT`).

It uses [Skip Fuse](https://skip.dev): the iOS app's Swift code is **compiled natively for Android** (Swift 6 Android SDK),
and its SwiftUI views are rendered with **Jetpack Compose** through SkipUI. So the routing, models, caching, live
updates and nearly all views are the same Swift source as on iOS. Where Android differs, the shared files have small
`#if os(Android)` branches; everything Android-only lives in two folders:

| Folder | What |
| --- | --- |
| `Sources/Publit/Android/` | Swift: app entry, SwiftUI shims for what Skip does not bridge (button/label styles, Grid, ContentUnavailableView, styled text, …), Apple API stand-ins (MapKit/CoreLocation types), map, notifications, tickets, OSM places |
| `Sources/Publit/Skip/` | Kotlin: `Droid.kt` (system services: location, notifications, TTS, calendar, files, brightness, shortcuts), `PublitMap.kt` (MapLibre), `PublitCompose.kt` (Compose views), `DroidTickets.kt`, `DroidNotifications.kt`; `skip.yml` (Gradle dependencies) |
| `Android/` | Gradle app project (manifest, `Main.kt` activity, resources, icons) |
| `upstream/` | Reference material from the iOS repo (README, changelog, docs, Watch/Widget/Share sources that are not ported) |

### iOS frameworks and their Android counterparts

| iOS | Android |
| --- | --- |
| MapKit (Apple Maps) | MapLibre with OpenStreetMap vector tiles (OpenFreeMap, Ink & Dot styles, light/dark) |
| Apple Maps points of interest ("Find nearby") | OpenStreetMap via Overpass API; free-text search via the Transitous geocoder |
| CoreLocation | `LocationManager` (fused provider on Android 12+), compass via rotation sensor |
| UserNotifications | `NotificationManager` + exact alarms (departure reminders, alerts). No background refresh yet: saved lines are checked when the app is open |
| Live Activities / Dynamic Island | Ongoing notification for the live trip |
| App Intents / Shortcuts | Launcher shortcuts (home, work, recent trips) |
| VisionKit / Vision (ticket import) | ML Kit text + barcode recognition, ML Kit document scanner, `PdfRenderer`, ZXing |
| PhotosPicker / file importer | Android photo picker / document picker |
| EventKit | Calendar insert intent |
| AVSpeechSynthesizer | `TextToSpeech` |
| Share extension | Share target in the activity (text, links, files) |

### Not available on Android (yet)

- Apple Wallet (hidden), Apple Watch app, home screen widgets
- Walking speed from Apple Health / pedometer (smart routing uses the default pace)
- MetricKit crash reports (Google Play's Android vitals instead)
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
# 1. Swift for Android (native .so libraries)
skip android build --arch aarch64 --configuration debug -d .build/Android/Publit/jni-libs \
  --product Publit -Xcc -fPIC -Xswiftc -DSKIP_BRIDGE -Xswiftc -DTARGET_OS_ANDROID
# 2. skipstone again without SKIP_BRIDGE, so the Kotlin side is generated (Xcode does this step on macOS)
# 3. Gradle
cd Android && SKIP_BRIDGE_ANDROID_BUILD_DISABLED=1 gradle assembleDebug
```

Use `--arch x86_64` for the emulator. The APK ends up in `.build/Android/app/outputs/apk/debug/`.

## Data and privacy

Same as the iOS app: routing and departures from [Transitous](https://transitous.org) (MOTIS API), no account, no tracking.
Map tiles come from [OpenFreeMap](https://openfreemap.org), nearby places from the [Overpass API](https://overpass-api.de)
(OpenStreetMap). Map data © OpenStreetMap contributors.

## License

MIT, like the iOS app ([LICENSE](upstream/LICENSE)).
