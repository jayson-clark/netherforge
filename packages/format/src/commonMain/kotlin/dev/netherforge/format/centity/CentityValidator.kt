package dev.netherforge.format.centity

import dev.netherforge.format.ProblemCode
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.game.has
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.Names
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.validate.Rules

/**
 * Everything wrong with a parsed centity, reported at once.
 *
 * The compiler builds only from a file with no errors here, so this is the
 * single definition of "valid" for the editor and the server alike.
 *
 * [files] is the set of paths that exist in the centity's folder (relative to
 * it), so a script pointing at a missing file is caught. [game] is optional:
 * without game data, ids are checked for shape only.
 */
object CentityValidator {

    fun validate(file: CentityFile, sink: ProblemSink, files: Set<String>?, game: GameData?) {
        if (file.nodes.isEmpty()) {
            sink.report(ProblemCodes.CENTITY_NO_NODES, "A centity needs at least one node", "$.nodes")
            return
        }

        for ((name, node) in file.nodes) {
            val at = nodePath(name)
            validateName(name, sink)
            val parent = node.parent
            if (parent != null) {
                if (parent == name) {
                    sink.report(ProblemCodes.CENTITY_SELF_PARENT, "Node \"$name\" is its own parent", "$at.parent")
                } else if (parent !in file.nodes) {
                    sink.report(ProblemCodes.CENTITY_UNKNOWN_PARENT, "Node \"$name\" has unknown parent \"$parent\"", "$at.parent")
                }
            }
            validateContent(name, node, sink, game)
        }

        for (name in cyclic(file)) {
            sink.report(ProblemCodes.CENTITY_CYCLE, "Node \"$name\" is part of a parent cycle", "${nodePath(name)}.parent")
        }

        validateAnimations(file, sink)
        file.script?.let { Rules.script(it, "$.script", sink, files) }
        file.spawning?.let { validateSpawning(it, sink, game) }
        file.body?.let { validateBody(it, sink) }
        file.health?.let { validateHealth(file, it, sink) }
    }

    private fun validateHealth(file: CentityFile, health: HealthDef, sink: ProblemSink) {
        if (!(health.max > 0) || !health.max.isFinite()) {
            sink.report(ProblemCodes.CENTITY_HEALTH, "max must be more than 0", "$.health.max")
        }
        val bar = health.bar ?: return
        val segments = bar.segments
        if (segments != null && segments !in 1..ResolvedHealth.MAX_SEGMENTS) {
            sink.report(ProblemCodes.CENTITY_HEALTH, "segments must be 1 to ${ResolvedHealth.MAX_SEGMENTS}", "$.health.bar.segments")
        }
        if (bar.showTicks != null && bar.showTicks < 0) {
            sink.report(ProblemCodes.CENTITY_HEALTH, "showTicks can't be below 0", "$.health.bar.showTicks")
        }
        if (bar.glyph != null && bar.glyph.isEmpty()) sink.report(ProblemCodes.CENTITY_HEALTH, "glyph can't be empty", "$.health.bar.glyph")
        val own = file.nodes[ResolvedHealth.BAR_NODE]
        if (own != null && own.display !is TextDisplay) {
            sink.report(
                ProblemCodes.CENTITY_HEALTH,
                "The node \"${ResolvedHealth.BAR_NODE}\" draws the health bar, so it must be a text display",
                "${nodePath(ResolvedHealth.BAR_NODE)}.display"
            )
        }
    }

    private fun validateBody(body: BodyDef, sink: ProblemSink) {
        val at = "$.body"
        for ((field, value, most) in listOf(
            Triple("width", body.width, ResolvedBody.MAX_WIDTH),
            Triple("height", body.height, ResolvedBody.MAX_HEIGHT)
        )) {
            if (value != null && (value <= 0 || value > most)) {
                sink.report(
                    ProblemCodes.CENTITY_BODY_SIZE,
                    "$field must be more than 0 and at most ${CanonicalJson.formatNumber(most.toString())} blocks",
                    "$at.$field"
                )
            }
        }
        for ((field, value) in listOf("drag" to body.drag, "liquidDrag" to body.liquidDrag, "bounce" to body.bounce)) {
            if (value != null && (value < 0 || value > 1)) {
                sink.report(ProblemCodes.CENTITY_BODY_RANGE, "$field is clamped to between 0 and 1", "$at.$field")
            }
        }
        for ((field, value) in listOf(
            "maxFallSpeed" to body.maxFallSpeed,
            "liquidGravity" to body.liquidGravity,
            "acceleration" to body.acceleration,
            "airAcceleration" to body.airAcceleration,
            "friction" to body.friction,
            "stepHeight" to body.stepHeight
        )) {
            if (value != null &&
                value < 0
            ) {
                sink.report(ProblemCodes.CENTITY_BODY_RANGE, "A negative $field is treated as zero", "$at.$field")
            }
        }
    }

