//! A project's dependencies (format's `Packages`, `VersionRange` and the
//! lock's models): one package per namespace across the whole tree, each found
//! where its requirer's `netherforge.json` says (a folder relative to the
//! requirer's, or a git repository), and what's wrong on the way.

use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet, VecDeque};
use std::sync::LazyLock;

use regex::Regex;
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

use crate::canonical::Map;
use crate::json::child_path;
use crate::manifest::{self, Dependency, Manifest};
use crate::names;
use crate::problem::{Location, Problem, codes};
use crate::project::ProjectSource;

pub const LOCK_FILE: &str = "netherforge.lock";
pub const LOCK_SCHEMA: &str = ".netherforge/schema/lock.schema.json";

/// `netherforge.lock`: every package the project uses, by namespace, as it resolved.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct LockFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    pub packages: Map<LockedPackage>,
}

/// One package as it resolved: its version, where from, and its content hash.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct LockedPackage {
    pub version: String,
    pub source: PackageSource,
    pub hash: String,
}

/// Where a package came from: a folder, a git commit, or (for a later
/// NetherForge) a registry, whose lock is out of date for this one.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize, JsonSchema)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub enum PackageSource {
    /// A folder, relative to the locking project's own (`../economy`).
    Path {
        path: String,
    },
    /// A commit of a repository: the dependency's `url` and `rev` as the manifest says them, and the commit they resolved to.
    Git {
        url: String,
        #[serde(skip_serializing_if = "Option::is_none")]
        rev: Option<String>,
        commit: String,
    },
    Registry {
        url: String,
    },
}

/// What content a package's hash covers, and how it's made (`PackageHash`).
pub mod hash {
    pub const PREFIX: &str = "sha256:";

    /// Whether `path` (relative to a package's folder) is part of the package: hashed and bundled.
    pub fn is_content(path: &str) -> bool {
        if path.split('/').any(|p| p.starts_with('.')) {
            return false;
        }
        crate::kinds::classify(path).is_some_and(|c| {
            c.kind.is_some() || matches!(c.document, Some("netherforge" | "vanilla"))
        })
    }

    /// The text whose SHA-256 is the hash, from every file's hex SHA-256 by path.
    pub fn listing<'a>(digests: impl IntoIterator<Item = (&'a str, &'a str)>) -> String {
        let mut content: Vec<(&str, &str)> =
            digests.into_iter().filter(|(p, _)| is_content(p)).collect();
        content.sort();
        content
            .iter()
            .map(|(path, digest)| format!("{}  {path}\n", digest.to_lowercase()))
            .collect()
    }

    /// The hash, from the hex SHA-256 of the listing.
    pub fn of(listing_sha256: &str) -> String {
        format!("{PREFIX}{}", listing_sha256.to_lowercase())
    }
}

/// A version as Semantic Versioning orders them; build metadata never counts.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SemVer {
    pub major: u64,
    pub minor: u64,
    pub patch: u64,
    pub prerelease: Vec<String>,
}

impl SemVer {
    pub fn parse(text: &str) -> Option<SemVer> {
        let [major, minor, patch, pre] = names::version_parts(text)?;
        Some(SemVer {
            major: major.parse().ok()?,
            minor: minor.parse().ok()?,
            patch: patch.parse().ok()?,
            prerelease: if pre.is_empty() {
                Vec::new()
            } else {
                pre.split('.').map(String::from).collect()
            },
        })
    }

    fn release(&self) -> SemVer {
        SemVer {
            prerelease: Vec::new(),
            ..self.clone()
        }
    }

    fn of(major: u64, minor: u64, patch: u64) -> SemVer {
        SemVer {
            major,
            minor,
            patch,
            prerelease: Vec::new(),
        }
    }
}

impl Ord for SemVer {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        use std::cmp::Ordering;
        let release =
            (self.major, self.minor, self.patch).cmp(&(other.major, other.minor, other.patch));
        if release != Ordering::Equal {
            return release;
        }
        match (self.prerelease.is_empty(), other.prerelease.is_empty()) {
            (true, true) => return Ordering::Equal,
            (true, false) => return Ordering::Greater,
            (false, true) => return Ordering::Less,
            _ => {}
        }
        for (a, b) in self.prerelease.iter().zip(&other.prerelease) {
            let c = match (a.parse::<u64>(), b.parse::<u64>()) {
                (Ok(a), Ok(b)) => a.cmp(&b),
                (Ok(_), Err(_)) => Ordering::Less,
                (Err(_), Ok(_)) => Ordering::Greater,
                _ => a.cmp(b),
            };
            if c != Ordering::Equal {
                return c;
            }
        }
        self.prerelease.len().cmp(&other.prerelease.len())
    }
}

