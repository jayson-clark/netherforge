//! A whole project, read and checked: format's `Projects.load`. Each resource
//! is read and checked on its own (`kind::read`), then against the rest of the
//! project: every reference it makes (`reference`), every stack it holds
//! (`item::check_across`), and its kind's own checks across resources
//! (`Document::cross_check`, with an [`Across`]).
//!
//! Not yet: packages (a project's dependencies, loaded each in its own
//! namespace, and what they export), so a reference to another namespace is
//! always one the project doesn't depend on.

use std::collections::{BTreeMap, BTreeSet};

use serde_json::Value;

use crate::game::GameData;
use crate::kind::{self, Doc, Source};
use crate::manifest::{self, Manifest};
use crate::package::{PackageSources, ResolvedPackage};
use crate::problem::{Problem, ProblemSink};
use crate::reference::{self, Defined, RefKind, RefScope, ReferenceIndex, index::RefUse};
use crate::snapshot::{self, Found};

/// One resource the scan found, read: its own problems, and its document
/// when it has one that parsed.
pub struct Resource {
    pub found: Found,
    pub problems: Vec<Problem>,
    pub document: Option<Doc>,
    /// Whether it read: a document kind's parsed, and a files kind's always does.
    pub read: bool,
    /// Whether any of its problems (its own, and against the project) is an error: then nothing should run it.
    pub errors: bool,
    /// What the server runs of a package's resource: its model with every reference written in full.
    pub qualified: Option<Box<dyn kind::Model>>,
    /// What it compiled to, when it has no errors and its kind compiles (`Document::compile`):
    /// a package's from its `qualified` model, named `ns:id`.
    pub compiled: Option<Box<dyn crate::document::Compiled>>,
}

impl Resource {
    /// Its model, if it's a `T` that parsed.
    pub fn model<T: 'static>(&self) -> Option<&T> {
        self.document
            .as_ref()
            .and_then(|d| d.model.as_any().downcast_ref::<T>())
    }
}

/// A project read and checked.
pub struct Project<'a> {
    pub manifest: Option<Manifest>,
    /// `client/vanilla.json`, when it's there and reads.
    pub vanilla: Option<crate::vanilla::VanillaFile>,
    /// `fonts/default.json`, when it's there and reads: the default font's advances, for measuring text.
    pub default_font: Option<crate::font::DefaultFontFile>,
    /// Every resource of a ported kind, in format's order (kind by kind, by id).
    pub resources: Vec<Resource>,
    /// What its references can name, and every reference its files make.
    pub references: ReferenceIndex,
    /// The game's facts it was read with.
    game: Option<&'a dyn GameData>,
    /// The packages it depends on, their own dependencies included, by namespace: each loaded on
    /// its own, in its own namespace. None for a package's own project.
    pub packages: BTreeMap<String, LoadedPackage<'a>>,
    /// Whether it's a package, checked on its own in its own namespace.
    pub in_package: bool,
    /// Every problem, by file and then line (each file's in the order they were found).
    pub problems: Vec<Problem>,
    /// Every pack, the packages' included (by `everywhere`'s names), compiled together (glyphs are numbered
    /// across them all); none when any has errors, so a server keeps its last good build.
    pub compiled_resource_packs:
        BTreeMap<String, crate::kind::resource_pack::compiled::CompiledResourcePack>,
}

/// Read access to a project's files (format's `ProjectSource`): paths are
/// project-relative with `/`. The plugin reads the disk, the editor its open
/// workspace, tests a map. What only a file's bytes say (a picture's pixels)
/// comes from the host, when it reads them.
pub trait ProjectSource {
    /// Every file in the project.
    fn files(&self) -> &BTreeSet<String>;
    /// A file's text, if it's there.
    fn read(&self, path: &str) -> Option<String>;
    /// Whether `image` reads pixels at all: then `None` for a texture that's there means it can't be read.
    fn reads_images(&self) -> bool {
        false
    }
    /// A PNG's pixel facts, when this source reads them and the file is a readable picture.
    fn image(&self, _path: &str) -> Option<ImageInfo> {
        None
    }
    /// Whether `size` answers at all: a source that reads images does, and one that only measures headers says so.
    fn reads_sizes(&self) -> bool {
        self.reads_images()
    }
    /// A PNG's `[width, height]` in pixels, where known.
    fn size(&self, path: &str) -> Option<[u32; 2]> {
        self.image(path).map(|i| [i.width, i.height])
    }
    /// Whether `wasm` reads WebAssembly modules at all: then `None` for a module that's there means it can't be
    /// read. The editor doesn't (its dev server says); the server and the CLI do.
    fn reads_wasm(&self) -> bool {
        false
    }
    /// What a module (`wasm/<id>/module.wasm`) says about itself, read by `wasm::read`.
    fn wasm(&self, _path: &str) -> Option<crate::wasm::WasmInfo> {
        None
    }
}

/// What only a picture's pixels say (`ImageInfo`): its size, and how far its
/// opaque columns reach (what a bitmap glyph's advance is measured from).
#[derive(Clone, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ImageInfo {
    pub width: u32,
    pub height: u32,
    /// The rightmost column holding a pixel that isn't fully transparent, plus one.
    pub opaque_width: u32,
    /// Whether each column, left to right, holds such a pixel; a host may leave it out for a picture no wider than a font's sheet.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub opaque_columns: Option<Vec<bool>>,
}

