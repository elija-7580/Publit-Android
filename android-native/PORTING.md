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

Two product flavors: `play` (default; Google Play and the GitHub APKs: fused location, ML Kit text, codes and document scanner, Play Billing) and `foss` (no Google library at all, for F-Droid and phones without Google services: platform GPS/network location, ZXing reads codes, Tesseract reads text with the tessdata_fast models deu/eng/fra/ita/spa in `app/src/foss/assets/tessdata`, no paper scanner, support links to publit.eu). Flavor code lives in `app/src/play` and `app/src/foss` (`FusedLocation`, `TicketVision`, `SupportBilling`); everything else is shared. CI builds both; the release also carries `Publit-native-foss.apk`. `.github/workflows/android-device-test.yml` runs both on an emulator: device tests in `app/src/androidTest` (ticket text, QR and binary Aztec codes from an image and a PDF), the four demo-data views (`screenshots/capture.py`) and 2500 random taps without a crash.

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
| 10 | Tickets, widget, Wear OS | Tickets/*, DroidWidget.kt, Android/wear | done: tickets as passes (on-device recognition with ML Kit + the full TicketTextParser, ZXing redraw, wallet, detail at full brightness, editor with field assignment, archive, trip tickets), widget (Glance), Wear OS (module `wear/`; the trip under way from the phone via the Data Layer item `/live-trip` (`watch/WatchLink`, play flavor only): live screen `LiveTripActivity` with labelled countdown, vehicle, progress and stages, map `LiveMapActivity` (MapLibre, OpenFreeMap dark, legs in line colours, walks dotted, stops, own position; opens on the leg under way), an ongoing activity on the watch face, opens by itself when a trip starts and once each time the app comes to the front), operator logos (Wikidata/Wikimedia Commons, website icon fallback), Google Wallet export via services/google-wallet-signer (hidden until the repo variable WALLET_SIGNER_URL is set) |
| 11 | Switch app ID to `io.github.elija7580.publit`, release signing, remove Skip port | – | app ID switched, Skip Gradle project and its workflow removed; open: Play upload key as CI secret (owner), then remove `Sources/` |

## Geschätztes Fahrzeug folgt dem Linienverlauf (2026-11-25, iOS Build 99 `TripProgress.position(path:)`, Publit#184)
- [x] `data/TripProgress.kt` `position(p, stops, path)`: Zwischen zwei Halten liegt das geschätzte Fahrzeug auf dem Linienverlauf der Fahrt statt auf der Luftlinie (ein Fernbus Wien–Venedig stand sonst mitten in der Steiermark). Der Verlauf gilt nur, wenn beide Halte höchstens 2 km davon entfernt und in ihrer Reihenfolge darauf liegen; sonst und ohne Verlauf bleibt die Luftlinie. Ein Halte-Index außerhalb der Liste gibt keine Position (vorher ein Absturz). Aufrufer mit Verlauf: Fahrtansicht (`TripScreen`), „Track vehicle“ (`VehicleTracking`), Live-Fahrt (`LiveTrip`). Wie auf iOS ohne Verlauf: die Prüfung „verpasst?“ in `TripGuide.check`; die geschätzten Fahrzeuge der Linienkarte (`LineDetail`) bleiben auf der Luftlinie.
- Tests: `VehicleOnShapeTest` (dieselben Fälle wie iOS `testPositionFollowsTheShape`, dazu Rückfall und Grenzen). Kein neuer Text, kein „What's new“-Eintrag, `versionName` unverändert. Nicht am Gerät getestet.

## Besserer Halt unterwegs (2026-11-25, iOS #179, Publit#110)
- [x] Live-Fahrt: Läuft alles nach Plan und man geht gerade zu einer Fahrt (der Fußweg hat laut Plan begonnen, bis zur Abfahrt bleiben mindestens 3 min), sucht Publit einmal je Fußweg von der eigenen Position zum Ziel (`TripAlternative.worthLooking`, `TripAlternative.faster` in `data/TripGuide.kt`, Port aus `CityGuide.swift`). Kommt ein Weg mindestens 3 min früher an als die Fahrt, wie sie steht, zeigt das Panel oben die Karte „Faster“ (`AlternativesCard` mit `offered`, in der Farbe eines Tipps) mit Linie, Abfahrt, Halt, Ankunft und „x min earlier than your trip“; „Switch“ wechselt wie bei den Alternativen. Dieselben Fahrzeuge wie die Fahrt gelten nicht als anderer Weg. Tests: `FasterWayTest`. Kein neuer Text (alle Schlüssel aus dem iOS-Katalog). Nicht am Gerät getestet.
- Anders als iOS: die Suche fragt ohne Ziel-Haltestelle (`PlanStops`) und ohne die zusätzlichen Live-Quellen an; die Karte der Alternativen bei „Missed it?“ usw. bleibt, wie sie war (ohne Vergleichszeile).

## Zu Fuß als echte Wahl (2026-11-26, iOS Build 97 `StreetComparison.walkIsAChoice`, `PlanModel.merge`, Publit#110)
- [x] `data/SmartRouting.kt` `StreetComparison.walk`, `streetMode`, `walkIsAChoice`, `merge`; `ui/PlanViewModel.kt`: Der reine Fußweg steht als Route in der Liste von Planen und „Where to now?“, wenn er etwa so schnell ist wie die schnellste Fahrt (höchstens 25 % länger), höchstens 10 min dauert oder wenn keine Fahrt von Tür zu Tür früher dort ist (Warten auf das Fahrzeug zählt mit; bei „Arrive by“: wenn man mit ihm später losgehen kann als mit jeder Fahrt). Ausgefallene Fahrten zählen nicht. Er steht in der Liste nach Zeit wie jede Route; die Karte zeigt „Walk · Strecke“ statt „Direct“. Der Fußweg erscheint nur einmal: steht er in der Liste, fehlt er unter „Other ways“ und als graue Zeile darunter. Nicht bei „Last“ (ein Fußweg ist keine Verbindung).
- Tests: `WalkChoiceTest` (dieselben Fälle wie iOS `FilterTests`, „Walking as a real choice“). Kein neuer Text (Schlüssel „Walk“ aus dem iOS-Katalog), kein „What's new“-Eintrag, `versionName` unverändert. Nicht am Gerät getestet.
- Anders als iOS: Findet die Suche gar keine Fahrt, bleibt der Fußweg die graue Zeile „x min walk“ unter der Liste (iOS listet ihn dann als einzige Route).

## Abfahrten einer Haltestelle der Fahrt (2026-11-24, iOS #130)
- [x] Fahrtansicht: Das Menü einer Haltestelle hat „Departures“ und öffnet deren Abfahrtstafel (Station, sonst der Halt: `Place.boardId`); Zurück führt zur Fahrt. Jede offene Fahrtansicht behält ihre eigene Fahrt (`tripTargets` in `PublitApp`), auch wenn von der Tafel aus eine andere Fahrt geöffnet wurde. Tests: `TripStopDeparturesTest`. Kein neuer Text (Schlüssel „Departures“ aus dem iOS-Katalog). Nicht am Gerät getestet.

## Gestartete Fahrt immer in der Leiste, X beendet sie (2026-11-25, iOS Build 99 `LiveTripBar`, Publit#185)
- [x] `ui/PublitApp.kt` `LiveTripBar`, `data/TripStore.kt` `LiveBar.shown`: Eine gestartete Fahrt steht immer in der Leiste über den Tabs, auch wenn die Leiste für diese Fahrt vor dem Start mit dem X ausgeblendet wurde (sie lief sonst unsichtbar weiter: Live-Mitteilung, Erinnerungen). Die dunkle Leiste der gestarteten Fahrt hat jetzt ein X („End trip“ für TalkBack): Es beendet die Fahrt über `TripSession.stop`, nicht nur die Leiste. Bei einer nicht gestarteten Fahrt bleibt das X „Hide“. Tests: `LiveBarTest`. Kein neuer Text (Schlüssel „End trip“ aus dem iOS-Katalog), kein „What's new“-Eintrag, `versionName` unverändert. Nicht am Gerät getestet.
- Anders als iOS, offen: Eine von Hand gestartete Fahrt, die erst in mehr als 3 Stunden abfährt, zeigt Android weiter nicht in der Leiste (`TripStore.current` und die Leiste nehmen gestartete Fahrten erst ab 3 h vor Abfahrt; iOS zeigt sie seit dem 17.11. immer). Dieselbe Regel steuert die Live-Mitteilung, darum hier nicht mitgeändert.

## Verbindung selbst zusammenstellen, Ausgänge, Stationskarte (2026-11-24, iOS #142, #143, #170, Android 0.12.0)
- [x] „Go on another way“ (#142) und „Change somewhere else“ (#143) unter jeder Fahrt der Routendetails in Planen (`data/RouteBuilder.kt`, `ui/RouteChange.kt`): Wege weiter ab dem Einstieg; oder Umstieg an einen anderen Halt der Fahrt (aus der ganzen Fahrt, auch nach dem geplanten Ausstieg) bzw. an einen gesuchten Ort / Kartenpunkt. Tests: `RouteBuilderTest`. Anders als iOS: gebaute Routen tragen keine Teile (`builtFrom`), Live-Zeiten einer gebauten Route werden nicht teilweise aktualisiert; kein Abschnittsplaner.
- [x] Live-Fahrt: Umstiegszeile und Karte „nächster Umstieg“ nennen Zugteil und Ausgang („Front of the train · Exit B2“, `data/ExitAdvice.kt`, Port von `ExitAdvice.swift` ohne eigenen Ausgang wählen).
- [x] Stationskarte: Taste „Departures nearby“ unten führt zurück zur Umgebung (iOS #17).
- What's new Eintrag 6. Neue Strings in `tools/strings-android.json`, bis IOS_COMMIT Publit#172 enthält.

## Kalendertermine als Ziele (2026-11-24, iOS #150 und #169)
- [x] `data/CalendarTrips.kt` (Logik wie iOS `CalendarTrips.swift`), `location/CalendarSuggestions.kt` (CalendarContract.Instances, nur auf dem Gerät, `READ_CALENDAR` erst beim Einschalten), `ui/CalendarViews.kt`: Einladung bzw. „From your calendar“ in Plan, Hinweis „Leave by …“ über den Routen, Schalter in Einstellungen → Calendar.
- [x] Nur Termine mit eindeutigem Ort (Feedback 2026-11-24): Kartenpin, Adresse (Straße + Nummer, PLZ + Ort, „Name, Ort“), oder ein gespeicherter Ort (Arbeit/Zuhause in allen App-Sprachen, sonst Name eines gespeicherten Orts). „Betriebsversammlung“, „Raum 3“ usw. erscheinen nicht. Tests: `CalendarTripsTest`.
- [x] Termine auch in der Zielsuche und in Nearby (2026-11-25, Publit#181): Die Zielsuche in Planen listet sie unter „From your calendar“ (`PlaceSearch(onAppointment)`, nur für das Ziel); Nearby zeigt in der Karte mit „Where to now?“ die Zeile „Next appointment: …“ mit Uhrzeit und Ort, wenn der nächste Termin in den nächsten vier Stunden beginnt (`CalendarTrips.next`, `nearbyDetail`), und öffnet Planen mit der Route. Nearby liest die Termine beim Erscheinen neu (höchstens einmal je Minute). Ein Text in `tools/strings-android.json` („Next appointment: %@“, Übersetzungen aus dem iOS-Katalog), bis IOS_COMMIT ihn enthält. Tests: `CalendarTripsTest`. Nicht am Gerät getestet.

## Community-Wünsche aus iOS Build 100 (2026-11-23, Android 0.11.0)
- [x] Verbindungen speichern (#28): `SavedConnection` (`publit.connections.v1`), Stern in Planen wenn Von und Nach Orte sind, Abschnitt „Connections“ in Gespeichert mit „Way back“ und eigener Reihenfolge
- [x] Kurzbefehle-Widget (#6): `widget/ShortcutsWidget.kt` (Glance), Zuhause, Arbeit, dann gespeicherte Orte (max. 4), Links `publit://go/…` und `publit://place?…`; folgt den gespeicherten Orten (`PublitApplication`)
- [x] Wallet-Pässe (#20): `tickets/WalletPassImport.kt` (pass.json aus der ZIP, Codes inkl. UIC-Bytes, Felder, Farbe), im Ticket-Import und beim Öffnen einer .pkpass aus anderen Apps (Intent-Filter)
- [x] Eigene Kurzbefehle mit Symbol (#22): `SavedPlace.shortcut/symbol`, `ui/ShortcutSymbols.kt`, in Gespeichert (Home und Work), Nähe, Planen und Widget
- [x] Linie mit Richtung (#21): `SavedLine.directionId/headsign`, Linie und Fahrt speichern in der gezeigten Richtung
- [x] Widget „Abfahrten in der Nähe“ (#19): `widget/NearbyWidget.kt`, gespeicherte Haltestellen bis 600 m zuerst; Standort aus der App-Berechtigung (letzter bekannter Standort im Hintergrund)
- [x] Wear-Komplikation (#18): `wear/…/PublitComplication.kt` (SHORT_TEXT/LONG_TEXT): Countdown der laufenden Fahrt, sonst ein Tipp zu Publit
- [x] Um ein Ticket planen (#23/#24): Ticket-Detail „Plan around this ticket“ → Weg zum Start (Ankunft 10 min vor Abfahrt) oder weiter ab dem Ziel (`tickets/TicketPlan.kt`); kein Abschnittsplaner wie auf iOS

## Ticket-Links (2026-11-24, iOS #148, #151, #168)
- [x] „Buy ticket: …“ je Betreiber unter den Fahrt-Aktionen (`data/TicketLinks.kt`, `TicketLinksCard`): Feed-Link → Tarifseite → Website → bekannter Shop; Partnerprogramme eingebaut, aber leer (wie iOS).
- [x] Vorausgefüllte Suche: DB öffnet bahn.de mit Start, Ziel und Abfahrt; FlixBus sucht beim Tippen die Städte-IDs (nur Haltestellennamen an FlixBus) und öffnet die Shop-Suche. Ein Verbund-Shop (MVV) schlägt die DB-Suche.
- Offen: eigene Ticket-Apps verknüpfen, Partnerliste vom Ticket-Link-Dienst laden, Ticket-Shop im App-Kauf.

## Umstiegs-Chance: Schätzungen mit „≈“ und Prozent (2026-11-25, iOS Build 99 `SmartChangesSection`, Publit#186)
- [x] `data/SmartRouting.kt` `Change.percentLabel`, Umstiegs-Karte im Routendetail (`ui/PlanScreen.kt` `ChangesCard`): Ein geschätzter Umstieg zeigt „≈ NN %“ in der Farbe seiner Einschätzung (vorher keine Zahl), ein gemessener „NN %“ wie bisher. Fährt dasselbe Fahrzeug weiter, steht keine Zahl mehr da („Stay on board“ sagt es, wie iOS). Die Karte erscheint auf Android schon unabhängig vom Smart Routing. Tests: `ChangePercentLabelTest`. Kein neuer Text, kein „What's new“-Eintrag, `versionName` unverändert. Nicht am Gerät getestet.

## Ohne Fernzüge (2026-11-24, iOS #119)
- [x] Routenoptionen → „Without long-distance trains“ unter den Verkehrsmitteln (`RouteOptions.regionalOnly`, Port von `TransitFilter.swift`): Die Suche fragt keine Hochgeschwindigkeits-, Fern- und Nachtzüge an (wie bei den Tickets auch kein „RAIL“), und Routen mit einem Fernzug nach Name oder Betreiber fallen aus der Antwort (`RouteOptions.allows`, `filtersRides`). Gespeichert mit den übrigen Optionen; alte Optionen laden ohne den Schalter. Tests: `OwnTicketsTest`. Zwei Texte in `tools/strings-android.json` (Übersetzungen aus dem iOS-Katalog), bis IOS_COMMIT sie enthält. Nicht am Gerät getestet.

## Feedback 2026-11-16 (iOS PR #87)
- [x] Live-Fahrt: unter der Etappe keine Liste aller Etappen mehr; Tasten „Back to now“ und „Trip overview“; Etappenliste nur in der Übersicht
- [x] Karte je Etappe an ihrem Ort (Warten/Umstieg: Einstiegshalt); nur auf dem aktuellen Fußweg nahe dem Weg folgt sie dir (`walkWith`)

## Status 2026-10-30
- DONE: Coverage atlas (areas without timetable data) on the map, Plan, route details, Nearby and stops.
- DONE: Home/Work shortcuts in the Plan form; Nearby "Where to now?", route-here; settings on every tab; launch animation.
- DONE: Website screenshots (workflow "Website-Screenshots erstellen", `screenshots/README.md`): build type `screenshots` (demo data server on 10.0.2.2, no extra live sources, onboarding skipped), link `publit://map` opens the station map. Status: the workflow runs green on `native-android` (emulator Pixel 8 Pro screen, API 35, 4 views × DE/EN, framed + posters + WebP; also on `main` for "Run workflow"). The AVD lives in `~/.android/avd` (explicit `ANDROID_AVD_HOME`), checks use destination names, not the white-on-colour line badges that OCR cannot read.
- OPEN: siehe „Weiterhin offen“ oben (Stand 2026-11-16). Alte Liste:
  1. LiveTripView map-first port (StagePager/StageStrip, TripPanel, GuideMap, GuideStopRow, TransferCard) from `Sources/Views/LiveTripView.swift`.
  2. Load-time investigation (LiveOverlay budget per call, MapLibre init).
  3. Full iOS feature audit: GoNowView, LinesView/LineDetailView, SharedRouteView, ConnectionGuideView, RecentSearches, WalkThereRow, ArrivalUpdates, DataExport, Diagnostics, ServiceAlertsView, "Open in maps".
  4. Better satellite imagery: needs a decision and key from the owner (Google Maps SDK, MapTiler or Esri).

## iOS Build 82–84 nachgezogen (2026-11-16, Android 0.9.0, IOS_COMMIT 9848d21)
- [x] Von/Nach „Choose on map“ (#67): `ChooseOnMap.kt`, Adresse über MOTIS reverse-geocode (`MapPick`), PR #7
- [x] Abfahrt „Track vehicle“ mit Positionsquelle (#68): `VehicleTracking.kt`, `VehiclePositionSource`, PR #8
- [x] Live-Fahrt: kein zweiter Fahrzeugpunkt im Fahrzeug (7104c05): Port von `TripProgress.located` + `TripGuide.ridePosition` (Standort auf die Linie gelegt), PR #9
- [x] Aussteige-Erinnerung nach Standort (#69): `GetOffWatch.kt`, `notify/GetOffAlerts.kt` (`GetOffService`, Vordergrund-Dienst Typ location, nur während einer gestarteten Fahrt mit noch kommender Fahrt), Einstellung „Started trips“, „Next stop … · n stops to go“ in der Live-Mitteilung, PR #10. Anders als iOS läuft der Standort ab Fahrtbeginn (Android darf ihn später nicht aus dem Hintergrund starten). Play Console: Begründung für FOREGROUND_SERVICE_LOCATION nötig (Owner).
- [x] Fußweg-Navigation (#66): „Start walking navigation“, nächste Abbiegung über der Karte, Sprachknopf auf der Karte, „Follow my location“ (`PublitMap.onUserMove`), PR #11
- [x] Wischaktionen im Dunkelmodus (#64): Android hat Langdruck-Menüs statt Wischaktionen; „Remove“ dort rot, PR #12
- [x] Veralteter Abdeckungsatlas (#76): Atlas-Download von publit.eu wie `CoverageAtlasStore` (≤1×/20 h), Recheck ≤1×/h, Hinweis entfällt bei ≥8 Lokallinien, PR #12
- [x] Park & Ride statt 2 h Fußweg ohne Halt in Gehweite (iOS PlanModel), Hinweistext über den Routen, PR #13
- [x] UX-Feedback 2026-11-03 aus PR #3 (Fahrtansicht, Suchknopf, Zeitreihenfolge, ÖBB-Linien), PR #14; #3 geschlossen
- [x] Strings: IOS_COMMIT 9848d21, Android-eigene Texte in `tools/strings-android.json` (15 Sprachen), keine englischen Platzhalter mehr; CI läuft auch für PRs (PR #6)
- [x] What's new Release 3, versionName 0.9.0

## Weiterhin offen (ehrlich)
1. Live-Fahrt mit der Karte im Mittelpunkt wie iOS `LiveTripView`: Android hat die Karte mit Bottom-Sheet und Etappenkarten, aber nicht StagePager/StageStrip, TripPanel, GuideStopRow und die iOS-Kamera, die beim Gehen dem Standort folgt (Android zeigt den ganzen Fußweg, folgt nur mit „Turn the map with you“).
2. Ladezeiten: nicht untersucht (LiveOverlay-Budget pro Aufruf, MapLibre-Start auf älteren Geräten wie dem Mate 20 Lite). Neu hinzugekommen: Atlas-Download beim Start (im Hintergrund-Thread, ≤1×/20 h).
3. Vollständiger Abgleich mit iOS: nur per Textsuche gegen `docs/FUNKTIONEN.md` (Stand #44) und die Build-Commits; kein Schirm-für-Schirm-Vergleich. Bekannt fehlend: Fußweg-Umweg über Karten-App, „Choose on map“ auch im Von-Feld von Go Now (Android-Go-Now hat kein Von-Feld).
4. Nichts davon wurde auf einem echten Gerät getestet (nur Unit-Tests und CI-Build).
5. Besseres Satellitenbild (Entscheidung und Schlüssel vom Owner), Play-Upload-Key als CI-Secret, danach `Sources/` entfernen.

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
- [x] Plan: "Last" chip = tonight's last connection (`data/LastConnection.kt`, iOS `LastConnection.swift`, Publit #161): arrive by 4 a.m., departed rides dropped, "☾ Last connection" on the route that leaves last
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
- [x] Park & Ride wie iOS (`ParkAndRide.kt`, iOS Build 66 + PR #77): erst max. 15 min Fahrt, ist der nächste Bahnhof weiter als 5 min, zweite Suche bis nächster Bahnhof + 10 min (max. 25), damit ein zweiter Bahnhof an anderer Linie mitkonkurriert; „fast ganz hingefahren“ und weiter entfernte Bahnhöfe fallen raus, auch beim Nachladen. Der iOS-Fallback „kein Halt in Gehweite → Park & Ride vor 2 h Fußweg“ mit Hinweistext ist seit PR #13 drin

## iOS Build 87–88 nachgezogen (2026-11-17, Android 0.10.0): gemessene Umstiegs-Chancen, Warnungen, Lernen
Quelle: iOS PRs #92–#94 (`DelayHistory.swift`, `TransferWalk.swift`, `SmartRouting.swift`, `TripLearning.swift`, `ChangeWatch.swift`, `ChangeRiskNotifier.swift`).
- [x] `data/DelayHistory.kt`: Verspätungstabellen je Land von publit.eu (gzip, ≤1×/20 h je Land, nur Länder der geplanten Routen, alte Datei bleibt), Nachschlagen Halt → Linie → Betreiber → Verkehrsmittel, Prognosefehler nach Vorlauf.
- [x] `data/TransferWalk.kt`: Fußweg-Merkmale (Ebenen, Aufzüge, Gleiswechsel, Bahnhofswechsel, große Station) und `WalkModel` mit Unsicherheit – gleiche Startwerte und Formel wie iOS.
- [x] `SmartRouting.chance`: Faltung Zubringer-Ankunft + Fußweg ≤ Anschluss-Abfahrt, Live-Prognose, Ausfallquoten, Durchbindung (gleiche tripId; Android-Leg hat kein `interlineWithPreviousLeg`), Deckel bei wenigen Beobachtungen, Rückfall auf die alte Formel mit „Estimate“ statt Prozent. Quelle klein unter jedem Umstieg.
- [x] `data/TripLearning.kt`: „Learn from my trips“ (aus, nur auf dem Gerät, `files/trip-learning.json`, keine Spur), Bayes-Gehmodell, eigene Erfolgsquote; `ChangeWatch` (Schwelle 70 %, einmal je Umstieg, erneut bei −15 Punkten oder Ausfall, nie bei Durchbindung).
- [x] `notify/ChangeAlerts.kt`: Mitteilung mit Link `publit://alternatives?…` → Plan ab Umstiegshalt bzw. hier zum Ziel; Neuberechnung bei jedem Trip-Refresh und bei Standort-Fixen im `GetOffService` (läuft jetzt auch für „Learn from my trips“ von der ersten bis zur letzten Fahrt).
- [x] Einstellungen: „Learn from my trips“, „Change alerts“; Reset löscht auch das Gelernte.
- [x] Tests `ChangeChanceTest` mit denselben Vektoren wie Python/iOS (`test/resources/change-chance-vectors.json`, Kopie von `services/delay-history/tests/vectors.json` im iOS-Repo).
- Android-Grenzen: ohne gestartete Fahrt (Vordergrund-Dienst) rechnet Android nur bei Trip-Refreshs in der App und beim Live-Tick neu; Doze verzögert Alarme. Keine Server-Pushes.
- Strings: `upstream/IOS_COMMIT` auf den iOS-Stand mit den neuen Texten.

## Gespeicherte Adressen mit gleicher ID (2026-11-23, Issue #21, iOS #59 und #121)
- [x] `data/FavoritesStore.kt`: Regeln als reine Funktionen in `SavedPlaces` (`set`, `toggle`). Zwei Adressen mit derselben Geocoder-ID sind nur am selben Ort (unter 30 m) derselbe Platz; ist die ID vergeben, bekommt der neue Eintrag eine eigene (`<id>|<lat>,<lon>`, für Home/Work `home|…` bzw. `work|…` wie iOS `slotID`). Work ersetzt Home nicht mehr, weitere Adressen erscheinen, Speichern entfernt keine andere Adresse. Haltestellen wie bisher über die ID. Gespeicherte Daten bleiben, wie sie sind.
- [x] Tests `SavedPlacesTest` (dieselben Fälle wie iOS `CoreTests`).
- Offen: am Gerät prüfen (zwei Hausnummern derselben Straße speichern, eine als Work setzen). Kein „What's new“-Eintrag, `versionName` unverändert.

## Onboarding-Text ohne „across Europe“ (2026-11-23, iOS #137, PR #140)
- [x] `ui/SettingsScreen.kt`: erste Seite sagt „routes wherever you go“ statt „routes across Europe“, wie iOS. Publit plant überall, wo Transitous Daten hat.
- [x] Strings: der neue Satz steht mit 14 Übersetzungen (aus dem iOS-Katalog, Commit 0109af5) in `tools/strings-android.json`, bis der Katalog hier den iOS-Stand hat; dann gewinnt der Katalog von selbst.
- Kein „What's new“-Eintrag, `versionName` unverändert.

## Eigene Reihenfolge unter „Saved“ (2026-11-23, iOS #124, PR #154)
- [x] `data/FavoritesStore.kt`: `SavedOrder.moved` (wie iOS `FavoritesStore.moved`: nur Einträge einer Art tauschen untereinander die Plätze; Haltestellen, Orte, Home und Work bleiben eine gespeicherte Liste) und `SavedOrder.stepped` (ein Platz hoch oder runter); `FavoritesStore.moveStop/movePlace/moveLine`. Speicherformat unverändert, neue Einträge kommen weiter ans Ende.
- [x] `ui/SavedScreen.kt`: Linien, Haltestellen und Orte bekommen im Langdruck-Menü „Move up“ und „Move down“ (nur wo es einen Platz gibt). Anders als iOS kein Ziehen: Android hat hier Langdruck-Menüs, wie bei „Remove“.
- [x] Strings: „Move up“, „Move down“ mit 14 Übersetzungen in `tools/strings-android.json` (gibt es im iOS-Katalog nicht).
- [x] Tests `SavedOrderTest` (dieselben Fälle wie iOS `FavoritesOrderTests`, dazu ein Schritt hoch/runter und die Enden).
- Offen: am Gerät prüfen (drei Haltestellen speichern, die letzte nach oben schieben, App neu starten). Kein „What's new“-Eintrag, `versionName` unverändert.
- iOS #115 (Straße neben gleichnamiger Haltestelle in der Suche): nichts zu portieren, Android sucht nur über Transitous und führt keine zweite Quelle zusammen.

## Routendetail Layout A: Aktionsleiste unten, Tickets nach den Schritten (2026-11-24, iOS PR #170)
- [x] `ui/TripActions.kt`: `TripActionBar` ersetzt die Kachel-Karte `TripActions`: feste Leiste am unteren Rand (Fläche mit Haarlinie oben, über der Navigationsleiste), Speichern, Kalender, Teilen, Erinnerung als 48-dp-Quadrate (nur Symbol, TalkBack liest den Namen), daneben „Start trip“ in Tinte (zu Fuß: „Start“ mit Gehsymbol). Läuft die Fahrt: Teilen und roter Knopf „End trip“. Hinweise („Trip ended“, „We'll remind you at …“, Grund für gesperrten Start) klein über der Leiste.
- [x] `ui/PlanScreen.kt` `ItineraryDetail`: neue Plätze `tickets` (nach den Schritten, vor den Preisen) und `bar` (unter der Liste). Plan, „Where to now?“, geteilte Route und gespeicherte Fahrt nutzen sie; `TicketLinksCard` steht jetzt am Ende statt unter den Aktionen.
- Keine neuen Texte (alle Schlüssel gibt es im iOS-Katalog). Offen: am Gerät prüfen (lange Reise mit vielen Umstiegen, Fahrt starten und beenden). Kein „What's new“-Eintrag, `versionName` unverändert.

## Live-Fahrt Layout A und Einstellungen Layout A (2026-11-24, iOS PR #170)
- [x] `ui/LiveTrip.kt`: die Etappen-Chips (`StageStrip`) sind weg. Unter der Etappenkarte steht die nächste Umsteigekarte (`NextChangeCard`: wo, Minuten Puffer groß, Gleise, Linie, wie sicher); ein Tipp öffnet die Warte-Etappe. Die Zeitleiste „Trip overview“ steht immer im Sheet, jeder Umstieg als eigene Zeile (`LiveChangeRow`) zwischen den Fahrten. Keine „3/8“-Zähler mehr. Sheet-Höhe wächst um die Karte.
- Offen: Ausgänge der U-Bahn in der Umsteigezeile (iOS `ExitAdvice`) gibt es auf native-android noch nicht.
- [x] `ui/SettingsScreen.kt` (Kalender-Abschnitt aus #36 steht unter Reisen): Startseite mit Suche, Community-Karte und Kategorien (Allgemein, Reisen, Karte, Benachrichtigungen, Daten, Diagnose, Über); jede Kategorie ist eine eigene kurze Seite mit den Gruppen von vorher, Zurück führt zur Startseite. Die Suche findet jede Einstellung ohne Groß-/Kleinschreibung und Akzente (`SettingsCategory.search`).
- [x] Strings: „Search settings“ und „No settings match your search.“ mit 14 Übersetzungen aus dem iOS-Katalog in `tools/strings-android.json`, bis IOS_COMMIT den Stand von iOS #170 hat. Alle anderen Texte gibt es schon im Katalog.

## Design-Durchgang Tickets, Gespeichert, Station, Einstellungen, Community (2026-11-24, iOS PR #170)
- [x] Nebenzeilen in Grau (Fußnoten, Untertitel, Hinweise) mindestens 13 sp wie `footnote` auf iOS (docs/design/README.md §9), vorher 11–12 sp.

## Stationskarte flüssiger aus „In der Nähe“ (2026-11-24, iOS Build 102, Publit#173)
- [x] `ui/NearbyMap.kt`: das Sheet mit der Liste lässt sich jetzt ganz nach unten ziehen (`skipHiddenState = false`). Erst wenn es nach dem Loslassen unten liegt, öffnet sich die Stationskarte, nie mitten im Ziehen; ein kurzes Vibrieren, sobald Loslassen sie öffnen würde. Zurück auf „In der Nähe“ steht die Liste wieder auf halber Höhe und öffnet die Karte nicht erneut. Die Liste im Kartenmodus hat kein Ziehen-zum-Aktualisieren, also kein Konflikt wie auf iOS.
- [x] What's new Release 7, versionName 0.12.1. Strings: Titel aus dem iOS-Katalog (#173), Satz Android-eigen, beide mit 14 Übersetzungen in `tools/strings-android.json`.
- Offen: am Gerät prüfen.

## Hinweis auf lange Fußwege über den Routen (2026-11-24, iOS `Reach.longWalkNote`, Publit#178)
- [x] `data/StopGrouping.kt` `Reach.longestAccessWalk` und `Reach.longWalkNote` (Port von `Reach.swift`): längster Fußweg zur ersten oder von der letzten Haltestelle über alle Routen mit Fahrt; Rad, Roller und Auto zählen nicht, Routen nur zu Fuß auch nicht. `PlanViewModel.plan()` setzt die Zeile „These routes include a long walk: up to … min to or from the stop.“ über die Routen, wenn mit einem Fußweg-Limit über 30 min gesucht wurde (nichts im üblichen Umkreis gefunden, oder das Limit in den Routenoptionen steht höher) und der längste Fußweg über 30 min liegt. `PlanState.noteIsWalk`: Fußgänger-Symbol statt Auto. Anders als iOS: gemessen an den gezeigten Routen (nach Smart Routing, ohne Umwege), nicht an der rohen Antwort. Die Park-and-Ride-Zeile bleibt.
- Tests: `LongWalkNoteTest`. Kein neuer Text (Schlüssel aus dem iOS-Katalog), kein „What's new“-Eintrag, `versionName` unverändert. Nicht am Gerät getestet.
