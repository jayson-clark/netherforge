@file:JsExport

package dev.netherforge.format

import dev.netherforge.format.editor.Usages
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.RefTarget
import dev.netherforge.format.ref.ReferenceIndex
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive

// References: finding, renaming, moving and resolving them, always as format's walker finds them.

/**
 * Document [text] (the file [path] of the resource [targetJson], a
 * `RefTarget` of kind `resource`, in package [from]) as it must read copied
 * into the project [to] as [id]: the package's own things named `from:…`,
 * the resource itself as the copy. Canonical; null when [path] isn't a
 * document format reads, or [text] isn't JSON.
 */
fun moveRefs(path: String, text: String, from: String, to: String, targetJson: String, id: String): String? {
    val kind = Kinds.classify(path)?.document?.let(Kinds::document) ?: return null
    val target = CanonicalJson.json.decodeFromString(RefTarget.serializer(), targetJson) as? RefTarget.Resource ?: return null
    return ReferenceIndex.move(kind, path, text, from, to, target, id)
}

/**
 * Document [text] (the file [path], written in namespace [namespace]) with
 * every reference in it written in full (`ui/coin` is `shop:ui/coin`), so it
 * means the same read from anywhere: how a package's resource is shown beside
 * the project's (as the server runs it, `ProjectSnapshot.everywhere`). Null
 * when [path] isn't a document format reads, or [text] isn't JSON.
 */
fun qualifyRefs(path: String, text: String, namespace: String): String? {
    val kind = Kinds.classify(path)?.document?.let(Kinds::document) ?: return null
    val element = runCatching { CanonicalJson.json.parseToJsonElement(text) }.getOrNull() ?: return null
    return ReferenceIndex.qualify(kind.serializer.descriptor, element, namespace).toString()
}

/**
 * Every reference to [targetJson] (a [RefTarget]) in the project
 * `{ path: text | null }`, from outside it: a [Usages]. What deleting it
 * would break, and what renaming it rewrites.
 */
fun findUsages(filesJson: String, targetJson: String): String {
    val files = CanonicalJson.json.decodeFromString<Map<String, String?>>(filesJson)
    val target = CanonicalJson.json.decodeFromString(RefTarget.serializer(), targetJson)
    val snapshot = Projects.load(MapProjectSource(files))
    return encode(Usages.serializer(), Usages(snapshot.references.usagesOf(target)))
}

/**
 * Document [text] (the project file [path]) with every reference to
 * [targetJson] (a [RefTarget]) renamed to [to] (its new id or key, or a
 * file's new project path), canonical; null when nothing in it names the
 * target, or it isn't a document format reads. [namespace] is the project's.
 * The one rule for following a rename, for every kind of reference.
 */
fun renameRefs(path: String, text: String, namespace: String, targetJson: String, to: String): String? {
    val kind = Kinds.classify(path)?.document?.let(Kinds::document) ?: return null
    val target = CanonicalJson.json.decodeFromString(RefTarget.serializer(), targetJson)
    return ReferenceIndex.rename(kind, path, text, namespace, target, to)
}

/**
 * [text], a reference of [kind] (a `RefKind`: `skin`, `glyph`, `item_model`…)
 * written in a project whose namespace is [namespace], as the key it names
 * (`shop:ui/coin`), or null when it isn't shaped like one. For previews that
 * look a reference up the way the server will.
 */
fun resolveReference(kind: String, text: String, namespace: String): String? {
    val refKind = CanonicalJson.json.decodeFromJsonElement(RefKind.serializer(), JsonPrimitive(kind))
    return ReferenceIndex.resolve(namespace, refKind, text)?.toString()
}
