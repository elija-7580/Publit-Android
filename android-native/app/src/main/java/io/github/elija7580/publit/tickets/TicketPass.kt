package io.github.elija7580.publit.tickets

import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.data.ApiInstant
import io.github.elija7580.publit.data.LineColors
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.UUID

/**
 * A ticket kept in Publit as a pass (TicketPass on iOS): the details the user checked, an optional
 * static code and the original document. Stored on the device only; a copy for quick access.
 */
@Serializable
data class TicketPass(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val operatorName: String? = null,
    val category: Category = Category.SINGLE,
    val symbol: Symbol = Symbol.TICKET,
    val origin: String? = null,
    val destination: String? = null,
    val via: String? = null,
    val passenger: String? = null,
    /** "1" or "2". */
    val travelClass: String? = null,
    /** As printed on the ticket, e.g. "€ 19,90". */
    val price: String? = null,
    /** Ticket or booking number. */
    val reference: String? = null,
    val validFrom: ApiInstant? = null,
    val validUntil: ApiInstant? = null,
    val barcode: Barcode? = null,
    val document: Document? = null,
    val style: PassStyle = PassStyle.standard,
    val notes: String? = null,
    /** The saved trip it was added for, if any. */
    val tripId: String? = null,
    val logo: Logo? = null,
    /** Set when the user moved the ticket to the archive by hand. */
    val archivedAt: ApiInstant? = null,
    /** The user took an expired ticket back out of the archive: it stays in the list. */
    val keptOut: Boolean? = null,
    val createdAt: ApiInstant = Instant.now(),
    val updatedAt: ApiInstant = Instant.now(),
) {
    @Serializable
    enum class Category {
        @SerialName("single") SINGLE, @SerialName("returnTrip") RETURN_TRIP, @SerialName("dayPass") DAY_PASS,
        @SerialName("period") PERIOD, @SerialName("subscription") SUBSCRIPTION, @SerialName("reservation") RESERVATION,
        @SerialName("other") OTHER;

        val title: String get() = L10n.t(when (this) {
            SINGLE -> "Single ticket"; RETURN_TRIP -> "Return ticket"; DAY_PASS -> "Day ticket"; PERIOD -> "Weekly or monthly pass"
            SUBSCRIPTION -> "Annual pass or subscription"; RESERVATION -> "Seat reservation"; OTHER -> "Other ticket"
        })
        /** Valid for many trips, so it is offered for every trip while it is valid. */
        val isPass: Boolean get() = this == DAY_PASS || this == PERIOD || this == SUBSCRIPTION
    }

    @Serializable
    enum class Symbol {
        @SerialName("train") TRAIN, @SerialName("tram") TRAM, @SerialName("metro") METRO, @SerialName("bus") BUS,
        @SerialName("ferry") FERRY, @SerialName("ticket") TICKET;

        val title: String get() = L10n.t(when (this) {
            TRAIN -> "Train"; TRAM -> "Tram"; METRO -> "Metro"; BUS -> "Bus"; FERRY -> "Ferry"; TICKET -> "Ticket"
        })
        /** A mode for the icon set of the app. */
        val mode: String get() = when (this) { TRAIN -> "RAIL"; TRAM -> "TRAM"; METRO -> "SUBWAY"; BUS -> "BUS"; FERRY -> "FERRY"; TICKET -> "OTHER" }
    }

    /** A code that does not change. Codes that rotate every few seconds only work in the operator's app. */
    @Serializable
    data class Barcode(
        val format: Format,
        /** Text content, when the code holds text. */
        val message: String? = null,
        /** Raw bytes (base64) when the text does not represent them exactly (UIC 918.3 rail tickets). */
        val payload: String? = null,
        val altText: String? = null,
        val region: Region? = null,
    ) {
        @Serializable
        enum class Format {
            @SerialName("qr") QR, @SerialName("aztec") AZTEC, @SerialName("pdf417") PDF417, @SerialName("code128") CODE128,
            @SerialName("dataMatrix") DATA_MATRIX, @SerialName("ean13") EAN13, @SerialName("ean8") EAN8, @SerialName("upce") UPCE,
            @SerialName("code39") CODE39, @SerialName("code93") CODE93, @SerialName("itf") ITF, @SerialName("codabar") CODABAR;

            val title: String get() = when (this) {
                QR -> "QR"; AZTEC -> "Aztec"; PDF417 -> "PDF417"; CODE128 -> "Code 128"; DATA_MATRIX -> "Data Matrix"; EAN13 -> "EAN-13"
                EAN8 -> "EAN-8"; UPCE -> "UPC-E"; CODE39 -> "Code 39"; CODE93 -> "Code 93"; ITF -> "ITF"; CODABAR -> "Codabar"
            }
            val isSquare get() = this == QR || this == AZTEC || this == DATA_MATRIX
            val isLinear get() = !isSquare && this != PDF417
            /** Publit can draw these again from their content; the others are shown as cut out of the original. */
            val canRedraw get() = this == QR || this == AZTEC || this == PDF417 || this == CODE128 || this == DATA_MATRIX
        }

        /** Where the code sits on the original page, as fractions (origin top left). */
        @Serializable
        data class Region(val x: Double, val y: Double, val width: Double, val height: Double) {
            /** With a margin for the quiet zone scanners need, kept inside the page. */
            fun padded(fraction: Double = 0.12): Region {
                val dx = maxOf(width * fraction, 0.01); val dy = maxOf(height * fraction, 0.01)
                val x0 = maxOf(0.0, x - dx); val y0 = maxOf(0.0, y - dy)
                val x1 = minOf(1.0, x + width + dx); val y1 = minOf(1.0, y + height + dy)
                return Region(x0, y0, x1 - x0, y1 - y0)
            }
        }

        /** The bytes to encode when the code is drawn again. */
        val bytes: ByteArray? get() = payload?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) } ?: message?.toByteArray()

        /** Static rail ticket code after UIC 918.3 (header `#UT`). */
        val isUIC: Boolean get() = bytes?.let { it.size >= 3 && it[0] == '#'.code.toByte() && it[1] == 'U'.code.toByte() && it[2] == 'T'.code.toByte() } == true

        /** Four-digit UIC company code of the issuer in a UIC 918.3 header, e.g. 1181 for ÖBB. */
        val uicIssuerCode: String?
            get() {
                val b = bytes ?: return null
                if (!isUIC || b.size < 9) return null
                val digits = b.copyOfRange(5, 9)
                return if (digits.all { it in 0x30..0x39 }) String(digits) else null
            }

        val dedupeKey: String get() = format.name + "|" + (message ?: payload ?: "")
    }

    @Serializable data class Logo(val file: String, val source: String? = null)

    @Serializable
    data class Document(val file: String, val kind: Kind, val page: Int = 0, val fingerprint: String? = null) {
        @Serializable enum class Kind { @SerialName("pdf") PDF, @SerialName("image") IMAGE }
    }

    sealed class Validity {
        object Unknown : Validity()
        data class Upcoming(val from: Instant) : Validity()
        data class Valid(val until: Instant?) : Validity()
        data class Expired(val at: Instant) : Validity()
    }

    fun validity(at: Instant = Instant.now()): Validity {
        validUntil?.let { if (it < at) return Validity.Expired(it) }
        validFrom?.let { if (it > at) return Validity.Upcoming(it) }
        if (validFrom == null && validUntil == null) return Validity.Unknown
        return Validity.Valid(validUntil)
    }

    fun isArchived(at: Instant = Instant.now()): Boolean {
        if (archivedAt != null) return true
        if (keptOut == true) return false
        val v = validity(at)
        return v is Validity.Expired && at.epochSecond - v.at.epochSecond >= ARCHIVE_DELAY
    }

    /** For ordering the archive: when the ticket ended, else when it was archived or added. */
    val archiveDate: Instant get() = validUntil ?: validFrom ?: archivedAt ?: createdAt
    val hasRoute: Boolean get() = !origin.isNullOrEmpty() && !destination.isNullOrEmpty()
    val displayTitle: String get() = title.trim().ifEmpty { operatorName?.takeIf { it.isNotEmpty() } ?: category.title }
    val travelClassText: String? get() = travelClass?.let { L10n.f("Class %@", it) }

    companion object {
        /** Expired tickets stay in the list for a day (a delayed trip, a late check), then move to the archive. */
        const val ARCHIVE_DELAY = 24 * 3600L

        fun of(draft: TicketDraft, barcode: Barcode?) = TicketPass(
            title = draft.title ?: "", operatorName = draft.operatorName, category = draft.category ?: Category.SINGLE,
            symbol = draft.symbol ?: Symbol.TICKET, origin = draft.origin, destination = draft.destination, via = draft.via,
            passenger = draft.passenger, travelClass = draft.travelClass, price = draft.price, reference = draft.reference,
            validFrom = draft.validFrom, validUntil = draft.validUntil, barcode = barcode,
            style = PassStyle.suggested(draft.operatorName) ?: PassStyle.standard)
    }
}

