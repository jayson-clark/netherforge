//! Every file the runtime reads (Kotlin's `ProjectFiles`): the project's, by project path, and each package's it
//! depends on, by package path (`library:modules/greetings/init.lua`).
//!
//! The configured folder is a project or a bundle, decided again by every load ([`PackageSources::prepare`]):
//! - **A project.** Its packages are found where its `netherforge.json`'s paths point, and a git package's checkout at
//!   the commit `netherforge.lock` pins in the editor's package cache (a server never fetches), but only when the
//!   runtime resolves them (the editor's dev server). A production server running a project with dependencies refuses
//!   it (`runtime.unbundled`).
//! - **A bundle** (`netherforge-bundle.json`): its packages are folders named by namespace beside the project's, each
//!   checked against the hash the bundle lists before anything runs (`runtime.bundle-hash`), and its signatures
//!   against `bundles:` (`runtime.bundle-signature`).
//!
//! **Packages the server provides** (`packages.provided`): each folder is a package, found by the namespace its
//! `netherforge.json` says, and it's what a dependency of that namespace opens, in place of any copy the project
//! points at, whether or not the server resolves. A bundle that carries its own copy of one is refused
//! (`runtime.provided-package`): it was built against something else, and the owner's code is the one that runs.
//!
//! Resource packs a package's scripts wrote are laid over each package's files ([`crate::written_packs`]).

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::path::{Path, PathBuf};
use std::rc::Rc;

use netherforge_format::bundle::{self, BundleManifest, BundledResourcePack};
use netherforge_format::codes;
use netherforge_format::json::child_path;
use netherforge_format::kinds::{package_path, split_package};
use netherforge_format::manifest;
use netherforge_format::package::{
    OpenedPackage, PackageLookup, PackageRequest, PackageSources, hash,
};
use netherforge_format::project::{ImageInfo, Project, ProjectSource};
use netherforge_format::wasm::WasmInfo;
use netherforge_format::{Problem, Severity};
use sha2::{Digest as _, Sha256};

use crate::config::BundlesConfig;
use crate::disk::DiskProject;
use crate::written_packs::WrittenPacks;

/// The problems a load finds that refuse the project on a server: a bundle that isn't what it was built as, or
/// dependencies a production server won't resolve.
pub const REFUSING: [&str; 4] = [
    codes::RUNTIME_UNBUNDLED.code,
    codes::RUNTIME_BUNDLE_HASH.code,
    codes::RUNTIME_PROVIDED_PACKAGE.code,
    codes::RUNTIME_BUNDLE_SIGNATURE.code,
];

/// Where the runtime finds its project and packages.
#[derive(Clone, Debug, Default)]
pub struct FilesConfig {
    /// The project's folder, or a bundle's.
    pub configured: PathBuf,
    /// Whether the project's dependencies are found where `netherforge.json` points: on the dev server.
    pub resolves: bool,
    /// The editor's package cache, where git packages' checkouts are.
    pub package_cache: Option<PathBuf>,
    pub bundles: BundlesConfig,
    /// The folders of the packages the server provides.
    pub provided: Vec<PathBuf>,
    /// `nf.files`' root: each package's `packs/<name>/` there is a resource pack its scripts wrote.
    pub written_packs: Option<PathBuf>,
    /// Where the written packs' last good copies are kept.
    pub kept_packs: Option<PathBuf>,
}

/// A package the server provides, as the last load found it.
#[derive(Clone)]
pub struct Provided {
    pub namespace: String,
    pub version: String,
    pub folder: PathBuf,
    source: Rc<DiskProject>,
}

/// A bundle as the last load read its manifest.
struct BundleRun {
    /// The namespaces it carries a folder of.
    folders: BTreeSet<String>,
    /// What it leaves for the server to provide.
    left_out: BTreeSet<String>,
}

#[derive(Default)]
struct Layout {
    root: Option<Rc<DiskProject>>,
    /// The project's namespace, as its manifest says.
    home: String,
    /// Every package's folder by namespace: what the last load opened.
    packages: BTreeMap<String, Rc<DiskProject>>,
    server: BTreeMap<String, Provided>,
    bundle: Option<BundleRun>,
    problems: Vec<Problem>,
    bundled_pack: Option<(BundledResourcePack, PathBuf)>,
    bundled_pack_ignored: Option<String>,
    provided_in_use: Vec<Provided>,
}

