//! Running a project's script tests (Kotlin's `ScriptTestRunner`, `TextReport` and `JUnitReport`): each test file
//! declares its tests with `nf.test.case`, and **each test runs on a server of its own**, a fresh one the caller
//! starts (its fake platform and a session with a harness), so nothing a test does is there for the next. The
//! file's own code runs once per test, before it.
//!
//! What a test passes or fails on:
//!  - the test's own code raising an error (a failed `assert`): `failed`, at its line;
//!  - a script of the project erroring while the test ran (or as the project started): `errored`, at the script's
//!    line, since the test can't have meant that;
//!  - the project not starting at all: a [`Refused`]; a test file that doesn't run to its end: an `errored` result
//!    for the file.
//!
//! What it reports is Kotlin's, event for event: one JSON object a line (`netherforge test --json`, which the editor
//! reads), or text, and JUnit XML.

use std::time::Instant;

use serde::Serialize;

use super::{ScriptError, TestOutcome};
use crate::server::ServerEvents;

/// How a test ended.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum Status {
    Passed,
    /// The test itself raised an error: a failed `assert`, an `error` call, a mistake calling the API.
    Failed,
    /// The test body was fine, but something else went wrong: a script of the project errored while it ran, the
    /// project didn't start, the test file didn't load.
    Errored,
}

/// A place in the project: `tests/greeter_test.lua`, line 12.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Place {
    pub file: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub line: Option<u32>,
}

/// What a run reports, in order: a `start`, a `file` per test file and a `result` per test as it finishes, then an
/// `end`. A run that can't start at all (the project is refused) is an `error` alone.
#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(tag = "event")]
pub enum TestEvent {
    #[serde(rename = "start")]
    Started { project: String, version: String },
    /// A test file's own code ran: the tests it declared, or why it didn't get to declare them.
    #[serde(rename = "file")]
    FileLoaded {
        file: String,
        tests: Vec<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        message: Option<String>,
        #[serde(skip_serializing_if = "Option::is_none")]
        source: Option<Place>,
    },
    #[serde(rename = "result")]
    Result(TestResult),
    #[serde(rename = "end")]
    Finished(RunFinished),
    #[serde(rename = "error")]
    Failed {
        message: String,
        #[serde(skip_serializing_if = "Vec::is_empty")]
        problems: Vec<String>,
    },
}

#[derive(Clone, Debug, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TestResult {
    pub file: String,
    pub name: String,
    pub status: Status,
    pub duration_millis: u64,
    /// What went wrong, for a test that didn't pass.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
    /// Where, in the project (the test's own line, or the script's that errored), when Luau could say.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source: Option<Place>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub traceback: Option<String>,
    /// What scripts logged while the test ran (`log(...)`), with file and line.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub logs: Vec<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RunFinished {
    pub passed: u32,
    pub failed: u32,
    pub errored: u32,
    pub duration_millis: u64,
}

impl RunFinished {
    /// Whether every test passed (a run that ran none counts).
    pub fn ok(&self) -> bool {
        self.failed == 0 && self.errored == 0
    }
}

impl TestEvent {
    /// The event as one line of JSON: `{"event":"result",...}`, a key left out when it's null or empty.
    pub fn json(&self) -> String {
        serde_json::to_string(self).expect("an event is plain data")
    }
}

/// A project the runtime refused or couldn't load, so no test could run.
#[derive(Clone, Debug, PartialEq)]
pub struct Refused {
    pub message: String,
    pub problems: Vec<String>,
}

/// One fresh server running the project, for one test: what the runner starts again for each.
pub trait TestServer {
    /// What the server tells the runtime: it holds the session.
    fn events(&self) -> &ServerEvents<'static>;
}

/// Runs a project's tests on servers `start` makes, one per test.
pub struct Runner<'s, S: TestServer> {
    /// The project, as the `start` event names it.
    pub project: String,
    /// The runner's version, as the `start` event says it.
    pub version: String,
    pub start: &'s dyn Fn() -> S,
}

/// A traceback without the runtime's own frames (the shim's, Luau's `[C]` ones: `error`, `assert`, a binding),
/// which say nothing about the script.
fn trimmed(traceback: Option<String>) -> Option<String> {
    traceback.map(|t| {
        t.lines()
            .filter(|l| !l.contains("nf/") && !l.trim_start().starts_with("[C]"))
            .collect::<Vec<_>>()
            .join("\n")
    })
}

fn place_of(error: &ScriptError) -> Option<Place> {
    error.file.as_ref().map(|file| Place {
        file: file.clone(),
        line: error.line,
    })
}

fn millis(since: Instant) -> u64 {
    since.elapsed().as_millis() as u64
}

