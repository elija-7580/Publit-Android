package io.github.elija7580.publit.data

import io.github.elija7580.publit.core.L10n

object ModeStyle {
    private val streetModes = setOf("WALK", "BIKE", "RENTAL", "CAR", "HGV", "CAR_PARKING", "CAR_DROPOFF")

    fun isTransit(mode: String) = mode !in streetModes

    fun label(mode: String): String = when (mode) {
        "WALK" -> L10n.t("Walk")
        "BIKE" -> L10n.t("Bike")
        "RENTAL" -> L10n.t("Rental")
        "CAR" -> L10n.t("Car")
        "BUS" -> L10n.t("Bus")
        "COACH" -> L10n.t("Coach")
        "TRAM" -> L10n.t("Tram")
        "SUBWAY", "METRO" -> L10n.t("Metro")
        "FERRY" -> L10n.t("Ferry")
        "AIRPLANE" -> L10n.t("Flight")
        "SUBURBAN" -> L10n.t("S-Bahn")
        "HIGHSPEED_RAIL" -> L10n.t("High-speed")
        "NIGHT_RAIL" -> L10n.t("Night train")
        "FUNICULAR" -> L10n.t("Funicular")
        "AERIAL_LIFT", "AREAL_LIFT", "CABLE_CAR" -> L10n.t("Cable car")
        else -> L10n.t("Train")
    }

    /** Fallback line colour when the feed has none. */
    fun defaultHex(mode: String): String = when (mode) {
        "BUS", "COACH", "FLEX" -> "2973CC"
        "TRAM" -> "CC2633"
        "SUBWAY", "METRO" -> "5940B3"
        "FERRY" -> "0D8CA6"
        "SUBURBAN" -> "1A8C4D"
        "HIGHSPEED_RAIL", "LONG_DISTANCE" -> "9E1B32"
        "NIGHT_RAIL" -> "33308C"
        "FUNICULAR", "AERIAL_LIFT", "AREAL_LIFT", "CABLE_CAR" -> "A15C0F"
        "AIRPLANE" -> "1F6FA8"
        "ODM", "RIDE_SHARING" -> "7A3DB8"
        "WALK", "BIKE", "RENTAL", "CAR", "CAR_PARKING", "CAR_DROPOFF", "HGV" -> "8E8E93"
        else -> "D0571B"
    }
}

/** A headsign is the vehicle's advertised direction, not the passenger's stop. */
object TransitLabels {
    fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }
    fun identity(value: String): String = value.uppercase().filter { it.isLetterOrDigit() }
}

/** Transport modes as people choose them ("no buses", "only trains"). */
enum class ModeGroup(val apiModes: List<String>) {
    TRAIN(listOf("HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "REGIONAL_FAST_RAIL", "REGIONAL_RAIL", "RAIL")),
    SUBURBAN(listOf("SUBURBAN")),
    SUBWAY(listOf("SUBWAY", "METRO")),
    TRAM(listOf("TRAM")),
    BUS(listOf("BUS", "COACH")),
    FERRY(listOf("FERRY")),
    OTHER(listOf("AIRPLANE", "FUNICULAR", "AERIAL_LIFT", "CABLE_CAR", "ODM", "OTHER"));

    /** Stored value, the same as iOS ("train", "bus"). */
    val raw: String get() = name.lowercase()

    val title: String
        get() = when (this) {
            TRAIN -> L10n.t("Train")
            SUBURBAN -> L10n.t("S-Bahn")
            SUBWAY -> L10n.t("Metro")
            TRAM -> L10n.t("Tram")
            BUS -> L10n.t("Bus")
            FERRY -> L10n.t("Ferry")
            OTHER -> L10n.t("Other")
        }

    /** Representative mode for colours and icons. */
    val mode: String get() = if (this == TRAIN) "RAIL" else apiModes[0]

    companion object {
        fun of(mode: String): ModeGroup = when (mode) {
            "SUBURBAN" -> SUBURBAN
            "SUBWAY", "METRO" -> SUBWAY
            "TRAM" -> TRAM
            "BUS", "COACH", "FLEX" -> BUS
            "FERRY" -> FERRY
            "AIRPLANE", "FUNICULAR", "AERIAL_LIFT", "AREAL_LIFT", "CABLE_CAR", "ODM", "OTHER", "RIDE_SHARING" -> OTHER
            else -> TRAIN
        }

        /** Groups present in some modes, in fixed order. */
        fun present(modes: Iterable<String>): List<ModeGroup> {
            val found = modes.filter(ModeStyle::isTransit).map(::of).toSet()
            return entries.filter { it in found }
        }

        fun fromRaw(raw: String): ModeGroup? = entries.firstOrNull { it.raw == raw }
    }
}

/** Which modes to show. Empty means all: a new mode in a feed is never hidden by accident. */
data class ModeFilter(val included: Set<ModeGroup> = emptySet()) {
    val isActive: Boolean get() = included.isNotEmpty() && included != ModeGroup.entries.toSet()

    fun allows(mode: String): Boolean = !isActive || !ModeStyle.isTransit(mode) || ModeGroup.of(mode) in included
    fun allows(group: ModeGroup): Boolean = !isActive || group in included

    /** Comma list for the API, null when nothing is filtered. */
    val apiValue: String?
        get() = if (!isActive) null else ModeGroup.entries.filter { it in included }.flatMap { it.apiModes }.joinToString(",")

    /** Chip behaviour: from "all" a tap shows only that group; then add/remove; none left = all. */
    fun toggleOnly(group: ModeGroup): ModeFilter {
        if (!isActive) return ModeFilter(setOf(group))
        val next = if (group in included) included - group else included + group
        return ModeFilter(next).let { if (!it.isActive) ModeFilter() else it }
    }

    val summary: String?
        get() {
            if (!isActive) return null
            val on = ModeGroup.entries.filter { it in included }
            val off = ModeGroup.entries.filter { it !in included }
            if (off.size < on.size) return io.github.elija7580.publit.core.L10n.f("Without %@", off.joinToString(", ") { it.title })
            return on.joinToString(", ") { it.title }
        }

    /** Stored as "train,bus", like @AppStorage on iOS. */
    val storageValue: String get() = ModeGroup.entries.filter { it in included }.joinToString(",") { it.raw }

    companion object {
        fun fromStorage(value: String) = ModeFilter(value.split(',').mapNotNull { ModeGroup.fromRaw(it.trim()) }.toSet())
    }
}
