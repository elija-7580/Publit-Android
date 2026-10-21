# Data sources beyond Transitous

Research notes (checked from a server in Europe, 14 Oct 2026). "Works" means the endpoint answered with data without a key at that time. Feeds change; check again before relying on one.

## 1. Bike, scooter, moped and car sharing

### What Publit already gets
Transitous (`/api/v1/rentals`, used by the app) imports GBFS feeds listed in its feed config (mostly via transitland-atlas ids). Coverage by city (30 × 30 km box around the centre):

| City | In Transitous today |
|---|---|
| Munich | Call a Bike, Dott, MyRadl |
| Berlin | Call a Bike, Dott, Flinkster, nextbike |
| Hamburg | Flinkster only (no StadtRAD) |
| Cologne | Call a Bike, Flinkster, Zeus, nextbike |
| Vienna | WienMobil Rad only |
| Zurich | Bolt, Dott, Lime, Voi, Bird, Mobility, Velospot, carvelo, Zeus, … |
| Paris | Dott, Lime, Vélib', Voi, Zeus |
| Lyon | Citiz, Dott, Leo and Go, Vélo'v, Zeus |
| Brussels | Villo!, Zeus |
| Amsterdam, London, Madrid, Lisbon, Milan | Zeus only |
| Barcelona | Clem', Zeus |
| Oslo | Bolt, Getaround, Hyre, Oslo Bysykkel, Ryde, Voi |
| Stockholm | Voi |
| Budapest | MOL Bubi |
| Dublin, Rome, Copenhagen, Warsaw, Prague, Tallinn, Riga, Vilnius | nothing |

### Public GBFS feeds that work without a key but are missing in Transitous

| Provider | Cities | Auto-discovery URL |
|---|---|---|
| Bird | Munich, Madrid, Barcelona, Lisbon, Milan, Rome, Helsinki | `https://mds.bird.co/gbfs/v2/public/<city>/gbfs.json` |
| Donkey Republic | Munich, Copenhagen, Budapest (catalogue lists more) | `https://stables.donkey.bike/api/public/gbfs/3.0/donkey_<city>/gbfs.json` |
| Dott | Cologne, Brussels, Copenhagen | `https://gbfs.api.ridedott.com/public/v2/<city>/gbfs.json` |
| KVB-Rad (nextbike) | Cologne | `https://gbfs.nextbike.net/maps/gbfs/v2/nextbike_kg/gbfs.json` |
| Bolt | Brussels | `https://mds.bolt.eu/gbfs/3/336/gbfs` |
| Lime | Brussels | `https://data.lime.bike/api/partners/v2/gbfs/brussels/gbfs.json` |
| Felyx (mopeds) | Brussels, Amsterdam | `https://maas.zeus.cooltra.com/gbfs/<city>/3.0/en/gbfs.json` |
| Cooltra (mopeds) | Madrid, Rome (catalogue lists Milan, Barcelona) | same pattern as Felyx |
| Check (mopeds) | Netherlands | `https://api.ridecheck.app/gbfs/v3/<city>/gbfs.json` |
| Dublinbikes, Vilnius Cyclocity (JCDecaux) | Dublin, Vilnius | `https://api.cyclocity.fr/contracts/<city>/gbfs/v3/gbfs.json` |
| Moby | Dublin | via `api.sharebike.com` (see MobilityData catalog) |
| BiciMAD, Bicing | Madrid, Barcelona | `https://<city>.publicbikesystem.net/customer/gbfs/v3.0/gbfs.json` |
| AMBici (nextbike) | Barcelona area | `https://gbfs.nextbike.net/maps/gbfs/v2/nextbike_bs/gbfs.json` |
| BikeMi | Milan | `https://gbfs.urbansharing.com/bikemi.com/gbfs.json` |

Not usable without an agreement or key: StadtRAD Hamburg (DB API Marketplace "Shared Mobility GBFS", free key), Pony (key), Bleeper Dublin (HTTP 421), most Voi cities (random feed ids, 401), Lime Hamburg (404). Lime, Bolt and Voi publish open GBFS only where a city requires it.

