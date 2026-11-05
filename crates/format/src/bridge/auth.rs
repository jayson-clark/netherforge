//! How each end of the dev bridge proves it holds the token, without sending
//! it (format's `BridgeAuth`): HMAC-SHA256 under the token over both ends'
//! nonces, with a label for whose proof it is, so the editor's answers (which
//! anyone that connects may ask for) never serve as the plugin's.

use sha2::{Digest, Sha256};

/// A new nonce: 24 random bytes, as hex.
pub fn nonce() -> String {
    let mut bytes = [0u8; 24];
    getrandom::fill(&mut bytes).expect("the system has randomness");
    hex(&bytes)
}

/// Whether `nonce` is one an end may send: hex, 32 to 64 digits.
pub fn is_nonce(nonce: &str) -> bool {
    (32..=64).contains(&nonce.len()) && nonce.bytes().all(|b| b.is_ascii_hexdigit())
}

/// The editor's proof of `token` for the plugin's nonce `plugin` and its own `editor`.
pub fn editor_proof(token: &str, plugin: &str, editor: &str) -> String {
    hex(&hmac_sha256(
        token.as_bytes(),
        format!("editor\n{plugin}\n{editor}").as_bytes(),
    ))
}

/// The plugin's proof of `token` for the editor's nonce `editor` and its own `plugin`.
pub fn plugin_proof(token: &str, editor: &str, plugin: &str) -> String {
    hex(&hmac_sha256(
        token.as_bytes(),
        format!("plugin\n{editor}\n{plugin}").as_bytes(),
    ))
}

/// Whether `given` is `expected`, compared in constant time.
pub fn matches(expected: &str, given: Option<&str>) -> bool {
    let Some(given) = given else { return false };
    let (a, b) = (expected.as_bytes(), given.as_bytes());
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

/// HMAC-SHA256 (RFC 2104).
pub fn hmac_sha256(key: &[u8], message: &[u8]) -> [u8; 32] {
    const BLOCK: usize = 64;
    let mut block = [0u8; BLOCK];
    if key.len() > BLOCK {
        block[..32].copy_from_slice(&Sha256::digest(key));
    } else {
        block[..key.len()].copy_from_slice(key);
    }
    let mut inner = Sha256::new();
    inner.update(block.map(|b| b ^ 0x36));
    inner.update(message);
    let mut outer = Sha256::new();
    outer.update(block.map(|b| b ^ 0x5c));
    outer.update(inner.finalize());
    outer.finalize().into()
}

/// Lowercase hex.
pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hmac_is_rfc_4231s() {
        // RFC 4231, test case 2.
        assert_eq!(
            hex(&hmac_sha256(b"Jefe", b"what do ya want for nothing?")),
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"
        );
        // Test case 6: a key longer than a block.
        assert_eq!(
            hex(&hmac_sha256(
                &[0xaa; 131],
                b"Test Using Larger Than Block-Size Key - Hash Key First"
            )),
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"
        );
    }

    #[test]
    fn a_proof_is_good_only_as_its_ends() {
        let (plugin, editor) = (nonce(), nonce());
        assert!(is_nonce(&plugin) && plugin != editor);
        let theirs = plugin_proof("token", &editor, &plugin);
        assert!(matches(
            &theirs,
            Some(&plugin_proof("token", &editor, &plugin))
        ));
        assert!(!matches(
            &theirs,
            Some(&editor_proof("token", &plugin, &editor))
        ));
        assert!(!matches(
            &theirs,
            Some(&plugin_proof("other", &editor, &plugin))
        ));
        assert!(!matches(&theirs, None));
    }
}
