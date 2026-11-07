//! Resource packs a package's scripts wrote while the server runs (Kotlin's `WrittenPacks`): `packs/<name>/` in the
//! package's own `nf.files` folder (`<data>/<namespace>/packs/<name>/pack.json` and its pictures and sounds), read
//! as though they were the package's `resource_packs/<name>/`. So format loads, checks and numbers them with the rest,
//! a script names their glyphs as its own packs', and `nf.packs.rebuild` (which reads everything again) builds what
//! changed and sends it.
//!
//! A pack the package already has under `resource_packs/` keeps its name: a written one of that name isn't read
//! (`runtime.written-pack`). One with errors mustn't take the rest down with it, so its own problems are reported, as
//! warnings at the file that has them, and the load is done again with **its last good copy**, kept in `kept` (out of
//! the scripts' reach) each time it loads clean; without one, it's left out. A deployed project's folder, its hash and
//! the editor never see any of this: the files are the server's.

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::path::{Path, PathBuf};
use std::rc::Rc;

use netherforge_format::codes;
use netherforge_format::kinds::{package_path, split_package};
use netherforge_format::project::{ImageInfo, Project, ProjectSource};
use netherforge_format::wasm::WasmInfo;
use netherforge_format::{Problem, Severity};

use crate::disk::DiskProject;

/// The folder of a package's `nf.files` its scripts write packs in.
pub const FOLDER: &str = "packs";
const PACKS: &str = "resource_packs";
const PACK_FILE: &str = "pack.json";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Use {
    Live,
    Kept,
    None,
}

/// One pack as this pass reads it: from the scripts' `live` folder or its last good copy.
#[derive(Clone)]
struct Chosen {
    namespace: String,
    id: String,
    live: PathBuf,
    source: Rc<DiskProject>,
    using: Use,
}

#[derive(Default)]
struct Pass {
    /// What this pass read, by namespace then pack id.
    chosen: BTreeMap<String, BTreeMap<String, Chosen>>,
    /// A written pack whose name the package has.
    notes: Vec<Problem>,
}

pub struct WrittenPacks {
    data: Option<PathBuf>,
    kept: Option<PathBuf>,
    /// The packs whose live copy had errors on an earlier pass of this load, by `<namespace>/<id>`.
    fallback: RefCell<HashMap<String, Use>>,
    pass: RefCell<Pass>,
    /// Whether the next pass is of the same load (the last asked for another).
    again: std::cell::Cell<bool>,
    /// What this load's passes found wrong with the packs they left out.
    reported: RefCell<Vec<Problem>>,
    /// Each live pack's listing as it was last kept, so an unchanged one isn't copied again.
    kept_listing: RefCell<HashMap<String, String>>,
    /// Each pack folder's source, kept across loads (its listing outlives the call that made it).
    sources: RefCell<HashMap<PathBuf, Rc<DiskProject>>>,
    /// Listings with packs laid in, kept for good once made (a borrow `files` answers outlives the call).
    listings: RefCell<HashMap<BTreeSet<String>, &'static BTreeSet<String>>>,
}

/// A package's source with its written packs (by pack id) laid in under `resource_packs/`.
struct Overlaid {
    base: Rc<DiskProject>,
    packs: BTreeMap<String, Rc<DiskProject>>,
    files: &'static BTreeSet<String>,
}

impl Overlaid {
    fn split<'p>(&self, path: &'p str) -> Option<(&Rc<DiskProject>, &'p str)> {
        let rest = path.strip_prefix(PACKS)?.strip_prefix('/')?;
        let (id, local) = rest.split_once('/')?;
        Some((self.packs.get(id)?, local))
    }
}

impl ProjectSource for Overlaid {
    fn files(&self) -> &BTreeSet<String> {
        self.files
    }
    fn read(&self, path: &str) -> Option<String> {
        match self.split(path) {
            Some((pack, local)) => pack.read(local),
            None => self.base.read(path),
        }
    }
    fn bytes(&self, path: &str) -> Option<Vec<u8>> {
        match self.split(path) {
            Some((pack, local)) => pack.bytes(local),
            None => self.base.bytes(path),
        }
    }
    fn reads_images(&self) -> bool {
        true
    }
    fn image(&self, path: &str) -> Option<ImageInfo> {
        match self.split(path) {
            Some((pack, local)) => pack.image(local),
            None => self.base.image(path),
        }
    }
    fn reads_wasm(&self) -> bool {
        true
    }
    fn wasm(&self, path: &str) -> Option<WasmInfo> {
        self.base.wasm(path)
    }
}

