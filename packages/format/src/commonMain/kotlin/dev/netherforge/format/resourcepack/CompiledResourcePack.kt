package dev.netherforge.format.resourcepack

import dev.netherforge.format.block.BlockCarriers
import dev.netherforge.format.block.VanillaBlockStates
import dev.netherforge.format.block.VanillaFile
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.ImageInfo
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.screen.VideoFile
import dev.netherforge.format.screen.VideoKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A pack as it builds: its `pack.json`, and the model files its looks name, read ([PackModels.read]), by path under `models/`. */
class ResourcePackRead(val file: ResourcePackFile, val models: Map<String, PackModels.Model> = emptyMap())

/**
 * A validated pack with every character settled. [id] is the pack's folder
 * (`resource_packs/<id>/`) and [namespace] the project's: the pack's assets are the
 * resource keys `<namespace>:<id>/<key>`, so `shop:ui/coin` names the same
 * glyph in a file, a script and the client.
 *
 * Characters are assigned in key order. Skins live in the pack's own font, so
 * each pack numbers its skins from the start of the private-use block. Glyphs
 * all merge into Minecraft's default font, so they're numbered across the
 * whole project ([compileAll]). Characters move when a key is added before
 * others, which is safe: nothing outside the project ever sees one (scripts
 * and menus name things by key), and the pack and everything that uses
 * it are always built and delivered together.
 */
