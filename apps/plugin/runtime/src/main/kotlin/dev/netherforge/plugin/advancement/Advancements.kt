package dev.netherforge.plugin.advancement

import dev.netherforge.format.advancement.AdvancementKind
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject

/**
 * The project's advancements (`advancements/<id>.json`), its packages'
 * included. The server learns them only as it starts, from the start-up
 * datapack, so nothing here puts one on the server: a reload reports the
 * files' problems, and the session then asks whether the server must
 * restart (`StartupDatapackCheck`). What this keeps is which advancements the
 * project has, so a script naming one the server doesn't (yet) is told why.
 */
class Advancements(
    /** The project's namespace, which an advancement's key is in. */
    private val namespace: () -> String
) : RuntimeService {
    override val name get() = "advancements"

    /** Every advancement the project's files have, as the project names it (`adventurer`, `library:gem_collector`). */
    private val names = HashSet<String>()

    override fun define(project: SessionProject) {
        names.clear()
        names += project.everywhere(AdvancementKind).keys
    }

    override val reloads: Set<KindSpec<*, *>> get() = setOf(AdvancementKind)

    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        for (id in ids) {
            if (id in batch.snapshot.everywhere(AdvancementKind)) names += id else names -= id
            batch.data(Resource(AdvancementKind, id))
        }
    }

    /** Whether the project's files have the advancement [name] (as the project names it). */
    fun has(name: String): Boolean = name in names

    /** [name] (as the project names it) as the server's key: `showcase:adventurer`, `library:gem_collector`. */
    fun keyOf(name: String): String =
        requireNotNull(ResourceRef(name).resolve(namespace())) { "\"$name\" isn't an advancement's name" }.toString()
}