fn key(namespace: &str, id: &str) -> String {
    format!("{namespace}/{id}")
}

impl WrittenPacks {
    pub fn new(data: Option<PathBuf>, kept: Option<PathBuf>) -> WrittenPacks {
        WrittenPacks {
            data,
            kept,
            fallback: RefCell::default(),
            pass: RefCell::default(),
            again: std::cell::Cell::new(false),
            reported: RefCell::default(),
            kept_listing: RefCell::default(),
            sources: RefCell::default(),
            listings: RefCell::default(),
        }
    }

    fn live(&self, namespace: &str) -> Option<PathBuf> {
        let folder = self.data.as_ref()?.join(namespace).join(FOLDER);
        is_dir(&folder).then_some(folder)
    }

    fn disk(&self, folder: &Path) -> Rc<DiskProject> {
        let source = self
            .sources
            .borrow_mut()
            .entry(folder.to_owned())
            .or_insert_with(|| Rc::new(DiskProject::new(folder.to_owned())))
            .clone();
        source.relist();
        source
    }

    fn kept_copy(&self, namespace: &str, id: &str) -> Option<PathBuf> {
        let folder = self.kept.as_ref()?.join(namespace).join(id);
        folder.join(PACK_FILE).is_file().then_some(folder)
    }

    /// Whether the last load read any written pack.
    pub fn any(&self) -> bool {
        self.pass.borrow().chosen.values().any(|p| !p.is_empty())
    }

    /// The `pack.json` of every pack the scripts of a namespace the last load read have written now, read or not
    /// yet, as a reload names it (a package's at its package path).
    pub fn pack_files(&self, home: &str) -> Vec<String> {
        let namespaces: Vec<String> = self.pass.borrow().chosen.keys().cloned().collect();
        let mut out = Vec::new();
        for namespace in namespaces {
            let Some(live) = self.live(&namespace) else {
                continue;
            };
            let mut ids: Vec<String> = std::fs::read_dir(&live)
                .into_iter()
                .flatten()
                .flatten()
                .map(|e| e.file_name().to_string_lossy().into_owned())
                .filter(|n| !n.starts_with('.') && live.join(n).join(PACK_FILE).is_file())
                .collect();
            ids.sort();
            for id in ids {
                let local = format!("{PACKS}/{id}/{PACK_FILE}");
                out.push(if namespace == home {
                    local
                } else {
                    package_path(&namespace, &local)
                });
            }
        }
        out
    }

    /// The written packs of `namespace` this pass reads, given its own files: chosen once per pass.
    fn choose(&self, namespace: &str, files: &BTreeSet<String>) -> BTreeMap<String, Chosen> {
        if let Some(chosen) = self.pass.borrow().chosen.get(namespace) {
            return chosen.clone();
        }
        let mut found = BTreeMap::new();
        if let Some(live) = self.live(namespace) {
            let own: BTreeSet<&str> = files
                .iter()
                .filter_map(|f| f.strip_prefix(PACKS)?.strip_prefix('/')?.split('/').next())
                .collect();
            let mut folders: Vec<(String, PathBuf)> = std::fs::read_dir(&live)
                .into_iter()
                .flatten()
                .flatten()
                .filter(|e| e.file_type().is_ok_and(|t| t.is_dir()))
                .map(|e| (e.file_name().to_string_lossy().into_owned(), e.path()))
                .filter(|(n, _)| !n.starts_with('.'))
                .collect();
            folders.sort();
            for (id, folder) in folders {
                if !folder.join(PACK_FILE).is_file() {
                    continue;
                }
                if own.contains(id.as_str()) {
                    let note = codes::RUNTIME_WRITTEN_PACK.at(
                        &self.shown_at(namespace, &id, PACK_FILE),
                        format!(
                            "\"{namespace}\" has a resource pack \"{id}\" of its own ({PACKS}/{id}/), so the one its scripts wrote of that name isn't read: give it another name"
                        ),
                        None,
                    );
                    self.pass.borrow_mut().notes.push(note);
                    continue;
                }
                let using = self
                    .fallback
                    .borrow()
                    .get(&key(namespace, &id))
                    .copied()
                    .unwrap_or(Use::Live);
                let from = match using {
                    Use::Live => Some(folder.clone()),
                    Use::Kept => self.kept_copy(namespace, &id),
                    Use::None => None,
                };
                if let Some(from) = from {
                    found.insert(
                        id.clone(),
                        Chosen {
                            namespace: namespace.to_owned(),
                            id,
                            live: folder,
                            source: self.disk(&from),
                            using,
                        },
                    );
                }
            }
        }
        self.pass
            .borrow_mut()
            .chosen
            .insert(namespace.to_owned(), found.clone());
        found
    }

