package dev.netherforge.format

import java.io.File

actual object TestFiles {
    /** What a project loader skips; a dev server run from the editor leaves a whole world in .netherforge/. */
    private val SKIPPED = setOf(".git", ".netherforge")

    private val root = File(System.getenv("NETHERFORGE_REPO") ?: error("NETHERFORGE_REPO is not set; run tests through Gradle"))

    actual fun read(relative: String): String? = File(root, relative).takeIf { it.isFile }?.readText()

    actual fun write(relative: String, text: String) {
        File(root, relative).apply { parentFile.mkdirs() }.writeText(text)
    }

    actual fun list(relative: String): List<String> {
        val base = File(root, relative)
        return base.walkTopDown().onEnter {
            it.name !in SKIPPED
        }.filter { it.isFile }.map { it.relativeTo(base).invariantSeparatorsPath }.sorted().toList()
    }

    actual fun dirs(relative: String): List<String> = File(root, relative).listFiles()?.filter {
        it.isDirectory
    }?.map { it.name }?.sorted().orEmpty()

    actual fun sha256(relative: String): String = hex(File(root, relative).readBytes())

    actual fun sha256OfText(text: String): String = hex(text.encodeToByteArray())

    private fun hex(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
    }

    actual val updateGolden: Boolean = System.getenv("UPDATE_GOLDEN") == "1"
}
