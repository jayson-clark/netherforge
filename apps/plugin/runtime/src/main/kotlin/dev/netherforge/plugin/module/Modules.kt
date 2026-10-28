package dev.netherforge.plugin.module

import dev.netherforge.format.module.ModuleInfo
import dev.netherforge.format.module.ModuleKind
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.Names
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.project.ProjectManifest
import dev.netherforge.format.project.ResourcePlace
import dev.netherforge.format.ref.ReferenceIndex
import dev.netherforge.format.script.ScriptDef
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.ScopeOwner
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject
import dev.netherforge.plugin.session.forScript

/**
 * The project's modules: server-wide Lua, one scope (one set of globals) per
 * module, all on the project's single Lua state.
 *
 * A module with an `init.lua` is started at load. One without is only run
 * when something requires it. Either way a module's scope is created at most
 * once per load, so `require("combat")` from anywhere gets the module that's
 * running, with its state.
 *
 * The packages the project depends on have modules too, named as the
 * runtime names a package's resources (`acme:api`), each in a scope of its
 * own like any module.
 */
class Modules(
    private val scripts: Scripts,
    /** Every file, the packages' at package paths. */
    private val projectFiles: () -> Set<String>,
    /** The project's namespace. */
    private val home: () -> String,
    /** The `netherforge.json` of the project or a package, by namespace: what each depends on and exports. */
    private val manifestOf: (String) -> ProjectManifest?
) : RuntimeService {
    override val name get() = "modules"

    enum class Status(val label: String) {
        /** Has no init.lua and nothing has required it. */
        IDLE("idle"),
        RUNNING("running"),
        FAILED("failed")
    }

    private val modules = LinkedHashMap<String, ModuleInfo>()

    /** Where each module's folder is, in its group folders: where `require` finds its files. */
    private val places = HashMap<String, ResourcePlace>()
    private val scopes = HashMap<String, Scope>()

    /** Takes every module, the packages' included, as their files say (errors or not: a file is Lua the server runs). */
    override fun define(project: SessionProject) {
        modules.clear()
        places.clear()
        for ((id, loaded) in project.everywhere(ModuleKind)) define(id, loaded.value, loaded.group)
    }

    override fun start() = startAll()

    override fun stop() = stopAll()

    override val reloads: Set<KindSpec<*, *>> get() = setOf(ModuleKind)

    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        for (id in ids) reload(id, batch)
    }

    /** Reloads a module, then everything that required it: other modules now, the rest in their kind's turn. */
    private fun reload(id: String, batch: ReloadBatch) {
        val resource = Resource(ModuleKind, id)
        if (!batch.first(resource)) return
        val requiredBy = dependents(id)
        val loaded = batch.snapshot.everywhere(ModuleKind)[id]
        val failures = batch.failures {
            stop(id)
            define(id, loaded?.value, loaded?.group.orEmpty())
            if (loaded != null) start(id)
        }
        batch.report(resource, ok = failures.isEmpty(), problems = batch.problemsUnder(resource) + failures)
        for (scope in requiredBy) {
            val owner = scope.owner.resource ?: continue
            if (owner.kind == ModuleKind) reload(owner.id, batch) else batch.add(owner)
        }
    }

    private fun define(id: String, info: ModuleInfo?, group: String) {
        if (info == null) {
            modules.remove(id)
            places.remove(id)
        } else {
            modules[id] = info
            places[id] = ModuleKind.place(id, group)
        }
    }

    /** Where module [id] is (a package's named `ns:id`); in `modules/` itself when it isn't there. */
    private fun placeOf(id: String): ResourcePlace = places[id] ?: ModuleKind.place(id)

    fun ids(): List<String> = modules.keys.sorted()

    fun info(id: String): ModuleInfo? = modules[id]

    fun status(id: String): Status {
        val scope = scopes[id] ?: return Status.IDLE
        return if (scope.live) Status.RUNNING else Status.FAILED
    }

    /** Starts every module, in id order. A module another one already required is running and isn't run twice. */
    fun startAll() {
        for (id in ids()) start(id)
    }

    /** Runs a module's init.lua (if it has one). False when it failed. */
    fun start(id: String): Boolean {
        val info = modules[id] ?: return false
        if (!info.hasInit) return true
        val scope = scopeFor(id) ?: return false
        return scripts.start(scope, initPath(id))
    }

    /** The module's scope, creating it the first time anything needs it. */
    fun scopeFor(id: String): Scope? {
        if (id !in modules) return null
        scopes[id]?.let { if (it.state != Scope.State.CLOSED) return it }
        val scope = scripts.open(ScopeOwner.Module(id), ScriptDef.DEFAULT_BUDGET)
        scopes[id] = scope
        return scope
    }

    /** Ends a module: its `unload` handlers run, then its handlers, timers and commands go and its files are forgotten. */
    fun stop(id: String) {
        scopes.remove(id)?.let { scripts.close(it) }
        scripts.host?.forget("${placeOf(id).location}/")
    }

    fun stopAll() {
        for (id in scopes.keys.toList()) stop(id)
    }

    /** Scopes other than [id]'s own that required it, so a reload of [id] can restart them too. */
    fun dependents(id: String): List<Scope> = scripts.scopes().filter { id in it.requires && it.module != id }

    /**
     * Resolves `require(name)` from [caller] to a project file and the scope
     * whose globals it runs in.
     *
     * Dots are folders, and a name may also be a folder with an `init.lua`.
     * The caller's own files come first: inside a module, its folder
     * (`require("util")` is `modules/<self>/util.lua`); in a resource's
     * script (a centity's, menu's, dialog's or item's), the folder of that
     * script (`require("helpers")` is `centities/<id>/helpers.lua`), and such
     * a file runs in the requiring scope itself, once per scope, as if it
     * were part of the script. Otherwise the first part names a module
     * (`require("greeter.messages")` is `modules/greeter/messages.lua`,
     * `require("greeter")` is its `init.lua`).
     *
     * A bare module name is in the caller's own package: the project's, or,
     * for code in a package, that package's. `require("acme:api")` names a
     * module of the package `acme`, which the caller's package must depend
     * on and `acme` must export (`exports.modules`); anything else of a
     * package's is its own.
     */
    fun resolve(caller: Scope, name: String): Pair<String, Scope> {
        val (named, local) = PackagePaths.split(name)
        val parts = local.split('.')
        if (parts.any { !PART.matches(it) } || (named != null && !Names.isNamespace(named))) {
            throw LuaApiException(
                "\"$name\" isn't a module name: use dots between folder and file names, like \"greeter.messages\", " +
                    "and a package's namespace before a colon, like \"acme:api\""
            )
        }
        val files = projectFiles()
        val joined = parts.joinToString("/")
        // The package the calling code is in (null: the project's own), where a bare name is looked for.
        val home = home()
        val from = caller.namespace.takeIf { it != home }
        val pkg = when (named) {
            null -> from
            home -> null
            else -> named
        }
        if (pkg != from) checkExported(from, pkg!!, parts[0], name)
        // A resource's script, whose folder holds its own files; a module's are in its folder. Only a bare name looks there.
        val script = caller.owner.file.takeIf { caller.module == null }
        val own = if (named != null) null else caller.module?.let { placeOf(it).location } ?: script?.substringBeforeLast('/', "")
        val ownFiles = own?.let { listOf(within(it, "$joined.lua"), within(it, "$joined/${ModuleKind.ENTRY}")) }.orEmpty()
        val id = pkg?.let { PackagePaths.of(it, parts[0]) } ?: parts[0]
        val rest = parts.drop(1).joinToString("/")
        val moduleFiles = if (rest.isEmpty()) {
            listOf(initPath(id))
        } else {
            placeOf(id).let { listOf(it.file("$rest.lua"), it.file("$rest/${ModuleKind.ENTRY}")) }
        }
        val candidates = ownFiles + moduleFiles
        val path =
            candidates.firstOrNull { it in files && (if (it in ownFiles) script != null || caller.module in modules else id in modules) }
                ?: throw LuaApiException(
                    "no ${if (script != null) "file or module" else "module"} \"$name\" (looked for ${candidates.joinToString()})"
                )
        if (script != null && path in ownFiles) {
            // A file beside the script runs as part of it: in this scope, once per scope.
            if (path == script) throw LuaApiException("\"$name\" is this script's own file, $path, which is already running")
            return path to caller
        }
        val module = if (path in ownFiles) caller.module!! else id
        val scope = scopeFor(module) ?: throw LuaApiException("module \"$module\" isn't loaded")
        if (!scope.live) throw LuaApiException("module \"$module\" failed to load; fix it and save to reload")
        if (module != caller.module) caller.requires += module
        return path to scope
    }

    /**
     * Code in package [from] (null: the project's) may require [module] of
     * package [to] (null: the project's) only when [from] depends on [to]
     * and [to] exports it: a package's other modules are its own.
     */
    private fun checkExported(from: String?, to: String?, module: String, name: String) {
        val requirer = from ?: home()
        val target = to ?: home()
        if (manifestOf(requirer)?.dependencies?.containsKey(target) != true) {
            throw LuaApiException("\"$name\": " + ReferenceIndex.unknownNamespace(target, requirer).forScript())
        }
        val manifest = manifestOf(target) ?: throw LuaApiException("\"$name\": the package \"$target\" isn't loaded")
        if (!manifest.exports(
                ModuleKind,
                module
            )
        ) {
            throw LuaApiException(ReferenceIndex.notExported(target, "module \"$module\"").forScript())
        }
    }

    private fun initPath(id: String) = placeOf(id).file(ModuleKind.ENTRY)

    private fun within(folder: String, file: String) = if (folder.isEmpty()) file else "$folder/$file"

    private companion object {
        val PART = Regex("^[A-Za-z0-9_]+$")
    }
}