impl PartialOrd for SemVer {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
}

/// The versions a dependency takes, a subset of npm's ranges: `*`, `1.2.3`
/// or `=1.2.3`, `^1.2.3`, `~1.2.3`, and `>`, `>=`, `<`, `<=`; comparisons with
/// spaces between them all hold, `||` between sets takes either. A prerelease
/// is in a range only when its set names a prerelease of the same version.
#[derive(Clone, Debug)]
pub struct VersionRange {
    text: String,
    sets: Vec<Vec<(Op, SemVer)>>,
}

#[derive(Clone, Copy, Debug)]
enum Op {
    Eq,
    Gt,
    Ge,
    Lt,
    Le,
}

impl VersionRange {
    /// What a range may be, as an error says it.
    pub const RULE: &str = "a version range like ^1.2.0, ~1.2.0, >=1.0.0 <2.0.0, 1.2.3 or *";

    pub fn parse(text: &str) -> Option<VersionRange> {
        let trimmed = text.trim();
        if trimmed.is_empty() {
            return None;
        }
        let mut sets = Vec::new();
        for part in trimmed.split("||") {
            let words: Vec<&str> = part.split_whitespace().collect();
            if words.is_empty() {
                return None;
            }
            let mut set = Vec::new();
            for word in words {
                set.extend(comparisons(word)?);
            }
            sets.push(set);
        }
        Some(VersionRange {
            text: trimmed.to_owned(),
            sets,
        })
    }

    pub fn contains(&self, version: &SemVer) -> bool {
        self.sets.iter().any(|set| {
            set.iter().all(|(op, v)| {
                let c = version.cmp(v);
                match op {
                    Op::Eq => c.is_eq(),
                    Op::Gt => c.is_gt(),
                    Op::Ge => c.is_ge(),
                    Op::Lt => c.is_lt(),
                    Op::Le => c.is_le(),
                }
            }) && (version.prerelease.is_empty()
                || set
                    .iter()
                    .any(|(_, v)| !v.prerelease.is_empty() && v.release() == version.release()))
        })
    }

    /// Whether `version` is a version and in the range.
    pub fn admits(&self, version: &str) -> bool {
        SemVer::parse(version).is_some_and(|v| self.contains(&v))
    }
}

impl std::fmt::Display for VersionRange {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.text)
    }
}

fn comparisons(word: &str) -> Option<Vec<(Op, SemVer)>> {
    if word == "*" {
        return Some(Vec::new());
    }
    let op = [">=", "<=", ">", "<", "=", "^", "~"]
        .into_iter()
        .find(|op| word.starts_with(op));
    let version = SemVer::parse(&word[op.map_or(0, str::len)..])?;
    Some(match op {
        None | Some("=") => vec![(Op::Eq, version)],
        Some(">") => vec![(Op::Gt, version)],
        Some(">=") => vec![(Op::Ge, version)],
        Some("<") => vec![(Op::Lt, version)],
        Some("<=") => vec![(Op::Le, version)],
        Some("~") => {
            let below = SemVer::of(version.major, version.minor + 1, 0);
            vec![(Op::Ge, version), (Op::Lt, below)]
        }
        _ => {
            let below = if version.major > 0 {
                SemVer::of(version.major + 1, 0, 0)
            } else if version.minor > 0 {
                SemVer::of(0, version.minor + 1, 0)
            } else {
                SemVer::of(0, 0, version.patch + 1)
            };
            vec![(Op::Ge, version), (Op::Lt, below)]
        }
    })
}

/// Where a dependency is to be found, as format asks a host for it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PackageRequest {
    /// A folder, relative to the root project's own, `..` resolved as far as it goes (`../economy`).
    Folder(String),
    /// A repository: exactly `commit` when the lock pins one, otherwise whatever `rev` (or the default branch) is now.
    Git {
        url: String,
        rev: Option<String>,
        commit: Option<String>,
    },
}

