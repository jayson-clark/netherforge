package dev.netherforge.plugin.particle

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.Severity
import dev.netherforge.format.bridge.ScriptError
import dev.netherforge.format.bridge.SourceRef
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.particle.CompiledEffect
import dev.netherforge.format.particle.EffectFrame
import dev.netherforge.format.particle.EffectSampler
import dev.netherforge.format.particle.EffectSpawn
import dev.netherforge.format.particle.ParticleEffectKind
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.project.PathRole
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.binding.EffectEndEvent
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.notInProject
import dev.netherforge.plugin.centity.Centities
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.ParticleSpawn
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
import java.util.UUID
import kotlin.random.Random

/** Why an effect ended, as `EffectEndEvent.reason` says it. */
enum class EndReason(val luaName: String) {
    FINISHED("finished"),
    STOPPED("stopped"),
    TARGET_GONE("target_gone"),
    REMOVED("removed"),
    UNLOADED("unloaded")
}

/**
 * The project's particle effects and every one playing.
 *
 * Definitions come from the snapshot: the compiled (valid) effects, keeping
 * the last good one while a file has errors. An effect belongs to the scope
 * that played it and ends with it ([stopOwned]); one the editor played over
 * the bridge belongs to the session ([owner] null) and ends when the project
 * restarts or the editor stops it.
 *
 * [tick] is one pass a server tick, after the centity pipeline's sync, so a
 * centity moved this tick is where its effect plays this tick. Per effect,
 * round-robin: follow its target, step its sampler (always, so its time is
 * wall time), work out who's near enough to see it, and send its spawns in
 * world space through [dev.netherforge.plugin.platform.ParticleOps.spawn].
 * The pass is pure Kotlin; it never raises into Lua (except the `end` event's
 * handlers, which are called like any handler).
 */