class CompiledResourcePack(
    val namespace: String,
    val id: String,
    val name: String?,
    val description: String?,
    val skins: Map<String, Skin>,
    val glyphs: Map<String, Glyph>,
    val items: Map<String, ItemModelDef>,
    val tooltips: Map<String, TooltipDef>,
    val equipment: Map<String, EquipmentAssetDef>,
    val blocks: Map<String, BlockModelDef>,
    /** Every sound event by key, in key order ([PackSounds.events]). */
    val sounds: Map<String, PackSounds.Sound> = emptyMap(),
    /** How the project names the pack, which says where its files are: its [id], or `ns:id` for a package's. */
    val key: String = id,
    /** The model files its looks name, read, by path under `models/`. */
    val models: Map<String, PackModels.Model> = emptyMap(),
    /** Its HUD elements, their pictures numbered in its own HUD font; null for a pack with no `hud`. */
    val hud: CompiledHud? = null,
    /** The game's GUI sprites it redraws (`hud.replace`), by name. */
    val spriteReplacements: Map<String, Replacement> = emptyMap(),
    /** The game's other textures it redraws (`textures`), by name. */
    val textureReplacements: Map<String, Replacement> = emptyMap(),
    /** The game's sound events it replaces (`sounds` keyed `minecraft:…`), by the event in full ([PackSounds.replacements]). */
    val soundReplacements: Map<String, PackSounds.Sound> = emptyMap(),
    /** The game's models it redraws (`models`), by name (`block/red_mushroom`), each a model file of the pack. */
    val modelReplacements: Map<String, String> = emptyMap()
) {
    /** A game sprite or texture drawn with the pack's [texture] (a path under `textures/`), and its `.mcmeta` when it has [meta]. */
    class Replacement(val texture: String, val meta: Boolean)

    /**
     * [char] draws the skin: its character, or for a picture wider than a font sheet its pieces side by side
     * ([PackFonts.picture]), whose characters are [cells]. [advance] is how far it moves the cursor in pixels
     * ([PackFonts.bitmapAdvance]), or null when its picture couldn't be read.
     */
    class Skin(val key: String, val def: SkinDef, val char: String, val advance: Int?, val cells: String = char) {
        val height: Int get() = def.height ?: SkinDef.DEFAULT_HEIGHT
        val ascent: Int get() = def.ascent ?: SkinDef.DEFAULT_ASCENT
        val offset: Int get() = def.offset ?: SkinDef.DEFAULT_OFFSET

        /**
         * What goes at the front of a window title to draw this skin, in the
         * font `<namespace>:<pack>/gui`: move [offset], draw the picture, then move
         * back by everything that moved the cursor, so the prefix is zero wide
         * and the title's words start where they would without a skin, over
         * the art. With no [advance] there's no way back: the words then start
         * after the picture (the pack validator warns about it).
         */
        val titlePrefix: String get() = titlePrefix(0)

        /**
         * [titlePrefix] with the picture moved [shift] more pixels (still zero
         * wide): for a window that centres its title, where the prefix moves
         * with the words and the shift puts the picture back at the left.
         */
        fun titlePrefix(shift: Int): String =
            PackFonts.offset(offset + shift) + char + (advance?.let { PackFonts.offset(-(offset + shift + it)) } ?: "")
    }

    /**
     * [char] is what draws the glyph in text: its character, or for a picture wider than a font sheet its pieces
     * side by side and the moves between them ([PackFonts.picture]), whose characters are [cells]. [advance]: the
     * glyph's advance in text ([PackFonts.bitmapAdvance]), or null when its picture couldn't be read.
     */
    class Glyph(val key: String, val def: GlyphDef, val char: String, val advance: Int?, val cells: String = char) {
        val height: Int get() = def.height ?: GlyphDef.DEFAULT_HEIGHT
        val ascent: Int get() = def.ascent ?: GlyphDef.DEFAULT_ASCENT
    }

    val guiFont: String get() = "$namespace:$id/${PackFonts.GUI_FONT}"

    /**
     * Every texture path (under `textures/`) this pack refers to and the built
     * pack keeps at `textures/<id>/…`: the equipment layers aren't among them,
     * they're copied to where the game draws them from ([PackLayout]).
     */
    fun textures(): Set<String> = buildSet {
        skins.values.forEach { add(it.def.texture) }
        glyphs.values.forEach { add(it.def.texture) }
        items.values.forEach { item ->
            item.texture?.let(::add)
            item.guiTexture?.let(::add)
            item.model?.let { models[it] }?.let { addAll(it.packTextures) }
            item.drawn?.forEach { models[it]?.let { model -> addAll(model.packTextures) } }
        }
        tooltips.values.forEach { tooltip ->
            tooltip.background?.let(::add)
            tooltip.frame?.let(::add)
        }
        blocks.values.forEach { block ->
            addAll(block.textures())
            block.model?.let { models[it] }?.let { addAll(it.packTextures) }
        }
        hud?.pieces?.forEach { add(it.texture) }
        modelReplacements.values.forEach { file -> models[file]?.let { addAll(it.packTextures) } }
    }

    /** The texture breaking block look [block] throws up: a model's particle, or the faces' ([BlockModelDef.particle]). */
    fun particleOf(block: BlockModelDef): String? = block.model?.let { models[it]?.particle } ?: block.particle()

    /** Every sound file (under `sounds/`) this pack's events play, and the game's events it replaces. */
    fun soundFiles(): Set<String> = (sounds.values + soundReplacements.values).flatMap { it.files }.sorted().toSet()

    companion object {
        /**
         * Every pack in a project whose namespace is [namespace], by pack id,
         * glyphs numbered across all of them in id order. A package's packs
         * can be among them, keyed `ns:id` and built in that namespace (after
         * the project's own). [image] answers a
         * texture's pixel facts by project path
         * (`resource_packs/ui/textures/gui/shop.png`), or null when unknown; skins and
         * glyphs whose picture is unknown get no [Skin.advance]. [soundFiles]
         * is each pack's usable sound files under `sounds/`
         * ([PackSounds.files]), by pack id, and [textureFiles] the files under its `textures/`, which say
         * whether a replaced sprite brings a `.mcmeta`.
         */
        fun compileAll(
            namespace: String,
            files: Map<String, ResourcePackFile>,
            soundFiles: Map<String, Set<String>> = emptyMap(),
            models: Map<String, Map<String, PackModels.Model>> = emptyMap(),
            textureFiles: Map<String, Set<String>> = emptyMap(),
            image: (projectPath: String) -> ImageInfo? = { null }
        ): Map<String, CompiledResourcePack> {
            var nextGlyph = 0
            // A glyph cut into pieces takes a character per further piece, after every glyph's own, so no glyph's
            // own character depends on another's pixels.
            var nextPiece = files.values.sumOf { it.glyphs.size }
            val piece = { nextPiece.takeIf { it < PackFonts.PUA_COUNT }?.let { PackFonts.charAt(nextPiece++) } }
            // A package's pack is keyed `ns:id` and built in its own namespace; the project's come first,
            // so its glyphs keep the characters the editor previews them with.
            return files.entries.sortedWith(compareBy({ ':' in it.key }, { it.key })).associate { (key, file) ->
                val (pkg, id) = PackagePaths.split(key)
                val pack =
                    compile(
                        pkg ?: namespace,
                        id,
                        key,
                        file,
                        nextGlyph,
                        piece,
                        soundFiles[key].orEmpty(),
                        image,
                        models[key].orEmpty(),
                        textureFiles[key].orEmpty()
                    )
                nextGlyph += file.glyphs.size
                key to pack
            }
        }

        /** Where a pack's texture is in the project, or (for [pack] `ns:id`) in its package, at its package path. */
        fun texturePath(pack: String, texture: String): String {
            val (pkg, id) = PackagePaths.split(pack)
            val local = "resource_packs/$id/${ResourcePackFile.TEXTURES}/$texture"
            return if (pkg == null) local else PackagePaths.of(pkg, local)
        }

        private fun compile(
            namespace: String,
            id: String,
            key: String,
            file: ResourcePackFile,
            glyphStart: Int,
            glyphPiece: () -> String?,
            soundFiles: Set<String>,
            image: (String) -> ImageInfo?,
            models: Map<String, PackModels.Model>,
            textureFiles: Set<String>
        ): CompiledResourcePack {
            fun replacements(replaced: Map<String, String>) = replaced.entries.sortedBy { it.key }
                .associate { (name, texture) -> name to Replacement(texture, "$texture.mcmeta" in textureFiles) }
            fun picture(char: String, texture: String, drawnHeight: Int, piece: () -> String?) =
                PackFonts.picture(char, image(texturePath(key, texture)), drawnHeight, piece)
            val skinKeys = file.skins.keys.sorted()
            val glyphKeys = file.glyphs.keys.sorted()
            // Skins are in the pack's own font: a skin's further pieces are numbered after every skin.
            var nextSkinPiece = skinKeys.size
            val skinPiece = { nextSkinPiece.takeIf { it < PackFonts.PUA_COUNT }?.let { PackFonts.charAt(nextSkinPiece++) } }
            val skins = skinKeys.withIndex().associate { (i, key) ->
                val def = file.skins.getValue(key)
                val drawn = picture(PackFonts.charAt(i), def.texture, def.height ?: SkinDef.DEFAULT_HEIGHT, skinPiece)
                key to Skin(key, def, drawn.text, drawn.advance, drawn.cells)
            }
            val glyphs = glyphKeys.withIndex().associate { (i, key) ->
                val def = file.glyphs.getValue(key)
                val drawn = picture(PackFonts.charAt(glyphStart + i), def.texture, def.height ?: GlyphDef.DEFAULT_HEIGHT, glyphPiece)
                key to Glyph(key, def, drawn.text, drawn.advance, drawn.cells)
            }
            return CompiledResourcePack(
                namespace,
                id,
                file.name,
                file.description,
                skins,
                glyphs,
                file.items.entries.sortedBy { it.key }.associate { it.key to it.value },
                file.tooltips.entries.sortedBy { it.key }.associate { it.key to it.value },
                file.equipment.entries.sortedBy { it.key }.associate { it.key to it.value },
                file.blocks.entries.sortedBy { it.key }.associate { it.key to it.value },
                PackSounds.events(file, soundFiles),
                key,
                models,
                file.hud?.let { hud ->
                    CompiledHud.compile(namespace, id, hud) { texture -> image(texturePath(key, texture))?.takeIf { it.height > 0 } }
                },
                replacements(file.hud?.replace.orEmpty()),
                replacements(file.textures),
                PackSounds.replacements(file),
                file.models.entries.sortedBy { it.key }.associate { it.key to it.value }
            )
        }
    }
}

