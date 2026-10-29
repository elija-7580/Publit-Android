# Android development handoff — 2026-10-21

## Scope and safety

- Work only in **elija-7580/Publit-Android**, branch `main`. **Never modify the iOS repository**.
- Base: iOS `3bb9c11`, version 0.6.0, build 59. Application ID `io.github.elija7580.publit`.
- Push each meaningful step. Source changes through GitHub commit `b52d4690ca23e372c677b1acadb5cf7ad3010615` are saved remotely.
- Do not interpret this checkpoint as a completed release or completed UI QA.

## Added in this continuation

- Android version/build from PackageManager; Transitous User-Agent identifies Android and the Android repository.
- Android 16 promoted ongoing Live Update request; system/user still controls promotion.
- Standalone Wear OS module (`Android/wear`): location permission, fresh location with timeout, nearby departures,
  500/1500/5000 m fallback, stop grouping, cancellation, delay/live indicators, local stop time zone, refresh, 15 locales.
- Native Compose text selection and non-consuming pressed feedback; larger soft/chip touch targets.
- Widget category includes keyguard for devices/launchers that support it; not a promise of universal phone lock-screen support.
- Optional Google Wallet export with explicit consent, default off. Text barcodes only; binary railway tickets remain in Publit.
  Signing service template in `services/google-wallet-signer`; issuer setup and deployment are still required.
- Debug-only access to existing demo route/live/pass screens via intent string extra `publit.preview`.
  Production/release builds do not expose the demo UI.
- Linux build bootstraps skipstone and command replay on a fresh checkout. Exact Swift dependency revisions saved in `Package.resolved`.
- Wear release reuses the phone upload keystore, which is never committed.

## What has actually been tested

- Wallet signer Node tests: **4/4 passed**, including verification of an RS256 signature using an ephemeral test key.
- Android XML syntax: **62 files parsed** at the initial resource audit.
- `git diff --check`: clean at the checkpoint.
- First x86_64 native Swift build succeeded **before several later UI/Wallet edits**.
- Gradle build and emulator boot are still running at this checkpoint. **Latest complete app/Wear build not yet confirmed.**
- **No app screen has yet passed visual inspection; no final app screenshots have been produced.**

## Build check — 2026-10-27

- Fresh Linux checkout, Swift 6.4.1 + Swift SDK for Android, NDK 28.2.13676358: **arm64 debug build succeeded**
  (native Swift `libPublit.so`, Kotlin, Gradle); `app-debug.apk` and `wear-debug.apk` produced.
- Swift build of `Publit` for Android: **0 errors, 0 warnings** in project sources after this round.
- Not yet done: install/launch on an emulator or device, screen-by-screen UI check.
- Fixed in this round:
  - Android compile: `@State private` properties cannot be bridged (AddToWalletButton);
    `TextSelectability` is a protocol on Skip, so `== .enabled` does not compile (SwiftUIShims).
  - Kotlin compile on older compile SDKs: Android 16 Live Update setters are now called reflectively (DroidNotifications).
  - macOS/iOS host pre-build (Android Studio sync): `AppState.shared` defined on all platforms; Android-only
    `PublitRootView`/`PublitAppDelegate` guarded; duplicate `ActivityAttributes` conformance removed;
    widget-dependent `PresentationSampleView` excluded from the iOS host build.
  - Concurrency warnings that become errors in Swift 6 mode: PlanModel `maxWalk` captured by `async let`,
    `PassStore.maxDocumentBytes` read off the main actor, PlaceSearchView `scope` in an autoclosure.
  - `scripts/linux/install.sh`: script path resolved before `cd`; prerequisite check. Ubuntu 26.04 also needs
    `libxml2.so.2` → `libxml2.so.16` symlink and the sandbox TLS CA imported into the JDK for `sdkmanager`.
- Mac (Android Studio) prerequisites, in this order: `brew install skiptools/skip/skip`, `skip android sdk install`,
  Android Studio → SDK Tools → NDK 28.2.13676358 + Command-line Tools. Then sync and Run.

