# Übersetzung prüfen — kurze Anleitung für Muttersprachler

Publit ist eine freie ÖPNV-App fürs iPhone. Die Texte liegen in
`Sources/Shared/Localizable.xcstrings` (App, Widget, Live Activity).
Ergänzend: Siri-Phrasen in `Sources/App/AppShortcuts.xcstrings`,
Berechtigungstexte in `Sources/App/InfoPlist.xcstrings`.

## Sprachen

Englisch (Quelle), Deutsch, Französisch, Italienisch, Spanisch,
Portugiesisch (Portugal, `pt-PT`), Niederländisch, Polnisch, Ukrainisch,
Rumänisch, Tschechisch, Schwedisch, Griechisch, Ungarisch, Türkisch.

Viele Einträge sind maschinell übersetzt und als `needs_review` markiert.
Deutsch und zentrale Fachbegriffe wurden schon nachgebessert — trotzdem
freut uns jede Muttersprachler-Prüfung.

## So prüfst du

1. Repo klonen, in Xcode die String Catalogs öffnen **oder** die
   `.xcstrings`-Dateien im Editor durchsuchen.
2. Auf natürliche Formulierungen achten: Haltestelle, Abfahrt, Umstieg,
   Verspätung, Gleis/Steig — so wie man vor Ort spricht.
3. Platzhalter wie `%@`, `%lld` oder `${applicationName}` / `${stop}`
   unverändert lassen.
4. Fehler oder bessere Varianten melden: GitHub Issue mit Vorlage
   „Translation“ (`.github/ISSUE_TEMPLATE/translation.md`) — Sprache,
   Schlüssel oder Screenshot, Vorschlag.

Ton der Meldung: kurz, freundlich; in Sprachen mit üblichem Du gerne per Du.

Danke!
