package dev.netherforge.plugin.api

import dev.netherforge.plugin.binding.FileApi
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.data.parseJson
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.lua.LuaValue
import dev.netherforge.plugin.session.ProjectSession

internal class FileImpl(private val session: ProjectSession) : FileApi {
    private val files get() = session.files

    /** The handle's path, checked again: handles are only made from normalized paths, so this never fails in practice. */
    private fun checked(self: LuaHandle.File): String = files.normalize(self.path) ?: throw LuaApiException("not a usable file path")

    override fun path(self: LuaHandle.File): String = self.path

    override fun name(self: LuaHandle.File): String = self.path.substringAfterLast('/')

    override fun parent(self: LuaHandle.File): LuaHandle.File? =
        if (self.path == "") null else LuaHandle.File(self.path.substringBeforeLast('/', ""))

    override fun child(self: LuaHandle.File, name: String): LuaHandle.File? {
        if ('/' in name) return null
        return files.normalize(if (self.path == "") name else "${self.path}/$name")?.let { LuaHandle.File(it) }
    }

    override fun exists(self: LuaHandle.File): Boolean = files.exists(checked(self))

    override fun isFolder(self: LuaHandle.File): Boolean = files.isDirectory(checked(self))

    override fun size(self: LuaHandle.File): Long? = files.size(checked(self))

    override fun children(self: LuaHandle.File): List<LuaHandle.File> {
        val path = checked(self)
        return files.list(path).map { LuaHandle.File(if (path == "") it else "$path/$it") }
    }

    override fun read(self: LuaHandle.File): String? = files.read(checked(self))

    override fun write(self: LuaHandle.File, text: String): Boolean = files.write(checked(self), text, append = false)

    override fun append(self: LuaHandle.File, text: String): Boolean = files.write(checked(self), text, append = true)

    override fun readJson(self: LuaHandle.File): Any? {
        val text = files.read(checked(self)) ?: return null
        // A save someone edited by hand isn't a reason to take a script out of service.
        return parseJson(text)
    }

    override fun writeJson(self: LuaHandle.File, value: LuaValue): Boolean {
        val path = checked(self)
        val element = runCatching { value.json() }.getOrNull() ?: return false
        return files.write(path, element.toString(), append = false)
    }

    override fun modifiedTime(self: LuaHandle.File): Long? = files.modified(checked(self))

    override fun rename(self: LuaHandle.File, path: String): LuaHandle.File? {
        val to = files.normalize(path) ?: return null
        return if (files.move(checked(self), to)) LuaHandle.File(to) else null
    }

    override fun copyTo(self: LuaHandle.File, path: String): LuaHandle.File? {
        val to = files.normalize(path) ?: return null
        return if (files.copy(checked(self), to)) LuaHandle.File(to) else null
    }

    override fun createFolder(self: LuaHandle.File): Boolean = files.makeDirectory(checked(self))

    override fun delete(self: LuaHandle.File): Boolean = files.delete(checked(self))
}