impl ImageInfo {
    /// The opaque width of the columns from `from` until `until` alone, counted from `from`. Without
    /// `opaque_columns` every column left of `opaque_width` is taken to hold a pixel.
    pub fn opaque_width_between(&self, from: u32, until: u32) -> u32 {
        match &self.opaque_columns {
            None => self.opaque_width.min(until).saturating_sub(from),
            Some(columns) => (from..until)
                .rev()
                .find(|x| columns.get(*x as usize).copied().unwrap_or(false))
                .map_or(0, |x| x - from + 1),
        }
    }
}

/// A project in a folder on disk: its files listed as it's opened, read as they're asked for.
pub struct Folder {
    root: std::path::PathBuf,
    files: BTreeSet<String>,
}

impl Folder {
    /// The project in `root`, if it's a folder.
    pub fn open(root: impl Into<std::path::PathBuf>) -> Option<Folder> {
        fn walk(dir: &std::path::Path, base: &std::path::Path, out: &mut BTreeSet<String>) {
            let Ok(entries) = std::fs::read_dir(dir) else {
                return;
            };
            for entry in entries.flatten() {
                let path = entry.path();
                if path.is_dir() {
                    walk(&path, base, out);
                } else if let Ok(relative) = path.strip_prefix(base) {
                    out.insert(relative.to_string_lossy().replace('\\', "/"));
                }
            }
        }
        let root = root.into();
        if !root.is_dir() {
            return None;
        }
        let mut files = BTreeSet::new();
        walk(&root, &root, &mut files);
        Some(Folder { root, files })
    }

    /// Where it is on disk.
    pub fn root(&self) -> &std::path::Path {
        &self.root
    }
}

impl ProjectSource for Folder {
    fn files(&self) -> &BTreeSet<String> {
        &self.files
    }

    fn read(&self, path: &str) -> Option<String> {
        if !self.files.contains(path) {
            return None;
        }
        std::fs::read_to_string(self.root.join(path)).ok()
    }
}

/// A project's files and a way to read them, and nothing of their bytes.
pub struct Files<'a> {
    pub files: &'a BTreeSet<String>,
    pub read: &'a dyn Fn(&str) -> Option<String>,
}

impl ProjectSource for Files<'_> {
    fn files(&self) -> &BTreeSet<String> {
        self.files
    }

    fn read(&self, path: &str) -> Option<String> {
        (self.read)(path)
    }
}

/// How a project is loaded: the game's facts (none: the checks that need them
/// say nothing), the feature table targets are held to (a test's own, since
/// the real one may hold nothing a test could rely on), and where its packages
/// come from (nowhere: every dependency is missing).
#[derive(Clone, Copy)]
pub struct Options<'a> {
    pub game: Option<&'a dyn GameData>,
    pub features: crate::features::FeatureTable<'a>,
    pub packages: &'a dyn PackageSources,
}

impl Default for Options<'_> {
    fn default() -> Self {
        Options {
            game: None,
            features: crate::features::CURRENT,
            packages: &crate::package::NoPackages,
        }
    }
}

/// A package the project depends on: as it was found, and as it loaded on its own, in its own namespace.
pub struct LoadedPackage<'a> {
    pub resolved: ResolvedPackage<'a>,
    pub project: Project<'a>,
}

/// One project's (or package's) files read, each resource checked on its own.
struct Reading {
    home: String,
    manifest: Option<Manifest>,
    manifest_text: Option<String>,
    vanilla: Option<crate::vanilla::VanillaFile>,
    default_font: Option<crate::font::DefaultFontFile>,
    resources: Vec<Resource>,
    problems: Vec<Problem>,
}

/// Loads the project in `source` and every package it depends on. Each
/// package is read and checked on its own, in its own namespace; the project
/// (and each package) can name what the packages it depends on export, and
/// nothing else of theirs.
pub fn load<'a>(source: &dyn ProjectSource, options: Options<'a>) -> Project<'a> {
    let mut root = read(source, None, &options);
    let mut problems = std::mem::take(&mut root.problems);
    let lock_text = root
        .manifest
        .as_ref()
        .and_then(|_| source.read(crate::package::LOCK_FILE));
    let mut resolution = match &root.manifest {
        Some(manifest) => crate::package::resolve(
            manifest,
            options.packages,
            crate::package::parse_lock(lock_text.as_deref()).as_ref(),
        ),
        None => crate::package::Resolution {
            packages: BTreeMap::new(),
            order: Vec::new(),
            problems: Vec::new(),
        },
    };
    problems.extend(resolution.problems.iter().cloned());
    if options.packages.checks_lock() && root.manifest.is_some() {
        crate::package::check_lock(lock_text.as_deref(), &resolution, &mut problems);
    }
    let readings: Vec<(String, Reading)> = resolution
        .order
        .iter()
        .map(|ns| {
            (
                ns.clone(),
                read(&*resolution.packages[ns].source, Some(ns), &options),
            )
        })
        .collect();
    let mut defined: BTreeMap<String, Defined> = readings
        .iter()
        .map(|(ns, reading)| (ns.clone(), defined_with_exports(reading)))
        .collect();
    defined.insert(root.home.clone(), defined_with_exports(&root));
    let mut packages = BTreeMap::new();
    for (namespace, reading) in readings {
        let resolved = resolution.packages.remove(&namespace).expect("resolved");
        let project = check(
            reading,
            &*resolved.source,
            &defined,
            &options,
            BTreeMap::new(),
            true,
        );
        packages.insert(namespace, LoadedPackage { resolved, project });
    }
    for (namespace, package) in &packages {
        problems.extend(
            package
                .project
                .problems
                .iter()
                .cloned()
                .map(|p| crate::package::in_package(namespace, p)),
        );
    }
    root.problems = problems;
    check(root, source, &defined, &options, packages, false)
}

