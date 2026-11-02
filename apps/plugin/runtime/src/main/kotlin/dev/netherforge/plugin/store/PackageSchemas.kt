package dev.netherforge.plugin.store

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.Severity
import dev.netherforge.format.migration.MigrationFile
import dev.netherforge.format.migration.MigrationKind
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.Loaded
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject

/**
 * The schema of every package's database: its `migrations/NNN_name.sql`,
 * applied in order when the project loads and again when one is added on a
 * reload, each once (the database records which it has had, see
 * [Database.migrate]).
 *
 * A package whose migrations can't all be applied (a file with problems, SQL
 * the database refuses) gets **no database at all** until that's fixed, rather
 * than a half-built schema: its scripts' `nf.db()` calls answer `nil, err`
 * with the reason ([unavailable]), and the failure is a problem on the file
 * that caused it. A migration that was applied stays applied (migrations only
 * go forward), so editing or deleting one has no effect on a database that
 * already ran it; a database is reset by deleting its file.
 */
class PackageSchemas(
    private val databases: PackageDatabases,
    private val log: RuntimeLog,
    /** The project's namespace, which the ids of its own migrations are in. */
    private val home: () -> String,
    /** A project file's text, by its project path (a package's at its package path). */
    private val read: (String) -> String?,
    private val problemsChanged: () -> Unit,
    /** A package's own database on a connection the owner named for it (`own-databases`), or null for its SQLite file. */
    private val own: (String) -> OwnDatabase? = { null }
) : RuntimeService {
    override val name get() = "package databases"

    /** Why a package's database can't be used, by namespace, and the problem that says it in the editor. */
    private val failures = HashMap<String, Problem>()

    /** Why [namespace]'s database can't be used (its migrations failed), or null when it can. */
    fun unavailable(namespace: String): String? = failures[namespace]?.message

    override fun define(project: SessionProject) {
        failures.clear()
        for ((namespace, migrations) in byPackage(project.everywhere(MigrationKind))) apply(namespace, migrations)
    }

    override val reloads: Set<KindSpec<*, *>> get() = setOf(MigrationKind)

    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        val all = byPackage(batch.snapshot.everywhere(MigrationKind))
        val namespaces = ids.map { namespaceOf(it) }.toSet()
        for (namespace in namespaces) apply(namespace, all[namespace].orEmpty())
        for (id in ids) {
            val mine = failures[namespaceOf(id)]
            val found = batch.problemsUnder(Resource(MigrationKind, id))
            batch.report(
                Resource(MigrationKind, id),
                ok = found.none { it.severity == Severity.ERROR } && mine == null,
                problems =
                found + listOfNotNull(mine)
            )
        }
        problemsChanged()
    }

    override fun problems(): List<Problem> = failures.values.toList()

    private fun namespaceOf(id: String): String = PackagePaths.split(id).first ?: home()

    private fun byPackage(
        all: Map<String, Loaded<MigrationFile, MigrationFile>>
    ): Map<String, Map<String, Loaded<MigrationFile, MigrationFile>>> =
        all.entries.groupBy({ namespaceOf(it.key) }, { it.toPair() }).mapValues { it.value.toMap() }

    /** Brings [namespace]'s database up to date with [migrations] (all of the package's), or records why it can't be. */
    private fun apply(namespace: String, migrations: Map<String, Loaded<MigrationFile, MigrationFile>>) {
        failures.remove(namespace)
        if (migrations.isEmpty()) return
        val first = migrations.keys.minOf { it }
        val broken = migrations.entries.firstOrNull { (_, loaded) -> loaded.problems.any { it.severity == Severity.ERROR } }
        fun pathOf(id: String) = MigrationKind.pathOf(id, migrations[id]?.group)
        if (broken != null) {
            fail(
                namespace,
                pathOf(broken.key),
                "its migrations have problems (see ${pathOf(broken.key)}), so the database isn't built"
            )
            return
        }
        val shared = own(namespace)
        if (shared != null) {
            // On a server of another dialect, every step is that server's version of it: SQLite's SQL isn't theirs.
            val dialect = shared.dialect
            val without = migrations.entries.sortedBy { it.key }.firstOrNull { dialect !in it.value.value.dialects }
            if (without != null) {
                return fail(
                    namespace,
                    pathOf(without.key),
                    "it's on the ${shared.describe()}, and its migrations have no $dialect versions " +
                        "(${dialectPathOf(without.key, without.value.group, dialect)})",
                    code = ProblemCodes.MIGRATION_DIALECT
                )
            }
        }
        val steps = ArrayList<Migration>()
        for ((id, loaded) in migrations.entries.sortedBy { it.key }) {
            val path = if (shared == null) pathOf(id) else dialectPathOf(id, loaded.group, shared.dialect)
            val sql = read(path) ?: return fail(namespace, path, "$path couldn't be read")
            steps += Migration.of(PackagePaths.split(id).second + ".sql", sql)
        }
        try {
            if (shared == null) databases.database(namespace).migrate(namespace, steps) else shared.migrate(steps)
        } catch (e: MigrationFailed) {
            val failed = migrations.keys.first { PackagePaths.split(it).second + ".sql" == e.migration.fileName }
            val path = if (shared == null) pathOf(failed) else dialectPathOf(failed, migrations[failed]?.group, shared.dialect)
            fail(namespace, path, "couldn't apply ${e.migration.fileName}: ${e.cause.message}", e)
        } catch (e: MigrationAhead) {
            fail(
                namespace,
                pathOf(migrations.keys.maxOf { it }),
                "the ${shared?.describe()} has ${e.message} applied, which this server's copy of $namespace doesn't have: " +
                    "a newer copy ran there, so run that one here too",
                code = ProblemCodes.MIGRATION_AHEAD
            )
        } catch (e: Exception) {
            fail(namespace, pathOf(first), "couldn't open the database: ${e.message}", e)
        }
    }

    private fun dialectPathOf(id: String, group: String?, dialect: String) = MigrationKind.dialectPathOf(id, group, dialect)

    private fun fail(
        namespace: String,
        file: String,
        message: String,
        cause: Throwable? = null,
        code: dev.netherforge.format.ProblemCode = ProblemCodes.MIGRATION_FAILED
    ) {
        failures[namespace] = code.at(file, "The database of $namespace is unavailable: $message")
        log.error("The database of $namespace is unavailable: $message", cause)
    }
}
