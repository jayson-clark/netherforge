package dev.netherforge.format.migration

/**
 * A migration: `migrations/NNN_name.sql`, one step of a package's database
 * schema. [number] is the `NNN` (null when the file isn't named that way).
 * Format checks the names and the numbering; the runtime reads and applies
 * the SQL.
 */
data class MigrationFile(val id: String, val number: Int?) {
    companion object {
        /** `NNN_name`: three digits, then the name. */
        val ID = Regex("^(\\d{3})_[a-z0-9_]+$")

        fun of(id: String) = MigrationFile(id, ID.matchEntire(id)?.groupValues?.get(1)?.toInt())
    }
}
