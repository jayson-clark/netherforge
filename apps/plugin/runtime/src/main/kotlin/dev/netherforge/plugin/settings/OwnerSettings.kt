package dev.netherforge.plugin.settings

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.bridge.PackageSettings
import dev.netherforge.format.bridge.ServerSettings
import dev.netherforge.format.bridge.SettingState
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.project.ProjectManifest
import dev.netherforge.format.settings.SettingDef
import dev.netherforge.format.settings.SettingRead
import dev.netherforge.format.settings.SettingValues
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.SettingChangedEvent
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Server-owner settings: what each package declares in `netherforge.json`'s
 * `settings`, and the values the server's owner gave them, kept in
 * `<plugin folder>/settings/<namespace>.json` (format's [SettingValues]).
 * Scripts read them with `nf.config(name)`, each package only its own.
 *
 * The owner changes one from the editor (the bridge), with `/nf settings`
 * (a command, or the dialog it opens), or by editing the file and running
 * `/nf settings reload`. A change is written to the file at once, then:
 *
 *  - the package's scripts that listen for `setting_changed` hear it (only
 *    that package's: [Scripts.emitTo]);
 *  - every other script of the package that read a changed setting
 *    (`nf.config`, remembered per scope) is restarted through a reload of
 *    its resource ([restart]), so what it set up with the old value is set
 *    up again. One that only listens, or never read it, keeps running.
 *
 * A full reload reads the files again, so a value edited by hand reaches
 * every script then too (they all restart anyway).
 */
class OwnerSettings(
    /** `<plugin folder>/settings`: a file per package that has values set. */
    private val directory: Path,
    private val scripts: Scripts,
    private val log: RuntimeLog,
    /** Reloads resources whose scripts read a setting that changed. */
    private val restart: (List<Resource>) -> Unit,
    /** Something changed that the editor's settings page shows. */
    private val changed: () -> Unit,
    /** Every problem this service reports changed. */
    private val problemsChanged: () -> Unit,
    /** The project's own namespace: its manifest is `netherforge.json`, a package's `<namespace>:netherforge.json`. */
    private val home: () -> String
) : RuntimeService {
    override val name get() = "settings"

    /** One package's settings: what it declares, and what the owner's file holds. */
    private class Package(val namespace: String, val name: String, val declared: Map<String, SettingDef>) {
        /** The file as read or last written: every key, the ones that don't fit too. */
        var file: Map<String, JsonElement> = emptyMap()

        /** The owner's values that fit their settings. */
        var values: Map<String, JsonPrimitive> = emptyMap()

        /** Why a key of the file wasn't used, by key. */
        var misfits: Map<String, SettingValues.Problem> = emptyMap()

        /** The file isn't a JSON object, so nothing in it is used (and it isn't written over). */
        var unreadable = false

        fun current(setting: String): JsonPrimitive? = values[setting] ?: declared[setting]?.defaultValue
    }

    private val packages = LinkedHashMap<String, Package>()

    /** The settings each scope has read, by scope id: what restarts it when one changes. */
    private val reads = HashMap<Int, MutableSet<String>>()

    override fun define(project: SessionProject) {
        packages.clear()
        reads.clear()
        if (project.refused) return
        val snapshot = project.snapshot
        for (namespace in listOf(snapshot.namespace) + snapshot.packages.keys.sorted()) {
            val manifest = snapshot.manifestOf(namespace) ?: continue
            val settings = manifest.settings.orEmpty()
            if (settings.isEmpty()) continue
            packages[namespace] = Package(namespace, manifest.name, settings).also(::read)
        }
    }

    override fun start() = changed()

    override fun scopeReleased(scope: Scope) {
        reads.remove(scope.id)
    }

    // ---- what scripts read ------------------------------------------------------

    /** `nf.config(name)` for [scope]: its package's setting [setting], as a script gets it. Remembered as read. */
    fun config(scope: Scope, setting: String): Any {
        val pkg = packages[scope.namespace]
        val definition = pkg?.declared?.get(setting) ?: throw LuaApiException(unknown(scope.namespace, setting, pkg))
        reads.getOrPut(scope.id) { HashSet() } += setting
        return definition.value(pkg.current(setting)!!)
    }

    private fun unknown(namespace: String, setting: String, pkg: Package?): String {
        val declared = pkg?.declared?.keys.orEmpty().sorted()
        val has = if (declared.isEmpty()) "it declares none" else "it has ${declared.joinToString(", ")}"
        return "there's no setting \"$setting\" in ${manifestOf(namespace)}'s settings ($has)"
    }

    // ---- what the owner changes -------------------------------------------------------

    /** Package [namespace]'s settings, or an error naming the packages that have some. */
    private fun pkg(namespace: String): Package = packages[namespace]
        ?: throw IllegalArgumentException(
            if (packages.isEmpty()) {
                "no package here has settings"
            } else {
                "no package \"$namespace\" with settings (there's ${packages.keys.joinToString(", ")})"
            }
        )

    /** The setting [name] of package [namespace]. */
    fun definition(namespace: String, name: String): SettingDef {
        val pkg = pkg(namespace)
        return pkg.declared[name] ?: throw IllegalArgumentException(unknown(namespace, name, pkg))
    }

    /**
     * Sets [namespace]'s setting [name] to [value] (JSON; null or JSON null
     * puts it back to its default), writes the file and tells the scripts.
     * Throws [IllegalArgumentException] saying why for a setting there
     * isn't or a value it can't have. Returns whether its value changed.
     */
    fun set(namespace: String, name: String, value: JsonElement?): Boolean {
        val definition = definition(namespace, name)
        val read = if (value == null || value is JsonNull) null else definition.read(value)
        if (read is SettingRead.Bad) throw IllegalArgumentException("$name: ${read.message}")
        return name in setAll(namespace, mapOf(name to (read as SettingRead.Ok?)?.json))
    }

    /** [set], from words typed (`/nf settings set`, a dialog's text box). */
    fun setText(namespace: String, name: String, text: String): Boolean {
        val read = definition(namespace, name).parse(text)
        if (read is SettingRead.Bad) throw IllegalArgumentException("$name: ${read.message}")
        return name in setAll(namespace, mapOf(name to (read as SettingRead.Ok).json))
    }

    /**
     * Puts [values] in place at once (each already read by its setting, or
     * null for its default), writes the file once, and announces what
     * changed what scripts read, together: a script that read two of them
     * restarts once. Returns the settings whose value changed.
     */
    fun setAll(namespace: String, values: Map<String, JsonPrimitive?>): List<String> {
        val pkg = pkg(namespace)
        check(!pkg.unreadable) { "${file(namespace)} isn't a JSON object: fix or delete it, then /nf settings reload" }
        for (name in values.keys) definition(namespace, name)
        val before = values.keys.associateWith { pkg.current(it)!! }
        val misfit = values.keys.any { it in pkg.misfits }
        for ((name, value) in values) {
            pkg.file = if (value == null) pkg.file - name else pkg.file + (name to value)
            pkg.values = if (value == null) pkg.values - name else pkg.values + (name to value)
            pkg.misfits = pkg.misfits - name
        }
        write(pkg)
        if (misfit) problemsChanged()
        val changes = before.mapNotNull { (name, was) -> pkg.current(name)!!.takeIf { it != was }?.let { Change(name, was, it) } }
        announce(pkg, changes)
        // The editor shows whether each is set, which changes even when the value doesn't.
        if (changes.isEmpty()) changed()
        return changes.map { it.setting }
    }

    /**
     * Reads every package's file again (after the owner edited one by hand)
     * and announces what changed. Returns how many settings did.
     */
    fun reload(): Int {
        var count = 0
        for (pkg in packages.values) {
            val before = pkg.declared.keys.associateWith { pkg.current(it)!! }
            read(pkg)
            val changes = before.mapNotNull { (name, was) -> pkg.current(name)!!.takeIf { it != was }?.let { Change(name, was, it) } }
            count += changes.size
            announce(pkg, changes)
        }
        problemsChanged()
        changed()
        return count
    }

    private data class Change(val setting: String, val was: JsonPrimitive, val now: JsonPrimitive)

    /**
     * Tells [pkg]'s scripts about [changes]: `setting_changed` to those that
     * listen, a restart for those that read a changed setting and don't.
     */
    private fun announce(pkg: Package, changes: List<Change>) {
        if (changes.isEmpty()) return
        // Who handles it themselves is asked before anyone hears it, so a handler's own changes don't count.
        val listening = scripts.scopesListening(Events.NF_SETTING_CHANGED)
        for (change in changes) {
            val definition = pkg.declared.getValue(change.setting)
            log.info(
                "Setting ${pkg.namespace}:${change.setting} is now ${definition.show(change.now)} (was ${definition.show(change.was)})"
            )
            scripts.emitTo(
                pkg.namespace,
                Events.NF_SETTING_CHANGED,
                SettingChangedEvent(change.setting, definition.value(change.now), definition.value(change.was))
            )
        }
        val names = changes.map { it.setting }.toSet()
        val stale = scripts.scopes()
            .filter { it.namespace == pkg.namespace && it.id !in listening && reads[it.id]?.any(names::contains) == true }
            .mapNotNull { it.owner.resource }
            .distinct()
        if (stale.isNotEmpty()) {
            log.info("Restarting what read it: ${stale.joinToString(", ") { it.key }}")
            restart(stale)
        }
        changed()
    }

    // ---- the files ------------------------------------------------------------------

    /** Where the owner's values for [namespace] are kept. */
    fun file(namespace: String): Path = directory.resolve("$namespace.json")

    private fun read(pkg: Package) {
        pkg.file = emptyMap()
        pkg.values = emptyMap()
        pkg.misfits = emptyMap()
        pkg.unreadable = false
        val file = file(pkg.namespace)
        if (!file.exists()) return
        val text = try {
            file.readText()
        } catch (e: IOException) {
            log.error("Couldn't read $file; ${pkg.namespace}'s settings have their defaults", e)
            pkg.unreadable = true
            return
        }
        val read = SettingValues.read(text, pkg.declared)
        if (read == null) {
            log.warn("$file isn't a JSON object, so ${pkg.namespace}'s settings have their defaults")
            pkg.unreadable = true
            return
        }
        pkg.file = read.file
        pkg.values = read.values
        pkg.misfits = read.problems.associateBy { it.setting }
        for (problem in read.problems) {
            log.warn(
                "$file: ${problem.setting}: ${problem.message}${if (problem.unknown) "" else "; it has its default"}"
            )
        }
    }

    /** Written at once (a change is rare, and must outlast a crash right after), atomically. A file left empty goes. */
    private fun write(pkg: Package) {
        val file = file(pkg.namespace)
        try {
            if (pkg.file.isEmpty()) {
                Files.deleteIfExists(file)
                return
            }
            Files.createDirectories(file.parent)
            val temp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(temp, SettingValues.write(pkg.file))
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            log.error("Couldn't save $file", e)
            throw IllegalStateException("couldn't save $file: ${e.message}")
        }
    }

    // ---- what's shown -----------------------------------------------------------------

    /** Every package with settings, by namespace: the project's first. */
    fun namespaces(): List<String> = packages.keys.toList()

    /** Every package's settings as the editor shows them, each file's path [relative] as the editor reads it. */
    fun state(relative: (Path) -> String = Path::toString): ServerSettings = ServerSettings(
        packages.values.map { pkg ->
            PackageSettings(
                pkg.namespace,
                pkg.name,
                relative(file(pkg.namespace)),
                pkg.declared.mapValues { (name, definition) ->
                    SettingState(definition, pkg.current(name)!!, name in pkg.values, pkg.misfits[name]?.message)
                }
            )
        }
    )

    /** [namespace]'s settings with their values, for `/nf settings` and its dialog. */
    fun values(namespace: String): List<Triple<String, SettingDef, JsonPrimitive>> {
        val pkg = pkg(namespace)
        return pkg.declared.keys.sorted().map { Triple(it, pkg.declared.getValue(it), pkg.current(it)!!) }
    }

    /** Whether the owner set [namespace]'s [setting]; otherwise it has its default. */
    fun isSet(namespace: String, setting: String): Boolean = setting in pkg(namespace).values

    /** The package [namespace]'s name, `netherforge.json`'s. */
    fun packageName(namespace: String): String = pkg(namespace).name

    override fun problems(): List<Problem> = packages.values.flatMap { pkg ->
        val manifest = manifestOf(pkg.namespace)
        val file = "settings/${pkg.namespace}.json"
        if (pkg.unreadable) {
            listOf(
                ProblemCodes.SETTINGS_FILE.at(
                    manifest,
                    "The server's $file isn't a JSON object, so ${pkg.namespace}'s settings have their defaults",
                    "$.settings"
                )
            )
        } else {
            pkg.misfits.values.map { problem ->
                if (problem.unknown) {
                    ProblemCodes.SETTINGS_UNKNOWN.at(
                        manifest,
                        "The server's $file sets \"${problem.setting}\", which isn't a setting here",
                        "$.settings"
                    )
                } else {
                    ProblemCodes.SETTINGS_VALUE.at(
                        manifest,
                        "The server's $file sets ${problem.setting} to what it can't be (${problem.message}), so it has its default",
                        CanonicalJson.childPath("$.settings", problem.setting)
                    )
                }
            }
        }
    }

    private fun manifestOf(namespace: String): String =
        if (namespace == home()) ProjectManifest.FILE_NAME else PackagePaths.of(namespace, ProjectManifest.FILE_NAME)
}
