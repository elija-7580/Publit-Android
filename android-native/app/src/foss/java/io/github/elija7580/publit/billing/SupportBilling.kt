// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.billing

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Build without Google services (F-Droid): no Google Play Billing. The support screen shows the
 * link to publit.eu instead of amounts. Same shape as the Play version, so the UI stays shared.
 */
object SupportBilling {
    const val webOnly = true

    data class Offer(val id: String, val price: String, val monthly: Boolean)
    data class State(
        val loaded: Boolean = true, val monthly: List<Offer> = emptyList(), val oneTime: List<Offer> = emptyList(),
        val activeMonthly: String? = null, val purchasing: String? = null, val thanked: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    @Suppress("UNUSED_PARAMETER") fun load(context: Context) {}
    @Suppress("UNUSED_PARAMETER") fun buy(activity: Activity, offer: Offer) {}
}