/// A package a host found: its files, their hash when the host computes one,
/// the commit it is for a git package, and where it came from when that isn't
/// where the dependency points.
pub struct OpenedPackage<'a> {
    pub source: Box<dyn ProjectSource + 'a>,
    pub hash: Option<String>,
    pub commit: Option<String>,
    pub from: Option<String>,
}

/// What a host found for a request.
pub enum PackageLookup<'a> {
    Opened(OpenedPackage<'a>),
    /// Nothing there: no project at a folder (no reason), or a repository that couldn't be fetched (why).
    Missing(Option<String>),
}

/// Where a project's packages come from.
pub trait PackageSources {
    /// The package a dependency named `namespace` asks for with `request`.
    fn open(&self, namespace: &str, request: &PackageRequest) -> PackageLookup<'_>;

    /// Whether the project's `netherforge.lock` is checked against what resolves: a bundle has none.
    fn checks_lock(&self) -> bool {
        true
    }

    /// As a load starts: a host that decides where the project and its packages are on every load (a bundle, the
    /// server's own packages) decides it again.
    fn prepare(&self) {}

    /// The project as it loaded, for the host to add what it finds wrong besides format's problems (a bundle that
    /// isn't what it was built as): true to load it again, the host having changed what it reads (a resource pack
    /// with errors left out).
    fn checked(&self, _project: &mut crate::project::Project<'_>) -> bool {
        false
    }
}

/// Nowhere: every dependency is missing.
pub struct NoPackages;

impl PackageSources for NoPackages {
    fn open(&self, _: &str, _: &PackageRequest) -> PackageLookup<'_> {
        PackageLookup::Missing(None)
    }
}

/// Packages in folders beside the project's (its dependencies' `path`s, from
/// `root`, the project's folder); a git dependency is missing. What a host
/// that doesn't hash or fetch hands the loader.
pub struct Folders {
    pub root: std::path::PathBuf,
}

impl PackageSources for Folders {
    fn open(&self, _: &str, request: &PackageRequest) -> PackageLookup<'_> {
        let PackageRequest::Folder(location) = request else {
            return PackageLookup::Missing(Some("this host doesn't fetch from git".into()));
        };
        match crate::project::Folder::open(self.root.join(location)) {
            Some(folder) if folder.files().contains(manifest::FILE_NAME) => {
                PackageLookup::Opened(OpenedPackage {
                    source: Box::new(folder),
                    hash: None,
                    commit: None,
                    from: None,
                })
            }
            _ => PackageLookup::Missing(None),
        }
    }
}

/// One package the tree resolved to.
pub struct ResolvedPackage<'a> {
    pub namespace: String,
    /// Where its files are: a folder relative to the root project's, or `git:<commit>` in the cache.
    pub location: String,
    pub origin: PackageSource,
    /// Its manifest, when that reads (its own problems say why not).
    pub manifest: Option<Manifest>,
    pub source: Box<dyn ProjectSource + 'a>,
    pub hash: Option<String>,
    /// The namespaces of what depends on it, in the order they were found.
    pub required_by: Vec<String>,
    pub from: Option<String>,
}

/// What a project's dependencies resolved to: every package by namespace, and what's wrong.
pub struct Resolution<'a> {
    pub packages: BTreeMap<String, ResolvedPackage<'a>>,
    /// In the order they were resolved.
    pub order: Vec<String>,
    pub problems: Vec<Problem>,
}

impl Resolution<'_> {
    /// The lock this resolution makes, when every package has a version and a hash and none isn't what the lock pinned.
    pub fn lock(&self) -> Option<LockFile> {
        if self
            .problems
            .iter()
            .any(|p| p.code.as_deref() == Some(codes::PACKAGE_HASH.code))
        {
            return None;
        }
        let mut packages = Vec::new();
        for name in &self.order {
            let p = &self.packages[name];
            packages.push((
                crate::canonical::Key(name.clone()),
                LockedPackage {
                    version: p.manifest.as_ref()?.version.clone(),
                    source: p.origin.clone(),
                    hash: p.hash.clone()?,
                },
            ));
        }
        Some(LockFile {
            schema: Some(LOCK_SCHEMA.into()),
            packages: crate::canonical::Entries(packages),
        })
    }
}

