//! Copying a dev-server world into the project as a map,
//! `maps/<id>/`, for "Save a dev-server world as this map".
//!
//! Since Minecraft 26.1 every world on a server is a dimension of the main
//! world's storage (`world/dimensions/minecraft/<name>/`: region files and
//! saved data), beside one `level.dat` they share. The plugin saves the world
//! and says where those two are (the bridge's `save_world`); this copies them
//! into the layout `nf.worlds.copy` imports: `level.dat` at the top and the
//! world's folder as the map's overworld, which Paper's legacy-world
//! import moves into a new world's dimension. So a captured nether or a world
//! a script made comes back as the overworld of its copies, like any map.
//! Before 26.1 (a 1.21.x project) each world is a folder of its own with its
//! `level.dat` inside: the plugin names that folder as the dimension and that
//! file as the level, so the same layout comes out, and the plugin installs
//! it back as a world folder.
//!
//! The source is scoped to the dev server's folder and the destination to the
//! project, both through [ProjectRoot::resolve]. The copy is made in a
//! staging folder under `.netherforge/` (never watched) and moved into place
//! with one rename, so the watcher never sees half a world and a failed copy
//! leaves nothing behind.

use std::path::{Path, PathBuf};

use serde::Serialize;

use super::{ProjectRoot, atomic, is_writable, scope};
use crate::error::{Context, Result, bail};

/// Where maps live in a project.
pub const MAPS: &str = "maps";
/// Where a map's world goes inside it.
pub const OVERWORLD: &str = "dimensions/minecraft/overworld";
const LEVEL: &str = "level.dat";

/// How far a copy has got, in bytes.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, specta::Type, tauri_specta::Event)]
#[serde(rename_all = "camelCase")]
#[tauri_specta(event_name = "map://capture-progress")]
pub struct CaptureProgress {
    pub done: u64,
    pub total: u64,
}

/// Files a map never carries, by their path inside the world's folder:
/// what the server writes for itself (its lock, the world's identity, Paper's
/// metadata and per-world config, backups) and what belongs to players.
pub fn is_excluded(relative: &str) -> bool {
    const FILES: [&str; 4] = [
        "session.lock",
        "uid.dat",
        "level.dat_old",
        "paper-world.yml",
    ];
    const FOLDERS: [&str; 4] = ["players", "playerdata", "stats", "advancements"];
    let parts: Vec<&str> = relative.split('/').collect();
    let name = parts.last().copied().unwrap_or_default();
    if FILES.contains(&name) {
        return true;
    }
    if parts[..parts.len() - 1]
        .iter()
        .any(|part| FOLDERS.contains(part))
    {
        return true;
    }
    // Paper's saved data: the world's UUID, its persistent data and overrides of the main level data.
    relative.starts_with("data/paper/")
}

/// Whether [segment] is a group folder: an id in parentheses, `(arenas)`
/// (format's `Names.GROUP`). Groups sort maps without changing their ids.
fn is_group(segment: &str) -> bool {
    segment
        .strip_prefix('(')
        .and_then(|it| it.strip_suffix(')'))
        .is_some_and(|name| {
            let bytes = name.as_bytes();
            !bytes.is_empty()
                && bytes.len() <= 64
                && (bytes[0].is_ascii_lowercase() || bytes[0].is_ascii_digit())
                && bytes
                    .iter()
                    .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || *b == b'_')
        })
}

