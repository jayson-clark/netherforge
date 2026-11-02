package dev.netherforge.format.project

import dev.netherforge.format.text.DefaultFontFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `netherforge.lock`, beside `netherforge.json`: what the project's
 * dependencies resolved to, committed so everyone builds the same packages.
 * The editor (or `netherforge lock`) writes it whenever resolving gives
 * something else; loading a project warns while it's out of date.
 */
@Serializable
data class LockFile(
    @SerialName("\$schema") val schema: String? = null,
    /** Every package the project uses, its dependencies' dependencies included, by namespace. */
    val packages: Map<String, LockedPackage>
) {
    companion object {
        const val FILE_NAME = "netherforge.lock"
        const val SCHEMA = ".netherforge/schema/lock.schema.json"
    }
}

/** One package as it was resolved: which [version], from where ([source]), and its content [hash] ([PackageHash]). */
@Serializable
data class LockedPackage(val version: String, val source: PackageSource, val hash: String)

/**
 * Where a package came from. A tagged union on `type`: [Path] and [Git] are
 * resolved; [Registry] holds its place so it arrives as an addition, and a
 * lock naming one is out of date for this NetherForge.
 */
@Serializable
sealed interface PackageSource {
    /** A folder, relative to the locking project's own (`../economy`). */
    @Serializable
    @SerialName("path")
    data class Path(val path: String) : PackageSource

    /**
     * A commit of a git repository: the dependency's [url] and [rev] as
     * `netherforge.json` says them (null: the repository's default branch),
     * and the [commit] that resolved to, which everyone then uses until the
     * lock is updated.
     */
    @Serializable
    @SerialName("git")
    data class Git(val url: String, val rev: String? = null, val commit: String) : PackageSource

    /** Reserved: a package registry. */
    @Serializable
    @SerialName("registry")
    data class Registry(val url: String) : PackageSource
}

/**
 * `netherforge-bundle.json`, at the top of a bundle `netherforge build`
 * wrote: a project and every package it depends on, each in a folder named
 * after its namespace, with what each must hash to. A production server runs
 * a bundle as it is: it never resolves a dependency itself.
 */
@Serializable
data class BundleManifest(
    /** The project file format the bundle's packages are in ([FormatVersion]). */
    val formatVersion: Int,
    /** The namespace of the project the bundle runs; every other package is one it depends on. */
    val project: String,
    /** Every package in the bundle, the project's own included, by namespace. */
    val packages: Map<String, BundledPackage>,
    /**
     * Who vouches for the bundle: each an Ed25519 signature over [signedText], by its author (`netherforge build
     * --sign`) and maybe a network that checked it (`netherforge sign`). A server can require one by a key it trusts.
     */
    val signatures: List<BundleSignature>? = null,
    /**
     * The project's resource pack, built with the bundle (`netherforge build` with the game's data): a server whose pack
     * format is [BundledResourcePack.format] sends it as it is rather than building its own.
     */
    val resourcePack: BundledResourcePack? = null,
    /**
     * The namespaces of packages the project depends on that the bundle leaves out, for the server to provide
     * (`packages.provided` in its `config.yml`): a dependency marked `provided`, or one `netherforge build --exclude`
     * named. A server refuses a bundle that carries a package it provides.
     */
    val provided: List<String>? = null
) {
    /** What a signature signs: the manifest as it's written, without its signatures (so a countersignature adds one). */
    fun signedText(): String = BundleKind.write(copy(signatures = null))

    companion object {
        const val FILE_NAME = "netherforge-bundle.json"
    }
}

/** A resource pack built into a bundle, as [FILE] beside the manifest: its [sha1], and the pack [format] it's for. */
@Serializable
data class BundledResourcePack(val sha1: String, val format: List<Int>) {
    companion object {
        const val FILE = "resource-pack.zip"
    }
}

/** One signature on a bundle: the signing [key] (`ed25519:<base64 of its 32 bytes>`) and the [signature] in base64. */
@Serializable
data class BundleSignature(val key: String, val signature: String)

/** A package in a bundle: its [version], and the [PackageHash] its folder must have. */
@Serializable
data class BundledPackage(val version: String, val hash: String)

/**
 * A package's content hash: what `netherforge.lock` pins and a bundle is
 * checked against.
 *
 * It covers what a package _is_ ([isContent]): `netherforge.json` and every
 * file of every resource, not READMEs, agent notes or the package's own lock,
 * and not `fonts/default.json`: the editor writes that from whichever client
 * is imported on the computer it runs on, and only the project's own is ever
 * read, so a package's mustn't move its pin. A bundle carries it all the same
 * ([isBundled]). The [listing] has one line per such file, in path
 * order: the hex SHA-256 of its bytes, two spaces, its path (Go's `dirhash`
 * layout); the hash is [PREFIX] and the hex SHA-256 of the listing's UTF-8.
 * Each host hashes with its own platform's SHA-256 (the JVM's, Node's, the
 * editor's Rust and the webview's); format owns which files and in what form.
 */
object PackageHash {
    const val PREFIX = "sha256:"

    /** Whether [path] (relative to a package's folder) is part of the package: hashed and bundled. */
    fun isContent(path: String): Boolean {
        if (path.split('/').any { it.startsWith(".") }) return false
        val at = Kinds.classify(path) ?: return false
        return at.kind != null || at.document == ManifestKind.id || at.document == VanillaKind.id
    }

    /** Whether `netherforge build` copies [path] into a bundle: the [content][isContent], and the default font the server measures text with. */
    fun isBundled(path: String): Boolean = isContent(path) || path == DefaultFontFile.FILE

    /** The text whose SHA-256 is the hash, from every file's hex SHA-256 by path (files that aren't content are left out). */
    fun listing(digests: Map<String, String>): String = buildString {
        for (path in digests.keys.filter(
            ::isContent
        ).sorted()) {
            append(digests.getValue(path).lowercase()).append("  ").append(path).append('\n')
        }
    }

    /** The hash, from the hex SHA-256 of the [listing]. */
    fun of(listingSha256: String): String = PREFIX + listingSha256.lowercase()
}