/// One project's (or package's) files, classified and each resource read and
/// checked on its own; `home` is the namespace it's loaded as (its manifest's, for the project).
fn read(source: &dyn ProjectSource, home: Option<&str>, options: &Options) -> Reading {
    let read = |path: &str| source.read(path);
    let files: BTreeSet<String> = source
        .files()
        .iter()
        .filter(|f| !f.starts_with(".git/") && !f.starts_with(".netherforge/"))
        .cloned()
        .collect();
    let mut problems = Vec::new();
    let manifest_text = read(manifest::FILE_NAME);
    let (manifest, manifest_problems) = manifest::load(manifest_text.as_deref());
    problems.extend(manifest_problems);
    problems.extend(manifest::listing_files(manifest.as_ref(), &files));
    let minecraft = manifest.as_ref().map(|m| m.minecraft.as_str());
    let default_font = match files.contains(crate::font::FILE) {
        true => read(crate::font::FILE).and_then(|text| {
            let (file, found) = crate::font::load(&text, minecraft);
            problems.extend(found);
            file
        }),
        false => None,
    };
    let vanilla = match files.contains(crate::vanilla::FILE) {
        true => read(crate::vanilla::FILE).and_then(|text| {
            let (file, found) = crate::vanilla::load(&text, minecraft);
            problems.extend(found);
            file
        }),
        false => None,
    };
    let scan = snapshot::scan(&files);
    problems.extend(scan.problems);
    let mut reader = Source::new(&files, &read);
    reader.game = options.game;
    reader.minecraft = minecraft;
    reader.features = Some(options.features);
    let resources: Vec<Resource> = scan
        .resources
        .into_iter()
        .filter_map(|found| {
            let read = kind::read(&found, &reader)?;
            Some(Resource {
                read: read.document.is_some() || !found.kind.is_document(),
                found,
                problems: read.problems,
                document: read.document,
                errors: false,
                qualified: None,
                compiled: None,
            })
        })
        .collect();
    let home = match home {
        Some(home) => home.to_owned(),
        None => manifest
            .as_ref()
            .map(|m| m.namespace.clone())
            .unwrap_or_default(),
    };
    Reading {
        home,
        manifest,
        manifest_text,
        vanilla,
        default_font,
        resources,
        problems,
    }
}

