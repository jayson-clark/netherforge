//! Loading a project: which resources it has, and then each one read. So far
//! the first part, `Projects.load`'s scan: every path classified, group
//! folders checked, an id read once whatever group it's in, and each resource
//! checked for a usable id and the files it needs.

use std::collections::{BTreeMap, BTreeSet};

use crate::kinds::{self, Kind, Layout, PathRole, classify};
use crate::names;
use crate::problem::{Location, Problem, codes};

/// A resource the scan found: its kind, id, group folders and the files in it
/// (their paths inside the resource).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Found {
    pub kind: &'static Kind,
    pub id: String,
    pub group: String,
    pub inside: BTreeSet<String>,
}

impl Kind {
    /// Where a resource is: its folder, or its file for a single-file kind.
    pub fn location_of(&self, id: &str, group: &str) -> String {
        let inside = if group.is_empty() {
            self.folder.to_owned()
        } else {
            format!("{}/{group}", self.folder)
        };
        match self.layout {
            Layout::Folder { .. } => format!("{inside}/{id}"),
            Layout::SingleFile { extension, .. } => format!("{inside}/{id}{extension}"),
        }
    }

    pub fn main_file(&self) -> Option<&'static str> {
        match self.layout {
            Layout::Folder { main } => main,
            Layout::SingleFile { .. } => None,
        }
    }

    /// The file a resource is read from: its main file, or its location when it has none.
    pub fn path_of(&self, id: &str, group: &str) -> String {
        match self.main_file() {
            Some(main) => format!("{}/{main}", self.location_of(id, group)),
            None => self.location_of(id, group),
        }
    }

    /// The document beside a single file, when its kind has one.
    pub fn companion_path_of(&self, id: &str, group: &str) -> Option<String> {
        let Layout::SingleFile {
            extension,
            companion: Some(companion),
            ..
        } = self.layout
        else {
            return None;
        };
        let location = self.location_of(id, group);
        Some(format!(
            "{}{companion}",
            &location[..location.len() - extension.len()]
        ))
    }

    /// The kind's name in a sentence: `loot table`.
    pub fn words(&self) -> String {
        self.id.replace('_', " ")
    }
}

/// What the scan made of a project's files: its resources, in format's order
/// (kind by kind, by id), and the problems of its layout.
#[derive(Debug, Default)]
pub struct Scan {
    pub resources: Vec<Found>,
    pub problems: Vec<Problem>,
}

fn at(code: crate::ProblemCode, file: &str, message: String) -> Problem {
    code.at(file, message, None)
}

/// A file in `kind`'s folder that isn't one of its resources, which is ignored.
fn stray_file(kind: &Kind, path: &str) -> Problem {
    let shape = match kind.layout {
        Layout::Folder { main } => format!("{}/<id>/{}", kind.folder, main.unwrap_or("…")),
        Layout::SingleFile {
            extension,
            companion,
            ..
        } => format!(
            "{}/<id>{extension}{}",
            kind.folder,
            companion
                .map(|c| format!(" (and <id>{c} beside it)"))
                .unwrap_or_default()
        ),
    };
    at(
        codes::PROJECT_STRAY_FILE,
        path,
        format!(
            "Only {shape} goes in {}/, so this file is ignored",
            kind.folder
        ),
    )
}

/// `Thing`, `Resource pack`: a kind's id as a sentence's start.
fn capitalized(kind: &Kind) -> String {
    let words = kind.words();
    let mut chars = words.chars();
    chars
        .next()
        .map_or_else(String::new, |c| c.to_uppercase().chain(chars).collect())
}

