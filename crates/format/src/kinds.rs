//! The registry of resource kinds, and which resource a path is: format's
//! `Kinds` and its `classify`. The one list: loading, hot reload and the
//! goldens all read it. Held to format's own table (the `KINDS` it generates
//! for the editor) by `tests/kinds.rs`.

use crate::names;

/// How a kind's resources sit on disk.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Layout {
    /// A folder per resource, `<folder>/<id>/`, around its main file (when it has one).
    Folder { main: Option<&'static str> },
    /// A file per resource, `<folder>/<id><extension>`, and maybe a companion
    /// document of the same name beside it; or in a variant's folder
    /// (`migrations/postgres/001_x.sql`).
    SingleFile {
        extension: &'static str,
        companion: Option<&'static str>,
        variants: &'static [&'static str],
    },
}

/// What format makes of a kind's files.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Contents {
    Json,
    Lua,
    Sql,
    Binary,
}

#[derive(Debug, PartialEq, Eq)]
pub struct Kind {
    pub id: &'static str,
    pub folder: &'static str,
    pub layout: Layout,
    pub contents: Contents,
    pub scripted: bool,
    pub template: bool,
    pub groupable: bool,
    pub exportable: bool,
    /// The document the file beside a single file is (a structure's `.json`).
    pub companion_document: Option<&'static str>,
}

impl Kind {
    /// Its resources are JSON documents (the kind's id is the document's).
    pub fn is_document(&self) -> bool {
        self.contents == Contents::Json
    }
}

const fn folder(
    id: &'static str,
    folder: &'static str,
    main: Option<&'static str>,
    contents: Contents,
    scripted: bool,
    template: bool,
) -> Kind {
    Kind {
        id,
        folder,
        layout: Layout::Folder { main },
        contents,
        scripted,
        template,
        groupable: true,
        exportable: true,
        companion_document: None,
    }
}

const fn file(
    id: &'static str,
    folder: &'static str,
    extension: &'static str,
    contents: Contents,
) -> Kind {
    Kind {
        id,
        folder,
        layout: Layout::SingleFile {
            extension,
            companion: None,
            variants: &[],
        },
        contents,
        scripted: false,
        template: true,
        groupable: true,
        exportable: true,
        companion_document: None,
    }
}

use Contents::*;

/// Every kind, in format's order.
pub const ALL: &[Kind] = &[
    Kind {
        groupable: false,
        ..folder(
            "resource_pack",
            "resource_packs",
            Some("pack.json"),
            Json,
            false,
            true,
        )
    },
    folder(
        "particle_effect",
        "particles",
        Some("effect.json"),
        Json,
        false,
        true,
    ),
    file("cutscene", "cutscenes", ".json", Json),
    folder(
        "screen_font",
        "screen_fonts",
        Some("font.json"),
        Json,
        false,
        true,
    ),
    folder("video", "videos", Some("video.json"), Json, false, true),
    folder("wasm", "wasm", Some("module.wasm"), Binary, false, false),
    folder("module", "modules", None, Lua, true, true),
    Kind {
        layout: Layout::SingleFile {
            extension: ".sql",
            companion: None,
            variants: &["mysql", "postgres"],
        },
        exportable: false,
        ..file("migration", "migrations", ".sql", Sql)
    },
    Kind {
        layout: Layout::SingleFile {
            extension: ".nbt",
            companion: Some(".json"),
            variants: &[],
        },
        template: false,
        companion_document: Some("structure_generation"),
        ..file("structure", "structures", ".nbt", Binary)
    },
    folder("build", "builds", Some("0_0_0.nbt"), Binary, false, false),
    folder("map", "maps", Some("level.dat"), Binary, false, false),
    folder("item", "items", Some("item.json"), Json, true, true),
    file("recipe", "recipes", ".json", Json),
    file("loot_table", "loot", ".json", Json),
    file("buff", "buffs", ".json", Json),
    folder("block", "blocks", Some("block.json"), Json, true, true),
    file("game_block", "game_blocks", ".json", Json),
    folder(
        "datapack",
        "datapacks",
        Some("pack.mcmeta"),
        Json,
        false,
        true,
    ),
    file("biome", "biomes", ".json", Json),
    file("sky", "skies", ".json", Json),
    file("dimension_type", "dimension_types", ".json", Json),
    Kind {
        layout: Layout::SingleFile {
            extension: ".json",
            companion: Some(".lua"),
            variants: &[],
        },
        ..file("terrain", "terrain", ".json", Json)
    },
    file("advancement", "advancements", ".json", Json),
    folder("menu", "menus", Some("menu.json"), Json, true, true),
    folder("dialog", "dialogs", Some("dialog.json"), Json, true, true),
    folder("screen", "screens", Some("screen.json"), Json, true, true),
    folder(
        "centity",
        "centities",
        Some("centity.json"),
        Json,
        true,
        true,
    ),
];

