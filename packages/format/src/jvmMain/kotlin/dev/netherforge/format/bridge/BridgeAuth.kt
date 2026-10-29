package dev.netherforge.format.bridge

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * How each end of the dev bridge proves it holds the token, without sending
 * it ([Bridge.hello], [Bridge.authenticate]): HMAC-SHA256 under the token over
 * both ends' nonces, with a label for whose proof it is, so the editor's
 * answers (which anyone that connects may ask for) never serve as the
 * plugin's. The editor's Rust backend makes the same proofs
 * (`bridge/protocol.rs`), and `session.ndjson`'s recorded ones are checked on
 * both sides.
 */
object BridgeAuth {
    private val random = SecureRandom()

    /** A new nonce: 24 random bytes, as hex. */
    fun nonce(): String = hex(ByteArray(24).also(random::nextBytes))

    /** The editor's proof of [token] for the plugin's nonce [plugin] and its own [editor]. */
    fun editorProof(token: String, plugin: String, editor: String) = hmac(token, "editor\n$plugin\n$editor")

    /** The plugin's proof of [token] for the editor's nonce [editor] and its own [plugin]. */
    fun pluginProof(token: String, editor: String, plugin: String) = hmac(token, "plugin\n$editor\n$plugin")

    /** Whether [given] is [expected], compared in constant time. */
    fun matches(expected: String, given: String?): Boolean =
        given != null && MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())

    /**
     * The editor's end of one connection's handshake, as the JVM's stand-ins for the editor play it (the runtime's
     * and the integration test's; the editor itself is Rust): [answer] the hello, then whether the plugin's
     * [Bridge.authenticate] [accepts].
     */
    class EditorSide(private val token: String) {
        private var plugin: String? = null
        private val editor = nonce()

        fun answer(hello: HelloParams, protocol: Int = Bridge.PROTOCOL): HelloResult {
            plugin = hello.nonce
            return HelloResult(protocol, editor, editorProof(token, hello.nonce, editor))
        }

        fun accepts(proof: AuthenticateParams): Boolean = plugin?.let { matches(pluginProof(token, editor, it), proof.proof) } == true
    }

    private fun hmac(token: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return hex(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
