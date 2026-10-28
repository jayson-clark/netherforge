package dev.netherforge.plugin.command

import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.LuaLocation
import dev.netherforge.plugin.binding.entityHandle
import dev.netherforge.plugin.lua.ResourceName
import dev.netherforge.plugin.platform.ArgumentSyntax
import dev.netherforge.plugin.platform.ArgumentType
import dev.netherforge.plugin.platform.ArgumentValue
import dev.netherforge.plugin.platform.CommandSender
import dev.netherforge.plugin.platform.SelectedEntity
import dev.netherforge.plugin.session.ProjectSession

/** A mistake in what was typed that only the runtime can see (no such world, centity or menu), said to whoever typed it. */
internal class CommandProblem(message: String) : Exception(message) {
    override fun fillInStackTrace(): Throwable = this
}

/**
 * Turns what the server read for each argument ([ArgumentValue]) into the
 * value its handler gets, the same on every server: a `Player` handle, a
 * `Vec3`, a `World` once there's such a world, a `Dialog` once the project has
 * one. The server has checked everything it knows (numbers and their bounds,
 * choices, selectors, block states, items); this checks what only the runtime
 * knows (the project's centities, menus and dialogs, the worlds by name), and
 * says what those offer in tab completion.
 */
internal class ArgumentValues(private val session: ProjectSession) {
    private val platform get() = session.platform

    /** The handler's value for [argument], or a [CommandProblem] the way a player reads it. */
    fun resolve(argument: ArgumentSyntax, value: ArgumentValue, sender: CommandSender): Any {
        val name = "<${argument.name}>"
        fun mismatch(): Nothing = error("the server read <${argument.name}> (${argument.type.luaName}) as $value")
        return when (argument.type) {
            ArgumentType.WORD, ArgumentType.TEXT, ArgumentType.CHOICE -> (value as? ArgumentValue.Text)?.text ?: mismatch()
            ArgumentType.INTEGER -> (value as? ArgumentValue.Integer)?.value ?: mismatch()
            ArgumentType.NUMBER -> (value as? ArgumentValue.Decimal)?.value ?: mismatch()
            ArgumentType.BOOLEAN -> (value as? ArgumentValue.Bool)?.value ?: mismatch()
            ArgumentType.PLAYER -> (value as? ArgumentValue.Players)?.players?.single()?.let { LuaHandle.Player(it.uuid.toString()) }
                ?: mismatch()
            ArgumentType.PLAYERS -> (value as? ArgumentValue.Players)?.players?.map { LuaHandle.Player(it.uuid.toString()) } ?: mismatch()
            ArgumentType.ENTITY -> (value as? ArgumentValue.Entities)?.entities?.single()?.let(::entity) ?: mismatch()
            ArgumentType.ENTITIES -> (value as? ArgumentValue.Entities)?.entities?.map(::entity) ?: mismatch()
            ArgumentType.POSITION -> (value as? ArgumentValue.Position)?.let { Vec3(it.x, it.y, it.z) } ?: mismatch()
            ArgumentType.LOCATION -> {
                val at = value as? ArgumentValue.Position ?: mismatch()
                val from = sender.location
                // The console stands at a world's spawn facing nowhere in particular: no facing from it.
                val facing = sender.player != null && from != null
                LuaLocation(
                    LuaHandle.World(from?.world ?: platform.worlds.defaultWorld()),
                    Vec3(at.x, at.y, at.z),
                    from?.yaw?.takeIf { facing },
                    from?.pitch?.takeIf { facing }
                )
            }
            ArgumentType.BLOCK_STATE -> (value as? ArgumentValue.BlockState)?.state ?: mismatch()
            ArgumentType.ITEM -> (value as? ArgumentValue.Item)?.item ?: mismatch()
            ArgumentType.WORLD -> word(value).let { text ->
                text.takeIf { platform.worlds.exists(it) }?.let { LuaHandle.World(it) }
                    ?: throw CommandProblem("$name: no world called \"$text\" (worlds: ${platform.worlds.names().joinToString(", ")}).")
            }
            ArgumentType.CENTITY -> word(value).let { text ->
                session.centities.find(text)?.takeIf { !it.removed }?.let { LuaHandle.Centity(it.id.toString()) }
                    ?: throw CommandProblem("$name: no live centity with the id \"$text\".")
            }
            ArgumentType.CENTITY_KIND -> word(value).let { text ->
                text.takeIf { session.centities.definition(it) != null }?.let(::ResourceName)
                    ?: throw CommandProblem("$name: no centity \"$text\" in this project.")
            }
            ArgumentType.MENU -> word(value).let { text ->
                text.takeIf { session.menus.definition(it) != null }?.let(::ResourceName)
                    ?: throw CommandProblem("$name: no menu \"$text\" in this project.")
            }
            ArgumentType.DIALOG -> word(value).let { text ->
                text.takeIf { session.dialogs.file(it) != null }?.let { LuaHandle.Dialog(it) }
                    ?: throw CommandProblem("$name: no dialog \"$text\" in this project.")
            }
        }
    }

    /** The words a type only the runtime knows offers ([ArgumentType.names]); nothing for the rest, which the server completes. */
    fun suggestions(type: ArgumentType): List<String> = when (type) {
        ArgumentType.WORLD -> platform.worlds.names()
        ArgumentType.CENTITY -> session.centities.all().filter { !it.removed }.map { it.id.toString() }
        ArgumentType.CENTITY_KIND -> session.centities.definitionIds()
        ArgumentType.MENU -> session.menus.ids()
        ArgumentType.DIALOG -> session.dialogs.ids()
        else -> emptyList()
    }

    private fun word(value: ArgumentValue) = (value as? ArgumentValue.Text)?.text ?: error("a project name read as $value")

    /** An entity as the class it is (a `Player` for a player, a `Mob` for a mob); one the server can't describe as a plain `Entity`. */
    private fun entity(selected: SelectedEntity): LuaHandle.Entity =
        platform.worldEntities.info(selected.uuid)?.let { session.entityHandle(it) }
            ?: if (selected.player != null) LuaHandle.Player(selected.uuid.toString()) else LuaHandle.Entity(selected.uuid.toString())
}
