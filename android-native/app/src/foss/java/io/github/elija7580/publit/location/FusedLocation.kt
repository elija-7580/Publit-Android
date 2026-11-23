// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.location

import android.content.Context
import android.location.Location

/** Build without Google services: no fused provider, the platform GPS and network providers do the work. */
object FusedLocation {
    @Suppress("UNUSED_PARAMETER")
    fun start(context: Context, precise: Boolean, offer: (Location?, String) -> Unit): (() -> Unit)? = null
}
