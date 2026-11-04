//! What a project's references can name, and every reference its files make:
//! format's `ReferenceIndex`.

use std::collections::{BTreeMap, BTreeSet};

use serde::Serialize;

use super::{Found, GLYPH_TAG, RefKind, RefScope, ResourceKey, resolve, resolve_any};
use crate::kind::datapack::layout::entry_file;
use crate::kinds::{self, PathRole};
use crate::problem::{Location, ProblemCode, codes};

/// One reference a project file makes (`RefUse`): where, what it names as
/// written, and what that resolves to: a namespaced key (`shop:ruby`) for a
/// resource or a pack's entry, a project path for a file; `None` when it isn't
/// shaped like a reference of its kind. A glyph tag also says where in its
/// text it is.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct RefUse {
    pub file: String,
    pub path: String,
    pub kind: RefKind,
    pub text: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub target: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub start: Option<usize>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub end: Option<usize>,
}

impl RefUse {
    /// `found`, in the document `file` of namespace `home`.
    pub fn of(home: &str, file: &str, found: Found) -> RefUse {
        RefUse {
            target: target_of(home, file, found.kind, &found.text),
            file: file.to_owned(),
            path: found.path,
            kind: found.kind,
            text: found.text,
            start: found.span.map(|s| s.0),
            end: found.span.map(|s| s.1),
        }
    }
}

/// What a reference in `file` resolves to: a key for a resource or a pack's
/// entry, the project path for a file inside `file`'s resource.
pub fn target_of(home: &str, file: &str, kind: RefKind, text: &str) -> Option<String> {
    if kind.spec().scope != RefScope::File {
        return resolve(home, kind, text).map(|k| k.to_string());
    }
    base_of(file, kind).map(|base| base + text)
}

/// Where `kind`'s file references in `file` are relative to
/// (`resource_packs/ui/textures/`); `None` outside a folder resource.
fn base_of(file: &str, kind: RefKind) -> Option<String> {
    let at = kinds::classify(file).filter(|c| c.role == PathRole::Main)?;
    let resource = kinds::by_id(at.kind?)?;
    resource.main_file()?;
    let location = resource.location_of(&at.id?, at.group.as_deref().unwrap_or(""));
    Some(format!("{location}/{}", kind.spec().folder.unwrap_or("")))
}

/// Why a reference names nothing: a problem's code and message, and where it
/// should have been found.
#[derive(Clone, Debug, PartialEq)]
pub struct BrokenRef {
    pub code: ProblemCode,
    pub message: String,
    pub related: Vec<Location>,
}

impl BrokenRef {
    fn new(code: ProblemCode, message: impl Into<String>) -> BrokenRef {
        BrokenRef {
            code,
            message: message.into(),
            related: Vec::new(),
        }
    }
}

/// What one namespace defines: each kind's names (a kind that names `#tags`
/// has them among its names, each after a `#`), and its packs, so a missing
/// pack and a missing key read differently. A package's has `exported`, the
/// part of it other projects may name.
#[derive(Clone, Debug, Default)]
pub struct Defined {
    pub names: BTreeMap<RefKind, BTreeSet<String>>,
    pub packs: BTreeSet<String>,
    pub exported: Option<Box<Defined>>,
}

impl Defined {
    /// What a project depending on this one sees of it.
    fn visible(&self) -> &Defined {
        self.exported.as_deref().unwrap_or(self)
    }

    fn has(&self, kind: RefKind, name: &str) -> bool {
        self.names.get(&kind).is_some_and(|n| n.contains(name))
    }
}

/// What a project's references can name, by namespace (`home` its own, each
/// package it depends on what that exports), and every reference its files
/// make, file by file, each in document order.
#[derive(Clone, Debug, Default)]
pub struct ReferenceIndex {
    pub home: String,
    pub namespaces: BTreeMap<String, Defined>,
    pub uses: Vec<RefUse>,
}

impl ReferenceIndex {
    /// Every reference `file` makes.
    pub fn uses_in<'a>(&'a self, file: &'a str) -> impl Iterator<Item = &'a RefUse> {
        self.uses.iter().filter(move |u| u.file == file)
    }