pub const GIT_LOCATION_PREFIX: &str = "git:";

static COMMIT: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^(?:[0-9a-f]{40}|[0-9a-f]{64})$").unwrap());
static SCP_LIKE: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^[A-Za-z0-9._-]+@[A-Za-z0-9.-]+:[^/].*$").unwrap());
static REV: LazyLock<Regex> =
    LazyLock::new(|| Regex::new("^[A-Za-z0-9_][A-Za-z0-9_./+-]*$").unwrap());

/// Whether `text` is a full commit id: 40 lowercase hex digits, or 64.
pub fn is_commit(text: &str) -> bool {
    COMMIT.is_match(text)
}

/// Whether `url` is a repository NetherForge fetches from: `https://`, `ssh://`,
/// `file://`, or scp-like `user@host:path`; never `http://`, `git://`, a
/// transport that runs commands, or anything git could read as an option.
pub fn is_git_url(url: &str) -> bool {
    if url
        .chars()
        .any(|c| c.is_whitespace() || (c as u32) < 0x20 || c as u32 == 0x7f)
        || url.starts_with('-')
    {
        return false;
    }
    match ["https://", "ssh://", "file://"]
        .into_iter()
        .find(|s| url.starts_with(s))
    {
        Some(scheme) => url.len() > scheme.len(),
        None => SCP_LIKE.is_match(url),
    }
}

/// Whether `rev` can be a git dependency's `rev`: a branch or tag name, or a full commit.
pub fn is_git_rev(rev: &str) -> bool {
    REV.is_match(rev)
        && !rev.contains("..")
        && !rev.contains("//")
        && !rev.ends_with('/')
        && !rev.ends_with('.')
        && !rev.ends_with(".lock")
        && !rev.split('/').any(|p| p.starts_with('.'))
}

/// Whether `path` can be a dependency's `path`: relative, `/` between its parts, none empty.
pub fn is_path(path: &str) -> bool {
    !path.is_empty()
        && !path.starts_with('/')
        && path
            .split('/')
            .all(|p| !p.is_empty() && (p == "." || p == ".." || names::is_segment(p)))
}

/// `path` (relative to the folder at `base`) as a folder relative to the root project's.
pub fn join(base: &str, path: &str) -> String {
    let mut parts: Vec<&str> = Vec::new();
    for part in base
        .split('/')
        .filter(|p| !p.is_empty())
        .chain(path.split('/'))
    {
        match part {
            "." | "" => {}
            ".." if parts.last().is_some_and(|l| *l != "..") => {
                parts.pop();
            }
            _ => parts.push(part),
        }
    }
    parts.join("/")
}

/// The `netherforge.json` of `namespace` as `home`'s problems name it.
fn manifest_path(namespace: &str, home: &str) -> String {
    if namespace == home {
        manifest::FILE_NAME.into()
    } else {
        crate::kinds::package_path(namespace, manifest::FILE_NAME)
    }
}

fn parse_manifest(text: &str) -> Option<Manifest> {
    crate::read::parse::<Manifest>(text, manifest::FILE_NAME).ok()
}

/// The lock in `text`, for its pins: `None` when there's none or it doesn't read.
pub fn parse_lock(text: Option<&str>) -> Option<LockFile> {
    crate::read::parse::<LockFile>(text?, LOCK_FILE).ok()
}

fn describe_request(request: &PackageRequest) -> String {
    match request {
        PackageRequest::Folder(location) => location.clone(),
        PackageRequest::Git { url, rev, commit } => match commit.as_ref().or(rev.as_ref()) {
            Some(at) => format!("{url} at {at}"),
            None => url.clone(),
        },
    }
}

fn describe_origin(origin: &PackageSource) -> String {
    match origin {
        PackageSource::Path { path } => path.clone(),
        PackageSource::Git { url, rev, commit } => {
            let at = if commit.is_empty() {
                rev.as_deref().unwrap_or("its default branch")
            } else {
                commit
            };
            format!("{url} at {at}")
        }
        PackageSource::Registry { url } => url.clone(),
    }
}

