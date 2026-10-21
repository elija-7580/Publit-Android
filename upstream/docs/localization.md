# Localization

English is the source language. The app, departures widget and Live Activity
share `Sources/Shared/Localizable.xcstrings`. Supported languages: English,
German, French, Italian, Spanish, European Portuguese (`pt-PT`), Dutch, Polish,
Ukrainian, Romanian, Czech, Swedish, Greek, Hungarian and Turkish.

Siri invocation phrases are in `Sources/App/AppShortcuts.xcstrings`.
Privacy permission descriptions are in `Sources/App/InfoPlist.xcstrings`.
Preserve `${applicationName}` and `${stop}` exactly in invocation phrases.

Translations marked `needs_review` are machine-translated drafts. German UI
copy and essential transport terminology across the languages were edited.
Native-speaker review is still required before a public release. Do not mark
all drafts as reviewed merely because they compile.

Integer plurals for changes, stops and spoken delay descriptions are explicit,
including one/few/many/other in Polish, Ukrainian and Czech, and one/few/other
in Romanian. Romanian has no CLDR `many` category.

`LocalizationTests` checks language coverage, placeholder preservation and
plural variants. UI test F08 captures German Plan, Settings, Nearby and Saved
screens. These tests do not replace native-speaker review or on-device checks
with accessibility text sizes.

English keys in model code go through `L10n`; SwiftUI literals are localized
by the shared catalog. Dynamic route/stop names must stay verbatim.