/// `reading` checked against itself and what it can name of the packages it
/// depends on (`defined`, by namespace): with `packages`, the project's
/// project holding them. `in_package` for a package checked on its own.
fn check<'a>(
    reading: Reading,
    source: &dyn ProjectSource,
    defined: &BTreeMap<String, Defined>,
    options: &Options<'a>,
    packages: BTreeMap<String, LoadedPackage<'a>>,
    in_package: bool,
) -> Project<'a> {
    let Reading {
        home,
        manifest,
        manifest_text,
        vanilla,
        default_font,
        resources,
        mut problems,
    } = reading;
    // The manifest names resources too (a world's terrain): its references are found and checked like any file's.
    let manifest_json = manifest
        .as_ref()
        .and(manifest_text.as_deref())
        .and_then(|t| serde_json::from_str::<Value>(t).ok());
    let manifest_uses: Vec<RefUse> = manifest_json
        .iter()
        .flat_map(reference::find::<Manifest>)
        .map(|f| RefUse::of(&home, manifest::FILE_NAME, f))
        .collect();
    let uses = resources
        .iter()
        .filter_map(|r| r.document.as_ref())
        .flat_map(|d| {
            reference::find_in(d.schema, &d.json)
                .into_iter()
                .map(|f| RefUse::of(&home, &d.path, f))
        })
        .chain(manifest_uses)
        .collect();
    // Its own namespace, all of it; of each package it depends on, what that exports.
    let mut namespaces = BTreeMap::new();
    if let Some(own) = defined.get(&home) {
        namespaces.insert(
            home.clone(),
            Defined {
                exported: None,
                ..own.clone()
            },
        );
    }
    for name in manifest
        .iter()
        .flat_map(|m| m.dependencies.iter().flat_map(|d| d.iter()))
        .map(|(k, _)| &k.0)
    {
        if *name != home
            && let Some(theirs) = defined.get(name)
        {
            namespaces.insert(name.clone(), theirs.clone());
        }
    }
    let references = ReferenceIndex {
        namespaces,
        home,
        uses,
    };
    let mut project = Project {
        manifest,
        vanilla,
        default_font,
        resources,
        references,
        game: options.game,
        packages,
        in_package,
        problems: Vec::new(),
        compiled_resource_packs: BTreeMap::new(),
    };
    let mut sink = ProblemSink::new(manifest::FILE_NAME);
    exports(&project, &mut sink);
    report_references(&project.references, &mut sink);
    world_heights(&project, &mut sink);
    // The game's blocks are the whole server's: the packages' rules are checked against each other here.
    crate::kind::game_block::across_packages(&project, &mut sink);
    problems.extend(sink.problems);
    let mut errors = Vec::new();
    for resource in &project.resources {
        let found: Vec<Problem> = resource
            .problems
            .iter()
            .cloned()
            .chain(across(&project, source, resource))
            .collect();
        errors.push(crate::problem::has_errors(&found));
        problems.extend(found);
    }
    let home = project.references.home.clone();
    let minecraft = project.manifest.as_ref().map(|m| m.minecraft.clone());
    for (resource, errors) in project.resources.iter_mut().zip(errors) {
        resource.errors = errors;
        // A package's resources run beside the project's, so they name everything in full.
        if in_package
            && !errors
            && let Some(document) = &resource.document
            && let Some(kind) = kind::document_kind(resource.found.kind.id)
        {
            let json = reference::qualify(document.schema, &document.json, &home);
            resource.qualified = kind.model(&json);
        }
        if !errors {
            resource.compiled = compile(
                resource,
                &home,
                in_package,
                source,
                options,
                minecraft.as_deref(),
            );
        }
    }
    project.compiled_resource_packs = build_packs(&project, source);
    // The loader's order: by file, then line, keeping the order they were found in otherwise.
    problems.sort_by(|a, b| {
        a.file
            .cmp(&b.file)
            .then(a.line.unwrap_or(0).cmp(&b.line.unwrap_or(0)))
    });
    project.problems = problems;
    project
}

/// Every pack of `project` and its packages built together, or none when any
/// has errors (`ResourcePackKind.build`): a package's pictures are its source's.
fn build_packs(
    project: &Project,
    source: &dyn ProjectSource,
) -> BTreeMap<String, crate::kind::resource_pack::compiled::CompiledResourcePack> {
    use crate::kind::resource_pack::{compiled, sounds, under};
    let packs = project.everywhere("resource_pack");
    if packs.iter().any(|(_, r)| r.errors) {
        return BTreeMap::new();
    }
    let inputs: BTreeMap<String, compiled::PackInput> = packs
        .iter()
        .filter_map(|(name, resource)| {
            let read = resource
                .compiled
                .as_ref()?
                .as_any()
                .downcast_ref::<compiled::ResourcePackRead>()?;
            let id = name.rsplit(':').next().unwrap_or(name);
            let (sound_files, _) = sounds::files(id, under(&resource.found.inside, sounds::FOLDER));
            let texture_files = under(&resource.found.inside, crate::kind::resource_pack::TEXTURES)
                .into_iter()
                .map(str::to_owned)
                .collect();
            Some((
                name.clone(),
                compiled::PackInput {
                    read,
                    sound_files,
                    texture_files,
                },
            ))
        })
        .collect();
    let image = |path: &str| match path.split_once(':') {
        Some((namespace, local)) => project
            .packages
            .get(namespace)?
            .resolved
            .source
            .image(local),
        None => source.image(path),
    };
    compiled::compile_all(project.namespace(), &inputs, &image)
}

/// What `resource` (with no errors) compiles to, with what its own checks saw: a package's from
/// its qualified model, named as the server running it names it (`ns:id`).
fn compile(
    resource: &Resource,
    home: &str,
    in_package: bool,
    source: &dyn ProjectSource,
    options: &Options,
    minecraft: Option<&str>,
) -> Option<Box<dyn crate::document::Compiled>> {
    let found = &resource.found;
    let (model, id) = match in_package {
        true => (
            resource.qualified.as_deref()?,
            crate::kinds::package_path(home, &found.id),
        ),
        false => (resource.document.as_ref()?.model.as_ref(), found.id.clone()),
    };
    let location = found.kind.location_of(&found.id, &found.group);
    let inside = |file: &str| source.read(&format!("{location}/{file}"));
    let cx = crate::document::Context {
        id: &found.id,
        location: &location,
        read: Some(&inside),
        files: Some(&found.inside),
        game: options.game,
        minecraft,
        features: Some(options.features),
    };
    model.compile(&id, &cx)
}

/// What a reading defines, with the part it exports.
fn defined_with_exports(reading: &Reading) -> Defined {
    let exported = |kind: &str, id: &str| {
        let folder = crate::kinds::by_id(kind).map(|k| k.folder);
        reading
            .manifest
            .as_ref()
            .and_then(|m| m.exports.as_ref())
            .and_then(|e| e.get_str(folder.unwrap_or_default()))
            .is_some_and(|ids| ids.iter().any(|i| i == id))
    };
    Defined {
        exported: Some(Box::new(defined(
            &reading.home,
            &reading.resources,
            &exported,
        ))),
        ..defined(&reading.home, &reading.resources, &|_, _| true)
    }
}

