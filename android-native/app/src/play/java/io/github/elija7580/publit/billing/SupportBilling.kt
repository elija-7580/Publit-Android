package io.github.elija7580.publit.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import io.github.elija7580.publit.data.Support
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Monthly support and one-time tips through Google Play Billing (SupportStore on iOS). Unlocks nothing. */
object SupportBilling : PurchasesUpdatedListener {
    /** True only in the build without Google services: support then goes through publit.eu. */
    const val webOnly = false

    data class Offer(val id: String, val price: String, val monthly: Boolean, val details: ProductDetails, val offerToken: String?)
    data class State(
        val loaded: Boolean = false, val monthly: List<Offer> = emptyList(), val oneTime: List<Offer> = emptyList(),
        val activeMonthly: String? = null, val purchasing: String? = null, val thanked: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    private var client: BillingClient? = null

    private fun client(context: Context): BillingClient = client ?: BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build().also { client = it }

    private suspend fun connect(c: BillingClient): Boolean {
        if (c.isReady) return true
        return suspendCancellableCoroutine { cont ->
            c.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(r: BillingResult) { if (cont.isActive) cont.resume(r.responseCode == BillingClient.BillingResponseCode.OK) }
                override fun onBillingServiceDisconnected() { if (cont.isActive) cont.resume(false) }
            })
        }
    }

    /** Loads prices and the running support; also finishes purchases that were pending. */
    fun load(context: Context) = scope.launch {
        val c = client(context)
        if (!connect(c)) { _state.value = _state.value.copy(loaded = true); return@launch }
        val subs = query(c, Support.monthlyIds, BillingClient.ProductType.SUBS).mapNotNull { d ->
            val offer = d.subscriptionOfferDetails?.firstOrNull() ?: return@mapNotNull null
            val price = offer.pricingPhases.pricingPhaseList.lastOrNull()?.formattedPrice ?: return@mapNotNull null
            Offer(d.productId, price, true, d, offer.offerToken)
        }
        val tips = query(c, Support.oneTimeIds, BillingClient.ProductType.INAPP).mapNotNull { d ->
            d.oneTimePurchaseOfferDetails?.formattedPrice?.let { Offer(d.productId, it, false, d, null) }
        }
        _state.value = _state.value.copy(loaded = true,
            monthly = Support.sorted(subs, { it.id }, Support.Kind.MONTHLY),
            oneTime = Support.sorted(tips, { it.id }, Support.Kind.ONE_TIME))
        refresh(c)
    }

    private suspend fun query(c: BillingClient, ids: List<String>, type: String): List<ProductDetails> = suspendCancellableCoroutine { cont ->
        val params = QueryProductDetailsParams.newBuilder().setProductList(ids.map {
            QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(type).build()
        }).build()
        c.queryProductDetailsAsync(params) { _, result -> if (cont.isActive) cont.resume(result.productDetailsList) }
    }

    private suspend fun refresh(c: BillingClient) {
        val subs = suspendCancellableCoroutine { cont ->
            c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { _, l -> if (cont.isActive) cont.resume(l) }
        }
        val tips = suspendCancellableCoroutine { cont ->
            c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { _, l -> if (cont.isActive) cont.resume(l) }
        }
        (subs + tips).forEach { handle(c, it, thank = false) }
        val active = subs.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }?.products?.firstOrNull { Support.parse(it)?.first == Support.Kind.MONTHLY }
        _state.value = _state.value.copy(activeMonthly = active)
    }

    fun buy(activity: Activity, offer: Offer) {
        val c = client ?: return
        val product = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(offer.details)
            .apply { offer.offerToken?.let { setOfferToken(it) } }.build()
        _state.value = _state.value.copy(purchasing = offer.id)
        val r = c.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(product)).build())
        if (r.responseCode != BillingClient.BillingResponseCode.OK) _state.value = _state.value.copy(purchasing = null)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        val c = client
        _state.value = _state.value.copy(purchasing = null)
        if (c == null || result.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) return
        scope.launch { purchases.forEach { handle(c, it, thank = true) }; refresh(c) }
    }

    /** Tips are consumed (they can be given again), monthly support is acknowledged. */
    private suspend fun handle(c: BillingClient, p: Purchase, thank: Boolean) {
        if (p.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val kind = p.products.firstNotNullOfOrNull { Support.parse(it)?.first } ?: return
        if (kind == Support.Kind.ONE_TIME) suspendCancellableCoroutine { cont ->
            c.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { _, _ -> if (cont.isActive) cont.resume(Unit) }
        } else if (!p.isAcknowledged) suspendCancellableCoroutine { cont ->
            c.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { if (cont.isActive) cont.resume(Unit) }
        }
        if (thank) _state.value = _state.value.copy(thanked = true)
    }
}
