// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.watch

import android.content.Context
import android.net.Uri
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable

/**
 * The trip under way on a paired Wear OS watch (Google Play services Data Layer). The phone writes one small data
 * item, the watch app shows it as a live trip screen and an ongoing activity. The Data Layer only passes it on when
 * something changed, so an unchanged trip costs nothing. Without Google Play services or without a watch nothing happens.
 */
object WatchLink {
    const val PATH = "/live-trip"

    fun send(ctx: Context, json: String) {
        if (!available(ctx)) return
        runCatching {
            val request = PutDataRequest.create(PATH).setData(json.toByteArray(Charsets.UTF_8)).setUrgent()
            Wearable.getDataClient(ctx.applicationContext).putDataItem(request)
        }
    }

    fun clear(ctx: Context) {
        if (!available(ctx)) return
        runCatching {
            Wearable.getDataClient(ctx.applicationContext)
                .deleteDataItems(Uri.Builder().scheme("wear").path(PATH).build())
        }
    }

    private fun available(ctx: Context): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS
    }.getOrDefault(false)
}
