// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.elija7580.publit.data.PlaceRole
import io.github.elija7580.publit.data.SavedPlace

/** Symbols an own shortcut can have (iOS `SavedPlace.shortcutSymbols`, Publit#22). Names are stored. */
object ShortcutSymbol {
    const val DEFAULT = "star"
    val all: List<Pair<String, ImageVector>> = listOf(
        "star" to Icons.Filled.Star, "heart" to Icons.Filled.Favorite, "school" to Icons.Filled.School,
        "fitness" to Icons.Filled.FitnessCenter, "shop" to Icons.Filled.ShoppingCart, "restaurant" to Icons.Filled.Restaurant,
        "cafe" to Icons.Filled.LocalCafe, "park" to Icons.Filled.Park, "hospital" to Icons.Filled.LocalHospital,
        "airport" to Icons.Filled.Flight, "train" to Icons.Filled.Train, "family" to Icons.Filled.Groups,
        "sports" to Icons.Filled.SportsSoccer, "music" to Icons.Filled.MusicNote, "beach" to Icons.Filled.BeachAccess,
        "party" to Icons.Filled.Celebration, "pets" to Icons.Filled.Pets,
    )

    fun icon(name: String?): ImageVector = all.firstOrNull { it.first == name }?.second ?: Icons.Filled.Star

    /** The icon of a pinned place: Home, Work or its own symbol. */
    fun of(p: SavedPlace): ImageVector = when (p.role) {
        PlaceRole.HOME -> Icons.Filled.Home
        PlaceRole.WORK -> Icons.Filled.Work
        null -> icon(p.symbol)
    }
}
