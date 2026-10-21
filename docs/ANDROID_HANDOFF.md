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

## Remaining work

1. Finish x86_64 debug build with the latest source; fix compiler/manifest/resource failures if present.
2. Install on emulator, test first launch/location, Nearby, Plan, Tickets, Saved, Settings and Map; inspect actual screenshots.
3. Use debug demo scenarios (`route`, `live`, `pass`, `pass-editor`, `passes`) for additional deterministic UI checks;
   explicitly label fixture screenshots as demo data. Test real application flows too.
4. Build and test Wear OS. A real watch is still required for hardware-specific validation.
5. Build signed arm64 phone APK/AAB and signed Wear AAB; verify certificates and packaged ABIs.
6. Replace README/checkpoint status with actual results and save screenshots/test evidence.
7. Before enabling Wallet in a public build: issuer account, approved class, authorized service account, deployed signer,
   rate limit/budget, privacy policy and Play Data Safety review. An offline signer test is not a real Google Wallet end-to-end test.
8. Rolling-digit number animation remains unsupported; search scopes remain a segmented control rather than an iOS native bar.

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
