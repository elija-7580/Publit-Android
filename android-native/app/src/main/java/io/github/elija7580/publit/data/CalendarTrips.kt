// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.data

import io.github.elija7580.publit.core.LatLon
import java.time.Instant

/**
 * An upcoming calendar event with a place, offered as a destination (CalendarTrips.swift on iOS).
 * Read on the phone only: nothing of it leaves the device until a route to it is asked for.
 */
data class CalendarAppointment(
    /** Event ID and start: the occurrences of a repeating event share one event ID. */
    val id: String,
    val title: String,
    val start: Instant,
    val end: Instant,
    /** The place as written in the event ("Café Central\nHerrengasse 14, 1010 Wien"). */
    val location: String,
    val lat: Double? = null,
    val lon: Double? = null,
    val allDay: Boolean = false,
) {
    val coordinate: LatLon? get() = if (lat != null && lon != null && (lat != 0.0 || lon != 0.0)) LatLon(lat, lon) else null

    private val lines: List<String> get() = location.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** The first line of the place, for a row ("Café Central"). */
    val placeName: String get() = lines.firstOrNull() ?: location

    /** The place on one line, for the address lookup. */
    val searchText: String get() = lines.joinToString(", ")

    /** The title, or the place when the event has none. */
    val displayTitle: String get() = title.trim().ifEmpty { placeName }

    /** When to be there: a few minutes before the start. */
    val arriveBy: Instant get() = start.minusSeconds(CalendarTrips.ARRIVE_EARLY_SECONDS)
}

object CalendarTrips {
    const val ENABLED_KEY = "calendarSuggestions"
    const val INVITE_DISMISSED_KEY = "calendarSuggestionsInviteDismissed"
    const val LOOKAHEAD_SECONDS = 36L * 3600
    const val NEARBY_WINDOW_SECONDS = 4L * 3600
    const val ARRIVE_EARLY_SECONDS = 5L * 60
    const val MINIMUM_LEAD_SECONDS = 5L * 60
    const val ALREADY_THERE_METERS = 200.0

    /**
     * Upcoming events worth a route, soonest first: not all-day, with a clear place ([destination]),
     * starting within [LOOKAHEAD_SECONDS], and not where you are now.
     */
    fun suggestions(events: List<CalendarAppointment>, now: Instant, here: LatLon? = null,
                    saved: List<SavedPlace> = emptyList(), limit: Int = 3): List<CalendarAppointment> =
        events.mapNotNull { destination(it, saved) }
            .filter { e ->
                val lead = e.start.epochSecond - now.epochSecond
                !e.allDay && lead >= MINIMUM_LEAD_SECONDS && lead <= LOOKAHEAD_SECONDS &&
                    !(here != null && e.coordinate != null && e.coordinate!!.distanceTo(here) < ALREADY_THERE_METERS)
            }
            .sortedBy { it.start }
            .distinctBy { it.id }
            .take(limit)

    /** The next event for Nearby, when it starts soon enough to set off for it from here. */
    fun next(suggestions: List<CalendarAppointment>, now: Instant): CalendarAppointment? =
        suggestions.firstOrNull { it.start.epochSecond - now.epochSecond <= NEARBY_WINDOW_SECONDS }

    /** The line under "Next appointment: …" in Nearby: when it starts and where ("14:30 · Herrengasse 14"). */
    fun nearbyDetail(time: String, place: String): String =
        listOf(time.trim(), place.trim()).filter { it.isNotEmpty() }.joinToString(" · ")

    /**
     * The event with a place clear enough to route to, or null (feedback 2026-11-24: a "staff meeting"
     * without a real place must not be offered). Clear is: a map pin, a saved place it names ("Work",
     * "Büro", "Zuhause" or a saved place's own name; it then takes that place's position), or a written
     * address (street and number, postcode and town, or "name, town").
     */
    fun destination(e: CalendarAppointment, saved: List<SavedPlace>): CalendarAppointment? {
        if (!isPhysicalPlace(e.location)) return null
        if (e.coordinate != null) return e
        savedPlace(e.location, saved)?.let { return e.copy(lat = it.lat, lon = it.lon) }
        return if (looksLikeAddress(e.location)) e else null
    }

