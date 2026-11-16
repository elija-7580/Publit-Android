package io.github.elija7580.publit.data

import io.github.elija7580.publit.core.L10n

/**
 * When the get-off reminder of a started trip goes off (Settings → Started trips, GetOffAlert on iOS #69).
 * The time-based reminder stays the fallback for every choice: it still fires about two minutes before
 * the arrival when no reliable location is there. Raw values as on iOS.
 */
enum class GetOffAlert(val raw: String, val title: String, val stopsBefore: Int) {
    /** At the stop before yours ("Next stop: get off"). */
    ONE_STOP_BEFORE("oneStopBefore", "One stop before", 1),
    TWO_STOPS_BEFORE("twoStopsBefore", "Two stops before", 2),
    /** Shortly before your stop (about 400 m away along the line). */
    APPROACHING("approaching", "Shortly before the stop", 0),
    /** By the clock only: no location used. */
    TIME_ONLY("timeOnly", "By the clock only", 0);

    val usesLocation: Boolean get() = this != TIME_ONLY

    companion object {
        const val KEY = "getOffAlert"
        val DEFAULT = ONE_STOP_BEFORE
        fun of(raw: String?): GetOffAlert = entries.firstOrNull { it.raw == raw } ?: DEFAULT
        fun current(prefs: android.content.SharedPreferences): GetOffAlert = of(prefs.getString(KEY, null))
    }
}

/**
 * Decides from the phone's position on board (laid onto the line, see TripProgress.located) whether the
 * get-off alert is due (GetOffWatch on iOS). The order of the stops counts, not only distance: a line
 * passing close to your stop earlier (a loop, a parallel street) does not set it off.
 */
object GetOffWatch {
    /** "Approaching": this close to the stop. */
    const val APPROACH_METERS = 400.0

    /** `progress` must come from your location; `metresToExit` is the distance to the exit stop. */
    fun isDue(alert: GetOffAlert, progress: TripProgress, stopCount: Int, metresToExit: Double?): Boolean {
        if (!alert.usesLocation || stopCount < 2) return false
        if (progress == TripProgress.NotStarted || progress == TripProgress.Finished) return false
        val exit = stopCount - 1
        val value = progress.stopValue(stopCount)
        if (alert == GetOffAlert.APPROACHING) {
            if (progress is TripProgress.AtStop && progress.index == exit) return true
            if (value <= (exit - 1).toDouble() || metresToExit == null) return false
            return metresToExit <= APPROACH_METERS
        }
        // At (or past) the milestone stop, not yet at the exit. With fewer stops on the ride than the
        // milestone asks for, the first stop counts once the vehicle has left it.
        val milestone = maxOf(0, exit - alert.stopsBefore)
        if (milestone == 0 && progress == TripProgress.AtStop(0)) return false
        return value >= milestone && value < exit + 0.001
    }

    /** "Get off at Odeonsplatz (next stop)" / "… in 2 stops". */
    fun message(exit: String, stopsLeft: Int): String = when {
        stopsLeft <= 0 -> L10n.f("Get off here: %@", exit)
        stopsLeft == 1 -> L10n.f("Next stop: %@. Get off there.", exit)
        else -> L10n.f("Get off at %@ in %lld stops.", exit, stopsLeft)
    }
}
