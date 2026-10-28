package dev.netherforge.format.advancement

import dev.netherforge.format.ref.Ref
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.text.MiniMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * `advancements/<id>.json`: one of the project's advancements, a quest a
 * player completes. They make trees: an advancement with no [parent] is a
 * tree's root, which the game shows as a tab of the advancements screen,
 * and every other hangs off its parent.
 *
 * The server learns them at start, from the datapack NetherForge generates
 * then ([dev.netherforge.format.datapack.StartupDatapack]), so a change
 * needs a restart. Scripts grant, revoke and read them with the player's
 * advancement functions, by id (`player:grant_advancement("first_steps")`),
 * and hear `player_complete_advancement` when one is completed.
 */
@Serializable
data class AdvancementFile(
    @SerialName("\$schema") val schema: String? = null,
    /** The advancement it follows in its tree: the project's own (`first_steps`), or one a package exports. None for a tree's root. */
    @Ref(RefKind.ADVANCEMENT) val parent: ResourceRef? = null,
    /** How the advancements screen, its toast and the chat show it. None: it's never shown (it only tracks something). */
    val display: AdvancementDisplay? = null,
    /**
     * What completes it, by name. A criterion with no trigger is met only
     * when a script grants it (`player:grant_advancement(id, name)`); one with
     * a game trigger is also met when the game sees it happen.
     */
    val criteria: Map<String, AdvancementCriterion> = emptyMap(),
    /**
     * Which criteria complete it: every group must have one of its criteria
     * met (each group is "any of these", the list "all of these"). Default:
     * every criterion, each its own group.
     */
    val requirements: List<List<String>>? = null,
    /** Experience points a player gets on completing it. Default 0. */
    val experience: Int? = null
) {
    companion object {
        const val SCHEMA = "../.netherforge/schema/advancement.schema.json"
    }
}

/** How an advancement is shown: on the advancements screen, in the toast when it's completed, and in chat. */
@Serializable
data class AdvancementDisplay(
    /** The item drawn on its frame. */
    val icon: AdvancementIcon,
    /** Its name, MiniMessage. */
    @MiniMessage val title: String,
    /** What to do for it, shown under the title on the advancements screen, MiniMessage. */
    @MiniMessage val description: String? = null,
    /** The frame's shape, and the toast's words for it. Default `task`. */
    val frame: AdvancementFrame? = null,
    /**
     * For a tree's root: the texture its tab is tiled with, by the client's
     * id, `minecraft:block/stone` (a `textures/` path without `.png`).
     */
    val background: String? = null,
    /** A toast at the top right when it's completed. Default true. */
    val toast: Boolean? = null,
    /** A chat line for everyone when it's completed. Default true. */
    val announce: Boolean? = null,
    /** Kept off the advancements screen until it's completed. Default false. */
    val hidden: Boolean? = null
)

/** The item an advancement's frame shows: one of the game's items by [kind], or one of the project's own as [item]. */
@Serializable
data class AdvancementIcon(
    /** The game's item: `minecraft:diamond`, or `diamond`. Leave it out for a project [item]. */
    val kind: String? = null,
    /** One of the project's items (or a package's, `ns:id`), drawn with its look. */
    @Ref(RefKind.ITEM) val item: ResourceRef? = null,
    /** `<pack>/<key>`: a look from one of the project's packs (an [item]'s own look when it has one). */
    @Ref(RefKind.ITEM_MODEL) val itemModel: ResourceRef? = null,
    /** The enchanted shimmer (an [item]'s own when it says). */
    val glint: Boolean? = null
)

/** An advancement's frame: its shape on the screen and what the toast calls it. */
@Serializable
enum class AdvancementFrame {
    /** A square: "Advancement Made!". */
    @SerialName("task")
    TASK,

    /** Rounded: "Goal Reached!". */
    @SerialName("goal")
    GOAL,

    /** Spiked, and purple in the toast: "Challenge Complete!". */
    @SerialName("challenge")
    CHALLENGE
}

/**
 * One way towards completing an advancement. With no [trigger] a script
 * meets it; with one, the game also does when it happens, as it does for
 * its own advancements.
 */
@Serializable
data class AdvancementCriterion(
    /** One of the game's triggers: `minecraft:inventory_changed`, `minecraft:tick`. None: only a script meets it. */
    val trigger: String? = null,
    /** The trigger's conditions, handed to the game as written (its own advancement format). Only with a [trigger]. */
    val conditions: Map<String, JsonElement>? = null
)