impl<'a> Project<'a> {
    /// The game's facts it was read with.
    pub fn game(&self) -> Option<&'a dyn GameData> {
        self.game
    }

    /// The project's namespace: what its references resolve in when they name none.
    pub fn namespace(&self) -> &str {
        &self.references.home
    }

    /// Every resource of `kind` in the project and every package, as a server
    /// running them names them: the project's own by id (`ruby`), a package's
    /// as `ns:id` (`acme:coin`), which is how the project refers to it too.
    pub fn everywhere(&self, kind: &str) -> Vec<(String, &Resource)> {
        let own = self
            .resources
            .iter()
            .filter(|r| r.read && r.found.kind.id == kind)
            .map(|r| (r.found.id.clone(), r));
        let theirs = self.packages.iter().flat_map(|(namespace, package)| {
            package
                .project
                .resources
                .iter()
                .filter(|r| r.read && r.found.kind.id == kind)
                .map(move |r| (crate::kinds::package_path(namespace, &r.found.id), r))
        });
        own.chain(theirs).collect()
    }

    /// What the server runs of model `T`, the packages' included: each resource
    /// with no errors, a package's with every reference written in full, by the name
    /// `everywhere` gives it.
    pub fn running<T: 'static>(&self) -> Vec<(String, &T)> {
        let own = self
            .resources
            .iter()
            .filter(|r| !r.errors)
            .filter_map(|r| Some((r.found.id.clone(), r.model::<T>()?)));
        let theirs = self.packages.iter().flat_map(|(namespace, package)| {
            package
                .project
                .resources
                .iter()
                .filter(|r| !r.errors)
                .filter_map(move |r| {
                    let model = r.qualified.as_ref()?.as_any().downcast_ref::<T>()?;
                    Some((crate::kinds::package_path(namespace, &r.found.id), model))
                })
        });
        own.chain(theirs).collect()
    }

    /// What the server runs of the resources that compile to `C` (`Document::compile`), the
    /// packages' included, by the name `everywhere` gives them: each with no errors.
    pub fn compiled<C: 'static>(&self) -> Vec<(String, &C)> {
        fn typed<C: 'static>(r: &Resource) -> Option<&C> {
            r.compiled.as_ref()?.as_any().downcast_ref::<C>()
        }
        let own = self
            .resources
            .iter()
            .filter_map(|r| Some((r.found.id.clone(), typed::<C>(r)?)));
        let theirs = self.packages.iter().flat_map(|(namespace, package)| {
            package.project.resources.iter().filter_map(move |r| {
                Some((
                    crate::kinds::package_path(namespace, &r.found.id),
                    typed::<C>(r)?,
                ))
            })
        });
        own.chain(theirs).collect()
    }

    /// Every resource of `kind` that compiled, the packages' included, by the name `everywhere`
    /// gives it.
    pub fn compiled_of(&self, kind: &str) -> Vec<(String, &dyn crate::document::Compiled)> {
        self.everywhere(kind)
            .into_iter()
            .filter_map(|(name, r)| Some((name, r.compiled.as_deref()?)))
            .collect()
    }

    /// The manifest of `namespace`: the project's own, or a package's.
    pub fn manifest_of(&self, namespace: &str) -> Option<&Manifest> {
        if namespace == self.namespace() {
            self.manifest.as_ref()
        } else {
            self.packages.get(namespace)?.resolved.manifest.as_ref()
        }
    }

    /// What code or files in `namespace` (the project's own, or a package's) can name.
    pub fn references_of(&self, namespace: &str) -> Option<&ReferenceIndex> {
        if namespace == self.namespace() {
            Some(&self.references)
        } else {
            Some(&self.packages.get(namespace)?.project.references)
        }
    }

    /// The file the resource `id` of `kind` is read from: the project's own by
    /// id, a package's as `ns:id` (at its package path), in its group folders.
    /// One that isn't there is where a new one would go.
    pub fn place_of(&self, kind: &str, id: &str) -> String {
        let spec = crate::kinds::by_id(kind).expect("a kind");
        let (package, bare) = crate::kinds::split_package(id);
        let resources = match package {
            None => Some(&self.resources),
            Some(p) => self.packages.get(p).map(|p| &p.project.resources),
        };
        let group = resources
            .into_iter()
            .flatten()
            .find(|r| r.found.kind.id == kind && r.found.id == bare)
            .map_or("", |r| r.found.group.as_str());
        let path = spec.path_of(bare, group);
        match package {
            Some(p) => crate::kinds::package_path(p, &path),
            None => path,
        }
    }

    /// Why something in `from` (the project's namespace, or a package's) can't
    /// name `key`, a resource of `kind`: its namespace is neither `from` nor a
    /// package `from` depends on, or it's that package's and it doesn't export
    /// it. `None` when it may (whether it exists is the caller's to say).
    pub fn nameable(
        &self,
        from: &str,
        kind: &str,
        key: &reference::ResourceKey,
    ) -> Option<reference::BrokenRef> {
        if key.namespace == from {
            return None;
        }
        let depends = self
            .manifest_of(from)
            .and_then(|m| m.dependencies.as_ref())
            .is_some_and(|d| d.get_str(&key.namespace).is_some());
        if !depends {
            return Some(reference::index::unknown_namespace(&key.namespace, from));
        }
        let target = self.packages.get(&key.namespace)?;
        let spec = crate::kinds::by_id(kind).expect("a kind");
        let exists = target
            .project
            .resources
            .iter()
            .any(|r| r.read && r.found.kind.id == kind && r.found.id == key.path);
        let exported = target
            .resolved
            .manifest
            .as_ref()
            .and_then(|m| m.exports.as_ref())
            .and_then(|e| e.get_str(spec.folder))
            .is_some_and(|ids| ids.contains(&key.path));
        (exists && !exported).then(|| {
            reference::index::not_exported(
                &key.namespace,
                &format!("{} \"{}\"", spec.words(), key.path),
            )
        })
    }
}

