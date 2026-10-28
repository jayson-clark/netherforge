package dev.netherforge.format.resourcepack

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.block.VanillaFile
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.game.has
import dev.netherforge.format.hasErrors
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.DocumentResourceKind
import dev.netherforge.format.project.ImageInfo
import dev.netherforge.format.project.KindContext
import dev.netherforge.format.project.Layout
import dev.netherforge.format.project.Loaded
import dev.netherforge.format.project.ResourceContext
import kotlinx.serialization.json.JsonObject

/**
 * `resource_packs/<id>/pack.json`: one resource pack of pictures and sounds.
 * Every resource pack is built into the one Minecraft resource pack the
 * project sends players, together ([build]), in the project's namespace
 * under the resource pack's id (`shop:ui/coin`), and glyph
 * characters are numbered across all of them.
 *
 * Its model files (`models/<path>.json`) are read with it ([readsFile]): each one a
 * look names is checked in itself ([PackModels.validate]) and built in with its
 * textures ([ResourcePackRead]).
 */
object ResourcePackKind : DocumentResourceKind<ResourcePackFile, ResourcePackRead>(
    "resource_pack",
    "resource_packs",
    Layout.Folder(ResourcePackFile.FILE_NAME),
    ResourcePackFile.serializer(),
    ResourcePackFile.SCHEMA
) {
    override fun canonical(value: ResourcePackFile) = value.copy(schema = schemaRef)

    override fun readsFile(file: String): Boolean = file.startsWith("${PackModels.FOLDER}/") && file.endsWith(PackModels.EXTENSION)

    override fun validate(value: ResourcePackFile, ctx: ResourceContext) {
        val id = ctx.id
        val textures = under(ctx.files, ResourcePackFile.TEXTURES)
        val models = under(ctx.files, PackModels.FOLDER)
        // Sound files are checked in themselves; a bad one keeps the packs from building, like an error in pack.json.
        val (sounds, soundProblems) = PackSounds.files(id, under(ctx.files, ResourcePackFile.SOUNDS))
        ctx.report(soundProblems)
        ResourcePackValidator.validate(value, ctx.sink, textures, sounds, models)
        // Each model file a look names, checked in itself, once however many name it.
        for (path in modelsOf(value).filter { it in models }) {
            val file = ResourcePackFile.modelFile(path)
            val at = ctx.pathIn(this, file)
            when (val parsed = ctx.json(file, at)) {
                null -> {}
                is CanonicalJson.Parsed.Failed -> ctx.report(listOf(parsed.problem))
                is CanonicalJson.Parsed.Ok -> ctx.report(PackModels.validate(parsed.value).map { it.code.at(at, it.message, it.path) })
            }
        }
    }

    /** The model files [file]'s looks name, each once, by path under `models/`. */
    fun modelsOf(file: ResourcePackFile): List<String> = (
        file.items.values.flatMap { listOfNotNull(it.model) + it.drawn.orEmpty() } +
            file.blocks.values.mapNotNull { it.model } +
            file.models.values
        ).filter(PackModels::isModelPath).distinct()

    override fun crossCheck(value: ResourcePackFile, ctx: KindContext) {
        val id = ctx.id
        val textures = under(ctx.files, ResourcePackFile.TEXTURES)
        // Only pixels say how wide a skin is, and without that its title prefix can't move back.
        if (ctx.readsImages) {
            for ((key, skin) in value.skins) {
                if (skin.texture in textures && ctx.image(CompiledResourcePack.texturePath(id, skin.texture)) == null) {
                    ctx.sink.report(
                        ProblemCodes.RESOURCE_PACK_IMAGE,
                        "textures/${skin.texture} can't be read as a PNG, so the title's words will start after this skin instead of over it",
                        CanonicalJson.childPath("$.skins", key) + ".texture"
                    )
                }
            }
        }
        // A font draws a picture too big for its sheet blank; one too wide is cut into equal pieces, if it can be.
        if (ctx.readsSizes) {
            fun fits(what: String, texture: String, drawnHeight: Int?, at: String) {
                val (width, height) = ctx.size(CompiledResourcePack.texturePath(id, texture))?.takeIf { it.size == 2 } ?: return
                val size = "$what is $width×$height pixels"
                val sheet = PackFonts.SHEET
                when {
                    height > sheet -> ctx.sink.report(
                        ProblemCodes.RESOURCE_PACK_PICTURE_SIZE,
                        "$size; Minecraft draws a font picture over $sheet pixels tall blank, so use one at most $sheet tall",
                        at
                    )
                    height > 0 && PackFonts.pieces(width, height, drawnHeight ?: height) == null -> ctx.sink.report(
                        ProblemCodes.RESOURCE_PACK_PICTURE_SIZE,
                        "$size; a picture over $sheet pixels wide is drawn in equal pieces of $sheet or less, " +
                            "and $width doesn't cut into any. Pad it with transparent columns to ${PackFonts.evenWidth(width)} pixels wide",
                        at
                    )
                }
            }
            for ((key, skin) in value.skins) {
                fits("Skin $key", skin.texture, skin.height ?: SkinDef.DEFAULT_HEIGHT, CanonicalJson.childPath("$.skins", key) + ".texture")
            }
            for ((key, glyph) in value.glyphs) {
                fits(
                    "Glyph $key",
                    glyph.texture,
                    glyph.height ?: GlyphDef.DEFAULT_HEIGHT,
                    CanonicalJson.childPath("$.glyphs", key) + ".texture"
                )
            }
            for ((key, bar) in value.hud?.bars.orEmpty()) {
                val at = CanonicalJson.childPath("$.hud.bars", key)
                fits("Bar $key's full picture", bar.full, null, "$at.full")
                fits("Bar $key's empty picture", bar.empty, null, "$at.empty")
                bar.half?.let { fits("Bar $key's half picture", it, null, "$at.half") }
            }
            for ((key, row) in value.hud?.icons.orEmpty()) {
                for ((icon, texture) in row.icons) {
                    fits(
                        "Icon $key/$icon",
                        texture,
                        null,
                        CanonicalJson.childPath(CanonicalJson.childPath("$.hud.icons", key) + ".icons", icon)
                    )
                }
            }
        }
        // Hiding the game's HUD elements blanks their sprites, which only the client lists.
        if (!value.hud?.hide.isNullOrEmpty()) {
            val vanilla = ctx.vanilla
            if (vanilla?.sprites.isNullOrEmpty() || (ctx.minecraft != null && vanilla?.minecraft != ctx.minecraft)) {
                ctx.sink.report(
                    ProblemCodes.RESOURCE_PACK_HUD_HIDE,
                    "Hiding the game's HUD takes the names of its sprites, which the editor writes into ${VanillaFile.FILE} from " +
                        "your Minecraft: open the project in the editor with ${ctx.minecraft?.let {
                            "Minecraft $it"
                        } ?: "the project's Minecraft"} " +
                        "imported (import it again if it was imported before the editor listed sprites). Until then nothing is hidden",
                    "$.hud.hide"
                )
            }
        }
        // Redrawing the game's sprites and textures takes their names, and for a texture its size, which only the client has.
        val sprites = value.hud?.replace.orEmpty()
        if (sprites.isNotEmpty() || value.textures.isNotEmpty() || value.models.isNotEmpty()) {
            val vanilla = ctx.vanilla
            val stale = ctx.minecraft != null && vanilla?.minecraft != ctx.minecraft

            // The file holds only the textures the project asked about, so a name in neither list is one the editor
            // hasn't looked up yet (a name that isn't a path is reported where it's written).
            fun named(name: String) = ResourcePackFile.isTexturePath("$name${ResourcePackFile.TEXTURE_EXTENSION}")
            val unlooked = vanilla != null &&
                (
                    value.textures.keys.any { named(it) && it !in vanilla.textures && it !in vanilla.unknownTextures } ||
                        value.models.keys.any { named(it) && it !in vanilla.models && it !in vanilla.unknownModels }
                    )
            if (vanilla == null ||
                stale ||
                (sprites.isNotEmpty() && vanilla.sprites.isEmpty()) ||
                unlooked
            ) {
                ctx.sink.report(
                    ProblemCodes.RESOURCE_PACK_VANILLA_MISSING,
                    "Replacing the game's sprites, textures and models takes their names and sizes, " +
                        "which the editor writes into ${VanillaFile.FILE} from " +
                        "your Minecraft: open the project in the editor with ${ctx.minecraft?.let {
                            "Minecraft $it"
                        } ?: "the project's Minecraft"} imported (import it again if it was imported before the editor listed them). " +
                        "Until then nothing is replaced",
                    when {
                        sprites.isNotEmpty() -> "$.hud.replace"
                        value.textures.isNotEmpty() -> "$.textures"
                        else -> "$.models"
                    }
                )
            } else {
                val others = ctx.models(ResourcePackKind).filterKeys { it < id }.values
                fun check(map: String, replaced: Map<String, String>, known: Set<String>, taken: (ResourcePackFile) -> Set<String>) {
                    for ((name, _) in replaced.entries.sortedBy { it.key }) {
                        val at = CanonicalJson.childPath(map, name)
                        when {
                            // A name that isn't a path is already reported where it's written.
                            !ResourcePackFile.isTexturePath("$name${ResourcePackFile.TEXTURE_EXTENSION}") -> {}
                            name !in known -> ctx.sink.report(
                                ProblemCodes.RESOURCE_PACK_REPLACE_NAME,
                                "The game has no \"$name\" (${VanillaFile.FILE} lists what Minecraft ${vanilla.minecraft} has)",
                                at
                            )
                            others.any { name in taken(it) } -> ctx.sink.report(
                                ProblemCodes.RESOURCE_PACK_REPLACE_NAME,
                                "Another resource pack replaces \"$name\" too; only one can",
                                at
                            )
                        }
                    }
                }
                check("$.hud.replace", sprites, vanilla.sprites.toSet()) { it.hud?.replace.orEmpty().keys }
                check("$.textures", value.textures, vanilla.textures.keys) { it.textures.keys }
                check("$.models", value.models, vanilla.models.toSet()) { it.models.keys }
                if (ctx.readsSizes) {
                    for ((name, picture) in value.textures.entries.sortedBy { it.key }) {
                        val size = vanilla.textures[name]?.takeIf { it.size == 2 } ?: continue
                        val at = CanonicalJson.childPath("$.textures", name)
                        val image = ctx.size(CompiledResourcePack.texturePath(id, picture))
                        if (image == null) {
                            if (picture in textures) {
                                ctx.sink.report(
                                    ProblemCodes.RESOURCE_PACK_IMAGE,
                                    "textures/$picture can't be read as a PNG, so its size can't be checked",
                                    at
                                )
                            }
                            continue
                        }
                        when (ReplacedTexture.fit(name, size, image, "$picture.mcmeta" in textures)) {
                            ReplacedTexture.Fit.OK -> {}
                            ReplacedTexture.Fit.SIZE -> ctx.sink.report(
                                ProblemCodes.RESOURCE_PACK_REPLACE_SIZE,
                                "textures/$picture is ${image[0]}x${image[1]} pixels, but the game's $name is ${size[0]}x${size[1]}: " +
                                    "the screen cuts its parts out at fixed places, so a replacement must be the same size",
                                at
                            )
                            ReplacedTexture.Fit.SHAPE -> ctx.sink.report(
                                ProblemCodes.RESOURCE_PACK_REPLACE_SHAPE,
                                "textures/$picture is ${image[0]}x${image[1]} pixels, but the game's $name is ${size[0]}x${size[1]}: " +
                                    "a replacement may be bigger or smaller, but the same shape, or it's drawn stretched",
                                at
                            )
                            ReplacedTexture.Fit.STILL -> ctx.sink.report(
                                ProblemCodes.RESOURCE_PACK_REPLACE_SHAPE,
                                "textures/$picture is ${image[0]}x${image[1]} pixels, but the game animates $name (its frames stacked, " +
                                    "${size[0]}x${size[1]}): a still replacement is one square frame, and an animated one needs " +
                                    "textures/$picture.mcmeta beside it, or it's drawn as one tall picture",
                                at
                            )
                        }
                    }
                }
            }
        }
        // The game's sound events a pack replaces: ones the game has, each by one pack.
        val replaced = value.sounds.keys.filter(PackSounds::isGameEvent).sorted()
        if (replaced.isNotEmpty()) {
            val game = ctx.game
            val others = ctx.models(ResourcePackKind).filterKeys { it < id }.values
            for (event in replaced) {
                val at = CanonicalJson.childPath("$.sounds", event)
                when {
                    game?.has(RegistryKey.SOUND_EVENT, event) == false -> ctx.sink.report(
                        ProblemCodes.RESOURCE_PACK_SOUND_EVENT,
                        "Minecraft ${game.minecraftVersion} has no sound event $event",
                        at
                    )
                    others.any { event in it.sounds } -> ctx.sink.report(
                        ProblemCodes.RESOURCE_PACK_SOUND_EVENT,
                        "Another resource pack replaces $event too; only one can",
                        at
                    )
                }
            }
        }
        // Glyphs share one font across every pack, numbered in id order
        // (CompiledResourcePack.compileAll), so the limit is the project's, reported in
        // every pack past it.
        val glyphs = ctx.models(ResourcePackKind).filterKeys { it <= id }.values.sumOf { it.glyphs.size }
        if (value.glyphs.isNotEmpty() && glyphs > PackFonts.PUA_COUNT) {
            ctx.sink.report(
                ProblemCodes.RESOURCE_PACK_TOO_MANY_GLYPHS,
                "The project's resource packs hold more than ${PackFonts.PUA_COUNT} glyphs between them",
                "$.glyphs"
            )
        }
    }

    override fun compile(id: String, value: ResourcePackFile, ctx: ResourceContext): ResourcePackRead {
        val textures = under(ctx.files, ResourcePackFile.TEXTURES)
        val models = modelsOf(value).mapNotNull { path ->
            val file = ResourcePackFile.modelFile(path)
            val json = (ctx.json(file, ctx.pathIn(this, file)) as? CanonicalJson.Parsed.Ok)?.value as? JsonObject ?: return@mapNotNull null
            path to PackModels.read(json, textures)
        }.toMap()
        return ResourcePackRead(value, models)
    }

    /** An empty pack. */
    override fun template(id: String, game: GameData?) = mapOf(pathOf(id) to write(ResourcePackFile()))

    /** A pack's id names everything in it (`ui/coin`) and where its files are: it stays in `resource_packs/`. */
    override val groupable get() = false

    /**
     * Every pack that read, built into the one resource pack in [namespace],
     * by pack id: empty when any pack has errors (the server keeps its last
     * good build). [image] answers a texture's pixel facts by project path.
     */
    fun build(
        namespace: String,
        packs: Map<String, Loaded<ResourcePackFile, ResourcePackRead>>,
        image: (String) -> ImageInfo?
    ): Map<String, CompiledResourcePack> {
        if (packs.values.any { it.problems.hasErrors }) return emptyMap()
        val files = packs.mapValues { it.value.value }
        val soundFiles = packs.mapValues { (id, pack) -> soundFiles(id, pack.files) }
        val models = packs.mapValues { it.value.compiled?.models.orEmpty() }
        val textureFiles = packs.mapValues { under(it.value.files, ResourcePackFile.TEXTURES) }
        return CompiledResourcePack.compileAll(namespace, files, soundFiles, models, textureFiles, image)
    }

    /** Pack [id]'s sound events by key, from its `pack.json` and the files in its folder ([files], relative to it). */
    fun sounds(id: String, file: ResourcePackFile, files: Set<String>): Map<String, PackSounds.Sound> =
        PackSounds.events(file, soundFiles(id, files)).filterKeys(PackSounds::isKey)

    private fun soundFiles(id: String, files: Set<String>) = PackSounds.files(id, under(files, ResourcePackFile.SOUNDS)).first

    /** The files under one of a pack's folders, relative to it. */
    private fun under(files: Set<String>, folder: String): Set<String> =
        files.filter { it.startsWith("$folder/") }.map { it.removePrefix("$folder/") }.toSet()
}
