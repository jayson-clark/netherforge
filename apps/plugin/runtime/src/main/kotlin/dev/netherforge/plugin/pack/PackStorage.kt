package dev.netherforge.plugin.pack

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `resource-pack.storage:` in `config.yml`: an S3-compatible store (AWS, Cloudflare R2, MinIO) the server uploads its
 * packs to, players fetching them from [publicUrl] instead of from this server. Packs are named by their SHA-1, so
 * servers running the same bundle share one upload, and one that's there already isn't uploaded again.
 */
data class PackStorageConfig(
    val endpoint: String,
    val bucket: String,
    val prefix: String = "",
    val region: String = "auto",
    /** Where players fetch from: the bucket's public address (a CDN in front of it, or the bucket's own). */
    val publicUrl: String,
    val accessKey: String,
    val secretKey: String
) {
    /** Where players fetch the pack with [sha1]. */
    fun urlOf(sha1: String) = publicUrl.trimEnd('/') + "/" + keyOf(sha1)

    fun keyOf(sha1: String) = listOf(prefix.trim('/'), "$sha1.zip").filter { it.isNotEmpty() }.joinToString("/")
}

/** The store, over HTTP with AWS Signature Version 4 (path-style: `<endpoint>/<bucket>/<key>`). */
class PackStorage(private val config: PackStorageConfig) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    /** Puts the pack [bytes] (whose SHA-1 is [sha1]) in the store, unless it's there already. True when it uploaded. */
    fun upload(sha1: String, bytes: ByteArray): Boolean {
        val key = config.keyOf(sha1)
        val head = client.send(signed("HEAD", key, ByteArray(0)).build(), HttpResponse.BodyHandlers.discarding())
        if (head.statusCode() == 200) return false
        val put = client.send(
            signed("PUT", key, bytes, "application/zip").PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
            HttpResponse.BodyHandlers.ofString()
        )
        if (put.statusCode() !in 200..299) throw java.io.IOException("the store answered ${put.statusCode()}: ${put.body().take(300)}")
        return true
    }

    private fun signed(method: String, key: String, body: ByteArray, contentType: String? = null): HttpRequest.Builder {
        val uri = URI("${config.endpoint.trimEnd('/')}/${config.bucket}/${encode(key)}")
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        val stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
        val day = stamp.substring(0, 8)
        val hash = hex(sha256(body))
        val headers = sortedMapOf("host" to uri.authority, "x-amz-content-sha256" to hash, "x-amz-date" to stamp)
        if (contentType != null) headers["content-type"] = contentType
        val names = headers.keys.joinToString(";")
        val canonical = listOf(method, uri.rawPath, "", headers.entries.joinToString("\n") { "${it.key}:${it.value}" }, "", names, hash)
            .joinToString("\n")
        val scope = "$day/${config.region}/s3/aws4_request"
        val toSign = listOf("AWS4-HMAC-SHA256", stamp, scope, hex(sha256(canonical.toByteArray()))).joinToString("\n")
        var signing = hmac("AWS4${config.secretKey}".toByteArray(), day)
        for (part in listOf(config.region, "s3", "aws4_request")) signing = hmac(signing, part)
        val signature = hex(hmac(signing, toSign))
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2))
            .header("x-amz-content-sha256", hash)
            .header("x-amz-date", stamp)
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=${config.accessKey}/$scope, SignedHeaders=$names, Signature=$signature")
        contentType?.let { builder.header("Content-Type", it) }
        if (method == "HEAD") builder.method("HEAD", HttpRequest.BodyPublishers.noBody())
        return builder
    }

    private fun encode(key: String) = key.split('/').joinToString("/") { URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hmac(key: ByteArray, text: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(text.toByteArray())
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