/// Every broken reference the file of `sink` makes, reported in it.
fn report_references(references: &ReferenceIndex, sink: &mut ProblemSink) {
    let file = sink.file.clone();
    for use_ in references.uses_in(&file) {
        if let Some(broken) = references.check_use(use_) {
            sink.problems.push(
                broken
                    .code
                    .at(&file, broken.message, Some(use_.path.clone()))
                    .with_related(broken.related),
            );
        }
    }
}

/// What `resource` says about the rest of the project: its kind's checks
/// across resources, its stacks', and its references'.
fn across(project: &Project, source: &dyn ProjectSource, resource: &Resource) -> Vec<Problem> {
    let cx = Across {
        project,
        source,
        found: &resource.found,
    };
    let Some(document) = &resource.document else {
        return kind::files::cross_check(&resource.found, &cx);
    };
    let mut sink = ProblemSink::new(&document.path);
    document.model.cross_check(&cx, &mut sink);
    crate::item::check_stacks(document, &cx, &mut sink);
    report_references(&project.references, &mut sink);
    sink.problems
}

/// What a kind's checks across resources know (`KindContext`): the resource,
/// and the project around it.
pub struct Across<'a> {
    project: &'a Project<'a>,
    source: &'a dyn ProjectSource,
    pub found: &'a Found,
}

impl<'a> Across<'a> {
    pub fn id(&self) -> &'a str {
        &self.found.id
    }

    pub fn references(&self) -> &'a ReferenceIndex {
        &self.project.references
    }

    /// The project's namespace.
    pub fn home(&self) -> &'a str {
        &self.project.references.home
    }

    pub fn game(&self) -> Option<&'a dyn GameData> {
        self.project.game
    }

    /// The project's target Minecraft version, when its manifest reads.
    pub fn minecraft(&self) -> Option<&'a str> {
        self.project.manifest.as_ref().map(|m| m.minecraft.as_str())
    }

    /// `client/vanilla.json`, when it's there and reads.
    pub fn vanilla(&self) -> Option<&'a crate::vanilla::VanillaFile> {
        self.project.vanilla.as_ref()
    }

    /// Whether `image` reads pixels at all.
    pub fn reads_images(&self) -> bool {
        self.source.reads_images()
    }

    /// A PNG's pixel facts by project path.
    pub fn image(&self, path: &str) -> Option<ImageInfo> {
        self.source.image(path)
    }

    /// Whether `size` knows pictures' sizes at all.
    pub fn reads_sizes(&self) -> bool {
        self.source.reads_sizes()
    }

    /// A PNG's `[width, height]` by project path.
    pub fn size(&self, path: &str) -> Option<[u32; 2]> {
        self.source.size(path)
    }

    /// Whether `wasm` reads WebAssembly modules at all.
    pub fn reads_wasm(&self) -> bool {
        self.source.reads_wasm()
    }

    /// What a WebAssembly module says about itself, by project path.
    pub fn wasm(&self, path: &str) -> Option<crate::wasm::WasmInfo> {
        self.source.wasm(path)
    }

    /// The files in the resource's folder, relative to it.
    pub fn files(&self) -> &'a BTreeSet<String> {
        &self.found.inside
    }

    /// A project file's text.
    pub fn read_file(&self, path: &str) -> Option<String> {
        self.source.read(path)
    }

    /// What the project's own resources put in the start-up datapack, by
    /// registry and path: which resource (kind and id) does, the first in
    /// format's order.
    pub fn datapack_entries(&self) -> BTreeMap<(&'static str, String), (&'static str, &'a str)> {
        let mut out = BTreeMap::new();
        for r in &self.project.resources {
            let Some(document) = &r.document else {
                continue;
            };
            for entry in document.model.datapack_entries(&r.found.id) {
                out.entry(entry)
                    .or_insert((r.found.kind.id, r.found.id.as_str()));
            }
        }
        out
    }

    /// `fonts/default.json`, when it's there and reads.
    pub fn default_font(&self) -> Option<&'a crate::font::DefaultFontFile> {
        self.project.default_font.as_ref()
    }

    /// The packages the project depends on, by namespace (none while a package is checked on its own).
    pub fn packages(&self) -> &'a BTreeMap<String, LoadedPackage<'a>> {
        &self.project.packages
    }

    /// Where a resource the server names `name` (the project's by id, a package's as `ns:id`) is read from.
    pub fn place_of_everywhere(&self, kind: &str, name: &str) -> String {
        self.project.place_of(kind, name)
    }

    /// Whether the resource is a package's, checked on its own in its own namespace.
    pub fn in_package(&self) -> bool {
        self.project.in_package
    }

    /// Every resource of a kind (by its id) that read, by id.
    pub fn read(&self, kind: &str) -> impl Iterator<Item = &'a Resource> {
        self.project
            .resources
            .iter()
            .filter(move |r| r.found.kind.id == kind && r.read)
    }

    /// Whether a resource of `kind` named `id` read.
    pub fn has(&self, kind: &str, id: &str) -> bool {
        self.read(kind).any(|r| r.found.id == id)
    }

    /// Every resource whose model is a `T`, by id, in id order.
    pub fn models<T: 'static>(&self) -> impl Iterator<Item = (&'a str, &'a T)> {
        self.project
            .resources
            .iter()
            .filter_map(|r| Some((r.found.id.as_str(), r.model::<T>()?)))
    }

    /// The resource of model `T` named `id`.
    pub fn model<T: 'static>(&self, id: &str) -> Option<&'a T> {
        self.models::<T>().find(|(i, _)| *i == id).map(|(_, m)| m)
    }

    /// The group folders the resource `id` of `kind` is in (none when it isn't there).
    fn group_of(&self, kind: &str, id: &str) -> &'a str {
        self.project
            .resources
            .iter()
            .find(|r| r.found.kind.id == kind && r.found.id == id)
            .map_or("", |r| r.found.group.as_str())
    }

    /// The file the resource `id` of `kind` is read from, in its group folders
    /// (or where it would be if it isn't there).
    pub fn path_of(&self, kind: &str, id: &str) -> String {
        crate::kinds::by_id(kind)
            .expect("a kind")
            .path_of(id, self.group_of(kind, id))
    }

    /// Where the resource `id` of `kind` is: its folder, or its one file.
    pub fn location_of(&self, kind: &str, id: &str) -> String {
        crate::kinds::by_id(kind)
            .expect("a kind")
            .location_of(id, self.group_of(kind, id))
    }
}

