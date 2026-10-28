import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

/**
 * Downloads one Paper server build through PaperMC's downloads API and checks
 * it against the SHA-256 the API gives. [jar] is in the Gradle user home, so
 * every checkout shares it; one already there isn't fetched again.
 */
abstract class DownloadPaper : DefaultTask() {
    @get:Input
    abstract val minecraft: Property<String>

    @get:Input
    abstract val build: Property<Int>

    @get:OutputFile
    abstract val jar: RegularFileProperty

    init {
        outputs.upToDateWhen { jar.get().asFile.exists() }
    }

    @TaskAction
    fun download() {
        val target = jar.get().asFile
        if (target.exists()) return
        val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build()
        fun get(url: String): HttpResponse<ByteArray> = http.send(
            HttpRequest.newBuilder(URI(url)).header("User-Agent", "netherforge-build").build(),
            HttpResponse.BodyHandlers.ofByteArray()
        )

        val api = "https://fill.papermc.io/v3/projects/paper/versions/${minecraft.get()}/builds/${build.get()}"
        val info = get(api).body().decodeToString()
        val download =
            Regex(
                "\"server:default\"\\s*:\\s*\\{.*?\"sha256\"\\s*:\\s*\"([0-9a-f]+)\".*?\"url\"\\s*:\\s*\"([^\"]+)\"",
                RegexOption.DOT_MATCHES_ALL
            ).find(info) ?: throw GradleException("No server download in $api: $info")
        val (sha256, url) = download.destructured
        val bytes = get(url).body()
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (actual != sha256) throw GradleException("Paper download from $url has SHA-256 $actual, expected $sha256")
        // Written beside it and moved into place: another build (another checkout) may be looking for the same jar.
        target.parentFile.mkdirs()
        val partial = File.createTempFile(target.name, ".part", target.parentFile)
        try {
            partial.writeBytes(bytes)
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            partial.delete()
        }
    }
}
