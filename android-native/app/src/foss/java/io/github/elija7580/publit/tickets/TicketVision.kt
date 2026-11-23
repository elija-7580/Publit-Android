// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.tickets

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/**
 * Build without Google services (F-Droid), everything on the device: ZXing (Apache-2.0) reads codes,
 * Tesseract (Apache-2.0, models from tessdata_fast in assets/tessdata) reads text. No document scanner.
 */
object TicketVision {
    class Code(val format: TicketPass.Barcode.Format, val text: String?, val bytes: ByteArray?, val box: Rect?)

    const val canScanPaper = false

    private fun format(f: BarcodeFormat): TicketPass.Barcode.Format? = when (f) {
        BarcodeFormat.QR_CODE -> TicketPass.Barcode.Format.QR; BarcodeFormat.AZTEC -> TicketPass.Barcode.Format.AZTEC
        BarcodeFormat.PDF_417 -> TicketPass.Barcode.Format.PDF417; BarcodeFormat.CODE_128 -> TicketPass.Barcode.Format.CODE128
        BarcodeFormat.DATA_MATRIX -> TicketPass.Barcode.Format.DATA_MATRIX; BarcodeFormat.EAN_13 -> TicketPass.Barcode.Format.EAN13
        BarcodeFormat.EAN_8 -> TicketPass.Barcode.Format.EAN8; BarcodeFormat.UPC_E -> TicketPass.Barcode.Format.UPCE
        BarcodeFormat.CODE_39 -> TicketPass.Barcode.Format.CODE39; BarcodeFormat.CODE_93 -> TicketPass.Barcode.Format.CODE93
        BarcodeFormat.ITF -> TicketPass.Barcode.Format.ITF; BarcodeFormat.CODABAR -> TicketPass.Barcode.Format.CODABAR
        else -> null
    }

    fun codes(bmp: Bitmap): List<Code> {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return CodeFinder.find(px, bmp.width, bmp.height).mapNotNull { r ->
            val f = format(r.barcodeFormat) ?: return@mapNotNull null
            // Binary rail ticket codes: ZXing keeps the exact bytes in its byte segments.
            val bytes = CodeFinder.bytes(r)
            val pts = r.resultPoints?.filterNotNull().orEmpty()
            val box = if (pts.isEmpty()) null else Rect(pts.minOf { it.x }.toInt(), pts.minOf { it.y }.toInt(), pts.maxOf { it.x }.toInt(), pts.maxOf { it.y }.toInt())
            Code(f, r.text, bytes, box?.takeIf { it.width() > 0 && it.height() > 0 })
        }
    }

    private const val LANGUAGES = "deu+eng+fra+ita+spa"
    @Volatile private var context: Context? = null
    private val lock = Any()

    fun install(context: Context) { this.context = context.applicationContext }

    /** Copies the models out of the APK once (Tesseract reads files, not assets); null when that fails. */
    private fun dataPath(): String? = synchronized(lock) {
        val ctx = context ?: return null
        val root = File(ctx.noBackupFilesDir, "ocr")
        val dir = File(root, "tessdata")
        runCatching {
            val names = ctx.assets.list("tessdata").orEmpty().filter { it.endsWith(".traineddata") }
            for (name in names) {
                val target = File(dir, name)
                if (target.isFile && target.length() > 0) continue
                dir.mkdirs()
                val tmp = File(dir, "$name.part")
                ctx.assets.open("tessdata/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(target)
            }
            root.path
        }.getOrNull()
    }

    /** Text lines with their position on the page. */
    fun lines(bmp: Bitmap): List<Pair<String, Rect>> {
        val path = dataPath() ?: return emptyList()
        val api = TessBaseAPI()
        try {
            if (!api.init(path, LANGUAGES, TessBaseAPI.OEM_LSTM_ONLY)) return emptyList()
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            api.setImage(bmp)
            api.getUTF8Text() ?: return emptyList()
            val it = api.getResultIterator() ?: return emptyList()
            val out = ArrayList<Pair<String, Rect>>()
            try {
                val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
                it.begin()
                do {
                    val text = it.getUTF8Text(level)?.trim().orEmpty()
                    if (text.isNotEmpty() && it.confidence(level) >= 40f) out += text to it.getBoundingRect(level)
                } while (it.next(level))
            } finally { it.delete() }
            return out
        } finally { api.recycle() }
    }

    @Suppress("UNUSED_PARAMETER")
    fun startPaperScan(activity: Activity, onIntent: (IntentSender) -> Unit, onError: (String?) -> Unit) {}

    @Suppress("UNUSED_PARAMETER") fun scannedPdf(data: Intent?): Uri? = null
}
