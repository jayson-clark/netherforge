package dev.netherforge.format.resourcepack

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.Names

object ResourcePackValidator {

    /**
     * [textures] is the set of files under this pack's `textures/` folder,
     * [sounds] its usable sound files under `sounds/` ([PackSounds.files]),
     * [models] the files under `models/`. What's in a model file is checked
     * in the file itself ([PackModels.validate]).
     */
    fun validate(
        file: ResourcePackFile,
        sink: ProblemSink,
        textures: Set<String>?,
        sounds: Set<String>? = null,
        models: Set<String>? = null
    ) {
        fun texture(path: String, at: String) {
            if (!ResourcePackFile.isTexturePath(path)) {
                sink.report(ProblemCodes.RESOURCE_PACK_TEXTURE_PATH, "\"$path\" must be ${ResourcePackFile.TEXTURE_PATH_RULE}", at)
            } else if (textures != null && path !in textures) {
                sink.report(ProblemCodes.RESOURCE_PACK_TEXTURE_MISSING, "textures/$path doesn't exist", at)
            }
        }
        fun model(path: String, at: String) {
            if (!PackModels.isModelPath(path)) {
                sink.report(ProblemCodes.RESOURCE_PACK_MODEL_FILE, "\"$path\" must be ${PackModels.MODEL_PATH_RULE}", at)
            } else if (models != null && path !in models) {
                sink.report(ProblemCodes.RESOURCE_PACK_MODEL_FILE, "${PackModels.FOLDER}/$path doesn't exist", at)
            }
        }
        fun key(key: String, at: String) {
            if (!Names.isId(key)) sink.report(ProblemCodes.RESOURCE_PACK_KEY, "\"$key\" isn't a usable key (${Names.ID_RULE})", at)
        }

        if (file.onDemand == true) {
            // What every player's game must draw can't wait for a script to send it.
            val everyone = listOfNotNull(
                "blocks".takeIf { file.blocks.isNotEmpty() },
                "hud".takeIf { file.hud != null },
                "textures".takeIf { file.textures.isNotEmpty() },
                "models".takeIf { file.models.isNotEmpty() },
                "the game's sounds".takeIf { file.sounds.keys.any { PackSounds.isGameEvent(it) } }
            )
            if (everyone.isNotEmpty()) {
                sink.report(
                    ProblemCodes.RESOURCE_PACK_ON_DEMAND,
                    "an onDemand pack can't have ${everyone.joinToString(" or ")}: every player's game needs them, " +
                        "so they go in a pack players get as they join",
                    "$.onDemand"
                )
            }
        }
        for ((key, skin) in file.skins) {
            val at = CanonicalJson.childPath("$.skins", key)
            key(key, at)
            texture(skin.texture, "$at.texture")
            val height = skin.height ?: SkinDef.DEFAULT_HEIGHT
            val ascent = skin.ascent ?: SkinDef.DEFAULT_ASCENT
            if (height <= 0) sink.report(ProblemCodes.RESOURCE_PACK_HEIGHT, "height must be positive", "$at.height")
            if (ascent >
                height
            ) {
                sink.report(ProblemCodes.RESOURCE_PACK_ASCENT, "ascent can't exceed height; Minecraft refuses to load it", "$at.ascent")
            }
            val offset = skin.offset ?: SkinDef.DEFAULT_OFFSET
            if (offset !in -PackFonts.MAX_OFFSET..PackFonts.MAX_OFFSET) {
                sink.report(ProblemCodes.RESOURCE_PACK_OFFSET, "offset must be within ±${PackFonts.MAX_OFFSET}", "$at.offset")
            }
        }
        for ((key, glyph) in file.glyphs) {
            val at = CanonicalJson.childPath("$.glyphs", key)
            key(key, at)
            texture(glyph.texture, "$at.texture")
            val height = glyph.height ?: GlyphDef.DEFAULT_HEIGHT
            val ascent = glyph.ascent ?: GlyphDef.DEFAULT_ASCENT
            if (height <= 0) sink.report(ProblemCodes.RESOURCE_PACK_HEIGHT, "height must be positive", "$at.height")
            if (ascent >
                height
            ) {
                sink.report(ProblemCodes.RESOURCE_PACK_ASCENT, "ascent can't exceed height; Minecraft refuses to load it", "$at.ascent")
            }
        }
        for ((key, item) in file.items) {
            val at = CanonicalJson.childPath("$.items", key)
            key(key, at)
            item.texture?.let { texture(it, "$at.texture") }
            item.guiTexture?.let { texture(it, "$at.guiTexture") }
            item.model?.let { model(it, "$at.model") }
            item.drawn?.forEachIndexed { i, stage -> model(stage, "$at.drawn[$i]") }
            val looks = listOfNotNull(item.texture?.let { "texture" }, item.block?.let { "block" }, item.model?.let { "model" })
            when {
                looks.isEmpty() ->
                    sink.report(ProblemCodes.RESOURCE_PACK_ITEM_LOOK, "Give this item a texture, a block it's drawn as, or a model", at)
                item.drawn?.isEmpty() == true ->
                    sink.report(
                        ProblemCodes.RESOURCE_PACK_ITEM_LOOK,
                        "`drawn` lists the models of each draw stage, so it needs at least one",
                        "$at.drawn"
                    )
                looks.size > 1 ->
                    sink.report(
                        ProblemCodes.RESOURCE_PACK_ITEM_LOOK,
                        "An item is drawn as one of a texture, a block and a model: it has ${looks.joinToString(" and ")}",
                        "$at.${looks.last()}"
                    )
                item.parent != null && item.texture == null ->
                    sink.report(
                        ProblemCodes.RESOURCE_PACK_ITEM_LOOK,
                        "Only a texture is held as `parent` says: a block or model is held as it is, so drop `parent`",
                        "$at.parent"
                    )
            }
        }
        for ((key, tooltip) in file.tooltips) {
            val at = CanonicalJson.childPath("$.tooltips", key)
            key(key, at)
            tooltip.background?.let { texture(it, "$at.background") }
            tooltip.frame?.let { texture(it, "$at.frame") }
            if (tooltip.background == null && tooltip.frame == null) {
                sink.report(
                    ProblemCodes.RESOURCE_PACK_TOOLTIP_EMPTY,
                    "This tooltip has neither a background nor a frame, so it looks like vanilla",
                    at
                )
            }
        }
        for ((key, look) in file.equipment) {
            val at = CanonicalJson.childPath("$.equipment", key)
            key(key, at)
            for ((field, path) in listOf(
                "humanoid" to look.humanoid,
                "humanoidLeggings" to look.humanoidLeggings,
                "wings" to look.wings,
                "horseBody" to look.horseBody,
                "wolfBody" to look.wolfBody
            )) {
                path?.let { texture(it, "$at.$field") }
            }
            if (look.layers().isEmpty()) {
                sink.report(
                    ProblemCodes.RESOURCE_PACK_EQUIPMENT_EMPTY,
                    "This equipment look has no layer, so nothing is drawn when it's worn",
                    at
                )
            }
        }
        for ((key, block) in file.blocks) {
            val at = CanonicalJson.childPath("$.blocks", key)
            key(key, at)
            for ((field, path) in listOf(
                "texture" to block.texture,
                "top" to block.top,
                "bottom" to block.bottom,
                "side" to block.side,
                "north" to block.north,
                "south" to block.south,
                "east" to block.east,
                "west" to block.west
            )) {
                path?.let { texture(it, "$at.$field") }
            }
            if (block.model != null) {
                model(block.model, "$at.model")
                if (block.textures().isNotEmpty()) {
                    sink.report(
                        ProblemCodes.RESOURCE_PACK_BLOCK_FACES,
                        "A block look is a model or textures, not both: the model's own textures are what it's drawn with",
                        "$at.model"
                    )
                }
            } else if (block.faces() == null) {
                val missing = BlockModelDef.FACES.filter { block.faceTexture(it) == null }
                sink.report(
                    ProblemCodes.RESOURCE_PACK_BLOCK_FACES,
                    "This block look has no texture for its ${missing.joinToString(", ")} face${if (missing.size == 1) "" else "s"}: " +
                        "give it a texture, or a texture for each face",
                    at
                )
            }
        }
        // A replaced sprite or texture is written to a path in the built pack, so its name is held to a path's shape.
        fun replacements(map: String, replaced: Map<String, String>) {
            for ((name, picture) in replaced) {
                val at = CanonicalJson.childPath(map, name)
                if (!ResourcePackFile.isTexturePath("$name${ResourcePackFile.TEXTURE_EXTENSION}")) {
                    sink.report(
                        ProblemCodes.RESOURCE_PACK_REPLACE_NAME,
                        "\"$name\" isn't the name of a game texture: a path like hud/hotbar, without .png",
                        at
                    )
                }
                texture(picture, at)
            }
        }
        replacements("$.textures", file.textures)
        file.hud?.let { replacements("$.hud.replace", it.replace) }
        // A replaced model is written over the game's file of its name, so the name is a path's shape too.
        for ((name, path) in file.models) {
            val at = CanonicalJson.childPath("$.models", name)
            if (!ResourcePackFile.isTexturePath("$name${ResourcePackFile.TEXTURE_EXTENSION}")) {
                sink.report(
                    ProblemCodes.RESOURCE_PACK_REPLACE_NAME,
                    "\"$name\" isn't the name of a game model: a path like block/red_mushroom, without .json",
                    at
                )
            }
            model(path, at)
        }
        file.hud?.let { hud ->
            val seen = HashSet<String>()
            fun element(map: String, key: String): String {
                val at = CanonicalJson.childPath("$.hud.$map", key)
                key(key, at)
                if (!seen.add(key)) {
                    sink.report(
                        ProblemCodes.RESOURCE_PACK_HUD,
                        "\"$key\" names another of this pack's HUD elements too: scripts name them by key",
                        at
                    )
                }
                return at
            }
            fun place(x: Int, y: Int?, at: String) {
                if (x !in -HudDef.MAX_X..HudDef.MAX_X) {
                    sink.report(
                        ProblemCodes.RESOURCE_PACK_HUD,
                        "x is from -${HudDef.MAX_X} to ${HudDef.MAX_X} pixels from the middle of the screen",
                        "$at.x"
                    )
                }
                if (y != null && y !in 0..HudDef.MAX_Y) {
                    sink.report(
                        ProblemCodes.RESOURCE_PACK_HUD,
                        "y is from 0 (the bottom of the screen) to ${HudDef.MAX_Y}: the action bar can't draw higher",
                        "$at.y"
                    )
                }
            }
            for ((key, bar) in hud.bars) {
                val at = element("bars", key)
                texture(bar.full, "$at.full")
                texture(bar.empty, "$at.empty")
                bar.half?.let { texture(it, "$at.half") }
                place(bar.x, bar.y, at)
                if (bar.segments !in 1..HudDef.MAX_SEGMENTS) {
                    sink.report(ProblemCodes.RESOURCE_PACK_HUD, "A bar has 1 to ${HudDef.MAX_SEGMENTS} segments", "$at.segments")
                }
                if ((bar.gap ?: 0) < 0) sink.report(ProblemCodes.RESOURCE_PACK_HUD, "gap can't be negative", "$at.gap")
            }
            for ((key, row) in hud.icons) {
                val at = element("icons", key)
                place(row.x, row.y, at)
                if (row.icons.isEmpty()) sink.report(ProblemCodes.RESOURCE_PACK_HUD, "A row of icons needs an icon to show", "$at.icons")
                for ((icon, texture) in row.icons) {
                    val path = CanonicalJson.childPath("$at.icons", icon)
                    key(icon, path)
                    texture(texture, path)
                }
                if ((row.gap ?: 0) < 0) sink.report(ProblemCodes.RESOURCE_PACK_HUD, "gap can't be negative", "$at.gap")
            }
            for ((key, text) in hud.texts) place(text.x, null, element("texts", key))
        }
        for ((key, sound) in file.sounds) {
            val at = CanonicalJson.childPath("$.sounds", key)
            val game = PackSounds.isGameKey(key)
            if (game && !PackSounds.isGameEvent(key)) {
                sink.report(
                    ProblemCodes.RESOURCE_PACK_SOUND_KEY,
                    "\"$key\" isn't one of the game's sound events: they're written like \"minecraft:block.stone.break\"",
                    at
                )
            } else if (!game && !PackSounds.isKey(key)) {
                val hint = if (':' in key) "; the game's own sound events are replaced by their name in the minecraft namespace" else ""
                sink.report(ProblemCodes.RESOURCE_PACK_SOUND_KEY, "\"$key\" isn't a usable sound key (${PackSounds.KEY_RULE})$hint", at)
            }
            if (game && sound.files == null) {
                sink.report(
                    ProblemCodes.RESOURCE_PACK_SOUND_REPLACE,
                    "Replacing the game's $key takes files to play instead: \"files\": [\"block/break_1.ogg\"]",
                    at
                )
            }
            val files = sound.files
            if (files == null) {
                if (sounds != null && !game && PackSounds.isKey(key) && key + PackSounds.EXTENSION !in sounds) {
                    sink.report(
                        ProblemCodes.RESOURCE_PACK_SOUND_MISSING,
                        "sounds/$key${PackSounds.EXTENSION} doesn't exist, and this sound lists no files to play instead",
                        at
                    )
                }
            } else {
                if (files.isEmpty()) sink.report(ProblemCodes.RESOURCE_PACK_SOUND_FILES, "files lists no sound files", "$at.files")
                files.forEachIndexed { index, path ->
                    if (!PackSounds.isFile(path)) {
                        sink.report(
                            ProblemCodes.RESOURCE_PACK_SOUND_PATH,
                            "\"$path\" must be a ${PackSounds.EXTENSION} path inside this resource pack's sounds/ folder (${PackSounds.KEY_RULE})",
                            "$at.files[$index]"
                        )
                    } else if (sounds != null && path !in sounds) {
                        sink.report(ProblemCodes.RESOURCE_PACK_SOUND_MISSING, "sounds/$path doesn't exist", "$at.files[$index]")
                    }
                }
            }
            sound.volume?.let { if (it <= 0) sink.report(ProblemCodes.RESOURCE_PACK_SOUND_VOLUME, "volume must be positive", "$at.volume") }
            sound.pitch?.let { if (it <= 0) sink.report(ProblemCodes.RESOURCE_PACK_SOUND_PITCH, "pitch must be positive", "$at.pitch") }
        }
        if (file.skins.size > PackFonts.PUA_COUNT) {
            sink.report(ProblemCodes.RESOURCE_PACK_TOO_MANY, "A resource pack holds at most ${PackFonts.PUA_COUNT} skins", "$.skins")
        }
    }
}