## Latest verification — 2026-10-29

See [the detailed QA report](ANDROID_QA_2026-10-29.md).

- ARM64 and x86_64 phone debug APKs and Wear debug APK built successfully with the current app code.
- Fixed the observed `NoSuchMethodError`: SkipBridge resolves companion objects, not `object Droid`'s
  `@JvmStatic` methods. `Droid` now uses a companion; production JVM bridge tests pass **2/2**.
- Seven live backend contract checks and four Wallet signer tests passed. Deterministic local OSM regression
  checks passed. Public Overpass providers all failed in this continuation; no claim of working category search.
- Category search has sequential fallback, cancellation handling and a visible retry state. Old category requests
  cannot overwrite newer requests. Material typography uses neutral letter spacing.
- Linux CLI opts out of the redundant/failing Apple host pre-build after generating native/Kotlin outputs,
  targets phone/Wear assembly directly and accepts `GRADLE_XMX`.
- Debug APK ABI filters checked: phone ARM64 contains only `arm64-v8a`; emulator APK only `x86_64`.
- No upload key was generated or changed. No iOS repository edits.

## Remaining work

1. Verify the new bridge and the latest typography on a running Android app: location permission + fresh location,
   Nearby live departures, Plan search/results, Tickets import, Saved, Settings and Map, light/dark and large text.
   Prior onboarding screenshots are not approval of this new build.
2. Category search: verify provider recovery and the retry UI on a device; all public Overpass providers can be down.
3. Use debug fixture scenarios (`route`, `live`, `pass`, `pass-editor`, `passes`) for additional UI checks,
   explicitly labelling demo data. They do not replace real backend/permission flows.
4. Test on a real Wear OS watch. A successful Wear debug build is not hardware validation.
5. Signed release APK/AAB using the user's existing upload key, Play Console screenshots/policies and Data Safety.
   Do not replace the upload key. Keep the Overpass fallback providers in the privacy review.
6. Before public Wallet enablement: real issuer/class/service-account setup, deployed signer and end-to-end validation.
7. Rolling-digit animation remains unsupported; search scopes remain a segmented control.

## Sandbox recovery

Repository: `/data/Publit-Android`; tools: `/data/tools`.

```sh
bash scripts/fetch-ios-strings.sh
bash scripts/linux/install.sh
ARCHS=x86_64 bash scripts/linux/apk.sh debug
# arm64 release; only after latest code/UI fixes:
GRADLE_EXTRA=':app:bundleRelease :wear:bundleRelease' bash scripts/linux/apk.sh release
```

- Build log: `/data/apk.log`; initial build log copied to `/data/first-debug.log` before the queued rebuild.
- Queued rebuild monitor: `/data/rebuild-monitor.log`.
- Emulator: AVD `test`, Android 35 default x86_64, 540×1170, density 240, software acceleration,
  `-no-window -accel off -no-audio -gpu swiftshader_indirect -no-snapshot -no-metrics -memory 1536 -no-boot-anim`.
  Boot without KVM is very slow. Avoid full builds and emulator boot together if possible on a 4 GB host.
- `adb`: `/data/tools/android-sdk/platform-tools/adb`; emulator log `/data/tools/emurun2.log`.
- Debug APK output: `.build/Android/app/outputs/apk/debug/app-debug.apk`.
- Wear output: `Android/wear/build/outputs/` (verify actual Gradle path).
- Ignore Linux host-prebuild `CoreGraphics` errors only when the subsequent Android Gradle build actually succeeds.
- Upload signing files belong in `Android/app/keystore.jks` and `keystore.properties`, both gitignored.
  The user already has the separately delivered upload-key ZIP. Do not generate a replacement key or publish secrets.
- All binaries and screenshots that should survive a sandbox reset must be downloaded as session files or uploaded to GitHub;
  merely writing them under `/data` is not sufficient backup.
