package dev.netherforge.plugin.project

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.project.BundleKind
import dev.netherforge.format.project.BundleManifest
import dev.netherforge.format.project.BundleSignature
import dev.netherforge.format.project.BundledPackage
import dev.netherforge.format.project.FormatVersion
import dev.netherforge.plugin.TestServer
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Signed bundles: who made one, whether a network checked it, and a server that runs only those it trusts. */
class BundleSignaturesTest {
    /** A key pair as `netherforge keygen` makes one: the private key, and the public one as a server lists it. */
    private class Key {
        private val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val private: PrivateKey get() = pair.private

        // The raw 32 bytes are the X.509 encoding's last 32.
        val public = "ed25519:" + Base64.getEncoder().encodeToString(pair.public.encoded.takeLast(32).toByteArray())

        fun sign(manifest: BundleManifest): BundleSignature {
            val signature = Signature.getInstance("Ed25519").run {
                initSign(private)
                update(manifest.signedText().toByteArray())
                sign()
            }
            return BundleSignature(public, Base64.getEncoder().encodeToString(signature))
        }
    }

    private val manifest = BundleManifest(FormatVersion.CURRENT, "test", mapOf("test" to BundledPackage("1.0.0", "abc")))

    @Test
    fun `a signature verifies over the manifest without its signatures, so a countersignature adds to it`() {
        val author = Key()
        val network = Key()
        val signed = manifest.copy(signatures = listOf(author.sign(manifest)))
        val countersigned = signed.copy(signatures = signed.signatures!! + network.sign(signed))
        assertNull(BundleSignatures.problem(countersigned, BundlesConfig(requireSignature = true, trustedKeys = listOf(network.public))))
        val strange = Key()
        assertTrue(BundleSignatures.problem(countersigned, BundlesConfig(true, listOf(strange.public)))!!.contains("aren't among them"))
        assertTrue(BundleSignatures.problem(manifest, BundlesConfig(true, listOf(network.public)))!!.contains("isn't signed"))
        assertNull(BundleSignatures.problem(manifest, BundlesConfig()), "not required, none needed")

        val changed = countersigned.copy(packages = mapOf("test" to BundledPackage("1.0.1", "abc")))
        assertTrue(BundleSignatures.problem(changed, BundlesConfig())!!.contains("doesn't match"))
        assertFalse(BundleSignatures.verifies("rsa:xyz", "abc", "text"))
    }

    @Test
    fun `a server that requires a trusted signature runs a bundle that has one, and refuses one that doesn't`() {
        val key = Key()
        val files = mapOf("test/" + TestServer.MANIFEST to TestServer.manifest(), "test/modules/t/init.lua" to """log("ran")""")
        for (trusted in listOf(key.public, Key().public)) {
            TestServer(
                files,
                start = false,
                resolvesPackages = false,
                writeManifest = false,
                bundles = BundlesConfig(requireSignature = true, trustedKeys = listOf(trusted))
            ).use { server ->
                val hash = ProjectFiles.hash(DiskProjectSource(server.project.resolve("test")))
                val bundle = BundleManifest(FormatVersion.CURRENT, "test", mapOf("test" to BundledPackage("1.0.0", hash)))
                server.project.resolve(
                    BundleManifest.FILE_NAME
                ).writeText(BundleKind.write(bundle.copy(signatures = listOf(key.sign(bundle)))))
                server.start()
                if (trusted == key.public) {
                    assertTrue(server.runtime.session.running, "${server.runtime.currentProblems()}")
                    assertEquals(listOf("ran"), server.logs)
                } else {
                    assertFalse(server.runtime.session.running)
                    assertNotNull(server.runtime.currentProblems().singleOrNull { it.code == ProblemCodes.RUNTIME_BUNDLE_SIGNATURE.code })
                }
            }
        }
    }
}
