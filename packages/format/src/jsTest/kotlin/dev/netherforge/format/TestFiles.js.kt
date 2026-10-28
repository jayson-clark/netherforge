package dev.netherforge.format

@JsModule("node:fs")
@JsNonModule
private external object Fs {
    fun readFileSync(path: String, encoding: String): String
    fun readFileSync(path: String): dynamic
    fun writeFileSync(path: String, data: String)
    fun existsSync(path: String): Boolean
    fun statSync(path: String): dynamic
    fun readdirSync(path: String): Array<String>
    fun mkdirSync(path: String, options: dynamic)
}

@JsModule("node:crypto")
@JsNonModule
private external object Crypto {
    fun createHash(algorithm: String): dynamic
}

private external val process: dynamic

actual object TestFiles {
    /** What a project loader skips; a dev server run from the editor leaves a whole world in .netherforge/. */
    private val SKIPPED = setOf(".git", ".netherforge")

    private val root: String = (process.env.NETHERFORGE_REPO as String?) ?: error("NETHERFORGE_REPO is not set; run tests through Gradle")

    private fun abs(relative: String) = if (relative.isEmpty()) root else "$root/$relative"

    private fun isFile(path: String) = Fs.existsSync(path) && (Fs.statSync(path).isFile() as Boolean)

    private fun isDir(path: String) = Fs.existsSync(path) && (Fs.statSync(path).isDirectory() as Boolean)

    actual fun read(relative: String): String? = abs(relative).takeIf { isFile(it) }?.let { Fs.readFileSync(it, "utf8") }

    actual fun write(relative: String, text: String) {
        val path = abs(relative)
        Fs.mkdirSync(path.substringBeforeLast('/'), js("({ recursive: true })"))
        Fs.writeFileSync(path, text)
    }

    actual fun list(relative: String): List<String> {
        val out = mutableListOf<String>()
        fun walk(dir: String, prefix: String) {
            for (name in Fs.readdirSync(dir)) {
                val path = "$dir/$name"
                if (isDir(path)) {
                    if (name !in SKIPPED) walk(path, "$prefix$name/")
                } else {
                    out += "$prefix$name"
                }
            }
        }
        if (isDir(abs(relative))) walk(abs(relative), "")
        return out.sorted()
    }

    actual fun dirs(relative: String): List<String> {
        val base = abs(relative)
        if (!isDir(base)) return emptyList()
        return Fs.readdirSync(base).filter { isDir("$base/$it") }.sorted()
    }

    actual fun sha256(relative: String): String = Crypto.createHash("sha256").update(Fs.readFileSync(abs(relative))).digest("hex") as String

    actual fun sha256OfText(text: String): String = Crypto.createHash("sha256").update(text, "utf8").digest("hex") as String

    actual val updateGolden: Boolean = process.env.UPDATE_GOLDEN == "1"
}
