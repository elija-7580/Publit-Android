// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.elija7580.publit.tickets.TicketPass
import io.github.elija7580.publit.tickets.TicketRecognizer
import io.github.elija7580.publit.tickets.TicketTextParser
import io.github.elija7580.publit.tickets.TicketVision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Ticket recognition on a real device or emulator, in the flavor under test: play (ML Kit) or foss
 * (ZXing + Tesseract). Draws a ticket with text, a QR code and an Aztec code with binary content
 * (like UIC rail tickets) and reads it back as image and as PDF, the way an import does.
 */
@RunWith(AndroidJUnit4::class)
class TicketScanDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val qrText = "PUBLIT-TEST-4711"
    private val aztecBytes = ByteArray(48) { (it * 37 + 11).toByte() } + byteArrayOf(0, -1, -128, 127)

    private fun drawTicket(c: Canvas, w: Int, scale: Float) {
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD); textSize = 44f * scale }
        val lines = listOf("Deutschlandticket", "Name: Max Mustermann", "Gültig bis 30.11.2026", "Klasse: 2")
        lines.forEachIndexed { i, s -> c.drawText(s, 60f * scale, (110f + i * 80f) * scale, p) }
        val qr = TicketRecognizer.renderCode(TicketPass.Barcode(TicketPass.Barcode.Format.QR, message = qrText), (300 * scale).toInt(), (300 * scale).toInt())!!
        val aztec = TicketRecognizer.renderCode(TicketPass.Barcode(TicketPass.Barcode.Format.AZTEC, payload = Base64.encodeToString(aztecBytes, Base64.NO_WRAP)), (300 * scale).toInt(), (300 * scale).toInt())!!
        val top = 480f * scale
        c.drawBitmap(qr, 80f * scale, top, null)
        c.drawBitmap(aztec, w - aztec.width - 80f * scale, top, null)
    }

    private fun check(file: File, pdf: Boolean) = runBlocking {
        val scan = TicketRecognizer.scan(file, pdf, 0)
        val text = scan.lines.joinToString("\n")
        Log.i("TicketScanDeviceTest", "flavor=${BuildConfig.FLAVOR} pdf=$pdf lines=$text codes=${scan.barcodes}")
        val qr = scan.barcodes.firstOrNull { it.format == TicketPass.Barcode.Format.QR }
        assertEquals("QR code (${BuildConfig.FLAVOR}, pdf=$pdf): ${scan.barcodes}", qrText, qr?.message)
        val az = scan.barcodes.firstOrNull { it.format == TicketPass.Barcode.Format.AZTEC }
        assertTrue("Aztec code with binary content (${BuildConfig.FLAVOR}, pdf=$pdf): ${scan.barcodes}",
            az?.payload != null && Base64.decode(az.payload, Base64.DEFAULT).contentEquals(aztecBytes))
        val low = text.lowercase()
        assertTrue("OCR text (${BuildConfig.FLAVOR}, pdf=$pdf): $text", "deutschlandticket" in low && "mustermann" in low)
        val draft = TicketTextParser.parse(scan.lines, scan.barcodes)
        Log.i("TicketScanDeviceTest", "draft=$draft")
        assertTrue("Ticket details from the text: $draft", draft.validUntil != null || draft.title != null)
    }

    @Test fun imageTicket() {
        TicketVision.install(ctx)
        val bmp = Bitmap.createBitmap(1240, 1000, Bitmap.Config.ARGB_8888)
        drawTicket(Canvas(bmp), bmp.width, 1f)
        val f = File(ctx.cacheDir, "test-ticket.png").apply { outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        check(f, pdf = false)
    }

    @Test fun pdfTicket() {
        TicketVision.install(ctx)
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create()) // A4 in points
        drawTicket(page.canvas, 595, 0.45f)
        doc.finishPage(page)
        val f = File(ctx.cacheDir, "test-ticket.pdf").apply { outputStream().use { doc.writeTo(it) } }
        doc.close()
        check(f, pdf = true)
    }
}
