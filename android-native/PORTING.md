# Publit native Android — porting plan and status

Goal: Publit for Android as a native Kotlin + Jetpack Compose app, as light and fast as possible.
The iOS app (Swift, `Sources/Publit/`) is the reference; Android is ported feature by feature, not shared.
The old Skip Gradle project (`Android/`) is removed; `Sources/` stays as the read-only iOS reference until porting is finished.

## Rules (every session)
- Read only this file, `CLAUDE.md` and the iOS files of the current milestone. No repo-wide exploration.
- Port behaviour 1:1 from Swift; keep English string keys exactly as in Swift (`L10n.t("Nearby")`, `L10n.f("%lld min", n)`).
- Architecture: `data/` (models, API, pure logic, unit-tested) → `ui/<screen>/` ViewModel (StateFlow) + Composables.
  Location/GPS only while a screen is resumed. No new libraries without need.
- Build on GitHub Actions (local Maven downloads get rate-limited). Read only the error lines of the log.
- Commit + push after every finished step (session limits end work without warning); WIP is pushed and marked in the status.
- Each milestone ends with: tests green, APK on release `native-latest`, status below updated, commit + push.
- Device: Huawei Mate 20 Lite (Android ≤10, slow). Test there; crash report is shown in-app at next start.

## Layout
`app/src/main/java/io/github/elija7580/publit/`: `core/` (L10n, Fmt, Geo), `data/` (TransitModels, ModeStyle,
LineColors, Alerts, StopGrouping, TransitousClient), `location/LocationRepository`, `ui/` (Theme, Components,
NearbyViewModel, PublitApp, StopScreen, PlanScreen, TripScreen, PublitMap, StationMapScreen, SavedScreen, SettingsScreen), `CrashLog`, `PublitApplication`, `MainActivity`.
Strings: `tools/strings.py` turns the iOS catalog into `assets/strings/<lang>.json` (CI step, not committed).
Two test APKs per release: arm64 (`Publit-native.apk`) and 32-bit ARM (`Publit-native-32bit.apk`). App ID `io.github.elija7580.publit` (replaces the old Skip build; uninstall that once, its signing key differs), fixed test key `app/publit-test.jks` until the Play upload key is a CI secret.

