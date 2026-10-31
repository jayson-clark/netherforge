package dev.netherforge.plugin.api

import dev.netherforge.format.centity.CentityKind
import dev.netherforge.format.dialog.DialogKind
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.menu.MenuKind
import dev.netherforge.format.menu.MenuValidator
import dev.netherforge.format.project.Names
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.resourcepack.DisplayGlyphs
import dev.netherforge.format.resourcepack.PackFonts
import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.CentityFilter
import dev.netherforge.plugin.binding.CentitySpawnOptions
import dev.netherforge.plugin.binding.LocationOrVec3
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.Marshal
import dev.netherforge.plugin.binding.NfApi
import dev.netherforge.plugin.binding.NfCentitiesApi
import dev.netherforge.plugin.binding.NfDialogsApi
import dev.netherforge.plugin.binding.NfFilesApi
import dev.netherforge.plugin.binding.NfJsonApi
import dev.netherforge.plugin.binding.NfLooksApi
import dev.netherforge.plugin.binding.NfMenusApi
import dev.netherforge.plugin.binding.NfPlayersApi
import dev.netherforge.plugin.binding.NfServerApi
import dev.netherforge.plugin.binding.NfTextApi
import dev.netherforge.plugin.binding.TextMeasureOptions
import dev.netherforge.plugin.binding.TextSize
import dev.netherforge.plugin.binding.notInProject
import dev.netherforge.plugin.binding.place
import dev.netherforge.plugin.data.ScriptData
import dev.netherforge.plugin.data.parseJson
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaCodecs
import dev.netherforge.plugin.lua.LuaMap
import dev.netherforge.plugin.lua.LuaValue
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.ScopeOwner
import dev.netherforge.plugin.session.ProjectSession
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.sqrt

/** `nf.server`. */
internal class NfImpl(private val session: ProjectSession) : NfApi {
    override fun data(caller: Caller, name: String): Any? {
        if (!Names.isId(name)) throw LuaApiException("\"$name\" isn't a data table name (${Names.ID_RULE})")
        // Each package's own: a dependency's `nf.data("config")` isn't the project's.
        return session.data.table(ScriptData.Owner.Named(caller.scope.namespace, name), "nf.data(\"$name\")")
    }

    // The calling package's own database: a dependency's `nf.db()` isn't the project's. The `db` requirement is checked in the
    // generated primitive (the spec says `requires: "db"`), held to the calling package. A named connection is the server owner's
    // as well: it must exist and list the calling package, and that is checked here, before any statement.
    override fun db(caller: Caller, name: String?): LuaHandle.Database {
        val namespace = caller.scope.namespace
        if (name == null) return LuaHandle.Database(namespace, "")
        session.runtime.remoteDatabases.problem(name, namespace)?.let { throw LuaApiException("nf.db: $it") }
        return LuaHandle.Database(namespace, name)
    }

    // The calling package's own settings: a dependency's `nf.config("x")` isn't the project's.
    override fun config(caller: Caller, name: String): Any = session.settings.config(caller.scope, name)
}

internal class NfServerImpl(private val session: ProjectSession) : NfServerApi {
    private val platform get() = session.platform

    override fun tick(caller: Caller): Long = session.ticks

    override fun unixTime(caller: Caller): Long = session.runtime.wallClock.millis()

    override fun ticksPerSecond(caller: Caller): Double = platform.performance.ticksPerSecond()

    override fun tickMilliseconds(caller: Caller): Double = platform.performance.tickMilliseconds()

    override fun minecraftVersion(caller: Caller): String = platform.info.minecraftVersion

    override fun broadcast(caller: Caller, text: String) = platform.players.broadcast(text)

    override fun run(caller: Caller, command: String): Boolean = platform.commands.runConsole(command.removePrefix("/"))

    private val admin get() = platform.serverAdmin

    override fun maxPlayers(caller: Caller): Long = admin.maxPlayers().toLong()

    override fun setMaxPlayers(caller: Caller, count: Long) {
        if (count !in 0..Int.MAX_VALUE) throw LuaApiException("count must be at least 0, not $count")
        admin.setMaxPlayers(count.toInt())
    }

    override fun motd(caller: Caller): String = admin.motd()

    override fun setMotd(caller: Caller, text: String) {
        admin.setMotd(text)
    }

    override fun isWhitelistEnabled(caller: Caller): Boolean = admin.isWhitelistEnabled()

    override fun setWhitelistEnabled(caller: Caller, enabled: Boolean) {
        admin.setWhitelistEnabled(enabled)
    }
}

/** `nf.players`. */
internal class NfPlayersImpl(private val session: ProjectSession) : NfPlayersApi {
    private val players get() = session.platform.players