/// What a project's resources define, by the kinds of reference that can name
/// them: each kind's ids, a pack's entries, and what its datapacks define in
/// its namespace.
fn defined(home: &str, resources: &[Resource], include: &dyn Fn(&str, &str) -> bool) -> Defined {
    let read = || {
        resources
            .iter()
            .filter(|r| r.read && include(r.found.kind.id, &r.found.id))
    };
    let ids = |kind: &str| -> BTreeSet<String> {
        read()
            .filter(|r| r.found.kind.id == kind)
            .map(|r| r.found.id.clone())
            .collect()
    };
    // What the datapacks define in this namespace, by registry: a biome's or a placed feature's names.
    let mut entries: BTreeMap<(String, bool), BTreeSet<String>> = BTreeMap::new();
    for r in read() {
        if let Some(meta) = r.model::<kind::datapack::PackMeta>() {
            for path in kind::datapack::layout::entries(meta, &r.found.inside) {
                if path.namespace == home {
                    let name = if path.tag {
                        format!("#{}", path.path)
                    } else {
                        path.path
                    };
                    entries
                        .entry((path.registry, path.tag))
                        .or_default()
                        .insert(name);
                }
            }
        }
    }
    let of_registry = |registry: Option<&str>, tag: bool| -> BTreeSet<String> {
        registry
            .and_then(|r| entries.get(&(r.to_owned(), tag)))
            .cloned()
            .unwrap_or_default()
    };
    let packs: Vec<(&str, &kind::resource_pack::ResourcePackFile, &Found)> = read()
        .filter_map(|r| Some((r.found.id.as_str(), r.model()?, &r.found)))
        .collect();
    let mut names = BTreeMap::new();
    for &kind in RefKind::ALL {
        let spec = kind.spec();
        let set: BTreeSet<String> = match spec.scope {
            RefScope::Resource => {
                let mut set = ids(spec.resource_kind.expect("a resource's kind"));
                set.extend(of_registry(spec.registry, false));
                set.extend(of_registry(spec.tag_registry, true));
                set
            }
            RefScope::Datapack => of_registry(spec.registry, false),
            RefScope::ResourcePack => packs
                .iter()
                .flat_map(|(pack, file, found)| {
                    pack_entries(kind, file, found)
                        .into_iter()
                        .map(move |key| format!("{pack}/{key}"))
                })
                .collect(),
            RefScope::File => continue,
        };
        names.insert(kind, set);
    }
    Defined {
        names,
        packs: packs.iter().map(|(p, _, _)| p.to_string()).collect(),
        exported: None,
    }
}

