# Android QA — 2026-10-29

## Scope

Android repository only, base `5318db4`, version 0.6.0/build 59. No iOS changes and no new upload key.
A fresh Linux sandbox required reinstalling Swift 6.4.1, Android SDK/NDK 28.2, JDK 21 and Gradle 9.4.1.

## Fixed: native Swift could not call Android services

`AnyDynamicObject(forStaticsOfClassName: "publit.module.Droid")` uses SkipBridge 0.18.0's
`Reflector`. That constructor resolves `companionObjectInstance` plus `KClass.staticFunctions`.
Kotlin `object Droid` has no companion, and its `@JvmStatic` members do not appear as Kotlin
static functions. This reproduces the observed `NoSuchMethodError` for both `appBuild()` and
`startLocation(false)`. The thread-safe Swift `static let` was not the cause.

`Droid` is now a non-instantiable class with a companion object. Kotlin callers retain `Droid.foo()`;
Swift can use the existing static reflector. Service state remains singleton state.

- Exact pinned Reflector + old/new JVM fixtures: failure reproduced for the old form;
  String, Void(Boolean), Boolean and Double calls pass with the companion form.
- Production-class Android unit tests cover the companion/method surface and reflective TTS no-op calls.
- JVM fixture tests do not establish GPS, permission dialogs, notifications or TTS hardware functionality.

## Live backend checks

`python3 scripts/qa/backend-smoke.py`: **7/7 passed**, each HTTP 200 with JSON shape checks:

| Check | Additional validation |
| --- | --- |
| Transitous geocode | Non-empty search results |
| Transitous plan | Non-empty transit itineraries |
| Transitous map stops | Non-empty station list |
| Transitous stop times | Non-empty departures |
| Transitous rentals | JSON object |
| OpenFreeMap Liberty style | `layers` present |
| Wiener Linien monitor | `data` present |

These are public fixed Vienna test coordinates, not the user's location. This is server availability/
contract testing, not a full app end-to-end test or an uptime guarantee.

### Overpass limitation

A separate live POST test of café places in Vienna failed on all three instances in this continuation:
`overpass-api.de` HTTP 502, `maps.mail.ru` HTTP 504, `overpass.private.coffee` timeout.
The earlier continuation reported a successful mirror response; it is not a current availability guarantee.

Android category browsing now tries bounded sequential fallbacks (15 seconds per instance), honours
cancellation, and accepts only HTTP 200 with valid JSON. There is no unsynchronised preferred-server state.
Exhausted providers now raise an error instead of returning a false empty result. The category chip shows
an error icon and a translated **Try again** action. A request sequence prevents an old search from replacing
a newer one (including a second search of the same category).

This improves resilience but **does not resolve an outage affecting every public instance**. Free-text
place/address search still uses Transitous. Category browsing is not release-verified. The queried map
bounding box/category is sent to each attempted Overpass provider; reflect these providers in privacy review.

## Other checks and changes

- Wallet signing service: **4/4 Node tests passed**, including an RS256 signature check using an ephemeral
  test key. No real issuer/deployed service/Google Wallet end-to-end validation.
- `python3 scripts/qa/osm-regression.py`: **5 checks passed** against the actual OSM source with a local
  HTTP server and minimal model stubs: HTTP/malformed JSON fallback, valid-coordinate filtering,
  exhausted-provider error, valid empty result, cancellation and exact request order. This substitutes
  the platform guard and endpoint URLs only; it does not test the rendered map or Android networking stack.
- Material text styles now use neutral letter spacing, preserving the existing type sizes and weights.
  Visual approval is separate from compilation.
- Linux APK build supports `GRADLE_XMX=2g` for a 4 GB builder.
- `git diff --check` passed before the build.

## Reproduction

```sh
bash scripts/fetch-ios-strings.sh
bash scripts/linux/install.sh
bash scripts/qa/bridge-regression.sh
python3 scripts/qa/backend-smoke.py
python3 scripts/qa/osm-regression.py
node --test services/google-wallet-signer/worker.test.mjs
ARCHS=x86_64 GRADLE_XMX=2g GRADLE_WORKERS=1 bash scripts/linux/apk.sh debug
# After building, from Android/ with scripts/linux/env.sh sourced:
# SKIP_BRIDGE_ANDROID_BUILD_DISABLED=1 SKIP_BRIDGE_ROBOLECTRIC_BUILD_DISABLED=1 \
# PUBLIT_ABIS=x86_64 gradle --no-daemon -Dorg.gradle.jvmargs=-Xmx2g \
# -Dorg.gradle.workers.max=1 :app:testDebugUnitTest --tests publit.module.DroidBridgeTest
```