pub struct ProjectFiles {
    config: FilesConfig,
    layout: RefCell<Layout>,
    /// Each folder's source, kept across loads (its listing outlives the call that made it).
    opened: RefCell<HashMap<PathBuf, Rc<DiskProject>>>,
    written: WrittenPacks,
    /// Each package's folder by namespace, as the last load found them, for the debugger's thread.
    folders: crate::debug::PackageFolders,
}

/// A folder's source as format is handed it: shared with the layout, laid over with its written packs.
struct Opened(Rc<dyn ProjectSource>);

impl ProjectSource for Opened {
    fn files(&self) -> &BTreeSet<String> {
        self.0.files()
    }
    fn read(&self, path: &str) -> Option<String> {
        self.0.read(path)
    }
    fn bytes(&self, path: &str) -> Option<Vec<u8>> {
        self.0.bytes(path)
    }
    fn reads_images(&self) -> bool {
        true
    }
    fn image(&self, path: &str) -> Option<ImageInfo> {
        self.0.image(path)
    }
    fn reads_wasm(&self) -> bool {
        true
    }
    fn wasm(&self, path: &str) -> Option<WasmInfo> {
        self.0.wasm(path)
    }
}

impl ProjectFiles {
    pub fn new(config: FilesConfig) -> ProjectFiles {
        let written = WrittenPacks::new(config.written_packs.clone(), config.kept_packs.clone());
        ProjectFiles {
            config,
            layout: RefCell::default(),
            opened: RefCell::default(),
            written,
            folders: crate::debug::PackageFolders::default(),
        }
    }

    /// Each package's folder by namespace, kept up to date by every load: what the debugger maps files by.
    pub fn folders(&self) -> crate::debug::PackageFolders {
        self.folders.clone()
    }

    fn disk(&self, folder: &Path) -> Rc<DiskProject> {
        self.opened
            .borrow_mut()
            .entry(folder.to_owned())
            .or_insert_with(|| Rc::new(DiskProject::new(folder.to_owned())))
            .clone()
    }

    fn root(&self) -> Rc<DiskProject> {
        if let Some(root) = &self.layout.borrow().root {
            return root.clone();
        }
        let root = self.disk(&self.config.configured);
        self.layout.borrow_mut().root = Some(root.clone());
        root
    }

    /// The project's own folder: the configured one, or the project's inside a bundle.
    pub fn root_folder(&self) -> PathBuf {
        self.root().root().to_owned()
    }

    /// The project's namespace, as the last load read its manifest.
    pub fn home(&self) -> String {
        self.layout.borrow().home.clone()
    }

    /// The resource pack the bundle was built with and where it is: none for a project, a bundle built without one,
    /// or one whose pack the server's packages add to.
    pub fn bundled_pack(&self) -> Option<(BundledResourcePack, PathBuf)> {
        self.layout.borrow().bundled_pack.clone()
    }

    /// Why the bundle's pack isn't used though it has one, or none.
    pub fn bundled_pack_ignored(&self) -> Option<String> {
        self.layout.borrow().bundled_pack_ignored.clone()
    }

    /// The packages the server provides that the last load used.
    pub fn provided_in_use(&self) -> Vec<Provided> {
        self.layout.borrow().provided_in_use.clone()
    }

    /// Whether the last load read any resource pack a package's scripts wrote.
    pub fn reads_written_packs(&self) -> bool {
        self.written.any()
    }

    /// The `pack.json` of every resource pack scripts wrote, as a reload names it.
    pub fn written_pack_files(&self) -> Vec<String> {
        self.written.pack_files(&self.home())
    }

    /// `problem` as the owner reads it: one in a pack scripts wrote names the file in their `nf.files` folder.
    pub fn shown(&self, problem: Problem) -> Problem {
        self.written.shown(problem, &self.home())
    }

