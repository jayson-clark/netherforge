//! Format's package goldens (`packages/format/testdata/packages/<case>/`): a
//! case's `app` loaded with the packages beside it, its dependencies' paths
//! folders of the case, its git dependencies the case's `repos.json` (a URL's
//! refs and the folder of each commit). Each package is hashed as a host
//! hashes it (`package::hash`), so the lock's checks run.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use netherforge_format::package::{
    self, OpenedPackage, PackageLookup, PackageRequest, PackageSources,
};
use netherforge_format::project::{self, Folder, ProjectSource};
use serde_json::Value;
use sha2::{Digest, Sha256};

fn cases() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../packages/format/testdata/packages")
}

fn hex(bytes: &[u8]) -> String {
    Sha256::digest(bytes)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

#[derive(serde::Deserialize, Default)]
struct Repo {
    #[serde(default)]
    refs: BTreeMap<String, String>,
    #[serde(default)]
    commits: BTreeMap<String, String>,
}

struct TestPackages {
    base: PathBuf,
    root: String,
    repos: BTreeMap<String, Repo>,
}

impl TestPackages {
    fn folder(&self, dir: &Path, commit: Option<String>) -> PackageLookup<'_> {
        let Some(folder) = Folder::open(dir).filter(|f| f.files().contains("netherforge.json"))
        else {
            return PackageLookup::Missing(None);
        };
        let digests: Vec<(String, String)> = folder
            .files()
            .iter()
            .map(|f| (f.clone(), hex(&std::fs::read(dir.join(f)).unwrap())))
            .collect();
        let listing = package::hash::listing(digests.iter().map(|(p, d)| (p.as_str(), d.as_str())));
        PackageLookup::Opened(OpenedPackage {
            source: Box::new(folder),
            hash: Some(package::hash::of(&hex(listing.as_bytes()))),
            commit,
            from: None,
        })
    }
}

impl PackageSources for TestPackages {
    fn open(&self, _: &str, request: &PackageRequest) -> PackageLookup<'_> {
        match request {
            PackageRequest::Folder(location) => {
                self.folder(&self.base.join(package::join(&self.root, location)), None)
            }
            PackageRequest::Git { url, rev, commit } => {
                let Some(repo) = self.repos.get(url) else {
                    return PackageLookup::Missing(Some(format!("there's no repository at {url}")));
                };
                let wanted = commit
                    .clone()
                    .or_else(|| repo.refs.get(rev.as_deref().unwrap_or("HEAD")).cloned());
                match wanted.as_ref().and_then(|c| repo.commits.get(c)) {
                    Some(folder) => self.folder(&self.base.join(folder), wanted.clone()),
                    None => PackageLookup::Missing(Some(format!(
                        "{url} has no {}",
                        commit.as_deref().or(rev.as_deref()).unwrap_or("HEAD")
                    ))),
                }
            }
        }
    }
}

/// What a problem says is wrong and where, as the invalid cases compare them.
fn what_and_where(p: &Value) -> String {
    let keep = [
        "severity", "file", "path", "line", "column", "code", "related",
    ];
    let o: serde_json::Map<String, Value> = p
        .as_object()
        .unwrap()
        .iter()
        .filter(|(k, _)| keep.contains(&k.as_str()))
        .map(|(k, v)| (k.clone(), v.clone()))
        .collect();
    Value::Object(o).to_string()
}

#[test]
fn every_package_case_is_formats() {
    let mut failures = Vec::new();
    let mut cases_run = 0;
    let mut compared = 0;
    for entry in std::fs::read_dir(cases()).unwrap() {
        let base = entry.unwrap().path();
        let Ok(expected) = std::fs::read_to_string(base.join("expected.json")) else {
            continue;
        };
        let expected: Vec<Value> = serde_json::from_str(&expected).unwrap();
        let repos = std::fs::read_to_string(base.join("repos.json"))
            .map(|t| serde_json::from_str(&t).unwrap())
            .unwrap_or_default();
        let packages = TestPackages {
            base: base.clone(),
            root: "app".into(),
            repos,
        };
        let app = Folder::open(base.join("app")).unwrap();
        let loaded = project::load(
            &app,
            project::Options {
                packages: &packages,
                ..Default::default()
            },
        );
        let mut actual: Vec<String> = loaded
            .problems
            .iter()
            .map(|p| what_and_where(&p.to_json()))
            .collect();
        let mut wanted: Vec<String> = expected.iter().map(what_and_where).collect();
        actual.sort();
        wanted.sort();
        cases_run += 1;
        compared += wanted.len();
        if actual != wanted {
            let missing: Vec<&String> = wanted.iter().filter(|w| !actual.contains(w)).collect();
            let extra: Vec<&String> = actual.iter().filter(|a| !wanted.contains(a)).collect();
            failures.push(format!(
                "{}:\n  missing: {missing:#?}\n  extra: {extra:#?}",
                base.file_name().unwrap().to_string_lossy()
            ));
        }
    }
    assert!(cases_run > 5, "{cases_run} cases");
    assert!(compared > 20, "{compared} problems compared");
    assert!(
        failures.is_empty(),
        "{} of {cases_run} cases differ:\n{}",
        failures.len(),
        failures.join("\n")
    );
}

/// What the runtime takes: every resource by the name a server knows it by, a
/// package's with its references written in full, and what a namespace may name.
#[test]
fn a_projects_packages_run_beside_it_named_in_full() {
    use netherforge_format::kind::loot::LootTableFile;
    use netherforge_format::reference::ResourceKey;
    let base = cases().join("loot");
    let packages = TestPackages {
        base: base.clone(),
        root: "app".into(),
        repos: BTreeMap::new(),
    };
    let app = Folder::open(base.join("app")).unwrap();
    let loaded = project::load(
        &app,
        project::Options {
            packages: &packages,
            ..Default::default()
        },
    );
    let running = loaded.running::<LootTableFile>();
    let names: Vec<&str> = running.iter().map(|(n, _)| n.as_str()).collect();
    // The package's tables that loop have errors, and so has the app's (it names what lib doesn't export): none runs.
    assert_eq!(names, ["lib:drops"]);
    let drops = serde_json::to_string(running[0].1).unwrap();
    assert!(
        drops.contains(r#""item":"lib:secret""#) && drops.contains(r#""table":"lib:hidden""#),
        "{drops}"
    );
    assert_eq!(
        loaded.place_of("loot_table", "lib:drops"),
        "lib:loot/drops.json"
    );
    let key = |n: &str, p: &str| ResourceKey {
        namespace: n.into(),
        path: p.into(),
    };
    let home = loaded.namespace().to_owned();
    assert!(
        loaded
            .nameable(&home, "item", &key("lib", "coin"))
            .is_none()
    );
    assert_eq!(
        loaded
            .nameable(&home, "item", &key("lib", "secret"))
            .map(|b| b.code.code),
        Some("reference.not-exported")
    );
    assert_eq!(
        loaded
            .nameable(&home, "item", &key("elsewhere", "x"))
            .map(|b| b.code.code),
        Some("reference.namespace")
    );
}