    private fun validateSpawning(spawning: SpawningDef, sink: ProblemSink, game: GameData?) {
        val at = "$.spawning"
        spawning.worlds?.forEachIndexed { index, world ->
            if (!Names.isWorldName(world)) {
                sink.report(
                    ProblemCodes.CENTITY_SPAWNING_WORLD,
                    "\"$world\" isn't a usable world name (${Names.WORLD_NAME_RULE})",
                    "$at.worlds[$index]"
                )
            }
        }
        spawning.biomes?.forEachIndexed { index, biome ->
            // The project's own biomes are references, which the loader checks; the game's are checked here.
            if (RefKind.BIOME.isGame(
                    biome
                )
            ) {
                spawningId(biome, "biome", RegistryKey.BIOME, ProblemCodes.CENTITY_SPAWNING_BIOME, "$at.biomes[$index]", sink, game)
            }
        }
        spawning.blocks?.forEachIndexed { index, block ->
            spawningId(block, "block", RegistryKey.BLOCK, ProblemCodes.CENTITY_SPAWNING_BLOCK, "$at.blocks[$index]", sink, game)
        }
        spawning.light?.let { light ->
            validateRange(light, "$at.light", sink)
            for ((end, value) in listOf("min" to light.min, "max" to light.max)) {
                if (value != null && value !in SpawningDef.MIN_LIGHT..SpawningDef.MAX_LIGHT) {
                    sink.report(ProblemCodes.CENTITY_SPAWNING_RANGE, "A light level is from 0 to 15", "$at.light.$end")
                }
            }
        }
        spawning.height?.let { validateRange(it, "$at.height", sink) }
        spawning.group?.let { group ->
            validateRange(group, "$at.group", sink)
            for ((end, value) in listOf("min" to group.min, "max" to group.max)) {
                if (value != null &&
                    value < 1
                ) {
                    sink.report(ProblemCodes.CENTITY_SPAWNING_NUMBER, "A group has at least one", "$at.group.$end")
                }
            }
        }
        for ((field, value) in listOf("weight" to spawning.weight, "cap" to spawning.cap, "despawnDistance" to spawning.despawnDistance)) {
            if (value != null && value < 1) sink.report(ProblemCodes.CENTITY_SPAWNING_NUMBER, "$field must be positive", "$at.$field")
        }
        spawning.time?.range?.let { range ->
            for ((end, value) in listOf("min" to range.min, "max" to range.max)) {
                if (value != null && value !in 0..SpawnTime.LAST_TICK) {
                    sink.report(
                        ProblemCodes.CENTITY_SPAWNING_TIME,
                        "A time of day is a tick from 0 to ${SpawnTime.LAST_TICK} (a min above the max runs through midnight)",
                        "$at.time.$end"
                    )
                }
            }
        }
        spawning.condition?.let { condition ->
            if (!SpawningDef.isConditionName(condition)) {
                sink.report(
                    ProblemCodes.CENTITY_SPAWNING_WHEN,
                    "\"$condition\" isn't a condition's name: ${Names.ID_RULE}, or `ns:name` for a package's",
                    "$at.when"
                )
            }
        }
        if (spawning.despawnDistance != null && spawning.despawnDistance in 1..SpawningDef.MAX_DISTANCE) {
            sink.report(
                ProblemCodes.CENTITY_SPAWNING_DESPAWN,
                "The spawner places centities up to ${SpawningDef.MAX_DISTANCE} blocks from a player, so a despawnDistance of " +
                    "${spawning.despawnDistance} removes them as they appear",
                "$at.despawnDistance"
            )
        }
    }