    /// `files` with the files of `packs` laid in under `resource_packs/`.
    fn laid_in(
        &self,
        files: &BTreeSet<String>,
        packs: &BTreeMap<String, Chosen>,
    ) -> &'static BTreeSet<String> {
        let mut all = files.clone();
        for (id, pack) in packs {
            all.extend(
                pack.source
                    .listed()
                    .iter()
                    .map(|f| format!("{PACKS}/{id}/{f}")),
            );
        }
        self.listings
            .borrow_mut()
            .entry(all.clone())
            .or_insert_with(|| Box::leak(Box::new(all)))
    }

    /// `base` (a package's folder, or the project's) with the packs its scripts wrote laid in, as `namespace`'s.
    pub fn over(&self, namespace: &str, base: Rc<DiskProject>) -> Rc<dyn ProjectSource> {
        let packs = self.choose(namespace, base.listed());
        if packs.is_empty() {
            return base;
        }
        let files = self.laid_in(base.listed(), &packs);
        Rc::new(Overlaid {
            base,
            packs: packs.into_iter().map(|(id, c)| (id, c.source)).collect(),
            files,
        })
    }

    /// `files` (the project's own, as `namespace`) with the files of the packs its scripts wrote.
    pub fn with_packs(
        &self,
        namespace: &str,
        files: &'static BTreeSet<String>,
    ) -> &'static BTreeSet<String> {
        let packs = self.choose(namespace, files);
        if packs.is_empty() {
            return files;
        }
        self.laid_in(files, &packs)
    }

    /// Where a file of `namespace`'s written pack is read from, for a file at `local` in that package.
    pub fn source_of(
        &self,
        namespace: &str,
        local: &str,
    ) -> Option<(Rc<dyn ProjectSource>, String)> {
        let rest = local.strip_prefix(PACKS)?.strip_prefix('/')?;
        let (id, file) = rest.split_once('/')?;
        let pass = self.pass.borrow();
        let pack = pass.chosen.get(namespace)?.get(id)?;
        Some((pack.source.clone(), file.to_owned()))
    }

    /// Where a written pack's file at `path` (a project or package path) is on disk.
    pub fn path_of(&self, path: &str, home: &str) -> Option<PathBuf> {
        let (package, local) = split_package(path);
        let namespace = package.unwrap_or(home);
        let rest = local.strip_prefix(PACKS)?.strip_prefix('/')?;
        let (id, file) = rest.split_once('/')?;
        let pass = self.pass.borrow();
        pass.chosen.get(namespace)?.get(id)?.source.path_of(file)
    }

    /// `problem` as the owner reads it: one in a written pack's files names the file where the scripts wrote it.
    pub fn shown(&self, mut problem: Problem, home: &str) -> Problem {
        let (package, local) = split_package(&problem.file);
        let namespace = package.unwrap_or(home).to_owned();
        let Some(rest) = local.strip_prefix(PACKS).and_then(|r| r.strip_prefix('/')) else {
            return problem;
        };
        let (id, file) = rest.split_once('/').unwrap_or((rest, ""));
        let (id, file) = (id.to_owned(), file.to_owned());
        if !self
            .pass
            .borrow()
            .chosen
            .get(&namespace)
            .is_some_and(|p| p.contains_key(&id))
        {
            return problem;
        }
        problem.file = self.shown_at(&namespace, &id, &file);
        problem
    }

    /// The path a written pack's file has on the server, for problems: in the package's `nf.files` folder.
    fn shown_at(&self, namespace: &str, id: &str, rest: &str) -> String {
        let folder = match &self.data {
            Some(data) => data
                .join(namespace)
                .join(FOLDER)
                .join(id)
                .to_string_lossy()
                .replace('\\', "/"),
            None => format!("{namespace}/{FOLDER}/{id}"),
        };
        if rest.is_empty() {
            folder
        } else {
            format!("{folder}/{rest}")
        }
    }

    /// Where format names `namespace`'s pack `id`'s files.
    fn file_in(project: &Project, namespace: &str, id: &str) -> String {
        let local = format!("{PACKS}/{id}/");
        if namespace == project.namespace() {
            local
        } else {
            package_path(namespace, &local)
        }
    }

    /// Starts a pass of a load (format's `prepare`): the first of a load forgets what the last load left out.
    pub fn begin_pass(&self) {
        if !self.again.replace(false) {
            self.fallback.borrow_mut().clear();
            self.reported.borrow_mut().clear();
        }
        *self.pass.borrow_mut() = Pass::default();
    }

    /// After a pass (format's `checked`): true when a written pack it read has errors, which the next pass reads from
    /// its last good copy, or leaves out after that. Its problems, and why, are among the load's own; and once none
    /// has, each problem in a written pack names the file the scripts wrote.
    pub fn checked(&self, project: &mut Project) -> bool {
        let failing: Vec<Chosen> = self
            .pass
            .borrow()
            .chosen
            .values()
            .flat_map(|p| p.values().cloned())
            .filter(|pack| {
                let prefix = Self::file_in(project, &pack.namespace, &pack.id);
                pack.using != Use::None
                    && project
                        .problems
                        .iter()
                        .any(|p| p.severity == Severity::Error && p.file.starts_with(&prefix))
            })
            .collect();
        if !failing.is_empty() {
            for pack in &failing {
                let report = self.report(project, pack);
                self.reported.borrow_mut().extend(report);
                let next = if pack.using == Use::Live
                    && self.kept_copy(&pack.namespace, &pack.id).is_some()
                {
                    Use::Kept
                } else {
                    Use::None
                };
                self.fallback
                    .borrow_mut()
                    .insert(key(&pack.namespace, &pack.id), next);
            }
            self.again.set(true);
            return true;
        }
        self.keep();
        let home = project.namespace().to_owned();
        let problems = std::mem::take(&mut project.problems);
        project.problems = problems.into_iter().map(|p| self.shown(p, &home)).collect();
        project
            .problems
            .extend(self.pass.borrow().notes.iter().cloned());
        project
            .problems
            .extend(self.reported.borrow().iter().cloned());
        false
    }

    /// What's wrong with `pack`: its problems, at the files the scripts wrote, as warnings, and what's read instead.
    fn report(&self, project: &Project, pack: &Chosen) -> Vec<Problem> {
        let prefix = Self::file_in(project, &pack.namespace, &pack.id);
        let own: Vec<&Problem> = project
            .problems
            .iter()
            .filter(|p| p.file.starts_with(&prefix))
            .collect();
        let errors = own.iter().filter(|p| p.severity == Severity::Error).count();
        let (namespace, id) = (&pack.namespace, &pack.id);
        let message = if pack.using == Use::Kept {
            format!(
                "The last good copy of the resource pack \"{id}\" that \"{namespace}\"'s scripts wrote has errors too ({errors}) now, so it isn't built"
            )
        } else if self.kept_copy(namespace, id).is_some() {
            format!(
                "The resource pack \"{id}\" that \"{namespace}\"'s scripts wrote has errors ({errors}), so its last good copy is built instead"
            )
        } else {
            format!(
                "The resource pack \"{id}\" that \"{namespace}\"'s scripts wrote has errors ({errors}), so it isn't built until they're fixed and nf.packs.rebuild() reads it again"
            )
        };
        let mut out = vec![codes::RUNTIME_WRITTEN_PACK.at(
            &self.shown_at(namespace, id, PACK_FILE),
            message,
            None,
        )];
        if pack.using == Use::Kept {
            return out;
        }
        out.extend(own.into_iter().map(|p| {
            let mut p = p.clone();
            p.severity = Severity::Warning;
            p.file = self.shown_at(namespace, id, &p.file[prefix.len()..]);
            p
        }));
        out
    }

    /// Copies each written pack read from its live folder (which therefore loaded clean) to its last good copy, once
    /// per change, and drops the copies of packs that are gone from a namespace this load read.
    fn keep(&self) {
        let Some(kept) = &self.kept else {
            return;
        };
        let chosen = self.pass.borrow().chosen.clone();
        for (namespace, packs) in chosen {
            for pack in packs.values().filter(|p| p.using == Use::Live) {
                let Some(listing) = listing(&pack.live) else {
                    continue;
                };
                let k = key(&namespace, &pack.id);
                let target = kept.join(&namespace).join(&pack.id);
                if self.kept_listing.borrow().get(&k) == Some(&listing) && target.is_dir() {
                    continue;
                }
                if copy(&pack.live, &target) {
                    self.kept_listing.borrow_mut().insert(k, listing);
                }
            }
            let folder = kept.join(&namespace);
            let Ok(entries) = std::fs::read_dir(&folder) else {
                continue;
            };
            let written = self.data.as_ref().map(|d| d.join(&namespace).join(FOLDER));
            for entry in entries.flatten() {
                let name = entry.file_name().to_string_lossy().into_owned();
                let gone = written
                    .as_ref()
                    .is_none_or(|w| !w.join(&name).join(PACK_FILE).is_file());
                if gone {
                    let _ = std::fs::remove_dir_all(entry.path());
                    self.kept_listing
                        .borrow_mut()
                        .remove(&key(&namespace, &name));
                }
            }
        }
    }
}

