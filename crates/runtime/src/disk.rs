//! A project on disk as the runtime reads it: listed again each time it's loaded (a hot reload
//! sees files saved since), read as files are asked for, pictures' pixel facts and WebAssembly modules' headers
//! read for format. Kotlin's `DiskProjectSource`.

use std::cell::RefCell;
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

use netherforge_format::project::{ImageInfo, ProjectSource};

/// Folders that are never a project's: VCS metadata, what the editor generates, package managers' caches.
pub const SKIPPED: [&str; 3] = [".git", ".netherforge", "node_modules"];

pub struct DiskProject {
    root: PathBuf,
    /// The last listing. Each one that differs is kept for good (a reload's worth of paths), since
    /// `files` answers a borrow that outlives the call.
    files: RefCell<&'static BTreeSet<String>>,
}

impl DiskProject {
    pub fn new(root: PathBuf) -> DiskProject {
        let files = Box::leak(Box::new(list(&root)));
        DiskProject {
            root,
            files: RefCell::new(files),
        }
    }

    pub fn root(&self) -> &Path {
        &self.root
    }

    /// The files as the last listing found them (`files` lists them again).
    pub fn listed(&self) -> &'static BTreeSet<String> {
        *self.files.borrow()
    }

    /// Lists the files again, keeping the new listing when it differs: what `files` answers.
    pub fn relist(&self) -> &'static BTreeSet<String> {
        let now = list(&self.root);
        if now != **self.files.borrow() {
            *self.files.borrow_mut() = Box::leak(Box::new(now));
        }
        *self.files.borrow()
    }

    /// Where [path] (a project path) is on disk, if it's a file of the project.
    /// A folder holding some of the project's files (a map's `maps/arena`) is one too.
    pub fn path_of(&self, path: &str) -> Option<PathBuf> {
        let file = self.root.join(path);
        let files = self.files.borrow();
        if files.contains(path) {
            return file.is_file().then_some(file);
        }
        let folder = format!("{}/", path.trim_end_matches('/'));
        let holds = files
            .range(folder.clone()..)
            .next()
            .is_some_and(|f| f.starts_with(&folder));
        (holds && file.is_dir()).then_some(file)
    }
}

/// Every file under [root], as project paths (`/` between names), the dot folders' left out.
fn list(root: &Path) -> BTreeSet<String> {
    fn walk(dir: &Path, base: &Path, out: &mut BTreeSet<String>) {
        let Ok(entries) = std::fs::read_dir(dir) else {
            return;
        };
        for entry in entries.flatten() {
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().into_owned();
            if path.is_dir() {
                // `.nf` (captures), `.git`, the editor's output: never the project's.
                if !name.starts_with('.') && !SKIPPED.contains(&name.as_str()) {
                    walk(&path, base, out);
                }
            } else if let Ok(relative) = path.strip_prefix(base) {
                out.insert(relative.to_string_lossy().replace('\\', "/"));
            }
        }
    }
    let mut out = BTreeSet::new();
    walk(root, root, &mut out);
    out
}

impl ProjectSource for DiskProject {
    fn files(&self) -> &BTreeSet<String> {
        self.relist()
    }

    fn read(&self, path: &str) -> Option<String> {
        std::fs::read_to_string(self.path_of(path)?).ok()
    }

    fn bytes(&self, path: &str) -> Option<Vec<u8>> {
        std::fs::read(self.path_of(path)?).ok()
    }

    fn reads_images(&self) -> bool {
        true
    }

    fn image(&self, path: &str) -> Option<ImageInfo> {
        image_info(&self.bytes(path)?)
    }

    fn reads_wasm(&self) -> bool {
        true
    }

    fn wasm(&self, path: &str) -> Option<netherforge_format::wasm::WasmInfo> {
        Some(netherforge_format::wasm::read(&self.bytes(path)?))
    }
}

/// A picture's size and how far its opaque columns reach (what a skin's title moves back by), and for one wider than
/// a font's sheet which columns are opaque (its pieces' advances); none for bytes that don't decode.
pub fn image_info(bytes: &[u8]) -> Option<ImageInfo> {
    let decoded = image::load_from_memory(bytes).ok()?;
    let (width, height) = (decoded.width(), decoded.height());
    if width == 0 || height == 0 {
        return None;
    }
    let alpha = decoded.color().has_alpha();
    let rgba = decoded.to_rgba8();
    let opaque_at = |x: u32| !alpha || (0..height).any(|y| rgba.get_pixel(x, y).0[3] != 0);
    let opaque_width = (0..width)
        .rev()
        .find(|x| opaque_at(*x))
        .map_or(0, |x| x + 1);
    let sheet = netherforge_format::kind::resource_pack::fonts::SHEET;
    let opaque_columns = (width > sheet).then(|| (0..width).map(opaque_at).collect());
    Some(ImageInfo {
        width,
        height,
        opaque_width,
        opaque_columns,
    })
}