    /// `text` as a reference of `kind`, resolved, if it's shaped like one.
    pub fn resolve(&self, kind: RefKind, text: &str) -> Option<ResourceKey> {
        resolve(&self.home, kind, text)
    }

    /// `text` as a reference of `kind` to one of the project's own: its id.
    pub fn own(&self, kind: RefKind, text: &str) -> Option<String> {
        self.resolve(kind, text)
            .filter(|k| k.namespace == self.home)
            .map(|k| k.path)
    }

    /// The namespaces of the packages this project depends on.
    pub fn packages(&self) -> impl Iterator<Item = &str> {
        self.namespaces
            .keys()
            .map(String::as_str)
            .filter(|n| *n != self.home)
    }

    /// Why `text`, a reference of `kind`, names nothing; `None` when it names
    /// something, or is a file's (its kind's checks look at those) or the game's.
    pub fn check(&self, kind: RefKind, text: &str) -> Option<BrokenRef> {
        let spec = kind.spec();
        if spec.scope == RefScope::File || kind.is_game(text) {
            return None;
        }
        if kind.is_datapack_tag(text) {
            return self.check_tag(kind, text);
        }
        let Some(key) = self.resolve(kind, text) else {
            return Some(BrokenRef::new(
                codes::REFERENCE_SYNTAX,
                format!(
                    "\"{text}\" isn't a reference to {}: write {}",
                    kind.a_noun(),
                    kind.shape()
                ),
            ));
        };
        let Some(all) = self.namespaces.get(&key.namespace) else {
            return Some(unknown_namespace(&key.namespace, &self.home));
        };
        let namespace = all.visible();
        if namespace.has(kind, &key.path) {
            return None;
        }
        if all.has(kind, &key.path) {
            let what = if spec.scope == RefScope::ResourcePack {
                format!("resource pack \"{}\"", key.pack())
            } else {
                format!("{} \"{}\"", spec.noun, key.path)
            };
            return Some(not_exported(&key.namespace, &what));
        }
        let own = key.namespace == self.home;
        // A bare id of a kind the game has too is likely the game's, written as if it were the project's.
        let game = if spec.game && own && !text.contains(':') {
            format!(
                "; the game's are written \"{}:{}\"",
                crate::game::NAMESPACE,
                key.path
            )
        } else {
            String::new()
        };
        Some(match spec.scope {
            RefScope::Resource => {
                let resource = kinds::by_id(spec.resource_kind.expect("a resource's kind"))
                    .expect("a kind of resource");
                // A kind a datapack can define too says where either would be.
                let datapack = spec
                    .registry
                    .map(|r| format!(", or {}", datapack_file(&key, r)))
                    .unwrap_or_default();
                let place = if own {
                    format!(" ({}{datapack})", resource.path_of(&key.path, ""))
                } else {
                    format!(" in \"{}\"", key.namespace)
                };
                BrokenRef::new(
                    kind.missing(),
                    format!("There's no {} \"{}\"{place}{game}", spec.noun, key.path),
                )
            }
            RefScope::Datapack => {
                let registry = spec.registry.expect("a datapack's entry has a registry");
                let place = if own {
                    format!(" ({})", datapack_file(&key, registry))
                } else {
                    format!(" in \"{}\"'s datapacks", key.namespace)
                };
                BrokenRef::new(
                    kind.missing(),
                    format!("There's no {} \"{}\"{place}{game}", spec.noun, key.path),
                )
            }
            RefScope::ResourcePack => {
                let in_namespace = if own {
                    String::new()
                } else {
                    format!(" in \"{}\"", key.namespace)
                };
                if !namespace.packs.contains(key.pack()) {
                    BrokenRef::new(
                        codes::REFERENCE_RESOURCE_PACK,
                        format!("There's no resource pack \"{}\"{in_namespace}", key.pack()),
                    )
                } else {
                    let pack = kinds::by_id("resource_pack").expect("packs are a kind");
                    let file = pack.path_of(key.pack(), "");
                    let file = if own {
                        file
                    } else {
                        crate::kinds::package_path(&key.namespace, &file)
                    };
                    // Sounds are files under sounds/ as much as entries in pack.json: point at the file.
                    let at = (kind != RefKind::Sound)
                        .then(|| format!("$.{}", spec.pack_entries.expect("a pack's entries")));
                    let prefix = format!("{}/", key.pack());
                    let has: Vec<&str> = namespace
                        .names
                        .get(&kind)
                        .into_iter()
                        .flatten()
                        .filter_map(|n| n.strip_prefix(&prefix))
                        .collect();
                    let hint = if has.is_empty() {
                        String::new()
                    } else {
                        format!(" (it has: {})", has.join(", "))
                    };
                    BrokenRef {
                        code: codes::REFERENCE_RESOURCE_PACK_KEY,
                        message: format!(
                            "Resource pack \"{}\"{in_namespace} has no {} \"{}\"{hint}",
                            key.pack(),
                            spec.noun,
                            key.key()
                        ),
                        related: vec![Location { file, path: at }],
                    }
                }
            }
            RefScope::File => return None,
        })
    }

