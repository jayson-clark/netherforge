//! The packages a project depends on (`netherforge.json`'s `dependencies`),
//! read-only: their files and each one's SHA-256 (what format's content hash
//! is built from), a file's text, and copying a resource out of one into the
//! project. Which packages there are is format's to say (the UI resolves the
//! tree through it); here a package is only a folder that holds a
//! `netherforge.json`, named by its location: relative to the open project
//! (`../library`), or `git:<commit>`, a git package's checkout in the package
//! cache (fetched by [super::git]).

use std::path::Path;

use serde::Serialize;
use sha2::{Digest, Sha256};

use super::{ProjectRoot, atomic, list, writable};
use crate::error::{Context, Result, bail};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct PackageFile {
    /// Relative to the package's folder, `/`-separated.
    pub path: String,
    /// Hex SHA-256 of the file's bytes.
    pub sha256: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct PackageFiles {
    /// The package folder's canonical absolute path.
    pub root: String,
    /// Every file, as `fs_list` lists a project's (no `.git`, no top-level
    /// `.netherforge/`, no temp files), sorted by path.
    pub files: Vec<PackageFile>,
}

/// The package folder at [location]: relative to [project]'s folder, `/`
/// between its parts, `..` allowed (a package is usually a sibling), never
/// absolute, a drive letter or a backslash; or `git:<commit>`, that commit's
/// checkout in the package cache [cache]. It must hold a `netherforge.json`.
pub fn open(project: &ProjectRoot, cache: &Path, location: &str) -> Result<ProjectRoot> {
    if let Some(checkout) = super::git::location_dir(cache, location) {
        let checkout = checkout?;
        if !checkout.join("netherforge.json").is_file() {
            bail!(
                NotFound,
                "{location} hasn't been fetched into the package cache"
            );
        }
        return ProjectRoot::new(&checkout);
    }
    if location.is_empty()
        || location.starts_with('/')
        || location.contains(['\\', ':', '\0'])
        || location.split('/').any(str::is_empty)
    {
        bail!(
            InvalidPath,
            "\"{location}\" isn't a package folder relative to the project (like ../economy)"
        );
    }
    let mut target = project.path().to_path_buf();
    for part in location.split('/') {
        target.push(part);
    }
    if !target.is_dir() {
        bail!(NotFound, "There's no folder at {location}");
    }
    let root = ProjectRoot::new(&target)?;
    if !root.path().join("netherforge.json").is_file() {
        bail!(
            NotFound,
            "{location} isn't a project (it has no netherforge.json)"
        );
    }
    Ok(root)
}

/// Every file of the package, with its digest.
pub fn files(package: &ProjectRoot) -> Result<PackageFiles> {
    let mut files = Vec::new();
    for entry in list(package)? {
        let bytes = std::fs::read(package.resolve(&entry.path)?)
            .context(|| format!("Couldn't read {}", entry.path))?;
        files.push(PackageFile {
            path: entry.path,
            sha256: hex::encode(Sha256::digest(&bytes)),
        });
    }
    Ok(PackageFiles {
        root: package.path().to_string_lossy().into_owned(),
        files,
    })
}

/// Copies [from] (a file or folder of [package]) into [project] at [to],
/// built beside the target under a temp name the watcher ignores and renamed
/// into place, so the project never sees half a resource. Refuses to
/// overwrite anything.
pub fn copy(package: &ProjectRoot, project: &ProjectRoot, from: &str, to: &str) -> Result<()> {
    let source = package.resolve(from)?;
    let target = writable(project, to)?;
    let meta = std::fs::metadata(&source).context(|| format!("Couldn't find {from}"))?;
    if std::fs::symlink_metadata(&target).is_ok() {
        bail!(AlreadyExists, "{to} already exists");
    }
    let parent = target
        .parent()
        .context(|| format!("{to} has no parent folder"))?;
    std::fs::create_dir_all(parent).context(|| format!("Couldn't create the folder for {to}"))?;
    let staging = atomic::sibling_temp(&target);
    let result = (|| -> Result<()> {
        if meta.is_dir() {
            copy_folder(package, &source, &staging)?;
        } else {
            std::fs::copy(&source, &staging).context(|| format!("Couldn't copy {from}"))?;
        }
        atomic::rename_replacing(&staging, &target).context(|| format!("Couldn't create {to}"))
    })();
    if result.is_err() {
        let _ = std::fs::remove_dir_all(&staging);
        let _ = std::fs::remove_file(&staging);
    }
    result
}

/// [source]'s files (as [list] sees them, so nothing hidden and nothing a
/// link leads out to) copied under [target].
fn copy_folder(package: &ProjectRoot, source: &Path, target: &Path) -> Result<()> {
    std::fs::create_dir_all(target).context(|| format!("Couldn't create {}", target.display()))?;
    let prefix = package
        .to_project_path(source)
        .context(|| "Couldn't copy the package's folder".to_string())?;
    for entry in list(package)? {
        let Some(rest) = entry.path.strip_prefix(&format!("{prefix}/")) else {
            continue;
        };
        let to = rest
            .split('/')
            .fold(target.to_path_buf(), |path, part| path.join(part));
        if let Some(parent) = to.parent() {
            std::fs::create_dir_all(parent)
                .context(|| format!("Couldn't create {}", parent.display()))?;
        }
        std::fs::copy(package.resolve(&entry.path)?, &to)
            .context(|| format!("Couldn't copy {}", entry.path))?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fs::read_text;

    fn write(root: &Path, path: &str, text: &str) {
        let abs = root.join(path);
        std::fs::create_dir_all(abs.parent().unwrap()).unwrap();
        std::fs::write(abs, text).unwrap();
    }

    /// `<tmp>/app` (the project) and `<tmp>/lib` (a package beside it).
    fn setup() -> (tempfile::TempDir, ProjectRoot) {
        let tmp = tempfile::tempdir().unwrap();
        write(&tmp.path().join("app"), "netherforge.json", "{}");
        let lib = tmp.path().join("lib");
        write(&lib, "netherforge.json", "{\"namespace\":\"lib\"}");
        write(&lib, "items/gem/item.json", "{}");
        write(&lib, "items/gem/script.lua", "-- gem");
        write(&lib, ".git/HEAD", "ref");
        write(&lib, ".netherforge/schema/x.json", "{}");
        write(&lib, "items/gem/.script.lua.nftmp-abc", "temp");
        write(&tmp.path().join("notapackage"), "readme.txt", "hi");
        let project = ProjectRoot::new(&tmp.path().join("app")).unwrap();
        (tmp, project)
    }

    #[test]
    fn lists_a_packages_files_with_digests() {
        let (_tmp, project) = setup();
        let package = open(&project, Path::new("/no-cache"), "../lib").unwrap();
        let listed = files(&package).unwrap();
        let paths: Vec<_> = listed.files.iter().map(|f| f.path.as_str()).collect();
        assert_eq!(
            paths,
            [
                "items/gem/item.json",
                "items/gem/script.lua",
                "netherforge.json"
            ]
        );
        // sha256("{}")
        assert_eq!(
            listed.files[0].sha256,
            "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
        );
        assert!(listed.root.ends_with("lib"));
        assert_eq!(
            read_text(&package, "items/gem/script.lua").unwrap(),
            "-- gem"
        );
        assert!(read_text(&package, "../app/netherforge.json").is_err());
    }

    #[test]
    fn a_location_must_be_a_relative_project_folder() {
        let (tmp, project) = setup();
        for bad in [
            "",
            "/lib",
            "..\\lib",
            "C:/lib",
            "..//lib",
            "../missing",
            "../notapackage",
        ] {
            assert!(
                open(&project, Path::new("/no-cache"), bad).is_err(),
                "{bad}"
            );
        }
        assert!(open(&project, Path::new("/no-cache"), "./../lib").is_ok());
        let absolute = tmp.path().join("lib").to_string_lossy().into_owned();
        assert!(open(&project, Path::new("/no-cache"), &absolute).is_err());
    }

    #[test]
    fn a_git_location_is_a_checkout_in_the_cache() {
        let (tmp, project) = setup();
        let cache = tmp.path().join("cache");
        let commit = "a".repeat(40);
        let checkout = crate::fs::git::checkout_dir(&cache, &commit);
        write(&checkout, "netherforge.json", "{}");
        write(&checkout, "items/gem/item.json", "{}");
        let package = open(&project, &cache, &format!("git:{commit}")).unwrap();
        assert_eq!(read_text(&package, "items/gem/item.json").unwrap(), "{}");
        let unfetched = open(&project, &cache, &format!("git:{}", "b".repeat(40))).unwrap_err();
        assert_eq!(unfetched.code(), crate::error::ErrorCode::NotFound);
        for bad in ["git:abc", "git:../lib", "git:"] {
            let error = open(&project, &cache, bad).unwrap_err();
            assert_eq!(error.code(), crate::error::ErrorCode::InvalidPath, "{bad}");
        }
    }

    #[test]
    fn copies_a_resource_into_the_project_without_overwriting() {
        let (_tmp, project) = setup();
        let package = open(&project, Path::new("/no-cache"), "../lib").unwrap();
        copy(&package, &project, "items/gem", "items/shiny").unwrap();
        assert_eq!(
            read_text(&project, "items/shiny/script.lua").unwrap(),
            "-- gem"
        );
        let copied: Vec<_> = list(&project)
            .unwrap()
            .into_iter()
            .map(|e| e.path)
            .collect();
        assert_eq!(
            copied,
            [
                "items/shiny/item.json",
                "items/shiny/script.lua",
                "netherforge.json"
            ]
        );
        assert!(copy(&package, &project, "items/gem", "items/shiny").is_err());
        assert!(
            copy(
                &package,
                &project,
                "items/gem/item.json",
                "netherforge.json"
            )
            .is_err()
        );
        copy(
            &package,
            &project,
            "items/gem/item.json",
            "recipes/one.json",
        )
        .unwrap();
        assert!(copy(&package, &project, "../app", "x").is_err());
        assert!(copy(&package, &project, "items/gem", "../escaped").is_err());
        assert!(copy(&package, &project, "items/gem", ".git/hooks").is_err());
        assert!(copy(&package, &project, "items/missing", "items/m").is_err());
    }

    #[cfg(unix)]
    #[test]
    fn a_link_out_of_the_package_is_left_behind() {
        let (tmp, project) = setup();
        std::fs::write(tmp.path().join("secret.txt"), "secret").unwrap();
        std::os::unix::fs::symlink(
            tmp.path().join("secret.txt"),
            tmp.path().join("lib/items/gem/secret.txt"),
        )
        .unwrap();
        let package = open(&project, Path::new("/no-cache"), "../lib").unwrap();
        assert!(read_text(&package, "items/gem/secret.txt").is_err());
        assert!(
            !files(&package)
                .unwrap()
                .files
                .iter()
                .any(|f| f.path.ends_with("secret.txt"))
        );
        copy(&package, &project, "items/gem", "items/shiny").unwrap();
        assert!(!project.path().join("items/shiny/secret.txt").exists());
    }
}
