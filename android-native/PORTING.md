# Publit native Android — porting plan and status

Goal: Publit for Android as a native Kotlin + Jetpack Compose app, as light and fast as possible.
The iOS app (Swift, `Sources/Publit/`) is the reference; Android is ported feature by feature, not shared.
Old Skip port (`Android/`, `Sources/`) stays until the native app replaces it.

## Rules (every session)
- Read only this file, `CLAUDE.md` and the iOS files of the current milestone. No repo-wide exploration.
- Port behaviour 1:1 from Swift; keep English string keys exactly as in Swift (`L10n.t("Nearby")`, `L10n.f("%lld min", n)`).
- Architecture: `data/` (models, API, pure logic, unit-tested) → `ui/<screen>/` ViewModel (StateFlow) + Composables.
  Location/GPS only while a screen is resumed. No new libraries without need.
- Build on GitHub Actions (local Maven downloads get rate-limited). Read only the error lines of the log.
- Each milestone ends with: tests green, APK on release `native-latest`, status below updated, commit + push.
- Device: Huawei Mate 20 Lite (Android ≤10, slow). Test there; crash report is shown in-app at next start.

## Layout
`app/src/main/java/io/github/elija7580/publit/`: `core/` (L10n, Fmt, Geo), `data/` (TransitModels, ModeStyle,
LineColors, Alerts, StopGrouping, TransitousClient), `location/LocationRepository`, `ui/` (Theme, Components,
NearbyViewModel, PublitApp), `CrashLog`, `PublitApplication`, `MainActivity`.
Strings: `tools/strings.py` turns the iOS catalog into `assets/strings/<lang>.json` (CI step, not committed).
App ID `io.github.elija7580.publit.next` (installs next to the old app), fixed test key `app/publit-test.jks`.

## Milestones
| # | Milestone | iOS sources | Status |
|---|-----------|-------------|--------|
| 1 | Scaffold, core logic, location, Nearby | NearbyView, TransitModels, StopGrouping, TransitFilter, LineColors, ModeStyle | done (Google + GPS + network location, crash report in-app) |
| 2 | CI build + release `native-latest`, compile fixes, unit tests | – | done (`.github/workflows/android-native.yml`, 1.8 MB APK) |
| 3 | Stop departures + lines view, bottom tabs, navigation | NearbyView (StopDeparturesView), LineGrouping | done |
| 4 | Place search + Plan (route search, results) | PlanView, PlaceSearchView, PlanModel, TransitousClient.plan, Reach | done (basic: no route options, fares, smart routing yet) |
| 5 | Itinerary detail + trip view | ItineraryDetailView, TripView, TripStatus | done (trip view: stops, live progress, route here/from here; map follows in 6, save line in 7) |
| 6 | Map (MapLibre) + station map | Views/Map, StationMapView, MapLayers | open |
| 7 | Saved places/lines/trips, settings, onboarding | FavoritesView, FavoritesStore, SettingsView, OnboardingView | open |
| 8 | Live trip, notifications, reminders | LiveTripView, Notifications/* | open |
| 9 | Live sources overlay, offline cache | LiveSources*, ResponseCache | open |
| 10 | Tickets (optional), widget, Wear OS | Tickets/*, DroidWidget.kt | open |
| 11 | Switch app ID to `io.github.elija7580.publit`, release signing, remove Skip port | – | open |