### Catalogues and aggregators
- **MobilityData GBFS catalogue** – `https://raw.githubusercontent.com/MobilityData/gbfs/master/systems.csv`: 1,263 systems, 1,013 in Europe, almost all without authentication. The best list to keep Publit's coverage current.
- **MobiData BW** – `https://api.mobidata-bw.de/sharing/gbfs/`: 106 systems for Baden-Württemberg and neighbours (incl. Voi, Bolt, Lime, Bird), no key.
- **CityBikes** – `https://api.citybik.es/v2/networks`: docked bike share worldwide, free, no key (own format, not GBFS).

### Recommendation
1. **Upstream first:** add the missing feeds to Transitous (pull request to `public-transport/transitous`, `feeds/<country>.json`, entries with `"spec": "gbfs"` and the auto-discovery URL). Publit then gets them on the map and in routing without app changes, and so does everyone else.
2. **Optional app-side fallback:** fetch GBFS `free_bike_status` / `vehicle_status` and `station_status` directly for the visible map area from a small curated list (above), only where Transitous has nothing. Cache 60 s, respect `ttl`.

## 2. Backup timetable and journey APIs (if Transitous is down or thin)

| API | Coverage | Key | Status 14 Oct 2026 | Notes |
|---|---|---|---|---|
| `v6.vbb.transport.rest`, `v6.bvg.transport.rest` | Berlin/Brandenburg | no | works | HAFAS-based REST (journeys, departures, live) |
| `v6.db.transport.rest` | Germany (DB) | no | **down (503)** | community service, unreliable |
| `transport.opendata.ch` | Switzerland | no | works | connections, stationboard |
| Entur Journey Planner v3 (GraphQL) `api.entur.io/journey-planner/v3/graphql` | Norway | no (header `ET-Client-Name`) | works | full routing incl. realtime |
| TfL Unified API `api.tfl.gov.uk` | London | optional (rate limit) | works | journeys, arrivals, BikePoint (Santander Cycles), lift disruptions |
| Wiener Linien OGD `wienerlinien.at/ogd_realtime/monitor?stopId=` | Vienna | no | works | realtime departures, elevator outages |
| SL Transport `transport.integration.sl.se/v1` | Stockholm | no | works | departures, deviations |
| iRail `api.irail.be/v1/connections/` | Belgium (SNCB) | no | works | connections, liveboard |
| Self-hosted MOTIS (`europe.motis-project.de` demo answers) | Europe | – | works (demo) | same engine and API as Transitous; a second instance with Transitous' config is a drop-in backup |
| Digitransit | Finland | free key | – | |
| Navitia / SNCF API | France / SNCF | free token | 401 without | |
| Rejseplanen | Denmark | free key | – | |
| Trafiklab ResRobot | Sweden | free key | – | |
| NS API | Netherlands | free key | – | |
| DB API Marketplace (Timetables, StaDa, FaSta) | Germany | free key | – | also elevator status (FaSta) and StadtRAD |
| IDFM PRIM | Île-de-France | free key | – | realtime, disruptions |
| Golemio | Prague | free key | – | realtime vehicles |
| BKK Futár | Budapest | free key | – | |
| UK Bus Open Data (BODS) | England buses | free key | 401 without | timetables + SIRI-VM vehicle positions |
| National Rail Darwin | Great Britain rail | free key | – | |
| Transport for Ireland | Ireland | free key | – | GTFS-RT |
| OASA telematics `telematics.oasa.gr/api/` | Athens buses | no | no answer from the test server | fills the Athens gap (see COVERAGE.md); test from a device |
| İBB open data `data.ibb.gov.tr` | Istanbul | no | works (catalogue) | GTFS and IETT data sets |
| ViaggiaTreno | Italy (Trenitalia) | no | 403 from the test server | unofficial |
| Transitland, Mobility Database API | worldwide feed catalogues | free key | – | for finding feeds, not routing |

### In the app (build 29)
`TransitousClient` fails over to `https://europe.motis-project.de` (MOTIS project, TU Darmstadt, separate hosting from Transitous). Checked 14 Oct 2026: plan, stoptimes, trip, geocode, map/stops, map/trips and rentals all answer with the same IDs and with live times. Used only on connection failures, HTTP 429 and 5xx; backup first for 3 minutes after a failure. **Before a public release, ask the MOTIS team whether an app may use this server as fallback** (it is their demo instance); the list of backup servers is one constant (`TransitousClient.backupServer`), so an own MOTIS instance can replace it.

