# Website-Screenshots (Android)

Echte Screenshots der nativen Android-App für publit.eu und die persönliche Website. Der Workflow
**„Website-Screenshots erstellen“** (`.github/workflows/website-screenshots.yml`) baut den Build-Typ
`screenshots`, startet ihn in einem Android-Emulator (Pixel 8 Pro, 1344 × 2992, API 35) und nimmt vier
Ansichten auf Deutsch und Englisch auf:

| Nr. | Ansicht | Datei |
|---|---|---|
| 01 | Abfahrten in der Nähe | `01-nearby` |
| 02 | Routenplanung (Universität → Flughafen München) | `02-plan` |
| 03 | Fahrtbegleitung (unterwegs in der U6) | `03-live` |
| 04 | Haltestellenkarte (MapLibre, OpenFreeMap-Kacheln) | `04-map` |

Karten: Android nutzt **MapLibre** (Kacheln von OpenFreeMap, Daten © OpenStreetMap), iOS nutzt **Apple Maps**.
Die Karten sehen deshalb auf beiden Plattformen verschieden aus.

## Neu erzeugen

GitHub → **Actions** → **Website-Screenshots erstellen** → **Run workflow**. Optional: anderer Branch/Commit
(`ref`, Standard `native-android`) und ob die Bilder zusätzlich im Vorab-Release `website-screenshots`
abgelegt werden. Der Workflow läuft auch bei Änderungen in `android-native/screenshots/` auf `native-android`.
Dauer etwa 25–35 Minuten.

## Bilder herunterladen

Im Workflow-Lauf unter **Artifacts** (oder im Release `website-screenshots`):

- `screenshots-raw-png`: Original-Screenshots, 1344 × 2992 PNG
- `screenshots-framed-png`: im Pixel-8-Pro-Rahmen, transparenter Hintergrund, dezenter Schatten
- `screenshots-webp`: optimierte WebP (Rahmen halbe Größe, Poster, `web/` für publit.eu: 600 px breite
  Screenshots `web-<ansicht>-android.webp` und der Rahmen `pixel-8-pro.webp`)
- `screenshots-posters-png`: 1320 × 2868 wie die iPhone-Poster (gleiche Bildunterschriften, Ränder, Gerätehöhe, Schatten)
- `screenshots-preview`: Übersicht aller Bilder (`framed.png`, `posters.png`, `index.html`) und Prüfbericht
- `screenshots-diagnostics`: Logcat, OCR-Text, Anfragen an den Demo-Server, letzte Bilder bei Fehlern

## Was geprüft wird (sonst schlägt der Lauf fehl)

App im Vordergrund; Bild über drei Aufnahmen stabil (kein Ladekreis, Karte fertig); erwartete Texte per OCR
(z. B. „Universität“, „U6“, Sprache an den Tab-Namen); keine Lade- oder Fehlertexte; der Demo-Server wurde
gefragt; die Karte ist nicht leer; alle acht Aufnahmen unterscheiden sich. Gleiche Bedingungen in jedem Lauf:
Uhr 09:41 (Statusleiste im Demo-Modus, Gerätezeit 09:40:40 Europe/Berlin), volles WLAN/Netz, Akku 100 %, keine
Benachrichtigungen, Schriftgröße 1,0, 24-Stunden-Format, heller Modus, Gestennavigation.

## Demo-Daten

`fixtures/munich/` enthält einmal aufgezeichnete, echte Transitous-Antworten (MOTIS) für München vom
3. 11. 2026, 09:41, gekürzt. Keine persönlichen Daten: Standort ist der U-Bahnhof Universität, Ziel der Flughafen.
`mock_server.py` beantwortet damit alle Anfragen der App und verschiebt alle Zeiten auf den Tag des Laufs, so
passen Uhr und Abfahrten immer zusammen. Nur der Build-Typ `screenshots` spricht mit dem Demo-Server
(`BuildConfig.DEMO_API`, Klartext nur zu 10.0.2.2/localhost); normale Builds sind unverändert.

**Ansichten anpassen:** `scenes.json` – je Ansicht die Schritte (`start` oder Deep Link `link`, z. B.
`publit://place?…`, `publit://trip/<id>`, `publit://map`), die erwarteten Wörter (`expect`), Toleranz und
Zeitlimit. Bildunterschriften der Poster stehen in `frame.py` (`CAP`).

**Daten ändern:** Neue Antworten mit `record_fixtures.py` aufnehmen (Ort, Ziel und Zeit oben im Skript; braucht
Internet), dann `scenario.json` anpassen (Referenzzeit, Standort). Für die Fahrtbegleitung legt `capture.py`
die erste Verbindung aus `plan.json` als gestartete Fahrt an (`live` in `scenes.json`: Verbindung, Teilstrecke,
Fortschritt).

Lokal testen (ohne Emulator): `python3 mock_server.py` und z. B.
`curl 'localhost:8765/api/v6/stoptimes?center=48.1499,11.5803'`.

## Geräterahmen: Quelle und Lizenz

Der Rahmen ist die Gerätegrafik **„Pixel 8 Pro“ aus Android Studio** (`back.webp`, `mask.webp`; Kamera-Loch
in der Mitte oben wie beim Emulator-Profil), aus dem AOSP-Repository
[`platform/tools/adt/idea`](https://android.googlesource.com/platform/tools/adt/idea/+/488997af9ec931fc1d173aca75f9982667dc3786/artwork/resources/device-art-resources/pixel_8_pro/),
Commit `488997af9ec9`, Pfad `artwork/resources/device-art-resources/pixel_8_pro/`.
Lizenz: **Apache License 2.0** (Android Open Source Project). Der Workflow lädt die Dateien zur Laufzeit und
prüft ihre SHA-256-Summen; sie liegen nicht im Repository. Die Screenshots kommen unskaliert in die
Bildschirmöffnung (genau 1344 × 2992 an Position 58, 58), werden also nicht verzerrt.
Schrift der Poster: Noto Sans (SIL Open Font License), wie bei den iPhone-Postern.

## Hinweise aus den ersten Läufen

- `avdmanager` legt das AVD sonst unter `~/.config/.android/avd` ab, der Emulator sucht aber in `~/.android/avd`; der Workflow setzt deshalb `ANDROID_AVD_HOME` und begrenzt `adb wait-for-device` auf 5 Minuten.
- Weiße Schrift auf den farbigen Linien-Abzeichen (U3, U6) liest die OCR nicht; die Prüfungen in `scenes.json` nutzen deshalb Zielnamen.
- Die Statusleiste zeigt im Demo-Modus 09:41, WLAN, 5G, volle Balken und 100 % Akku.

- Dunkle Aufnahmen: `capture.py` nimmt jede Ansicht zusätzlich im Dunkelmodus auf (`--themes light,dark`); `frame.py` macht daraus nur die Website-Bilder `web/<lang>/web-<ansicht>-android-dark.webp` für den Hell/Dunkel-Schalter auf publit.eu.
