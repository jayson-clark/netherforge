//! The handshake's proof and a connection's sealed lines (`:network`'s `Auth` and `Channel`), exactly as the hub
//! makes and opens them.
//!
//! - **The proof**: HMAC-SHA256 under the secret of `hello\n<hub nonce>\n<server>\n<server nonce>\n<credential>`, in
//!   hex. The secret never crosses.
//! - **A channel**: a key per direction, HKDF-SHA256 (extract salted with `<hub nonce>\n<server nonce>`, one block of
//!   expand with `netherforge network v3: <direction>\n<server>\n<credential>`), AES-256-GCM over each frame's JSON,
//!   the 96-bit nonce four zero bytes and the count of lines sealed before it that way. A sealed line is `~` and the
//!   ciphertext with its tag in base64. A line altered, replayed, dropped or reordered doesn't open.

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use hmac::{Hmac, Mac};
use ring::aead::{AES_256_GCM, Aad, LessSafeKey, Nonce, UnboundKey};
use sha2::Sha256;

use super::protocol::VERSION;

/// What starts a sealed line (no JSON line does).
pub const PREFIX: &str = "~";

fn hmac(key: &[u8], message: &[u8]) -> [u8; 32] {
    let mut mac = Hmac::<Sha256>::new_from_slice(key).expect("HMAC takes a key of any length");
    mac.update(message);
    mac.finalize().into_bytes().into()
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// A nonce of 24 random bytes, as hex (`Auth.newNonce`).
pub fn new_nonce() -> String {
    let mut bytes = [0u8; 24];
    getrandom::fill(&mut bytes).expect("the system has randomness");
    hex(&bytes)
}

/// A new secret: 32 random bytes, as hex (`Auth.newSecret`).
pub fn new_secret() -> String {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes).expect("the system has randomness");
    hex(&bytes)
}

/// The hello's proof of `secret` (`Auth.proof`).
pub fn proof(
    secret: &str,
    nonce: &str,
    server: &str,
    server_nonce: &str,
    credential: Option<&str>,
) -> String {
    let message = format!(
        "hello\n{nonce}\n{server}\n{server_nonce}\n{}",
        credential.unwrap_or("")
    );
    hex(&hmac(secret.as_bytes(), message.as_bytes()))
}

/// Whether `proof` is the proof of `secret`, compared in constant time (`Auth.verify`).
pub fn verify(
    secret: &str,
    nonce: &str,
    server: &str,
    server_nonce: &str,
    credential: Option<&str>,
    given: &str,
) -> bool {
    let wanted = proof(secret, nonce, server, server_nonce, credential);
    wanted.len() == given.len()
        && wanted
            .bytes()
            .zip(given.bytes())
            .fold(0u8, |acc, (a, b)| acc | (a ^ b))
            == 0
}

/// Whether `nonce` is one a server may send: hex, 32 to 64 digits (`Auth.isNonce`).
pub fn is_nonce(nonce: &str) -> bool {
    (32..=64).contains(&nonce.len())
        && nonce
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

/// One connection's sealed lines, one end's.
pub struct Channel {
    sealing: LessSafeKey,
    opening: LessSafeKey,
    sealed: u64,
    opened: u64,
}

/// The two directions' names, as the keys are bound to them.
const TO_SERVER: &str = "hub to server";
const TO_HUB: &str = "server to hub";

fn key(
    secret: &str,
    hub_nonce: &str,
    server_nonce: &str,
    server: &str,
    credential: Option<&str>,
    direction: &str,
) -> LessSafeKey {
    let prk = hmac(
        format!("{hub_nonce}\n{server_nonce}").as_bytes(),
        secret.as_bytes(),
    );
    let mut info = format!(
        "netherforge network v{VERSION}: {direction}\n{server}\n{}",
        credential.unwrap_or("")
    )
    .into_bytes();
    info.push(1);
    let bytes = hmac(&prk, &info);
    LessSafeKey::new(UnboundKey::new(&AES_256_GCM, &bytes).expect("a 32-byte key"))
}

fn nonce(count: u64) -> Nonce {
    let mut bytes = [0u8; 12];
    bytes[4..].copy_from_slice(&count.to_be_bytes());
    Nonce::assume_unique_for_key(bytes)
}

impl Channel {
    /// `server`'s end of its connection to the hub.
    pub fn server(
        secret: &str,
        hub_nonce: &str,
        server_nonce: &str,
        server: &str,
        credential: Option<&str>,
    ) -> Channel {
        Channel {
            sealing: key(secret, hub_nonce, server_nonce, server, credential, TO_HUB),
            opening: key(
                secret,
                hub_nonce,
                server_nonce,
                server,
                credential,
                TO_SERVER,
            ),
            sealed: 0,
            opened: 0,
        }
    }

    /// The hub's end (a test's hub, which plays the proxy's).
    pub fn hub(
        secret: &str,
        hub_nonce: &str,
        server_nonce: &str,
        server: &str,
        credential: Option<&str>,
    ) -> Channel {
        Channel {
            sealing: key(
                secret,
                hub_nonce,
                server_nonce,
                server,
                credential,
                TO_SERVER,
            ),
            opening: key(secret, hub_nonce, server_nonce, server, credential, TO_HUB),
            sealed: 0,
            opened: 0,
        }
    }

    /// `text` (a frame's JSON) as the line to send.
    pub fn seal(&mut self, text: &str) -> String {
        let mut bytes = text.as_bytes().to_vec();
        self.sealing
            .seal_in_place_append_tag(nonce(self.sealed), Aad::empty(), &mut bytes)
            .expect("a line is far below GCM's limit");
        self.sealed += 1;
        format!("{PREFIX}{}", STANDARD.encode(bytes))
    }

    /// The frame's JSON in a line the other end sealed next, or none (nothing is counted then).
    pub fn open(&mut self, line: &str) -> Option<String> {
        let mut bytes = STANDARD.decode(line.strip_prefix(PREFIX)?).ok()?;
        let plain = self
            .opening
            .open_in_place(nonce(self.opened), Aad::empty(), &mut bytes)
            .ok()?;
        let text = String::from_utf8(plain.to_vec()).ok()?;
        self.opened += 1;
        Some(text)
    }
}