    /// Where `path` (a project or package path) is on disk, if it's a file there (or a folder holding some).
    pub fn path_of(&self, path: &str) -> Option<PathBuf> {
        if let Some(found) = self.written.path_of(path, &self.home()) {
            return Some(found);
        }
        let (source, local) = self.disk_of(path)?;
        source.path_of(local)
    }

    /// The project or package path of the file at `file` (an absolute path, as an editor sees it): in the project's
    /// folder, or a package's. A package nested in another folder wins over the folder around it.
    pub fn project_path(&self, file: &Path) -> Option<String> {
        let candidates: Vec<PathBuf> = [Some(absolute(file)), file.canonicalize().ok()]
            .into_iter()
            .flatten()
            .collect();
        let layout = self.layout.borrow();
        let mut folders: Vec<(Option<String>, PathBuf)> = layout
            .packages
            .iter()
            .map(|(ns, s)| (Some(ns.clone()), s.root().to_owned()))
            .collect();
        folders.push((None, self.root().root().to_owned()));
        let mut best: Option<(usize, String)> = None;
        for (namespace, base) in folders {
            let bases: Vec<PathBuf> = [Some(absolute(&base)), base.canonicalize().ok()]
                .into_iter()
                .flatten()
                .collect();
            for candidate in &candidates {
                for folder in &bases {
                    let Ok(local) = candidate.strip_prefix(folder) else {
                        continue;
                    };
                    if local.as_os_str().is_empty() {
                        continue;
                    }
                    let local = local.to_string_lossy().replace('\\', "/");
                    let path = match &namespace {
                        None => local,
                        Some(ns) => package_path(ns, &local),
                    };
                    let depth = folder.components().count();
                    if best.as_ref().is_none_or(|(d, _)| depth > *d) {
                        best = Some((depth, path));
                    }
                }
            }
        }
        best.map(|(_, path)| path)
    }

