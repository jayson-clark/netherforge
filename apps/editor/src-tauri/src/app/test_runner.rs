//! The script test runner: `NetherForgeTest-<version>.jar`, a JVM program that runs a
//! project's `*_test.lua` files on a fake server (`apps/plugin/test-runner`), run here with
//! the Java the dev server uses. The editor carries the jar like the plugin jars:
//!
//! - **Bundled app**: `tauri.bundle.conf.json` copies it into the app's resources under
//!   `test-runner/`.
//! - **Dev (debug builds)**: also looks in the repo's `apps/plugin/test-runner/build/libs/`
//!   (`node tools/gradle.mjs :plugin:test-runner:assemble`).
//!
//! The `netherforge test` command (apps/cli) finds the jar in `<data>/test-runner/`, where
//! [install_for_cli] puts a copy when the editor starts.
//!
//! The jar prints one JSON event per line with `--json` (`TestEvent` in the runner); [parse]
//! reads them back into a [TestReport].

use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::time::Duration;

use serde::{Deserialize, Serialize};
use tokio::process::Command;

use crate::error::{Error, ErrorCode, Result};
use crate::server::process::hide_console;

const PREFIX: &str = "NetherForgeTest-";

/// Longest a run may take: a project's tests are small, so past this something is stuck.
const TIMEOUT: Duration = Duration::from_secs(15 * 60);

/// `NetherForgeTest-0.1.0.jar` → `0.1.0`.
pub fn parse_jar_name(name: &str) -> Option<&str> {
    let version = name.strip_prefix(PREFIX)?.strip_suffix(".jar")?;
    (!version.is_empty()).then_some(version)
}

/// The folders to search, best first.
pub fn search_dirs(resource_dir: Option<&Path>) -> Vec<PathBuf> {
    let mut dirs = Vec::new();
    if let Some(resources) = resource_dir {
        dirs.push(resources.join("test-runner"));
    }
    if cfg!(debug_assertions) {
        dirs.push(
            Path::new(env!("CARGO_MANIFEST_DIR")).join("../../plugin/test-runner/build/libs"),
        );
    }
    dirs
}

/// The runner jar in [dirs]: from the first folder that has one, the most recently built.
pub fn find(dirs: &[PathBuf]) -> Option<PathBuf> {
    for dir in dirs {
        let Ok(entries) = std::fs::read_dir(dir) else {
            continue;
        };
        let mut jars: Vec<(PathBuf, std::time::SystemTime)> = entries
            .flatten()
            .filter(|e| parse_jar_name(&e.file_name().to_string_lossy()).is_some())
            .filter_map(|e| {
                let meta = e.metadata().ok().filter(|m| m.is_file())?;
                Some((e.path(), meta.modified().unwrap_or(std::time::UNIX_EPOCH)))
            })
            .collect();
        jars.sort_by_key(|jar| std::cmp::Reverse(jar.1));
        if let Some((jar, _)) = jars.into_iter().next() {
            return Some(jar);
        }
    }
    None
}

/// Puts a copy of [jar] in [dir] (`<data>/test-runner/`), where `netherforge test` finds it, and
/// removes the runners of other versions there. Nothing is copied when [dir] already has this one.
pub fn install_for_cli(jar: &Path, dir: &Path) -> std::io::Result<()> {
    let Some(name) = jar.file_name() else {
        return Ok(());
    };
    std::fs::create_dir_all(dir)?;
    for entry in std::fs::read_dir(dir)?.flatten() {
        let other = entry.file_name();
        if other != name && parse_jar_name(&other.to_string_lossy()).is_some() {
            let _ = std::fs::remove_file(entry.path());
        }
    }
    let target = dir.join(name);
    let same = std::fs::metadata(&target)
        .ok()
        .zip(std::fs::metadata(jar).ok())
        .is_some_and(|(a, b)| a.len() == b.len());
    if !same {
        // Beside the target and renamed, so a `netherforge test` running now never reads half a jar.
        let staged = dir.join(format!(".{}.part", name.to_string_lossy()));
        std::fs::copy(jar, &staged)?;
        std::fs::rename(&staged, &target)?;
    }
    Ok(())
}

/// How a test ended.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, specta::Type)]
#[serde(rename_all = "lowercase")]
pub enum TestStatus {
    Passed,
    /// The test itself raised an error: a failed `assert`.
    Failed,
    /// Something else went wrong while it ran: a script erroring, the project not starting.
    Errored,
}

/// A place in the project: `tests/greeter_test.lua`, line 12.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct TestPlace {
    pub file: String,
    #[serde(default)]
    pub line: Option<u32>,
}

/// One test's result, as the runner reports it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct TestResult {
    pub file: String,
    pub name: String,
    pub status: TestStatus,
    pub duration_millis: u64,
    /// What went wrong, for a test that didn't pass.
    #[serde(default)]
    pub message: Option<String>,
    /// Where: the test's own line, or the script's that errored.
    #[serde(default)]
    pub source: Option<TestPlace>,
    #[serde(default)]
    pub traceback: Option<String>,
    /// What scripts logged while it ran, with file and line.
    #[serde(default)]
    pub logs: Vec<String>,
}

