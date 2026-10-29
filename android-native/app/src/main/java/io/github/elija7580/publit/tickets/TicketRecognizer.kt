package io.github.elija7580.publit.tickets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.github.elija7580.publit.core.L10n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** What one page of a ticket contains, read on the device. Nothing is uploaded. */
data class TicketScan(val lines: List<String>, val barcodes: List<TicketPass.Barcode>, val suggestedColor: String?)

/**
 * Reading and drawing tickets on the device (TicketRecognizer on iOS): PdfRenderer renders pages,
 * ML Kit reads text and codes, ZXing draws codes again.
 */
object TicketRecognizer {
    class Unreadable : Exception(L10n.t("This file could not be read."))

    fun pageCount(file: File): Int = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
    }.getOrDefault(0)

    /** A PDF page on white, at most `maxPixels` on its long side (6× its size at most). */
    fun renderPage(file: File, page: Int, maxPixels: Int = 2600): Bitmap? = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                if (page !in 0 until r.pageCount) return@runCatching null
                r.openPage(page).use { p ->
                    val scale = minOf(maxPixels.toFloat() / maxOf(p.width, p.height), 6f)
                    val bmp = Bitmap.createBitmap(maxOf(1, (p.width * scale).toInt()), maxOf(1, (p.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    }.getOrNull()

    /** An image file upright (EXIF orientation applied), at most `maxPixels` on its long side. */
    fun loadImage(file: File, maxPixels: Int = 3200): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPixels) sample *= 2
        var bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
        val rotation = runCatching {
            when (androidx.exifinterface.media.ExifInterface(file.path).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
        val long = maxOf(bmp.width, bmp.height)
        if (long > maxPixels) { val f = maxPixels.toFloat() / long; bmp = Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * f).toInt()), maxOf(1, (bmp.height * f).toInt()), true) }
        bmp
    }.getOrNull()

    fun page(file: File, pdf: Boolean, page: Int, maxPixels: Int = -1): Bitmap? =
        if (pdf) renderPage(file, page, if (maxPixels < 0) 2600 else maxPixels) else loadImage(file, if (maxPixels < 0) 3200 else maxPixels)

    private fun format(f: Int): TicketPass.Barcode.Format? = when (f) {
        Barcode.FORMAT_QR_CODE -> TicketPass.Barcode.Format.QR; Barcode.FORMAT_AZTEC -> TicketPass.Barcode.Format.AZTEC
        Barcode.FORMAT_PDF417 -> TicketPass.Barcode.Format.PDF417; Barcode.FORMAT_CODE_128 -> TicketPass.Barcode.Format.CODE128
        Barcode.FORMAT_DATA_MATRIX -> TicketPass.Barcode.Format.DATA_MATRIX; Barcode.FORMAT_EAN_13 -> TicketPass.Barcode.Format.EAN13
        Barcode.FORMAT_EAN_8 -> TicketPass.Barcode.Format.EAN8; Barcode.FORMAT_UPC_E -> TicketPass.Barcode.Format.UPCE
        Barcode.FORMAT_CODE_39 -> TicketPass.Barcode.Format.CODE39; Barcode.FORMAT_CODE_93 -> TicketPass.Barcode.Format.CODE93
        Barcode.FORMAT_ITF -> TicketPass.Barcode.Format.ITF; Barcode.FORMAT_CODABAR -> TicketPass.Barcode.Format.CODABAR
        else -> null
    }

    /** Codes, text lines (pieces on one row joined) and the main colour of one page. */
    suspend fun scan(file: File, pdf: Boolean, page: Int): TicketScan = withContext(Dispatchers.Default) {
        val bmp = page(file, pdf, page) ?: throw Unreadable()
        val image = InputImage.fromBitmap(bmp, 0)
        val codes = ArrayList<TicketPass.Barcode>()
        runCatching {
            val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build())
            try {
                val seen = HashSet<String>()
                for (b in Tasks.await(scanner.process(image), 30, TimeUnit.SECONDS)) {
                    val f = format(b.format) ?: continue
                    val text = b.rawValue; val bytes = b.rawBytes
                    // Raw bytes only when the text does not carry them exactly (binary rail ticket codes).
                    val code = when {
                        text != null && (bytes == null || text.toByteArray().contentEquals(bytes)) -> TicketPass.Barcode(f, message = text)
                        bytes != null -> TicketPass.Barcode(f, payload = Base64.encodeToString(bytes, Base64.NO_WRAP))
                        else -> continue
                    }
                    val r = b.boundingBox
                    val withRegion = if (r != null) code.copy(region = TicketPass.Barcode.Region(r.left.toDouble() / bmp.width, r.top.toDouble() / bmp.height,
                        r.width().toDouble() / bmp.width, r.height().toDouble() / bmp.height)) else code
                    if (seen.add(withRegion.dedupeKey)) codes += withRegion
                }
            } finally { scanner.close() }
        }
        // Text embedded in a PDF would be exact, but Android has no PDF text API: OCR for every page.
        val lines = runCatching { rows(ocr(image)) }.getOrDefault(emptyList())
        TicketScan(lines, codes, dominantColor(bmp))
    }

    /** Only a unique recognizable ticket page is chosen automatically (TicketRecognizer.uniqueTicket). */
    suspend fun uniqueTicket(file: File, count: Int): Pair<Int, TicketScan>? {
        if (count <= 1 || count > TicketImportPolicy.MAX_AUTOMATIC_PAGES) return null
        var candidate: Pair<Int, TicketScan>? = null
        for (p in 0 until count) {
            val s = scan(file, true, p)
            val d = TicketTextParser.parse(s.lines, s.barcodes, strictValidity = true)
            if (TicketImportPolicy.isRecognizable(d, s.barcodes)) { if (candidate != null) return null; candidate = p to s }
        }
        return candidate
    }

    private class Piece(val text: String, val box: android.graphics.Rect)

    private fun ocr(image: InputImage): List<Piece> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = Tasks.await(recognizer.process(image), 60, TimeUnit.SECONDS)
            return text.textBlocks.flatMap { b -> b.lines.mapNotNull { l -> l.boundingBox?.takeIf { l.text.isNotBlank() }?.let { Piece(l.text, it) } } }
        } finally { recognizer.close() }
    }

    /** Pieces on the same row become one line ("Von:" + "Wien Hbf"), top to bottom. */
    private fun rows(pieces: List<Piece>): List<String> {
        val rows = ArrayList<MutableList<Piece>>()
        for (p in pieces.sortedBy { it.box.exactCenterY() }) {
            val last = rows.lastOrNull()?.lastOrNull()
            if (last != null && kotlin.math.abs(last.box.exactCenterY() - p.box.exactCenterY()) < minOf(last.box.height(), p.box.height()) * 0.5f) rows.last() += p
            else rows += mutableListOf(p)
        }
        return rows.map { r -> r.sortedBy { it.box.left }.joinToString(" ") { it.text } }
    }

    /** Average of the clearly coloured pixels as RRGGBB; null for black-and-white tickets. */
    fun dominantColor(bmp: Bitmap): String? {
        val side = 32
        val small = Bitmap.createScaledBitmap(bmp, side, side, true)
        var r = 0.0; var g = 0.0; var b = 0.0; var weight = 0.0
        for (y in 0 until side) for (x in 0 until side) {
            val c = small.getPixel(x, y)
            val pr = Color.red(c) / 255.0; val pg = Color.green(c) / 255.0; val pb = Color.blue(c) / 255.0
            val maxC = maxOf(pr, pg, pb); val minC = minOf(pr, pg, pb)
            val s = if (maxC == 0.0) 0.0 else (maxC - minC) / maxC
            if (s <= 0.35 || maxC <= 0.2) continue
            r += pr * s; g += pg * s; b += pb * s; weight += s
        }
        if (weight <= side * side * 0.03) return null
        fun byte(v: Double) = maxOf(0, minOf(255, Math.round(v * 255).toInt()))
        return String.format("%02X%02X%02X", byte(r / weight), byte(g / weight), byte(b / weight))
    }

    /** The code's region of the original page, for codes Publit cannot draw again. */
    fun crop(file: File, pdf: Boolean, page: Int, region: TicketPass.Barcode.Region): Bitmap? {
        val bmp = page(file, pdf, page) ?: return null
        val left = (region.x * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val top = (region.y * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val w = (region.width * bmp.width).toInt().coerceIn(1, bmp.width - left)
        val h = (region.height * bmp.height).toInt().coerceIn(1, bmp.height - top)
        return Bitmap.createBitmap(bmp, left, top, w, h)
    }

    /** Draws a stored code again with ZXing: black on white, every module a whole number of pixels. */
    fun renderCode(code: TicketPass.Barcode, maxW: Int, maxH: Int): Bitmap? {
        val f = when (code.format) {
            TicketPass.Barcode.Format.QR -> com.google.zxing.BarcodeFormat.QR_CODE
            TicketPass.Barcode.Format.AZTEC -> com.google.zxing.BarcodeFormat.AZTEC
            TicketPass.Barcode.Format.PDF417 -> com.google.zxing.BarcodeFormat.PDF_417
            TicketPass.Barcode.Format.CODE128 -> com.google.zxing.BarcodeFormat.CODE_128
            TicketPass.Barcode.Format.DATA_MATRIX -> com.google.zxing.BarcodeFormat.DATA_MATRIX
            else -> return null
        }
        val hints = HashMap<com.google.zxing.EncodeHintType, Any>()
        hints[com.google.zxing.EncodeHintType.MARGIN] = 0
        val content = if (!code.payload.isNullOrEmpty()) {
            hints[com.google.zxing.EncodeHintType.CHARACTER_SET] = "ISO-8859-1"
            String(Base64.decode(code.payload, Base64.DEFAULT), Charsets.ISO_8859_1)
        } else { hints[com.google.zxing.EncodeHintType.CHARACTER_SET] = "UTF-8"; code.message ?: return null }
        if (code.format == TicketPass.Barcode.Format.QR) hints[com.google.zxing.EncodeHintType.ERROR_CORRECTION] = com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M
        if (code.format == TicketPass.Barcode.Format.AZTEC) hints[com.google.zxing.EncodeHintType.ERROR_CORRECTION] = 23
        return runCatching {
            val m = com.google.zxing.MultiFormatWriter().encode(content, f, 0, 0, hints)
            val factor = maxOf(1, minOf(maxW / m.width, maxH / m.height))
            val px = IntArray(m.width * factor * m.height * factor)
            val w = m.width * factor
            for (y in 0 until m.height * factor) for (x in 0 until w) px[y * w + x] = if (m.get(x / factor, y / factor)) Color.BLACK else Color.WHITE
            Bitmap.createBitmap(px, w, m.height * factor, Bitmap.Config.ARGB_8888)
        }.getOrNull()
    }
}

/** Conservative page-recognition rules. Recognition does not verify authenticity or transport rights. */
object TicketImportPolicy {
    const val MAX_AUTOMATIC_PAGES = 12

    fun isRecognizable(d: TicketDraft, barcodes: List<TicketPass.Barcode>): Boolean {
        val from = d.validFrom; val until = d.validUntil
        if (from != null && until != null && until < from) return false
        // A barcode alone may be a website link; an operator alone may be an advertisement.
        if (d.category == null) return false
        return d.operatorName != null || barcodes.isNotEmpty() || (d.origin != null && d.destination != null)
    }

    /** Exact-document duplicate detection, not authentication. Page is part of the identity. */
    fun fingerprint(bytes: ByteArray, page: Int): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) } + ":$page"
}