## Milestones
| # | Milestone | iOS sources | Status |
|---|-----------|-------------|--------|
| 1 | Scaffold, core logic, location, Nearby | NearbyView, TransitModels, StopGrouping, TransitFilter, LineColors, ModeStyle | done (Google + GPS + network location, crash report in-app) |
| 2 | CI build + release `native-latest`, compile fixes, unit tests | – | done (`.github/workflows/android-native.yml`, 1.8 MB APK) |
| 3 | Stop departures + lines view, bottom tabs, navigation | NearbyView (StopDeparturesView), LineGrouping | done |
| 4 | Place search + Plan (route search, results) | PlanView, PlaceSearchView, PlanModel, TransitousClient.plan, Reach | done (route options, fares, smart routing: ranking, badges, change chances, backups, learning, own walking speed from Health Connect; street comparison) |
| 5 | Itinerary detail + trip view | ItineraryDetailView, TripView, TripStatus | done (trip view: stops, live progress, route here/from here; map follows in 6, save line in 7) |
| 6 | Map (MapLibre) + station map | Views/Map, StationMapView, MapLayers | done (MapLibre/OpenFreeMap: trip + route map, station map with search, mode filter, live line shapes and moving vehicles, sharing stations and vehicles, layers, satellite (Sentinel-2), category search (OpenStreetMap places via Overpass)) |
| 7 | Saved places/lines/trips, settings, onboarding | FavoritesView, FavoritesStore, SettingsView, OnboardingView | done (Home/Work, saved stops/places/lines, save stop + save line, line departures, settings: start screen/data/about, onboarding; settings for map/route options/notifications/diagnostics as their features arrive) |
| 8 | Live trip, notifications, reminders | LiveTripView, Notifications/* | done (saved + past trips, trip actions, leave/get-off reminders, live notification, restored after reboot; live guide: stages, cues with vibration and spoken directions, missed/off-route/risky change with alternatives and switch; saved-line alerts every ~30 min) |
| 9 | Live sources overlay, offline cache | LiveSources*, ResponseCache | done (offline copies + note; all extra live sources: ÖBB, Wiener Linien, search.ch, VRR, VBB, RMV, VBN, NAH.SH, VRN, ACTV, SL, TfL; settings toggle and source list) |
| 10 | Tickets, widget, Wear OS | Tickets/*, DroidWidget.kt, Android/wear | done: tickets as passes (on-device recognition with ML Kit + the full TicketTextParser, ZXing redraw, wallet, detail at full brightness, editor with field assignment, archive, trip tickets), widget (Glance), Wear OS (module `wear/`), operator logos (Wikidata/Wikimedia Commons, website icon fallback), Google Wallet export via services/google-wallet-signer (hidden until the repo variable WALLET_SIGNER_URL is set) |
| 11 | Switch app ID to `io.github.elija7580.publit`, release signing, remove Skip port | – | app ID switched, Skip Gradle project and its workflow removed; open: Play upload key as CI secret (owner), then remove `Sources/` |

## Status 2026-10-30
- DONE: Coverage atlas (areas without timetable data) on the map, Plan, route details, Nearby and stops.
- DONE: Home/Work shortcuts in the Plan form; Nearby "Where to now?", route-here; settings on every tab; launch animation.
- DONE: Website screenshots (workflow "Website-Screenshots erstellen", `screenshots/README.md`): build type `screenshots` (demo data server on 10.0.2.2, no extra live sources, onboarding skipped), link `publit://map` opens the station map. Status: the workflow runs green on `native-android` (emulator Pixel 8 Pro screen, API 35, 4 views × DE/EN, framed + posters + WebP; also on `main` for "Run workflow"). The AVD lives in `~/.android/avd` (explicit `ANDROID_AVD_HOME`), checks use destination names, not the white-on-colour line badges that OCR cannot read.
- OPEN (next, in order):
  1. LiveTripView map-first port (StagePager/StageStrip, TripPanel, GuideMap, GuideStopRow, TransferCard) from `Sources/Views/LiveTripView.swift`.
  2. Load-time investigation (LiveOverlay budget per call, MapLibre init).
  3. Full iOS feature audit: GoNowView, LinesView/LineDetailView, SharedRouteView, ConnectionGuideView, RecentSearches, WalkThereRow, ArrivalUpdates, DataExport, Diagnostics, ServiceAlertsView, "Open in maps".
  4. Better satellite imagery: needs a decision and key from the owner (Google Maps SDK, MapTiler or Esri).

## iOS Build 82 (2026-11-16) – Meine Tickets
- [x] Routenoptionen → „My tickets“ (`OwnTicketsScreen`, `data/OwnTickets.kt`, Port von `OwnTicket.swift`): Deutschlandticket, BahnCard 100, KlimaTicket Ö, VOR MetropolRegion/Region, Jahreskarte Wien, KlimaTickets OÖ/Salzburg/Tirol/Steiermark/Kärnten/VMOBIL, GA; Mehrfachauswahl, gespeicherte Pässe am Titel erkannt.
- [x] Prüfung je Fahrt über Zugart und Land/Bundesland aus der Haltestellen-ID (`StopArea`); Suche ohne Modi, die kein Ticket abdeckt, Rest wird nach der Antwort gefiltert (`TransitousClient.plan`); Meldung, wenn keine Route abgedeckt ist. Gleiche Rohwerte wie iOS (`RouteOptions.tickets`).
- [x] What's new Release 2; Tests `OwnTicketsTest`.

## iOS Build 81 (2026-11-15)
- [x] Kompakte Umstiegskarte zwischen den Abschnitten im Routendetail (`TransferCard` in PlanScreen: „Change at …“ + Puffer-Kapsel, „Zeit · Steig → Zeit · Steig“, „Linie → Linie · Fußweg“)
- [x] Smart-Badges brechen nicht mehr um (FlowRow, Begründung rutscht bei Platzmangel darunter)
- [x] Community zuerst: `CommunityCard` ganz oben in den Einstellungen, Wunschliste (`WishesScreen`, publit.eu/api/wishes, gleiche Voter-ID-Regeln), Start-Bildschirm unter „General“
- [x] „What's new“ nach Updates (`WhatsNewDialog`, `data/Community.kt` `WhatsNew.entries`; bei jeder Android-Version mit sichtbaren Änderungen einen Eintrag ergänzen, `release` hochzählen), nicht bei Neuinstallation
- [x] Unterstützen (`SupportScreen`, `billing/SupportBilling.kt`, Play Billing 7): Abos 1,50/3/5/10/20/50 € monatlich zuerst, einmalig 3/5/10/20/50 €; gleiche Produkt-IDs wie iOS (`io.github.elija7580.publit.support.monthly.<Cent>` als Abo mit einem monatlichen Basis-Abo, `…support.tip.<Cent>` als In-App-Produkt, wird verbraucht). Produkte muss der Owner in der Play Console anlegen.
- IOS_COMMIT auf den iOS-Stand mit den neuen Strings gesetzt.

## Abgleich mit iOS docs/FUNKTIONEN.md (Stand #44, 2026-10-30)
Quelle: elija-7580/publit `docs/FUNKTIONEN.md`. Per Textsuche fehlende Funktionen in Android:
- [x] Live-Fahrt kartenzentriert inkl. Turn-by-turn (WalkRoute-Port aus MOTIS steps) und Kompass
- [x] Nearby: "Next trip" (Active trip = Live-Leiste)
- [x] Haltestelle: "Share departures"
- [x] LineDetail (Richtungen, Karte mit geschätzten Fahrzeugen, Halte) und LineTimetable (Tag)
- [-] Linienliste (LinesView): in iOS nirgends eingebunden, daher nicht portiert
- [x] Karte: "Data gaps", "Dropped pin", Leihrad "Available now"
- [x] "Recently searched" + "Clear history" (im Planer)
- [x] Plan: "Earlier connections", "Other ways" ("About as fast")
- [x] GoNow: "Leave now / Leave in x min / Walk all the way" auf Routen vom Standort
- [x] Routendetail: "Feels like", "Remind me to get off"
- [x] Live-Leiste: "Open trip", "Start trip", "Hide", "Arrived"
- [x] Tickets: "Default ticket", "Show ticket"
- [x] Einstellungen: Zeitleisten, "How to read connections", "Open directions in", "Export my data", Diagnose
- [x] Teilen aus anderen Apps, publit://place, publit://go/home|work
- [x] Geteilte Routen und Haltestellen im iOS-Linkformat (https://publit.eu/open/#route?…), in beide Richtungen
- [x] Live-Karte: "Turn the map with you"

## Feedback 2026-10-30 (Test auf Gerät)
- [x] Nearby: "Where to now?", Home, Work öffnen Go Now (eigene Suche, Routenoptionen, Karte, Routen ab Standort) wie iOS
- [x] Live-Benachrichtigung wie die iOS Live Activity (Linie, Countdown, Gleis, "Then", Etappen mit Halten; Android 16 Live Update mit Fortschritt je Abschnitt; Show ticket, End trip)
- [x] Live-Leiste wie iOS (▶ Start, dunkle Live-Leiste mit Ring und Countdown, Hochwischen öffnet, Live-Zeiten alle 90 s)
- [x] Ortssuche: Home/Work ("Set Home/Work" wenn leer), Current location, Saved
- [x] Eigener Navigations-Stapel pro Tab (Tabs öffnen das Richtige, erneutes Tippen → oberste Seite, Zurück auf Tab-Start → In der Nähe); vorher teilten sich alle Tabs einen Stapel
- [x] Live-Fahrt: "Back to now" als Knopf über dem Panel
- [x] Nearby: Karte als Bildschirm, ganze Liste als Sheet darüber (startet halb, oben bleibt ein Kartenstreifen), „Where to now?“ als fester Kopf; Einstellungen, Standort und Stationskarte (mit Suche) als Knöpfe oben; abschaltbar unter Einstellungen → Nearby → Map, wie iOS PR #61 (`NearbyMap.kt`; Hinweistext bis zum nächsten IOS_COMMIT englisch)
- [x] Live-Fahrt: Ausgangsnummer beim Aufgang aus der U-Bahn ("Take exit B2", OpenStreetMap über Overpass, `StationExits`), wie iOS #8 (iOS-Strings kommen mit dem nächsten IOS_COMMIT-Update, bis dahin englisch)
- [x] Park & Ride wie iOS (`ParkAndRide.kt`, iOS Build 66 + PR #77): erst max. 15 min Fahrt, ist der nächste Bahnhof weiter als 5 min, zweite Suche bis nächster Bahnhof + 10 min (max. 25), damit ein zweiter Bahnhof an anderer Linie mitkonkurriert; „fast ganz hingefahren“ und weiter entfernte Bahnhöfe fallen raus, auch beim Nachladen. Offen: der iOS-Fallback „kein Halt in Gehweite → Park & Ride vor 2 h Fußweg“ mit Hinweistext