    override fun online(caller: Caller): List<LuaHandle.Player> = players.online().map(::playerHandle)

    override fun get(caller: Caller, nameOrId: String): LuaHandle.Player? = players.known(nameOrId)?.let(::playerHandle)

    private val admin get() = session.platform.serverAdmin

    override fun known(caller: Caller): List<LuaHandle.Player> = admin.known().map(::playerHandle)

    override fun banned(caller: Caller): List<LuaHandle.Player> = admin.banned().map(::playerHandle)

    override fun whitelisted(caller: Caller): List<LuaHandle.Player> = admin.whitelisted().map(::playerHandle)
}

/** `nf.centities`. */
internal class NfCentitiesImpl(private val session: ProjectSession) : NfCentitiesApi {
    private val centities get() = session.centities

    override fun spawn(
        caller: Caller,
        kind: String,
        locationOrPosition: LocationOrVec3,
        options: CentitySpawnOptions?
    ): LuaHandle.Centity? {
        val id = session.names.resource(CentityKind, kind)
        val place = locationOrPosition.place
        val scope = caller.scope
        val lifetime = options?.lifetime
        if (lifetime != null && lifetime < 1) throw LuaApiException("bad argument 'options.lifetime' (at least 1 expected, got $lifetime)")
        if (centities.definition(id) == null) throw LuaApiException(session.notInProject("centity", kind, centities.definitionIds()))
        val at = place.resolve(place.world?.name ?: session.scriptWorld(scope))
        val instance = centities.spawn(
            id,
            at,
            place.yaw ?: 0.0,
            temporary = options?.temporary == true,
            owner = scope.id,
            lifetime = lifetime
        ) ?: return null
        return LuaHandle.Centity(instance.id.toString())
    }

    override fun properties(caller: Caller, kind: String): Any? {
        val id = session.names.resource(CentityKind, kind)
        val definition = centities.definition(id) ?: throw LuaApiException(session.notInProject("centity", kind, centities.definitionIds()))
        return JsonObject(definition.properties.orEmpty())
    }

    override fun get(caller: Caller, id: String): LuaHandle.Centity? =
        centities.find(id)?.takeIf { !it.removed }?.let { LuaHandle.Centity(id) }

    override fun all(caller: Caller, filter: CentityFilter?): List<LuaHandle.Centity> = centitiesMatching(session, filter)
}

/** The world a bare `Vec3` means for [scope]'s code: a centity script's own; for anything else, the server's default. */
internal fun ProjectSession.scriptWorld(scope: Scope): String {
    val owner = scope.owner
    if (owner is ScopeOwner.CentityScript) centities.find(owner.instance)?.let { return it.anchor.world }
    return platform.worlds.defaultWorld()
}

/** The live instances a filter picks: what `nf.centities.all` and `world:centities` list. */
internal fun centitiesMatching(session: ProjectSession, filter: CentityFilter?): List<LuaHandle.Centity> {
    val near = filter?.near
    if ((near == null) != (filter?.radius == null)) throw LuaApiException("filter.near and filter.radius go together")
    val kind = filter?.kind?.let { session.names.resource(CentityKind, it) }
    fun distance(at: Location, to: Vec3): Double =
        sqrt((at.x - to.x) * (at.x - to.x) + (at.y - to.y) * (at.y - to.y) + (at.z - to.z) * (at.z - to.z))
    return session.centities.all()
        .filter { instance ->
            val anchor = instance.anchor
            !instance.removed &&
                (kind == null || instance.centity == kind) &&
                (filter?.world == null || anchor.world == filter.world.name) &&
                (near == null || distance(anchor, near) <= filter.radius!!)
        }
        .map { LuaHandle.Centity(it.id.toString()) }
}

/** `nf.menus`. */
internal class NfMenusImpl(private val session: ProjectSession, private val marshal: Marshal) : NfMenusApi {
    override fun shared(caller: Caller, menu: String): LuaHandle.Menu? {
        val id = session.names.resource(MenuKind, menu)
        val menus = session.menus
        val definition = menus.definition(id) ?: throw LuaApiException(session.notInProject("menu", menu, menus.ids()))
        if (!definition.shared) return null
        return menus.window(id)?.let { LuaHandle.Menu(it.id) }
    }

    /** Checked as `menu.json` is (its skin and the glyphs in its title against the calling package's packs too), then kept for the calling scope. */
    override fun create(caller: Caller, definition: LuaValue): LuaHandle.MenuTemplate {
        val owner = caller.scope
        val file = menuFrom(
            definition,
            { slots -> slots.read(SLOTS, "definition.slots") },
            session.platform.game
        ) { session.names.definition(MenuKind.serializer.descriptor, it, ::luaPath) }
        val template = session.menus.create({ id -> MenuValidator.compile(id, file) }, owner.id)
        return LuaHandle.MenuTemplate(template.id)
    }
}

