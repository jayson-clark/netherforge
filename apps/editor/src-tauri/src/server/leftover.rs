//! A dev server left running by an earlier editor session.
//!
//! The editor stops its server when it quits, but a crash, a force-quit or a
//! `tauri dev` rebuild kills the editor without running any cleanup, and the
//! server lives on, holding the port and the world. The plugin stops such a
//! server by itself once its editor has been gone a while (`BridgeClient`'s
//! abandon timeout); this is the editor's half: on start, find that server
//! and stop it rather than failing on "port in use".
//!
//! A started server's pid is written to `<server dir>/netherforge-server.pid`.
//! A pid alone could have been reused by an unrelated process, so a leftover
//! only counts if that process's command line names this project
//! (`-Dnetherforge.project=<root>`).

use std::path::{Path, PathBuf};
use std::time::Duration;

use sysinfo::{Pid, ProcessRefreshKind, ProcessesToUpdate, System, UpdateKind};

use crate::error::{Context, Result};
use crate::fs::atomic::write_atomic;

const PID_FILE: &str = "netherforge-server.pid";

/// How long a leftover gets to shut down cleanly (it saves the world) before it's killed.
pub const GRACE: Duration = Duration::from_secs(30);

fn pid_file(server_dir: &Path) -> PathBuf {
    server_dir.join(PID_FILE)
}

/// Remembers the server just started.
pub fn record(server_dir: &Path, pid: u32) -> Result<()> {
    write_atomic(&pid_file(server_dir), pid.to_string().as_bytes())
        .context(|| "Couldn't record the dev server's process id".into())
}

/// Forgets it, once it has exited.
pub fn clear(server_dir: &Path) {
    let _ = std::fs::remove_file(pid_file(server_dir));
}

/// The project argument a server for [project_root] was started with.
pub fn project_arg(project_root: &Path) -> String {
    format!("-Dnetherforge.project={}", project_root.display())
}

/// Whether [command_line] is a dev server for [project_root].
pub fn is_server_for(command_line: &[String], project_root: &Path) -> bool {
    let wanted = project_arg(project_root);
    command_line.contains(&wanted)
}

fn command_line(system: &System, pid: Pid) -> Option<Vec<String>> {
    let process = system.process(pid)?;
    Some(
        process
            .cmd()
            .iter()
            .map(|arg| arg.to_string_lossy().into_owned())
            .collect(),
    )
}

fn refresh(system: &mut System, pid: Pid) {
    system.refresh_processes_specifics(
        ProcessesToUpdate::Some(&[pid]),
        true,
        ProcessRefreshKind::nothing().with_cmd(UpdateKind::Always),
    );
}

/// The pid of a server from an earlier session that's still running for this
/// project, if there is one. A stale pid file is removed.
pub fn find(server_dir: &Path, project_root: &Path) -> Option<u32> {
    let text = std::fs::read_to_string(pid_file(server_dir)).ok()?;
    let Ok(pid) = text.trim().parse::<u32>() else {
        clear(server_dir);
        return None;
    };
    let mut system = System::new();
    refresh(&mut system, Pid::from_u32(pid));
    match command_line(&system, Pid::from_u32(pid)) {
        Some(line) if is_server_for(&line, project_root) => Some(pid),
        _ => {
            clear(server_dir);
            None
        }
    }
}

/// Asks [pid] to shut down cleanly (Paper treats SIGTERM like `/stop`), waits
/// up to [grace], then kills it. Returns once it's gone.
pub async fn stop(server_dir: &Path, pid: u32, grace: Duration) {
    let pid = Pid::from_u32(pid);
    let mut system = System::new();
    refresh(&mut system, pid);
    if let Some(process) = system.process(pid) {
        // Windows has no polite signal for a console app; kill is all there is.
        if process.kill_with(sysinfo::Signal::Term).is_none() {
            process.kill();
        }
    }
    let deadline = tokio::time::Instant::now() + grace;
    loop {
        refresh(&mut system, pid);
        if system.process(pid).is_none() {
            break;
        }
        if tokio::time::Instant::now() >= deadline {
            if let Some(process) = system.process(pid) {
                process.kill();
            }
            tokio::time::sleep(Duration::from_millis(500)).await;
            break;
        }
        tokio::time::sleep(Duration::from_millis(250)).await;
    }
    clear(server_dir);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_a_server_for_this_project_counts() {
        let root = Path::new("/projects/basic");
        let ours = vec![
            "java".to_string(),
            "-Xmx2048M".into(),
            project_arg(root),
            "-jar".into(),
            "paper.jar".into(),
        ];
        assert!(is_server_for(&ours, root));
        assert!(!is_server_for(&ours, Path::new("/projects/other")));
        assert!(!is_server_for(
            &["java".into(), "-jar".into(), "paper.jar".into()],
            root
        ));
        // A path that merely starts the same isn't this project.
        let longer = vec![project_arg(Path::new("/projects/basic2"))];
        assert!(!is_server_for(&longer, root));
    }

    #[test]
    fn a_stale_pid_file_is_cleared() {
        let tmp = tempfile::tempdir().unwrap();
        // This test process is alive, but it isn't a dev server for the project.
        record(tmp.path(), std::process::id()).unwrap();
        assert_eq!(find(tmp.path(), Path::new("/projects/basic")), None);
        assert!(!pid_file(tmp.path()).exists());

        std::fs::write(pid_file(tmp.path()), "not a pid").unwrap();
        assert_eq!(find(tmp.path(), Path::new("/projects/basic")), None);
        assert!(!pid_file(tmp.path()).exists());
    }

    #[tokio::test]
    async fn finds_and_stops_a_leftover_server() {
        let tmp = tempfile::tempdir().unwrap();
        let root = tmp.path().join("project");
        // Stands in for an orphaned `java … -Dnetherforge.project=<root> …`.
        let mut child = std::process::Command::new(crate::testing::fake_server())
            .args(["sleep", "30", &project_arg(&root)])
            .spawn()
            .unwrap();
        record(tmp.path(), child.id()).unwrap();

        assert_eq!(find(tmp.path(), &root), Some(child.id()));
        stop(tmp.path(), child.id(), Duration::from_secs(5)).await;
        assert!(child.wait().is_ok());
        assert_eq!(find(tmp.path(), &root), None);
    }
}