impl<S: TestServer> Runner<'_, S> {
    /// A server started, or why the project can't run there.
    fn server(&self) -> Result<S, Refused> {
        let server = (self.start)();
        {
            let session = server.events().session();
            if session.room().is_none() {
                let problems: Vec<String> = session
                    .problems()
                    .iter()
                    .map(|p| {
                        let line = p.line.map(|l| format!(":{l}")).unwrap_or_default();
                        let severity = format!("{:?}", p.severity).to_lowercase();
                        format!("{}{line}: {severity}: {}", p.file, p.message)
                    })
                    .collect();
                let first = problems
                    .first()
                    .cloned()
                    .or_else(|| session.refusal().map(|r| r.message.clone()))
                    .unwrap_or_else(|| "it was refused".into());
                return Err(Refused {
                    message: format!("the project can't run: {first}"),
                    problems,
                });
            }
        }
        Ok(server)
    }

    /// Runs every test whose "file: name" contains `filter` (all of them without one), reporting each event to
    /// `report` as it happens: the totals, which the `end` event reported; or why the project can't run at all.
    pub fn run(
        &self,
        filter: Option<&str>,
        report: &mut dyn FnMut(&TestEvent),
    ) -> Result<RunFinished, Refused> {
        let started = Instant::now();
        // The project's own test files, as a probe server finds them.
        let files = self.server()?.events().session().test_files();
        report(&TestEvent::Started {
            project: self.project.clone(),
            version: self.version.clone(),
        });
        let mut totals = RunFinished {
            passed: 0,
            failed: 0,
            errored: 0,
            duration_millis: 0,
        };
        for file in files {
            self.run_file(&file, filter, &mut totals, report)?;
        }
        totals.duration_millis = millis(started);
        report(&TestEvent::Finished(totals));
        Ok(totals)
    }

    fn run_file(
        &self,
        file: &str,
        filter: Option<&str>,
        totals: &mut RunFinished,
        report: &mut dyn FnMut(&TestEvent),
    ) -> Result<(), Refused> {
        let mut count = |result: TestResult, report: &mut dyn FnMut(&TestEvent)| {
            match result.status {
                Status::Passed => totals.passed += 1,
                Status::Failed => totals.failed += 1,
                Status::Errored => totals.errored += 1,
            }
            report(&TestEvent::Result(result));
        };
        let wanted = |name: &str| {
            filter.is_none_or(|f| {
                format!("{file}: {name}")
                    .to_lowercase()
                    .contains(&f.to_lowercase())
            })
        };
        let mut server = self.server()?;
        let mut start_errors = server.events().session().take_test_record().0;
        let loaded = server.events().session().load_tests(file);
        if !loaded.names.iter().any(|n| wanted(n)) && loaded.failure.is_none() {
            return Ok(());
        }
        report(&TestEvent::FileLoaded {
            file: file.to_owned(),
            tests: loaded.names.clone(),
            message: loaded.failure.as_ref().map(|f| f.message.clone()),
            source: loaded.failure.as_ref().and_then(place_of),
        });
        if let Some(failure) = loaded.failure {
            let (_, logs) = server.events().session().take_test_record();
            count(
                TestResult {
                    file: file.to_owned(),
                    name: format!("(running {file})"),
                    status: Status::Errored,
                    duration_millis: 0,
                    message: Some(failure.message.clone()),
                    source: place_of(&failure),
                    traceback: trimmed(failure.traceback),
                    logs,
                },
                report,
            );
            return Ok(());
        }
        let mut first = true;
        for name in &loaded.names {
            if !wanted(name) {
                continue;
            }
            // The first test runs on the server that found the names; each later one gets a fresh one.
            if !first {
                drop(server);
                server = self.server()?;
                start_errors = server.events().session().take_test_record().0;
                server.events().session().load_tests(file);
            }
            first = false;
            count(run_test(&server, file, name, &start_errors), report);
        }
        Ok(())
    }
}

/// Runs test `name` of `file` on `server`, whose project reported `start_errors` as it started.
fn run_test<S: TestServer>(
    server: &S,
    file: &str,
    name: &str,
    start_errors: &[ScriptError],
) -> TestResult {
    let started = Instant::now();
    let outcome = server.events().session().run_test(name);
    let (during, logs) = server.events().session().take_test_record();
    let duration_millis = millis(started);
    let base = TestResult {
        file: file.to_owned(),
        name: name.to_owned(),
        status: Status::Passed,
        duration_millis,
        message: None,
        source: None,
        traceback: None,
        logs,
    };
    if let TestOutcome::Failed(failure) = outcome {
        return TestResult {
            status: Status::Failed,
            message: Some(failure.message.clone()),
            source: place_of(&failure),
            traceback: trimmed(failure.traceback),
            ..base
        };
    }
    let errors: Vec<&ScriptError> = start_errors.iter().chain(during.iter()).collect();
    let Some(first) = errors.first() else {
        return base;
    };
    let when = if start_errors.is_empty() {
        "while the test ran"
    } else {
        "while the project started"
    };
    let more = if errors.len() > 1 {
        format!(" (and {} more)", errors.len() - 1)
    } else {
        String::new()
    };
    TestResult {
        status: Status::Errored,
        message: Some(format!("a script failed {when}: {}{more}", first.message)),
        source: place_of(first),
        traceback: trimmed(first.traceback.clone()),
        ..base
    }
}

/// What a run prints for people: each test with its verdict and where it failed, then the totals.
#[derive(Default)]
pub struct TextReport {
    file: Option<String>,
}

