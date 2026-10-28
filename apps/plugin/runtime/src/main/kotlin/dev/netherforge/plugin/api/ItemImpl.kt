package dev.netherforge.plugin.api

import dev.netherforge.format.block.BlockKind
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.item.ItemKind
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.project.Names
import dev.netherforge.format.recipe.Material
import dev.netherforge.format.recipe.RecipeFile
import dev.netherforge.format.recipe.RecipeKind
import dev.netherforge.format.recipe.RecipeValidator
import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.NfItemsApi
import dev.netherforge.plugin.binding.NfRecipesApi
import dev.netherforge.plugin.binding.ProjectItemApi
import dev.netherforge.plugin.binding.RecipeAvailableOptions
import dev.netherforge.plugin.binding.RecipeCraftEvent
import dev.netherforge.plugin.binding.RecipeCraftOptions
import dev.netherforge.plugin.binding.RecipeMaterial
import dev.netherforge.plugin.binding.StationRecipe
import dev.netherforge.plugin.item.StationCrafting
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaValue
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.session.ProjectSession
import kotlinx.serialization.json.JsonObject

/** `nf.items`: the project's items, and stacks of them. */
internal class NfItemsImpl(private val session: ProjectSession, private val api: RuntimeApi) : NfItemsApi {
    override fun create(caller: Caller, item: String, overrides: LuaValue?): ItemData {
        val named = session.names.resource(ItemKind, item)
        val id = session.items.idOf(named) ?: throw LuaApiException("there's no item \"$item\" (${ItemKind.pathOf(named)})")
        return session.items.create(id, overrides?.let { api.overrides(it, id) })
    }

    /** Its project item, as the caller's package writes it. */
    override fun id(caller: Caller, stack: ItemData): String? = stack.def.item?.text?.let(session.names::spell)

    override fun get(caller: Caller, item: String): LuaHandle.ProjectItem? =
        session.items.idOf(session.names.resource(ItemKind, item))?.let { LuaHandle.ProjectItem(it) }

    override fun all(caller: Caller): List<LuaHandle.ProjectItem> =
        session.items.ids().filter { session.names.usable(ItemKind, it) }.map { LuaHandle.ProjectItem(it) }
}

/** `nf.recipes`: the project's recipes, and those scripts register. */
internal class NfRecipesImpl(private val session: ProjectSession, private val api: RuntimeApi) : NfRecipesApi {
    /** Checked as a `recipes/` file is, then kept for the calling scope. */
    override fun register(caller: Caller, id: String, definition: LuaValue) {
        if (!Names.isId(id)) throw LuaApiException("\"$id\" isn't a usable recipe id (${Names.ID_RULE})")
        val owner = caller.scope
        val file = recipeFrom(
            definition,
            { value -> value.read { lua, index -> api.item(lua, index) }.def },
            session.platform.game
        ) { session.names.definition(RecipeKind.serializer.descriptor, it, ::luaPath) }
        for ((at, ingredient) in RecipeValidator.ingredients(file)) {
            val item = ingredient.item?.text ?: continue
            if (session.items.idOf(item) == null) throw LuaApiException("${luaPath(at)}: there's no item \"$item\"")
        }
        // The calling package's own: a package's recipes are named in its namespace.
        session.recipes.register(session.names.resource(RecipeKind, id), file, owner.id)
    }

    override fun remove(caller: Caller, id: String): Boolean = session.recipes.remove(session.names.resource(RecipeKind, id))

    private val crafting by lazy {
        StationCrafting(session.platform, session.items, session.customBlocks) { name ->
            runCatching { session.names.resource(BlockKind, name) }.getOrNull()?.takeIf(session.customBlocks::has) ?: name
        }
    }

