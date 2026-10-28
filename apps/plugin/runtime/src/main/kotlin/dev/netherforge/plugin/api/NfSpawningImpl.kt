package dev.netherforge.plugin.api
import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.NfSpawningApi
import dev.netherforge.plugin.lua.LuaFunction
import dev.netherforge.plugin.session.ProjectSession

/** `nf.spawning`: conditions handed to the [NaturalSpawner], owned by the calling script. */
internal class NfSpawningImpl(private val session: ProjectSession) : NfSpawningApi {
    override fun condition(caller: Caller, name: String, callback: LuaFunction) {
        session.naturalSpawner.registerCondition(caller.scope, name, callback)
    }
}
