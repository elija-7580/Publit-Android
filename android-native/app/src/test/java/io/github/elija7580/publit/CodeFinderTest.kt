// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import io.github.elija7580.publit.tickets.CodeFinder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Codes away from the page centre, as on real tickets (TicketScanDeviceTest checks the same on a device). */
class CodeFinderTest {
    private val w = 1240; private val h = 1000
    private val binary = ByteArray(48) { (it * 37 + 11).toByte() } + byteArrayOf(0, -1, -128, 127)

    private fun draw(px: IntArray, format: BarcodeFormat, content: String, charset: String, x0: Int, y0: Int, size: Int) {
        val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to charset)
        val m = MultiFormatWriter().encode(content, format, 0, 0, hints)
        val f = size / m.width
        for (y in 0 until m.height * f) for (x in 0 until m.width * f) if (m.get(x / f, y / f)) px[(y0 + y) * w + x0 + x] = 0xFF000000.toInt()
    }

    private fun page() = IntArray(w * h) { 0xFFFFFFFF.toInt() }

    @Test fun qrAndBinaryAztecBesideEachOther() {
        val px = page()
        draw(px, BarcodeFormat.QR_CODE, "PUBLIT-TEST-4711", "UTF-8", 80, 480, 300)
        draw(px, BarcodeFormat.AZTEC, String(binary, Charsets.ISO_8859_1), "ISO-8859-1", 860, 480, 300)
        val codes = CodeFinder.find(px, w, h)
        assertEquals("PUBLIT-TEST-4711", codes.single { it.barcodeFormat == BarcodeFormat.QR_CODE }.text)
        assertArrayEquals(binary, CodeFinder.bytes(codes.single { it.barcodeFormat == BarcodeFormat.AZTEC }))
    }

    @Test fun aztecInACorner() {
        val px = page()
        draw(px, BarcodeFormat.AZTEC, "TICKET-0815", "UTF-8", 40, 40, 260)
        assertEquals(listOf("TICKET-0815"), CodeFinder.find(px, w, h).map { it.text })
    }

    @Test fun dataMatrixOffCentre() {
        val px = page()
        draw(px, BarcodeFormat.DATA_MATRIX, "DM-12345", "UTF-8", 900, 650, 240)
        assertEquals(listOf("DM-12345"), CodeFinder.find(px, w, h).map { it.text })
    }

    @Test fun latinTextStaysText() {
        val px = page()
        draw(px, BarcodeFormat.QR_CODE, "PUBLIT", "UTF-8", 470, 350, 300)
        assertEquals(null, CodeFinder.bytes(CodeFinder.find(px, w, h).single()))
    }

    @Test fun emptyPage() { assertEquals(emptyList<Any>(), CodeFinder.find(page(), w, h)) }
}
