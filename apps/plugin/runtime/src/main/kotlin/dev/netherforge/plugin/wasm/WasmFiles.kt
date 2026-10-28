package dev.netherforge.plugin.wasm

import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportFunction
import com.dylibso.chicory.runtime.Memory
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * A module's data: the files beside `module.wasm` in its folder, by their path there (`nets/big.nnue`), each with
 * where it is on disk. The same for every instance of the module, so it's worked out once, when it's compiled.
 */
class WasmData(private val files: Map<String, Path>) {
    /** Every folder the files are in, the root (`""`) included. */
    private val folders: Set<String> = buildSet {
        add("")
        for (file in files.keys) {
            var at = file.lastIndexOf('/')
            while (at > 0) {
                add(file.substring(0, at))
                at = file.lastIndexOf('/', at - 1)
            }
        }
    }

    fun file(path: String): Path? = files[path]

    fun isFolder(path: String): Boolean = path in folders

    /** What's directly in [folder], sorted: each name, and whether it's a folder. */
    fun list(folder: String): List<Pair<String, Boolean>> {
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val names = sortedMapOf<String, Boolean>()
        for (path in files.keys + folders) {
            if (path.isEmpty() || !path.startsWith(prefix)) continue
            val rest = path.substring(prefix.length)
            if ('/' in rest) continue
            names[rest] = path in folders
        }
        return names.map { it.key to it.value }
    }

    companion object {
        val NONE = WasmData(emptyMap())
    }
}

/**
 * What an instance sees as its file system: its module's [data] at its root, read-only, and nothing else. WASI's
 * functions that take a file descriptor or a path are these for the instance's own descriptors (the root is 3,
 * opened files after it), and Chicory's own [wasi] for the rest (0 to 2, its input and output), so no host path is
 * ever handed to Chicory: a name is looked up in [data] or doesn't exist. Writing, making, renaming or removing
 * anything is `EROFS`; a path that climbs out of the root is `ENOTCAPABLE`.
 *
 * One instance's, used only by the thread running it (WASI calls come from its code).
 */
class WasmFiles(private val data: WasmData, private val wasi: Map<String, ImportFunction>) : AutoCloseable {
    private class Open(val path: String, val folder: Boolean) {
        var channel: FileChannel? = null
        var position = 0L
    }

    private val open = HashMap<Int, Open>().apply { put(ROOT, Open("", folder = true)) }
    private var nextFd = ROOT + 1

