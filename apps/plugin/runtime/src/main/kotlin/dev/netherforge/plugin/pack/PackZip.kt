package dev.netherforge.plugin.pack

import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Zips a resource pack so the same files always make the same bytes.
 *
 * The SHA-1 is what tells a client it already has a pack, so anything in the
 * zip that isn't content would cost every player a download on every restart.
 * Entries go in path order, every one carries the same date, and the date is
 * set as a local date-time ([ZipEntry.setTimeLocal]): a zip stores DOS local
 * time, and going through epoch milliseconds would make the bytes depend on
 * the server's time zone. No extra fields, no comment, one compression level.
 */
object PackZip {
    /** 2000-01-01T00:00, comfortably inside the DOS date range in every time zone. */
    private val FIXED: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0)

    fun zip(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setLevel(Deflater.DEFAULT_COMPRESSION)
            for (path in files.keys.sorted()) {
                val entry = ZipEntry(path)
                entry.setTimeLocal(FIXED)
                zip.putNextEntry(entry)
                zip.write(files.getValue(path))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