### Recommendation
- Keep Transitous as the only source for routing. Add a small **fallback layer** used only when Transitous fails or returns nothing: per country one adapter (Entur, opendata.ch, TfL, Wiener Linien, SL, VBB/BVG, iRail) for departures first, journeys later. Services that need a key must not ship the key in the app (proxy or user-supplied).
- Longer term the most robust backup is a second MOTIS instance with the Transitous configuration.

## 3. Other useful data

- **Station entrances, exits, elevators, platforms (OpenStreetMap)** via Overpass (`overpass-api.de`, `overpass.kumi.systems`): `railway=subway_entrance`, `highway=elevator`, `public_transport=platform`. Free, no key; the main instance was overloaded (504) during the test, so cache results per station and use a fallback instance. Would improve the guide's "Exit towards …" hints.
- **Elevator status:** DB FaSta (key), Wiener Linien OGD, TfL lift disruptions.
- **Occupancy:** Entur and some GTFS-RT feeds carry `occupancy_status`; Transitous passes it through where present.
- **EU National Access Points** (official open data per country, mostly GTFS/NeTEx/GBFS): Mobilithek (DE), transport.data.gouv.fr (FR, API works without key), opentransportdata.swiss (CH), NDOV (NL), Entur (NO), Trafiklab (SE), Fintraffic (FI), nap.transportes.gob.es (ES), data.gov.gr (GR). Good places to find feeds for Transitous gaps.

## 4. Comparison with Abfahrt! (2026-10-17)