fn same_request(origin: &PackageSource, request: &PackageRequest) -> bool {
    match (request, origin) {
        (PackageRequest::Folder(location), PackageSource::Path { path }) => location == path,
        (PackageRequest::Git { url, rev, .. }, PackageSource::Git { url: u, rev: r, .. }) => {
            url == u && rev == r
        }
        _ => false,
    }
}

/// Why the package `name` at `version` doesn't do for `requirer`'s dependency on it.
fn version_problem(
    requirer: &str,
    name: &str,
    dependency: &Dependency,
    version: Option<&str>,
    from: Option<&str>,
) -> Option<String> {
    let range = VersionRange::parse(dependency.version.as_deref()?)?;
    let version = version?;
    if range.admits(version) {
        return None;
    }
    let from = from.map(|f| format!(" ({f})")).unwrap_or_default();
    Some(format!(
        "\"{requirer}\" takes \"{name}\" {range}, and the package it resolved to{from} is {version}, so it isn't loaded"
    ))
}

/// What `dependency` (declared in `file` at `at`, by a package whose files are
/// at `base`) asks a host for, or `None` with the problem reported.
fn request(
    dependency: &Dependency,
    base: &str,
    pin: Option<&LockedPackage>,
    file: &str,
    at: &str,
    problems: &mut Vec<Problem>,
) -> Option<PackageRequest> {
    let (path, git) = (&dependency.path, &dependency.git);
    if path.is_none() == git.is_none() || (path.is_some() && dependency.rev.is_some()) {
        let why = if path.is_none() && git.is_none() {
            "This dependency says nowhere to find it: give it a \"path\" or a \"git\" repository"
        } else if git.is_some() && path.is_some() {
            "This dependency has both a \"path\" and a \"git\" repository: give it one"
        } else {
            "\"rev\" goes with a \"git\" repository, not a \"path\""
        };
        problems.push(codes::PACKAGE_SOURCE.at(file, why, Some(at.to_owned())));
        return None;
    }
    if let Some(version) = &dependency.version
        && VersionRange::parse(version).is_none()
    {
        problems.push(codes::PACKAGE_VERSION_RANGE.at(
            file,
            format!("\"{version}\" isn't {}", VersionRange::RULE),
            Some(format!("{at}.version")),
        ));
        return None;
    }
    if let Some(path) = path {
        if !is_path(path) {
            problems.push(codes::PACKAGE_PATH.at(
                file,
                format!("\"{path}\" isn't a folder relative to this project's, with / between its parts (like ../economy)"),
                Some(format!("{at}.path")),
            ));
            return None;
        }
        if base.starts_with(GIT_LOCATION_PREFIX) {
            problems.push(codes::PACKAGE_GIT_PATH.at(
                file,
                format!("A package fetched from git can't depend on the folder \"{path}\": it isn't there for whoever fetches the package. Depend on it by git too"),
                Some(format!("{at}.path")),
            ));
            return None;
        }
        return Some(PackageRequest::Folder(join(base, path)));
    }
    let url = git.as_ref().expect("one of them");
    if !is_git_url(url) {
        problems.push(codes::PACKAGE_GIT_URL.at(
            file,
            format!("\"{url}\" isn't a repository NetherForge fetches from: https://…, ssh://…, user@host:path or file://…"),
            Some(format!("{at}.git")),
        ));
        return None;
    }
    let rev = dependency.rev.clone();
    if let Some(rev) = &rev
        && !is_git_rev(rev)
    {
        problems.push(codes::PACKAGE_GIT_REV.at(
            file,
            format!("\"{rev}\" isn't the name of a branch, a tag or a full commit"),
            Some(format!("{at}.rev")),
        ));
        return None;
    }
    let locked = match pin.map(|p| &p.source) {
        Some(PackageSource::Git {
            url: u,
            rev: r,
            commit,
        }) if u == url && *r == rev && is_commit(commit) => Some(commit.clone()),
        _ => None,
    };
    Some(PackageRequest::Git {
        url: url.clone(),
        rev,
        commit: locked,
    })
}

