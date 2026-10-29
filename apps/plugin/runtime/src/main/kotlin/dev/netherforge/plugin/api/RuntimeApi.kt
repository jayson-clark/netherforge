package dev.netherforge.plugin.api

import dev.netherforge.format.game.MinecraftVersion
import dev.netherforge.format.item.ItemDef
import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.LuaApi
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.Marshal
import dev.netherforge.plugin.binding.codec
import dev.netherforge.plugin.binding.entityHandle
import dev.netherforge.plugin.binding.luaPrimitives
import dev.netherforge.plugin.binding.notInProject
import dev.netherforge.plugin.data.ScriptData
import dev.netherforge.plugin.item.ItemMatch
import dev.netherforge.plugin.item.LuaItems
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaCall
import dev.netherforge.plugin.lua.LuaCodec
import dev.netherforge.plugin.lua.LuaGates
import dev.netherforge.plugin.lua.LuaRef
import dev.netherforge.plugin.lua.LuaTypeMismatch
import dev.netherforge.plugin.lua.LuaValue
import dev.netherforge.plugin.lua.Primitive
import dev.netherforge.plugin.lua.ScriptFailure
import dev.netherforge.plugin.mob.MobGoals
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.ProjectSession
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import party.iroiro.luajava.Lua
import java.util.concurrent.CompletionStage

/**
 * The runtime's side of the Lua API: the implementations of the generated
 * interfaces (one class per Lua class, in this package), the conversions the
 * generated primitives need ([Marshal]), and the few primitives only the
 * prelude's hand-written core calls ([corePrimitives]).
 *
 * The rules every implementation keeps: a handle to something that has gone
 * (a removed centity, a player who left) answers nil/false rather than
 * erroring, so a script holding one from earlier doesn't have to guard every
 * call; and a mistake only Kotlin can see (no such animation, no such
 * menu) is a [LuaApiException], which Lua reports at the script's line.
 */