    /** A station recipe as scripts see it, with [times] when it's being offered. */
    private fun shown(id: String, file: RecipeFile, times: Int?) = StationRecipe(
        id = session.names.spell(id),
        result = crafting.result(file),
        materials = file.materials.orEmpty().map { m ->
            val ingredient = m.ingredient
            RecipeMaterial(
                item = ingredient.item?.let { session.items.idOf(it.text) }?.let(session.names::spell),
                kind = ingredient.kind?.let(GameIds::normalize),
                tag = ingredient.tag?.let(GameIds::normalize),
                count = m.countOrDefault.toLong()
            )
        },
        stations = file.stations.orEmpty(),
        stationRange = (file.stationRange ?: Material.DEFAULT_STATION_RANGE).toLong(),
        group = file.group,
        times = times?.toLong()
    )

    override fun get(caller: Caller, id: String): StationRecipe? {
        val name = session.names.resource(RecipeKind, id)
        val file = session.recipes.file(name)?.takeIf { !it.type.inGame } ?: return null
        return shown(name, file, null)
    }

    override fun available(caller: Caller, player: LuaHandle.Player, options: RecipeAvailableOptions?): List<StationRecipe> {
        val uuid = player.uuidOrNull()?.takeIf { session.platform.players.get(it) != null } ?: return emptyList()
        val at = session.platform.players.location(uuid) ?: return emptyList()
        val recipes = session.recipes.stations().filter { options?.group == null || it.value.group == options.group }
        val near = crafting.near(
            at.world,
            options?.near ?: Vec3(at.x, at.y, at.z),
            crafting.range(recipes.values),
            options?.stations.orEmpty()
        )
        return recipes.entries.sortedBy { it.key }.mapNotNull { (id, file) ->
            val times = if (crafting.stationsNear(file, near)) crafting.times(file, uuid) else 0
            if (times > 0 || options?.all == true) shown(id, file, times) else null
        }
    }

    override fun craft(caller: Caller, player: LuaHandle.Player, id: String, options: RecipeCraftOptions?): Long {
        val times = options?.times ?: 1
        if (times < 1) throw LuaApiException("bad argument 'options.times' (at least 1 expected, got $times)")
        val name = session.names.resource(RecipeKind, id)
        val file = session.recipes.file(name) ?: throw LuaApiException("there's no recipe \"$id\"")
        if (file.type.inGame) throw LuaApiException("recipe \"$id\" isn't a station recipe: the game makes it")
        val uuid = player.uuidOrNull()?.takeIf { session.platform.players.get(it) != null } ?: return 0
        val at = session.platform.players.location(uuid) ?: return 0
        val near = crafting.near(
            at.world,
            options?.near ?: Vec3(at.x, at.y, at.z),
            crafting.range(listOf(file)),
            options?.stations.orEmpty()
        )
        if (!crafting.stationsNear(file, near)) return 0
        val made = minOf(times.toInt(), crafting.times(file, uuid))
        val scripts = session.scripts
        val each = if (scripts.listening(null, Events.NF_RECIPE_CRAFT)) {
            { result: ItemData ->
                RecipeCraftEvent(player, session.names.spell(name), result).also { scripts.emit(Events.NF_RECIPE_CRAFT, null, it) }.result
            }
        } else {
            null
        }
        if (made > 0) crafting.make(file, uuid, made, each)
        return made.toLong()
    }

    override fun all(caller: Caller): List<String> = session.recipes.ids().map(session.names::spell)
}

/** A project item: answers `nil` or `false` once the project no longer has it. */
internal class ProjectItemImpl(private val session: ProjectSession, private val api: RuntimeApi) : ProjectItemApi {
    override fun id(self: LuaHandle.ProjectItem): String = session.names.spell(self.id)

    override fun exists(self: LuaHandle.ProjectItem): Boolean = session.items.has(self.id)

    override fun properties(self: LuaHandle.ProjectItem): Any? = session.items.properties(self.id)?.let(::JsonObject)

    override fun create(self: LuaHandle.ProjectItem, overrides: LuaValue?): ItemData? {
        if (!session.items.has(self.id)) return null
        return session.items.create(self.id, overrides?.let { api.overrides(it, self.id) })
    }
}