/** The list order: valid now (ending soonest first), upcoming, without dates, expired (latest first). */
enum class PassGroup {
    VALID, UPCOMING, UNDATED, EXPIRED;

    val title: String get() = L10n.t(when (this) { VALID -> "Valid now"; UPCOMING -> "Upcoming"; UNDATED -> "No dates"; EXPIRED -> "Expired" })

    companion object {
        fun of(p: TicketPass, at: Instant): PassGroup = when (p.validity(at)) {
            is TicketPass.Validity.Valid -> VALID; is TicketPass.Validity.Upcoming -> UPCOMING
            TicketPass.Validity.Unknown -> UNDATED; is TicketPass.Validity.Expired -> EXPIRED
        }

        fun grouped(passes: List<TicketPass>, at: Instant): List<Pair<PassGroup, List<TicketPass>>> = entries.mapNotNull { g ->
            val far = Instant.MAX; val past = Instant.MIN
            val members = passes.filter { !it.isArchived(at) && of(it, at) == g }.let { m ->
                when (g) {
                    VALID -> m.sortedWith(compareBy<TicketPass> { it.validUntil ?: far }.thenBy { it.createdAt })
                    UPCOMING -> m.sortedBy { it.validFrom ?: far }
                    UNDATED -> m.sortedByDescending { it.createdAt }
                    EXPIRED -> m.sortedByDescending { it.validUntil ?: past }
                }
            }
            if (members.isEmpty()) null else g to members
        }
    }
}

