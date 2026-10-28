package dev.netherforge.plugin.project

import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.Names
import dev.netherforge.format.project.PackagePaths
import dev.netherforge.format.project.PathRole

/**
 * The unit hot reload works in: one resource, a kind from format's registry
 * ([Kinds]) and an id. A saved file reloads the resource that owns it, once,
 * however many of its files were in the batch.
 */
data class Resource(val kind: KindSpec<*, *>, val id: String) {
    /** As the console names it: `centity:tower`, `recipe:ruby_sword`, `map:arena`. */
    val key: String get() = "${kind.id}:$id"

    /**
     * Whether [file] (a project or package path) is one of its files: its folder or a file in it, its one file, or
     * the document beside that, in whichever group folder it is.
     */
    fun owns(file: String): Boolean = of(file) == this

    companion object {
        /**
         * The resource a project-relative path belongs to, or null for files
         * no resource owns (the manifest, a README, `.gitignore`, anything in
         * `.netherforge/`) and for an id that can't be one.
         */
        fun of(path: String): Resource? {
            val found = Kinds.classify(path) ?: return null
            val kind = found.kind?.let(Kinds::byId) ?: return null
            val id = found.id?.takeIf(Names::isId) ?: return null
            // A package's resource is named as the runtime names it: `acme:coin`.
            return Resource(kind, found.pkg?.let { PackagePaths.of(it, id) } ?: id)
        }

        /** The resource whose main file [path] is, or null. */
        fun mainOf(path: String): Resource? = of(path).takeIf { Kinds.classify(path)?.role == PathRole.MAIN }
    }
}