internal class RuntimeApi(private val session: ProjectSession) :
    LuaApi,
    Marshal {
    override val host get() = requireNotNull(session.scripts.host) { "no Lua state" }

    override val nf = NfImpl(session)
    override val nfServer = NfServerImpl(session)
    override val nfPlayers = NfPlayersImpl(session)
    override val nfWorlds = NfWorldsImpl(session)
    override val nfCentities = NfCentitiesImpl(session)
    override val nfMenus = NfMenusImpl(session, this)
    override val nfDialogs = NfDialogsImpl(session, this)
    override val nfItems = NfItemsImpl(session, this)
    override val projectItem = ProjectItemImpl(session, this)
    override val nfRecipes = NfRecipesImpl(session, this)
    override val nfLoot = NfLootImpl(session)
    override val nfParticles = NfParticlesImpl(session)
    override val nfCutscenes = NfCutscenesImpl(session)
    override val nfScreens = dev.netherforge.plugin.api.NfScreensImpl(session)
    override val screen = dev.netherforge.plugin.api.ScreenImpl(session)
    override val playback = dev.netherforge.plugin.api.PlaybackImpl(session)
    override val nfCommands = NfCommandsImpl(session)
    override val nfFiles = NfFilesImpl(session)
    override val nfText = NfTextImpl(session)
    override val nfLooks = NfLooksImpl(session)
    override val nfJson = NfJsonImpl()
    override val nfHttp = NfHttpImpl(session)
    override val nfTime = NfTimeImpl { session.schedules.zone }
    override val nfRandom = NfRandomImpl()
    override val nfTest = NfTestImpl(session)
    override val centity = CentityImpl(session)
    override val node = NodeImpl(session)
    override val player = PlayerImpl(session)
    override val bot = BotImpl(session)
    override val nfBots = NfBotsImpl(session)
    override val sender = SenderImpl(session)
    override val menu = MenuImpl(session)
    override val slot = SlotImpl(session)
    override val menuTemplate = MenuTemplateImpl(session)
    override val dialog = DialogImpl(session)
    override val button = ButtonImpl(session)
    override val effect = EffectImpl(session)
    override val httpStream = HttpStreamImpl(session)
    override val webSocket = WebSocketImpl(session)
    override val nfWeb = NfWebImpl(session)
    override val route = RouteImpl(session)
    override val webResponse = WebResponseImpl(session)
    override val oAuthFlow = OAuthFlowImpl(session)
    override val nfWasm = NfWasmImpl(session)
    override val wasmInstance = WasmInstanceImpl(session)
    override val wasmProcess = WasmProcessImpl(session)
    override val nfNetwork = NfNetworkImpl(session)
    override val networkChannel = NetworkChannelImpl(session)
    override val networkMap = NetworkMapImpl(session)
    override val cutscene = CutsceneImpl(session)
    override val file = FileImpl(session)
    override val world = WorldImpl(session)
    override val block = BlockImpl(session)
    override val customBlock = CustomBlockImpl(session)
    override val projectBlock = ProjectBlockImpl(session)
    override val nfBlocks = NfBlocksImpl(session)
    override val entity = EntityImpl(session)
    override val living = LivingImpl(session)
    override val mob = MobImpl(session)
    override val droppedItem = DroppedItemImpl(session)
    override val mannequin = MannequinImpl(session)
    override val inventory = InventoryImpl(session)
    override val bossBar = BossBarImpl(session)
    override val sidebar = SidebarImpl(session)
    override val nfBossbars = NfBossbarsImpl(session)
    override val nfTeams = NfTeamsImpl(session)
    override val nfSchedule = NfScheduleImpl(session)
    override val nfSpawning = NfSpawningImpl(session)
    override val hud = HudImpl(session)
    override val schedule = ScheduleImpl(session)
    override val nfEconomy = NfEconomyImpl(session)
    override val nfPlaceholders = NfPlaceholdersImpl(session)
    override val team = TeamImpl(session)
    override val database = DatabaseImpl(session)
    override val nfStructures = NfStructuresImpl(session)
    override val worldBorder = WorldBorderImpl(session)

    /** Option table fields the server's Minecraft is too old for, by shape, for the codecs to refuse. */
    fun gates(): LuaGates {
        val running = session.platform.info.minecraftVersion
        val server = MinecraftVersion.parse(running)
        val options = session.versionGates.options
            .filter { (_, since) -> server == null || MinecraftVersion.of(since) > server }
            .entries
            .groupBy({ it.key.substringBeforeLast('.') }, { it.key.substringAfterLast('.') to it.value })
            .mapValues { (_, fields) -> fields.toMap() }
        return LuaGates(running, options)
    }

    /** Every primitive the prelude can call: the generated bindings' and the core's. */
    fun primitives(): Map<String, Primitive> = luaPrimitives(this, this) + corePrimitives()

    // ---- Marshal ----------------------------------------------------------------

    /** The calling scope; also the package names in this call are resolved and spelled in (`PackageNames.calling`). */
    override fun caller(lua: Lua, index: Int): Caller {
        val scope = lua.toInteger(index).toInt()
        host.callerScope = scope
        return Caller(session.scripts, scope)
    }

    /**
     * The item table at [index], by the one Lua↔JSON codec: its `data` may
     * hold typed values (`Vec3`s, handles), which it keeps tagged, and
     * something in it that can't be saved is an error naming where it was.
     */
    override fun item(lua: Lua, index: Int): ItemData =
        readItem(session.names.item(itemTable(lua, index, "item"), host.spelledFor(lua, index)))

    /** The `ItemMatch` table at [index]: a partial item, its `data` read the same way. */
    override fun match(lua: Lua, index: Int): ItemMatch = session.itemMatch(itemTable(lua, index, "match"), host.spelledFor(lua, index))

    /**
     * The fields a script gave a stack of a project item (`nf.items.create`'s
     * `overrides`), read as an item's are; the item's id is [id]. `kind` and
     * `item` are the project item's, not the script's to say.
     */
    fun overrides(value: LuaValue, id: String): ItemDef {
        val table = value.read { lua, index -> itemTable(lua, index, "overrides") } as? JsonObject
            ?: throw LuaApiException("overrides must be a table of item fields, like { count = 3 }")
        for (key in listOf("kind", "item")) {
            if (key in table) throw LuaApiException("overrides can't say $key: it's the project item's")
        }
        // [id] is already as the server names it; the rest is named as any item a script hands over.
        return readItem(JsonObject(session.names.item(table) + ("item" to JsonPrimitive(id)))).def
    }

    /**
     * The item table at [index] as JSON, as it would be saved: problem paths
     * from [root]. Read without running the codec (`LuaHost.fieldsJson`), so
     * a plain table costs the script nothing; only a `data` holding typed
     * values pays for the codec's instructions.
     */
    private fun itemTable(lua: Lua, index: Int, root: String): JsonElement {
        val table = host.fieldsJson(lua, index, root, kept = "data")
        ((table as? JsonObject)?.get("data"))?.toString()?.let { text ->
            if (ScriptData.isTooBig(text)) throw LuaApiException(tooBigData(root, text))
        }
        return table
    }

    private fun tooBigData(root: String, text: String) =
        "$root.data takes ${"%.1f".format(text.encodeToByteArray().size / 1048576.0)} MiB, more than the 1 MiB it may"

    /**
     * An item table already read as JSON (one inside a definition): its `data`
     * tagged JSON. Its names are the calling script's package's
     * (`PackageNames.item`).
     */
    override fun item(json: JsonElement): ItemData = readItem(session.names.item(json))

    /** An item table whose names are as the server knows them. */
    private fun readItem(json: JsonElement): ItemData =
        LuaItems.read(json, session.platform.game, projectBlock = session.customBlocks::has) { session.items.look(it)?.def }

    /** A stack as a table, its names as the reading script's package writes them. */
    override fun pushItem(lua: Lua, item: ItemData) {
        val names = session.names
        if (!names.spanning) return host.pushValue(lua, LuaItems.write(item))
        val reader = names.calling()
        host.pushValue(lua, LuaItems.write(names.spell(item, reader)))
        // Read back in the words it was written in, whichever package's code hands it back.
        host.markSpelled(lua, -1, reader)
    }

    override fun text(text: String): String = session.names.text(text)

    override fun spellText(text: String): String = session.names.spellText(text)

    override fun spell(name: String): String = session.names.spell(name)

    override fun requires(requirement: String, what: String) = session.requirements.check(requirement, what)

    override fun <T> await(what: String, scope: Scope, result: CompletionStage<T>, waker: LuaRef, codec: LuaCodec<T>): Int =
        session.async.await(what, scope, result, waker, codec)

    /** An entity's handle as the class it is now (a `Mob`, a `Player`), a block's (a `CustomBlock`); any other handle as it is. */
    override fun refine(handle: LuaHandle): LuaHandle = when (handle) {
        is LuaHandle.Entity -> handle.uuidOrNull()?.let { session.entityHandle(it) } ?: handle
        is LuaHandle.Block -> session.blockAs(handle)
        else -> handle
    }

    // ---- the core's primitives ----------------------------------------------------

    /**
     * What the prelude's hand-written core calls that isn't part of the API:
     * `require` (`resolve`, `source`), `log`, the Kotlin half of the
     * hand-written `nf.after`, `nf.every`, a task's waits (`timer`,
     * `timer.cancel`, `timer.active`), an async function's ended wait
     * (`async.cancel`), `Mob.add_goal` (`goals.add`), and the event core's
     * reports and checks (`events.*`).
     */
    private fun corePrimitives(): Map<String, Primitive> = buildMap {
        val scripts = session.scripts
        fun Lua.scope(index: Int) = Caller(scripts, toInteger(index).toInt()).scope
        put(
            "resolve",
            Primitive { lua ->
                val (path, owner) = session.modules.resolve(lua.scope(1), lua.toString(2)!!)
                lua.push(path)
                lua.push(owner.id.toLong())
                2
            }
        )
        put(
            "source",
            Primitive { lua ->
                val path = lua.toString(1)!!
                lua.push(session.source.read(path) ?: throw LuaApiException("can't read $path"))
                1
            }
        )
        put(
            "log",
            Primitive { lua ->
                val scope = scripts.scope(lua.toInteger(1).toInt())
                val line = if (lua.isNumber(4)) lua.toInteger(4).toInt() else null
                session.scriptLog(scope, lua.toString(2)!!, if (lua.isNoneOrNil(3)) null else lua.toString(3), line)
                0
            }
        )
        put(
            "timer",
            Primitive { lua ->
                // (scope, delay, period, fn)
                val scope = lua.scope(1)
                val delay = lua.toInteger(2)
                val period = lua.toInteger(3)
                if (delay < 0 || period < 0) throw LuaApiException("ticks can't be negative")
                // Timers run at the start of a tick, so the soonest one can run is the next.
                lua.push(scripts.schedule(scope, delay.coerceAtLeast(1), period, host.ref(lua, 4)).toLong())
                1
            }
        )
        put(
            "timer.cancel",
            Primitive { lua ->
                scripts.cancel(lua.toInteger(1).toInt())
                0
            }
        )
        put(
            "timer.active",
            Primitive { lua ->
                lua.push(scripts.isScheduled(lua.toInteger(1).toInt()))
                1
            }
        )
        put(
            "testing",
            Primitive { lua ->
                // Whether the script test runner runs this project: only then does `nf` have `nf.test`.
                lua.push(session.config.testing != null)
                1
            }
        )
        put(
            "gated",
            Primitive { lua ->
                // The server's version, then (kind, key, since) for each gate it's older than.
                val running = session.platform.info.minecraftVersion
                val server = MinecraftVersion.parse(running)
                lua.push(running)
                var count = 1
                val gates = session.versionGates
                for ((kind, entries) in listOf("functions" to gates.functions, "events" to gates.events, "options" to gates.options)) {
                    for ((key, since) in entries) {
                        if (server != null && MinecraftVersion.of(since) <= server) continue
                        lua.push(kind)
                        lua.push(key)
                        lua.push(since)
                        count += 3
                    }
                }
                count
            }
        )
        put(
            "events.check",
            Primitive { lua ->
                // (class, event, field, value, package): a handler assigning a writable field, read as it will be
                // read back, in the package the event's fields are spelled for now.
                val event = Events.of(lua.toString(1)!!, lua.toString(2)!!)
                val field = lua.toString(3)!!
                val call = LuaCall(host, lua)
                try {
                    host.readingAs(lua.toString(5)) { event?.payload?.check(call, field, 4, "event.$field") }
                } catch (e: LuaTypeMismatch) {
                    throw LuaApiException(call.where(2) + "bad value for ${e.where} (${e.expected} expected, got ${e.got})")
                } catch (e: LuaApiException) {
                    throw LuaApiException(call.where(2) + e.message)
                }
                0
            }
        )
        put(
            "events.listening",
            Primitive { lua ->
                // (target or nil for nf, event, handlers now, their rates as "rate:phase …" or nil when one runs every time)
                val target = if (lua.isNil(1)) null else host.handleAt(lua, 1)
                val rated = if (lua.isString(4)) {
                    lua.toString(4)!!.split(' ').filter { it.isNotEmpty() }.flatMap { it.split(':').map(String::toLong) }.toLongArray()
                } else {
                    null
                }
                scripts.listeningChanged(target, lua.toString(2)!!, lua.toInteger(3).toInt(), rated)
                0
            }
        )
        put(
            "events.phase",
            Primitive { lua ->
                // (handle): where its `every` handlers fall among the ticks, the same for every handler on it and
                // its parts, and spread over instances so they don't all run on the same tick.
                lua.push(Scripts.phaseOf(host.handleAt(lua, 1)))
                1
            }
        )
        put(
            "events.failed",
            Primitive { lua ->
                // (scope, what, message, file, line, traceback, gave up)
                val scope = scripts.scope(lua.toInteger(1).toInt())
                if (scope != null) {
                    val failure = ScriptFailure(
                        message = lua.toString(3) ?: "error",
                        file = if (lua.isString(4)) lua.toString(4) else null,
                        line = if (lua.isNumber(5)) lua.toInteger(5).toInt() else null,
                        traceback = if (lua.isString(6)) lua.toString(6) else null
                    )
                    scripts.handlerFailed(scope, lua.toString(2)!!, failure, lua.toBoolean(7))
                }
                0
            }
        )
        put(
            "events.alive",
            Primitive { lua ->
                // (handle): whether its thing is still there to listen to.
                lua.push(session.alive(host.handleAt(lua, 1)))
                1
            }
        )
        put(
            "events.calling",
            Primitive { lua ->
                // (): the package whose code is running, for a custom event's `from`.
                lua.push(session.names.calling())
                1
            }
        )
        put(
            "async.cancel",
            Primitive { lua ->
                // (wait id): the task waiting on an async function ended, so nothing is delivered.
                session.async.cancel(lua.toInteger(1).toInt())
                0
            }
        )
        put(
            "goals.add",
            Primitive { lua ->
                // (scope, mob, id, priority, controls or nil, then should_start, should_continue, start,
                // tick and stop, each a function or nil): the hand-written Mob.add_goal, which checked
                // the definition's shape.
                val scope = lua.scope(1)
                val self = host.handleAt(lua, 2) as LuaHandle.Mob
                val controls = if (lua.isNoneOrNil(5)) emptyList() else goalControls(host.json(lua, 5, "definition.controls"))
                fun kept(index: Int) = if (lua.isFunction(index)) host.ref(lua, index) else null
                val callbacks = MobGoals.Callbacks(kept(6), kept(7), kept(8), kept(9), kept(10))
                lua.push(mob.addGoal(scope, self, lua.toString(3)!!, lua.toInteger(4), controls, callbacks))
                1
            }
        )
    }
}

/** A goal definition's `controls`, read as JSON: a list of strings (an empty table is the empty list). */
private fun goalControls(json: JsonElement): List<String> {
    val bad = { LuaApiException("definition.controls must be a list of controls, like { \"move\", \"look\" }") }
    return when (json) {
        is JsonObject -> if (json.isEmpty()) emptyList() else throw bad()
        is JsonArray -> json.map { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content ?: throw bad() }
        else -> throw bad()
    }
}

/** [notInProject] with [known] (names as the server knows them) as the calling script's package writes them. */
internal fun ProjectSession.notInProject(kind: String, name: String, known: List<String>): String =
    dev.netherforge.plugin.binding.notInProject(kind, name, known.map(names::spell))
