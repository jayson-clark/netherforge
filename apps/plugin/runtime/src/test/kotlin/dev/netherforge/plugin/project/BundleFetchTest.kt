package dev.netherforge.plugin.project

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A bundle by URL: fetched once, checked against its hash, unpacked whole and never outside its folder. */
class BundleFetchTest {
    private val served = HashMap<String, ByteArray>()
    private val requests = AtomicInteger()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            requests.incrementAndGet()
            val body = served[exchange.requestURI.path]
            if (body == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.write(body)
            }
            exchange.close()
        }
        start()
    }
    private val cache = createTempDirectory("bundles")

    @AfterTest
    fun stop() = server.stop(0)

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    @Test
    fun `a bundle is fetched once, checked, and unpacked into the cache`() {
        val bytes = zip("netherforge-bundle.json" to "{}", "arena/netherforge.json" to "{ }")
        served["/arena-1.0.0.nfbundle"] = bytes
        val reference = "${url("/arena-1.0.0.nfbundle")}#sha256=${sha256(bytes)}"
        val folder = BundleFetch.folder(reference, cache)
        assertEquals("{ }", folder.resolve("arena/netherforge.json").readText())
        assertEquals(1, requests.get())
        assertEquals(folder, BundleFetch.folder(reference, cache))
        assertEquals(1, requests.get(), "from the cache")
    }

    @Test
    fun `a file that isn't what the reference names, or that reaches outside its folder, is refused`() {
        val bytes = zip("netherforge-bundle.json" to "{}")
        served["/x.nfbundle"] = bytes
        val wrong = assertFailsWith<java.io.IOException> { BundleFetch.folder("${url("/x.nfbundle")}#sha256=${"0".repeat(64)}", cache) }
        assertTrue(wrong.message!!.contains("isn't the bundle the project names"), wrong.message)
        assertFailsWith<IllegalArgumentException> { BundleFetch.folder(url("/x.nfbundle"), cache) }

        val evil = zip("../escaped.txt" to "gotcha")
        served["/evil.nfbundle"] = evil
        assertFailsWith<java.io.IOException> { BundleFetch.folder("${url("/evil.nfbundle")}#sha256=${sha256(evil)}", cache) }
        assertFalse(Files.exists(cache.parent.resolve("escaped.txt")))
        assertFalse(Files.exists(cache.resolve(sha256(evil))), "nothing half unpacked is left")
        assertTrue(BundleFetch.isReference("https://example.com/x.nfbundle#sha256=ab"))
        assertFalse(BundleFetch.isReference("projects/arena"))
    }
}