    private fun validateRange(range: SpawnRange, at: String, sink: ProblemSink) {
        if (range.min != null && range.max != null && range.min > range.max) {
            sink.report(ProblemCodes.CENTITY_SPAWNING_RANGE, "min is above max", at)
        }
    }

    /** A biome or block id, or `#tag`: well formed, and with game data, one the game has. */
    private fun spawningId(
        text: String,
        what: String,
        registry: RegistryKey,
        code: ProblemCode,
        at: String,
        sink: ProblemSink,
        game: GameData?
    ) {
        val tag = text.startsWith("#")
        val id = text.removePrefix("#")
        if (!GameIds.isValid(id)) {
            sink.report(code, "\"$text\" isn't a $what ${if (tag) "tag" else "id"}", at)
            return
        }
        val normal = GameIds.normalize(id)
        if (game == null || game.registry(registry) == null) return
        if (tag && game.tag(registry, normal) == null) {
            sink.report(code, "Minecraft ${game.minecraftVersion} has no $what tag \"#$normal\"", at)
        } else if (!tag && game.has(registry, normal) == false) {
            sink.report(code, "Minecraft ${game.minecraftVersion} has no $what \"$normal\"", at)
        }
    }

    /**
     * One node on its own, as a script adds it to a running centity: its name,
     * what it draws, where it can be clicked and how it moves, held to the
     * rules the same node in `centity.json` is. Its parent is the caller's to
     * find. Problems are at `$.nodes.<name>...` ([nodePath]).
     */
    fun validateNode(name: String, node: NodeDef, sink: ProblemSink, game: GameData?) {
        validateName(name, sink)
        validateContent(name, node, sink, game)
    }

    private fun validateName(name: String, sink: ProblemSink) {
        if (!Names.isNodeName(name)) {
            sink.report(ProblemCodes.CENTITY_NODE_NAME, "\"$name\" isn't a usable node name (${Names.NODE_NAME_RULE})", nodePath(name))
        }
    }

    private fun validateContent(name: String, node: NodeDef, sink: ProblemSink, game: GameData?) {
        node.rope?.let { validateRope(name, node, it, sink) }
        node.display?.let { validateDisplay(name, it, sink, game) }
        node.hitbox?.let { validateHitbox(name, node, it, sink) }
        node.physics?.let { validatePhysics(name, it, sink) }
    }

    private fun validateRope(name: String, node: NodeDef, rope: RopeDef, sink: ProblemSink) {
        val at = "${nodePath(name)}.rope"
        if (rope.links !in 1..RopeDef.MAX_LINKS) {
            sink.report(ProblemCodes.CENTITY_ROPE, "links must be 1 to ${RopeDef.MAX_LINKS}", "$at.links")
        }
        rope.linkLength?.let {
            if (it <= 0 || it > RopeDef.MAX_LINK_LENGTH) {
                sink.report(
                    ProblemCodes.CENTITY_ROPE,
                    "linkLength must be more than 0 and at most ${CanonicalJson.formatNumber(RopeDef.MAX_LINK_LENGTH.toString())} blocks",
                    "$at.linkLength"
                )
            }
        }
        if (node.display == null) sink.report(ProblemCodes.CENTITY_ROPE, "a rope needs a display: what each link draws", at)
        if (node.hitbox != null || node.physics != null) {
            sink.report(ProblemCodes.CENTITY_ROPE, "a rope can't have a hitbox or physics: it's laid by the server", at)
        }
    }