/// Scans a project's `files` (its paths, `/`-separated).
pub fn scan(files: &BTreeSet<String>) -> Scan {
    let mut problems = Vec::new();
    // By kind, then id, then group: the files inside.
    let mut found: BTreeMap<&'static str, BTreeMap<String, BTreeMap<String, BTreeSet<String>>>> =
        BTreeMap::new();
    let mut bad_groups = BTreeSet::new();
    for path in files {
        let Some(kind) = kinds::in_folder(
            path.split('/')
                .next()
                .filter(|_| path.contains('/'))
                .unwrap_or(""),
        ) else {
            continue;
        };
        let classified = classify(path);
        let Some(classified) = classified.filter(|c| c.id.is_some() && c.role != PathRole::Folder)
        else {
            // Hidden files (`.DS_Store`, `.gitkeep`) are nobody's, and not worth a warning.
            if !path.split('/').skip(1).any(|p| p.starts_with('.')) {
                problems.push(stray_file(kind, path));
            }
            continue;
        };
        let group = classified.group.clone().unwrap_or_default();
        let segments: Vec<&str> = if group.is_empty() {
            Vec::new()
        } else {
            group.split('/').collect()
        };
        if !segments.is_empty() && !kind.groupable {
            let folder = format!("{}/{}", kind.folder, segments[0]);
            if bad_groups.insert(folder.clone()) {
                problems.push(at(
                    codes::PROJECT_GROUP,
                    &folder,
                    format!(
                        "{}s can't go in group folders: an id names everything in it, so they stay in {}/, and everything in here is ignored",
                        capitalized(kind),
                        kind.folder
                    ),
                ));
            }
            continue;
        }
        if let Some(bad) = segments.iter().position(|s| !names::is_group(s)) {
            // Everything in a group folder that isn't one is ignored, and said once.
            let folder = std::iter::once(kind.folder)
                .chain(segments[..=bad].iter().copied())
                .collect::<Vec<_>>()
                .join("/");
            if bad_groups.insert(folder.clone()) {
                problems.push(at(
                    codes::PROJECT_GROUP,
                    &folder,
                    format!(
                        "\"{}\" isn't a usable group name ({}), so everything in it is ignored",
                        segments[bad],
                        names::GROUP_RULE
                    ),
                ));
            }
            continue;
        }
        let inside = found
            .entry(kind.id)
            .or_default()
            .entry(classified.id.expect("filtered"))
            .or_default()
            .entry(group)
            .or_default();
        if let Some(rest) = classified.rest {
            inside.insert(rest);
        }
    }
    let mut resources = Vec::new();
    for kind in kinds::ALL {
        let Some(by_id) = found.remove(kind.id) else {
            continue;
        };
        for (id, by_group) in by_id {
            // An id is the project's whole whatever group it's in: the first place in path order is read.
            let mut groups = by_group.into_iter();
            let (first, inside) = groups.next().expect("a resource has a place");
            for (other, _) in groups {
                problems.push(
                    codes::PROJECT_DUPLICATE_ID.at(
                        &kind.location_of(&id, &other),
                        format!(
                            "Another {} is \"{id}\" too ({}): an id is the project's whole, whatever group folder it's in, so this one is ignored",
                            kind.words(),
                            kind.location_of(&id, &first)
                        ),
                        None,
                    )
                    .with_related(vec![Location {
                        file: kind.location_of(&id, &first),
                        path: None,
                    }]),
                );
            }
            if let Some(found) = check(kind, &id, &first, inside, &mut problems) {
                resources.push(found);
            }
        }
    }
    Scan {
        resources,
        problems,
    }
}

/// A resource the scan found, if it can be read: its id is usable, and the
/// files it needs are there.
fn check(
    kind: &'static Kind,
    id: &str,
    group: &str,
    inside: BTreeSet<String>,
    problems: &mut Vec<Problem>,
) -> Option<Found> {
    let location = kind.location_of(id, group);
    if !names::is_id(id) {
        problems.push(at(
            codes::PROJECT_ID,
            &location,
            format!("\"{id}\" isn't a usable id ({})", names::ID_RULE),
        ));
        return None;
    }
    if let Some(main) = kind.main_file()
        && !inside.contains(main)
    {
        problems.push(at(
            codes::PROJECT_MISSING_FILE,
            &location,
            format!("Folder has no {main}, so it's ignored"),
        ));
        return None;
    }
    let path = kind.path_of(id, group);
    let file_name = |p: &str| p.rsplit('/').next().unwrap_or(p).to_owned();
    // A file with a companion document: the document is only read beside the file.
    if let Some(companion) = kind
        .companion_path_of(id, group)
        .filter(|c| inside.contains(&file_name(c)))
        && !inside.contains(&file_name(&path))
    {
        problems.push(at(
            codes::PROJECT_MISSING_FILE,
            &companion,
            format!("There's no {path} beside this, so it's ignored"),
        ));
        return None;
    }
    Some(Found {
        kind,
        id: id.to_owned(),
        group: group.to_owned(),
        inside,
    })
}
