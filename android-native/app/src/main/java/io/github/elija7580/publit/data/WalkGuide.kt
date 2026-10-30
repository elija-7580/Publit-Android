package io.github.elija7580.publit.data

import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import kotlinx.serialization.Serializable
import kotlin.math.*

/** One step of a walk as the router gives it (StepInstruction on iOS). */
@Serializable
data class StepInstruction(
    val relativeDirection: String? = null,
    val distance: Double = 0.0,
    val fromLevel: Double? = null,
    val toLevel: Double? = null,
    val polyline: EncodedPolyline? = null,
    val streetName: String? = null,
    val exit: String? = null,
    val area: Boolean? = null,
)

/** Compass words (Compass on iOS). */
object Compass {
    private val heads = listOf("Head north", "Head north-east", "Head east", "Head south-east", "Head south", "Head south-west", "Head west", "Head north-west")
    private val exits = listOf("Exit on the north side", "Exit on the north-east side", "Exit on the east side", "Exit on the south-east side",
        "Exit on the south side", "Exit on the south-west side", "Exit on the west side", "Exit on the north-west side")
    fun point(bearing: Double): Int = (((bearing % 360 + 360) % 360 + 22.5) / 45).toInt() % 8
    fun head(point: Int) = L10n.t(heads[point])
    fun exit(point: Int) = L10n.t(exits[point])
}

/** Geometry along a path of points (PathGeometry on iOS). */
object PathGeometry {
    fun bearing(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat); val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2); val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
    /** Signed change of direction, −180…180 (negative = left). */
    fun turn(from: Double, to: Double): Double { var d = (to - from) % 360; if (d > 180) d -= 360; if (d < -180) d += 360; return d }
    fun length(path: List<LatLon>) = path.zipWithNext().sumOf { (a, b) -> a.distanceTo(b) }

    fun point(at: Double, on: List<LatLon>): LatLon? {
        if (on.isEmpty()) return null
        if (at <= 0) return on.first()
        var walked = 0.0
        for ((a, b) in on.zipWithNext()) {
            val d = a.distanceTo(b)
            if (walked + d >= at && d > 0) { val f = (at - walked) / d; return LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f) }
            walked += d
        }
        return null
    }

    data class Hit(val along: Double, val offset: Double)

    /** Nearest point of the path to p: metres along it and metres away from it. */
    fun locate(p: LatLon, on: List<LatLon>): Hit? {
        if (on.size < 2) return null
        val k = 111_320.0 * cos(Math.toRadians(p.lat))
        var best: Hit? = null; var walked = 0.0
        for ((a, b) in on.zipWithNext()) {
            val ax = (a.lon - p.lon) * k; val ay = (a.lat - p.lat) * 110_540.0
            val bx = (b.lon - p.lon) * k; val by = (b.lat - p.lat) * 110_540.0
            val dx = bx - ax; val dy = by - ay; val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val off = hypot(ax + t * dx, ay + t * dy)
            val seg = a.distanceTo(b)
            if (best == null || off < best.offset) best = Hit(walked + t * seg, off)
            walked += seg
        }
        return best
    }
}