/// Copies the server's world ([level], its storage's `level.dat`, and
/// [dimension], its folder; both relative to [server]) into `maps/<id>/`,
/// or into group folders when [id] starts with them (`(arenas)/duel`).
/// An existing map is replaced only with [replace].
pub fn capture(
    project: &ProjectRoot,
    server: &ProjectRoot,
    level: &str,
    dimension: &str,
    id: &str,
    replace: bool,
    progress: &mut dyn FnMut(CaptureProgress),
) -> Result<()> {
    let segments = scope::segments(id)?;
    let (name, groups) = segments.split_last().unwrap_or((&"", &[]));
    if name.is_empty() || name.starts_with('.') || !groups.iter().all(|it| is_group(it)) {
        bail!(Invalid, "\"{id}\" can't name a map");
    }
    let destination_path = format!("{MAPS}/{id}");
    if !is_writable(&destination_path) {
        bail!(
            ReadOnly,
            "{destination_path} can't be changed from the editor"
        );
    }
    let destination = project.resolve(&destination_path)?;
    let exists = std::fs::symlink_metadata(&destination).is_ok();
    if exists && !replace {
        bail!(AlreadyExists, "{destination_path} already exists");
    }

    let level_file = server.resolve(level)?;
    if level_file.file_name().and_then(|it| it.to_str()) != Some(LEVEL) || !level_file.is_file() {
        bail!(
            NotFound,
            "{level} isn't a world's {LEVEL} in the server's folder"
        );
    }
    let world = server.resolve(dimension)?;
    if !world.is_dir() {
        bail!(
            NotFound,
            "{dimension} isn't a world folder in the server's folder"
        );
    }

    let files = world_files(&world)?;
    let total = std::fs::metadata(&level_file)?.len() + files.iter().map(|it| it.2).sum::<u64>();

    let staging = staging_dir(project, "capture")?;
    let result = (|| -> Result<()> {
        let mut done = 0;
        let mut reported = 0;
        let mut copy = |from: &Path, to: &Path, size: u64| -> Result<()> {
            if let Some(parent) = to.parent() {
                std::fs::create_dir_all(parent)
                    .context(|| format!("Couldn't create {}", parent.display()))?;
            }
            std::fs::copy(from, to).context(|| format!("Couldn't copy {}", from.display()))?;
            done += size;
            // About a hundred updates, whatever the size.
            if done == total || done - reported >= total / 100 {
                reported = done;
                progress(CaptureProgress { done, total });
            }
            Ok(())
        };
        copy(
            &level_file,
            &staging.join(LEVEL),
            std::fs::metadata(&level_file)?.len(),
        )?;
        let overworld = OVERWORLD
            .split('/')
            .fold(staging.clone(), |path, part| path.join(part));
        std::fs::create_dir_all(&overworld)?;
        for (from, relative, size) in &files {
            let to = relative
                .split('/')
                .fold(overworld.clone(), |path, part| path.join(part));
            copy(from, &to, *size)?;
        }
        place(project, &staging, &destination)
    })();
    if result.is_err() {
        let _ = std::fs::remove_dir_all(&staging);
    }
    result
}

/// Every file in the world's folder a map keeps: (absolute, relative with `/`, size).
fn world_files(world: &Path) -> Result<Vec<(PathBuf, String, u64)>> {
    let mut files = Vec::new();
    for entry in walkdir::WalkDir::new(world)
        .follow_links(false)
        .min_depth(1)
    {
        let entry = entry?;
        // Links aren't followed: one could point anywhere on the disk.
        if !entry.file_type().is_file() {
            continue;
        }
        let Some(relative) = scope::relative_project_path(world, entry.path()) else {
            continue;
        };
        if is_excluded(&relative) {
            continue;
        }
        let size = entry.metadata()?.len();
        files.push((entry.path().to_path_buf(), relative, size));
    }
    files.sort_by(|a, b| a.1.cmp(&b.1));
    Ok(files)
}

/// A new, empty folder under `.netherforge/` (inside the project, so a rename moves it).
fn staging_dir(project: &ProjectRoot, what: &str) -> Result<PathBuf> {
    let dir = project.path().join(".netherforge").join(format!(
        "{what}{}{}",
        atomic::TEMP_MARKER,
        atomic::random_suffix()
    ));
    std::fs::create_dir_all(&dir).context(|| format!("Couldn't create {}", dir.display()))?;
    Ok(dir)
}