    /// The folder holding `path` and the path in it.
    fn disk_of<'p>(&self, path: &'p str) -> Option<(Rc<DiskProject>, &'p str)> {
        let (package, local) = split_package(path);
        let source = match package {
            None => self.root(),
            Some(ns) => self.layout.borrow().packages.get(ns)?.clone(),
        };
        Some((source, local))
    }

    /// The source a project or package path is read from: a pack the scripts wrote, or the package's folder.
    fn source_of(&self, path: &str) -> Option<(Rc<dyn ProjectSource>, String)> {
        let (package, local) = split_package(path);
        let namespace = package.map_or_else(|| self.home(), str::to_owned);
        if let Some((source, local)) = self.written.source_of(&namespace, local) {
            return Some((source, local));
        }
        let (source, local) = self.disk_of(path)?;
        Some((source, local.to_owned()))
    }

    /// `source` as format opens it for `namespace`: with the packs its scripts wrote laid in.
    fn over(&self, namespace: &str, source: Rc<DiskProject>) -> Box<dyn ProjectSource + '_> {
        Box::new(Opened(self.written.over(namespace, source)))
    }

    /// The provided packages by namespace, read afresh, and what's wrong with them.
    fn read_provided(&self, problems: &mut Vec<Problem>) -> BTreeMap<String, Provided> {
        let mut found: BTreeMap<String, Provided> = BTreeMap::new();
        for folder in &self.config.provided {
            let source = self.disk(folder);
            source.relist();
            let manifest = source
                .read(manifest::FILE_NAME)
                .and_then(|text| manifest::load(Some(&text)).0);
            let Some(manifest) = manifest else {
                problems.push(codes::RUNTIME_PROVIDED_PACKAGE.at(
                    manifest::FILE_NAME,
                    format!(
                        "The server provides a package at {} (packages.provided in config.yml), but there's no readable {} there",
                        folder.display(),
                        manifest::FILE_NAME
                    ),
                    Some("$.dependencies".into()),
                ));
                continue;
            };
            let namespace = manifest.namespace.clone();
            if let Some(other) = found.get(&namespace) {
                problems.push(codes::RUNTIME_PROVIDED_PACKAGE.at(
                    manifest::FILE_NAME,
                    format!(
                        "The server provides two packages called \"{namespace}\" (packages.provided in config.yml): {} and {}. It runs one of each namespace",
                        other.folder.display(),
                        folder.display()
                    ),
                    Some("$.dependencies".into()),
                ));
                continue;
            }
            found.insert(
                namespace.clone(),
                Provided {
                    namespace,
                    version: manifest.version.clone(),
                    folder: folder.clone(),
                    source,
                },
            );
        }
        found
    }

    /// The server's copy of a package as format is given it, named as such in its problems.
    fn opened_provided(&self, provided: &Provided) -> PackageLookup<'_> {
        PackageLookup::Opened(OpenedPackage {
            source: self.over(&provided.namespace, provided.source.clone()),
            hash: Some(hash_of(&provided.source)),
            commit: None,
            from: Some(format!("the server's, from {}", provided.folder.display())),
        })
    }

    fn prepare_bundle(&self, file: &Path, layout: &mut Layout) {
        let manifest = match std::fs::read_to_string(file) {
            Err(e) => {
                layout.problems.push(codes::RUNTIME_BUNDLE_HASH.at(
                    bundle::FILE_NAME,
                    format!("The bundle's manifest can't be read: {e}"),
                    None,
                ));
                None
            }
            Ok(text) => match BundleManifest::parse(&text) {
                Ok(manifest) => Some(manifest),
                Err(problem) => {
                    layout.problems.push(*problem);
                    None
                }
            },
        };
        let mut folders = BTreeSet::new();
        for (name, _) in manifest.iter().flat_map(|m| m.packages.0.iter()) {
            if netherforge_format::names::is_namespace(&name.0) {
                folders.insert(name.0.clone());
            } else {
                layout.problems.push(codes::RUNTIME_BUNDLE_HASH.at(
                    bundle::FILE_NAME,
                    format!(
                        "\"{}\" isn't a package's namespace, so the bundle has no folder for it",
                        name.0
                    ),
                    Some("$.packages".into()),
                ));
            }
        }
        let configured = &self.config.configured;
        layout.bundled_pack = manifest
            .as_ref()
            .and_then(|m| m.resource_pack.clone())
            .map(|pack| (pack, configured.join(bundle::RESOURCE_PACK_FILE)));
        let project = manifest
            .as_ref()
            .map(|m| m.project.clone())
            .filter(|p| folders.contains(p));
        let root = self.disk(
            &project
                .as_ref()
                .map_or(configured.clone(), |p| configured.join(p)),
        );
        root.relist();
        layout.root = Some(root.clone());
        layout.packages = folders
            .iter()
            .filter(|f| Some(*f) != project.as_ref())
            .map(|f| (f.clone(), self.disk(&configured.join(f))))
            .collect();
        let Some(manifest) = manifest else {
            layout.bundle = Some(BundleRun {
                folders,
                left_out: BTreeSet::new(),
            });
            return;
        };
        if project.is_none() {
            layout.problems.push(codes::RUNTIME_BUNDLE_HASH.at(
                bundle::FILE_NAME,
                format!("The bundle lists no package \"{}\"", manifest.project),
                Some("$.project".into()),
            ));
        }
        if let Some(why) = signature_problem(&manifest, &self.config.bundles) {
            layout.problems.push(codes::RUNTIME_BUNDLE_SIGNATURE.at(
                bundle::FILE_NAME,
                why,
                Some("$.signatures".into()),
            ));
        }
        // A bundle runs as it was built: every package, the project's own included, as it was hashed.
        for (name, package) in &manifest.packages.0 {
            if !folders.contains(&name.0) {
                continue;
            }
            let source = if Some(&name.0) == project.as_ref() {
                root.clone()
            } else {
                layout.packages[&name.0].clone()
            };
            source.relist();
            let actual = hash_of(&source);
            if actual != package.hash {
                layout.problems.push(codes::RUNTIME_BUNDLE_HASH.at(
                    bundle::FILE_NAME,
                    format!(
                        "The package \"{}\" in this bundle isn't the one it was built with (its files hash to {actual}, not {}): build the bundle again rather than changing it",
                        name.0, package.hash
                    ),
                    Some(child_path("$.packages", &name.0)),
                ));
            }
        }
        // The owner's copy is the one that runs: a bundle carrying its own was built against something else.
        for name in folders.iter().filter(|n| layout.server.contains_key(*n)) {
            layout.problems.push(codes::RUNTIME_PROVIDED_PACKAGE.at(
                bundle::FILE_NAME,
                format!(
                    "This bundle carries its own \"{name}\", which the server provides ({}, packages.provided in config.yml): build it without it (mark the dependency \"provided\": true, or netherforge build --exclude {name})",
                    layout.server[name].folder.display()
                ),
                Some(child_path("$.packages", name)),
            ));
        }
        let provided_by_server: Vec<(String, Rc<DiskProject>)> = layout
            .server
            .iter()
            .filter(|(ns, _)| !folders.contains(*ns))
            .map(|(ns, p)| (ns.clone(), p.source.clone()))
            .collect();
        layout.packages.extend(provided_by_server);
        layout.bundle = Some(BundleRun {
            folders,
            left_out: manifest.provided.unwrap_or_default().into_iter().collect(),
        });
    }

    fn open_bundled(&self, namespace: &str) -> PackageLookup<'_> {
        let layout = self.layout.borrow();
        let run = layout.bundle.as_ref().expect("a bundle's load");
        if !run.folders.contains(namespace) {
            if let Some(provided) = layout.server.get(namespace).cloned() {
                drop(layout);
                return self.opened_provided(&provided);
            }
        } else if let Some(source) = layout.packages.get(namespace).cloned() {
            drop(layout);
            return PackageLookup::Opened(OpenedPackage {
                source: self.over(namespace, source),
                hash: None,
                commit: None,
                from: None,
            });
        }
        PackageLookup::Missing(Some(if run.left_out.contains(namespace) {
            format!(
                "the bundle leaves \"{namespace}\" for the server to provide, and this server provides no such package (packages.provided in config.yml)"
            )
        } else {
            format!("the bundle has no package \"{namespace}\"")
        }))
    }

    fn open_project(&self, namespace: &str, request: &PackageRequest) -> PackageLookup<'_> {
        let provided = self.layout.borrow().server.get(namespace).cloned();
        if let Some(provided) = provided {
            self.layout
                .borrow_mut()
                .packages
                .insert(namespace.to_owned(), provided.source.clone());
            return self.opened_provided(&provided);
        }
        if !self.config.resolves {
            return PackageLookup::Missing(None);
        }
        match request {
            PackageRequest::Folder(location) => {
                let folder = normalize(&self.config.configured.join(location));
                if !folder.join(manifest::FILE_NAME).is_file() {
                    return PackageLookup::Missing(None);
                }
                let source = self.disk(&folder);
                source.relist();
                self.layout
                    .borrow_mut()
                    .packages
                    .insert(namespace.to_owned(), source.clone());
                PackageLookup::Opened(OpenedPackage {
                    source: self.over(namespace, source),
                    hash: None,
                    commit: None,
                    from: None,
                })
            }
            PackageRequest::Git { commit, .. } => {
                let Some(commit) = commit.clone() else {
                    return PackageLookup::Missing(Some(format!(
                        "{} doesn't pin it yet, and a server never fetches: the editor does, and locks it",
                        netherforge_format::package::LOCK_FILE
                    )));
                };
                let folder = self
                    .config
                    .package_cache
                    .as_ref()
                    .map(|cache| cache.join("git").join("checkouts").join(&commit))
                    .filter(|f| f.join(manifest::FILE_NAME).is_file());
                let Some(folder) = folder else {
                    return PackageLookup::Missing(Some(
                        "it isn't in the editor's package cache yet: the editor fetches it".into(),
                    ));
                };
                let source = self.disk(&folder);
                source.relist();
                self.layout
                    .borrow_mut()
                    .packages
                    .insert(namespace.to_owned(), source.clone());
                PackageLookup::Opened(OpenedPackage {
                    hash: Some(hash_of(&source)),
                    source: self.over(namespace, source),
                    commit: Some(commit),
                    from: None,
                })
            }
        }
    }
}