/**
 * The files of the built resource pack, from every pack in a project.
 *
 * Every pack's assets go into the project's namespace, under the pack's id:
 * pack `ui`'s glyph texture `coin.png` is `assets/<namespace>/textures/ui/coin.png`,
 * its item model `ruby` the item definition `<namespace>:ui/ruby`, its sound
 * `menu/open` the event `<namespace>:ui/menu/open`. So a reference resolved
 * (`shop:ui/ruby`) is literally the key the client looks up, and two
 * packages' packs never collide.
 *
 * Pure and shared: the plugin zips this (with fixed entry dates, so the hash
 * only changes when the content does), and the editor can show what a pack
 * will contain. Texture bytes never pass through here; an entry is either text
 * or "copy this project file".
 */
/**
 * A video's converted sound for the resource pack: the video [id] (its own, without a package's namespace) of the
 * project or package [namespace], whose whole sound ([VideoFile.AUDIO]) is [projectPath] and whose pieces
 * ([VideoFile.AUDIO_CHUNKS]) are [chunks], in order.
 */
data class VideoSound(val namespace: String, val id: String, val projectPath: String, val chunks: List<String> = emptyList()) {
    companion object {
        /** Every running video of the project and its packages that keeps a sound and has one converted. */
        fun of(snapshot: ProjectSnapshot): List<VideoSound> = snapshot.everywhere(VideoKind).mapNotNull { (name, loaded) ->
            val video = loaded.compiled ?: return@mapNotNull null
            if (!video.audioOrDefault || VideoFile.AUDIO !in loaded.files) return@mapNotNull null
            val (pkg, id) = PackagePaths.split(name)
            val place = snapshot.placeOf(VideoKind, name)
            // Every piece from the first on, stopping at a gap: a piece after one that's missing could never be reached in order.
            val pieces = loaded.files.mapNotNull(VideoFile::chunkOf).toSet()
            val chunks = generateSequence(0) { it + 1 }.takeWhile { it in pieces }.map { place.file(VideoFile.chunkFile(it)) }.toList()
            VideoSound(pkg ?: snapshot.namespace, id, place.file(VideoFile.AUDIO), chunks)
        }
    }
}