    /// Why `#tag` `text` of `kind`'s tag registry names nothing.
    fn check_tag(&self, kind: RefKind, text: &str) -> Option<BrokenRef> {
        let registry = kind.spec().tag_registry.expect("a kind with tags");
        let Some(key) = resolve_any(&self.home, &text[1..]) else {
            return Some(BrokenRef::new(
                codes::REFERENCE_SYNTAX,
                format!("\"{text}\" isn't a tag: write #<namespace>:<path>"),
            ));
        };
        let Some(all) = self.namespaces.get(&key.namespace) else {
            return Some(unknown_namespace(&key.namespace, &self.home));
        };
        let name = format!("#{}", key.path);
        if all.visible().has(kind, &name) {
            return None;
        }
        if all.has(kind, &name) {
            return Some(not_exported(
                &key.namespace,
                &format!("datapack defining \"#{key}\""),
            ));
        }
        let place = if key.namespace == self.home {
            format!(
                " ({} in one of {}/)",
                entry_file(&key.namespace, &key.path, registry, true),
                datapacks_folder()
            )
        } else {
            format!(" in \"{}\"'s datapacks", key.namespace)
        };
        Some(BrokenRef::new(
            kind.missing(),
            format!("There's no {} tag \"#{key}\"{place}", kind.spec().noun),
        ))
    }

    /// Why `use_` names nothing, its message naming the tag for a glyph in text.
    pub fn check_use(&self, use_: &RefUse) -> Option<BrokenRef> {
        let mut broken = self.check(use_.kind, &use_.text)?;
        if use_.start.is_some() {
            broken.message = format!("<{GLYPH_TAG}:{}>: {}", use_.text, broken.message);
        }
        Some(broken)
    }
}

fn datapacks_folder() -> &'static str {
    kinds::by_id("datapack")
        .expect("datapacks are a kind")
        .folder
}

/// Where the entry `key` of `registry` would be, in one of the project's datapacks.
fn datapack_file(key: &ResourceKey, registry: &str) -> String {
    format!(
        "{} in one of {}/",
        entry_file(&key.namespace, &key.path, registry, false),
        datapacks_folder()
    )
}

/// Why a reference from `home` can't name anything in `namespace`: it's
/// neither `home`'s own nor a package `home` depends on. The one message, for
/// files and scripts alike.
pub fn unknown_namespace(namespace: &str, home: &str) -> BrokenRef {
    BrokenRef::new(
        codes::REFERENCE_NAMESPACE,
        format!(
            "\"{namespace}\" is neither this project's namespace (\"{home}\") nor a package's it depends on"
        ),
    )
}

/// Why package `namespace`'s `what` can't be named from outside it: it isn't
/// in its exports.
pub fn not_exported(namespace: &str, what: &str) -> BrokenRef {
    BrokenRef {
        code: codes::REFERENCE_NOT_EXPORTED,
        message: format!(
            "Package \"{namespace}\" doesn't export its {what}, so only it can use it (its netherforge.json's exports)"
        ),
        related: vec![Location {
            file: crate::kinds::package_path(namespace, crate::manifest::FILE_NAME),
            path: Some("$.exports".into()),
        }],
    }
}
