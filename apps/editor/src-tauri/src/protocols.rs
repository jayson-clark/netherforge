//! The app's custom URL schemes, read by the webview like any URL:
//! `nfasset://localhost/<version>/<path>` serves cached client assets (see
//! [crate::minecraft::asset]) and `nfproject://localhost/<project path>` the
//! open project's files (see [crate::fs::serve]). Both are scoped by their
//! resolvers; this only turns a resolved file into a response.

use std::path::{Path, PathBuf};

use tauri::http::{Response, StatusCode, header};

use crate::fs::ProjectRoot;
use crate::fs::serve::{self, ServeError};
use crate::minecraft::asset::{self, AssetError};

/// A file, or why there's none, as a response the webview can read from
/// anywhere (the editor draws these on canvases, which needs CORS).
fn file_response(resolved: Result<PathBuf, StatusCode>) -> Response<Vec<u8>> {
    let read = resolved.and_then(|path| {
        std::fs::read(&path)
            .map(|bytes| (asset::content_type(&path), bytes))
            .map_err(|_| StatusCode::NOT_FOUND)
    });
    let builder = Response::builder()
        .header(header::ACCESS_CONTROL_ALLOW_ORIGIN, "*")
        .header(header::CACHE_CONTROL, "no-cache");
    let response = match read {
        Ok((content_type, bytes)) => builder
            .status(StatusCode::OK)
            .header(header::CONTENT_TYPE, content_type)
            .body(bytes),
        Err(status) => builder.status(status).body(Vec::new()),
    };
    response.unwrap_or_else(|_| Response::new(Vec::new()))
}

pub fn asset_response(minecraft_root: &Path, request_path: &str) -> Response<Vec<u8>> {
    file_response(
        asset::resolve(minecraft_root, request_path).map_err(|e| match e {
            AssetError::BadRequest => StatusCode::BAD_REQUEST,
            AssetError::NotFound => StatusCode::NOT_FOUND,
        }),
    )
}

/// [request_path] in the open project ([root]) or one of its packages
/// ([cache] holding git packages' checkouts).
pub fn project_response(
    root: Option<&ProjectRoot>,
    cache: &Path,
    request_path: &str,
) -> Response<Vec<u8>> {
    let resolved = root
        .ok_or(ServeError::NotFound)
        .and_then(|root| serve::resolve(root, cache, request_path));
    file_response(resolved.map_err(|e| match e {
        ServeError::BadRequest => StatusCode::BAD_REQUEST,
        ServeError::NotFound => StatusCode::NOT_FOUND,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn asset_responses() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path().join("minecraft");
        let file = root.join("26.3/client/assets/minecraft/lang/en_us.json");
        std::fs::create_dir_all(file.parent().unwrap()).unwrap();
        std::fs::write(&file, "{}").unwrap();

        let ok = asset_response(&root, "/26.3/assets/minecraft/lang/en_us.json");
        assert_eq!(ok.status(), StatusCode::OK);
        assert_eq!(ok.headers()[header::CONTENT_TYPE], "application/json");
        assert_eq!(ok.headers()[header::ACCESS_CONTROL_ALLOW_ORIGIN], "*");
        assert_eq!(ok.body(), b"{}");
        assert_eq!(
            asset_response(&root, "/26.3/../../x").status(),
            StatusCode::BAD_REQUEST
        );
        assert_eq!(
            asset_response(&root, "/26.3/assets/none.png").status(),
            StatusCode::NOT_FOUND
        );
    }

    #[test]
    fn project_responses() {
        let tmp = tempfile::tempdir().unwrap();
        let file = tmp.path().join("resource_packs/ui/textures/coin.png");
        std::fs::create_dir_all(file.parent().unwrap()).unwrap();
        std::fs::write(&file, b"\x89PNG").unwrap();
        let root = ProjectRoot::new(tmp.path()).unwrap();

        let ok = project_response(
            Some(&root),
            Path::new("/no-cache"),
            "/resource_packs/ui/textures/coin.png",
        );
        assert_eq!(ok.status(), StatusCode::OK);
        assert_eq!(ok.headers()[header::CONTENT_TYPE], "image/png");
        assert_eq!(ok.body(), b"\x89PNG");
        assert_eq!(
            project_response(Some(&root), Path::new("/no-cache"), "/../x").status(),
            StatusCode::BAD_REQUEST
        );
        assert_eq!(
            project_response(
                None,
                Path::new("/no-cache"),
                "/resource_packs/ui/textures/coin.png"
            )
            .status(),
            StatusCode::NOT_FOUND
        );
    }
}