/** Only explicit user assignments, never inference (TicketField on iOS). */
enum class TicketField {
    TITLE, OPERATOR, ORIGIN, DESTINATION, VIA, PASSENGER, PRICE, REFERENCE, VALID_FROM, VALID_UNTIL, NOTES;

    val title: String get() = L10n.t(when (this) {
        TITLE -> "Title"; OPERATOR -> "Operator"; ORIGIN -> "From"; DESTINATION -> "To"; VIA -> "Via"; PASSENGER -> "Passenger"
        PRICE -> "Price"; REFERENCE -> "Ticket number"; VALID_FROM -> "Valid from"; VALID_UNTIL -> "Valid until"; NOTES -> "Notes"
    })

    /** Null leaves the pass unchanged, e.g. when an assigned date cannot be read. */
    fun apply(text: String, p: TicketPass): TicketPass? {
        val v = text.trim().ifEmpty { return null }
        return when (this) {
            TITLE -> p.copy(title = v); OPERATOR -> p.copy(operatorName = v); ORIGIN -> p.copy(origin = v); DESTINATION -> p.copy(destination = v)
            VIA -> p.copy(via = v); PASSENGER -> p.copy(passenger = v); PRICE -> p.copy(price = v); REFERENCE -> p.copy(reference = v); NOTES -> p.copy(notes = v)
            VALID_FROM -> TicketTextParser.date(v, false)?.let { p.copy(validFrom = it) }
            VALID_UNTIL -> TicketTextParser.date(v, true)?.let { p.copy(validUntil = it) }
        }
    }
}
