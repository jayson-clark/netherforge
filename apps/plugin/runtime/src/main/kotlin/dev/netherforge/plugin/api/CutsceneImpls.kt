package dev.netherforge.plugin.api
import dev.netherforge.format.cutscene.CutsceneKind
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.CutsceneApi
import dev.netherforge.plugin.binding.CutscenePlayOptions
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.NfCutscenesApi
import dev.netherforge.plugin.binding.place
import dev.netherforge.plugin.cutscene.ActiveCutscene
import dev.netherforge.plugin.cutscene.Cutscenes
import dev.netherforge.plugin.session.ProjectSession

/** `nf.cutscenes`. */
internal class NfCutscenesImpl(private val session: ProjectSession) : NfCutscenesApi {
    override fun play(caller: Caller, player: LuaHandle.Player, cutscene: String, options: CutscenePlayOptions?): LuaHandle.Cutscene? {
        // Resolved first: a mistake in the name is the script's, whoever is watching.
        val kind = session.names.resource(CutsceneKind, cutscene)
        val uuid = player.uuidOrNull() ?: return null
        val origin = options?.origin?.place
        return session.cutscenes.play(
            kind,
            uuid,
            caller.scope.id,
            Cutscenes.PlayOptions(
                origin = origin?.position ?: Vec3.ZERO,
                world = origin?.world?.name,
                skippable = options?.skippable
            )
        )?.handle
    }

    override fun stop(caller: Caller, player: LuaHandle.Player): Boolean {
        val playing = player.uuidOrNull()?.let(session.cutscenes::of) ?: return false
        return session.cutscenes.stop(playing)
    }

    override fun current(caller: Caller, player: LuaHandle.Player): LuaHandle.Cutscene? =
        player.uuidOrNull()?.let(session.cutscenes::of)?.handle
}

/** `Cutscene`: a handle to a playing cutscene, by number; once it has ended, nil and false. */
internal class CutsceneImpl(private val session: ProjectSession) : CutsceneApi {
    private fun cutscene(self: LuaHandle.Cutscene): ActiveCutscene? = session.cutscenes.find(self.number)

    override fun kind(self: LuaHandle.Cutscene): String = session.names.spell(self.kind)

    override fun length(self: LuaHandle.Cutscene): Double = session.cutscenes.lengthOf(self.kind) ?: 0.0

    override fun player(self: LuaHandle.Cutscene): LuaHandle.Player? =
        cutscene(self)?.takeIf { session.platform.players.get(it.player) != null }?.let { LuaHandle.Player(it.player.toString()) }

    override fun isActive(self: LuaHandle.Cutscene): Boolean = cutscene(self) != null

    override fun time(self: LuaHandle.Cutscene): Double? = cutscene(self)?.takeIf { !it.ended }?.time

    override fun stop(self: LuaHandle.Cutscene): Boolean = cutscene(self)?.let { session.cutscenes.stop(it) } == true
}
