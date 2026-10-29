package io.github.elija7580.publit.data

import io.github.elija7580.publit.core.Fmt
import io.github.elija7580.publit.core.L10n
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Where the traveller is in a trip at a given moment: drives the leave reminder and the status line. */
data class TripStatus(val phase: Phase, val nextEvent: Instant, val headline: String, val detail: String, val delayMinutes: Int) {
    enum class Phase { BEFORE_LEAVING, WALKING, RIDING, TRANSFER, ARRIVED, CANCELLED }

    companion object {
        /** Seconds before leaving at which the leave reminder fires. */
        const val LEAVE_LEAD = 5 * 60L

        fun leaveTime(it: Itinerary): Instant = it.startTime
        fun reminderTime(it: Itinerary): Instant = leaveTime(it).minusSeconds(LEAVE_LEAD)

        fun reminderMoved(old: Itinerary, new: Itinerary, now: Instant = Instant.now()): Boolean =
            abs(reminderTime(new).epochSecond - reminderTime(old).epochSecond) >= 60 && new.startTime > now.plusSeconds(30)

        private fun describe(leg: Leg): String {
            val time = Fmt.time(leg.startTime, leg.from.tz)
            val h = leg.headsign
            val line = if (!h.isNullOrEmpty()) L10n.f("%@ to %@ from %@", leg.lineName, h, leg.from.name)
            else L10n.f("%@ from %@", leg.lineName, leg.from.name)
            val track = leg.from.track
            val now = TransitLabels.clean(track); val planned = TransitLabels.clean(leg.from.scheduledTrack)
            if (now != null && planned != null && TransitLabels.identity(now) != TransitLabels.identity(planned))
                return L10n.f("%@, Pl. %@ at %@", line, L10n.f("%@ (changed)", now), time)
            if (!track.isNullOrEmpty()) return L10n.f("%@, Pl. %@ at %@", line, track, time)
            return L10n.f("%@ at %@", line, time)
        }

        private fun minutes(seconds: Long) = (seconds / 60.0).roundToInt()

        fun at(now: Instant, it: Itinerary, countdown: Boolean = true): TripStatus {
            val tz = it.legs.firstOrNull()?.from?.tz
            val transit = it.transitLegs
            if (it.isCancelled) {
                val leg = it.legs.firstOrNull { l -> l.isTransit && l.cancelled == true }
                return TripStatus(Phase.CANCELLED, it.startTime, L10n.t("Connection cancelled"),
                    leg?.let { l -> L10n.f("%@ from %@ does not run.", l.lineName, l.from.name) } ?: L10n.t("Plan again."), 0)
            }
            val delay = transit.firstOrNull()?.delayMinutes ?: 0
            val last = it.legs.lastOrNull()
            if (now >= it.endTime) {
                return TripStatus(Phase.ARRIVED, it.endTime, L10n.t("Arrived"),
                    L10n.f("at %@ %@", last?.to?.name ?: L10n.t("destination"), Fmt.time(it.endTime, last?.to?.tz)), 0)
            }
            if (now < it.startTime) {
                val mins = ceil((it.startTime.toEpochMilli() - now.toEpochMilli()) / 60_000.0).toInt()
                val head = when {
                    mins <= 0 -> L10n.t("Leave now")
                    mins < 60 && countdown -> L10n.f("Leave in %lld min", mins)
                    else -> L10n.f("Leave at %@", Fmt.time(it.startTime, tz))
                }
                val det = transit.firstOrNull()?.let(::describe) ?: L10n.f("Walk %@", Fmt.duration(it.duration))
                return TripStatus(Phase.BEFORE_LEAVING, it.startTime, head, det, delay)
            }
            val i = it.legs.indexOfFirst { l -> now < l.endTime }
            if (i < 0) return TripStatus(Phase.ARRIVED, it.endTime, L10n.t("Arrived"), "", 0)
            val leg = it.legs[i]
            if (leg.isTransit) {
                if (now < leg.startTime) {
                    return TripStatus(Phase.TRANSFER, leg.startTime,
                        L10n.f("%@ departs %@", leg.lineName, Fmt.time(leg.startTime, leg.from.tz)), describe(leg), leg.delayMinutes)
                }
                return TripStatus(Phase.RIDING, leg.endTime, L10n.f("Get off at %@", leg.to.name),
                    L10n.f("%@ arrives %@", leg.lineName, Fmt.time(leg.endTime, leg.to.tz)),
                    minutes(leg.endTime.epochSecond - leg.scheduledEnd.epochSecond))
            }
            it.legs.drop(i + 1).firstOrNull { l -> l.isTransit }?.let { next ->
                return TripStatus(if (i == 0) Phase.WALKING else Phase.TRANSFER, next.startTime,
                    L10n.f("Walk to %@", next.from.name), describe(next), next.delayMinutes)
            }
            return TripStatus(Phase.WALKING, it.endTime, L10n.f("Walk to %@", last?.to?.name ?: L10n.t("destination")),
                L10n.f("Arrive %@", Fmt.time(it.endTime, last?.to?.tz)), 0)
        }

        /** Why a route cannot be started, or null when it can. */
        fun startBlocked(it: Itinerary, now: Instant): String? {
            if (it.endTime < now) return L10n.t("This connection is already over. Plan again for a new one.")
            if (it.isCancelled) {
                val leg = it.legs.firstOrNull { l -> l.isTransit && l.cancelled == true }
                return leg?.let { l -> L10n.f("%@ from %@ does not run. Choose another connection.", l.lineName, l.from.name) }
                    ?: L10n.t("A ride on this connection does not run. Choose another connection.")
            }
            return null
        }
    }
}

/** How often the open app refreshes a running trip (seconds). */
object LiveRefresh {
    const val IDLE = 300L

    fun interval(start: Instant, end: Instant, nextEvent: Instant, now: Instant = Instant.now()): Long {
        val toStart = start.epochSecond - now.epochSecond
        if (now >= end) return IDLE
        if (toStart > 3600) return 600
        if (toStart > 20 * 60) return 180
        if (toStart > 0) return 60
        return if (nextEvent.epochSecond - now.epochSecond > 10 * 60) 120 else 60
    }

    fun interval(trips: List<Itinerary>, now: Instant = Instant.now()): Long =
        trips.minOfOrNull { interval(it.startTime, it.endTime, TripStatus.at(now, it).nextEvent, now) } ?: IDLE
}
