// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.tickets

import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.data.LineColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * Reads an Apple Wallet pass (`.pkpass`, Publit#20) into a Publit ticket, as WalletPassImport on iOS:
 * the code exactly as issued, the route, the validity, the operator and the colour. Everything stays
 * on the device; the signature is not checked (Publit shows the code, the operator's scanner checks it).
 */
object WalletPassImport {
    class NotAPass : Exception() {
        override val message: String get() = L10n.t("This file is not a Wallet pass Publit can read.")
    }

    data class Result(val pass: TicketPass, val codes: List<TicketPass.Barcode>, /** `RRGGBB` of the pass. */ val color: String?)

    private const val MAX_ENTRY = 20 * 1024 * 1024

    /** A ZIP file (a .pkpass is one): starts with "PK\u0003\u0004". */
    fun isZip(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() && bytes[2] == 3.toByte() && bytes[3] == 4.toByte()

    /** The pass inside a `.pkpass` file. */
    fun read(pkpass: ByteArray): Result {
        val json = passJson(pkpass) ?: throw NotAPass()
        return readJson(json)
    }

    private fun passJson(zip: ByteArray): ByteArray? = runCatching {
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: return@use null
                if (!e.isDirectory && e.name == "pass.json") {
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = z.read(buf); if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_ENTRY) return@use null
                    }
                    return@use out.toByteArray()
                }
            }
            null
        }
    }.getOrNull()

    private class Field(val key: String?, val label: String?, val value: String?)

    fun readJson(passJSON: ByteArray): Result {
        val root = runCatching { Json.parseToJsonElement(String(passJSON, Charsets.UTF_8)).jsonObject }.getOrNull() ?: throw NotAPass()
        fun str(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val boarding = root["boardingPass"] as? JsonObject
        val s = listOf("boardingPass", "generic", "eventTicket", "storeCard", "coupon").firstNotNullOfOrNull { root[it] as? JsonObject }
        if (s == null && root["barcode"] == null && root["barcodes"] == null) throw NotAPass()
        fun fields(name: String): List<Field> = (s?.get(name) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { f ->
            val v = f["value"] as? JsonPrimitive
            val text = when {
                v == null -> null
                v.isString -> v.content
                else -> v.doubleOrNull?.let { n -> if (n == Math.rint(n)) n.toLong().toString() else n.toString() } ?: v.content
            }
            Field(str(f, "key"), str(f, "label"), text?.takeIf { it.isNotBlank() })
        }
        val primary = fields("primaryFields")
        val front = fields("headerFields") + primary + fields("secondaryFields") + fields("auxiliaryFields")
        val back = fields("backFields")

        // Text the usual rules understand ("Deutschlandticket", "Gültig bis …", "2. Klasse").
        val lines = (listOfNotNull(str(root, "organizationName"), str(root, "description"), str(root, "logoText")) +
            (front + back).map { listOfNotNull(it.label, it.value).joinToString(" ") })
            .map(TicketTextParser::clean).filter { it.isNotEmpty() }
        val rawCodes = (root["barcodes"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: listOfNotNull(root["barcode"] as? JsonObject)
        val codes = rawCodes.mapNotNull { b -> barcode(str(b, "format"), (b["message"] as? JsonPrimitive)?.contentOrNull, str(b, "messageEncoding"), str(b, "altText")) }
        val draft = TicketTextParser.parse(lines, codes)

        draft.operatorName = str(root, "organizationName") ?: draft.operatorName
        draft.title = draft.title ?: str(root, "description") ?: str(root, "logoText")
        str(s, "transitType")?.let { t -> symbol(t)?.let { draft.symbol = it } }
        // A boarding pass names its start and end in the first two primary fields.
        if (boarding != null && primary.size >= 2) {
            draft.origin = place(primary[0]) ?: draft.origin
            draft.destination = place(primary[1]) ?: draft.destination
        }
        fun field(words: List<String>): String? = (front + back).firstOrNull { f ->
            val k = ((f.key ?: "") + " " + (f.label ?: "")).lowercase()
            words.any { k.contains(it) }
        }?.value
        draft.passenger = field(listOf("passenger", "reisende", "fahrgast", "traveller", "traveler", "name")) ?: draft.passenger
        draft.travelClass = draft.travelClass ?: field(listOf("class", "klasse"))?.firstOrNull { it == '1' || it == '2' }?.toString()
        draft.reference = field(listOf("ticket", "booking", "buchung", "pnr", "auftrag", "order", "reference")) ?: draft.reference ?: str(root, "serialNumber")
        str(root, "relevantDate")?.let(::date)?.let { draft.validFrom = draft.validFrom ?: it }
        str(root, "expirationDate")?.let(::date)?.let { draft.validUntil = it }
        if (draft.category == null) {
            draft.category = if (boarding != null) TicketPass.Category.SINGLE
                else if (draft.validUntil != null && draft.validFrom == null) TicketPass.Category.PERIOD else null
        }

        var pass = TicketPass.of(draft, codes.firstOrNull())
        val color = str(root, "backgroundColor")?.let(::hex)
        if (PassStyle.suggested(draft.operatorName) == null && color != null) pass = pass.copy(style = PassStyle(color))
        val notes = back.mapNotNull { f -> f.value?.let { v -> f.label?.let { "$it: $v" } ?: v } }
        if (notes.isNotEmpty()) pass = pass.copy(notes = notes.joinToString("\n").take(2000))
        return Result(pass, codes, color)
    }

    private fun barcode(format: String?, message: String?, encoding: String?, altText: String?): TicketPass.Barcode? {
        val f = when (format) {
            "PKBarcodeFormatQR" -> TicketPass.Barcode.Format.QR
            "PKBarcodeFormatAztec" -> TicketPass.Barcode.Format.AZTEC
            "PKBarcodeFormatPDF417" -> TicketPass.Barcode.Format.PDF417
            "PKBarcodeFormatCode128" -> TicketPass.Barcode.Format.CODE128
            else -> return null
        }
        if (message.isNullOrEmpty()) return null
        // Binary codes (UIC 918.3) travel as ISO-8859-1 text: one character per byte.
        if (encoding?.lowercase() == "iso-8859-1" && message.any { it.code > 0x7F || it.code < 0x20 } && message.all { it.code <= 0xFF }) {
            val bytes = ByteArray(message.length) { message[it].code.toByte() }
            return TicketPass.Barcode(f, payload = Base64.getEncoder().encodeToString(bytes), altText = altText)
        }
        return TicketPass.Barcode(f, message = message, altText = altText)
    }

    private fun symbol(transitType: String): TicketPass.Symbol? = when (transitType) {
        "PKTransitTypeTrain" -> TicketPass.Symbol.TRAIN
        "PKTransitTypeBus" -> TicketPass.Symbol.BUS
        "PKTransitTypeBoat" -> TicketPass.Symbol.FERRY
        "PKTransitTypeGeneric", "PKTransitTypeAir" -> TicketPass.Symbol.TICKET
        else -> null
    }

    /** A station's name: the label when the value is only a code ("VIE"), else the value. */
    private fun place(f: Field): String? {
        val v = f.value?.trim().orEmpty()
        val label = f.label?.trim().orEmpty()
        if (v.length <= 4 && v == v.uppercase() && label.length > v.length) return label
        return v.ifEmpty { null } ?: label.ifEmpty { null }
    }

    private fun date(s: String): Instant? = runCatching { OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant() }.getOrNull()

    /** `rgb(12, 34, 56)` or `#0c2238` → `0C2238`. */
    fun hex(css: String): String? {
        val t = css.trim().lowercase()
        if (t.startsWith("#")) return LineColors.RGB.parse(t.drop(1))?.let { LineColors.normalized(t) }
        if (!t.startsWith("rgb")) return null
        val nums = Regex("\\d+").findAll(t).map { it.value.toInt() }.toList()
        if (nums.size < 3 || nums.take(3).any { it !in 0..255 }) return null
        return String.format("%02X%02X%02X", nums[0], nums[1], nums[2])
    }
}
