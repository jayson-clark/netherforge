//! A project on disk as the runtime reads it: listed again each time it's loaded (a hot reload
//! sees files saved since), read as files are asked for. Kotlin's `DiskProjectSource`.

use std::cell::RefCell;
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

use netherforge_format::project::ProjectSource;

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

    /// Where [path] (a project path) is on disk, if it's a file of the project.
    pub fn path_of(&self, path: &str) -> Option<PathBuf> {
        let file = self.root.join(path);
        (self.files.borrow().contains(path) && file.is_file()).then_some(file)
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
            let hidden = entry.file_name().to_string_lossy().starts_with('.');
            if path.is_dir() {
                // `.nf` (captures), `.git`: never the project's.
                if !hidden {
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
        let now = list(&self.root);
        if now != **self.files.borrow() {
            *self.files.borrow_mut() = Box::leak(Box::new(now));
        }
        *self.files.borrow()
    }

    fn read(&self, path: &str) -> Option<String> {
        std::fs::read_to_string(self.path_of(path)?).ok()
    }

    fn bytes(&self, path: &str) -> Option<Vec<u8>> {
        std::fs::read(self.path_of(path)?).ok()
    }
}