    /** WASI's file functions as this instance has them: put them in place of Chicory's of the same name. */
    fun functions(): List<HostFunction> = listOf(
        own("fd_prestat_get") { memory, args ->
            val fd = args[0].toInt()
            if (fd != ROOT) return@own Errno.EBADF
            memory.writeByte(args[1].toInt(), PREOPEN_DIRECTORY) // a prestat's tag: the only kind of preopen
            memory.writeI32(args[1].toInt() + 4, ROOT_NAME.size)
            Errno.ESUCCESS
        },
        own("fd_prestat_dir_name") { memory, args ->
            if (args[0].toInt() != ROOT) return@own Errno.EBADF
            if (args[2].toInt() < ROOT_NAME.size) return@own Errno.ENAMETOOLONG
            memory.write(args[1].toInt(), ROOT_NAME)
            Errno.ESUCCESS
        },
        own("path_open") { memory, args -> pathOpen(memory, args) },
        byFd("fd_read") { _, memory, args, file -> read(memory, file, args[1].toInt(), args[2].toInt(), args[3].toInt(), null) },
        byFd("fd_pread") { _, memory, args, file ->
            read(memory, file, args[1].toInt(), args[2].toInt(), args[4].toInt(), args[3])
        },
        byFd("fd_seek") { _, memory, args, file ->
            if (file.folder) return@byFd Errno.ESPIPE
            val size = size(file) ?: return@byFd Errno.EIO
            val to = when (args[2].toInt()) {
                WHENCE_SET -> args[1]
                WHENCE_CURRENT -> file.position + args[1]
                WHENCE_END -> size + args[1]
                else -> return@byFd Errno.EINVAL
            }
            if (to < 0) return@byFd Errno.EINVAL
            file.position = to
            memory.writeLong(args[3].toInt(), to)
            Errno.ESUCCESS
        },
        byFd("fd_tell") { _, memory, args, file ->
            memory.writeLong(args[1].toInt(), file.position)
            Errno.ESUCCESS
        },
        byFd("fd_close") { fd, _, _, file ->
            if (fd == ROOT) return@byFd Errno.ENOTSUP
            open.remove(fd)
            file.channel?.close()
            Errno.ESUCCESS
        },
        byFd("fd_fdstat_get") { _, memory, args, file ->
            val at = args[1].toInt()
            memory.writeByte(at, (if (file.folder) DIRECTORY else REGULAR_FILE).toByte())
            memory.writeShort(at + 2, 0.toShort())
            memory.writeLong(at + 8, if (file.folder) FOLDER_RIGHTS else FILE_RIGHTS)
            memory.writeLong(at + 16, if (file.folder) FOLDER_RIGHTS or FILE_RIGHTS else 0L)
            Errno.ESUCCESS
        },
        byFd("fd_filestat_get") { _, memory, args, file -> stat(memory, args[1].toInt(), file.path, file.folder) },
        byFd("fd_readdir") { _, memory, args, file -> readFolder(memory, file, args) },
        byFd("fd_advise") { _, _, _, _ -> Errno.ESUCCESS },
        byFd("fd_sync") { _, _, _, _ -> Errno.ESUCCESS },
        byFd("fd_datasync") { _, _, _, _ -> Errno.ESUCCESS },
        byFd("fd_write") { _, _, _, _ -> Errno.EBADF },
        byFd("fd_pwrite") { _, _, _, _ -> Errno.EBADF },
        byFd("fd_allocate") { _, _, _, _ -> Errno.EROFS },
        byFd("fd_filestat_set_size") { _, _, _, _ -> Errno.EROFS },
        byFd("fd_filestat_set_times") { _, _, _, _ -> Errno.EROFS },
        byFd("fd_fdstat_set_flags") { _, _, _, _ -> Errno.ENOTSUP },
        byFd("fd_fdstat_set_rights") { _, _, _, _ -> Errno.ENOTSUP },
        byFd("fd_renumber") { _, _, _, _ -> Errno.ENOTSUP },
        byFd("path_filestat_get") { _, memory, args, folder ->
            val path = resolve(folder, memory.readString(args[2].toInt(), args[3].toInt())) ?: return@byFd Errno.ENOTCAPABLE
            when {
                data.isFolder(path) -> stat(memory, args[4].toInt(), path, folder = true)
                data.file(path) != null -> stat(memory, args[4].toInt(), path, folder = false)
                else -> Errno.ENOENT
            }
        },
        byFd("path_readlink") { _, memory, args, folder ->
            val path = resolve(folder, memory.readString(args[1].toInt(), args[2].toInt())) ?: return@byFd Errno.ENOTCAPABLE
            if (data.isFolder(path) || data.file(path) != null) Errno.EINVAL else Errno.ENOENT
        },
        byFd("path_create_directory") { _, _, _, _ -> Errno.EROFS },
        byFd("path_remove_directory") { _, _, _, _ -> Errno.EROFS },
        byFd("path_unlink_file") { _, _, _, _ -> Errno.EROFS },
        byFd("path_rename") { _, _, _, _ -> Errno.EROFS },
        byFd("path_link") { _, _, _, _ -> Errno.EROFS },
        byFd("path_filestat_set_times") { _, _, _, _ -> Errno.EROFS },
        // path_symlink takes the descriptor third: (old path, its length, fd, new path, its length).
        own("path_symlink") { _, args ->
            if (args[2].toInt() in open) Errno.EROFS else null
        }
    )

    override fun close() {
        for (file in open.values) {
            try {
                file.channel?.close()
            } catch (_: IOException) {
            }
        }
        open.clear()
    }