object PassArchive {
    /** Search across what a ticket shows: title, operator, route, passenger, number, notes. */
    fun matches(p: TicketPass, query: String): Boolean {
        val words = query.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val hay = fold(listOfNotNull(p.displayTitle, p.operatorName, p.origin, p.destination, p.via, p.passenger, p.reference, p.notes, p.category.title).joinToString(" "))
        return words.all { fold(it) in hay }
    }
    private fun fold(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}

object PassFormat {
    /** Dates without a time of day (midnight, or 23:59 at the end) are shown as dates only. */
    fun date(instant: Instant, end: Boolean): String {
        val z = instant.atZone(ZoneId.systemDefault())
        val t = z.toLocalTime()
        val dateOnly = (end && t.hour == 23 && t.minute == 59) || (!end && t == LocalTime.MIDNIGHT)
        val f = if (dateOnly) DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        return z.format(f.withLocale(L10n.locale))
    }
}

/** The look of a pass: only the background is chosen; text colours follow with WCAG AA contrast. */
@Serializable
data class PassStyle(val background: String) {
    private val rgb get() = LineColors.RGB.parse(background) ?: LineColors.RGB(0.07, 0.07, 0.07)
    /** White or black, whichever contrasts more. */
    val foreground: String
        get() = if (LineColors.contrast(LineColors.RGB(1.0, 1.0, 1.0), rgb) >= LineColors.contrast(LineColors.RGB(0.0, 0.0, 0.0), rgb)) "FFFFFF" else "000000"
    /** Field labels: the text colour blended towards the background, as long as it stays legible. */
    val label: String
        get() {
            val fg = LineColors.RGB.parse(foreground)!!
            for (a in listOf(0.30, 0.22, 0.14)) {
                val m = LineColors.RGB(fg.r + (rgb.r - fg.r) * a, fg.g + (rgb.g - fg.g) * a, fg.b + (rgb.b - fg.b) * a)
                if (LineColors.contrast(m, rgb) >= 4.5) return m.hex
            }
            return foreground
        }

    companion object {
        val standard = PassStyle("111111")
        val presets = listOf("111111" to "Black", "FFC531" to "Yellow", "D70015" to "Red", "0066D6" to "Blue", "1B7A33" to "Green",
            "5E3B8C" to "Purple", "00727A" to "Teal", "B25000" to "Orange", "3A3A3C" to "Graphite", "F2F2F7" to "Light")
        /** Operator colour when the operator is known. */
        fun suggested(operatorName: String?): PassStyle? = operatorName?.let { KnownOperators.find(it) }?.let { PassStyle(it.color) }
    }
}