impl PackageSources for ProjectFiles {
    fn open(&self, namespace: &str, request: &PackageRequest) -> PackageLookup<'_> {
        if self.layout.borrow().bundle.is_some() {
            self.open_bundled(namespace)
        } else {
            self.open_project(namespace, request)
        }
    }

    // The editor keeps netherforge.lock; a server only runs what resolves.
    fn checks_lock(&self) -> bool {
        false
    }

    fn prepare(&self) {
        let mut problems = Vec::new();
        let server = self.read_provided(&mut problems);
        let mut layout = Layout {
            server,
            problems,
            ..Layout::default()
        };
        let file = self.config.configured.join(bundle::FILE_NAME);
        if file.is_file() {
            self.prepare_bundle(&file, &mut layout);
        } else {
            layout.root = Some(self.disk(&self.config.configured));
        }
        let root = layout.root.clone().expect("a root");
        layout.home = root
            .read(manifest::FILE_NAME)
            .and_then(|text| manifest::load(Some(&text)).0)
            .map(|m| m.namespace)
            .unwrap_or_default();
        *self.layout.borrow_mut() = layout;
        self.written.begin_pass();
    }

    fn checked(&self, project: &mut Project<'_>) -> bool {
        if self.written.checked(project) {
            return true;
        }
        let mut layout = self.layout.borrow_mut();
        let used: BTreeSet<&String> = project.packages.keys().collect();
        let folders = layout.bundle.as_ref().map(|b| b.folders.clone());
        layout
            .packages
            .retain(|ns, _| used.contains(ns) || folders.as_ref().is_some_and(|f| f.contains(ns)));
        let mut problems = std::mem::take(&mut layout.problems);
        match &folders {
            None => {
                let dependencies = project
                    .manifest
                    .as_ref()
                    .and_then(|m| m.dependencies.as_ref())
                    .map(|d| d.0.iter().map(|(k, _)| k.0.clone()).collect::<Vec<_>>())
                    .unwrap_or_default();
                if !self.config.resolves
                    && dependencies.iter().any(|d| !layout.server.contains_key(d))
                {
                    problems.push(codes::RUNTIME_UNBUNDLED.at(
                        manifest::FILE_NAME,
                        "This project has dependencies, and a server outside the editor never resolves them itself: build a bundle with `netherforge build` and point the plugin's `project` setting at it",
                        Some("$.dependencies".into()),
                    ));
                }
            }
            Some(folders) => {
                // The bundle's pack was built from what it carries: a package from the server that adds to it makes
                // it another pack.
                let adding: Vec<&String> = project
                    .packages
                    .iter()
                    .filter(|(name, p)| {
                        !folders.contains(*name)
                            && netherforge_format::kind::resource_pack::build::needed(&p.project)
                    })
                    .map(|(name, _)| name)
                    .collect();
                if layout.bundled_pack.is_some() && !adding.is_empty() {
                    let names: Vec<&str> = adding.iter().map(|s| s.as_str()).collect();
                    layout.bundled_pack_ignored =
                        Some(format!("the server's {} adds to it", names.join(", ")));
                    layout.bundled_pack = None;
                }
            }
        }
        layout.provided_in_use = layout
            .server
            .iter()
            .filter(|(ns, p)| {
                used.contains(ns)
                    && layout
                        .packages
                        .get(*ns)
                        .is_some_and(|s| Rc::ptr_eq(s, &p.source))
            })
            .map(|(_, p)| p.clone())
            .collect();
        *self.folders.lock().unwrap_or_else(|e| e.into_inner()) = layout
            .packages
            .iter()
            .map(|(ns, source)| (ns.clone(), source.root().to_owned()))
            .collect();
        drop(layout);
        // The bundle's pack was built without them, and they may number the glyphs differently.
        if self.written.any() {
            let mut layout = self.layout.borrow_mut();
            if layout.bundled_pack.take().is_some() {
                layout.bundled_pack_ignored =
                    Some("packages' scripts wrote resource packs of their own".into());
            }
        }
        project.problems.extend(problems);
        false
    }
}