    private fun pathOpen(memory: Memory, args: LongArray): Errno? {
        val folder = open[args[0].toInt()] ?: return null
        if (!folder.folder) return Errno.ENOTDIR
        val path = resolve(folder, memory.readString(args[2].toInt(), args[3].toInt())) ?: return Errno.ENOTCAPABLE
        val openFlags = args[4].toInt()
        val rights = args[5]
        val fdFlags = args[7].toInt()
        val isFolder = data.isFolder(path)
        val exists = isFolder || data.file(path) != null
        if (openFlags and OPEN_EXCLUSIVE != 0 && exists) return Errno.EEXIST
        if (openFlags and (OPEN_CREATE or OPEN_TRUNCATE) != 0 || fdFlags and FD_APPEND != 0 || rights and WRITE_RIGHTS != 0L) {
            return if (exists || openFlags and OPEN_CREATE != 0) Errno.EROFS else Errno.ENOENT
        }
        if (!exists) return Errno.ENOENT
        if (openFlags and OPEN_DIRECTORY != 0 && !isFolder) return Errno.ENOTDIR
        if (open.size >= MAX_OPEN) return Errno.EMFILE
        val fd = nextFd++
        open[fd] = Open(path, isFolder)
        memory.writeI32(args[8].toInt(), fd)
        return Errno.ESUCCESS
    }