/** One instruction of a walk: "Turn left onto Ludwigstraße", "Take the stairs down (level −1)". */
data class WalkManeuver(
    val kind: Kind, val along: Double, var distance: Double = 0.0, var street: String?, val level: Double?, val coordinate: LatLon,
    var exitTowards: String? = null, var exitSide: Int? = null,
) {
    enum class Kind { START, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, U_TURN,
        STAIRS_UP, STAIRS_DOWN, STAIRS, ELEVATOR, LEVEL_UP, LEVEL_DOWN, ARRIVE }

    val exitNote: String? get() = exitTowards?.let { L10n.f("Exit towards %@", it) } ?: exitSide?.let(Compass::exit)

    fun instruction(destination: String): String {
        val s = street
        val lvl = level?.let(::levelName)
        return when (kind) {
            Kind.START -> s?.let { L10n.f("Head along %@", it) } ?: L10n.t("Start walking")
            Kind.STRAIGHT -> s?.let { L10n.f("Continue on %@", it) } ?: L10n.t("Continue straight on")
            Kind.SLIGHT_LEFT -> s?.let { L10n.f("Bear left onto %@", it) } ?: L10n.t("Bear left")
            Kind.LEFT -> s?.let { L10n.f("Turn left onto %@", it) } ?: L10n.t("Turn left")
            Kind.SHARP_LEFT -> s?.let { L10n.f("Turn sharp left onto %@", it) } ?: L10n.t("Turn sharp left")
            Kind.SLIGHT_RIGHT -> s?.let { L10n.f("Bear right onto %@", it) } ?: L10n.t("Bear right")
            Kind.RIGHT -> s?.let { L10n.f("Turn right onto %@", it) } ?: L10n.t("Turn right")
            Kind.SHARP_RIGHT -> s?.let { L10n.f("Turn sharp right onto %@", it) } ?: L10n.t("Turn sharp right")
            Kind.U_TURN -> L10n.t("Turn around")
            Kind.STAIRS_UP -> lvl?.let { L10n.f("Take the stairs up (%@)", it) } ?: L10n.t("Take the stairs up")
            Kind.STAIRS_DOWN -> lvl?.let { L10n.f("Take the stairs down (%@)", it) } ?: L10n.t("Take the stairs down")
            Kind.STAIRS -> L10n.t("Take the stairs")
            Kind.ELEVATOR -> lvl?.let { L10n.f("Take the lift (%@)", it) } ?: L10n.t("Take the lift")
            Kind.LEVEL_UP -> lvl?.let { L10n.f("Go up (%@)", it) } ?: L10n.t("Go up")
            Kind.LEVEL_DOWN -> lvl?.let { L10n.f("Go down (%@)", it) } ?: L10n.t("Go down")
            Kind.ARRIVE -> L10n.f("Arrive at %@", destination)
        }
    }

    companion object {
        fun levelName(level: Double): String {
            if (level == 0.0) return L10n.t("street level")
            val n = if (level == Math.rint(level)) level.toInt().toString() else level.toString()
            return L10n.f("level %@", n.replace("-", "−"))
        }
    }
}

/**
 * Turn-by-turn instructions for a walking leg, and where a GPS fix is along it (WalkRoute on iOS).
 * The router marks stairs and levels but rarely turns, so turns come from the path's shape.
 */
class WalkRoute(leg: Leg) {
    val path: List<LatLon>
    val length: Double
    val maneuvers: List<WalkManeuver>
    /** Router distance per metre of path (stairs are shorter on the map than on foot). */
    val scale: Double

    data class Position(val along: Double, val offset: Double, val remaining: Double, val index: Int, val toNext: Double?)