impl ProjectSource for ProjectFiles {
    /// The project's own files (with the packs its scripts wrote), listed again: what format loads it from.
    fn files(&self) -> &BTreeSet<String> {
        let root = self.root();
        let files = root.relist();
        let home = self.home();
        if home.is_empty() {
            return files;
        }
        self.written.with_packs(&home, files)
    }

    fn read(&self, path: &str) -> Option<String> {
        let (source, local) = self.source_of(path)?;
        source.read(&local)
    }

    fn bytes(&self, path: &str) -> Option<Vec<u8>> {
        let (source, local) = self.source_of(path)?;
        source.bytes(&local)
    }

    fn reads_images(&self) -> bool {
        true
    }

    fn image(&self, path: &str) -> Option<ImageInfo> {
        let (source, local) = self.source_of(path)?;
        source.image(&local)
    }

    fn reads_wasm(&self) -> bool {
        true
    }

    fn wasm(&self, path: &str) -> Option<WasmInfo> {
        let (source, local) = self.source_of(path)?;
        source.wasm(&local)
    }
}

/// `source`'s package hash: its content files' SHA-256s, listed as format says, hashed.
pub fn hash_of(source: &DiskProject) -> String {
    let digests: Vec<(String, String)> = source
        .listed()
        .iter()
        .filter(|path| hash::is_content(path))
        .map(|path| {
            let bytes = source.bytes(path).unwrap_or_default();
            (path.clone(), hex(&Sha256::digest(&bytes)))
        })
        .collect();
    let listing = hash::listing(digests.iter().map(|(p, d)| (p.as_str(), d.as_str())));
    hash::of(&hex(&Sha256::digest(listing.as_bytes())))
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Whether `signature` is `key`'s over `text`; false for a key or signature that isn't one.
pub fn verifies(key: &str, signature: &str, text: &str) -> bool {
    use base64::Engine as _;
    let engine = base64::engine::general_purpose::STANDARD;
    let Some(raw) = key
        .strip_prefix(bundle::KEY_PREFIX)
        .and_then(|k| engine.decode(k).ok())
    else {
        return false;
    };
    let Ok(signature) = engine.decode(signature) else {
        return false;
    };
    raw.len() == 32
        && ring::signature::UnparsedPublicKey::new(&ring::signature::ED25519, raw)
            .verify(text.as_bytes(), &signature)
            .is_ok()
}

/// What's wrong with `manifest`'s signatures under `config`, or none: a signature that doesn't verify (the bundle or
/// its manifest was changed), or, when one is required, none by a trusted key.
pub fn signature_problem(manifest: &BundleManifest, config: &BundlesConfig) -> Option<String> {
    let text = manifest.signed_text();
    let signatures = manifest.signatures.clone().unwrap_or_default();
    if let Some(bad) = signatures
        .iter()
        .find(|s| !verifies(&s.key, &s.signature, &text))
    {
        return Some(format!(
            "The bundle's signature by {} doesn't match it: it was changed after it was signed",
            bad.key
        ));
    }
    if !config.require_signature {
        return None;
    }
    if signatures
        .iter()
        .any(|s| config.trusted_keys.contains(&s.key))
    {
        return None;
    }
    Some(if signatures.is_empty() {
        "This server runs only bundles signed by a key it trusts (bundles.require-signature), and this one isn't signed".into()
    } else {
        let signers: Vec<&str> = signatures.iter().map(|s| s.key.as_str()).collect();
        format!(
            "This server runs only bundles signed by a key it trusts (bundles.trusted-keys), and this one's signers ({}) aren't among them",
            signers.join(", ")
        )
    })
}

/// Whether `problem` refuses the project on a server (see [`REFUSING`]).
pub fn refuses(problem: &Problem) -> bool {
    problem.severity == Severity::Error
        && problem
            .code
            .as_deref()
            .is_some_and(|c| REFUSING.contains(&c))
}

fn absolute(path: &Path) -> PathBuf {
    normalize(&std::path::absolute(path).unwrap_or_else(|_| path.to_owned()))
}

/// `path` with its `.` and `..` worked out, without asking the disk.
pub fn normalize(path: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for part in path.components() {
        match part {
            std::path::Component::CurDir => {}
            std::path::Component::ParentDir => {
                out.pop();
            }
            other => out.push(other),
        }
    }
    out
}
