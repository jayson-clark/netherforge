package dev.netherforge.network

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One connection's sealed lines, once the handshake is done: what keeps the network's traffic private and
 * unaltered on the wire, and what tells a server it's talking to its network's hub (only something holding the
 * secret can seal the [HubFrame.Welcome] it opens).
 *
 * Each direction has a key of its own, derived with HKDF-SHA256 from the shared secret, salted with both ends'
 * nonces (the hub's [HubFrame.Challenge] and the server's [ServerFrame.Hello]) and bound to the server's name, so
 * keys are new for every connection and never reused across servers. A line is AES-256-GCM over the frame's JSON,
 * its 96-bit nonce the count of lines sealed before it in that direction: a line that's altered, replayed, dropped,
 * reordered or sealed with another key doesn't open ([open] is null), and the connection is closed. A sealed line
 * is [PREFIX] and the ciphertext in base64, which no JSON line starts with.
 *
 * Sealing and opening each go in order, one line at a time ([seal] and [open] are synchronized): a side's writes
 * are sealed in the order they're queued, and its reads opened in the order they're read.
 */
class Channel private constructor(private val sealing: SecretKeySpec, private val opening: SecretKeySpec) {
    private var sealed = 0L
    private var opened = 0L

    /** [text] (a frame's JSON) as the line to send. */
    @Synchronized
    fun seal(text: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, sealing, GCMParameterSpec(TAG_BITS, nonce(sealed++)))
        return PREFIX + Base64.getEncoder().encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    /** The frame's JSON in a [line] the other end sealed, or null when it isn't one it sealed next (nothing is counted then). */
    @Synchronized
    fun open(line: String): String? {
        if (!line.startsWith(PREFIX)) return null
        val bytes = try {
            Base64.getDecoder().decode(line.substring(PREFIX.length))
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, opening, GCMParameterSpec(TAG_BITS, nonce(opened)))
            String(cipher.doFinal(bytes), Charsets.UTF_8).also { opened++ }
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    companion object {
        /** What starts a sealed line. */
        const val PREFIX = "~"

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128

        /** The hub's end of the connection with [server], whose challenge was [hubNonce] and hello [serverNonce]. */
        fun hub(secret: String, hubNonce: String, serverNonce: String, server: String): Channel = Channel(
            sealing = key(secret, hubNonce, serverNonce, server, "hub to server"),
            opening = key(secret, hubNonce, serverNonce, server, "server to hub")
        )

        /** [server]'s end of its connection to the hub. */
        fun server(secret: String, hubNonce: String, serverNonce: String, server: String): Channel = Channel(
            sealing = key(secret, hubNonce, serverNonce, server, "server to hub"),
            opening = key(secret, hubNonce, serverNonce, server, "hub to server")
        )

        /** HKDF-SHA256 (RFC 5869): extract with both nonces as the salt, then one block of expand for [direction]. */
        private fun key(secret: String, hubNonce: String, serverNonce: String, server: String, direction: String): SecretKeySpec {
            val prk = hmac("$hubNonce\n$serverNonce".toByteArray(Charsets.UTF_8), secret.toByteArray(Charsets.UTF_8))
            val info = "netherforge network v${Protocol.VERSION}: $direction\n$server".toByteArray(Charsets.UTF_8)
            return SecretKeySpec(hmac(prk, info + byteArrayOf(1)), "AES")
        }

        private fun hmac(key: ByteArray, message: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(message)
        }

        /** A line's GCM nonce: four zero bytes, then the count of lines sealed before it in that direction. */
        private fun nonce(count: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(count).array()
    }
}