/// A whole run: every test's result, the totals, and, when the tests couldn't run at all (the
/// project has errors), why.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize, specta::Type)]
#[serde(rename_all = "camelCase")]
pub struct TestReport {
    pub results: Vec<TestResult>,
    pub passed: u32,
    pub failed: u32,
    pub errored: u32,
    pub duration_millis: u64,
    /// Why no test could run: the project was refused.
    pub error: Option<String>,
    /// What the project's problems were, when that is why.
    pub problems: Vec<String>,
}

/// One line of the runner's `--json` output; the ones this reads.
#[derive(Deserialize)]
#[serde(
    tag = "event",
    rename_all = "lowercase",
    rename_all_fields = "camelCase"
)]
enum Line {
    Start,
    File,
    Result(TestResult),
    End {
        passed: u32,
        failed: u32,
        errored: u32,
        duration_millis: u64,
    },
    Error {
        message: String,
        #[serde(default)]
        problems: Vec<String>,
    },
}

/// The runner's output as a report. A line that isn't an event (a JVM warning) is skipped.
pub fn parse(output: &str) -> TestReport {
    let mut report = TestReport::default();
    for line in output.lines() {
        let Ok(event) = serde_json::from_str::<Line>(line) else {
            continue;
        };
        match event {
            Line::Start | Line::File => {}
            Line::Result(result) => report.results.push(result),
            Line::End {
                passed,
                failed,
                errored,
                duration_millis,
            } => {
                report.passed = passed;
                report.failed = failed;
                report.errored = errored;
                report.duration_millis = duration_millis;
            }
            Line::Error { message, problems } => {
                report.error = Some(message);
                report.problems = problems;
            }
        }
    }
    report
}