    init {
        val p = ArrayList<LatLon>()
        var marks = ArrayList<Pair<Double, StepInstruction>>()
        var walked = 0.0
        for (step in leg.steps.orEmpty()) {
            val coords = step.polyline?.let { io.github.elija7580.publit.core.Polyline.decode(it.points, it.precision) }.orEmpty()
            marks += walked to step
            for (c in coords) {
                val last = p.lastOrNull()
                if (last != null) { if (last == c) continue; walked += last.distanceTo(c) }
                p += c
            }
        }
        var pathList: List<LatLon> = p
        if (pathList.size < 2) {
            val g = leg.legGeometry?.let { io.github.elija7580.publit.core.Polyline.decode(it.points, it.precision) }.orEmpty()
            pathList = if (g.size >= 2) g else listOf(leg.from.coordinate, leg.to.coordinate)
            marks = ArrayList()
            walked = PathGeometry.length(pathList)
        }
        path = pathList
        length = walked

        fun levelBefore(k: Int) = if (k > 0) marks[k - 1].second.let { it.toLevel ?: it.fromLevel } else null
        fun levelAfter(k: Int) = if (k + 1 < marks.size) marks[k + 1].second.let { it.fromLevel ?: it.toLevel } else null

        var out = arrayListOf(WalkManeuver(WalkManeuver.Kind.START, 0.0, street = marks.firstOrNull()?.let { street(it.second) }, level = null, coordinate = path[0]))
        for (k in 1 until marks.size) {
            val (at, step) = marks[k]
            val own = listOfNotNull(step.fromLevel, step.toLevel)
            val before = levelBefore(k) ?: own.firstOrNull()
            var after = levelAfter(k)
            if (after == null || after == before) after = own.firstOrNull { it != before } ?: after
            val climb = (after ?: 0.0) - (before ?: 0.0)
            var kind: WalkManeuver.Kind? = explicitKind(step.relativeDirection)
            when (step.relativeDirection) {
                "STAIRS" -> kind = if (climb > 0) WalkManeuver.Kind.STAIRS_UP else if (climb < 0) WalkManeuver.Kind.STAIRS_DOWN else WalkManeuver.Kind.STAIRS
                "ELEVATOR" -> kind = WalkManeuver.Kind.ELEVATOR
                else -> if (kind == null && climb != 0.0 && own.size == 2 && own[0] != own[1]) kind = if (climb > 0) WalkManeuver.Kind.LEVEL_UP else WalkManeuver.Kind.LEVEL_DOWN
            }
            val sinceLast = at - (out.lastOrNull()?.along ?: 0.0)
            if (kind == null && sinceLast >= MIN_SPACING) {
                val b = PathGeometry.point(at - 15, path); val j = PathGeometry.point(at, path); val a = PathGeometry.point(at + 15, path)
                if (b != null && j != null && a != null && b != j && j != a)
                    kind = turnKind(PathGeometry.turn(PathGeometry.bearing(b, j), PathGeometry.bearing(j, a)))
            }
            val name = street(step)
            val point = PathGeometry.point(at, path) ?: path[0]
            if (kind != null) {
                val cl = changesLevel(kind)
                out += WalkManeuver(kind, at, street = if (cl) null else name, level = if (cl) after else null, coordinate = point)
            } else if (name != null) {
                val last = out.last()
                if (last.street != name) {
                    if (last.street == null && at - last.along < 30 && !changesLevel(last.kind)) last.street = name
                    else if (sinceLast >= MIN_SPACING && last.street != null && step.area != true)
                        out += WalkManeuver(WalkManeuver.Kind.STRAIGHT, at, street = name, level = null, coordinate = point)
                }
            }
        }
        // Passages zigzag: a slight bend without a street, or a turn right before the next one, is noise.
        val snapshot = out
        out = ArrayList(snapshot.filterIndexed { i, m ->
            if (i == 0 || !isTurn(m.kind)) return@filterIndexed true
            if ((m.kind == WalkManeuver.Kind.SLIGHT_LEFT || m.kind == WalkManeuver.Kind.SLIGHT_RIGHT) && m.street == null) return@filterIndexed false
            val gap = (if (i + 1 < snapshot.size) snapshot[i + 1].along else walked) - m.along
            if (i + 1 < snapshot.size && isTurn(snapshot[i + 1].kind) && gap < 8) return@filterIndexed false
            !(gap < 4 || (i + 1 == snapshot.size && gap < 8))
        })
        out += WalkManeuver(WalkManeuver.Kind.ARRIVE, walked, street = null, level = null, coordinate = path.last())
        scale = leg.distance?.let { if (walked > 0) it / walked else 1.0 }?.takeIf { it in 0.7..1.6 } ?: 1.0
        for (i in 0 until out.size - 1) out[i].distance = max(0.0, out[i + 1].along - out[i].along) * scale
        markExit(out, marks.firstOrNull()?.second?.fromLevel, leg.from.coordinate)
        maneuvers = out
    }

    fun position(p: LatLon): Position? {
        val hit = PathGeometry.locate(p, path) ?: return null
        val along = min(hit.along, length)
        val index = maneuvers.indexOfLast { it.along <= along + 5 && it.kind != WalkManeuver.Kind.ARRIVE }.coerceAtLeast(0)
        val next = maneuvers.getOrNull(index + 1)
        return Position(along, hit.offset, max(0.0, length - along) * scale, index, next?.let { max(0.0, it.along - along) * scale })
    }

