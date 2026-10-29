package publit.module

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Android 12+ tombstones are protobuf: the crash report must show the cause, not the memory map. */
class TombstoneReaderTest {
    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) { out.write(b); return }
            out.write(b or 0x80)
        }
    }
    private fun num(field: Int, value: Long) = ByteArrayOutputStream().also { varint(it, (field shl 3).toLong()); varint(it, value) }.toByteArray()
    private fun bytes(field: Int, value: ByteArray) = ByteArrayOutputStream().also {
        varint(it, ((field shl 3) or 2).toLong()); varint(it, value.size.toLong()); it.write(value)
    }.toByteArray()
    private fun str(field: Int, value: String) = bytes(field, value.toByteArray())
    private fun cat(vararg parts: ByteArray) = ByteArrayOutputStream().also { o -> parts.forEach { o.write(it) } }.toByteArray()

    @Test fun readsSignalAbortMessageAndCrashingThread() {
        val frame = cat(num(1, 0x5f0a4), str(4, "abort"), num(5, 164), str(6, "/apex/com.android.runtime/lib64/bionic/libc.so"))
        val swiftFrame = cat(num(1, 0x1234), str(4, "\$s6Publit3MapV4bodyQrvg"), num(5, 40), str(6, "/data/app/x/lib/arm64/libPublit.so"))
        val thread = cat(num(1, 4242), str(2, "main"), bytes(4, frame), bytes(4, swiftFrame))
        val signal = cat(num(1, 6), str(2, "SIGABRT"), num(3, -6), str(4, "SI_TKILL"))
        val mapping = cat(num(1, 0x1000), num(2, 0x2000), str(7, "/system/lib64/libEGL.so"))
        val log = cat(str(1, "main"), bytes(2, cat(str(1, "10-29 17:26:08.100"), num(4, 6), str(5, "libc"), str(6, "Fatal error: boom"))))
        val tombstone = cat(num(6, 4242), bytes(10, signal), str(14, "Fatal error: Unexpectedly found nil"),
            bytes(16, cat(num(1, 4242), bytes(2, thread))), bytes(17, mapping), bytes(18, log), num(22, 16384))

        val text = TombstoneReader.summarize(tombstone)
        assertNotNull(text)
        assertTrue(text!!, text.contains("Signal 6 (SIGABRT), code -6 (SI_TKILL)"))
        assertTrue(text, text.contains("Abort message: Fatal error: Unexpectedly found nil"))
        assertTrue(text, text.contains("Crashing thread \"main\" (tid 4242)"))
        assertTrue(text, text.contains("#01 pc 0000000000001234  /data/app/x/lib/arm64/libPublit.so"))
        assertTrue(text, text.contains("F libc: Fatal error: boom"))
        assertFalse("memory maps are left out", text.contains("libEGL"))
    }

    @Test fun textTombstonesAreLeftToTheTextSummary() {
        assertNull(TombstoneReader.summarize("*** *** *** signal 11 (SIGSEGV)".toByteArray()))
    }
}