[Abfahrt!](https://www.abfahrt.now/attributions/) shows live departures across Europe from operator interfaces (TRIAS, HAFAS, EFA, SIRI, own APIs) rather than one GTFS router. Its sources line up with the gaps measured for Transitous (live share of departures, 2026-10-06):

| Gap in Publit (live share) | Source used by Abfahrt! | Access | Next step |
| --- | --- | --- | --- |
| Vienna (0–1 %) | Wiener Linien OGD, CC BY | no key, per stop (`monitor?stopId=`) | map Transitous stop IDs to RBL numbers; departure overlay, then a GTFS-RT converter for Transitous like `feeds/amtab-gtfsrt` |
| ÖBB / Krems (0–1 %) | ÖBB | HAFAS is not an official open API | check Mobilitätsdaten Österreich for an official real-time feed first |
| London Tube (0 %) | TfL Open Data | optional key | "Powered by TfL Open Data" attribution |
| Milan (0 %) | ATM Milano | to check | licence and endpoint |
| Stockholm (0 %) | Trafiklab, CC0 / CC BY 4.0 | free key | key via proxy, not in the app |
| Copenhagen (0 %) | Rejseplanen | free key | terms |
| Madrid (17 %) | EMT Madrid, CRTM, Renfe | key | |
| Bari | AMTAB | no key | converter built, deploy open |
| Rome, Naples | Roma Mobilità, ANM | to check | is the feed active in Transitous? |

Venice (ACTV ferries) is missing at Abfahrt!; Transitous has the timetable (no live times).

Abfahrt!'s licence column is coarse ("Open Data"): verify each licence before use. Order: Wiener Linien → ÖBB → TfL → ATM Milano → Trafiklab → Rejseplanen. A source that can be turned into GTFS-RT goes to Transitous (better routing and transfers for everyone); only otherwise an in-app overlay for departure boards. Every source used is credited in Settings → Data.

## 5. Extra live sources (build 47)

Transitous stays the router. Where its feed has no live time for a ride, `LiveOverlay` (Sources/API/LiveSources.swift) asks the operator's own system for the same ride (same line, timetable time within 90 s) and takes over the live departure, a cancellation and the platform; the arrival is estimated from the departure delay, and walks after the ride move with it. Alerts (diversions, closures, construction, disruptions) are added in any case, without duplicates. A live time from Transitous always wins. Lookups run side by side with a time budget (routes: shown first, corrected about a second later; boards: 2 s; live trip refresh: 3 s), answers are cached for 30–90 s, a failing server is left alone for a minute. Only the stop, line and time of a ride are sent; no account, no key.

| Area | Source | What | Terms |
|---|---|---|---|
| Vienna | Wiener Linien OGD `ogd_realtime/monitor` (stop by DIVA from Transitous' stop ID) | Live countdowns U-Bahn, tram, bus; current disruptions per line | Open data, CC BY 4.0 Stadt Wien – data.wien.gv.at |
| Austria | ÖBB HAFAS `fahrplan.oebb.at/bin/mgate.exe` (LocGeoPos, StationBoard) | Live times of trains (ÖBB, WESTbahn, GKB …) and of buses/trams where the regional network reports them (e.g. VVT Innsbruck); cancellations; platforms; HIM notes (construction, rail replacement) | Not an official open API (the ÖBB app's backend); used sparingly, cached. If it changes, rides keep their timetable times |
| Switzerland, Liechtenstein (build 58) | search.ch `timetable.search.ch/api/stationboard.json` (stop by Swiss number from Transitous' `ch:1:sloid:NNNNN` → `85NNNNN`; otherwise `completion.json?latlon=` within 100 m, only for Swiss time zones) | Live delays (`dep_delay` "+N"), cancellations ("X") and track changes of trains, trams, buses, boats, cable cars; asked only for rides without a Transitous live time | Free, no key; limit 10 080 departure boards per day (per client), cached 30 s |
| North Rhine-Westphalia (build 58) | VRR EFA `efa.vrr.de/standard/XML_DM_REQUEST?outputFormat=rapidJSON` (stop by DHID `de:05xxx:nnnn` from Transitous' stop ID) | Live times of monitored rides (`isRealtimeControlled`), cancellations, platforms, line notes; asked only for rides without a Transitous live time | Open EFA interface, no key; cached 30 s |
| Berlin, Brandenburg (build 58) | VBB HAFAS `fahrinfo.vbb.de/bin/mgate.exe` (LocGeoPos, StationBoard; stops with DHID `de:11…`/`de:12…`) | Live times, cancellations, platforms, notes; asked only for rides without a Transitous live time | Not an official open API (VBB web app backend); cached |
| Hesse (build 58) | RMV HAFAS `www.rmv.de/auskunft/bin/jp/mgate.exe` (DHID `de:06…`) | Same, incl. NVV (Kassel) | Not an official open API (RMV web app backend); cached |
| Lower Saxony, Bremen (build 58) | VBN HAFAS `fahrplaner.vbn.de/bin/mgate.exe`, request signed with `mic`/`mac` (MD5) (DHID `de:03…`/`de:04…`, or Transitous' `de-VBN_` stop IDs) | Same, incl. GVH (Hannover) | Not an official open API (VBN app backend); cached |
| Schleswig-Holstein (build 58) | NAH.SH HAFAS `nahsh.hafas.cloud/gate` (DHID `de:01…`) | Same | Not an official open API (NAH.SH app backend); cached |
| Rhineland-Palatinate (build 58) | VRN EFA `www.vrn.de/mngvrn/XML_DM_REQUEST?outputFormat=rapidJSON` (DHID `de:07…`) | Same as VRR | Open EFA interface, no key; cached 30 s |
| Venice | ACTV `oraritemporeale.actv.it/{nav,aut}/backend` | Live countdowns of vaporetti and buses where ACTV has them | Unofficial (the public departure screens) |
| Stockholm | SL Transport API `transport.integration.sl.se/v1` | Live departures (metro, pendeltåg, tram, bus, boat), cancellations, track, deviations | Open, no key (Trafiklab) |
| London | TfL Unified API `Line/Mode/…/Status` | Line status (delays, closures, part suspensions) for Underground, Overground, DLR, Elizabeth line, trams | Open data, "Powered by TfL Open Data" |

Checked but not used (build 58): bahn.de's web API (blocks non-browser clients, "OPS_BLOCKED"); VRR EFA outside NRW (adds no live times there, e.g. Berlin, Dresden, Chemnitz, Kiel: 0 of 40); Bavarian (bahnland-bayern.de), BW (efa-bw.de), VVO, KVV, VVS, DING and MVV EFA servers and INSA (Saxony-Anhalt) HAFAS (no more live times than Transitous already has there, e.g. Kempten 2/15 vs 1/15, Pforzheim 25/40 vs 22/44, Magdeburg 12/14 vs 9/13); NAH.SH's old `nah.sh.hafas.de` (moved). Checked but not used: ViaggiaTreno (Trenitalia; blocks server requests, may work from devices – to test), Deutsche Bahn (Germany is already live through DELFI), iRail disturbances (redirects). Next candidates: Milan ATM, Copenhagen (Rejseplanen, needs key), Lisbon Carris, Brussels STIB (key), Prague is already live.