    private val onlineMarkers = listOf(
        "http://", "https://", "www.", "zoom.us", "zoom meeting", "teams.microsoft", "microsoft teams",
        "meet.google", "google meet", "webex", "skype", "facetime", "jitsi", "whereby",
        "online", "virtual", "virtuell", "remote", "telefon", "phone call", "conference call",
    )

    /** A place someone can travel to: not empty and not a video call link. */
    fun isPhysicalPlace(text: String): Boolean {
        val t = text.trim().lowercase()
        return t.length >= 2 && onlineMarkers.none { t.contains(it) }
    }

    val workWords = setOf(
        "arbeit", "work", "büro", "buero", "office", "firma", "job", "arbeitsplatz", "the office",
        "bureau", "travail", "au bureau", "ufficio", "lavoro", "oficina", "trabajo", "escritório", "trabalho",
        "kantoor", "werk", "biuro", "praca", "робота", "офіс", "birou", "serviciu", "kancelář", "práce",
        "kontoret", "jobbet", "arbetet", "γραφείο", "δουλειά", "iroda", "munkahely", "ofis", "iş", "işyeri",
    )
    val homeWords = setOf(
        "zuhause", "zu hause", "daheim", "home", "at home", "maison", "chez moi", "à la maison", "casa", "a casa",
        "en casa", "em casa", "thuis", "dom", "w domu", "вдома", "дім", "acasă", "acasa", "doma", "domov",
        "hemma", "hem", "σπίτι", "otthon", "itthon", "ev", "evde",
    )

    private fun normalized(text: String): String =
        text.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** The saved place the event's place names: Home or Work by a word for it, or any saved place by its name. */
    fun savedPlace(text: String, saved: List<SavedPlace>): SavedPlace? {
        val first = text.lines().firstOrNull() ?: text
        val names = setOf(normalized(text), normalized(first)).filter { it.isNotEmpty() }.toSet()
        if (names.isEmpty()) return null
        if (names.any { it in workWords }) saved.firstOrNull { it.role == PlaceRole.WORK }?.let { return it }
        if (names.any { it in homeWords }) saved.firstOrNull { it.role == PlaceRole.HOME }?.let { return it }
        return saved.firstOrNull { normalized(it.name) in names }
    }

    private val roomWords = setOf(
        "raum", "room", "zimmer", "saal", "sala", "salle", "stock", "og", "eg", "ug", "etage", "floor",
        "gebäude", "building", "besprechungsraum", "konferenzraum", "meetingraum", "aula", "hörsaal", "kantine",
    )
    private val postcode = Regex("(^|[^\\d])\\d{4,5}\\s+\\p{L}{2,}")
    private val streetNumber = Regex("\\p{L}{3,}\\.?\\s+\\d{1,4}\\s?[a-z]?(\\b|,|$)")
    private val numberStreet = Regex("(^|\\s)\\d{1,4}[a-z]?,?\\s+\\p{L}{3,}")
    private val word = Regex("\\p{L}{3,}")

    /**
     * Written like an address: street and number ("Herrengasse 14", "12 rue de Rivoli"), a postcode with
     * its town ("1010 Wien"), or a name and a town ("Café Central, Wien"). Rooms and floors are not.
     */
    fun looksLikeAddress(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (postcode.containsMatchIn(lower)) return true
        val words = normalized(text).split(" ").toSet()
        if (words.any { it in roomWords }) return false
        if (streetNumber.containsMatchIn(lower) || numberStreet.containsMatchIn(lower)) return true
        val parts = text.split(',', '\n').map { it.trim() }.filter { word.containsMatchIn(it) }
        return parts.size >= 2
    }

    /** The route to take to be there on time: the one leaving last that still arrives by [arriveBy]. */
    fun latestOnTime(routes: List<Itinerary>, arriveBy: Instant): Itinerary? =
        routes.filter { !it.isCancelled && !it.endTime.isAfter(arriveBy.plusSeconds(30)) }.maxByOrNull { it.startTime }
}
