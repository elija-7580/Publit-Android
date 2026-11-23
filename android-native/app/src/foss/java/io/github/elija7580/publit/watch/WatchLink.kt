// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.watch

import android.content.Context

/** Without Google Play services there is no Data Layer to a Wear OS watch: the trip stays on the phone. */
object WatchLink {
    const val PATH = "/live-trip"

    @Suppress("UNUSED_PARAMETER") fun send(ctx: Context, json: String) {}
    @Suppress("UNUSED_PARAMETER") fun clear(ctx: Context) {}
}
