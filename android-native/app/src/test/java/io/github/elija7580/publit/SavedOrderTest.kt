// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.data.PlaceRole
import io.github.elija7580.publit.data.SavedOrder
import io.github.elija7580.publit.data.SavedPlace
import io.github.elija7580.publit.data.SavedPlaces
import org.junit.Assert.assertEquals
import org.junit.Test

/** Saved stops, places and lines in an order of your own (iOS Publit#124, same cases as FavoritesOrderTests). */
class SavedOrderTest {
    private fun stop(id: String) = SavedPlace(id, "Stop $id", null, 48.0, 16.0, stopId = id)
    private fun place(id: String, lat: Double) = SavedPlace(id, "Place $id", null, lat, 16.0)
    private val isStop: (SavedPlace) -> Boolean = { it.isStop && it.role == null }
    private val isPlace: (SavedPlace) -> Boolean = { !it.isStop && it.role == null }

    @Test fun movedWorksLikeAListDrag() {
        val all = listOf("a", "b", "c", "d")
        fun moved(from: List<Int>, to: Int) = SavedOrder.moved(all, from, to) { true }
        assertEquals(listOf("b", "c", "d", "a"), moved(listOf(0), 4))   // first to the end
        assertEquals(listOf("d", "a", "b", "c"), moved(listOf(3), 0))   // last to the top
        assertEquals(listOf("a", "c", "b", "d"), moved(listOf(1), 3))   // one down
        assertEquals(listOf("a", "c", "b", "d"), moved(listOf(2), 1))   // one up
        assertEquals(listOf("b", "d", "a", "c"), moved(listOf(0, 2), 4))
        assertEquals(all, moved(listOf(1), 1))                          // dropped where it was
        assertEquals(all, moved(listOf(1), 2))
        assertEquals(all, moved(listOf(9), 0))                          // nothing there
        assertEquals(all, moved(listOf(0), 9))
    }

    @Test fun onlyTheMovedKindChangesItsOrder() {
        // Stops (upper case) and places share one stored list.
        val all = listOf("A", "x", "B", "y", "C")
        assertEquals(listOf("C", "x", "A", "y", "B"), SavedOrder.moved(all, listOf(2), 0) { it == it.uppercase() })
        assertEquals(listOf("A", "y", "B", "x", "C"), SavedOrder.moved(all, listOf(0), 2) { it != it.uppercase() })
    }

    @Test fun oneStepUpOrDownAndNothingAtTheEnds() {
        val all = listOf("a", "b", "c")
        fun stepped(index: Int, up: Boolean) = SavedOrder.stepped(all, index, up) { true }
        assertEquals(listOf("b", "a", "c"), stepped(1, true))
        assertEquals(listOf("a", "c", "b"), stepped(1, false))
        assertEquals(listOf("a", "c", "b"), stepped(2, true))
        assertEquals(listOf("b", "a", "c"), stepped(0, false))
        assertEquals(all, stepped(0, true))     // already on top
        assertEquals(all, stepped(2, false))    // already last
        assertEquals(all, stepped(5, true))     // nothing there
    }

    @Test fun stopsMoveWithoutTouchingPlacesHomeOrWork() {
        var items = SavedPlaces.set(emptyList(), place("home", 48.1), PlaceRole.HOME)
        for (id in listOf("1", "2", "3")) items = SavedPlaces.toggle(items, stop(id))
        items = SavedPlaces.toggle(items, place("p", 48.5))
        items = SavedOrder.moved(items, listOf(2), 0, isStop)           // the newest stop to the top
        assertEquals(listOf("3", "1", "2"), items.filter(isStop).map { it.id })
        assertEquals(listOf("p"), items.filter(isPlace).map { it.id })
        assertEquals("home", items.firstOrNull { it.role == PlaceRole.HOME }?.id)
        assertEquals(5, items.size)
        // A stop saved later still goes to the end.
        items = SavedPlaces.toggle(items, stop("4"))
        assertEquals(listOf("3", "1", "2", "4"), items.filter(isStop).map { it.id })
    }

    @Test fun placesMoveWithoutTouchingStops() {
        var items = SavedPlaces.toggle(emptyList(), stop("1"))
        items = SavedPlaces.toggle(items, place("a", 48.1))
        items = SavedPlaces.toggle(items, stop("2"))
        items = SavedPlaces.toggle(items, place("b", 48.2))
        items = SavedOrder.stepped(items, 1, true, isPlace)
        assertEquals(listOf("b", "a"), items.filter(isPlace).map { it.id })
        assertEquals(listOf("1", "2"), items.filter(isStop).map { it.id })
    }
}
