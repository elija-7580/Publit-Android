// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.tickets

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/** Reading codes and text on the device with Google ML Kit (Play build). Nothing is uploaded. */
object TicketVision {
    class Code(val format: TicketPass.Barcode.Format, val text: String?, val bytes: ByteArray?, val box: Rect?)

    /** ML Kit reads printed text; the build without Google services cannot. */
    const val readsText = true

    private fun format(f: Int): TicketPass.Barcode.Format? = when (f) {
        Barcode.FORMAT_QR_CODE -> TicketPass.Barcode.Format.QR; Barcode.FORMAT_AZTEC -> TicketPass.Barcode.Format.AZTEC
        Barcode.FORMAT_PDF417 -> TicketPass.Barcode.Format.PDF417; Barcode.FORMAT_CODE_128 -> TicketPass.Barcode.Format.CODE128
        Barcode.FORMAT_DATA_MATRIX -> TicketPass.Barcode.Format.DATA_MATRIX; Barcode.FORMAT_EAN_13 -> TicketPass.Barcode.Format.EAN13
        Barcode.FORMAT_EAN_8 -> TicketPass.Barcode.Format.EAN8; Barcode.FORMAT_UPC_E -> TicketPass.Barcode.Format.UPCE
        Barcode.FORMAT_CODE_39 -> TicketPass.Barcode.Format.CODE39; Barcode.FORMAT_CODE_93 -> TicketPass.Barcode.Format.CODE93
        Barcode.FORMAT_ITF -> TicketPass.Barcode.Format.ITF; Barcode.FORMAT_CODABAR -> TicketPass.Barcode.Format.CODABAR
        else -> null
    }

    fun codes(bmp: Bitmap): List<Code> {
        val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build())
        try {
            return Tasks.await(scanner.process(InputImage.fromBitmap(bmp, 0)), 30, TimeUnit.SECONDS)
                .mapNotNull { b -> format(b.format)?.let { Code(it, b.rawValue, b.rawBytes, b.boundingBox) } }
        } finally { scanner.close() }
    }

    /** Text lines with their position on the page. */
    fun lines(bmp: Bitmap): List<Pair<String, Rect>> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 60, TimeUnit.SECONDS)
            return text.textBlocks.flatMap { b -> b.lines.mapNotNull { l -> l.boundingBox?.takeIf { l.text.isNotBlank() }?.let { l.text to it } } }
        } finally { recognizer.close() }
    }

    /** Google's document scanner for paper tickets. */
    const val canScanPaper = true

    fun startPaperScan(activity: Activity, onIntent: (IntentSender) -> Unit, onError: (String?) -> Unit) {
        val opts = GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(false).setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_BASE).build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(activity)
            .addOnSuccessListener { onIntent(it) }
            .addOnFailureListener { e -> onError(e.localizedMessage) }
    }

    fun scannedPdf(data: Intent?): Uri? = GmsDocumentScanningResult.fromActivityResultIntent(data)?.pdf?.uri
}
