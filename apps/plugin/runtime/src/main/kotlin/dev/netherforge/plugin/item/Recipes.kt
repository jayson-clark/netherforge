package dev.netherforge.plugin.item

import dev.netherforge.format.item.ItemKind
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.ResourcePlace
import dev.netherforge.format.recipe.RecipeFile
import dev.netherforge.format.recipe.RecipeKind
import dev.netherforge.format.recipe.RecipeValidator
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.RecipeSpec
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject
import dev.netherforge.plugin.session.TickPhase

/**
 * The recipes the project adds to the server: every valid `recipes/<id>.json`,
 * and those scripts register (`nf.recipes.register`), each belonging to the
 * scope that registered it.
 *
 * The server is told at once ([RecipeOps.add] and `remove`), but players'
 * clients only once a tick ([tick]: `resend`), so a reload that changes many
 * recipes, or a script registering a few in its body, sends the list once.
 */
class Recipes(
    private val platform: Platform,
    private val log: RuntimeLog,
    /** Where a resource is now (in its group folders), by kind and id: where a recipe's file is. */
    private val placeOf: (KindSpec<*, *>, String) -> ResourcePlace,
    private val itemId: (reference: String) -> String?
) : RuntimeService {
    override val name get() = "recipes"

    private class Created(val file: RecipeFile, val owner: Int)

    /** The project's files' recipes that the server has, by id. */
    private val files = LinkedHashMap<String, RecipeFile>()

    /** Recipes scripts registered, by id. */
    private val created = LinkedHashMap<String, Created>()

    /** Whether players' recipe lists are behind the server's. */
    private var dirty = false

    private val ops get() = platform.recipes

    /** Every recipe's id: the files', then the scripts'. */
    fun ids(): List<String> = files.keys.sorted() + created.keys.sorted()

    fun has(id: String): Boolean = id in files || id in created

    fun isFile(id: String): Boolean = id in files

    /** The recipe [id] (a file's or a script's), or null. */
    fun file(id: String): RecipeFile? = files[id] ?: created[id]?.file

    /** Every `station` recipe, by id: the files', then the scripts'. Scripts make these, never the game. */
    fun stations(): Map<String, RecipeFile> = buildMap {
        for ((id, file) in files) if (!file.type.inGame) put(id, file)
        for ((id, recipe) in created) if (!recipe.file.type.inGame) put(id, recipe.file)
    }

    /** The ids the game was given, so a `station` recipe (never given) is never taken back from it. */
    private val sent = HashSet<String>()

    /** Takes every file's recipe, after the items: a recipe's project items are built from their looks. */
    override fun define(project: SessionProject) {
        for (id in files.keys.toList()) take(id)
        files.clear()
        for ((id, file) in project.running(RecipeKind)) {
            if (put(id, file)) files[id] = file
        }
    }

    override fun scopeReleased(scope: Scope) = removeOwned(scope.id)

    override fun tick(phase: TickPhase) {
        if (phase == TickPhase.UPKEEP) resend()
    }

    override val reloads: Set<KindSpec<*, *>> get() = setOf(RecipeKind)

    /** The server learns the recipe again (or forgets it); players are sent the list at the next tick. */
    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        for (id in ids) {
            batch.resource(Resource(RecipeKind, id), batch.snapshot.running(RecipeKind)[id]) {
                reload(id, it)
                0
            }
        }
    }

    /** Recipes are built from their project items' looks. */
    override val follows: Set<String> get() = setOf(ItemKind.id)

    override fun followed(kind: String, ids: Set<String>, batch: ReloadBatch) {
        for (id in ids) itemChanged(id)
    }

    /** Moves [id] onto [next], or takes it away when [next] is null (the file was deleted). */
    fun reload(id: String, next: RecipeFile?) {
        if (files.remove(id) != null) take(id)
        if (next != null && put(id, next)) files[id] = next
    }

    /**
     * Adds a recipe for the scope [owner], replacing one a script registered
     * with that id. An id a file has is the script's mistake.
     */
    fun register(id: String, file: RecipeFile, owner: Int) {
        if (id in files) throw LuaApiException("recipe \"$id\" is ${placeOf(RecipeKind, id).path}'s; pick another id")
        if (created.remove(id) != null) take(id)
        if (!put(id, file)) throw LuaApiException("the server couldn't make recipe \"$id\"")
        created[id] = Created(file, owner)
    }

    /** Takes away a recipe a script registered; false when there's none by that id. */
    fun remove(id: String): Boolean {
        if (id in files) throw LuaApiException("recipe \"$id\" is ${placeOf(RecipeKind, id).path}'s; delete the file to remove it")
        created.remove(id) ?: return false
        take(id)
        return true
    }

    /** The scope [owner] stopped: the recipes it registered go. */
    fun removeOwned(owner: Int) {
        for ((id, recipe) in created.entries.toList()) {
            if (recipe.owner != owner) continue
            created.remove(id)
            take(id)
        }
    }

    /**
     * Project item [item] changed (its kind may have): every recipe naming it
     * is built again, since the server matches a project item by its kind
     * before the adapter checks its id.
     */
    fun itemChanged(item: String) {
        for ((id, file) in files) if (names(file, item)) put(id, file)
        for ((id, recipe) in created) if (names(recipe.file, item)) put(id, recipe.file)
    }

    /** Takes every recipe away (the session ending), and tells players at once. */
    override fun stop() {
        for (id in files.keys + created.keys) take(id)
        files.clear()
        created.clear()
        resend()
    }

    /** Once a tick: tells players about the recipes that changed. */
    private fun resend() {
        if (!dirty) return
        dirty = false
        ops.resend()
    }

    private fun names(file: RecipeFile, item: String): Boolean {
        fun names(reference: ResourceRef?) = reference != null && itemId(reference.text) == item
        return names(file.result.item) || RecipeValidator.ingredients(file).any { (_, ingredient) -> names(ingredient.item) }
    }

    private fun put(id: String, file: RecipeFile): Boolean {
        if (!file.type.inGame) return true
        dirty = true
        val added = ops.add(RecipeSpec(id, file))
        if (added) sent += id else log.warn("The server couldn't make recipe \"$id\"; it's left out")
        return added
    }

    private fun take(id: String) {
        if (!sent.remove(id)) return
        dirty = true
        ops.remove(id)
    }
}