object PackLayout {

    sealed interface Entry {
        data class Text(val text: String) : Entry

        /** Copy a project file (a PNG) verbatim. */
        data class Copy(val projectPath: String) : Entry

        /** Bytes of the build's own: the blank picture a hidden HUD sprite is replaced with. */
        class Bytes(val bytes: ByteArray) : Entry {
            override fun equals(other: Any?) = other is Bytes && bytes.contentEquals(other.bytes)

            override fun hashCode() = bytes.contentHashCode()
        }
    }

    /** A one-pixel PNG, fully transparent: what a hidden HUD sprite draws. */
    val BLANK_PNG: ByteArray = intArrayOf(
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
        0x89, 0x00, 0x00, 0x00, 0x0B, 0x49, 0x44, 0x41, 0x54, 0x78, 0xDA, 0x63, 0x60, 0x00, 0x02, 0x00,
        0x00, 0x05, 0x00, 0x01, 0xE9, 0xFA, 0xDC, 0xD8, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
        0xAE, 0x42, 0x60, 0x82
    ).map { it.toByte() }.toByteArray()

    /**
     * [format] is the target version's resource pack format as `[major, minor]`,
     * which comes from game data. [textureSize] answers a texture's pixel
     * dimensions (for nine-slice tooltip sprites), or null if unknown.
     * [blocks] says which states the project's blocks are held in, and
     * [vanilla] how the game draws the other states of a carrier that takes
     * more than one model (`client/vanilla.json`).
     */
    fun build(
        packs: List<CompiledResourcePack>,
        format: List<Int>,
        description: String,
        blocks: BlockCarriers.Plan? = null,
        vanilla: VanillaFile? = null,
        videos: List<VideoSound> = emptyList(),
        textureSize: (projectPath: String) -> Pair<Int, Int>?
    ): Map<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        // The game's models the packs may redraw: only those the client says it has.
        val knownModels = vanilla?.models.orEmpty().toSet()
        out["pack.mcmeta"] = json(
            buildJsonObject {
                putJsonObject("pack") {
                    put("description", description)
                    put("min_format", JsonArray(format.map { JsonPrimitive(it) }))
                    put("max_format", JsonArray(format.map { JsonPrimitive(it) }))
                }
            }
        )

