// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit.tickets

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.Reader
import com.google.zxing.Result
import com.google.zxing.ResultMetadataType
import com.google.zxing.ResultPoint
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.aztec.AztecReader
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.datamatrix.DataMatrixReader
import com.google.zxing.multi.GenericMultipleBarcodeReader
import java.util.Base64

/**
 * All codes on a ticket page with ZXing, plain JVM (used by the build without Google services).
 * ZXing's Aztec and Data Matrix detectors start at the centre of the image, so a rail ticket's Aztec
 * code beside a QR code or under text is missed on the whole page: those two are looked for again in
 * overlapping square windows (a quarter step, so a code up to three quarters of a window always fits in one).
 */
object CodeFinder {
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)

    fun find(argb: IntArray, width: Int, height: Int): List<Result> {
        val source = RGBLuminanceSource(width, height, argb)
        val found = LinkedHashMap<String, Result>()
        fun add(r: Result) { found.putIfAbsent(key(r), r) }
        runCatching { GenericMultipleBarcodeReader(MultiFormatReader()).decodeMultiple(BinaryBitmap(HybridBinarizer(source)), hints) }
            .getOrNull()?.forEach(::add)
        val side0 = minOf(width, height)
        for (frac in doubleArrayOf(0.7, 0.5, 0.34)) {
            val side = (side0 * frac).toInt().coerceAtLeast(64)
            if (side >= side0 && found.isNotEmpty()) continue
            for (top in steps(height, side)) for (left in steps(width, side)) {
                val crop = source.crop(left, top, minOf(side, width - left), minOf(side, height - top))
                for (reader in listOf<Reader>(AztecReader(), DataMatrixReader())) decode(reader, crop, left, top)?.let(::add)
            }
        }
        return found.values.toList()
    }

    private fun steps(total: Int, side: Int): List<Int> {
        if (side >= total) return listOf(0)
        val out = ArrayList<Int>()
        var p = 0
        while (p + side < total) { out += p; p += side / 4 }
        out += total - side
        return out.distinct()
    }

    private fun decode(reader: Reader, crop: LuminanceSource, dx: Int, dy: Int): Result? = runCatching {
        val r = reader.decode(BinaryBitmap(HybridBinarizer(crop)), hints)
        val pts = r.resultPoints?.filterNotNull()?.map { ResultPoint(it.x + dx, it.y + dy) }?.toTypedArray()
        Result(r.text, r.rawBytes, pts, r.barcodeFormat).also { it.putAllMetadata(r.resultMetadata) }
    }.getOrNull()

    /**
     * Exact bytes of a code: its byte segments joined (binary UIC 918.3 rail tickets), else null.
     * ZXing's Aztec decoder keeps no byte segments; it decodes bytes as ISO-8859-1, one character per
     * byte, so non-ASCII text in that range is turned back into the bytes (drawn again the same way).
     */
    fun bytes(r: Result): ByteArray? {
        @Suppress("UNCHECKED_CAST")
        val segments = r.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<ByteArray>
        segments?.takeIf { it.isNotEmpty() }?.let { return it.reduce { a, b -> a + b } }
        val text = r.text ?: return null
        if (r.barcodeFormat == BarcodeFormat.AZTEC && text.any { it.code >= 0x80 } && text.all { it.code <= 0xFF })
            return text.toByteArray(Charsets.ISO_8859_1)
        return null
    }

    private fun key(r: Result) = r.barcodeFormat.name + "|" + r.text + "|" + (bytes(r)?.let { Base64.getEncoder().encodeToString(it) } ?: "")
}