    private fun validateDisplay(name: String, display: DisplayDef, sink: ProblemSink, game: GameData?) {
        val at = "${nodePath(name)}.display"
        when (display) {
            is BlockDisplay -> Rules.blockState(display.block, "$at.block", Rules.CENTITY_BLOCK, sink, game)
            is ItemDisplay -> {
                val id = GameIds.normalize(display.item)
                if (!GameIds.isValid(display.item)) {
                    sink.report(ProblemCodes.CENTITY_ITEM_ID, "\"${display.item}\" isn't an item id", "$at.item")
                } else if (game?.has(RegistryKey.ITEM, id) == false) {
                    sink.report(ProblemCodes.CENTITY_UNKNOWN_ITEM, "Minecraft ${game.minecraftVersion} has no item \"$id\"", "$at.item")
                }
            }
            // Its look is a reference, which the loader checks.
            is ModelDisplay -> {}
            is TextDisplay -> {
                if (display.background != null && !ARGB.matches(display.background)) {
                    sink.report(ProblemCodes.CENTITY_BACKGROUND, "background must be #AARRGGBB", "$at.background")
                }
                if (display.lineWidth != null && display.lineWidth <= 0) {
                    sink.report(ProblemCodes.CENTITY_LINE_WIDTH, "lineWidth must be positive", "$at.lineWidth")
                }
            }
            is RectDisplay -> {
                if (!RGB.matches(display.color) && !ARGB.matches(display.color)) {
                    sink.report(ProblemCodes.CENTITY_COLOR, "color must be #RRGGBB or #AARRGGBB", "$at.color")
                }
                display.size?.let { validateSize(it, "$at.size", sink) }
            }
            is ImageDisplay -> {
                val side = 1..ImageDisplay.MAX_SIDE
                if (display.width !in side || display.height !in side) {
                    sink.report(
                        ProblemCodes.CENTITY_IMAGE_SIZE,
                        "an image is 1 to ${ImageDisplay.MAX_SIDE} pixels each way, not ${display.width} by ${display.height}",
                        at
                    )
                } else if (display.width * display.height > ImageDisplay.MAX_PIXELS) {
                    sink.report(
                        ProblemCodes.CENTITY_IMAGE_SIZE,
                        "an image has at most ${ImageDisplay.MAX_PIXELS} pixels, not ${display.width * display.height}",
                        at
                    )
                }
                display.size?.let { validateSize(it, "$at.size", sink) }
            }
        }
    }

    private fun validateSize(size: List<Double>, at: String, sink: ProblemSink) {
        if (size.size != 2 || size.any { !(it > 0) || !it.isFinite() }) {
            sink.report(ProblemCodes.CENTITY_DISPLAY_SIZE, "size is [width, height] in blocks, both more than 0", at)
        }
    }

    private fun validateHitbox(name: String, node: NodeDef, hitbox: HitboxDef, sink: ProblemSink) {
        val at = "${nodePath(name)}.hitbox"
        if (hitbox.followsCollision) {
            if (hitbox.boxes != null) {
                sink.report(ProblemCodes.CENTITY_HITBOX_BOTH, "A hitbox has either boxes or shape: \"collision\", not both", at)
            }
            if (node.display !is BlockDisplay) {
                sink.report(
                    ProblemCodes.CENTITY_HITBOX_COLLISION,
                    "shape: \"collision\" needs a block display on the same node",
                    "$at.shape"
                )
            }
        }
        hitbox.boxes?.forEachIndexed { index, box ->
            if (box.max.x <= box.min.x || box.max.y <= box.min.y || box.max.z <= box.min.z) {
                sink.report(
                    ProblemCodes.CENTITY_HITBOX_SIZE,
                    "Each box's max must be greater than its min on every axis",
                    "$at.boxes[$index]"
                )
            }
        }
        if (hitbox.boxes?.isEmpty() == true) {
            sink.report(ProblemCodes.CENTITY_HITBOX_EMPTY, "This hitbox has no boxes, so nothing can click it", "$at.boxes")
        }
        if (hitbox.fittedTo != null) {
            val current = fitKey(node.display)
            if (current != null && current != hitbox.fittedTo) {
                sink.report(
                    ProblemCodes.CENTITY_HITBOX_STALE,
                    "Hitbox was fitted to \"${hitbox.fittedTo}\" but the display is now \"$current\"; fit it again",
                    "$at.fittedTo"
                )
            }
        }
    }

    /** What a hitbox fitted to [display] records in `fittedTo`. */
    fun fitKey(display: DisplayDef?): String? = when (display) {
        is BlockDisplay -> BlockState.parse(display.block)?.toString() ?: display.block
        is TextDisplay -> "text:" + display.text
        is ItemDisplay, is ModelDisplay, is RectDisplay, is ImageDisplay -> null
        null -> null
    }