        val defaultFontProviders = mutableListOf<JsonObject>()
        // One sounds.json per namespace, holding every pack's events.
        val soundEvents = LinkedHashMap<String, MutableMap<String, JsonObject>>()
        // The textures models draw, by the atlas the client must stitch them into (see [atlas]).
        val blockSprites = mutableSetOf<String>()
        val itemSprites = mutableSetOf<String>()
        for (pack in packs.sortedWith(compareBy({ it.namespace }, { it.id }))) {
            val ns = pack.namespace
            val id = pack.id
            fun source(texture: String) = CompiledResourcePack.texturePath(pack.key, texture)
            fun resource(texture: String) = "$ns:$id/${texture.removeSuffix(".png")}"
            for (texture in pack.textures()) {
                out["assets/$ns/textures/$id/$texture"] = Entry.Copy(source(texture))
            }

            if (pack.skins.isNotEmpty()) {
                val providers = buildJsonArray {
                    for (skin in pack.skins.values) {
                        add(bitmap("$ns:$id/${skin.def.texture}", skin.height, skin.ascent, skin.cells))
                    }
                    add(
                        buildJsonObject {
                            put("type", "space")
                            putJsonObject("advances") { PackFonts.advances().forEach { (c, px) -> put(c, px) } }
                        }
                    )
                }
                out["assets/$ns/font/$id/${PackFonts.GUI_FONT}.json"] = json(buildJsonObject { put("providers", providers) })
            }
            for (glyph in pack.glyphs.values) {
                defaultFontProviders += bitmap("$ns:$id/${glyph.def.texture}", glyph.height, glyph.ascent, glyph.cells)
            }

            // The HUD's pictures, each at its height on the screen, and the moves that put them across it.
            pack.hud?.takeIf { it.pieces.isNotEmpty() }?.let { hud ->
                val providers = buildJsonArray {
                    for (piece in hud.pieces) add(bitmap("$ns:$id/${piece.texture}", piece.height, piece.ascent, piece.cells))
                    add(
                        buildJsonObject {
                            put("type", "space")
                            putJsonObject("advances") { PackFonts.advances().forEach { (c, px) -> put(c, px) } }
                        }
                    )
                }
                out["assets/$ns/font/$id/${CompiledHud.FONT}.json"] = json(buildJsonObject { put("providers", providers) })
            }

            for ((key, item) in pack.items) {
                val model = "$ns:item/$id/$key"
                // A block look is the block's model as the item's parent, which holds the game's block display
                // transforms (3D in slots and the hand); its textures are the block's, already in the blocks atlas.
                val shaped = item.model?.let { pack.models[it] }
                out["assets/$ns/models/item/$id/$key.json"] = json(
                    when {
                        // A model of its own: as written, its textures the pack's.
                        shaped != null -> PackModels.write(shaped, ::resource).also { itemSprites += shaped.packTextures.map(::resource) }
                        item.block != null -> buildJsonObject {
                            put("parent", "${item.block.resolve(ns)!!.let { "${it.namespace}:block/${it.path}" }}")
                        }
                        else -> itemModel(item.parent, resource(item.texture!!)).also { itemSprites += resource(item.texture) }
                    }
                )
                item.guiTexture?.let { itemSprites += resource(it) }
                // The look a stack shows at rest: the model, or the GUI's picture in inventories and item frames.
                val look = if (item.guiTexture == null) {
                    buildJsonObject {
                        put("type", "minecraft:model")
                        put("model", model)
                    }
                } else {
                    val guiModel = "$ns:item/$id/${key}_gui"
                    out["assets/$ns/models/item/$id/${key}_gui.json"] =
                        json(itemModel(ItemModelParent.GENERATED, resource(item.guiTexture)))
                    buildJsonObject {
                        put("type", "minecraft:select")
                        put("property", "minecraft:display_context")
                        putJsonArray("cases") {
                            add(
                                buildJsonObject {
                                    putJsonArray("when") {
                                        add(JsonPrimitive("gui"))
                                        add(JsonPrimitive("fixed"))
                                    }
                                    putJsonObject("model") {
                                        put("type", "minecraft:model")
                                        put("model", guiModel)
                                    }
                                }
                            )
                        }
                        putJsonObject("fallback") {
                            put("type", "minecraft:model")
                            put("model", model)
                        }
                    }
                }
                val definition = item.drawn?.takeIf { it.isNotEmpty() }?.let { drawn ->
                    // Each stage is a model of its own, in the item's folder, as its GUI picture is.
                    val stages = drawn.mapIndexed { i, name ->
                        val stage = "${key}_drawn$i"
                        out["assets/$ns/models/item/$id/$stage.json"] = json(PackModels.write(pack.models.getValue(name), ::resource))
                        itemSprites += pack.models.getValue(name).packTextures.map(::resource)
                        buildJsonObject {
                            put("type", "minecraft:model")
                            put("model", "$ns:item/$id/$stage")
                        }
                    }
                    // The game's `use_duration` counts ticks used, scaled: at 0.05 a full second (20 ticks) is 1, the
                    // draw a bow takes. Stage i starts at i / (last), so the last begins at a full draw.
                    val last = (stages.size - 1).coerceAtLeast(1)
                    buildJsonObject {
                        putJsonObject("model") {
                            put("type", "minecraft:condition")
                            put("property", "minecraft:using_item")
                            put("on_false", look)
                            putJsonObject("on_true") {
                                put("type", "minecraft:range_dispatch")
                                put("property", "minecraft:use_duration")
                                put("scale", ItemModelDef.DRAW_SCALE)
                                putJsonArray("entries") {
                                    stages.drop(1).forEachIndexed { i, stage ->
                                        add(
                                            buildJsonObject {
                                                put("threshold", (i + 1).toDouble() / last)
                                                put("model", stage)
                                            }
                                        )
                                    }
                                }
                                put("fallback", stages.first())
                            }
                        }
                    }
                } ?: buildJsonObject { put("model", look) }
                out["assets/$ns/items/$id/$key.json"] = json(definition)
            }

            if (pack.sounds.isNotEmpty() || pack.soundReplacements.isNotEmpty()) {
                for (file in pack.soundFiles()) {
                    out["assets/$ns/sounds/$id/$file"] = Entry.Copy(PackSounds.soundPath(pack.key, file))
                }
                if (pack.sounds.isNotEmpty()) {
                    val events = soundEvents.getOrPut(ns) { LinkedHashMap() }
                    for (sound in pack.sounds.values) events["$id/${sound.key}"] = soundEvent("$ns:$id", sound)
                }
                // The game's own events, in its sounds.json: `replace` drops the game's files for each, so only the pack's play.
                for ((event, sound) in pack.soundReplacements) {
                    val (namespace, path) = event.split(':', limit = 2)
                    soundEvents.getOrPut(namespace) { LinkedHashMap() }[path] =
                        JsonObject(mapOf("replace" to JsonPrimitive(true)) + soundEvent("$ns:$id", sound))
                }
            }

            // The game's models the pack redraws, written over the game's, their textures the pack's or the game's.
            for ((name, file) in pack.modelReplacements) {
                if (name !in knownModels) continue
                val model = pack.models[file] ?: continue
                out["assets/minecraft/models/$name.json"] = json(PackModels.write(model, ::resource))
                val sprites = model.packTextures.map(::resource)
                if (name.startsWith(BLOCK_MODELS)) blockSprites += sprites else itemSprites += sprites
            }

            for ((key, block) in pack.blocks) {
                // `model: "<id>/<key>"` on a block is the model `<ns>:block/<id>/<key>`: a cube, or (for a block a
                // centity is drawn over) only the texture its particles take, which the server keeps from showing.
                val shaped = block.model?.let { pack.models[it] }
                out["assets/$ns/models/block/$id/$key.json"] =
                    json(shaped?.let { PackModels.write(it, ::resource) } ?: cubeModel(block, ::resource))
                pack.particleOf(block)?.let { blockSprites += resource(it) }
                block.faces().orEmpty().values.forEach { blockSprites += resource(it) }
                shaped?.packTextures?.forEach { blockSprites += resource(it) }
                if (blocks?.uses.orEmpty().any { it.hidden && it.pack == pack.key && it.entry == key }) {
                    out["assets/$ns/models/block/$id/${key}_$HIDDEN.json"] = json(hiddenModel(pack.particleOf(block), ::resource))
                }
            }

            for ((key, look) in pack.equipment) {
                // `equipment: "<ns>:<id>/<key>"` is `assets/<ns>/equipment/<id>/<key>.json` (the `equippable` asset id);
                // each layer's texture `<ns>:<id>/<key>` is `textures/entity/equipment/<layer>/<id>/<key>.png`.
                val layers = look.layers()
                for ((layer, texture) in layers) {
                    out["assets/$ns/textures/entity/equipment/$layer/$id/$key.png"] = Entry.Copy(source(texture))
                }
                out["assets/$ns/equipment/$id/$key.json"] = json(
                    buildJsonObject {
                        putJsonObject("layers") {
                            for ((layer, _) in layers) {
                                putJsonArray(layer) { add(buildJsonObject { put("texture", "$ns:$id/$key") }) }
                            }
                        }
                    }
                )
            }

            for ((key, tooltip) in pack.tooltips) {
                for ((part, texture) in listOf("background" to tooltip.background, "frame" to tooltip.frame)) {
                    if (texture == null) continue
                    // `tooltipStyle: "<ns>:<id>/<key>"` draws the sprites `<ns>:tooltip/<id>/<key>_<part>`.
                    val sprite = "assets/$ns/textures/gui/sprites/tooltip/$id/${key}_$part.png"
                    out[sprite] = Entry.Copy(source(texture))
                    val (width, height) = textureSize(source(texture)) ?: (100 to 100)
                    out["$sprite.mcmeta"] = json(
                        buildJsonObject {
                            putJsonObject("gui") {
                                putJsonObject("scaling") {
                                    put("type", "nine_slice")
                                    put("width", width)
                                    put("height", height)
                                    put("border", TOOLTIP_BORDER)
                                }
                            }
                        }
                    )
                }
            }
        }

