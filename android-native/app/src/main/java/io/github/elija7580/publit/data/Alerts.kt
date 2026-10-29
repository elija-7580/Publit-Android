package io.github.elija7580.publit.data

import java.time.Instant

object Alerts {
    fun priority(a: Alert): Int =
        if (a.severityLevel == "SEVERE" || a.effect == "NO_SERVICE") 0 else if (a.severityLevel == "WARNING") 1 else 2

    fun visible(a: Alert, now: Instant = Instant.now(), from: Instant? = null, to: Instant? = null): Boolean {
        val comm = a.communicationPeriod
        if (!comm.isNullOrEmpty() && comm.none { it.contains(now) }) return false
        val impact = a.impactPeriod
        if (!impact.isNullOrEmpty() && impact.none { it.overlaps(from ?: now, to ?: from ?: now) }) return false
        return true
    }

    fun relevant(alerts: List<Alert>, now: Instant = Instant.now(), from: Instant? = null, to: Instant? = null): List<Alert> =
        alerts.filter { visible(it, now, from, to) }.distinct()
            .sortedWith(compareBy<Alert>({ priority(it) }, { it.headerText }, { it.descriptionText }))
}

/** Circle lines carry the stop they start from as headsign. */
object CircularLine {
    private val quoted = Regex("\\s*[\"“”„][^\"“”„]{1,6}[\"“”„]")
    private val bracket = Regex("\\s*\\([^)]{1,12}\\)\\s*$")
    private val letter = Regex("\\s+[A-Z]$")

    fun baseName(name: String): String =
        StopGrouping.normalizedName(name.replace(quoted, "").replace(bracket, "").replace(letter, ""))

    fun isLoop(headsign: String?, origin: String): Boolean {
        val h = TransitLabels.clean(headsign) ?: return false
        val a = baseName(h)
        return a.isNotEmpty() && a == baseName(origin)
    }
}
