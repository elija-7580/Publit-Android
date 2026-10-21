# Transitous — introduction and load estimate (draft, not sent)

Draft message for the Transitous community. Numbers below are derived from the Publit source as of the commit that added this file; they are estimates, not measured production traffic.

---

**Subject:** Publit (open-source iOS client) — introduction and expected API load

Hello,

I am building **Publit**, a free MIT-licensed iPhone app for public transport:
https://github.com/elija-7580/Publit-

It uses the Transitous API for geocoding, nearby departures, stop times, trip planning and itinerary refresh. The app identifies itself with a User-Agent that includes a contact URL, attributes Transitous and OSM, and is non-commercial.

### Expected requests per active user per day (from code)

Polling and cache policy in the app (not including WidgetKit’s own schedule):

| Screen / behaviour | Interval while visible | Client cache `maxAge` |
| --- | --- | --- |
| Nearby departures | 30 s | 20 s (`stoptimes` nearby slot) |
| Stop departure board / vehicle trip | 30 s | 20 s live / 15 s trip |
| Plan “leave now” live refresh | 60 s | plan: 20 s (now) / 60 s (later) |
| Saved / active trip refresh | 60 s | `refresh-itinerary` not cached |
| App-wide refresh for Live Activity trips | 60 s | same |

Rough upper bound for one commuting day with the app open about **1 hour** on Nearby/trip screens and occasional planning:

- Nearby/trip polls: on the order of **~100–150** requests/hour while those screens stay open (30 s cadence, with cache hits reducing network calls when `maxAge` still applies).
- Active-trip / Live Activity refresh: about **1 request/minute** per running trip while the app is in the foreground.
- Idle with only a home-screen widget: far fewer; WidgetKit typically refreshes on the order of tens of times per day, not continuous polling from the app process.

A light day (open Nearby a few minutes, plan once) is likely **tens of requests**, not hundreds. I have not measured fleet-wide traffic; these figures are code-derived ceilings for one engaged user.

I would like to agree on acceptable load and any contact channel you prefer if usage grows. I am also preparing a GTFS-RT converter for AMTAB Bari (`feeds/amtab-gtfsrt`) and will only propose it to Transitous after the licence is clarified with AMTAB.

Thank you for running Transitous.

Elijá Friedrich-Ulrich  
https://github.com/elija-7580/Publit-