impl TextReport {
    /// The lines `event` prints.
    pub fn lines(&mut self, event: &TestEvent) -> Vec<String> {
        let mut out = Vec::new();
        match event {
            TestEvent::Started { .. } | TestEvent::FileLoaded { .. } => {}
            TestEvent::Result(result) => {
                if self.file.as_deref() != Some(result.file.as_str()) {
                    self.file = Some(result.file.clone());
                    out.push(result.file.clone());
                }
                let label = match result.status {
                    Status::Passed => "PASS",
                    Status::Failed => "FAIL",
                    Status::Errored => "ERROR",
                };
                out.push(format!(
                    "  {label}  {} ({} ms)",
                    result.name, result.duration_millis
                ));
                if result.status != Status::Passed {
                    let place = result
                        .source
                        .as_ref()
                        .map(|p| {
                            let line = p.line.map(|l| format!(":{l}")).unwrap_or_default();
                            format!("{}{line}: ", p.file)
                        })
                        .unwrap_or_default();
                    let message = result.message.clone().unwrap_or_default();
                    // Luau's own message usually starts with the location already.
                    let place = if message.starts_with(&place) {
                        String::new()
                    } else {
                        place
                    };
                    out.push(format!("        {place}{message}"));
                    for log in &result.logs {
                        out.push(format!("        log: {log}"));
                    }
                }
            }
            TestEvent::Finished(end) => {
                let mut parts = vec![
                    format!("{} passed", end.passed),
                    format!("{} failed", end.failed),
                ];
                if end.errored > 0 {
                    parts.push(format!("{} errored", end.errored));
                }
                out.push(String::new());
                out.push(format!(
                    "{} ({:.1} s)",
                    parts.join(", "),
                    end.duration_millis as f64 / 1000.0
                ));
            }
            TestEvent::Failed { message, problems } => {
                out.push(format!("Can't run the project's tests: {message}"));
                for problem in problems.iter().skip(1) {
                    out.push(format!("  {problem}"));
                }
            }
        }
        out
    }
}

/// `text` as XML character data.
fn xml_text(text: &str) -> String {
    text.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
}

/// `text` as an XML attribute's value.
fn xml_attribute(text: &str) -> String {
    xml_text(text).replace('"', "&quot;")
}

/// JUnit XML (what CI systems read: GitHub's test reporters, Jenkins, GitLab): one `testsuite` per test file, a
/// `testcase` per test, a `failure` for a failed one and an `error` for an errored one.
pub fn junit(results: &[TestResult], duration_millis: u64) -> String {
    let seconds = |millis: u64| format!("{:.3}", millis as f64 / 1000.0);
    let count =
        |of: &[&TestResult], status: Status| of.iter().filter(|t| t.status == status).count();
    let all: Vec<&TestResult> = results.iter().collect();
    let mut out = String::from("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    out.push_str(&format!(
        "<testsuites tests=\"{}\" failures=\"{}\" errors=\"{}\" time=\"{}\">",
        all.len(),
        count(&all, Status::Failed),
        count(&all, Status::Errored),
        seconds(duration_millis)
    ));
    let mut files: Vec<&str> = Vec::new();
    for result in results {
        if !files.contains(&result.file.as_str()) {
            files.push(&result.file);
        }
    }
    for file in files {
        let tests: Vec<&TestResult> = results.iter().filter(|t| t.file == file).collect();
        out.push_str(&format!(
            "\n  <testsuite name=\"{}\" tests=\"{}\" failures=\"{}\" errors=\"{}\" time=\"{}\">",
            xml_attribute(file),
            tests.len(),
            count(&tests, Status::Failed),
            count(&tests, Status::Errored),
            seconds(tests.iter().map(|t| t.duration_millis).sum())
        ));
        for test in &tests {
            out.push_str(&format!(
                "\n    <testcase classname=\"{}\" name=\"{}\" time=\"{}\">",
                xml_attribute(file),
                xml_attribute(&test.name),
                seconds(test.duration_millis)
            ));
            if test.status != Status::Passed {
                let element = if test.status == Status::Failed {
                    "failure"
                } else {
                    "error"
                };
                let body: Vec<String> = test
                    .source
                    .iter()
                    .map(|p| {
                        let line = p.line.map(|l| format!(":{l}")).unwrap_or_default();
                        format!("{}{line}", p.file)
                    })
                    .chain(test.traceback.clone())
                    .collect();
                out.push_str(&format!(
                    "\n      <{element} message=\"{}\">{}</{element}>",
                    xml_attribute(test.message.as_deref().unwrap_or_default()),
                    xml_text(&body.join("\n"))
                ));
            }
            if !test.logs.is_empty() {
                out.push_str(&format!(
                    "\n      <system-out>{}</system-out>",
                    xml_text(&test.logs.join("\n"))
                ));
            }
            if test.status != Status::Passed || !test.logs.is_empty() {
                out.push_str("\n    ");
            }
            out.push_str("</testcase>");
        }
        out.push_str("\n  </testsuite>");
    }
    out.push_str("\n</testsuites>\n");
    out
}