class ParticleEffects(
    private val platform: Platform,
    private val scripts: Scripts,
    private val log: RuntimeLog,
    /** The centities effects follow (made after this, so asked for when needed). */
    centities: () -> Centities,
    /** Every file the project and its packages have, by project and package path: which effects have a file. */
    private val projectFiles: () -> Set<String> = { emptySet() },
    /** The budget's warning came or went: the problems changed. */
    private val problemsChanged: () -> Unit = {}
) : RuntimeService {
    override val name get() = "particle effects"

    private val centities by lazy(centities)

    /**
     * The warning the pass's budget has in the problems while it skips effects
     * (on the first skipped effect's file), at most once every
     * [BUDGET_WARNING_TICKS] while it goes on, gone once nothing has been
     * skipped for that long (or everything stopped).
     */
    private var budgetProblem: Problem? = null

    private val definitions = HashMap<String, CompiledEffect>()

    /** Every effect file the project has, with the first error of one that has errors (null when it's valid). */
    private var files: Map<String, String?> = emptyMap()

    private val active = LinkedHashMap<Long, ActiveEffect>()
    private var nextNumber = 1L

    /** How far along the active effects the next pass starts, so the same ones aren't always the ones a full budget skips. */
    private var rotation = 0L
    private var ticks = 0L
    private var lastBudgetWarning = Long.MIN_VALUE
    private var lastSkip = Long.MIN_VALUE
    private var skippedSinceWarning = 0
    private val skippedKinds = LinkedHashSet<String>()

    /** When a platform failure spawning each kind was last logged. */
    private val failureLogged = HashMap<String, Long>()

    fun ids(): List<String> = files.keys.sorted()

    fun find(number: Long): ActiveEffect? = active[number]

    fun all(): List<ActiveEffect> = active.values.toList()

    /** Takes every definition: the valid effects, and every effect file with its first error (null for a valid one). */
    override fun define(project: SessionProject) {
        definitions.clear()
        definitions.putAll(project.running(ParticleEffectKind))
        files = if (project.refused) emptyMap() else effectFiles(project.snapshot)
    }

    override fun tick(phase: TickPhase) {
        if (phase == TickPhase.EFFECTS) pass()
    }

    /** A scope stopped: what it played ends `unloaded`. */
    override fun scopeReleased(scope: Scope) = stopOwned(scope.id)

    /** Last in: what the editor played, and anything a script's unload didn't end. */
    override fun stop() = stopAll()

    override fun costs(): Map<String, (Scope) -> Int> = mapOf("effects" to { scope -> countOwned(scope.id) })

    override fun liveness() = listOf(liveness<LuaHandle.Effect> { find(it.number) != null })

    override fun problems(): List<Problem> = listOfNotNull(budgetProblem)

    override val reloads: Set<KindSpec<*, *>> get() = setOf(ParticleEffectKind)

    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        for (id in ids) {
            val exists = batch.exists(Resource(ParticleEffectKind, id))
            val next = batch.snapshot.running(ParticleEffectKind)[id]
            // A file with errors keeps its last good version playing; noted, so playing one that never had one can say why.
            if (next == null && exists) reload(id, null, exists = true, problem = effectProblem(batch.snapshot, id))
            batch.resource(Resource(ParticleEffectKind, id), next) { reload(id, it, exists, null) }
        }
    }

    /** Every particle effect file the project and its packages have, with the first error of each that has errors. */
    private fun effectFiles(snapshot: ProjectSnapshot): Map<String, String?> = projectFiles().mapNotNull { path ->
        val found = Kinds.classify(path)?.takeIf { it.kind == ParticleEffectKind.id && it.role == PathRole.MAIN }
        found?.id?.let { id -> (found.pkg?.let { PackagePaths.of(it, id) } ?: id).let { it to effectProblem(snapshot, it) } }
    }.toMap()

    /** The first error in [id]'s effect file, or null when it has none. */
    private fun effectProblem(snapshot: ProjectSnapshot, id: String): String? {
        val resource = Resource(ParticleEffectKind, id)
        return snapshot.problems.firstOrNull { it.severity == Severity.ERROR && Resource.mainOf(it.file) == resource }?.message
    }

    /**
     * Reloads one effect: [next] is its new definition, null when its file has
     * errors ([problem], the first) or is gone ([exists] false). A valid file
     * moves every live effect of it onto the new definition at its next tick
     * (its tick and same-named emitters' accumulators carry over); a file with
     * errors leaves the last good definition playing; a deleted one ends its
     * live effects with `"removed"`. Returns how many live effects moved over.
     */
    fun reload(id: String, next: CompiledEffect?, exists: Boolean, problem: String?): Int {
        files = if (exists) files + (id to problem.takeIf { next == null }) else files - id
        if (next != null) {
            definitions[id] = next
            val live = active.values.filter { it.kind == id }
            for (effect in live) effect.sampler.redefine(next)
            return live.size
        }
        if (!exists) {
            definitions.remove(id)
            for (effect in active.values.filter { it.kind == id }) end(effect, EndReason.REMOVED)
        }
        return 0
    }

    /**
     * Starts [kind] at [at] (its yaw and pitch turn it), for [owner] (null: the
     * runtime). Null when [at]'s world isn't loaded. A kind the project doesn't
     * have, one that never had a good version, a [scale] out of range or too
     * many effects playing is the script's mistake.
     */
    fun play(kind: String, at: Location, owner: Int?, options: PlayOptions = PlayOptions()): ActiveEffect? {
        val definition = definitions[kind]
        if (definition == null) {
            val problem = files[kind] ?: throw LuaApiException(notInProject("particle effect", kind, ids()))
            throw LuaApiException("particle effect \"$kind\" has errors, and there's no earlier version to play: $problem")
        }
        if (active.size >= MAX_ACTIVE) {
            throw LuaApiException("too many particle effects playing ($MAX_ACTIVE): stop the ones you're done with")
        }
        val scale = options.scale ?: 1.0
        if (!(scale > 0 && scale <= MAX_SCALE)) {
            throw LuaApiException(
                "options.scale must be more than 0 and at most $MAX_SCALE, not $scale"
            )
        }
        if (options.offset != null && options.follow == null) throw LuaApiException("options.offset only goes with options.follow")
        if (!platform.worlds.exists(at.world)) return null
        val effect = ActiveEffect(
            number = nextNumber++,
            kind = kind,
            owner = owner,
            sampler = EffectSampler(definition, Random.nextLong()),
            world = at.world,
            origin = Vec3(at.x, at.y, at.z),
            yaw = at.yaw,
            pitch = at.pitch,
            scale = scale,
            viewers = options.viewers,
            loopOverride = options.loop
        )
        options.follow?.let { follow(effect, it, options.offset) }
        active[effect.number] = effect
        return effect
    }

    /** What `nf.particles.play` takes besides the effect and the place. */
    data class PlayOptions(
        val viewers: Set<UUID>? = null,
        val scale: Double? = null,
        val loop: Boolean? = null,
        val follow: FollowTarget? = null,
        val offset: Vec3? = null
    )

    /** Moves [effect] to [to] (its facing too) and stops it following anything. False once it has ended. */
    fun teleport(effect: ActiveEffect, to: Location): Boolean {
        if (effect.ended) return false
        effect.follow = null
        effect.offset = Vec3.ZERO
        effect.world = to.world
        effect.origin = Vec3(to.x, to.y, to.z)
        effect.yaw = to.yaw
        effect.pitch = to.pitch
        return true
    }

    /** Makes [effect] follow [target] from the next pass on. False once it has ended. */
    fun follow(effect: ActiveEffect, target: FollowTarget, offset: Vec3?): Boolean {
        if (effect.ended) return false
        effect.follow = target
        effect.offset = offset ?: Vec3.ZERO
        return true
    }

    /** Ends [effect] now, with [reason]. False when it had already ended. */
    fun stop(effect: ActiveEffect, reason: EndReason = EndReason.STOPPED): Boolean {
        if (effect.ended) return false
        end(effect, reason)
        return true
    }

    /** How many effects [scope] played are still playing: what `/nf scripts` shows as its effects. */
    fun countOwned(scope: Int): Int = active.values.count { it.owner == scope }

    /** Ends every effect [scope] played: it unloaded. */
    fun stopOwned(scope: Int) {
        for (effect in active.values.filter { it.owner == scope }) end(effect, EndReason.UNLOADED)
    }

    /** Ends every effect the runtime played (the editor's "Play on server"); returns how many. */
    fun stopUnowned(): Int {
        val doomed = active.values.filter { it.owner == null }
        for (effect in doomed) end(effect, EndReason.STOPPED)
        return doomed.size
    }

    /** Ends everything (the project stopping). */
    private fun stopAll() {
        for (effect in active.values.toList()) end(effect, EndReason.UNLOADED)
        budgetRecovered()
    }

    /**
     * Ends [effect]: its `end` handlers hear it while the handle still
     * answers (`is_active()` is true, `location()` says where it was, and
     * `nf.wait_for` hands the event back; `stop()` is false, since it's
     * already ending), then it's gone and so is every handler on it.
     */
    private fun end(effect: ActiveEffect, reason: EndReason) {
        if (effect.ended) return
        effect.ended = true
        val handle = effect.handle
        try {
            scripts.emit(Events.EFFECT_END, handle, EffectEndEvent(handle, reason.luaName))
        } finally {
            active.remove(effect.number)
            scripts.dropTarget(handle)
        }
    }

    // ---- the pass ----------------------------------------------------------------

    /** One pass over every playing effect. Once a server tick, after the centities' sync. */
    private fun pass() {
        ticks++
        if (lastSkip != Long.MIN_VALUE && ticks - lastSkip >= BUDGET_WARNING_TICKS) budgetRecovered()
        if (active.isEmpty()) return
        val effects = active.values.toList()
        val start = (rotation++ % effects.size).toInt()
        val online = platform.players.online()
        var sent = 0
        val skipped = ArrayList<String>()
        for (k in effects.indices) {
            val effect = effects[(start + k) % effects.size]
            if (effect.ended) continue
            // 1. Follow: a target that's gone ends the effect.
            effect.follow?.let { target ->
                if (!moveToTarget(effect, target)) {
                    end(effect, EndReason.TARGET_GONE)
                    return@let
                }
            }
            if (effect.ended) continue
            // 2. Step, always: its time is the server's, watched or not.
            val spawns = effect.sampler.step(effect.loop)
            // 3–4. Who's near enough, then send, within the pass's budget.
            if (spawns.isNotEmpty()) {
                if (sent + spawns.size > MAX_POINTS_PER_TICK) {
                    skipped += effect.kind
                } else if (send(effect, spawns, online)) {
                    sent += spawns.size
                }
            }
            // 5. A timeline that has played out ends.
            if (effect.sampler.finished) end(effect, EndReason.FINISHED)
        }
        if (skipped.isNotEmpty()) budgetExceeded(skipped)
    }

    /** Puts [effect] where [target] is now, facing its yaw. False when the target is gone. */
    private fun moveToTarget(effect: ActiveEffect, target: FollowTarget): Boolean {
        val (world, position, yaw) = where(target) ?: return false
        effect.world = world
        effect.yaw = yaw
        effect.pitch = 0.0
        effect.origin = position + EffectFrame(Vec3.ZERO, yaw).direction(effect.offset)
        return true
    }

    /** A target's world, position and yaw, or null once it's gone. */
    private fun where(target: FollowTarget): Triple<String, Vec3, Double>? = when (target) {
        is FollowTarget.Centity -> centities.find(target.id)?.takeIf { !it.removed }?.let { instance ->
            val anchor = instance.anchor
            Triple(anchor.world, Vec3(anchor.x, anchor.y, anchor.z), instance.yaw)
        }
        is FollowTarget.Node -> centities.find(target.centity)?.takeIf { !it.removed }?.let { instance ->
            val index = instance.indexOf(target.name) ?: return@let null
            Triple(instance.anchor.world, instance.worldMatrix(index).translation(), instance.yaw)
        }
        is FollowTarget.Player -> platform.players.get(target.uuid)?.let { platform.players.location(it.uuid) }?.let {
            Triple(it.world, Vec3(it.x, it.y, it.z), it.yaw)
        }
        is FollowTarget.Entity -> platform.worldEntities.info(target.uuid)?.location?.let {
            Triple(it.world, Vec3(it.x, it.y, it.z), it.yaw)
        }
    }

    /**
     * Sends [spawns] (effect space) to everyone in range of [effect]: 32 blocks,
     * 128 for a `force` emitter's, of its viewers when it has them. Nobody in
     * range sends nothing. False when nothing was sent.
     */
    private fun send(effect: ActiveEffect, spawns: List<EffectSpawn>, online: List<PlayerRef>): Boolean {
        val ops = platform.particles
        val origin = effect.origin
        fun inRange(range: Double): List<PlayerRef> = online.filter { player ->
            if (effect.viewers != null && player.uuid !in effect.viewers) return@filter false
            val at = platform.players.location(player.uuid) ?: return@filter false
            val dx = at.x - origin.x
            val dy = at.y - origin.y
            val dz = at.z - origin.z
            at.world == effect.world && dx * dx + dy * dy + dz * dz <= range * range
        }
        val frame = EffectFrame(origin, effect.yaw, effect.pitch, effect.scale)
        val (forced, plain) = spawns.partition { it.force }
        var any = false
        for ((batch, range) in listOf(plain to RANGE, forced to FORCED_RANGE)) {
            if (batch.isEmpty()) continue
            val viewers = inRange(range)
            if (viewers.isEmpty()) continue
            val world = batch.map { spawn ->
                val placed = frame.toWorld(spawn)
                ParticleSpawn(placed.particle, placed.position, placed.count, placed.offset, placed.speed, placed.data, placed.force)
            }
            try {
                ops.spawn(effect.world, world, viewers)
                any = true
            } catch (e: Exception) {
                // One effect's failure mustn't stop the pass; once a minute per kind is enough to say so.
                val last = failureLogged[effect.kind]
                if (last == null || ticks - last >= FAILURE_QUIET_TICKS) {
                    failureLogged[effect.kind] = ticks
                    log.error("Couldn't spawn particle effect ${effect.kind}", e)
                }
            }
        }
        return any
    }

    private fun budgetExceeded(skipped: List<String>) {
        lastSkip = ticks
        skippedSinceWarning += skipped.size
        skippedKinds += skipped
        if (lastBudgetWarning != Long.MIN_VALUE && ticks - lastBudgetWarning < BUDGET_WARNING_TICKS) return
        lastBudgetWarning = ticks
        val message = "particle budget exceeded: $skippedSinceWarning effects skipped (more than $MAX_POINTS_PER_TICK points in a tick, " +
            "from ${skippedKinds.sorted().joinToString()}); stop effects you're done with, or play fewer at once"
        budgetWarning(message, skippedKinds.sorted())
        skippedSinceWarning = 0
        skippedKinds.clear()
    }

    /**
     * The pass skipped effects of [kinds] to keep to its budget: logged and
     * sent to the editor like a slow script, and kept as a warning on the
     * first skipped effect's file until it's over.
     */
    private fun budgetWarning(message: String, kinds: List<String>) {
        // The first skipped effect's file, in whichever group folder it is.
        val file = kinds.firstOrNull()?.let { id ->
            Resource(ParticleEffectKind, id).let { effect -> projectFiles().firstOrNull { Resource.mainOf(it) == effect } }
                ?: ParticleEffectKind.pathOf(id)
        }
        platform.log.warn(message)
        log.send(ScriptError(message, file?.let { SourceRef(it, null) }, null))
        val problem = ProblemCodes.PARTICLES_BUDGET.at(
            file ?: ParticleEffectKind.folder,
            "Too many particles at once: effects are skipped to keep to $MAX_POINTS_PER_TICK points a tick (${kinds.joinToString()})"
        )
        if (budgetProblem != problem) {
            budgetProblem = problem
            problemsChanged()
        }
    }

    private fun budgetRecovered() {
        if (lastSkip == Long.MIN_VALUE) return
        lastSkip = Long.MIN_VALUE
        if (budgetProblem != null) {
            budgetProblem = null
            problemsChanged()
        }
    }

    companion object {
        /** Effects playing on the server at once; one more is an error at `play`. */
        const val MAX_ACTIVE = 1024

        /** Points (spawns) the pass sends in one server tick, across every effect. */
        const val MAX_POINTS_PER_TICK = 4096

        /** The largest `scale` a play takes. */
        const val MAX_SCALE = 16.0

        /** How far the client draws particles that aren't forced, and those that are. */
        const val RANGE = 32.0
        const val FORCED_RANGE = 128.0

        /** The budget warning at most once in this many ticks (ten seconds), and over once none is skipped for as long. */
        const val BUDGET_WARNING_TICKS = 200L

        /** A platform failure spawning one kind is logged at most once a minute. */
        private const val FAILURE_QUIET_TICKS = 1200L
    }
}
