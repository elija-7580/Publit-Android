package io.github.elija7580.publit.data

/** "Choose on map" (ChooseOnMapView on iOS): what to call the point under the pin. */
object MapPick {
    /** Street and number of the nearest address, else the nearest named place (not a stop); null when nothing is known. */
    fun label(matches: List<GeocodeMatch>): String? {
        val m = matches.firstOrNull { it.type == "ADDRESS" } ?: matches.firstOrNull { !it.isStop && it.name.isNotBlank() } ?: return null
        val street = listOfNotNull(m.street?.takeIf { it.isNotBlank() }, m.houseNumber?.takeIf { it.isNotBlank() }).joinToString(" ")
        return street.ifEmpty { m.name.takeIf { it.isNotBlank() } }
    }

    /** The visible height in metres (kept when a tap moves the pin), never closer than 150 m. */
    fun spanMeters(latSpan: Double): Double = maxOf(150.0, latSpan * 111_000)
}