/// Every package `root` (the project's manifest) depends on, its dependencies'
/// dependencies included, from `sources`. A git dependency is asked for at the
/// commit `lock` pins while it says the same `git` and `rev`, and must then
/// hash to what the lock says. Problems are where each dependency is declared.
pub fn resolve<'s>(
    root: &Manifest,
    sources: &'s dyn PackageSources,
    lock: Option<&LockFile>,
) -> Resolution<'s> {
    let mut problems = Vec::new();
    let mut resolved: BTreeMap<String, ResolvedPackage<'s>> = BTreeMap::new();
    let mut order: Vec<String> = Vec::new();
    // Packages a requirer's range refused: refused for everyone, so what loads doesn't hang on which was read first.
    let mut out_of_range: HashMap<String, (String, Option<String>)> = HashMap::new();
    let home = root.namespace.clone();
    let mut queue: VecDeque<(String, String, Manifest)> =
        VecDeque::from([(home.clone(), String::new(), root.clone())]);
    while let Some((requirer, base, manifest)) = queue.pop_front() {
        let file = manifest_path(&requirer, &home);
        let mut dependencies: Vec<_> = manifest
            .dependencies
            .iter()
            .flat_map(|d| d.iter())
            .collect();
        dependencies.sort_by(|a, b| a.0.0.cmp(&b.0.0));
        for (name, dependency) in dependencies {
            let name = name.0.as_str();
            let at = child_path("$.dependencies", name);
            let bad = if name == requirer {
                Some("is this project's own namespace".to_owned())
            } else if !names::is_namespace(name) {
                Some(format!("isn't a namespace ({})", names::NAMESPACE_RULE))
            } else if names::RESERVED_NAMESPACES.contains(&name) {
                Some(
                    "is reserved (for the game, the server or NetherForge), so no package has it"
                        .to_owned(),
                )
            } else {
                None
            };
            if let Some(bad) = bad {
                problems.push(codes::PACKAGE_NAME.at(&file, format!("\"{name}\" {bad}"), Some(at)));
                continue;
            }
            let pin = lock.and_then(|l| l.packages.get_str(name));
            let Some(request) = request(dependency, &base, pin, &file, &at, &mut problems) else {
                continue;
            };
            if name == home {
                // A package depending on the project being loaded: it's that project (a loop), or another of its namespace.
                problems.push(codes::PACKAGE_CYCLE.at(
                    &file,
                    format!("\"{requirer}\" depends on \"{home}\", the project that uses it: a package can't depend on what depends on it"),
                    Some(at),
                ));
                continue;
            }
            if let Some((version, from)) = out_of_range.get(name) {
                if let Some(why) =
                    version_problem(&requirer, name, dependency, Some(version), from.as_deref())
                {
                    problems.push(codes::PACKAGE_VERSION.at(
                        &file,
                        why,
                        Some(format!("{at}.version")),
                    ));
                }
                continue;
            }
            if let Some(existing) = resolved.get_mut(name) {
                if same_request(&existing.origin, &request) {
                    existing.required_by.push(requirer.clone());
                    let version = existing.manifest.as_ref().map(|m| m.version.as_str());
                    if let Some(why) = version_problem(
                        &requirer,
                        name,
                        dependency,
                        version,
                        existing.from.as_deref(),
                    ) {
                        problems.push(codes::PACKAGE_VERSION.at(
                            &file,
                            why,
                            Some(format!("{at}.version")),
                        ));
                    }
                } else {
                    let them: Vec<String> = existing
                        .required_by
                        .iter()
                        .map(|r| format!("\"{r}\""))
                        .collect();
                    let theirs = match &request {
                        PackageRequest::Folder(_) => match sources.open(name, &request) {
                            PackageLookup::Opened(o) => format!(
                                "{}, ",
                                o.source
                                    .read(manifest::FILE_NAME)
                                    .and_then(|t| parse_manifest(&t))
                                    .map_or_else(|| "unreadable".to_owned(), |m| m.version)
                            ),
                            PackageLookup::Missing(_) => "unreadable, ".into(),
                        },
                        PackageRequest::Git { .. } => String::new(),
                    };
                    problems.push(
                        codes::PACKAGE_CONFLICT
                            .at(
                                &file,
                                format!(
                                    "Two packages are called \"{name}\": {} ({theirs}which \"{requirer}\" depends on) and {} ({}, which {} depends on). A server runs one package of each namespace.",
                                    describe_request(&request),
                                    describe_origin(&existing.origin),
                                    existing.manifest.as_ref().map_or("unreadable", |m| m.version.as_str()),
                                    them.join(" and ")
                                ),
                                Some(at.clone()),
                            )
                            .with_related(
                                existing
                                    .required_by
                                    .iter()
                                    .map(|r| Location {
                                        file: manifest_path(r, &home),
                                        path: Some(at.clone()),
                                    })
                                    .collect(),
                            ),
                    );
                }
                continue;
            }
            let opened = match sources.open(name, &request) {
                PackageLookup::Opened(opened) => opened,
                PackageLookup::Missing(reason) => {
                    problems.push(match &request {
                        PackageRequest::Folder(location) => codes::PACKAGE_MISSING.at(
                            &file,
                            reason.unwrap_or_else(|| {
                                format!(
                                    "There's no project at {location} (no {} there)",
                                    manifest::FILE_NAME
                                )
                            }),
                            Some(at),
                        ),
                        PackageRequest::Git { .. } => codes::PACKAGE_GIT.at(
                            &file,
                            format!(
                                "Couldn't fetch {}: {}",
                                describe_request(&request),
                                reason.as_deref().unwrap_or("nothing answered for it")
                            ),
                            Some(at),
                        ),
                    });
                    continue;
                }
            };
            let (location, origin) = match &request {
                PackageRequest::Folder(location) => (
                    location.clone(),
                    PackageSource::Path {
                        path: location.clone(),
                    },
                ),
                PackageRequest::Git { url, rev, commit } => {
                    // A bundle answers by namespace and may not know the commit; it never locks.
                    let commit = opened
                        .commit
                        .clone()
                        .or_else(|| commit.clone())
                        .unwrap_or_default();
                    (
                        format!("{GIT_LOCATION_PREFIX}{commit}"),
                        PackageSource::Git {
                            url: url.clone(),
                            rev: rev.clone(),
                            commit,
                        },
                    )
                }
            };
            let pinned = pin.map(|p| p.hash.as_str());
            if let (
                PackageRequest::Git {
                    commit: Some(_), ..
                },
                Some(pinned),
                Some(hash),
            ) = (&request, pinned, &opened.hash)
                && hash != pinned
            {
                problems.push(codes::PACKAGE_HASH.at(
                    &file,
                    format!(
                        "{} hashes to {hash}, not the {pinned} {LOCK_FILE} pins, so it isn't loaded: either its copy in the cache was changed (delete it, and it's fetched again) or the lock was (netherforge lock --update resolves it afresh)",
                        describe_origin(&origin)
                    ),
                    Some(at),
                ));
                continue;
            }
            let found = opened
                .source
                .read(manifest::FILE_NAME)
                .and_then(|t| parse_manifest(&t));
            if let Some(found) = &found
                && found.namespace != name
            {
                problems.push(codes::PACKAGE_NAMESPACE.at(
                    &file,
                    format!(
                        "The project at {} is \"{}\", not \"{name}\": a dependency is named by the namespace of what it points at",
                        describe_origin(&origin),
                        found.namespace
                    ),
                    Some(at),
                ));
                continue;
            }
            if let Some(found) = &found
                && let Some(why) = version_problem(
                    &requirer,
                    name,
                    dependency,
                    Some(&found.version),
                    opened.from.as_deref(),
                )
            {
                problems.push(codes::PACKAGE_VERSION.at(&file, why, Some(format!("{at}.version"))));
                out_of_range.insert(
                    name.to_owned(),
                    (found.version.clone(), opened.from.clone()),
                );
                continue;
            }
            if let Some(found) = &found {
                queue.push_back((name.to_owned(), location.clone(), found.clone()));
            }
            order.push(name.to_owned());
            resolved.insert(
                name.to_owned(),
                ResolvedPackage {
                    namespace: name.to_owned(),
                    location,
                    origin,
                    manifest: found,
                    source: opened.source,
                    hash: opened.hash,
                    required_by: vec![requirer.clone()],
                    from: opened.from,
                },
            );
        }
    }
    problems.extend(cycles(&home, &resolved, &order));
    Resolution {
        packages: resolved,
        order,
        problems,
    }
}

