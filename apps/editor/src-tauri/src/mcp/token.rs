//! The MCP server's bearer token: 32 random bytes in hex, made once per
//! install and kept in `<config>/mcp-token` (owner-only on Unix).
//!
//! Every request must carry it (`Authorization: Bearer <token>`), so neither
//! another program nor another user of this computer that can reach the
//! loopback port, nor a web page that got past the Host/Origin checks, can
//! call the tools (which run server commands). The editor writes it into the
//! `.mcp.json` it generates, which the project's `.gitignore` leaves out.
//!
//! Per install rather than per run: `.mcp.json` is read when an agent's
//! session starts, so a token that changed on every editor start would break
//! every open session and rewrite a project file on every launch, for no gain:
//! anyone who can read the config folder can read the project too.

use std::path::Path;

use crate::error::Result;
use crate::fs::atomic::write_atomic;

pub const FILE: &str = "mcp-token";

const BYTES: usize = 32;

fn is_token(text: &str) -> bool {
    text.len() == BYTES * 2 && text.bytes().all(|b| b.is_ascii_hexdigit())
}

/// A fresh token.
pub fn generate() -> String {
    let mut bytes = [0u8; BYTES];
    getrandom::fill(&mut bytes).expect("the OS random number generator failed");
    hex::encode(bytes)
}

/// The install's token from [path], made and saved first if there's none (or
/// what's there isn't one).
pub fn load_or_create(path: &Path) -> Result<String> {
    if let Ok(text) = std::fs::read_to_string(path)
        && is_token(text.trim())
    {
        return Ok(text.trim().to_string());
    }
    let token = generate();
    write_atomic(path, token.as_bytes())?;
    #[cfg(unix)]
    {
        use crate::error::Context;
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))
            .context(|| format!("Couldn't restrict {}", path.display()))?;
    }
    Ok(token)
}

/// Whether an `Authorization` header carries [token]. Compared in constant
/// time, so the answer's timing doesn't give the token away byte by byte.
pub fn authorizes(header: Option<&str>, token: &str) -> bool {
    let Some(given) = header.and_then(|h| {
        let (scheme, value) = h.trim().split_once(' ')?;
        scheme.eq_ignore_ascii_case("bearer").then(|| value.trim())
    }) else {
        return false;
    };
    given.len() == token.len()
        && given
            .bytes()
            .zip(token.bytes())
            .fold(0u8, |diff, (a, b)| diff | (a ^ b))
            == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn made_once_and_kept() {
        let tmp = tempfile::tempdir().unwrap();
        let path = tmp.path().join("config").join(FILE);
        let token = load_or_create(&path).unwrap();
        assert!(is_token(&token));
        assert_eq!(load_or_create(&path).unwrap(), token);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(&path).unwrap().permissions().mode();
            assert_eq!(mode & 0o777, 0o600);
        }
        std::fs::write(&path, "not a token").unwrap();
        let replaced = load_or_create(&path).unwrap();
        assert!(is_token(&replaced));
        assert_ne!(replaced, token);
        assert_ne!(generate(), generate());
    }

    #[test]
    fn only_the_bearer_token_authorizes() {
        let token = "ab".repeat(32);
        assert!(authorizes(Some(&format!("Bearer {token}")), &token));
        assert!(authorizes(Some(&format!("bearer  {token} ")), &token));
        assert!(!authorizes(None, &token));
        assert!(!authorizes(Some(&token), &token), "no scheme");
        assert!(!authorizes(Some(&format!("Basic {token}")), &token));
        assert!(!authorizes(Some("Bearer "), &token));
        assert!(!authorizes(
            Some(&format!("Bearer {}", "ab".repeat(31))),
            &token
        ));
        assert!(!authorizes(
            Some(&format!("Bearer {}", "cd".repeat(32))),
            &token
        ));
    }
}
