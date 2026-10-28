//! The `nfasset://` protocol: serves the client-asset cache to the webview so
//! textures and models load as URLs.
//!
//! `nfasset://localhost/<version>/<path under client/>` on macOS and Linux;
//! Windows (WebView2) serves custom schemes as
//! `http://nfasset.localhost/<version>/<path>`. Either way the request path is
//! `/<version>/<path>`. Tauri's `convertFileSrc(\`${version}/${path}\`, 'nfasset')`
//! builds the right form per OS; it percent-encodes the slashes, so we decode
//! the whole path before splitting it.

use std::path::{Path, PathBuf};

use crate::fs::scope;

use super::version;

#[derive(Debug, PartialEq, Eq)]
pub enum AssetError {
    BadRequest,
    NotFound,
}

/// The cached file a request path names, refusing anything outside
/// `<minecraft_root>/<version>/client/`.
pub fn resolve(minecraft_root: &Path, request_path: &str) -> Result<PathBuf, AssetError> {
    let decoded = percent_encoding::percent_decode_str(request_path)
        .decode_utf8()
        .map_err(|_| AssetError::BadRequest)?;
    let trimmed = decoded.trim_start_matches('/');
    let (minecraft, rest) = trimmed.split_once('/').ok_or(AssetError::BadRequest)?;
    if !version::is_release(minecraft) {
        return Err(AssetError::BadRequest);
    }
    let segments = scope::segments(rest).map_err(|_| AssetError::BadRequest)?;
    let client = minecraft_root.join(minecraft).join("client");
    let mut path = client.clone();
    path.extend(segments);

    let real_client = dunce::canonicalize(&client).map_err(|_| AssetError::NotFound)?;
    let real = dunce::canonicalize(&path).map_err(|_| AssetError::NotFound)?;
    if !real.starts_with(&real_client) {
        return Err(AssetError::BadRequest);
    }
    if !real.is_file() {
        return Err(AssetError::NotFound);
    }
    Ok(real)
}

pub fn content_type(path: &Path) -> &'static str {
    let extension = path
        .extension()
        .and_then(|e| e.to_str())
        .map(str::to_ascii_lowercase);
    match extension.as_deref() {
        Some("png") => "image/png",
        Some("json" | "mcmeta") => "application/json",
        Some("txt") => "text/plain; charset=utf-8",
        Some("ogg") => "audio/ogg",
        Some("ttf") => "font/ttf",
        Some("otf") => "font/otf",
        Some("vsh" | "fsh" | "glsl") => "text/plain; charset=utf-8",
        _ => "application/octet-stream",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cache() -> (tempfile::TempDir, PathBuf) {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path().join("minecraft");
        let texture = root.join("26.3/client/assets/minecraft/textures/block/stone.png");
        std::fs::create_dir_all(texture.parent().unwrap()).unwrap();
        std::fs::write(&texture, b"\x89PNG").unwrap();
        std::fs::write(tmp.path().join("secret.txt"), "x").unwrap();
        (tmp, root)
    }

    #[test]
    fn serves_cached_files_in_both_url_forms() {
        let (_tmp, root) = cache();
        let plain = resolve(&root, "/26.3/assets/minecraft/textures/block/stone.png").unwrap();
        assert!(plain.ends_with("stone.png"));
        // convertFileSrc encodes the whole path, slashes included.
        let encoded = resolve(
            &root,
            "/26.3%2Fassets%2Fminecraft%2Ftextures%2Fblock%2Fstone.png",
        )
        .unwrap();
        assert_eq!(plain, encoded);
        assert_eq!(content_type(&plain), "image/png");
    }

    #[test]
    fn refuses_escapes_and_reports_missing_files() {
        let (_tmp, root) = cache();
        for bad in [
            "/26.3/../../secret.txt",
            "/26.3/%2e%2e/%2e%2e/secret.txt",
            "/26.3%2F..%2F..%2Fsecret.txt",
            "/../secret.txt",
            "/26.3",
            "/latest/assets/x.png",
            "/26.3//etc/passwd",
        ] {
            assert_eq!(resolve(&root, bad), Err(AssetError::BadRequest), "{bad}");
        }
        assert_eq!(
            resolve(&root, "/26.3/assets/minecraft/textures/block/missing.png"),
            Err(AssetError::NotFound)
        );
        assert_eq!(
            resolve(&root, "/1.21.11/assets/x.png"),
            Err(AssetError::NotFound)
        );
        assert_eq!(
            resolve(&root, "/26.3/assets/minecraft/textures"),
            Err(AssetError::NotFound)
        );
    }

    #[test]
    fn content_types() {
        assert_eq!(content_type(Path::new("a/b.json")), "application/json");
        assert_eq!(
            content_type(Path::new("a/b.png.mcmeta")),
            "application/json"
        );
        assert_eq!(content_type(Path::new("a/b.PNG")), "image/png");
        assert_eq!(content_type(Path::new("a/b")), "application/octet-stream");
    }
}
