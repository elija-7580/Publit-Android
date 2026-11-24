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

## Kalendertermine als Ziele (2026-11-24, iOS #150 und #169)
- [x] `data/CalendarTrips.kt` (Logik wie iOS `CalendarTrips.swift`), `location/CalendarSuggestions.kt` (CalendarContract.Instances, nur auf dem Gerät, `READ_CALENDAR` erst beim Einschalten), `ui/CalendarViews.kt`: Einladung bzw. „From your calendar“ in Plan, Hinweis „Leave by …“ über den Routen, Schalter in Einstellungen → Calendar.
- [x] Nur Termine mit eindeutigem Ort (Feedback 2026-11-24): Kartenpin, Adresse (Straße + Nummer, PLZ + Ort, „Name, Ort“), oder ein gespeicherter Ort (Arbeit/Zuhause in allen App-Sprachen, sonst Name eines gespeicherten Orts). „Betriebsversammlung“, „Raum 3“ usw. erscheinen nicht. Tests: `CalendarTripsTest`.
- Offen: Termine auch in der Zielsuche und in Nearby (iOS hat beides).

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
3. Vollständiger Abgleich mit iOS: nur per Textsuche gegen `docs/FUNKTIONEN.md` (Stand #44) und die Build-Commits; kein Schirm-für-Schirm-Vergleich. Bekannt fehlend: Hinweis auf lange Fußwege über den Routen (`Reach.longWalkNote`), Fußweg-Umweg über Karten-App, „Choose on map“ auch im Von-Feld von Go Now (Android-Go-Now hat kein Von-Feld).
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
