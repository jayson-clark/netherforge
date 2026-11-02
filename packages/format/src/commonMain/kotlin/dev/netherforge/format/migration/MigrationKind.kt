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
 * the database, so each runs once. The numbers run 001, 002, 003 with none
 * skipped or repeated, so the order is never a guess; format checks that, and
 * the runtime applies the SQL.
 *
 * The file is SQLite's. When the server's owner puts the package's database on
 * a MySQL or PostgreSQL connection, the runtime runs the same step's version
 * for that server instead, `migrations/<dialect>/NNN_name.sql` ([DIALECTS]):
 * a dialect has a version of every migration or of none
 * (`migration.dialects`), so the steps are the same on every server.
 */
object MigrationKind : FilesKind<MigrationFile>(
    "migration",
    "migrations",
    Layout.SingleFile(".sql", variants = MigrationFile.DIALECTS),
    Contents.SQL
) {
    override val sampleId get() = "001_sample"

    // A package's database is its own; no one else sees its schema.
    override val exportable get() = false

    override fun template(id: String, game: GameData?) = mapOf(
        pathOf(id) to "-- Runs once, in order, on the package's own database (nf.db()).\n"
    )

    override fun of(id: String, files: List<String>): MigrationFile {
        // A single file's resource lists no files when it's only the file: then it's only SQLite's.
        val dialects = files.mapNotNull { file -> file.substringBefore('/', "").takeIf { it in DIALECTS } }.toSet()
        val base = files.isEmpty() || "$id.sql" in files
        return MigrationFile.of(id).copy(dialects = dialects, base = base)
    }

    override fun validate(value: MigrationFile, ctx: ResourceContext) {
        if (value.number == null) {
            ctx.sink.report(ProblemCodes.MIGRATION_NAME, "\"${value.id}.sql\" isn't named NNN_name.sql, like 001_init.sql")
        }
        if (!value.base) {
            val dialect = value.dialects.sorted().first()
            val at = dialectPathOf(value.id, ctx.group, dialect)
            ctx.sink.problems += ProblemCodes.MIGRATION_DIALECTS.at(
                at,
                "There's no ${pathOf(value.id, ctx.group)} beside $at: every migration is SQLite's first " +
                    "(every package database starts on SQLite), and a server's version goes beside it"
            )
        }
    }

    /** The servers a migration may have a version of its own for, by the folder it's in. */
    val DIALECTS: Set<String> get() = MigrationFile.DIALECTS

    /** Where [id]'s version for [dialect] is: `migrations/<group>/<dialect>/<id>.sql`. */
    fun dialectPathOf(id: String, group: String?, dialect: String): String {
        val main = pathOf(id, group)
        return main.substringBeforeLast('/') + "/$dialect/" + main.substringAfterLast('/')
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
        // A dialect some migration has a version for, which this one hasn't: the steps would differ between servers.
        val missing = numbered.flatMap { it.dialects }.toSet() - value.dialects
        for (dialect in missing.sorted()) {
            ctx.sink.report(
                ProblemCodes.MIGRATION_DIALECTS,
                "Other migrations have a $dialect version and this one hasn't: add " +
                    "${dialectPathOf(value.id, ctx.placeOf(MigrationKind, value.id).group, dialect)}, so every server runs the same steps"
            )
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