/// Moves the finished copy to [destination], moving an existing one out of the way first.
fn place(project: &ProjectRoot, staging: &Path, destination: &Path) -> Result<()> {
    if let Some(parent) = destination.parent() {
        std::fs::create_dir_all(parent)
            .context(|| format!("Couldn't create {}", parent.display()))?;
    }
    if std::fs::symlink_metadata(destination).is_err() {
        return atomic::rename_replacing(staging, destination)
            .context(|| format!("Couldn't move the copy to {}", destination.display()));
    }
    let old = staging_dir(project, "replaced")?;
    let aside = old.join("world");
    atomic::rename_replacing(destination, &aside)
        .context(|| format!("Couldn't move {} aside", destination.display()))?;
    if let Err(error) = atomic::rename_replacing(staging, destination) {
        // Put the old one back rather than leave nothing.
        let _ = atomic::rename_replacing(&aside, destination);
        let _ = std::fs::remove_dir_all(&old);
        return Err(error)
            .context(|| format!("Couldn't move the copy to {}", destination.display()));
    }
    let _ = std::fs::remove_dir_all(&old);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Setup {
        _tmp: tempfile::TempDir,
        project: ProjectRoot,
        server: ProjectRoot,
    }

    fn write(path: &Path, text: &str) {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, text).unwrap();
    }

    /// A project whose dev server has a 26.x world storage with the main world and one more.
    fn setup() -> Setup {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path().join("project");
        write(&root.join("netherforge.json"), "{}");
        let server = tmp.path().join("data/servers/0123456789abcdef");
        let storage = server.join("world");
        write(&storage.join("level.dat"), "level");
        write(&storage.join("level.dat_old"), "backup");
        write(&storage.join("session.lock"), "lock");
        write(&storage.join("players/data/abc.dat"), "player");
        write(&storage.join("data/minecraft/scoreboard.dat"), "scores");
        for world in ["overworld", "arena"] {
            let dim = storage.join("dimensions/minecraft").join(world);
            write(&dim.join("region/r.0.0.mca"), &format!("{world} blocks"));
            write(&dim.join("entities/r.0.0.mca"), "entities");
            write(&dim.join("poi/r.0.0.mca"), "poi");
            write(&dim.join("data/minecraft/game_rules.dat"), "rules");
            write(&dim.join("data/minecraft/world_gen_settings.dat"), "seed");
            write(&dim.join("data/paper/metadata.dat"), "uuid");
            write(&dim.join("data/paper/level_overrides.dat"), "spawn");
            write(&dim.join("paper-world.yml"), "config");
            write(&dim.join("uid.dat"), "legacy uuid");
            write(&dim.join("stats/abc.json"), "{}");
        }
        Setup {
            project: ProjectRoot::new(&root).unwrap(),
            server: ProjectRoot::new(&server).unwrap(),
            _tmp: tmp,
        }
    }

    fn listed(root: &Path) -> Vec<String> {
        let mut files: Vec<String> = walkdir::WalkDir::new(root)
            .into_iter()
            .filter_map(|it| it.ok())
            .filter(|it| it.file_type().is_file())
            .map(|it| scope::relative_project_path(root, it.path()).unwrap())
            .collect();
        files.sort();
        files
    }

    #[test]
    fn copies_the_world_as_the_overworld_without_what_the_server_keeps_for_itself() {
        let s = setup();
        let mut updates = Vec::new();
        capture(
            &s.project,
            &s.server,
            "world/level.dat",
            "world/dimensions/minecraft/arena",
            "arena",
            false,
            &mut |p| updates.push(p),
        )
        .unwrap();
        let map = s.project.path().join("maps/arena");
        assert_eq!(
            listed(&map),
            [
                "dimensions/minecraft/overworld/data/minecraft/game_rules.dat",
                "dimensions/minecraft/overworld/data/minecraft/world_gen_settings.dat",
                "dimensions/minecraft/overworld/entities/r.0.0.mca",
                "dimensions/minecraft/overworld/poi/r.0.0.mca",
                "dimensions/minecraft/overworld/region/r.0.0.mca",
                "level.dat",
            ]
        );
        assert_eq!(
            std::fs::read_to_string(map.join(OVERWORLD).join("region/r.0.0.mca")).unwrap(),
            "arena blocks"
        );
        let last = updates.last().unwrap();
        assert_eq!(last.done, last.total);
        assert!(updates.windows(2).all(|it| it[0].done < it[1].done));
        // Nothing left over: no staging folders.
        let leftovers: Vec<_> = std::fs::read_dir(s.project.path().join(".netherforge"))
            .unwrap()
            .filter_map(|it| it.ok())
            .map(|it| it.file_name().to_string_lossy().to_string())
            .filter(|it| it.contains(atomic::TEMP_MARKER))
            .collect();
        assert!(leftovers.is_empty(), "{leftovers:?}");
    }

    #[test]
    fn replaces_an_existing_map_only_when_asked() {
        let s = setup();
        let old = s.project.path().join("maps/main/old.txt");
        write(&old, "old");
        let run = |replace: bool| {
            capture(
                &s.project,
                &s.server,
                "world/level.dat",
                "world/dimensions/minecraft/overworld",
                "main",
                replace,
                &mut |_| {},
            )
        };
        assert_eq!(
            run(false).unwrap_err().message(),
            "maps/main already exists"
        );
        assert!(old.exists());
        run(true).unwrap();
        assert!(!old.exists());
        assert_eq!(
            std::fs::read_to_string(
                s.project
                    .path()
                    .join("maps/main")
                    .join(OVERWORLD)
                    .join("region/r.0.0.mca")
            )
            .unwrap(),
            "overworld blocks"
        );
    }

    #[test]
    fn reads_only_from_the_server_folder_and_writes_only_a_map() {
        let s = setup();
        let outside = s._tmp.path().join("elsewhere");
        write(&outside.join("level.dat"), "x");
        let run = |level: &str, dimension: &str, id: &str| {
            capture(
                &s.project,
                &s.server,
                level,
                dimension,
                id,
                false,
                &mut |_| {},
            )
            .unwrap_err()
            .message()
            .to_string()
        };
        let world = "world/dimensions/minecraft/arena";
        assert!(
            run("../../../elsewhere/level.dat", world, "a").contains("leaves the project folder")
        );
        assert!(run("/etc/level.dat", world, "a").contains("absolute"));
        assert!(run("world/session.lock", world, "a").contains("isn't a world's level.dat"));
        assert!(
            run("world/level.dat", "world/dimensions/minecraft/nope", "a")
                .contains("isn't a world folder")
        );
        assert!(
            run("world/level.dat", "../../netherforge.json", "a")
                .contains("leaves the project folder")
        );
        for id in ["", "..", "a/b", ".hidden"] {
            assert!(!run("world/level.dat", world, id).is_empty(), "{id:?}");
        }
        assert!(!s.project.path().join("maps").exists());

        // A link inside the server folder that points out of it is refused too.
        #[cfg(unix)]
        {
            std::os::unix::fs::symlink(&outside, s.server.path().join("linked")).unwrap();
            assert!(run("linked/level.dat", world, "a").contains("leads outside"));
        }
    }

    #[test]
    fn excludes_per_server_files_wherever_they_are() {
        for path in [
            "session.lock",
            "uid.dat",
            "level.dat_old",
            "paper-world.yml",
            "playerdata/abc.dat",
            "players/data/abc.dat",
            "stats/abc.json",
            "advancements/abc.json",
            "data/paper/metadata.dat",
        ] {
            assert!(is_excluded(path), "{path}");
        }
        for path in [
            "region/r.0.0.mca",
            "data/minecraft/game_rules.dat",
            "entities/r.0.0.mca",
            "stats.txt",
        ] {
            assert!(!is_excluded(path), "{path}");
        }
    }
}