/// Runs the project's tests (those whose "file: name" contains [filter], when given) with [java]
/// and the runner [jar], on the game data in [game_data] (the dev server's export of the project's
/// Minecraft version). Git packages' checkouts are found in [packages], the editor's cache.
pub async fn run(
    java: &Path,
    jar: &Path,
    root: &Path,
    packages: &Path,
    game_data: &Path,
    filter: Option<&str>,
) -> Result<TestReport> {
    let mut command = Command::new(java);
    command
        .arg("-jar")
        .arg(jar)
        .arg(root)
        .arg("--json")
        .arg("--packages")
        .arg(packages)
        .arg("--game-data")
        .arg(game_data);
    if let Some(filter) = filter.filter(|it| !it.is_empty()) {
        command.arg("--filter").arg(filter);
    }
    command
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    hide_console(&mut command);
    let output = tokio::time::timeout(TIMEOUT, command.output())
        .await
        .map_err(|_| {
            Error::new(
                ErrorCode::Timeout,
                "The tests didn't finish in 15 minutes and were stopped",
            )
        })?
        .map_err(|e| Error::msg(format!("Couldn't start {}: {e}", java.display())))?;
    let report = parse(&String::from_utf8_lossy(&output.stdout));
    if output.status.success() || !report.results.is_empty() || report.error.is_some() {
        return Ok(report);
    }
    // No events at all: the JVM or the jar failed before the runner said anything.
    let stderr = String::from_utf8_lossy(&output.stderr);
    let detail = stderr.trim().lines().last().unwrap_or("no output");
    Err(Error::msg(format!("The test runner failed: {detail}")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_jar_names() {
        assert_eq!(parse_jar_name("NetherForgeTest-0.1.0.jar"), Some("0.1.0"));
        assert_eq!(parse_jar_name("NetherForgeTest-.jar"), None);
        assert_eq!(parse_jar_name("NetherForge-0.1.0-paper-26.3.jar"), None);
        assert_eq!(parse_jar_name("NetherForgeTest-0.1.0.jar.part"), None);
    }

    #[test]
    fn finds_the_newest_jar_in_the_first_folder_that_has_one() {
        let tmp = tempfile::tempdir().unwrap();
        let bundled = tmp.path().join("bundled");
        let dev = tmp.path().join("dev");
        std::fs::create_dir_all(&bundled).unwrap();
        std::fs::create_dir_all(&dev).unwrap();
        std::fs::write(dev.join("NetherForgeTest-0.2.0.jar"), "b").unwrap();
        assert_eq!(
            find(&[tmp.path().join("missing"), bundled.clone(), dev.clone()]),
            Some(dev.join("NetherForgeTest-0.2.0.jar"))
        );
        std::fs::write(bundled.join("NetherForgeTest-0.1.0.jar"), "a").unwrap();
        std::fs::write(bundled.join("notes.txt"), "").unwrap();
        assert_eq!(
            find(&[bundled.clone(), dev.clone()]),
            Some(bundled.join("NetherForgeTest-0.1.0.jar"))
        );
        assert_eq!(find(&[tmp.path().join("missing")]), None);
    }

    #[test]
    fn installs_a_copy_for_the_command_and_drops_other_versions() {
        let tmp = tempfile::tempdir().unwrap();
        let jar = tmp.path().join("NetherForgeTest-0.2.0.jar");
        std::fs::write(&jar, "new jar").unwrap();
        let dir = tmp.path().join("data/test-runner");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("NetherForgeTest-0.1.0.jar"), "old").unwrap();
        std::fs::write(dir.join("other.txt"), "kept").unwrap();

        install_for_cli(&jar, &dir).unwrap();
        assert_eq!(
            std::fs::read_to_string(dir.join("NetherForgeTest-0.2.0.jar")).unwrap(),
            "new jar"
        );
        assert!(!dir.join("NetherForgeTest-0.1.0.jar").exists());
        assert!(dir.join("other.txt").exists());
        // Again: nothing to do, and no staging file left.
        install_for_cli(&jar, &dir).unwrap();
        let names: Vec<_> = std::fs::read_dir(&dir)
            .unwrap()
            .flatten()
            .map(|e| e.file_name().to_string_lossy().into_owned())
            .collect();
        assert_eq!(names.len(), 2, "{names:?}");
    }

    const OUTPUT: &str = r#"SLF4J: noise
{"event":"start","project":"/p","version":"0.1.0"}
{"event":"file","file":"tests/a_test.lua","tests":["ok","bad"]}
{"event":"result","file":"tests/a_test.lua","name":"ok","status":"passed","durationMillis":3}
{"event":"result","file":"tests/a_test.lua","name":"bad","status":"failed","durationMillis":1,"message":"nope","source":{"file":"tests/a_test.lua","line":7},"traceback":"stack","logs":["tests/a_test.lua:6: hi"]}
{"event":"end","passed":1,"failed":1,"errored":0,"durationMillis":40}
"#;

    #[test]
    fn reads_the_runners_events() {
        let report = parse(OUTPUT);
        assert_eq!((report.passed, report.failed, report.errored), (1, 1, 0));
        assert_eq!(report.duration_millis, 40);
        assert_eq!(report.results.len(), 2);
        assert_eq!(report.results[0].status, TestStatus::Passed);
        assert_eq!(report.results[0].message, None);
        let bad = &report.results[1];
        assert_eq!(bad.status, TestStatus::Failed);
        assert_eq!(
            bad.source,
            Some(TestPlace {
                file: "tests/a_test.lua".into(),
                line: Some(7)
            })
        );
        assert_eq!(bad.logs, ["tests/a_test.lua:6: hi"]);
        assert_eq!(report.error, None);
    }

    #[test]
    fn reads_a_refused_project() {
        let report = parse(
            r#"{"event":"error","message":"the project can't run","problems":["netherforge.json: error: bad"]}"#,
        );
        assert_eq!(report.error.as_deref(), Some("the project can't run"));
        assert_eq!(report.problems, ["netherforge.json: error: bad"]);
        assert!(report.results.is_empty());
    }

    /// The fake server stands in for `java`: it prints the "jar" (the runner's
    /// output) and records the arguments it was given beside it.
    #[tokio::test]
    async fn runs_java_with_the_jar_and_reads_what_it_prints() {
        let tmp = tempfile::tempdir().unwrap();
        let jar = tmp.path().join("NetherForgeTest-0.1.0.jar");
        std::fs::write(&jar, OUTPUT).unwrap();

        let report = run(
            &crate::testing::fake_server(),
            &jar,
            Path::new("/the/project"),
            Path::new("/cache"),
            Path::new("/data/game-data.json"),
            Some("greets"),
        )
        .await
        .unwrap();
        // A failed test makes the runner exit 1: still a report.
        assert_eq!(report.results.len(), 2);
        let args =
            std::fs::read_to_string(tmp.path().join("NetherForgeTest-0.1.0.jar.args")).unwrap();
        assert_eq!(
            args.lines().collect::<Vec<_>>(),
            [
                "-jar",
                &jar.display().to_string(),
                "/the/project",
                "--json",
                "--packages",
                "/cache",
                "--game-data",
                "/data/game-data.json",
                "--filter",
                "greets"
            ]
        );
    }

    #[tokio::test]
    async fn a_runner_that_says_nothing_is_an_error_with_its_last_line() {
        let tmp = tempfile::tempdir().unwrap();
        let error = run(
            &crate::testing::fake_server(),
            &tmp.path().join("NetherForgeTest-0.1.0.jar"),
            Path::new("/p"),
            Path::new("/c"),
            Path::new("/g.json"),
            None,
        )
        .await
        .unwrap_err();
        assert!(
            error.message.contains("Unable to access jarfile"),
            "{error:?}"
        );
    }
}