/// The keys of a pack's entries `kind` names.
fn pack_entries(
    kind: RefKind,
    file: &kind::resource_pack::ResourcePackFile,
    found: &Found,
) -> Vec<String> {
    use kind::resource_pack::sounds;
    let keys = |e: &mut dyn Iterator<Item = &kind::resource_pack::PackKey>| {
        e.map(|k| k.0.clone()).collect::<Vec<_>>()
    };
    match kind {
        RefKind::Skin => keys(&mut file.skins.iter().map(|(k, _)| k)),
        RefKind::Glyph => keys(&mut file.glyphs.iter().map(|(k, _)| k)),
        RefKind::ItemModel => keys(&mut file.items.iter().map(|(k, _)| k)),
        RefKind::Tooltip => keys(&mut file.tooltips.iter().map(|(k, _)| k)),
        RefKind::Equipment => keys(&mut file.equipment.iter().map(|(k, _)| k)),
        RefKind::BlockModel => keys(&mut file.blocks.iter().map(|(k, _)| k)),
        RefKind::Hud => file
            .hud
            .iter()
            .flat_map(|h| h.keys())
            .map(|k| k.0.clone())
            .collect(),
        RefKind::Sound => {
            let location = found.kind.location_of(&found.id, &found.group);
            let prefix = format!("{}/", sounds::FOLDER);
            let under = found.inside.iter().filter_map(|f| f.strip_prefix(&prefix));
            let usable = sounds::files(&location, under).0;
            sounds::events(file, &usable)
                .into_keys()
                .filter(|k| sounds::is_key(k))
                .collect()
        }
        _ => unreachable!("{kind:?} isn't a pack's entry"),
    }
}

/// `exports` names kinds by their folders, and resources the project has.
fn exports(project: &Project, sink: &mut ProblemSink) {
    let Some(exports) = project.manifest.as_ref().and_then(|m| m.exports.as_ref()) else {
        return;
    };
    for (folder, ids) in exports.iter() {
        let at = crate::json::child_path("$.exports", folder);
        let Some(kind) = crate::kinds::in_folder(folder).filter(|k| k.exportable) else {
            let kinds: Vec<&str> = crate::kinds::ALL
                .iter()
                .filter(|k| k.exportable)
                .map(|k| k.folder)
                .collect();
            sink.report(
                crate::codes::PACKAGE_EXPORT_KIND,
                format!(
                    "\"{folder}\" isn't a kind of resource: export from {}",
                    kinds.join(", ")
                ),
                Some(at),
            );
            continue;
        };
        for (i, id) in ids.iter().enumerate() {
            let there = project
                .resources
                .iter()
                .any(|r| r.read && r.found.kind.id == kind.id && r.found.id == *id);
            if !there {
                sink.report(
                    crate::codes::PACKAGE_EXPORT_MISSING,
                    format!(
                        "There's no {} \"{id}\" ({}) to export",
                        kind.id,
                        kind.location_of(id, "")
                    ),
                    Some(format!("{at}[{i}]")),
                );
            }
        }
    }
}

/// Each world that names both a terrain and a dimension type: the terrain held
/// to that dimension's build limits. On its own a terrain is held only to what
/// any world can be, so this finds what's out of range in this world alone. A
/// reference that names nothing is the reference check's.
fn world_heights(project: &Project, sink: &mut ProblemSink) {
    use crate::kind::dimension_type::DimensionTypeFile;
    use crate::kind::terrain::{TerrainFile, WorldHeight};
    let Some(worlds) = project.manifest.as_ref().and_then(|m| m.worlds.as_ref()) else {
        return;
    };
    let own = |kind: &str, reference: RefKind, text: &str| {
        let id = project.references.own(reference, text)?;
        project
            .resources
            .iter()
            .find(|r| r.found.kind.id == kind && r.found.id == id)
    };
    for (name, world) in worlds.iter() {
        let (Some(terrain_ref), Some(dimension_ref)) = (&world.terrain, &world.dimension_type)
        else {
            continue;
        };
        let Some(terrain) = own("terrain", RefKind::Terrain, terrain_ref) else {
            continue;
        };
        let (Some(model), Some(document)) = (terrain.model::<TerrainFile>(), &terrain.document)
        else {
            continue;
        };
        let Some(dimension) = own("dimension_type", RefKind::DimensionType, dimension_ref)
            .and_then(|r| r.model::<DimensionTypeFile>())
        else {
            continue;
        };
        let (min_y, max_y) = dimension.world_height();
        let at = crate::json::child_path(&crate::json::child_path("$.worlds", name), "terrain");
        for problem in model.in_world(&document.json, WorldHeight { min_y, max_y }) {
            let field = problem
                .path
                .as_deref()
                .map_or("the file", |p| p.strip_prefix("$.").unwrap_or(p));
            sink.problems.push(
                crate::codes::PROJECT_WORLD_HEIGHT
                    .at(
                        &sink.file,
                        format!(
                            "World \"{name}\" is from y {min_y} to {} (dimension type \"{dimension_ref}\"), but in terrain \"{terrain_ref}\", {field}: {}",
                            max_y - 1,
                            problem.message
                        ),
                        Some(at.clone()),
                    )
                    .with_related(vec![crate::Location {
                        file: document.path.clone(),
                        path: problem.path.clone(),
                    }]),
            );
        }
    }
}
