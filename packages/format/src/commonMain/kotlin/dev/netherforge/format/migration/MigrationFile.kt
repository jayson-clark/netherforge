package dev.netherforge.format.migration

/**
 * A migration: `migrations/NNN_name.sql`, one step of a package's database
 * schema. [number] is the `NNN` (null when the file isn't named that way).
 * [dialects] are the servers it has a version of its own for
 * (`migrations/postgres/NNN_name.sql`, [MigrationKind.DIALECTS]), and [base]
 * whether the file itself (SQLite's, which every package database starts on)
 * is there. Format checks the names and the numbering; the runtime reads and
 * applies the SQL.
 */
data class MigrationFile(val id: String, val number: Int?, val dialects: Set<String> = emptySet(), val base: Boolean = true) {
    companion object {
        /** `NNN_name`: three digits, then the name. */
        val ID = Regex("^(\\d{3})_[a-z0-9_]+$")

        /** The servers a migration may have a version of its own for, by the folder it's in: `migrations/postgres/`. */
        val DIALECTS: Set<String> = setOf("mysql", "postgres")

        fun of(id: String) = MigrationFile(id, ID.matchEntire(id)?.groupValues?.get(1)?.toInt())
    }
}
