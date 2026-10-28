package dev.netherforge.plugin.pack

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.block.BlockKind
import dev.netherforge.format.block.plan
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.ResourceKey
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.format.resourcepack.CompiledResourcePack
import dev.netherforge.format.resourcepack.PackLayout
import dev.netherforge.format.resourcepack.ResourcePackKind
import dev.netherforge.format.resourcepack.VideoSound
import dev.netherforge.format.resourcepack.pngSize
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.platform.PackOffer
import dev.netherforge.plugin.platform.Platform
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The project's resource pack: every pack under `resource_packs/` built into one zip,
 * served, and sent to players.
 *
 * Built with `PackLayout.build` (so the editor's preview and the zip agree on
 * every file), zipped with fixed entry dates so the SHA-1 changes only when
 * the content does, and served from a small HTTP server inside the plugin at
 * `<base>/<sha1>.zip`, or from a URL the server owner configures (they upload
 * `resource-pack.zip`, which the plugin writes beside its state). Players get
 * it as they join and again only when the hash has changed, so a client that
 * already has these bytes skips the download, and a rebuild reaches everyone
 * online without rejoining.
 *
 * Glyph and skin characters come from the compiled packs: a pack with errors
 * keeps the last good build (and its characters) running, as a broken centity
 * keeps its last good definition.
 *
 * It lives as long as the plugin, not a session: the server that serves the
 * zip, what each player's client was sent and said about it, and the last
 * good build are about the players' clients, so a full reload neither sends
 * everyone the same pack again nor loses the build to a pack with errors.
 * Each session's [PackService] rebuilds it and sends it.
 */
