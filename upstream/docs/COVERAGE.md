# Coverage in Europe (Transitous)

Publit plans with [Transitous](https://transitous.org), which merges open timetable (GTFS) and live (GTFS-RT, SIRI) feeds. What a city gets depends entirely on what its operators publish. Checked on 14 October 2026: the feed list of the Transitous repository (`feeds/*.json`), one trip across the centre of 68 cities (weekday 08:00 local time) and the departures within 600 m of the centre at about 18:30 local time.

These are samples. "No live data" may also mean the feed was down at that moment; check again before telling users.

## No timetable data

| Place | Finding |
|---|---|
| Athens | Feeds are listed (Athens-Bus, Athens-Rail), but no stop is loaded: no routes, no departures. Thessaloniki works. |
| Malta | No feed. |
| Belarus (Minsk) | No feed. |
| Russia | No feed (not tested). |

## Only part of the network

| City | In the data | Missing |
|---|---|---|
| Istanbul | IETT buses | Metro, Marmaray, trams, funiculars, ferries |
| Kyiv | City buses (Kyivpastrans) | Metro, trams, suburban trains |
| Pristina | Trains (Trainkos) | City buses |

## Timetable only, no live data at the centre

Vienna (Wiener Linien), Stockholm and Gothenburg (Sweden has no live feed), Copenhagen, Lisbon and Porto (only CP trains are live), Barcelona (only Rodalies), Valencia, Seville, Brussels (only SNCB trains, not STIB), Marseille, Milan (only Trenitalia), Naples (only Trenitalia), Luxembourg, Bratislava, Brno (only trains), Tallinn (only Elron trains), Manchester (only trains), Belgrade, Sarajevo, Podgorica, Tirana, Thessaloniki, Lviv, Istanbul, Ankara, Nicosia, Monaco, Andorra.

Since build 47 Publit fills some of these gaps itself (Settings → Data → "Live data from more operators", on by default): **Vienna** (Wiener Linien open data), **all of Austria** (ÖBB journey planner: trains and, where the regional networks report them, buses and trams, plus construction notes), **Venice** (ACTV vaporetti and buses), **Stockholm** (SL) and **London** line status (TfL); since build 58 also **Switzerland** (search.ch) and in Germany **North Rhine-Westphalia** (VRR), **Berlin/Brandenburg** (VBB), **Hesse** (RMV), **Lower Saxony/Bremen** (VBN), **Schleswig-Holstein** (NAH.SH) and **Rhineland-Palatinate** (VRN). See [DATA-SOURCES.md](DATA-SOURCES.md#5-extra-live-sources-build-47).

Without live data the app shows "Timetable": no delays, cancellations or platform changes, and the trip guide, live bar and Live Activity follow the timetable.

## Timetable and live data

Berlin, Hamburg, Munich (and Germany as a whole through DELFI), Graz, Paris, Lyon, London, Dublin, Madrid (Cercanías), Rome, Amsterdam and Rotterdam (partly), Antwerp, Oslo, Helsinki, Riga, Vilnius, Warsaw, Kraków, Prague, Budapest, Ljubljana, Zagreb, Skopje, Sofia, Bucharest, Chișinău (partly), Tbilisi (partly), Reykjavík.

## Austria, Germany, Switzerland (checked 19 October 2026)

Departures within ~700 m of the centre in the next hour, 78 places; "live" = with a live time. Script and raw results were not committed (one-off check).

| Country | Timetable | Live from Transitous | What Publit adds |
|---|---|---|---|
| Germany | Everywhere (DELFI, VBB, VBN, KVV, AVV) | Most cities 80–100 %. Weak: NRW (Essen trams 3/33, Bielefeld trams 3/68, Münster buses 45/123), Hesse (Wiesbaden buses 16/146, Kassel buses 24/49), Lower Saxony (Oldenburg 87/139, Hildesheim 16/40), Kaiserslautern 10/43, Berlin-Spandau 89/130, Flensburg 2/11; none in Berchtesgaden, Kempten 4/36 | Build 58: VRR (NRW: Essen 26 → 38 of 40, Bielefeld 6 → 38), RMV (Hesse: Wiesbaden 7 → 43 of 44), VBN (Lower Saxony/Bremen: Oldenburg 19 → 34 of 44, Bremen 31 → 40 of 42), VRN (Kaiserslautern 11 → 22 of 39), VBB (Spandau 35 → 41 of 41), NAH.SH (Schleswig-Holstein). Still weak: Kempten/Allgäu, Flensburg (no server has more live data there) |
| Switzerland | Everywhere (opentransportdata.swiss) | Almost none: Zürich HB 4/153, Bern 0/149, Basel 4/156, Lugano 0/139 (possibly because Transitous already routes on the 2027 timetable while its live feed belongs to 2026; to report upstream) | search.ch (build 58): Bern 3 → 42 of 48, Zürich Central 0 → 39 of 40, Lugano 0 → 28 of 42 |
| Austria | Everywhere except **Carinthia**: Transitous skips the Carinthian feed (`PTA-Carinthia-Flex-2026`, `"skip": true`, "Stay Seated forever during MOTIS import"). Villach, Klagenfurt, Spittal, Wolfsberg have only trains and FlixBus; no local buses at all | Only Styria (Graz 147/152, Leoben) | ÖBB (build 47): the ride is found for 100 % of rides tested in St. Pölten, Salzburg, Innsbruck, Bregenz, Feldkirch, Linz; live for 79–100 %. Wiener Linien for Vienna. Carinthia cannot be fixed in the app: ÖBB has the buses live (Klagenfurt 68/68), but Transitous has no bus rides to correct |

## Other gaps

- Fares come only from operators that publish them (`withFares`); most routes have none.
- Shared bikes and scooters (GBFS) are mainly in France, Slovenia, Greece, Finland, Hungary, Switzerland, Italy, Romania, Norway and Luxembourg.
- Platforms (tracks) are missing in many bus and tram feeds.
- Station exits are never named by the router (the guide derives them from levels and streets).
