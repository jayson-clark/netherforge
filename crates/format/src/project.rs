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
    /// Where it was read from.
    source: &'a dyn ProjectSource,
    /// Every problem, by file and then line (each file's in the order they were found).
    pub problems: Vec<Problem>,
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

/// Reads and checks the project in `source`.
pub fn load<'a>(source: &'a dyn ProjectSource, game: Option<&'a dyn GameData>) -> Project<'a> {
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
    reader.game = game;
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
            })
        })
        .collect();

    let home = manifest
        .as_ref()
        .map(|m| m.namespace.clone())
        .unwrap_or_default();
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
    let defined = defined(&home, &resources);
    let references = ReferenceIndex {
        namespaces: BTreeMap::from([(home.clone(), defined)]),
        home,
        uses,
    };
    let mut project = Project {
        manifest,
        vanilla,
        default_font,
        resources,
        references,
        game,
        source,
        problems: Vec::new(),
    };

    let mut sink = ProblemSink::new(manifest::FILE_NAME);
    report_references(&project.references, &mut sink);
    problems.extend(sink.problems);
    for resource in &project.resources {
        problems.extend(resource.problems.iter().cloned());
        problems.extend(across(&project, resource));
    }
    // The loader's order: by file, then line, keeping the order they were found in otherwise.
    problems.sort_by(|a, b| {
        a.file
            .cmp(&b.file)
            .then(a.line.unwrap_or(0).cmp(&b.line.unwrap_or(0)))
    });
    project.problems = problems;
    project
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
fn across(project: &Project, resource: &Resource) -> Vec<Problem> {
    let cx = Across {
        project,
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
        self.project.source.reads_images()
    }

    /// A PNG's pixel facts by project path.
    pub fn image(&self, path: &str) -> Option<ImageInfo> {
        self.project.source.image(path)
    }

    /// Whether `size` knows pictures' sizes at all.
    pub fn reads_sizes(&self) -> bool {
        self.project.source.reads_sizes()
    }

    /// A PNG's `[width, height]` by project path.
    pub fn size(&self, path: &str) -> Option<[u32; 2]> {
        self.project.source.size(path)
    }

    /// The files in the resource's folder, relative to it.
    pub fn files(&self) -> &'a BTreeSet<String> {
        &self.found.inside
    }

    /// `fonts/default.json`, when it's there and reads.
    pub fn default_font(&self) -> Option<&'a crate::font::DefaultFontFile> {
        self.project.default_font.as_ref()
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

    /// The file the resource `id` of `kind` is read from, in its group folders
    /// (or where it would be if it isn't there).
    pub fn path_of(&self, kind: &str, id: &str) -> String {
        let kind = crate::kinds::by_id(kind).expect("a kind");
        let group = self
            .project
            .resources
            .iter()
            .find(|r| r.found.kind.id == kind.id && r.found.id == id)
            .map_or("", |r| r.found.group.as_str());
        kind.path_of(id, group)
    }
}

/// What a project's resources define, by the kinds of reference that can name
/// them: each kind's ids, a pack's entries, and what its datapacks define in
/// its namespace.
fn defined(home: &str, resources: &[Resource]) -> Defined {
    let read = || resources.iter().filter(|r| r.read);
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