class Packs(
    private val config: PackConfig,
    private val stateDirectory: Path,
    private val log: RuntimeLog,
    private val platform: Platform,
    private val readBytes: (String) -> ByteArray?
) {
    /** One build: the zip, its hash and where players fetch it. */
    class Built(val bytes: ByteArray, val sha1: String, val url: String?)

    var built: Built? = null
        private set

    // Volatile: MiniMessage glyph tags read these from whatever thread parses text.
    @Volatile
    private var compiled: Map<String, CompiledResourcePack> = emptyMap()

    /** The project's namespace, the last load's: what a pack entry's name resolves in. */
    @Volatile
    private var home: String? = null

    /** Unknown `<glyph:…>` references already reported, so each is reported once per build. */
    private val reportedGlyphs: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val sent = HashMap<UUID, String>()
    private var server: PackServer? = null

    /** What each online player's game said last about the pack (`player:resource_pack_status()`). */
    private val statuses = HashMap<UUID, String>()

    /**
     * Rebuilds from [snapshot]. Returns what's wrong with the build that the
     * format didn't already say (the server not knowing its pack format), or
     * nothing. With no packs in the project or its packages there's no pack, and nothing is
     * sent.
     */
    fun rebuild(snapshot: ProjectSnapshot): List<Problem> {
        home = snapshot.namespace
        reportedGlyphs.clear()
        // Blocks need a pack too, even a project with no pictures of its own: the note block states they're held as are drawn by it.
        // So do videos' sounds, which the pack carries as sound events.
        val videos = VideoSound.of(snapshot)
        if (snapshot.everywhere(ResourcePackKind).isEmpty() && snapshot.everywhere(BlockKind).isEmpty() && videos.isEmpty()) {
            compiled = emptyMap()
            built = null
            return emptyList()
        }
        // A pack with errors isn't compiled: keep running the last good one.
        if (snapshot.everywhere(ResourcePackKind).isNotEmpty() && snapshot.compiledResourcePacks.isEmpty()) return emptyList()
        compiled = snapshot.compiledResourcePacks
        val format = platform.game.resourcePackFormat
        if (format == null) {
            val message = "This server doesn't say which resource pack format it uses, so no resource pack was built"
            log.warn(message)
            return listOf(ProblemCodes.RUNTIME_PACK_FORMAT.at(ResourcePackKind.folder, message))
        }
        val description = snapshot.manifest?.name?.takeIf { it.isNotBlank() } ?: "NetherForge"
        // The states the project's blocks are held as: drawn as their models, everything else as the game draws it.
        val blocks = BlockKind.plan(snapshot.everywhere(BlockKind), snapshot.namespace, platform.game)
        val layout = PackLayout.build(compiled.values.toList(), format, description, blocks, snapshot.vanilla, videos) { path ->
            readBytes(path)?.let(::pngSize)
        }
        val files = LinkedHashMap<String, ByteArray>()
        for ((path, entry) in layout) {
            files[path] = when (entry) {
                is PackLayout.Entry.Text -> entry.text.encodeToByteArray()
                is PackLayout.Entry.Copy -> readBytes(entry.projectPath) ?: continue
                is PackLayout.Entry.Bytes -> entry.bytes
            }
        }
        val bytes = PackZip.zip(files)
        val sha1 = sha1(bytes)
        if (built?.sha1 == sha1) return emptyList()
        built = Built(bytes, sha1, publish(bytes, sha1))
        log.info("Built the resource pack from ${compiled.size} resource pack(s), ${bytes.size} bytes, SHA-1 $sha1")
        return emptyList()
    }

    /** Makes [bytes] fetchable and answers the URL, or null when packs aren't delivered at all. */
    private fun publish(bytes: ByteArray, sha1: String): String? {
        if (!config.enabled) return null
        config.externalUrl?.let { url ->
            // The owner serves it: give them the file to upload.
            val file = stateDirectory.resolve("resource-pack.zip")
            runCatching {
                Files.createDirectories(stateDirectory)
                val temp = file.resolveSibling("resource-pack.zip.tmp")
                Files.write(temp, bytes)
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.onFailure { log.error("Couldn't write $file", it) }
            return url
        }
        val server = listener() ?: return null
        server.serve(sha1, bytes)
        val base = (config.publicUrl ?: "http://${server.host}:${server.port}").trimEnd('/')
        return "$base/$sha1.zip"
    }

    /**
     * The plugin's HTTP server (`resource-pack.bind` and `.port`), started if
     * it isn't yet: the pack's, and web routes' when they're on. Null when it
     * couldn't start (logged).
     */
    fun listener(): PackServer? = server ?: runCatching { PackServer.start(config.bind, config.port) }.getOrElse {
        log.error("Couldn't start NetherForge's HTTP server on ${config.bind}:${config.port}", it)
        return null
    }.also { server = it }

    /** Sends the current pack to [player] unless they already have this one. */
    fun send(player: UUID): Boolean {
        val pack = built ?: return false
        val url = pack.url ?: return false
        if (sent[player] == pack.sha1) return false
        if (!platform.resourcePacks.send(player, PackOffer(PACK_ID, url, pack.sha1, config.required, config.prompt))) return false
        sent[player] = pack.sha1
        return true
    }

    /** Sends the current pack to everyone online who doesn't have it; how many that was. */
    fun sendAll(): Int = platform.players.online().count { send(it.uuid) }

    /** A player left: next time they join they get it again. */
    fun forget(player: UUID) {
        sent.remove(player)
        statuses.remove(player)
    }

    /** What [player]'s game said last about the pack (null for nothing yet, or it isn't ours it spoke of). */
    fun status(player: UUID): String? = statuses[player]

    /** [player]'s game says [status] about [pack] (null: it forgot); only our pack's are kept. */
    fun statusChanged(player: UUID, pack: UUID, status: String?) {
        if (pack != PACK_ID) return
        if (status == null) statuses.remove(player) else statuses[player] = status
    }

    /** The plugin is stopping: the server goes. */
    fun stop() {
        server?.stop()
        server = null
        sent.clear()
    }

    // ---- what scripts and menus ask ------------------------------------
    //
    // Names here are as the server knows them: a pack entry of the project's
    // by `<pack>/<key>`, a package's as `ns:<pack>/<key>`. Whether a script
    // may name one (its own package's, or one a package it depends on
    // exports) was checked as it named it (`PackageNames`); files were
    // checked by the format. So these only look things up.

    /** [reference], an entry of [kind]'s name, as a key; null when it isn't shaped like one. */
    private fun keyOf(kind: RefKind, reference: String): ResourceKey? {
        val home = home ?: return null
        return ResourceRef(reference).resolve(home)?.takeIf { kind.isPath(it.path) }
    }

    /** The built pack [key] (an entry's, `shop:ui/coin`, `acme:ui/coin`) is in: the project's by id, a package's as `acme:ui`. */
    private fun packOf(key: ResourceKey): CompiledResourcePack? {
        val home = home ?: return null
        return compiled[ResourceKey(key.namespace, key.pack).relativeTo(home).text]
    }

    /** The character glyph [key] is drawn as; null while the packs aren't built. */
    fun glyph(key: ResourceKey): String? = packOf(key)?.glyphs?.get(key.key)?.char

    /**
     * What a `<glyph:ui/coin>` tag inserts: the glyph's character, or null
     * (nothing) while the packs aren't built or for a glyph no pack has. Files
     * can't name a missing glyph (the format refuses them), so an unknown one
     * comes from text a script built; it's reported once, as a warning, to the
     * console and the editor.
     */
    fun textGlyph(reference: String): String? {
        val glyph = keyOf(RefKind.GLYPH, reference)?.let(::glyph)
        if (glyph == null && home != null && reportedGlyphs.add(reference)) {
            log.warn(
                "<glyph:$reference> names no glyph in the project's resource packs (a glyph is <pack>/<key>, like ui/coin), so it shows nothing"
            )
        }
        return glyph
    }

    /**
     * The MiniMessage that draws a skin at the front of a title: the skin's
     * characters in the pack's `gui` font, in white so the picture isn't
     * tinted by the title's colour. Null when the packs don't have it built.
     */
    fun skinTitle(reference: String, shift: Int = 0): String? {
        val key = keyOf(RefKind.SKIN, reference) ?: return null
        val pack = packOf(key) ?: return null
        val skin = pack.skins[key.key] ?: return null
        return "<white><font:${pack.guiFont}>${skin.titlePrefix(shift)}</font></white>"
    }

    /** A glyph's advance in text (`<glyph:ui/coin>`), for measuring it; null when unknown. */
    fun glyphAdvance(reference: String): Int? {
        val key = keyOf(RefKind.GLYPH, reference) ?: return null
        return packOf(key)?.glyphs?.get(key.key)?.advance
    }

    companion object {
        /** One pack per server, sent under one id, so a new one replaces the old on the client. */
        val PACK_ID: UUID = UUID.nameUUIDFromBytes("netherforge:resource-pack".encodeToByteArray())

        fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
