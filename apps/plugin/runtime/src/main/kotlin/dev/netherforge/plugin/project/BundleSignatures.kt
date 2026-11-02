package dev.netherforge.plugin.project

import dev.netherforge.format.project.BundleManifest
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** `bundles:` in `config.yml`: whether a bundle must be signed by a key the server trusts, and which keys. */
data class BundlesConfig(val requireSignature: Boolean = false, val trustedKeys: List<String> = emptyList())

/**
 * Bundles' signatures (`netherforge build --sign`, `netherforge sign`): Ed25519 over the manifest as it's written
 * without its signatures ([BundleManifest.signedText]). A key is `ed25519:` and its 32 bytes in base64.
 */
object BundleSignatures {
    /** The DER that wraps a raw Ed25519 key as the JDK takes it (an X.509 SubjectPublicKeyInfo). */
    private val PREFIX = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

    /** Whether [signature] is [key]'s over [text]; false for a key or signature that isn't one. */
    fun verifies(key: String, signature: String, text: String): Boolean = try {
        val raw = Base64.getDecoder().decode(key.removePrefix(KEY_PREFIX).takeIf { key.startsWith(KEY_PREFIX) } ?: return false)
        if (raw.size != 32) return false
        val public = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(PREFIX + raw))
        Signature.getInstance("Ed25519").run {
            initVerify(public)
            update(text.toByteArray(Charsets.UTF_8))
            verify(Base64.getDecoder().decode(signature))
        }
    } catch (_: Exception) {
        false
    }

    /**
     * What's wrong with [manifest]'s signatures under [config], or null: a signature that doesn't verify (the bundle or
     * its manifest was changed), or, when one is required, none by a trusted key.
     */
    fun problem(manifest: BundleManifest, config: BundlesConfig): String? {
        val text = manifest.signedText()
        val signatures = manifest.signatures.orEmpty()
        signatures.firstOrNull { !verifies(it.key, it.signature, text) }?.let {
            return "The bundle's signature by ${it.key} doesn't match it: it was changed after it was signed"
        }
        if (!config.requireSignature) return null
        val trusted = config.trustedKeys.toSet()
        if (signatures.none { it.key in trusted }) {
            return if (signatures.isEmpty()) {
                "This server runs only bundles signed by a key it trusts (bundles.require-signature), and this one isn't signed"
            } else {
                "This server runs only bundles signed by a key it trusts (bundles.trusted-keys), and this one's signers " +
                    "(${signatures.joinToString { it.key }}) aren't among them"
            }
        }
        return null
    }

    const val KEY_PREFIX = "ed25519:"
}