pub fn by_id(id: &str) -> Option<&'static Kind> {
    ALL.iter().find(|k| k.id == id)
}

pub fn in_folder(folder: &str) -> Option<&'static Kind> {
    ALL.iter().find(|k| k.folder == folder)
}

/// What a path is to the project.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PathRole {
    /// A resource's main file.
    Main,
    /// Any other file of a resource.
    File,
    /// A resource's folder itself.
    Folder,
    /// One of the project's own documents (`netherforge.json`).
    Project,
    /// A group folder (`items/(weapons)`).
    Group,
}

/// Which resource a path is, and what of it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Classified {
    pub kind: Option<&'static str>,
    pub id: Option<String>,
    pub role: PathRole,
    /// The document kind the file is, if it's one.
    pub document: Option<&'static str>,
    /// The path inside the resource.
    pub rest: Option<String>,
    /// The package it's in (`acme` of `acme:items/coin/item.json`), or `None` for the project's own.
    pub package: Option<String>,
    /// Its group folders (`(weapons)/(wands)`).
    pub group: Option<String>,
}

/// A path's package (`None` for the project's own) and its path inside it.
pub fn split_package(path: &str) -> (Option<&str>, &str) {
    match path.split_once(':') {
        Some((pkg, rest)) => (Some(pkg), rest),
        None => (None, path),
    }
}

/// Which resource `path` is (`Kinds.classify`), or `None` when it's nobody's.
pub fn classify(path: &str) -> Option<Classified> {
    let path = path.replace('\\', "/");
    let (pkg, local) = split_package(&path);
    let mut found = classify_local(local)?;
    found.package = pkg.map(str::to_owned);
    Some(found)
}

fn project(document: &'static str) -> Classified {
    Classified {
        kind: None,
        id: None,
        role: PathRole::Project,
        document: Some(document),
        rest: None,
        package: None,
        group: None,
    }
}

fn classify_local(path: &str) -> Option<Classified> {
    let normalized = path.trim_matches('/');
    match normalized {
        "netherforge.json" => return Some(project("netherforge")),
        "netherforge.lock" => return Some(project("lock")),
        "fonts/default.json" => return Some(project("default_font")),
        "client/vanilla.json" => return Some(project("vanilla")),
        _ => {}
    }
    let parts: Vec<&str> = normalized.split('/').collect();
    let kind = in_folder(parts[0])?;
    // Group folders, `(weapons)/(swords)`, sit between the kind's folder and the resource's.
    let mut at = 1;
    while at < parts.len() && names::looks_like_group(parts[at]) {
        at += 1;
    }
    let group = Some(parts[1..at].join("/")).filter(|g| !g.is_empty());
    let resource = |id: String, role, document, rest: Option<String>| Classified {
        kind: Some(kind.id),
        id: Some(id),
        role,
        document,
        rest,
        package: None,
        group: group.clone(),
    };
    let Some(&name) = parts.get(at) else {
        return group.clone().map(|g| Classified {
            kind: Some(kind.id),
            id: None,
            role: PathRole::Group,
            document: None,
            rest: None,
            package: None,
            group: Some(g),
        });
    };
    // Hidden files (`.DS_Store`, `.gitkeep`) are nobody's.
    if name.is_empty() || name.starts_with('.') {
        return None;
    }
    let document = kind.is_document().then_some(kind.id);
    match kind.layout {
        Layout::Folder { main } => {
            let rest = parts[at + 1..].join("/");
            let role = if parts.len() == at + 1 {
                PathRole::Folder
            } else if Some(rest.as_str()) == main {
                PathRole::Main
            } else {
                PathRole::File
            };
            let document = document.filter(|_| role == PathRole::Main);
            Some(resource(name.to_owned(), role, document, Some(rest)))
        }
        Layout::SingleFile {
            extension,
            companion,
            variants,
        } => {
            if parts.len() == at + 2 && variants.contains(&name) {
                let file = parts[at + 1];
                if file.starts_with('.')
                    || !file.ends_with(extension)
                    || file.len() == extension.len()
                {
                    return None;
                }
                let id = file[..file.len() - extension.len()].to_owned();
                return Some(resource(
                    id,
                    PathRole::File,
                    None,
                    Some(format!("{name}/{file}")),
                ));
            }
            if parts.len() != at + 1 {
                return None;
            }
            if let Some(companion) = companion
                && name.ends_with(companion)
                && name.len() > companion.len()
            {
                // The document beside the file: part of its resource, and a document of its own kind.
                let id = name[..name.len() - companion.len()].to_owned();
                return Some(resource(
                    id,
                    PathRole::File,
                    kind.companion_document,
                    Some(name.to_owned()),
                ));
            }
            if !name.ends_with(extension) || name.len() == extension.len() {
                return None;
            }
            let id = name[..name.len() - extension.len()].to_owned();
            let rest = (companion.is_some() || !variants.is_empty()).then(|| name.to_owned());
            Some(resource(id, PathRole::Main, document, rest))
        }
    }
}