    private fun validatePhysics(name: String, physics: PhysicsDef, sink: ProblemSink) {
        val at = "${nodePath(name)}.physics"
        for ((field, value) in listOf("bounciness" to physics.bounciness, "drag" to physics.drag, "angularDrag" to physics.angularDrag)) {
            if (value != null && (value < 0 || value > 1)) {
                sink.report(ProblemCodes.CENTITY_PHYSICS_RANGE, "$field is clamped to between 0 and 1", "$at.$field")
            }
        }
        if (physics.friction != null && physics.friction < 0) {
            sink.report(ProblemCodes.CENTITY_PHYSICS_RANGE, "A negative friction is treated as zero", "$at.friction")
        }
        if (physics.maxSpeed != null && physics.maxSpeed <= 0) {
            sink.report(
                ProblemCodes.CENTITY_PHYSICS_STUCK,
                "With maxSpeed ${CanonicalJson.formatNumber(physics.maxSpeed.toString())} it can never move",
                "$at.maxSpeed"
            )
        }
        if (physics.blocks == false && physics.entities == false) {
            sink.report(
                ProblemCodes.CENTITY_PHYSICS_FALLS,
                "It collides with neither blocks nor centities, so nothing will stop it falling",
                at
            )
        }
        physics.collider?.let { box ->
            if (box.max.x <= box.min.x || box.max.y <= box.min.y || box.max.z <= box.min.z) {
                sink.report(
                    ProblemCodes.CENTITY_PHYSICS_COLLIDER,
                    "The collider's max must be greater than its min on every axis",
                    "$at.collider"
                )
            }
        }
        if (physics.mass != null && physics.mass <= 0) {
            sink.report(ProblemCodes.CENTITY_PHYSICS_MASS, "mass must be positive", "$at.mass")
        }
    }

    private fun validateAnimations(file: CentityFile, sink: ProblemSink) {
        for ((name, clip) in file.animations) {
            val at = child("$.animations", name)
            if (!Names.isNodeName(name)) {
                sink.report(ProblemCodes.CENTITY_ANIMATION_NAME, "\"$name\" isn't a usable animation name (${Names.NODE_NAME_RULE})", at)
            }
            if (clip.tracks.isEmpty()) {
                sink.report(ProblemCodes.CENTITY_ANIMATION_EMPTY, "Animation \"$name\" has no tracks", "$at.tracks")
                continue
            }
            var lastKey = 0.0
            for ((node, channels) in clip.tracks) {
                val trackAt = child("$at.tracks", node)
                if (node !in file.nodes) {
                    sink.report(ProblemCodes.CENTITY_ANIMATION_NODE, "Animation \"$name\" drives unknown node \"$node\"", trackAt)
                    continue
                }
                for ((channel, keys) in channels) {
                    if (keys.isEmpty()) {
                        sink.report(
                            ProblemCodes.CENTITY_TRACK_EMPTY,
                            "Track $node.${channel.key} in \"$name\" has no keyframes",
                            "$trackAt.${channel.key}"
                        )
                        continue
                    }
                    if (keys.any { it.time < 0 }) {
                        sink.report(ProblemCodes.CENTITY_KEY_TIME, "Keyframes can't be before 0", "$trackAt.${channel.key}")
                    }
                    lastKey = maxOf(lastKey, keys.maxOf { it.time })
                }
            }
            if (clip.length != null) {
                if (clip.length <= 0) {
                    sink.report(ProblemCodes.CENTITY_ANIMATION_LENGTH, "length must be positive", "$at.length")
                } else if (clip.length < lastKey) {
                    sink.report(
                        ProblemCodes.CENTITY_KEYS_PAST_LENGTH,
                        "Keyframes after ${CanonicalJson.formatNumber(clip.length.toString())}s never play",
                        "$at.length"
                    )
                }
            }
        }
    }

    /** Nodes that can't be ordered parents-first because their ancestry loops. */
    fun cyclic(file: CentityFile): List<String> {
        val result = mutableListOf<String>()
        for (start in file.nodes.keys) {
            val seen = mutableSetOf<String>()
            var current: String? = start
            while (current != null) {
                if (!seen.add(current)) {
                    if (current == start) result += start
                    break
                }
                val parent = file.nodes[current]?.parent
                current = if (parent != null && parent != current && parent in file.nodes) parent else null
            }
        }
        return result.sorted()
    }

    fun nodePath(name: String) = child("$.nodes", name)

    /** [parent] extended by [key]: `.key` when it's an identifier, `["key"]` otherwise. */
    fun child(parent: String, key: String) = CanonicalJson.childPath(parent, key)
    private val ARGB = Regex("^#[0-9a-fA-F]{8}$")
    private val RGB = Regex("^#[0-9a-fA-F]{6}$")
}