    /** [path] from [folder], with `.` and `..` worked out; null when it climbs out of the root. */
    private fun resolve(folder: Open, path: String): String? {
        val parts = if (folder.path.isEmpty()) ArrayList() else folder.path.split('/').toMutableList()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun channel(file: Open): FileChannel? {
        file.channel?.let { return it }
        val disk = data.file(file.path) ?: return null
        return try {
            FileChannel.open(disk, StandardOpenOption.READ).also { file.channel = it }
        } catch (_: IOException) {
            null
        }
    }

    private fun size(file: Open): Long? = try {
        channel(file)?.size()
    } catch (_: IOException) {
        null
    }

    /** Reads into the `iovec`s at [iovs] from where [file] is, or from [at] for `fd_pread` (which leaves the position). */
    private fun read(memory: Memory, file: Open, iovs: Int, count: Int, readPointer: Int, at: Long?): Errno {
        if (file.folder) return Errno.EISDIR
        val channel = channel(file) ?: return Errno.EIO
        var position = at ?: file.position
        var total = 0L
        try {
            for (i in 0 until count) {
                val buffer = memory.readInt(iovs + i * 8)
                val length = memory.readInt(iovs + i * 8 + 4)
                var done = 0
                while (done < length) {
                    val chunk = ByteBuffer.allocate(minOf(length - done, CHUNK))
                    val read = channel.read(chunk, position)
                    if (read <= 0) break
                    memory.write(buffer + done, chunk.array(), 0, read)
                    done += read
                    position += read
                }
                total += done
                if (done < length) break
            }
        } catch (_: IOException) {
            return Errno.EIO
        }
        if (at == null) file.position = position
        memory.writeI32(readPointer, total.toInt())
        return Errno.ESUCCESS
    }

    private fun stat(memory: Memory, at: Int, path: String, folder: Boolean): Errno {
        val size = if (folder) 0L else data.file(path)?.let { disk -> runCatching { Files.size(disk) }.getOrNull() } ?: return Errno.EIO
        memory.writeLong(at, 0L) // device
        memory.writeLong(at + 8, path.hashCode().toLong() and 0xFFFFFFFFL) // inode
        memory.writeByte(at + 16, (if (folder) DIRECTORY else REGULAR_FILE).toByte())
        memory.writeLong(at + 24, 1L) // links
        memory.writeLong(at + 32, size)
        memory.writeLong(at + 40, 0L)
        memory.writeLong(at + 48, 0L)
        memory.writeLong(at + 56, 0L)
        return Errno.ESUCCESS
    }

    /** `fd_readdir(fd, buf, buf_len, cookie, bufused)`: entries from the [cookie]th, as many bytes as fit. */
    private fun readFolder(memory: Memory, file: Open, args: LongArray): Errno {
        if (!file.folder) return Errno.ENOTDIR
        val buffer = args[1].toInt()
        val length = args[2].toInt()
        val entries = data.list(file.path)
        var used = 0
        var index = args[3].toInt()
        while (index < entries.size && used < length) {
            val (name, folder) = entries[index]
            val bytes = name.toByteArray(StandardCharsets.UTF_8)
            val entry = ByteBuffer.allocate(DIRENT + bytes.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            entry.putLong(index + 1L)
            entry.putLong((if (file.path.isEmpty()) name else "${file.path}/$name").hashCode().toLong() and 0xFFFFFFFFL)
            entry.putInt(bytes.size)
            entry.put((if (folder) DIRECTORY else REGULAR_FILE).toByte())
            entry.put(ByteArray(3))
            entry.put(bytes)
            val fits = minOf(entry.capacity(), length - used)
            memory.write(buffer + used, entry.array(), 0, fits)
            used += fits
            index++
        }
        memory.writeI32(args[4].toInt(), used)
        return Errno.ESUCCESS
    }

    /** One of WASI's functions, answered here; [body] answers null to hand the call to Chicory's. */
    private fun own(name: String, body: (Memory, LongArray) -> Errno?): HostFunction {
        val original = wasi.getValue(name)
        return HostFunction(original.module(), name, original.functionType()) { instance, args ->
            body(instance.memory(), args)?.let { longArrayOf(it.value.toLong()) } ?: original.handle().apply(instance, *args)
        }
    }

    /** A function whose first argument is a descriptor: this instance's own are answered by [body], the rest by Chicory. */
    private fun byFd(name: String, body: (Int, Memory, LongArray, Open) -> Errno): HostFunction = own(name) { memory, args ->
        val fd = args[0].toInt()
        open[fd]?.let { body(fd, memory, args, it) }
    }

    companion object {
        /** The root's descriptor: the first after standard input, output and error, as a WASI program expects its first preopen. */
        const val ROOT = 3
        private val ROOT_NAME = "/".toByteArray(StandardCharsets.UTF_8)

        /** Descriptors one instance may have open, its root included. */
        private const val MAX_OPEN = 64
        private const val CHUNK = 65_536
        private const val DIRENT = 24

        private const val PREOPEN_DIRECTORY: Byte = 0
        private const val DIRECTORY = 3
        private const val REGULAR_FILE = 4

        private const val WHENCE_SET = 0
        private const val WHENCE_CURRENT = 1
        private const val WHENCE_END = 2

        private const val OPEN_CREATE = 1
        private const val OPEN_DIRECTORY = 2
        private const val OPEN_EXCLUSIVE = 4
        private const val OPEN_TRUNCATE = 8
        private const val FD_APPEND = 1

        // WASI preview 1's rights, by bit.
        private const val FD_DATASYNC = 1L shl 0
        private const val FD_READ = 1L shl 1
        private const val FD_SEEK = 1L shl 2
        private const val FD_SYNC = 1L shl 4
        private const val FD_TELL = 1L shl 5
        private const val FD_WRITE = 1L shl 6
        private const val FD_ADVISE = 1L shl 7
        private const val FD_ALLOCATE = 1L shl 8
        private const val PATH_OPEN = 1L shl 13
        private const val FD_READDIR = 1L shl 14
        private const val PATH_READLINK = 1L shl 15
        private const val PATH_FILESTAT_GET = 1L shl 18
        private const val FD_FILESTAT_GET = 1L shl 21
        private const val FD_FILESTAT_SET_SIZE = 1L shl 22
        private const val POLL_FD_READWRITE = 1L shl 27

        private const val WRITE_RIGHTS = FD_WRITE or FD_ALLOCATE or FD_FILESTAT_SET_SIZE
        private const val FILE_RIGHTS =
            FD_DATASYNC or FD_READ or FD_SEEK or FD_SYNC or FD_TELL or FD_ADVISE or FD_FILESTAT_GET or POLL_FD_READWRITE
        private const val FOLDER_RIGHTS = PATH_OPEN or FD_READDIR or PATH_READLINK or PATH_FILESTAT_GET or FD_FILESTAT_GET
    }
}

/** The WASI preview 1 error numbers the file system answers with (Chicory's own list isn't public). */
enum class Errno(val value: Int) {
    ESUCCESS(0),
    EBADF(8),
    EEXIST(20),
    EINVAL(28),
    EIO(29),
    EISDIR(31),
    EMFILE(33),
    ENAMETOOLONG(37),
    ENOENT(44),
    ENOTDIR(54),
    ENOTSUP(58),
    EROFS(69),
    ESPIPE(70),
    ENOTCAPABLE(76)
}
