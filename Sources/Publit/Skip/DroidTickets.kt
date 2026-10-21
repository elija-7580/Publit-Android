// Publit for Android: reading and drawing tickets on the device (nothing is uploaded).
// Counterpart of Vision, PDFKit and Core Image on iOS: PdfRenderer renders pages, ML Kit reads
// text and codes, ZXing draws codes again. Called from background threads; results are JSON.
package publit.module

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object DroidTickets {
    fun pageCount(path: String): Int = try {
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { it.pageCount }
        }
    } catch (e: Exception) { 0 }

    /** A PDF page on white, at most `maxPixels` on its long side (6× its size at most). */
    fun renderPage(path: String, page: Int, maxPixels: Int): Bitmap? = try {
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                if (page < 0 || page >= r.pageCount) return null
                r.openPage(page).use { p ->
                    val scale = kotlin.math.min(maxPixels.toFloat() / kotlin.math.max(p.width, p.height), 6f)
                    val w = kotlin.math.max(1, (p.width * scale).toInt())
                    val h = kotlin.math.max(1, (p.height * scale).toInt())
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }
        }
    } catch (e: Exception) { null }

    /** An image file upright (EXIF orientation applied), at most `maxPixels` on its long side. */
    fun loadImage(path: String, maxPixels: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (kotlin.math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPixels) sample *= 2
        var bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val rotation = try {
            when (androidx.exifinterface.media.ExifInterface(path).getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
            }
        } catch (e: Exception) { 0f }
        if (rotation != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
        val long = kotlin.math.max(bmp.width, bmp.height)
        if (long > maxPixels) {
            val f = maxPixels.toFloat() / long
            bmp = Bitmap.createScaledBitmap(bmp, kotlin.math.max(1, (bmp.width * f).toInt()), kotlin.math.max(1, (bmp.height * f).toInt()), true)
        }
        bmp
    } catch (e: Exception) { null }

    /** maxPixels < 0: the size used for recognition (2600 for PDF pages, 3200 for images). */
    fun page(path: String, pdf: Boolean, page: Int, maxPixels: Int = -1): Bitmap? =
        if (pdf) renderPage(path, page, if (maxPixels < 0) 2600 else maxPixels)
        else loadImage(path, if (maxPixels < 0) 3200 else maxPixels)

    fun save(bmp: Bitmap, out: String): Boolean = try {
        File(out).parentFile?.mkdirs()
        File(out).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    } catch (e: Exception) { false }

    private fun format(f: Int): String? = when (f) {
        Barcode.FORMAT_QR_CODE -> "qr"
        Barcode.FORMAT_AZTEC -> "aztec"
        Barcode.FORMAT_PDF417 -> "pdf417"
        Barcode.FORMAT_CODE_128 -> "code128"
        Barcode.FORMAT_DATA_MATRIX -> "dataMatrix"
        Barcode.FORMAT_EAN_13 -> "ean13"
        Barcode.FORMAT_EAN_8 -> "ean8"
        Barcode.FORMAT_UPC_E -> "upce"
        Barcode.FORMAT_CODE_39 -> "code39"
        Barcode.FORMAT_CODE_93 -> "code93"
        Barcode.FORMAT_ITF -> "itf"
        Barcode.FORMAT_CODABAR -> "codabar"
        else -> null
    }

    private fun rect(json: JSONObject, r: android.graphics.Rect?, w: Int, h: Int): JSONObject {
        if (r == null) return json
        return json.put("x", r.left.toDouble() / w).put("y", r.top.toDouble() / h)
            .put("w", r.width().toDouble() / w).put("h", r.height().toDouble() / h)
    }

    /** Codes, text lines (pieces on one row joined) and the main colour of one page. */
    fun scan(path: String, pdf: Boolean, page: Int, recognizeText: Boolean): String {
        val bmp = page(path, pdf, page) ?: return ""
        val image = InputImage.fromBitmap(bmp, 0)
        val out = JSONObject()
        val codes = JSONArray()
        try {
            val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build())
            val found = Tasks.await(scanner.process(image), 30, TimeUnit.SECONDS)
            val seen = HashSet<String>()
            for (b in found) {
                val f = format(b.format) ?: continue
                val text = b.rawValue
                val bytes = b.rawBytes
                val key = f + ":" + (text ?: "") + ":" + (bytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: "")
                if (!seen.add(key)) continue
                val o = JSONObject().put("format", f)
                // Raw bytes only when the text does not carry them exactly (binary rail ticket codes).
                if (text != null && (bytes == null || text.toByteArray(Charsets.UTF_8).contentEquals(bytes))) o.put("text", text)
                else if (bytes != null) o.put("bytes", Base64.encodeToString(bytes, Base64.NO_WRAP))
                else if (text != null) o.put("text", text)
                else continue
                codes.put(rect(o, b.boundingBox, bmp.width, bmp.height))
            }
            scanner.close()
        } catch (e: Exception) { out.put("codeError", e.toString()) }
        out.put("codes", codes)
        if (recognizeText) {
            try {
                val lines = JSONArray()
                for (row in rows(ocr(image))) lines.put(row)
                out.put("lines", lines)
            } catch (e: Exception) { out.put("error", e.toString()) }
        }
        dominantColor(bmp)?.let { out.put("color", it) }
        out.put("width", bmp.width).put("height", bmp.height)
        return out.toString()
    }

    private class Piece(val text: String, val box: android.graphics.Rect)

    private fun ocr(image: InputImage): List<Piece> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = Tasks.await(recognizer.process(image), 60, TimeUnit.SECONDS)
            val pieces = mutableListOf<Piece>()
            for (block in text.textBlocks) for (line in block.lines) {
                val box = line.boundingBox ?: continue
                if (line.text.isNotBlank()) pieces.add(Piece(line.text, box))
            }
            return pieces
        } finally { recognizer.close() }
    }

    /** Pieces on the same row become one line ("Von:" + "Wien Hbf"), top to bottom. */
    private fun rows(pieces: List<Piece>): List<String> {
        val sorted = pieces.sortedBy { it.box.exactCenterY() }
        val rows = mutableListOf<MutableList<Piece>>()
        for (p in sorted) {
            val last = rows.lastOrNull()?.lastOrNull()
            if (last != null && kotlin.math.abs(last.box.exactCenterY() - p.box.exactCenterY()) < kotlin.math.min(last.box.height(), p.box.height()) * 0.5f) {
                rows.last().add(p)
            } else rows.add(mutableListOf(p))
        }
        return rows.map { row -> row.sortedBy { it.box.left }.joinToString(" ") { it.text } }
    }

    /** Individually selectable text regions for the review screen. */
    fun pieces(path: String, pdf: Boolean, page: Int): String {
        val bmp = page(path, pdf, page) ?: return ""
        val out = JSONArray()
        try {
            for (p in ocr(InputImage.fromBitmap(bmp, 0))) {
                out.put(rect(JSONObject().put("text", p.text), p.box, bmp.width, bmp.height))
            }
        } catch (e: Exception) { return "" }
        return out.toString()
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
        fun byte(v: Double) = kotlin.math.max(0, kotlin.math.min(255, Math.round(v * 255).toInt()))
        return String.format("%02X%02X%02X", byte(r / weight), byte(g / weight), byte(b / weight))
    }

    /** The code's region of the original page (normalized, top-left origin), saved as PNG. */
    fun crop(path: String, pdf: Boolean, page: Int, x: Double, y: Double, w: Double, h: Double, out: String): Boolean {
        val bmp = page(path, pdf, page) ?: return false
        val left = (x * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val top = (y * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val cw = (w * bmp.width).toInt().coerceIn(1, bmp.width - left)
        val ch = (h * bmp.height).toInt().coerceIn(1, bmp.height - top)
        return save(Bitmap.createBitmap(bmp, left, top, cw, ch), out)
    }

    /**
     * Draws a stored code again with ZXing: black on white, every module a whole number of pixels,
     * as large as fits maxW × maxH. Text codes as UTF-8, binary codes byte for byte (ISO-8859-1).
     */
    fun renderCode(format: String, text: String?, bytesB64: String?, maxW: Int, maxH: Int, out: String): Boolean {
        val f = when (format) {
            "qr" -> com.google.zxing.BarcodeFormat.QR_CODE
            "aztec" -> com.google.zxing.BarcodeFormat.AZTEC
            "pdf417" -> com.google.zxing.BarcodeFormat.PDF_417
            "code128" -> com.google.zxing.BarcodeFormat.CODE_128
            "dataMatrix" -> com.google.zxing.BarcodeFormat.DATA_MATRIX
            else -> return false
        }
        val hints = HashMap<com.google.zxing.EncodeHintType, Any>()
        hints[com.google.zxing.EncodeHintType.MARGIN] = 0
        val content: String
        if (bytesB64 != null && bytesB64.isNotEmpty()) {
            content = String(Base64.decode(bytesB64, Base64.DEFAULT), Charsets.ISO_8859_1)
            hints[com.google.zxing.EncodeHintType.CHARACTER_SET] = "ISO-8859-1"
        } else {
            content = text ?: return false
            hints[com.google.zxing.EncodeHintType.CHARACTER_SET] = "UTF-8"
        }
        when (format) {
            "qr" -> hints[com.google.zxing.EncodeHintType.ERROR_CORRECTION] = com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M
            "aztec" -> hints[com.google.zxing.EncodeHintType.ERROR_CORRECTION] = 23
        }
        return try {
            val m = com.google.zxing.MultiFormatWriter().encode(content, f, 0, 0, hints)
            val factor = kotlin.math.max(1, kotlin.math.min(maxW / m.width, maxH / m.height))
            val bmp = Bitmap.createBitmap(m.width * factor, m.height * factor, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            val paint = android.graphics.Paint().apply { color = Color.BLACK; isAntiAlias = false }
            for (yy in 0 until m.height) for (xx in 0 until m.width) {
                if (m.get(xx, yy)) c.drawRect((xx * factor).toFloat(), (yy * factor).toFloat(), ((xx + 1) * factor).toFloat(), ((yy + 1) * factor).toFloat(), paint)
            }
            save(bmp, out)
        } catch (e: Exception) { false }
    }

    fun canScanDocuments(): Boolean = try {
        com.google.android.gms.common.GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(Droid.context()) ==
            com.google.android.gms.common.ConnectionResult.SUCCESS
    } catch (e: Throwable) { false }
}