        // The game's sprites and textures the packs redraw, which only the client lists; a later pack's wins (the
        // project's validation refuses two).
        fun redraw(sprites: Boolean, known: Set<String>) {
            for (pack in packs.sortedWith(compareBy({ it.namespace }, { it.id }))) {
                for ((name, replacement) in if (sprites) pack.spriteReplacements else pack.textureReplacements) {
                    if (name !in known) continue
                    val target = "assets/minecraft/textures/${if (sprites) "gui/sprites/" else ""}$name.png"
                    val source = CompiledResourcePack.texturePath(pack.key, replacement.texture)
                    out[target] = Entry.Copy(source)
                    if (replacement.meta) out["$target.mcmeta"] = Entry.Copy("$source.mcmeta") else out.remove("$target.mcmeta")
                }
            }
        }
        redraw(true, vanilla?.sprites.orEmpty().toSet())
        redraw(false, vanilla?.textures?.keys.orEmpty())

        // The game's own HUD elements the packs hide: every sprite that draws one, blank.
        val hidden = packs.flatMap { it.hud?.hide.orEmpty() }.toSet()
        for (sprite in vanilla?.sprites.orEmpty()) {
            if (hidden.any { it.draws(sprite) } &&
                "assets/minecraft/textures/gui/sprites/$sprite.png" !in out
            ) {
                out["assets/minecraft/textures/gui/sprites/$sprite.png"] =
                    Entry.Bytes(BLANK_PNG)
            }
        }
        if (blocks != null) {
            for ((path, states) in blockStates(blocks, packs, vanilla)) out[path] = states
            // A block drawn by a centity that has no look of its own shows nothing and throws up no particles.
            if (blocks.uses.any { use -> use.hidden && packs.none { it.key == use.pack && use.entry in it.blocks } }) {
                out["assets/${blocks.home}/models/block/$HIDDEN.json"] = json(buildJsonObject { putJsonObject("textures") {} })
            }
        }
        if (blockSprites.isNotEmpty()) out["assets/minecraft/atlases/blocks.json"] = atlas(blockSprites)
        // A sprite in both atlases is one the game draws from only one of them (and warns about), which leaves the
        // block models drawing the missing texture; item models draw from the blocks atlas as well, as vanilla's do.
        (itemSprites - blockSprites).takeIf { it.isNotEmpty() }?.let { out["assets/minecraft/atlases/items.json"] = atlas(it) }
        // Each video's sound, streamed as music is: `video.<id>`, which no pack's `<pack>/<key>` can be.
        for (video in videos.sortedWith(compareBy({ it.namespace }, { it.id }))) {
            val events = soundEvents.getOrPut(video.namespace) { LinkedHashMap() }

            // [file] is where it goes under `sounds/`, without `.ogg`; [source] the project file it's copied from.
            fun sound(key: String, file: String, source: String) {
                out["assets/${video.namespace}/sounds/$file.ogg"] = Entry.Copy(source)
                events[key] = buildJsonObject {
                    putJsonArray("sounds") {
                        add(
                            buildJsonObject {
                                put("name", "${video.namespace}:$file")
                                put("stream", true)
                            }
                        )
                    }
                }
            }
            val key = VideoFile.soundKey(video.id)
            sound(key, key, video.projectPath)
            video.chunks.forEachIndexed { index, source -> sound(VideoFile.chunkKey(video.id, index), "$key/$index", source) }
        }
        for ((ns, events) in soundEvents) {
            out["assets/$ns/sounds.json"] =
                json(JsonObject(events.entries.sortedBy { it.key }.associate { it.key to it.value }))
        }
        // The space characters too, so any text can move the cursor (`<space:N>`, `<at:N>`: SpaceTags).
        defaultFontProviders += buildJsonObject {
            put("type", "space")
            putJsonObject("advances") { PackFonts.advances().forEach { (c, px) -> put(c, px) } }
        }
        out["assets/minecraft/font/default.json"] = json(buildJsonObject { put("providers", JsonArray(defaultFontProviders)) })
        return out.entries.sortedBy { it.key }.associate { it.key to it.value }
    }

    /** The game's block models (under `models/`), whose textures the game draws from the blocks atlas. */
    private const val BLOCK_MODELS = "block/"

    /** The border vanilla's own tooltip sprites use. */
    const val TOOLTIP_BORDER = 9

    /** What follows a block entry's key in the model of a block with no cube. */
    private const val HIDDEN = "hidden"

    /**
     * The blockstate file of each of the game's blocks a custom block is held
     * in: every state as the game draws it, but the ones a custom block holds,
     * which are that block's model. Explicit for every state, so no state's
     * look is left to which entry of a partial list the game matches first. A
     * carrier whose states the game draws with more than one model takes how
     * from [vanilla]; without it there, its file is left out (its blocks look
     * like the game's block, which `block.vanilla-looks` warns about).
     */
    private fun blockStates(plan: BlockCarriers.Plan, packs: List<CompiledResourcePack>, vanilla: VanillaFile?): List<Pair<String, Entry>> {
        val models = plan.uses.mapNotNull { use ->
            val pack = packs.firstOrNull { it.key == use.pack && use.entry in it.blocks }
            val model = when {
                pack != null -> "${pack.namespace}:block/${pack.id}/${use.entry}${if (use.hidden) "_$HIDDEN" else ""}"
                use.hidden -> "${plan.home}:block/$HIDDEN"
                else -> return@mapNotNull null
            }
            use.state.toString() to (model to use.facing)
        }.toMap()
        // Only the game's blocks some custom block is held in: a leaves block nobody uses keeps the game's own file.
        val used = plan.uses.map { it.state.id }.toSet()
        val out = mutableListOf<Pair<String, Entry>>()
        for (pool in plan.pools.values) {
            for ((block, states) in pool.blocks) {
                if (block !in used) continue
                val single = pool.vanillaModel(block)
                val client = vanilla?.blockstates?.get(block)
                if (single == null && client == null) continue
                val variants = buildJsonObject {
                    for (state in states) {
                        val key = state.properties.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
                        val own = models[state.toString()]
                        when {
                            own != null -> putJsonObject(key) {
                                put("model", own.first)
                                // A block that turns: its north-facing model, turned as the game turns its own.
                                own.second?.let { facing ->
                                    if (facing.x != 0) put("x", facing.x)
                                    if (facing.y != 0) put("y", facing.y)
                                }
                            }
                            single != null -> putJsonObject(key) { put("model", single) }
                            // As the client draws it; a state its file has nothing for is left to the game's missing model.
                            else -> client?.let { VanillaBlockStates.variant(it, state) }?.let { put(key, it) }
                        }
                    }
                }
                val (namespace, path) = block.split(':', limit = 2)
                out += "assets/$namespace/blockstates/$path.json" to json(buildJsonObject { put("variants", variants) })
            }
        }
        return out.sortedBy { it.first }
    }

    /**
     * An atlas file adding [sprites] (`<ns>:<id>/<path>`) to one of the game's atlases. A model's
     * textures are looked up in an atlas, and the game's own only stitch `textures/block/` (blocks)
     * and `textures/item/` (items), so without this every pack texture a model draws is the
     * missing texture. The game appends every resource pack's sources for an atlas, so this adds to
     * the vanilla list rather than replacing it. A sprite belongs to one atlas only: item models may
     * draw from either, block models only from `blocks`.
     */
    private fun atlas(sprites: Set<String>) = json(
        buildJsonObject {
            putJsonArray("sources") {
                for (sprite in sprites.sorted()) {
                    add(
                        buildJsonObject {
                            put("type", "minecraft:single")
                            put("resource", sprite)
                        }
                    )
                }
            }
        }
    )

    /** A custom block's cube: every face its own texture, and the one its particles take. */
    private fun cubeModel(block: BlockModelDef, resource: (String) -> String) = buildJsonObject {
        put("parent", "minecraft:block/cube")
        putJsonObject("textures") {
            block.particle()?.let { put("particle", resource(it)) }
            for ((face, texture) in block.faces().orEmpty()) put(face, resource(texture))
        }
    }

    /** What a block drawn by a centity shows in the world: nothing, with the texture its particles take. */
    private fun hiddenModel(particle: String?, resource: (String) -> String) = buildJsonObject {
        putJsonObject("textures") { particle?.let { put("particle", resource(it)) } }
    }

    /** A bitmap provider: [cells] are the characters of its one row, the picture cut into that many equal pieces. */
    private fun bitmap(file: String, height: Int, ascent: Int, cells: String) = buildJsonObject {
        put("type", "bitmap")
        put("file", file)
        put("height", height)
        put("ascent", ascent)
        putJsonArray("chars") { add(JsonPrimitive(cells)) }
    }

    /**
     * A `sounds.json` event: its files as `<location>/<path without .ogg>`
     * (where the game looks under `sounds/`; [location] is `<ns>:<pack>`), a
     * plain name unless volume, pitch or stream needs the object form.
     */
    private fun soundEvent(location: String, sound: PackSounds.Sound) = buildJsonObject {
        putJsonArray("sounds") {
            for (file in sound.files) {
                val name = "$location/${PackSounds.keyOf(file)}"
                if (sound.volume == null && sound.pitch == null && !sound.stream) {
                    add(JsonPrimitive(name))
                } else {
                    add(
                        buildJsonObject {
                            put("name", name)
                            sound.volume?.let { put("volume", it) }
                            sound.pitch?.let { put("pitch", it) }
                            if (sound.stream) put("stream", true)
                        }
                    )
                }
            }
        }
        sound.subtitle?.let { put("subtitle", it) }
    }

    private fun itemModel(parent: ItemModelParent?, layer0: String) = buildJsonObject {
        put("parent", CanonicalJson.serialName(parent ?: ItemModelParent.GENERATED))
        putJsonObject("textures") { put("layer0", layer0) }
    }

    private fun json(element: JsonObject) = Entry.Text(CanonicalJson.print(element) + "\n")
}

/** Width and height from a PNG's IHDR chunk, or null if [bytes] isn't a PNG. */
fun pngSize(bytes: ByteArray): Pair<Int, Int>? {
    val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    if (bytes.size < 24 || !bytes.copyOfRange(0, 8).contentEquals(signature)) return null
    fun int(at: Int) = ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
        ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
    return int(16) to int(20)
}