/// A loop among packages (the project's own loop is found as it's resolved): reported once, where it closes.
fn cycles(
    home: &str,
    resolved: &BTreeMap<String, ResolvedPackage>,
    order: &[String],
) -> Vec<Problem> {
    fn visit(
        name: &str,
        path: &mut Vec<String>,
        home: &str,
        resolved: &BTreeMap<String, ResolvedPackage>,
        done: &mut HashSet<String>,
        out: &mut Vec<Problem>,
    ) {
        if done.contains(name) {
            return;
        }
        let Some(manifest) = resolved.get(name).and_then(|p| p.manifest.as_ref()) else {
            return;
        };
        let mut next: Vec<&str> = manifest
            .dependencies
            .iter()
            .flat_map(|d| d.iter().map(|(k, _)| k.0.as_str()))
            .collect();
        next.sort_unstable();
        for next in next {
            if !resolved.contains_key(next) {
                continue;
            }
            if let Some(at) = path.iter().position(|p| p == next) {
                let lp: Vec<String> = path[at..]
                    .iter()
                    .map(String::as_str)
                    .chain([name, next])
                    .map(|n| format!("\"{n}\""))
                    .collect();
                let problem = codes::PACKAGE_CYCLE.at(
                    &manifest_path(name, home),
                    format!(
                        "Packages depend on each other in a loop: {}",
                        lp.join(" → ")
                    ),
                    Some(child_path("$.dependencies", next)),
                );
                if !out.contains(&problem) {
                    out.push(problem);
                }
            } else {
                path.push(name.to_owned());
                visit(next, path, home, resolved, done, out);
                path.pop();
            }
        }
        done.insert(name.to_owned());
    }
    let mut out = Vec::new();
    let mut done = HashSet::new();
    for name in order {
        visit(name, &mut Vec::new(), home, resolved, &mut done, &mut out);
    }
    out
}