fn is_dir(path: &Path) -> bool {
    std::fs::symlink_metadata(path).is_ok_and(|m| m.is_dir())
}

/// Each file's path, size and time, which change when a script writes it: none when the folder can't be read.
fn listing(folder: &Path) -> Option<String> {
    fn walk(dir: &Path, base: &Path, out: &mut Vec<String>) -> std::io::Result<()> {
        for entry in std::fs::read_dir(dir)? {
            let entry = entry?;
            let kind = entry.file_type()?;
            if kind.is_dir() {
                walk(&entry.path(), base, out)?;
            } else if kind.is_file() {
                let meta = entry.metadata()?;
                let time = meta
                    .modified()
                    .ok()
                    .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                    .map_or(0, |d| d.as_millis());
                let path = entry.path();
                let relative = path.strip_prefix(base).unwrap_or(&path).to_string_lossy();
                out.push(format!("{relative}:{}:{time}", meta.len()));
            }
        }
        Ok(())
    }
    let mut out = Vec::new();
    walk(folder, folder, &mut out).ok()?;
    out.sort();
    Some(out.join("\n"))
}

/// `from` copied whole to `to`, replacing what's there: written beside it first, then moved in.
fn copy(from: &Path, to: &Path) -> bool {
    fn copy_into(from: &Path, to: &Path) -> std::io::Result<()> {
        std::fs::create_dir_all(to)?;
        for entry in std::fs::read_dir(from)? {
            let entry = entry?;
            let kind = entry.file_type()?;
            let target = to.join(entry.file_name());
            if kind.is_symlink() {
                continue;
            } else if kind.is_dir() {
                copy_into(&entry.path(), &target)?;
            } else {
                std::fs::copy(entry.path(), &target)?;
            }
        }
        Ok(())
    }
    let Some(parent) = to.parent() else {
        return false;
    };
    if std::fs::create_dir_all(parent).is_err() {
        return false;
    }
    static COUNTER: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    let n = COUNTER.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let staging = parent.join(format!(".copy-{}-{n}", std::process::id()));
    let done = copy_into(from, &staging).and_then(|()| {
        if to.exists() {
            std::fs::remove_dir_all(to)?;
        }
        std::fs::rename(&staging, to)
    });
    if done.is_err() {
        let _ = std::fs::remove_dir_all(&staging);
    }
    done.is_ok()
}
