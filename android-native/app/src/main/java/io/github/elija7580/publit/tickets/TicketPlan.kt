// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.tickets

import java.time.Instant
import java.time.ZoneId

/**
 * Planning around a ticket you have (iOS Publit#23, #24, `TicketRoutePlanView`): the way to where the
 * ticket's ride starts, arriving before it leaves, and on from where it ends.
 */
object TicketPlan {
    /** Arrive this long before the ticket's ride leaves. */
    const val BUFFER_SECONDS = 10L * 60

    /** When the ticket's ride leaves: its start of validity, unless that is only a day (00:00). */
    fun departure(p: TicketPass, zone: ZoneId = ZoneId.systemDefault()): Instant? {
        val from = p.validFrom ?: return null
        val t = from.atZone(zone).toLocalTime()
        return if (t.hour == 0 && t.minute == 0) null else from
    }

    /** Arrive at the ticket's start by then; null when the ride's time is unknown or over. */
    fun arriveBy(p: TicketPass, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Instant? =
        departure(p, zone)?.minusSeconds(BUFFER_SECONDS)?.takeIf { it.isAfter(now) }

    /** Leave the ticket's end from then on: its end of validity when that is a time, else now. */
    fun onwardFrom(p: TicketPass, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Instant? {
        val until = p.validUntil ?: return null
        val t = until.atZone(zone).toLocalTime()
        if (t.hour == 23 && t.minute == 59 || t.hour == 0 && t.minute == 0) return null
        return until.takeIf { it.isAfter(now) }
    }
}
