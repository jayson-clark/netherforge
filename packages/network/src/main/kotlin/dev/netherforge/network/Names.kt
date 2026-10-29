package dev.netherforge.network

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The names the network carries, and the rules both ends hold them to: a channel's or map's full name is its
 * package's namespace and its own name (`shop:sales`), so packages can't collide and the hub can count what each
 * package keeps.
 */
object NetworkNames {
    /** A server as a proxy names it (`velocity.toml`'s `[servers]`). */
    private val SERVER = Regex("""[A-Za-z0-9_.\-]{1,64}""")

    /** A namespace, as format's `Names` has it. */
    private val NAMESPACE = Regex("""[a-z0-9_.\-]{1,64}""")

    /** A channel's or map's own name: words separated by `.`, `/`, `-` or `_`. */
    private val NAME = Regex("""[a-z0-9_.\-/]{1,64}""")

    const val MAX_KEY_LENGTH = 256

    fun isServer(name: String) = SERVER.matches(name)

    fun isName(name: String) = NAME.matches(name)

    /** Whether [full] is `namespace:name`. */
    fun isFull(full: String): Boolean {
        val colon = full.indexOf(':')
        return colon > 0 && NAMESPACE.matches(full.substring(0, colon)) && isName(full.substring(colon + 1))
    }

    /** The namespace of a full name (`shop` for `shop:sales`). */
    fun namespaceOf(full: String) = full.substringBefore(':')

    fun isKey(key: String) = key.isNotEmpty() && key.length <= MAX_KEY_LENGTH && key.none { it.isISOControl() }
}

/**
 * The shared secret, proved without sending it: the server answers the hub's nonce with HMAC-SHA256 of the nonce and
 * its own name under the secret, so a proof is good for one connection and one name.
 */
object Auth {
    private val random = SecureRandom()

    /** A new secret for a hub that has none yet: 32 random bytes, as hex. */
    fun newSecret(): String = hex(ByteArray(32).also(random::nextBytes))

    fun newNonce(): String = hex(ByteArray(24).also(random::nextBytes))

    /** The hello's proof of [secret]: over the hub's [nonce], the [server]'s name and the server's own [serverNonce]. */
    fun proof(secret: String, nonce: String, server: String, serverNonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return hex(mac.doFinal("hello\n$nonce\n$server\n$serverNonce".toByteArray(Charsets.UTF_8)))
    }

    /** Whether [proof] is [proof] of [secret], compared in constant time. */
    fun verify(secret: String, nonce: String, server: String, serverNonce: String, proof: String): Boolean =
        MessageDigest.isEqual(proof(secret, nonce, server, serverNonce).toByteArray(), proof.toByteArray())

    /** Whether [nonce] is one a server may send: what [newNonce] makes (hex, 32 to 64 digits). */
    fun isNonce(nonce: String): Boolean = nonce.length in 32..64 && nonce.all { it in '0'..'9' || it in 'a'..'f' }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