    /** Only the walk you are on, and only near its path. */
    fun livePosition(c: LatLon?, isActive: Boolean): Position? =
        if (!isActive || c == null) null else position(c)?.takeIf { it.offset <= 250 }

    /** Direction of the path at a point, for the compass row. */
    fun bearing(at: Double): Double? {
        val a = PathGeometry.point(at, path) ?: return null
        val b = PathGeometry.point(min(at + 20, length), path) ?: return null
        return if (a == b) null else PathGeometry.bearing(a, b)
    }

    companion object {
        const val MIN_TURN = 35.0
        const val MIN_SPACING = 10.0

        private fun markExit(out: MutableList<WalkManeuver>, startLevel: Double?, start: LatLon) {
            var level = startLevel ?: return
            if (level >= 0) return
            for (i in out.indices) {
                val next = out[i].level ?: continue
                if (level < 0 && next >= 0) {
                    val at = out[i].along
                    val street = out.drop(i + 1).firstOrNull { it.street != null && it.along - at <= 150 }?.street
                    if (street != null) out[i].exitTowards = street
                    else if (start.distanceTo(out[i].coordinate) >= 40) out[i].exitSide = Compass.point(PathGeometry.bearing(start, out[i].coordinate))
                    return
                }
                level = next
            }
        }

        private fun street(step: StepInstruction): String? {
            if (step.relativeDirection == "STAIRS" || step.relativeDirection == "ELEVATOR") return null
            val s = step.streetName?.trim() ?: return null
            return if (s.length > 2 && !s.all { it.isDigit() }) s else null
        }

        private fun isTurn(k: WalkManeuver.Kind) = k in setOf(WalkManeuver.Kind.SLIGHT_LEFT, WalkManeuver.Kind.LEFT, WalkManeuver.Kind.SHARP_LEFT,
            WalkManeuver.Kind.SLIGHT_RIGHT, WalkManeuver.Kind.RIGHT, WalkManeuver.Kind.SHARP_RIGHT, WalkManeuver.Kind.U_TURN)
        private fun changesLevel(k: WalkManeuver.Kind) = k in setOf(WalkManeuver.Kind.STAIRS_UP, WalkManeuver.Kind.STAIRS_DOWN, WalkManeuver.Kind.STAIRS,
            WalkManeuver.Kind.ELEVATOR, WalkManeuver.Kind.LEVEL_UP, WalkManeuver.Kind.LEVEL_DOWN)

        private fun explicitKind(d: String?) = when (d) {
            "LEFT" -> WalkManeuver.Kind.LEFT; "SLIGHTLY_LEFT" -> WalkManeuver.Kind.SLIGHT_LEFT; "HARD_LEFT" -> WalkManeuver.Kind.SHARP_LEFT
            "RIGHT" -> WalkManeuver.Kind.RIGHT; "SLIGHTLY_RIGHT" -> WalkManeuver.Kind.SLIGHT_RIGHT; "HARD_RIGHT" -> WalkManeuver.Kind.SHARP_RIGHT
            "UTURN_LEFT", "UTURN_RIGHT" -> WalkManeuver.Kind.U_TURN
            else -> null
        }

        fun turnKind(delta: Double): WalkManeuver.Kind? {
            val a = abs(delta)
            if (a < MIN_TURN) return null
            if (a >= 165) return WalkManeuver.Kind.U_TURN
            return if (delta < 0) (if (a < 60) WalkManeuver.Kind.SLIGHT_LEFT else if (a < 135) WalkManeuver.Kind.LEFT else WalkManeuver.Kind.SHARP_LEFT)
            else (if (a < 60) WalkManeuver.Kind.SLIGHT_RIGHT else if (a < 135) WalkManeuver.Kind.RIGHT else WalkManeuver.Kind.SHARP_RIGHT)
        }
    }
}