/** `nf.dialogs`. */
internal class NfDialogsImpl(private val session: ProjectSession, private val marshal: Marshal) : NfDialogsApi {
    override fun get(caller: Caller, dialog: String): LuaHandle.Dialog {
        val id = session.names.resource(DialogKind, dialog)
        val dialogs = session.dialogs
        if (id !in dialogs.ids()) throw LuaApiException(session.notInProject("dialog", dialog, dialogs.ids()))
        return LuaHandle.Dialog(id)
    }

    /** Checked as `dialog.json` is (a `dialog_list` may name any dialog there is), then kept for the calling scope. */
    override fun create(caller: Caller, definition: LuaValue): LuaHandle.Dialog {
        val owner = caller.scope
        val dialogs = session.dialogs
        // Item tables in it come out as items do, their `data` tagged, so they're read as every item a script hands over.
        val file = dialogFrom(definition.json("definition"), ::readItem, session.platform.game) {
            session.names.definition(DialogKind.serializer.descriptor, it, ::luaPath)
        }
        file.dialogs.forEachIndexed { index, other ->
            if (dialogs.idOf(other) == null) {
                val missing = session.notInProject("dialog", session.names.spell(other.text), dialogs.ids())
                throw LuaApiException("definition.dialogs[${index + 1}]: $missing")
            }
        }
        return LuaHandle.Dialog(dialogs.create(file, owner.id))
    }

    /** An item table inside a definition, read as every item a script hands over, its errors named by where it is. */
    private fun readItem(item: JsonElement, where: String): ItemDef = try {
        marshal.item(item).def
    } catch (e: LuaApiException) {
        throw LuaApiException("$where: ${e.message?.removePrefix("item.")}")
    }
}

/** A menu definition's `slots`: items by slot number. */
private val SLOTS = LuaMap(LuaCodecs.INTEGER, LuaCodecs.ITEM)

/** `nf.files`. */
internal class NfFilesImpl(private val session: ProjectSession) : NfFilesApi {
    /** A file in the calling package's own folder: the handle's key is `<namespace>/<path>` (see `FileImpl`). */
    override fun get(caller: Caller, path: String?): LuaHandle.File? {
        val relative = session.files.normalize(path ?: "") ?: return null
        val namespace = session.names.calling()
        return LuaHandle.File(if (relative.isEmpty()) namespace else "$namespace/$relative")
    }
}

/** `nf.text`. */
internal class NfLooksImpl(private val session: ProjectSession) : NfLooksApi {
    override fun qualify(caller: Caller, look: String): String = session.names.qualify(look)
}

internal class NfTextImpl(private val session: ProjectSession) : NfTextApi {
    override fun escape(caller: Caller, text: String): String = session.platform.text.escape(text)

    override fun strip(caller: Caller, text: String): String = session.platform.text.strip(text)

    override fun glyph(caller: Caller, glyph: String): String? = session.packs.glyph(session.names.entry(RefKind.GLYPH, glyph))

    override fun width(caller: Caller, text: String): Long? = session.measureText(text)?.toLong()

    override fun measure(caller: Caller, text: String, options: TextMeasureOptions?): TextSize? {
        val scale = options?.scale ?: 1.0
        if (!(scale > 0) || !scale.isFinite()) throw LuaApiException("bad argument 'options.scale' (more than 0, got $scale)")
        val (width, lines) = session.measureTextLines(text) ?: return null
        val box = DisplayGlyphs.textBox(width, lines)
        return TextSize(box.width * scale, box.height * scale)
    }

    override fun space(caller: Caller, pixels: Long): String? {
        if (session.packs.built == null) return null
        return PackFonts.offset(pixels.coerceIn(-PackFonts.MAX_OFFSET.toLong(), PackFonts.MAX_OFFSET.toLong()).toInt())
    }
}

/** `nf.json`. */
internal class NfJsonImpl : NfJsonApi {
    /** What JSON can't hold is the script's mistake, so [LuaValue.json]'s error stands. */
    override fun encode(caller: Caller, value: LuaValue): String = value.json().toString()

    /** Text that isn't JSON is a condition (it may come from a player or a file), so nil. */
    override fun decode(caller: Caller, text: String): Any? = parseJson(text)
}

internal fun playerHandle(player: PlayerRef) = LuaHandle.Player(player.uuid.toString())
