package dev.netherforge.proxy

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** The hub's audit log: one JSON line a change, appended to [file]. */
class AuditLog(private val file: Path) {
    @Synchronized
    fun append(line: String) {
        try {
            Files.createDirectories(file.toAbsolutePath().parent)
            Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (_: Exception) {
            // The log is a record, not a gate: what it couldn't write doesn't stop the change.
        }
    }
}
