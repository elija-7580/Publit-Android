package io.github.elija7580.publit.data

/** One line at a station with its next departures, e.g. "U3 → Moosach: 3, 13, 23 min". */
data class LineAtStop(val key: String, val departures: List<StopTime>) {
    val first: StopTime get() = departures[0]
    val group: ModeGroup get() = ModeGroup.of(first.mode)

    /** Next departures per direction (up to `perDirection`), directions ordered by their next departure. */
    fun byDirection(perDirection: Int = 3): List<Pair<String?, List<StopTime>>> {
        val order = ArrayList<String>()
        val buckets = HashMap<String, Pair<String?, MutableList<StopTime>>>()
        for (st in departures) {
            val k = st.directionName?.let(TransitLabels::identity) ?: ""
            val b = buckets.getOrPut(k) { order.add(k); st.directionName to mutableListOf() }
            if (b.second.size < perDirection) b.second.add(st)
        }
        return order.map { buckets.getValue(it) }
    }
}

object LineGrouping {
    /** Saved-line key, as SavedLine.key on iOS. */
    fun key(st: StopTime): String = st.routeId ?: "${st.agencyName ?: ""}|${st.lineName}"

    /** Lines ordered by mode (train first), then by name in natural order ("2" before "10"). */
    fun lines(stopTimes: List<StopTime>, filter: ModeFilter = ModeFilter()): List<LineAtStop> {
        val order = ArrayList<String>()
        val buckets = HashMap<String, MutableList<StopTime>>()
        for (st in stopTimes) {
            if (!filter.allows(st.mode)) continue
            val k = key(st)
            buckets.getOrPut(k) { order.add(k); mutableListOf() }.add(st)
        }
        return order.map { LineAtStop(it, buckets.getValue(it).sortedBy { s -> s.departureTime }) }
            .sortedWith { a, b ->
                val g = a.group.ordinal.compareTo(b.group.ordinal)
                if (g != 0) return@sortedWith g
                val n = naturalCompare(a.first.lineName, b.first.lineName)
                if (n != 0) n else a.first.departureTime.compareTo(b.first.departureTime)
            }
    }

    /** Case-insensitive, numbers by value. */
    fun naturalCompare(a: String, b: String): Int {
        val re = Regex("\\d+|\\D+")
        val pa = re.findAll(a.lowercase()).map { it.value }.toList()
        val pb = re.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]; val y = pb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) x.toBigInteger().compareTo(y.toBigInteger()) else x.compareTo(y)
            if (c != 0) return c
        }
        return pa.size.compareTo(pb.size)
    }
}
