package dev.netherforge.plugin.api
import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.CommandDefinitionOrFunction
import dev.netherforge.plugin.binding.NfCommandsApi
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaFunction
import dev.netherforge.plugin.session.ProjectSession

/** `nf.commands`: a module's own slash commands, declared through [ProjectCommands]. */
internal class NfCommandsImpl(private val session: ProjectSession) : NfCommandsApi {
    override fun register(caller: Caller, name: String, definition: CommandDefinitionOrFunction?, handler: LuaFunction?): Boolean {
        val scope = caller.scope
        val (declared, run) = when (definition) {
            null -> null to handler
            is CommandDefinitionOrFunction.CommandDefinition -> definition.value to handler
            is CommandDefinitionOrFunction.Function -> {
                if (handler !=
                    null
                ) {
                    throw LuaApiException(
                        "bad argument 'handler' (nil expected after a handler in the definition's place, got function)"
                    )
                }
                null to definition.value
            }
        }
        val registered = session.commands.declare(scope, name, declared, run)
        if (!registered) session.log.warn("${scope.owner.label}: /$name is already a command, so it wasn't declared")
        return registered
    }
}
