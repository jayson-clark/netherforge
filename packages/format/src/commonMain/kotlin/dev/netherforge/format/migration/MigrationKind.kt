package dev.netherforge.format.migration

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.game.GameData
import dev.netherforge.format.project.Contents
import dev.netherforge.format.project.FilesKind
import dev.netherforge.format.project.KindContext
import dev.netherforge.format.project.Layout
import dev.netherforge.format.project.ResourceContext

/**
 * `migrations/NNN_name.sql`: one step of a package's own database
 * (`nf.db()`), applied in number order when the project loads and recorded in
 * the database, so each runs once. One file each, nothing beside it. The
 * numbers run 001, 002, 003 with none skipped or repeated, so the order is
 * never a guess; format checks that, and the runtime applies the SQL.
 */
object MigrationKind : FilesKind<MigrationFile>("migration", "migrations", Layout.SingleFile(".sql"), Contents.SQL) {
    override val sampleId get() = "001_sample"

    // A package's database is its own; no one else sees its schema.
    override val exportable get() = false

    override fun template(id: String, game: GameData?) = mapOf(
        pathOf(id) to "-- Runs once, in order, on the package's own database (nf.db()).\n"
    )

    override fun of(id: String, files: List<String>) = MigrationFile.of(id)

    override fun validate(value: MigrationFile, ctx: ResourceContext) {
        if (value.number == null) {
            ctx.sink.report(ProblemCodes.MIGRATION_NAME, "\"${value.id}.sql\" isn't named NNN_name.sql, like 001_init.sql")
        }
    }

    /** A number used twice, or one that doesn't follow the one before it. */
    override fun crossCheck(value: MigrationFile, ctx: KindContext) {
        val number = value.number ?: return
        val numbered = ctx.models(MigrationKind).values.filter { it.number != null }
            .sortedWith(compareBy({ it.number }, { it.id }))
        val same = numbered.filter { it.number == number }
        if (same.size > 1) {
            if (same.first().id != value.id) {
                ctx.sink.report(
                    ProblemCodes.MIGRATION_DUPLICATE,
                    "Migration ${pad(number)} is also ${ctx.placeOf(MigrationKind, same.first().id).path}; the numbers must be unique"
                )
            }
            return
        }
        val before = numbered.lastOrNull { it.number!! < number }?.number ?: 0
        if (before != number - 1) {
            ctx.sink.report(
                ProblemCodes.MIGRATION_GAP,
                "Migration ${pad(number)} follows ${pad(before)}: the numbers run ${pad(1)}, ${pad(2)}, ${pad(3)} with none skipped"
            )
        }
    }

    private fun pad(number: Int) = number.toString().padStart(3, '0')
}
