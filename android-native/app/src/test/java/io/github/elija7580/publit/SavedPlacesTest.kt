// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.data.PlaceRole
import io.github.elija7580.publit.data.SavedPlace
import io.github.elija7580.publit.data.SavedPlaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saved places whose addresses share a geocoder ID (issue #21, iOS Publit#59 and Publit#121). */
class SavedPlacesTest {
    private val roma = SavedPlace("adr-1", "Via Roma 1", "Milano, IT", 45.46, 9.19)
    private val haupt = SavedPlace("adr-1", "Hauptstraße 1", "Lugano, CH", 46.00, 8.95)
    private val flat = SavedPlace("a", "Flat", null, 48.2, 16.3)
    private val office = SavedPlace("b", "Office", null, 48.4, 15.6)

    private fun List<SavedPlace>.role(r: PlaceRole) = firstOrNull { it.role == r }
    private fun List<SavedPlace>.places() = filter { !it.isStop && it.role == null }
    private fun List<SavedPlace>.saved(p: SavedPlace) = any { p.isSaved(it) }

    @Test fun workDoesNotReplaceHomeWithTheSameId() {
        var items = SavedPlaces.set(emptyList(), roma, PlaceRole.HOME)
        items = SavedPlaces.set(items, haupt, PlaceRole.WORK)
        assertEquals("Via Roma 1", items.role(PlaceRole.HOME)?.name)
        assertEquals("Hauptstraße 1", items.role(PlaceRole.WORK)?.name)
        items = items.filter { it.role != PlaceRole.WORK }
        assertEquals("Via Roma 1", items.role(PlaceRole.HOME)?.name)
    }

    @Test fun homeAndWorkAtTheSamePlaceAreTwoEntries() {
        var items = SavedPlaces.set(emptyList(), flat, PlaceRole.HOME)
        items = SavedPlaces.set(items, flat, PlaceRole.WORK)
        assertEquals("Flat", items.role(PlaceRole.HOME)?.name)
        assertEquals("Flat", items.role(PlaceRole.WORK)?.name)
        assertEquals(2, items.map { it.id }.toSet().size)
    }

    @Test fun aNewHomeLeavesTheOldOneAsASavedPlace() {
        var items = SavedPlaces.set(emptyList(), flat, PlaceRole.HOME)
        items = SavedPlaces.set(items, office, PlaceRole.HOME)
        assertEquals("b", items.role(PlaceRole.HOME)?.id)
        assertEquals(1, items.count { it.role == PlaceRole.HOME })
        assertTrue(items.places().any { it.id == "a" })
    }

    @Test fun savingASecondAddressWithTheSameIdKeepsBoth() {
        var items = SavedPlaces.toggle(emptyList(), roma)
        assertFalse(items.saved(haupt))
        items = SavedPlaces.toggle(items, haupt)
        assertEquals(listOf("Via Roma 1", "Hauptstraße 1"), items.places().map { it.name })
        assertEquals(2, items.map { it.id }.toSet().size)
        assertTrue(items.saved(roma))
        assertTrue(items.saved(haupt))
    }

    @Test fun removingOneOfTwoAddressesWithTheSameIdLeavesTheOther() {
        var items = SavedPlaces.toggle(SavedPlaces.toggle(emptyList(), roma), haupt)
        items = SavedPlaces.toggle(items, haupt)
        assertEquals(listOf("Via Roma 1"), items.map { it.name })
        items = SavedPlaces.toggle(SavedPlaces.toggle(items, haupt), roma)
        assertEquals(listOf("Hauptstraße 1"), items.map { it.name })
        assertTrue(SavedPlaces.toggle(items, haupt).isEmpty())
    }

    @Test fun savingTheSameAddressTwiceStaysOneEntry() {
        val items = SavedPlaces.toggle(emptyList(), roma)
        assertTrue(items.saved(roma.copy(lat = roma.lat + 0.00005)))
        assertTrue(SavedPlaces.toggle(items, roma.copy(lat = roma.lat + 0.00005)).isEmpty())
    }

    @Test fun homeDoesNotOverwriteASavedAddressWithTheSameId() {
        var items = SavedPlaces.toggle(emptyList(), roma)
        items = SavedPlaces.set(items, haupt, PlaceRole.HOME)
        assertEquals(listOf("Via Roma 1"), items.places().map { it.name })
        assertEquals("Hauptstraße 1", items.role(PlaceRole.HOME)?.name)
        items = SavedPlaces.set(items, roma, PlaceRole.WORK)
        assertTrue(items.places().isEmpty())
        assertEquals("Via Roma 1", items.role(PlaceRole.WORK)?.name)
        assertEquals("Hauptstraße 1", items.role(PlaceRole.HOME)?.name)
        assertEquals(items.size, items.map { it.id }.toSet().size)
    }

    @Test fun savingAnAddressDoesNotTouchHomeOrWorkWithTheSameId() {
        var items = SavedPlaces.set(SavedPlaces.set(emptyList(), roma, PlaceRole.HOME), haupt, PlaceRole.WORK)
        val third = SavedPlace("adr-1", "Rue de Rome 1", "Paris, FR", 48.87, 2.32)
        items = SavedPlaces.toggle(items, third)
        assertEquals("Via Roma 1", items.role(PlaceRole.HOME)?.name)
        assertEquals("Hauptstraße 1", items.role(PlaceRole.WORK)?.name)
        assertEquals(listOf("Rue de Rome 1"), items.places().map { it.name })
        assertEquals(2, SavedPlaces.toggle(items, third).size)
    }

    @Test fun settingTheSameWorkAgainLeavesNoCopy() {
        var items = SavedPlaces.set(SavedPlaces.set(emptyList(), roma, PlaceRole.HOME), haupt, PlaceRole.WORK)
        items = SavedPlaces.set(items, haupt, PlaceRole.WORK)
        assertEquals(2, items.size)
        assertEquals("Hauptstraße 1", items.role(PlaceRole.WORK)?.name)
        assertEquals("Via Roma 1", items.role(PlaceRole.HOME)?.name)
    }

    @Test fun aStopIsStillKnownByItsId() {
        val stop = SavedPlace("s1", "Marienplatz", null, 48.137, 11.575, stopId = "s1")
        val items = SavedPlaces.toggle(emptyList(), stop)
        assertTrue(items.saved(stop.copy(lat = 48.2)))
        assertTrue(SavedPlaces.toggle(items, stop.copy(lat = 48.2)).isEmpty())
    }
}
