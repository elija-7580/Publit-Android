// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.CalendarAppointment
import io.github.elija7580.publit.data.CalendarTrips
import io.github.elija7580.publit.data.SavedPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Upcoming calendar events with a clear place (CalendarSuggestions.swift on iOS), read on the phone through
 * CalendarContract.Instances. Off until turned on; READ_CALENDAR is asked for only then. The events are
 * not stored; a place is looked up (and so sent to the geocoder) only when its route is opened.
 */
object CalendarSuggestions {
    private val _appointments = MutableStateFlow<List<CalendarAppointment>>(emptyList())
    val appointments = _appointments.asStateFlow()
    private val _enabled = MutableStateFlow(false)
    val enabled = _enabled.asStateFlow()
    @Volatile private var lastRead = 0L

    private fun prefs(c: Context) = c.getSharedPreferences("publit", Context.MODE_PRIVATE)

    fun hasPermission(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** On and allowed: events are read. */
    fun isActive(c: Context): Boolean = prefs(c).getBoolean(CalendarTrips.ENABLED_KEY, false) && hasPermission(c)

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(CalendarTrips.ENABLED_KEY, on).apply()
        _enabled.value = on && hasPermission(c)
        lastRead = 0
        if (!_enabled.value) _appointments.value = emptyList()
    }

    /** Reads the events of the next hours again; within a minute the last reading is kept, unless [force]. */
    suspend fun refresh(c: Context, here: LatLon?, saved: List<SavedPlace>, force: Boolean = false) {
        _enabled.value = isActive(c)
        if (!_enabled.value) { _appointments.value = emptyList(); return }
        val now = System.currentTimeMillis()
        if (!force && now - lastRead < 60_000) return
        lastRead = now
        val events = withContext(Dispatchers.IO) { read(c, now) }
        _appointments.value = CalendarTrips.suggestions(events, Instant.ofEpochMilli(now), here, saved)
    }

    private fun read(c: Context, now: Long): List<CalendarAppointment> {
        val i = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, now - 3_600_000)
            android.content.ContentUris.appendId(it, now + CalendarTrips.LOOKAHEAD_SECONDS * 1000)
        }.build()
        val columns = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.STATUS, CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val out = mutableListOf<CalendarAppointment>()
        try {
            c.contentResolver.query(i, columns, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { r ->
                while (r.moveToNext() && out.size < 200) {
                    if (!r.isNull(6) && r.getInt(6) == CalendarContract.Events.STATUS_CANCELED) continue
                    if (!r.isNull(7) && r.getInt(7) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    val begin = r.getLong(2)
                    out += CalendarAppointment(
                        id = "${r.getLong(0)}|${begin / 1000}", title = r.getString(1) ?: "",
                        start = Instant.ofEpochMilli(begin), end = Instant.ofEpochMilli(r.getLong(3)),
                        location = r.getString(5) ?: "", allDay = r.getInt(4) == 1,
                    )
                }
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {}
        return out
    }
}
