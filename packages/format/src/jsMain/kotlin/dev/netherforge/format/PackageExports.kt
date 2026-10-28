@file:JsExport

package dev.netherforge.format

import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.PackageHash
import dev.netherforge.format.project.Packages
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

// The editor's questions about packages: what a package is made of, its hash, where git finds it.

/**
 * The text a package's content hash is the SHA-256 of, from every file's hex
 * SHA-256 by path (`{ path: hex }`; files that aren't the package's content
 * are left out): see [PackageHash]. The host hashes it and hands the hex to [packageHash].
 */
fun packageListing(digestsJson: String): String =
    PackageHash.listing(CanonicalJson.json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), digestsJson))

/** A package's content hash, from the hex SHA-256 of its [packageListing]. */
fun packageHash(listingSha256: String): String = PackageHash.of(listingSha256)

/** Whether [path] (relative to a package's folder) is part of what the package is: hashed, and copied into a bundle. */
fun isPackageContent(path: String): Boolean = PackageHash.isContent(path)

/** Whether [path] (relative to a package's folder) is copied into a bundle: its content and its default font ([PackageHash.isBundled]). */
fun isPackageBundled(path: String): Boolean = PackageHash.isBundled(path)

/**
 * Where the files a package location names are in the package cache: for
 * `git:<commit>`, its checkout relative to the cache (`git/checkouts/<commit>`,
 * see [Packages.gitCheckout]); null for a folder's location, which is relative
 * to the project instead.
 */
fun gitCheckout(location: String): String? = Packages.commitOf(location)?.let(Packages::gitCheckout)
