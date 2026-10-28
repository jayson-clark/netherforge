package dev.netherforge.plugin.cutscene

import dev.netherforge.format.Severity
import dev.netherforge.format.centity.TextDisplay
import dev.netherforge.format.cutscene.CameraPose
import dev.netherforge.format.cutscene.CompiledCue
import dev.netherforge.format.cutscene.CompiledCutscene
import dev.netherforge.format.cutscene.CutsceneKind
import dev.netherforge.format.math.Angles
import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.project.PathRole
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.binding.CutsceneCueEvent
import dev.netherforge.plugin.binding.CutsceneEndEvent
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.notInProject
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.DisplayLook
import dev.netherforge.plugin.platform.DisplayPose
import dev.netherforge.plugin.platform.EntityFlag
import dev.netherforge.plugin.platform.EntityNumber
import dev.netherforge.plugin.platform.EntityRole
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject
import dev.netherforge.plugin.session.TickPhase
import dev.netherforge.plugin.session.liveness
import dev.netherforge.plugin.store.Store
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The project's cutscenes and every one playing.
 *
 * A cutscene is a camera path. Playing one for a player makes a display
 * entity (the camera), puts the player in spectator mode looking through it,
 * and moves it each tick along the path with the display's own teleport
 * interpolation (one tick, so the client draws the movement between the
 * server's ticks). The server moves a spectating player to the entity every
 * tick, so the player's own chunks follow the camera. Spectator mode is what
 * stops them moving and interacting.
 *
 * What the player was (where, their game mode, whether they could fly) is
 * [PlayerState], put back when the cutscene ends **however it ends**: it
 * finishes or is stopped or skipped, the player quits ([playerQuit]), the
 * script that played it unloads ([scopeReleased]), the session ends
 * ([stop]: a reload, the server stopping). A cutscene played over another
 * keeps the first state, so a chain never flashes the world.
 *
 * A cutscene belongs to the scope that played it. A reload of its file
 * leaves the ones playing on the version they started with; the next play
 * uses the new one.
 */
class Cutscenes(
    private val platform: Platform,
    private val scripts: Scripts,
    private val log: RuntimeLog,
    /** Where what a player was is kept while a cutscene holds them, so a crash can't lose it. */
    private val states: Store.CutsceneStates,
    /** Every file the project and its packages have, by project and package path: which cutscenes have a file. */
    private val projectFiles: () -> Set<String> = { emptySet() }
) : RuntimeService {
    override val name get() = "cutscenes"

    private val definitions = HashMap<String, CompiledCutscene>()

    /** Every cutscene file the project has, with the first error of one that has errors (null when it's valid). */
    private var files: Map<String, String?> = emptyMap()

    private val active = LinkedHashMap<Long, ActiveCutscene>()
    private val byPlayer = HashMap<UUID, ActiveCutscene>()
    private var nextNumber = 1L

    /** What players a cutscene held when the server went down were before it, until they join and are put back. */
    private val leftovers = HashMap<UUID, PlayerState>()

    fun ids(): List<String> = files.keys.sorted()

    fun find(number: Long): ActiveCutscene? = active[number]

    /** What [player] is watching now. */
    fun of(player: UUID): ActiveCutscene? = byPlayer[player]

    /** The length of [kind] (seconds) as the project defines it, or null. */
    fun lengthOf(kind: String): Double? = definitions[kind]?.length

    override fun define(project: SessionProject) {
        definitions.clear()
        definitions.putAll(project.running(CutsceneKind))
        files = if (project.refused) emptyMap() else cutsceneFiles(project.snapshot)
    }

    override fun start() {
        leftovers.putAll(states.all())
    }

    /** A player the server lost mid-cutscene is put back before scripts hear they joined. */
    override fun playerJoined(player: PlayerRef) {
        val state = leftovers.remove(player.uuid) ?: return
        log.warn("${player.name} was in a cutscene when the server stopped; putting them back")
        restore(player.uuid, state)
    }

    override fun tick(phase: TickPhase) {
        if (phase == TickPhase.EFFECTS) pass()
    }

    override fun scopeReleased(scope: Scope) = stopOwned(scope.id)

    /** The player is leaving (or has): they're put back while the server still has them. */
    override fun playerQuit(player: PlayerRef) {
        byPlayer[player.uuid]?.let { end(it, EndReason.PLAYER_LEFT) }
    }

    /** The session ending: every player is put back before anything is torn down. */
    override fun stop() {
        for (cutscene in active.values.toList()) end(cutscene, EndReason.UNLOADED)
    }

    override fun costs(): Map<String, (Scope) -> Int> = mapOf("cutscenes" to { scope -> active.values.count { it.owner == scope.id } })

    override fun liveness() = listOf(liveness<LuaHandle.Cutscene> { find(it.number) != null })

    override val reloads: Set<KindSpec<*, *>> get() = setOf(CutsceneKind)

    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        for (id in ids) {
            val exists = batch.exists(Resource(CutsceneKind, id))
            val next = batch.snapshot.running(CutsceneKind)[id]
            // A file with errors keeps its last good version; noted, so playing one that never had one can say why.
            if (next == null && exists) reload(id, null, exists = true, problem = cutsceneProblem(batch.snapshot, id))
            batch.resource(Resource(CutsceneKind, id), next) { reload(id, it, exists, null) }
        }
    }

    /** Takes [next] as [id]'s definition (null: its file has errors, [problem] the first, or it's gone, [exists] false). Cutscenes playing keep theirs. */
    fun reload(id: String, next: CompiledCutscene?, exists: Boolean, problem: String?): Int {
        files = if (exists) files + (id to problem.takeIf { next == null }) else files - id
        if (next != null) {
            definitions[id] = next
        } else if (!exists) {
            definitions.remove(id)
        }
        return 0
    }

    private fun cutsceneFiles(snapshot: ProjectSnapshot): Map<String, String?> = projectFiles().mapNotNull { path ->
        val found = Kinds.classify(path)?.takeIf { it.kind == CutsceneKind.id && it.role == PathRole.MAIN }
        found?.id?.let { id -> (found.pkg?.let { PackagePaths.of(it, id) } ?: id).let { it to cutsceneProblem(snapshot, it) } }
    }.toMap()

    private fun cutsceneProblem(snapshot: ProjectSnapshot, id: String): String? {
        val resource = Resource(CutsceneKind, id)
        return snapshot.problems.firstOrNull { it.severity == Severity.ERROR && Resource.mainOf(it.file) == resource }?.message
    }

    /** What `nf.cutscenes.play` takes besides the player and the cutscene. */
    data class PlayOptions(
        /** Added to every position of the path. */
        val origin: Vec3 = Vec3.ZERO,
        /** The world the cutscene is in: the player's when null. */
        val world: String? = null,
        val skippable: Boolean? = null
    )

    /**
     * Starts [kind] for [player], for [owner] (null: the runtime). Null when
     * they can't watch one now: offline, dead, or the camera couldn't be made
     * (the player is then as they were). A cutscene the project doesn't have,
     * or one that never had a good version, is the script's mistake.
     */
    fun play(kind: String, player: UUID, owner: Int?, options: PlayOptions = PlayOptions()): ActiveCutscene? {
        val definition = definitions[kind]
        if (definition == null) {
            val problem = files[kind] ?: throw LuaApiException(notInProject("cutscene", kind, ids()))
            throw LuaApiException("cutscene \"$kind\" has errors, and there's no earlier version to play: $problem")
        }
        val here = platform.players.location(player) ?: return null
        if (platform.worldEntities.number(player, EntityNumber.HEALTH)?.let { it <= 0.0 } == true) return null
        val world = options.world ?: here.world
        if (!platform.worlds.exists(world)) return null

        // The state to put back is the player's before the first of a run of cutscenes.
        val previous = byPlayer[player]
        val saved = previous?.saved ?: capture(player, here) ?: return null
        val pose = definition.poseAt(0.0)
        val start = locationOf(world, pose, options.origin)
        // The camera's chunk is held while it's there: one nothing holds unloads at the end of the tick, before the
        // player carried to it has their own chunks round it, and a camera that can't be reached lets them go.
        val held = chunkOf(start)
        platform.worlds.holdChunk(world, held.first, held.second)
        val id = UUID.randomUUID()

        // Whatever else they were doing: windows closed, a ride left, then the camera made and looked through.
        if (previous != null) retire(previous)
        platform.players.closeInventory(player)
        platform.worldEntities.vehicle(player)?.let { platform.worldEntities.removePassenger(it, player) }
        val camera = platform.entities.spawnDisplay(
            start,
            // Nothing drawn: no words, no background. Other players see nothing where it is.
            TextDisplay("", background = INVISIBLE),
            DisplayPose(Matrix4(), 0, 0.0),
            EntityTag(id, CAMERA, EntityRole.DISPLAY)
        )
        if (camera != null) {
            // Saved with its chunk it could outlive a crash; the look makes the client tween each move over a tick.
            platform.entities.setPersistent(camera, false)
            platform.entities.setLook(camera, DisplayLook(teleportTicks = 1))
        }
        if (camera == null || !look(player, camera)) {
            camera?.let { platform.entities.remove(it) }
            platform.worlds.releaseChunk(world, held.first, held.second)
            // The cutscene they were in (if any) is gone too, so the state is theirs to have back.
            restore(player, saved)
            previous?.let { announce(it, EndReason.REPLACED) }
            return null
        }
        val cutscene = ActiveCutscene(
            number = nextNumber++,
            kind = kind,
            owner = owner,
            player = player,
            definition = definition,
            world = world,
            origin = options.origin,
            skippable = options.skippable ?: definition.skippable,
            saved = saved,
            camera = camera,
            id = id
        )
        cutscene.held = held
        active[cutscene.number] = cutscene
        byPlayer[player] = cutscene
        // Kept (a run of cutscenes keeps the first) until they're put back, in case the server goes down meanwhile.
        if (previous == null) states.put(player, saved)
        // The one it replaced is told only now, with the new one already playing: a handler that plays or stops again finds things whole.
        previous?.let { announce(it, EndReason.REPLACED) }
        return cutscene
    }

    /** Ends [cutscene] now, with [reason], and puts the player back. False when it had already ended. */
    fun stop(cutscene: ActiveCutscene, reason: EndReason = EndReason.STOPPED): Boolean {
        if (cutscene.ended) return false
        end(cutscene, reason)
        return true
    }

    /** Ends every cutscene [scope] played: it unloaded. */
    private fun stopOwned(scope: Int) {
        for (cutscene in active.values.filter { it.owner == scope }) end(cutscene, EndReason.UNLOADED)
    }

    // ---- the player ---------------------------------------------------------------

    /** What to put back, or null for a player who isn't there. */
    private fun capture(player: UUID, at: Location): PlayerState? {
        val mode = platform.players.gameMode(player) ?: return null
        val entities = platform.worldEntities
        return PlayerState(
            location = at,
            gameMode = mode,
            canFly = entities.flag(player, EntityFlag.CAN_FLY) ?: false,
            flying = entities.flag(player, EntityFlag.FLYING) ?: false,
            spectating = platform.playerViews.camera(player)
        )
    }

    /** Spectator mode, looking through [camera]. False when the server wouldn't. */
    private fun look(player: UUID, camera: UUID): Boolean {
        if (platform.players.gameMode(player) != SPECTATOR) platform.players.setGameMode(player, SPECTATOR)
        // A script may have refused the change, or the server the camera.
        return platform.players.gameMode(player) == SPECTATOR && platform.playerViews.setCamera(player, camera)
    }

    /**
     * Puts [player] back as [state] says, each step on its own: one the server
     * refuses (a player already gone) doesn't keep the others from happening.
     * The camera is let go first: a teleport or a game mode change with one
     * still on would carry them with it.
     */
    private fun restore(player: UUID, state: PlayerState) {
        // Whatever the server does next, nothing is left to put back.
        states.delete(player)
        attempt("let go of the camera") { platform.playerViews.setCamera(player, null) }
        attempt("teleport back") { platform.players.teleport(player, state.location) }
        attempt("restore the game mode") { platform.players.setGameMode(player, state.gameMode) }
        // A game mode change decides what they may do, so what they were allowed is said after it.
        attempt("restore flying") {
            platform.worldEntities.setFlag(player, EntityFlag.CAN_FLY, state.canFly)
            platform.worldEntities.setFlag(player, EntityFlag.FLYING, state.flying && state.canFly)
        }
        state.spectating?.let { entity ->
            if (state.gameMode == SPECTATOR) attempt("look through what they did") { platform.playerViews.setCamera(player, entity) }
        }
    }

    private fun attempt(what: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            log.error("Couldn't $what for a cutscene's player", e)
        }
    }

    // ---- ending ---------------------------------------------------------------------

    /** Takes [cutscene] out of play without telling anyone: its camera goes, and the player isn't put back. */
    private fun retire(cutscene: ActiveCutscene) {
        cutscene.ended = true
        active.remove(cutscene.number)
        if (byPlayer[cutscene.player] === cutscene) byPlayer.remove(cutscene.player)
        if (cutscene.textUntil >= 0) platform.players.clearTitle(cutscene.player)
        attempt("remove the camera") { platform.entities.remove(cutscene.camera) }
        cutscene.held?.let { (x, z) -> platform.worlds.releaseChunk(cutscene.world, x, z) }
        cutscene.held = null
    }

    /**
     * Ends [cutscene]: the player is put back first (so a handler that moves
     * them has the last word), then its `end` handlers hear it while the
     * handle still answers, then it's gone and so is every handler on it.
     */
    private fun end(cutscene: ActiveCutscene, reason: EndReason) {
        if (cutscene.ended) return
        retire(cutscene)
        restore(cutscene.player, cutscene.saved)
        announce(cutscene, reason)
    }

    /** Raises `end` for a cutscene that's already out of play, and drops what listened to it. */
    private fun announce(cutscene: ActiveCutscene, reason: EndReason) {
        val handle = cutscene.handle
        // Answers `is_active` for the handlers while they hear it.
        active[cutscene.number] = cutscene
        try {
            scripts.emit(
                Events.CUTSCENE_END,
                handle,
                CutsceneEndEvent(handle, LuaHandle.Player(cutscene.player.toString()), reason.luaName)
            )
        } finally {
            active.remove(cutscene.number)
            scripts.dropTarget(handle)
        }
    }

    // ---- the pass -------------------------------------------------------------------

    /** One pass over every playing cutscene, once a server tick after the world has moved. */
    private fun pass() {
        for (cutscene in active.values.toList()) {
            if (cutscene.ended) continue
            try {
                step(cutscene)
            } catch (e: Exception) {
                // One cutscene's failure mustn't leave its player stuck in the camera.
                log.error("A cutscene (${cutscene.kind}) failed, so it was ended", e)
                end(cutscene, EndReason.STOPPED)
            }
        }
    }

    private fun step(cutscene: ActiveCutscene) {
        val player = cutscene.player
        if (platform.players.get(player) == null) return end(cutscene, EndReason.PLAYER_LEFT)
        // Someone changed their game mode, or the camera's gone: the cutscene no longer holds them.
        if (platform.players.gameMode(player) != SPECTATOR || !platform.entities.isLoaded(cutscene.camera)) {
            return end(cutscene, EndReason.STOPPED)
        }
        // They pressed sneak, which makes the server let go of the camera: a skip, or a hold.
        if (platform.playerViews.camera(player) != cutscene.camera) {
            if (cutscene.skippable) return end(cutscene, EndReason.SKIPPED)
            if (!platform.playerViews.setCamera(player, cutscene.camera)) return end(cutscene, EndReason.STOPPED)
        }
        cutscene.tick++
        val ticks = ceil(cutscene.definition.length * ActiveCutscene.TICKS_PER_SECOND - EPSILON).toInt()
        // One tick past the last pose, so the client has drawn the camera arriving.
        if (cutscene.tick > ticks) return end(cutscene, EndReason.FINISHED)
        val pose = cutscene.definition.poseAt(cutscene.time)
        val to = locationOf(cutscene.world, pose, cutscene.origin)
        // The next chunk is held before the camera goes into it, then the last let go.
        val chunk = chunkOf(to)
        if (chunk != cutscene.held) {
            platform.worlds.holdChunk(cutscene.world, chunk.first, chunk.second)
            cutscene.held?.let { (x, z) -> platform.worlds.releaseChunk(cutscene.world, x, z) }
            cutscene.held = chunk
        }
        platform.entities.teleport(cutscene.camera, to)
        cues(cutscene)
        if (!cutscene.ended && cutscene.textUntil in 0..cutscene.tick) {
            cutscene.textUntil = -1
            platform.players.clearTitle(player)
        }
    }

    private fun cues(cutscene: ActiveCutscene) {
        val cues = cutscene.definition.cues
        val now = cutscene.tick / ActiveCutscene.TICKS_PER_SECOND + EPSILON
        while (!cutscene.ended && cutscene.nextCue < cues.size && cues[cutscene.nextCue].time <= now) {
            fire(cutscene, cues[cutscene.nextCue++])
        }
    }

    private fun fire(cutscene: ActiveCutscene, cue: CompiledCue) {
        cue.text?.let { text ->
            val stay = ceil(cue.duration * ActiveCutscene.TICKS_PER_SECOND).toInt()
            platform.players.title(cutscene.player, "", text, 0, stay, TEXT_FADE_OUT)
            cutscene.textUntil = cutscene.tick + stay
        }
        cue.event?.let { event ->
            val handle = cutscene.handle
            if (scripts.listening(handle, Events.CUTSCENE_CUE)) {
                scripts.emit(Events.CUTSCENE_CUE, handle, CutsceneCueEvent(handle, LuaHandle.Player(cutscene.player.toString()), event))
            }
        }
    }

    /** The chunk (x, z) a location is in. */
    private fun chunkOf(at: Location) = (floor(at.x).toInt() shr 4) to (floor(at.z).toInt() shr 4)

    private fun locationOf(world: String, pose: CameraPose, origin: Vec3) = Location(
        world,
        pose.position.x + origin.x,
        pose.position.y + origin.y,
        pose.position.z + origin.z,
        Angles.normalize(pose.yaw),
        pose.pitch
    )

    private companion object {
        const val SPECTATOR = "spectator"

        /** The camera's name in the tag it carries, which tells it from a centity's nodes. */
        const val CAMERA = "cutscene:camera"

        /** `#AARRGGBB`: no colour at all. */
        const val INVISIBLE = "#00000000"

        /** Ticks a cue's text fades out over. */
        const val TEXT_FADE_OUT = 10

        /** Keeps a time that is a whole tick from rounding up to the next. */
        const val EPSILON = 1e-9
    }
}
