package dev.netherforge.plugin.script

import dev.netherforge.format.block.BlockKind
import dev.netherforge.format.centity.CentityKind
import dev.netherforge.format.dialog.DialogKind
import dev.netherforge.format.item.ItemKind
import dev.netherforge.format.menu.MenuKind
import dev.netherforge.format.module.ModuleKind
import dev.netherforge.format.screen.ScreenKind
import dev.netherforge.plugin.project.Resource
import java.util.UUID

/** What a scope belongs to: a module, or the one script of a centity instance, a menu window, a dialog or a project item. */
sealed interface ScopeOwner {
    /** How it's named in logs: `module greeter`, `centity tower`, `menu shop`, `dialog welcome`. */
    val label: String

    /** The script's project file, for locating a failure; null for a module (whose failures carry their own file). */
    val file: String? get() = null

    /** The resource whose script this is: what reloads it, and what reloads with a module it required; null for a test, which no reload touches. */
    val resource: Resource?

    data class Module(val id: String) : ScopeOwner {
        override val label: String get() = "module $id"
        override val resource get() = Resource(ModuleKind, id)
    }

    /** The centity's script on one instance. */
    data class CentityScript(val instance: UUID, val centity: String, override val file: String) : ScopeOwner {
        override val label: String get() = "centity $centity"
        override val resource get() = Resource(CentityKind, centity)
    }

    /** The menu's script on one window. */
    data class MenuScript(val window: String, val menu: String, override val file: String) : ScopeOwner {
        override val label: String get() = "menu $menu"
        override val resource get() = Resource(MenuKind, menu)
    }

    /** The dialog's script. */
    data class DialogScript(val dialog: String, override val file: String) : ScopeOwner {
        override val label: String get() = "dialog $dialog"
        override val resource get() = Resource(DialogKind, dialog)
    }

    /** A project item's script. */
    data class ItemScript(val item: String, override val file: String) : ScopeOwner {
        override val label: String get() = "item $item"
        override val resource get() = Resource(ItemKind, item)
    }

    /** A project block's script. */
    data class BlockScript(val block: String, override val file: String) : ScopeOwner {
        override val label: String get() = "block $block"
        override val resource get() = Resource(BlockKind, block)
    }

    /** A project screen's script. */
    data class ScreenScript(val screen: String, override val file: String) : ScopeOwner {
        override val label: String get() = "screen $screen"
        override val resource get() = Resource(ScreenKind, screen)
    }

    /** A test file (`*_test.lua`) run by the script test runner: part of the project, but no resource. */
    data class TestScript(override val file: String) : ScopeOwner {
        override val label: String get() = "test $file"
        override val resource: Resource? get() = null
    }
}

/**
 * One script's globals and everything it has registered: a module (all its
 * files share one scope), or the one script of a centity instance, a menu
 * window, a dialog or a project item, with its own copy of every file beside
 * the script that it requires.
 *
 * Registrations are owned by the scope that made them and go when it closes,
 * so a reload never leaves a stale handler, timer or command behind. (Its
 * event subscriptions are kept by the prelude's event core, which drops them
 * with its environment.)
 */
class Scope internal constructor(
    val id: Int,
    val owner: ScopeOwner,
    val budget: Int,
    /**
     * The package its code is in (its namespace): the project's, or a
     * dependency's for a dependency's module or resource. A bare module name
     * it requires is that package's.
     */
    val namespace: String
) {
    enum class State {
        OPEN,

        /** Errored or overran: nothing reaches it again until its resource reloads. */
        DISABLED,
        CLOSED
    }

    var state: State = State.OPEN
        internal set

    val live: Boolean get() = state == State.OPEN

    /** Set once [Scripts.onRelease]'s listeners have been told it stopped. */
    internal var released = false

    internal val timers = LinkedHashSet<Int>()

    /** Modules this scope reached with `require`, so reloading one restarts its users too. */
    val requires = LinkedHashSet<String>()

    val module: String? get() = (owner as? ScopeOwner.Module)?.id

    /** How `/nf scripts` and the profiler name it: its owner's label, and a centity instance's id (`centity tower 1a2b3c4d`). */
    val title: String get() = when (owner) {
        is ScopeOwner.CentityScript -> "${owner.label} ${owner.instance.toString().take(8)}"
        else -> owner.label
    }

    override fun toString(): String = owner.label
}
