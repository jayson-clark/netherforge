//! The `nfproject://` protocol: serves the open project's files to the
//! webview, so a resource pack's textures preview as plain image URLs, and those of
//! the packages it depends on (`/:package/<location>/<path>`, see [resolve]),
//! a git package's from its checkout in the package cache.
//!
//! `nfproject://localhost/<project path>` on macOS and Linux,
//! `http://nfproject.localhost/<project path>` on Windows. The UI appends
//! `?v=<n>` to bust the webview's cache after a file changes; the query isn't
//! part of the path. Every request goes through [ProjectRoot::resolve], like
//! every other file command, and hidden paths (`.git`, `.netherforge/`) are not
//! served, except the UI's cached pictures in [THUMBNAILS].

use std::path::{Path, PathBuf};

use super::{ProjectRoot, THUMBNAILS, is_hidden};

#[derive(Debug, PartialEq, Eq)]
pub enum ServeError {
    BadRequest,
    NotFound,
}

/// What starts a request for a package's file: `/:package/<location>/<path>`,
/// the location (`../library`) one percent-encoded segment. A project path
/// never holds `:`, so it can't be mistaken for one.
const PACKAGE: &str = "/:package/";

/// The file a request path names: inside [root], or inside one of the
/// packages beside it (`/:package/..%2Flibrary/resource_packs/gems/textures/gem.png`),
/// found as every package command finds one ([super::package::open]).
pub fn resolve(
    root: &ProjectRoot,
    cache: &Path,
    request_path: &str,
) -> Result<PathBuf, ServeError> {
    let Some(rest) = request_path.strip_prefix(PACKAGE) else {
        return resolve_in(root, request_path);
    };
    let (location, path) = rest.split_once('/').ok_or(ServeError::BadRequest)?;
    let location = percent_encoding::percent_decode_str(location)
        .decode_utf8()
        .map_err(|_| ServeError::BadRequest)?;
    let package = super::package::open(root, cache, &location).map_err(|e| match e.code() {
        crate::error::ErrorCode::NotFound => ServeError::NotFound,
        _ => ServeError::BadRequest,
    })?;
    // The package's own hidden files aren't served either, thumbnails included.
    let decoded = percent_encoding::percent_decode_str(path)
        .decode_utf8()
        .map_err(|_| ServeError::BadRequest)?;
    if is_hidden(&decoded) {
        return Err(ServeError::NotFound);
    }
    resolve_in(&package, path)
}

/// The file [request_path] names inside [root].
fn resolve_in(root: &ProjectRoot, request_path: &str) -> Result<PathBuf, ServeError> {
    let decoded = percent_encoding::percent_decode_str(request_path)
        .decode_utf8()
        .map_err(|_| ServeError::BadRequest)?;
    let path = decoded.trim_start_matches('/');
    let thumbnail = path
        .strip_prefix(THUMBNAILS)
        .is_some_and(|rest| rest.starts_with('/'));
    if is_hidden(path) && !thumbnail {
        return Err(ServeError::NotFound);
    }
    let abs = root.resolve(path).map_err(|_| ServeError::BadRequest)?;
    if !abs.is_file() {
        return Err(ServeError::NotFound);
    }
    Ok(abs)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn serves_a_package_s_files_beside_the_project() {
        let tmp = tempfile::tempdir().unwrap();
        let project = tmp.path().join("p");
        std::fs::create_dir_all(&project).unwrap();
        for path in [
            "lib/netherforge.json",
            "lib/resource_packs/gems/textures/gem.png",
            "lib/.git/HEAD",
            "lib/.netherforge/thumbnails/a.png",
            "loose/resource_packs/x.png",
            "secret.txt",
        ] {
            let abs = tmp.path().join(path);
            std::fs::create_dir_all(abs.parent().unwrap()).unwrap();
            std::fs::write(abs, "x").unwrap();
        }
        let root = ProjectRoot::new(&project).unwrap();

        let gem = resolve(
            &root,
            Path::new("/no-cache"),
            "/:package/..%2Flib/resource_packs/gems/textures/gem.png",
        )
        .unwrap();
        assert!(gem.ends_with("gem.png"));
        for missing in [
            "/:package/..%2Flib/.git/HEAD",
            "/:package/..%2Flib/.netherforge/thumbnails/a.png",
            "/:package/..%2Flib/resource_packs/none.png",
            "/:package/..%2Fnone/resource_packs/x.png",
            // A folder without a netherforge.json isn't a package.
            "/:package/..%2Floose/resource_packs/x.png",
        ] {
            assert_eq!(
                resolve(&root, Path::new("/no-cache"), missing),
                Err(ServeError::NotFound),
                "{missing}"
            );
        }
        for bad in [
            "/:package/..%2Flib/../secret.txt",
            "/:package/%2Ftmp/x.png",
            "/:package/C%3A%2Fx/y.png",
            "/:package/..%2Flib",
        ] {
            assert_eq!(
                resolve(&root, Path::new("/no-cache"), bad),
                Err(ServeError::BadRequest),
                "{bad}"
            );
        }
    }

    #[test]
    fn serves_project_files_only() {
        let tmp = tempfile::tempdir().unwrap();
        let project = tmp.path().join("p");
        for path in [
            "resource_packs/ui/textures/gui/shop window.png",
            ".git/HEAD",
            ".netherforge/x",
            ".netherforge/thumbnails/tower.png",
            ".netherforge/thumbnails.png",
        ] {
            let abs = project.join(path);
            std::fs::create_dir_all(abs.parent().unwrap()).unwrap();
            std::fs::write(abs, "x").unwrap();
        }
        std::fs::write(tmp.path().join("secret.txt"), "x").unwrap();
        let root = ProjectRoot::new(&project).unwrap();

        let ok = resolve(
            &root,
            Path::new("/no-cache"),
            "/resource_packs/ui/textures/gui/shop%20window.png",
        )
        .unwrap();
        assert!(ok.ends_with("shop window.png"));
        // The whole path encoded, slashes included, works too.
        assert_eq!(
            resolve(
                &root,
                Path::new("/no-cache"),
                "/resource_packs%2Fui%2Ftextures%2Fgui%2Fshop%20window.png"
            )
            .unwrap(),
            ok
        );
        for bad in ["/../secret.txt", "/%2e%2e/secret.txt", "/a//b", "/C:/x"] {
            assert_eq!(
                resolve(&root, Path::new("/no-cache"), bad),
                Err(ServeError::BadRequest),
                "{bad}"
            );
        }
        // The UI's cached pictures are the one hidden folder served.
        assert!(
            resolve(
                &root,
                Path::new("/no-cache"),
                "/.netherforge/thumbnails/tower.png"
            )
            .unwrap()
            .ends_with("tower.png")
        );
        for missing in [
            "/.git/HEAD",
            "/.netherforge/x",
            "/.netherforge/thumbnails.png",
            "/.netherforge/thumbnails/none.png",
            "/resource_packs/none.png",
            "/resource_packs",
        ] {
            assert_eq!(
                resolve(&root, Path::new("/no-cache"), missing),
                Err(ServeError::NotFound),
                "{missing}"
            );
        }
    }
}
