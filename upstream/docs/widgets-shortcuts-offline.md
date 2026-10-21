# Widgets, Shortcuts and offline data

## Departures widget

Add the Publit Departures widget, touch and hold it, then choose Edit Widget.
Search for a stop in the widget configuration. Small, medium, large and
rectangular Lock Screen families are supported. Tapping opens that stop's board.

The extension needs the same development team as the app in Xcode. It does not
require an App Group: selected stops are encoded in the App Entity identifier.
Saved stops in the app are offered to Siri/Shortcuts, but cannot be shared with
the widget configuration without an App Group. WidgetKit controls refresh
frequency; updates every minute are timeline entries, not fresh API requests.
It requests a new timeline after 15 minutes, subject to system scheduling.

## Siri and Shortcuts

Actions: Next departures (choose a stop), Route home, Route to work, Show ticket.
Set Home/Work under Saved first. If missing, the action opens Saved to set them.
Invocation phrases include the app name; for example in German:

- "Nächste Abfahrten in Publit"
- "Bring mich nach Hause mit Publit"
- "Route zur Arbeit in Publit"
- "Zeige mein Ticket in Publit"

The departures action speaks a short answer and displays a board. The routing
and ticket actions open the app. Siri recognition and widget layouts should be
checked on a real iPhone as well as the build and simulator tests.

## Offline behavior

This is a cache-based fallback, not a downloaded offline routing engine.
Previously fetched requests can be reused on connection failures or server 5xx
responses. It cannot calculate a new route entirely offline or download an
entire city timetable. Locally saved tickets and trips remain on the device.

Saved responses show a timestamp. Boards drop already departed services; do
not treat cached live predictions as current. Nearby fallback is limited to
within 400 m of the saved request's position. Live-response fallback is limited
to 24 hours; search/map-response fallback to 7 days. Responses live in the
system Caches directory and may be removed by iOS. Copies older than 7 days
are pruned on launch. No push server or background Live Activity refresh has
been added.

In-memory caching (usually 15–60 seconds for live requests) and the last nearby
board reduce repeat loading. There is no measured FPS/latency improvement yet;
performance profiling on a physical device remains necessary.
