// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.tickets

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader

/**
 * Build without Google services (F-Droid): codes are read with ZXing (Apache-2.0), on the device.
 * There is no text recognition and no document scanner; the ticket fields are filled in by hand.
 */
object TicketVision {
    class Code(val format: TicketPass.Barcode.Format, val text: String?, val bytes: ByteArray?, val box: Rect?)

    const val readsText = false
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
        val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bmp.width, bmp.height, px)))
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        val results = try { GenericMultipleBarcodeReader(MultiFormatReader()).decodeMultiple(image, hints) } catch (e: NotFoundException) { return emptyList() }
        return results.mapNotNull { r ->
            val f = format(r.barcodeFormat) ?: return@mapNotNull null
            // Binary rail ticket codes: ZXing keeps the exact bytes in its byte segments.
            @Suppress("UNCHECKED_CAST")
            val segments = r.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<ByteArray>
            val bytes = segments?.takeIf { it.isNotEmpty() }?.reduce { a, b -> a + b }
            val pts = r.resultPoints?.filterNotNull().orEmpty()
            val box = if (pts.isEmpty()) null else Rect(pts.minOf { it.x }.toInt(), pts.minOf { it.y }.toInt(), pts.maxOf { it.x }.toInt(), pts.maxOf { it.y }.toInt())
            Code(f, r.text, bytes, box?.takeIf { it.width() > 0 && it.height() > 0 })
        }
    }

    @Suppress("UNUSED_PARAMETER") fun lines(bmp: Bitmap): List<Pair<String, Rect>> = emptyList()

    @Suppress("UNUSED_PARAMETER")
    fun startPaperScan(activity: Activity, onIntent: (IntentSender) -> Unit, onError: (String?) -> Unit) {}

    @Suppress("UNUSED_PARAMETER") fun scannedPdf(data: Intent?): Uri? = null
}