/// What's wrong with the project's lock (`text`, `None` when there's none) given what resolved.
pub fn check_lock(text: Option<&str>, resolution: &Resolution, problems: &mut Vec<Problem>) {
    let Some(text) = text else {
        if !resolution.packages.is_empty() {
            problems.push(codes::LOCK_MISSING.at(
                manifest::FILE_NAME,
                format!("There's no {LOCK_FILE} pinning the packages this project depends on; the editor writes it, or run netherforge lock"),
                Some("$.dependencies".into()),
            ));
        }
        return;
    };
    let lock = match crate::read::parse::<LockFile>(text, LOCK_FILE) {
        Err(problem) => {
            problems.push(*problem);
            return;
        }
        Ok(lock) => lock,
    };
    // A git package that isn't what the lock pinned is that problem, and the lock stays as it is.
    if resolution
        .problems
        .iter()
        .any(|p| p.code.as_deref() == Some(codes::PACKAGE_HASH.code))
    {
        return;
    }
    let names: BTreeSet<&str> = lock
        .packages
        .iter()
        .map(|(k, _)| k.0.as_str())
        .chain(resolution.packages.keys().map(String::as_str))
        .collect();
    let stale: Vec<String> = names
        .into_iter()
        .filter(
            |name| match (lock.packages.get_str(name), resolution.packages.get(*name)) {
                (Some(locked), Some(now)) => {
                    now.manifest.as_ref().map(|m| &m.version) != Some(&locked.version)
                        || locked.source != now.origin
                        || now.hash.as_ref().is_some_and(|h| *h != locked.hash)
                }
                _ => true,
            },
        )
        .map(|n| format!("\"{n}\""))
        .collect();
    if !stale.is_empty() {
        problems.push(codes::LOCK_STALE.at(
            LOCK_FILE,
            format!("{LOCK_FILE} is out of date for {}; the editor rewrites it, or run netherforge lock", stale.join(", ")),
            Some("$.packages".into()),
        ));
    }
}

/// `problem`, found in package `namespace`'s own files, as the project that depends on it names them.
pub fn in_package(namespace: &str, mut problem: Problem) -> Problem {
    problem.file = crate::kinds::package_path(namespace, &problem.file);
    for related in &mut problem.related {
        if crate::kinds::split_package(&related.file).0.is_none() {
            related.file = crate::kinds::package_path(namespace, &related.file);
        }
    }
    problem
}
