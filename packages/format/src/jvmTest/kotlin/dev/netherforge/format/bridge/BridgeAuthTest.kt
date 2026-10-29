package dev.netherforge.format.bridge

import dev.netherforge.format.TestFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The dev bridge's handshake proofs: the recorded session's are what [BridgeAuth] makes for its token (the editor's
 * Rust tests check the same lines), and each side's proof is good only as that side's.
 */
class BridgeAuthTest {
    private val lines = TestFiles.read("packages/format/testdata/bridge/session.ndjson")!!.lines().filter { it.isNotBlank() }

    private fun message(line: String) = (JsonRpc.decode(line) as RpcFrame.Single).message

    @Test
    fun `the recorded handshake's proofs are the token's`() {
        val hello = Bridge.hello.decodeParams((message(lines[0]) as RpcRequest).params)
        val answer = Bridge.hello.decodeResult((message(lines[1]) as RpcResponse).result!!)
        val proof = Bridge.authenticate.decodeParams((message(lines[2]) as RpcRequest).params)
        assertEquals(BridgeAuth.editorProof("t0k3n", hello.nonce, answer.nonce!!), answer.proof)
        assertEquals(BridgeAuth.pluginProof("t0k3n", answer.nonce!!, hello.nonce), proof.proof)
        // The token itself never appears on the wire.
        assertTrue(lines.take(4).none { "t0k3n" in it })
    }

    @Test
    fun `the editor's side answers once and accepts only the plugin's proof`() {
        val editor = BridgeAuth.EditorSide("token")
        val nonce = BridgeAuth.nonce()
        val answer = editor.answer(HelloParams(nonce, Bridge.PROTOCOL, "v", "26.3", "/p"))
        assertTrue(BridgeAuth.matches(BridgeAuth.editorProof("token", nonce, answer.nonce!!), answer.proof))
        assertTrue(editor.accepts(AuthenticateParams(BridgeAuth.pluginProof("token", answer.nonce!!, nonce))))
        // The editor's own proof, another token's, or one over other nonces doesn't pass for the plugin's.
        assertFalse(editor.accepts(AuthenticateParams(answer.proof!!)))
        assertFalse(editor.accepts(AuthenticateParams(BridgeAuth.pluginProof("other", answer.nonce!!, nonce))))
        assertFalse(editor.accepts(AuthenticateParams(BridgeAuth.pluginProof("token", BridgeAuth.nonce(), nonce))))
    }
}
