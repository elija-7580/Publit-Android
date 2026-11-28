// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.SavedLists
import io.github.elija7580.publit.data.SavedTrip
import io.github.elija7580.publit.data.TripStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/**
 * Saved data survives an entry the app cannot read (iOS `StabilityAuditTests`, "Saved data survives an entry
 * the app cannot read", Publit#207).
 */
class SavedListsTest {
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private val t0: Instant = Instant.ofEpochSecond(1_791_360_000)
    private val p = Place(name = "P", lat = 48.2, lon = 16.37)

    private fun ride(id: String, startMin: Int): Itinerary {
        val start = t0.plusSeconds(startMin * 60L)
        val leg = Leg(mode = "SUBWAY", from = p, to = p, duration = 600, startTime = start, endTime = start.plusSeconds(600), tripId = "T-$id")
        return Itinerary(id = id, duration = 600, startTime = leg.startTime, endTime = leg.endTime, legs = listOf(leg))
    }

    /** trips.json of a store with three trips; the middle one has lost its route. */
    private fun dirWithOneBrokenTrip(): File {
        val dir = Files.createTempDirectory("trips").toFile()
        val s = TripStore(dir)
        s.save(ride("a", 10), "A", "B"); s.save(ride("b", 20), "A", "B"); s.save(ride("c", 30), "A", "B")
        val file = File(dir, "trips.json")
        val entries = json.parseToJsonElement(file.readText()) as JsonArray
        assertEquals(3, entries.size)
        val broken = entries.mapIndexed { i, e -> if (i == 1) JsonObject((e as JsonObject).filterKeys { it != "itinerary" }) else e }
        file.writeText(JsonArray(broken).toString())
        return dir
    }

    @Test fun oneUnreadableEntryIsLeftOutAndTheOthersStay() {
        val text = File(dirWithOneBrokenTrip(), "trips.json").readText()
        val read = SavedLists.decode(json, SavedTrip.serializer(), text)
        assertEquals(2, read.items.size)
        assertEquals(1, read.skipped)
        assertFalse(read.damaged)
        assertFalse(read.complete)
    }

    @Test fun aListThatReadsFineIsCompleteAndGetsNoCopy() {
        val dir = Files.createTempDirectory("trips").toFile()
        val s = TripStore(dir)
        s.save(ride("a", 10), "A", "B"); s.save(ride("b", 20), "A", "B")
        val file = File(dir, "trips.json")
        assertTrue(SavedLists.decode(json, SavedTrip.serializer(), file.readText()).complete)
        assertEquals(2, TripStore(dir).trips.size)
        assertFalse(SavedLists.copyFile(file).exists())
        // No file yet: an empty list, nothing to keep.
        val empty = Files.createTempDirectory("trips").toFile()
        assertTrue(TripStore(empty).trips.isEmpty())
        assertFalse(SavedLists.copyFile(File(empty, "trips.json")).exists())
    }

    @Test fun textThatIsNoListGivesAnEmptyListAndIsKept() {
        for (text in listOf("", "{\"trips\":[]}", "[{\"id\":\"a\"", "not json")) {
            val read = SavedLists.decode(json, SavedTrip.serializer(), text)
            assertTrue(text, read.items.isEmpty())
            assertTrue(text, read.damaged)
        }
        val dir = Files.createTempDirectory("trips").toFile()
        val file = File(dir, "trips.json")
        file.writeText("[{\"id\":\"a\"")
        assertTrue(TripStore(dir).trips.isEmpty())
        assertEquals("[{\"id\":\"a\"", SavedLists.copyFile(file).readText())
    }

    /** The store starts with the trips it can read; its next save does not take the old file with it. */
    @Test fun aTripStoreKeepsTheReadableTripsAndACopyOfTheFile() {
        val dir = dirWithOneBrokenTrip()
        val file = File(dir, "trips.json")
        val before = file.readText()
        val s = TripStore(dir)
        assertEquals(listOf("a", "c"), s.trips.map { it.itinerary.id }.sorted())
        assertEquals(before, SavedLists.copyFile(file).readText())
        s.save(ride("d", 40), "A", "B")
        assertEquals(listOf("a", "c", "d"), TripStore(dir).trips.map { it.itinerary.id }.sorted())
        assertEquals(before, SavedLists.copyFile(file).readText())
    }
}
