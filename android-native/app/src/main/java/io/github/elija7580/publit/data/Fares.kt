package io.github.elija7580.publit.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Currency

@Serializable data class FareRiderCategory(val riderCategoryName: String, val isDefaultFareCategory: Boolean = false)
@Serializable data class FareMedia(val fareMediaName: String? = null, val fareMediaType: String = "NONE")

@Serializable
data class FareProduct(val name: String, val amount: Double, val currency: String, val riderCategory: FareRiderCategory? = null, val media: FareMedia? = null) {
    val isUnrestrictedDefault get() = (riderCategory == null || riderCategory.isDefaultFareCategory) &&
        (media == null || media.fareMediaType == "NONE" || media.fareMediaType == "PAPER_TICKET")
    val formattedAmount get() = Fares.format(amount, currency)
}

/** MOTIS fare transfer; `effectiveFareLegProducts` comes as [[[P]]] (bundles) or [[P]] (older servers). */
@Serializable
data class FareTransfer(val rule: String? = null, val transferProducts: List<FareProduct>? = null, val effectiveFareLegProducts: JsonElement? = null) {
    val legProducts: List<List<List<FareProduct>>> by lazy {
        val outer = effectiveFareLegProducts as? JsonArray ?: return@lazy emptyList()
        outer.map { choices ->
            (choices as? JsonArray).orEmpty().map { c ->
                when (c) {
                    is JsonArray -> c.mapNotNull { p -> runCatching { Fares.json.decodeFromJsonElement(FareProduct.serializer(), p) }.getOrNull() }
                    is JsonObject -> listOfNotNull(runCatching { Fares.json.decodeFromJsonElement(FareProduct.serializer(), c) }.getOrNull())
                    else -> emptyList()
                }
            }
        }
    }
}

data class FareEstimate(val amount: Double, val currency: String) { val formattedAmount get() = Fares.format(amount, currency) }

object Fares {
    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    fun format(amount: Double, currency: String): String = runCatching {
        NumberFormat.getCurrencyInstance(io.github.elija7580.publit.core.L10n.locale).apply { this.currency = Currency.getInstance(currency) }.format(amount)
    }.getOrDefault("$amount $currency")

    fun products(it: Itinerary): List<FareProduct> =
        it.fareTransfers.orEmpty().flatMap { t -> t.transferProducts.orEmpty() + t.legProducts.flatten().flatten() }.distinct()

    /** Fail closed: only a fully covered itinerary with one unambiguous unrestricted default bundle is estimated. */
    fun estimate(it: Itinerary): FareEstimate? {
        val transfers = it.fareTransfers ?: return null
        val rides = it.transitLegs
        if (transfers.isEmpty() || rides.isEmpty()) return null
        if (!rides.all { l -> val i = l.fareTransferIndex; val j = l.effectiveFareLegIndex
                i != null && j != null && i in transfers.indices && j in transfers[i].legProducts.indices }) return null
        if (rides.mapNotNull { it.fareTransferIndex }.toSet() != transfers.indices.toSet()) return null
        val products = ArrayList<FareProduct>()
        fun bundle(choices: List<List<FareProduct>>): List<FareProduct>? =
            choices.filter { c -> c.isNotEmpty() && c.all { it.isUnrestrictedDefault } }.singleOrNull()
        for (t in transfers) {
            val extra = t.transferProducts.orEmpty()
            if (extra.size > 1 || !extra.all { it.isUnrestrictedDefault }) return null
            when (t.rule) {
                "AB" -> { if (extra.isEmpty()) return null; products += extra }
                "A_AB" -> { products += bundle(t.legProducts.firstOrNull() ?: return null) ?: return null; products += extra }
                "A_AB_B", null -> {
                    if (t.legProducts.isEmpty() || (t.rule == null && t.legProducts.size != 1)) return null
                    for (c in t.legProducts) products += bundle(c) ?: return null
                    products += extra
                }
                else -> return null
            }
        }
        val currency = products.firstOrNull()?.currency ?: return null
        if (currency.length != 3 || products.any { it.currency != currency || it.amount.isNaN() }) return null
        val sum = products.fold(BigDecimal.ZERO) { a, p -> a + BigDecimal.valueOf(p.amount) }.toDouble()
        return if (sum >= 0) FareEstimate(sum, currency) else null
    }
}
