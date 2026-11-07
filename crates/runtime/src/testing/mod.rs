//! A project's script tests (`*_test.lua`, `nf.test`): the Kotlin runtime's `ScriptTests` and `TestHarness`, in
//! Rust. A test file runs in a scope of its own, like a resource's script (`require` reaches the project's modules
//! and the files beside it); its body declares the tests with `nf.test.case`, and `Session::run_test` calls one.
//! A session holds one file's tests at a time, since a runner starts a fresh server for every test (so nothing one
//! test did can reach the next). Only a session given a [`TestHarness`] (`Config::testing`) has `nf.test`.
//!
//! The runner (`runner.rs`) is what `netherforge test` and the editor's Tests panel read: one JSON event a line,
//! as Kotlin's `ScriptTestRunner` writes them, or text, and JUnit XML.

use std::rc::Rc;

use indexmap::IndexMap;
use mlua::Function;
use netherforge_luau::{Failure, ScopeId};
use netherforge_platform::{ItemData, PlatformEvents, PlayerRef, Uuid};

use crate::session::Session;

pub mod api;
pub mod runner;

/// What names a test file.
pub const SUFFIX: &str = "_test.lua";

/// A test player's UUID from their name, as a test server makes it (Java's `UUID.nameUUIDFromBytes`, as Kotlin's fake
/// server does): the network's other servers name them by it before they join here.
pub fn player_id(name: &str) -> Uuid {
    crate::packs::name_uuid(name.as_bytes())
}

/// What a test run gives the runtime: the fake server's clock, who is on it, and its events. Set as
/// `Config::testing` by a script test runner; a server never has one, which is why `nf.test` doesn't exist there.
pub trait TestHarness {
    /// One whole server tick: the runtime's (`PlatformEvents::tick`), then the server's own (its scheduler, the
    /// world's entities).
    fn tick(&self);

    /// Joins a fake player named `name`, the join events raised, and gives them: the one already online by that
    /// name, if there is one. None when the server can't.
    fn join(&self, name: &str) -> Option<PlayerRef>;

    /// The stack `player`'s game was last sent for `slot` of their inventory (of the window they have open, with
    /// `menu`; their cursor when `slot` is none), as they're shown it; none for none.
    fn shown(&self, player: Uuid, slot: Option<i32>, menu: bool) -> Option<ItemData>;

    /// What the server tells the runtime, to raise as the server would (a click, a key held).
    fn events(&self) -> Option<Rc<dyn PlatformEvents>>;
}

/// A script's failure as the editor's console gets it (`ScriptError`): what the runner fails a test with.
#[derive(Clone, Debug, PartialEq)]
pub struct ScriptError {
    pub message: String,
    pub file: Option<String>,
    pub line: Option<u32>,
    pub traceback: Option<String>,
}

/// How one test ended.
#[derive(Clone, Debug, PartialEq)]
pub enum TestOutcome {
    Passed,
    /// It raised an error: a failed `assert`, an `error` call, a mistake calling the API.
    Failed(ScriptError),
}

/// A test file run, and the tests it declared.
#[derive(Clone, Debug, PartialEq)]
pub struct LoadedTests {
    pub file: String,
    /// The tests' names, in the order the file declared them.
    pub names: Vec<String>,
    /// Why the file's own code failed (a syntax error, an error before it finished declaring tests), when it did.
    pub failure: Option<ScriptError>,
}

/// A request a script made in a test, waiting for the test's handler, and where its answer goes.
pub struct Queued {
    pub call: crate::http::fetch::HttpCall,
    pub done: Box<dyn FnOnce(Result<crate::http::fetch::HttpReply, String>)>,
}

/// A session's tests, and what a test run keeps of its scripts.
#[derive(Default)]
pub struct Tests {
    /// The harness the session runs under; none on a server.
    pub harness: Option<Rc<dyn TestHarness>>,
    cases: IndexMap<String, (ScopeId, Function)>,
    /// The test file's scope.
    scope: Option<ScopeId>,
    /// Whether a test file's own code is running: the only time tests are declared.
    declaring: bool,
    /// What scripts did wrong since the runner last took them.
    pub errors: Vec<ScriptError>,
    /// What scripts logged, `file:line: message`, since the runner last took them.
    pub logs: Vec<String>,
    /// What answers the test's `nf.http.request` calls (`nf.test.http`), in the scope that set it.
    pub(crate) http: Option<(ScopeId, Function)>,
    /// `nf.test.allow_private`: the owner's `http.allow-private` entries this test stands in for, until it ends.
    pub allowances: Vec<crate::http::PrivateAllowance>,
    /// Requests scripts made, for the test's handler to answer at the next tick.
    pub(crate) queued: Vec<Queued>,
    /// What else waits for the main thread's next turn, before the next tick (a WebAssembly stand-in's answer).
    pub(crate) later: Vec<Box<dyn FnOnce()>>,
    /// The test's other servers in its network, by name.
    pub(crate) peers: IndexMap<String, Rc<crate::network::local::TestPeer>>,
    /// How each of them answers requests on a channel (`nf.test.network_answer`): by server and full channel name,
    /// the test's scope and callback.
    pub(crate) peer_answers: IndexMap<(String, String), (ScopeId, Function)>,
    /// Why each request the test left unanswered failed it, in order; `reported` of them already raised.
    pub(crate) unanswered: Vec<String>,
    pub(crate) reported: usize,
}

impl Tests {
    /// `nf.test.case`: `scope`'s test `name`.
    pub(crate) fn register(
        &mut self,
        scope: ScopeId,
        file: Option<String>,
        name: String,
        callback: Function,
    ) -> Result<(), String> {
        if Some(scope) != self.scope || !self.declaring {
            return Err(
                "nf.test.case is for a test file's own code, which is where its tests are declared"
                    .into(),
            );
        }
        if name.trim().is_empty() {
            return Err("a test needs a name".into());
        }
        if self.cases.contains_key(&name) {
            return Err(format!(
                "there is already a test named \"{name}\" in {}",
                file.unwrap_or_default()
            ));
        }
        self.cases.insert(name, (scope, callback));
        Ok(())
    }

    /// The first request left unanswered since this was last asked: a test can't pass with one, even when the
    /// script took its `nil, err`.
    pub(crate) fn take_unanswered(&mut self) -> Option<String> {
        if self.reported < self.unanswered.len() {
            let message = self.unanswered[self.reported].clone();
            self.reported = self.unanswered.len();
            return Some(message);
        }
        None
    }

    /// Everything a test set up goes (the session is stopping).
    pub(crate) fn clear(&mut self) {
        self.cases.clear();
        self.scope = None;
        self.declaring = false;
        self.http = None;
        self.allowances.clear();
        self.queued.clear();
        self.later.clear();
        self.peers.clear();
        self.peer_answers.clear();
        self.unanswered.clear();
        self.reported = 0;
    }
}

/// A failure as the runner reports it: Luau's message (located, as it usually is), and where.
pub(crate) fn error_of(failure: &Failure, file: &str) -> ScriptError {
    let (located, traceback) = match failure {
        Failure::Error { message, traceback } => (message.clone(), traceback.clone()),
        other => (other.to_string(), None),
    };
    let (place, line) = match failure {
        Failure::Stopped {
            location: Some(l), ..
        } => (Some(l.chunk.clone()), Some(l.line)),
        _ => located
            .match_indices(": ")
            .find_map(|(at, _)| {
                let (file, line) = located[..at].rsplit_once(':')?;
                let line: u32 = line.parse().ok()?;
                file.ends_with(".lua")
                    .then(|| (Some(file.to_owned()), Some(line)))
            })
            .unwrap_or((Some(file.to_owned()), None)),
    };
    ScriptError {
        message: located,
        file: place,
        line,
        traceback,
    }
}

impl Session<'_> {
    /// The project's own test files, by project path: `*_test.lua` anywhere (a package's tests are its own to run).
    pub fn test_files(&self) -> Vec<String> {
        let mut files: Vec<String> = self
            .source()
            .files()
            .iter()
            .filter(|p| p.ends_with(SUFFIX) && !p.contains(':'))
            .cloned()
            .collect();
        files.sort();
        files
    }

    /// Runs the test file `file` (a project path) in a scope of its own, which declares its tests. A file that
    /// fails halfway leaves the tests it declared before that.
    pub fn load_tests(&self, file: &str) -> LoadedTests {
        let shared = self.shared().clone();
        let failed = |message: String| LoadedTests {
            file: file.to_owned(),
            names: Vec::new(),
            failure: Some(ScriptError {
                message,
                file: Some(file.to_owned()),
                line: None,
                traceback: None,
            }),
        };
        if shared.tests.borrow().harness.is_none() {
            return failed("tests run only under a test harness".into());
        }
        let Some(modules) = shared.modules.borrow().clone() else {
            return failed("the project isn't running".into());
        };
        {
            let mut tests = shared.tests.borrow_mut();
            tests.cases.clear();
            tests.declaring = true;
            tests.scope = None;
        }
        // The scope is known before its body runs, so `nf.test.case` can tell it's the file's own code.
        let (scope, ran) = modules.quiet_script(
            &format!("test {file}"),
            file,
            netherforge_format::script::DEFAULT_BUDGET as u64,
            &|scope| shared.tests.borrow_mut().scope = Some(scope),
        );
        let names = {
            let mut tests = shared.tests.borrow_mut();
            tests.declaring = false;
            tests.scope = scope;
            tests.cases.keys().cloned().collect()
        };
        self.released();
        LoadedTests {
            file: file.to_owned(),
            names,
            failure: ran.err().map(|f| error_of(&f, file)),
        }
    }

    /// Calls test `name`, which `load_tests` declared, as its file's code: how it ended.
    pub fn run_test(&self, name: &str) -> TestOutcome {
        let shared = self.shared().clone();
        let case = shared.tests.borrow().cases.get(name).cloned();
        let file = self.room().and_then(|room| {
            shared
                .tests
                .borrow()
                .scope
                .and_then(|s| room.host().scope_name(s))
        });
        let file = file
            .as_deref()
            .and_then(|n| n.strip_prefix("test "))
            .unwrap_or_default()
            .to_owned();
        let stopped = || {
            TestOutcome::Failed(ScriptError {
                message: "the test's file stopped running".into(),
                file: Some(file.clone()),
                line: None,
                traceback: None,
            })
        };
        let (Some((scope, callback)), Some(room)) = (case, self.room()) else {
            return TestOutcome::Failed(ScriptError {
                message: format!("no test named \"{name}\""),
                file: Some(file.clone()),
                line: None,
                traceback: None,
            });
        };
        let scripts = room.scripts().clone();
        if !scripts.live(scope) {
            return stopped();
        }
        let result = scripts.call_kept(scope, &callback, ());
        // What its handlers set off meanwhile is said now, so the runner sees it with this test.
        self.released();
        self.report();
        match result {
            Ok(_) => match shared.tests.borrow_mut().take_unanswered() {
                Some(message) => TestOutcome::Failed(ScriptError {
                    message,
                    file: Some(file.clone()),
                    line: None,
                    traceback: None,
                }),
                None => TestOutcome::Passed,
            },
            Err(failure) => TestOutcome::Failed(error_of(&failure, &file)),
        }
    }

    /// What scripts did wrong, and what they logged, since the last time: what the runner reports with a test.
    pub fn take_test_record(&self) -> (Vec<ScriptError>, Vec<String>) {
        let mut tests = self.shared().tests.borrow_mut();
        (
            std::mem::take(&mut tests.errors),
            std::mem::take(&mut tests.logs),
        )
    }
}

// ---- a test's answers to the scripts' requests (`nf.test.http`) ----------------------------------------------------

/// A script's value as JSON (the one Lua and JSON codec); none for nil.
pub(crate) fn json_of(
    host: &netherforge_luau::Host,
    value: &mlua::Value,
    what: &str,
) -> Result<Option<serde_json::Value>, String> {
    if value.is_nil() {
        return Ok(None);
    }
    let encoded = netherforge_luau::json::encode(
        host,
        value,
        what,
        "written as JSON",
        &netherforge_luau::json::NoTags,
    )
    .map_err(|stopped| stopped.0.to_string())?;
    if let Some(problem) = encoded.problems.into_iter().next() {
        return Err(problem);
    }
    let text = encoded
        .text
        .ok_or_else(|| format!("{what} can't be written as JSON"))?;
    serde_json::from_str(&text)
        .map(Some)
        .map_err(|e| e.to_string())
}

/// What a handler's answer may hold.
const ANSWER_KEYS: &[&str] = &["status", "headers", "body"];

/// A handler's answer as the reply a script gets; none when it isn't `{ status, headers, body }`.
fn reply_of(answer: &serde_json::Value, url: &url::Url) -> Option<crate::http::fetch::HttpReply> {
    use serde_json::Value as Json;
    let fields: serde_json::Map<String, Json> = match answer {
        Json::Object(fields) => fields.clone(),
        // An empty table reads as an empty list.
        Json::Array(list) if list.is_empty() => serde_json::Map::new(),
        _ => return None,
    };
    if fields.keys().any(|k| !ANSWER_KEYS.contains(&k.as_str())) {
        return None;
    }
    let status = match fields.get("status") {
        None => 200,
        Some(Json::Number(n)) => {
            let n = n.as_f64()?;
            if n.fract() != 0.0 || !(100.0..=599.0).contains(&n) {
                return None;
            }
            n as u16
        }
        Some(_) => return None,
    };
    let headers = match fields.get("headers") {
        None => std::collections::BTreeMap::new(),
        Some(Json::Object(given)) => given
            .iter()
            .map(|(k, v)| Some((k.to_lowercase(), v.as_str()?.to_owned())))
            .collect::<Option<_>>()?,
        Some(Json::Array(list)) if list.is_empty() => std::collections::BTreeMap::new(),
        Some(_) => return None,
    };
    let body = match fields.get("body") {
        None => String::new(),
        Some(Json::String(text)) => text.clone(),
        Some(_) => return None,
    };
    Some(crate::http::fetch::HttpReply {
        status,
        headers,
        bytes: body.clone().into_bytes(),
        body,
        url: url.clone(),
    })
}

/// Answers the requests scripts made since the last tick with the test's handler, as the test's code.
pub(crate) fn answer_queued(shared: &crate::Shared, scripts: &crate::Scripts) {
    let queued = std::mem::take(&mut shared.tests.borrow_mut().queued);
    for request in queued {
        let reply = answer(shared, scripts, &request.call);
        (request.done)(reply);
    }
    let later = std::mem::take(&mut shared.tests.borrow_mut().later);
    for job in later {
        job();
    }
    answer_peers(shared, scripts);
}

/// The test's other servers answer the requests they were asked on channels the test answers for them.
fn answer_peers(shared: &crate::Shared, scripts: &crate::Scripts) {
    let peers: Vec<Rc<crate::network::local::TestPeer>> =
        shared.tests.borrow().peers.values().cloned().collect();
    for peer in peers {
        let asked: Vec<(i64, String, String, serde_json::Value)> = {
            let tests = shared.tests.borrow();
            let mut log = peer.log.borrow_mut();
            let mut kept = std::collections::VecDeque::new();
            let mut asked = Vec::new();
            for request in log.requests.drain(..) {
                if tests
                    .peer_answers
                    .contains_key(&(peer.name.clone(), request.1.clone()))
                {
                    asked.push(request);
                } else {
                    kept.push_back(request);
                }
            }
            log.requests = kept;
            asked
        };
        for (id, channel, from, value) in asked {
            let answerer = shared
                .tests
                .borrow()
                .peer_answers
                .get(&(peer.name.clone(), channel))
                .cloned();
            let Some((scope, callback)) = answerer else {
                continue;
            };
            let host = scripts.host();
            let lua = host.lua();
            let given = crate::network::service::NetworkContext::to_lua(lua, Some(&value))
                .unwrap_or(mlua::Value::Nil);
            let (value, error) = match scripts.call_kept(scope, &callback, (given, from)) {
                Ok(answer) => match json_of(&host, &answer, "the answer") {
                    Ok(value) => (value, None),
                    Err(why) => (None, Some(why)),
                },
                Err(failure) => (None, Some(failure.to_string())),
            };
            peer.send(crate::network::protocol::ServerFrame::Reply { id, value, error });
        }
    }
}

fn answer(
    shared: &crate::Shared,
    scripts: &crate::Scripts,
    call: &crate::http::fetch::HttpCall,
) -> Result<crate::http::fetch::HttpReply, String> {
    let url = call.url.to_string();
    let unanswered = |why: String| {
        let message = format!("the request to {url} wasn't answered: {why}");
        shared.tests.borrow_mut().unanswered.push(message.clone());
        message
    };
    let handler = shared.tests.borrow().http.clone();
    let Some((scope, handler)) = handler else {
        return Err(unanswered(
            "no nf.test.http handler answers requests in this test".into(),
        ));
    };
    let host = scripts.host();
    let lua = host.lua();
    let request = (|| -> mlua::Result<mlua::Table> {
        let request = lua.create_table()?;
        request.raw_set("method", call.method.as_str())?;
        request.raw_set("url", url.as_str())?;
        let headers = lua.create_table()?;
        for (name, value) in &call.headers {
            headers.raw_set(name.as_str(), value.as_str())?;
        }
        request.raw_set("headers", headers)?;
        if let Some(body) = &call.body {
            request.raw_set("body", lua.create_string(body)?)?;
        }
        Ok(request)
    })()
    .map_err(|e| unanswered(e.to_string()))?;
    let answer = match scripts.call_kept(scope, &handler, request) {
        Ok(value) => value,
        Err(failure) => {
            return Err(unanswered(format!(
                "nf.test.http's handler failed: {failure}"
            )));
        }
    };
    let answer = json_of(&host, &answer, "the handler's answer")
        .map_err(|why| unanswered(format!("nf.test.http's handler answered {why}")))?;
    let Some(answer) = answer else {
        return Err(unanswered("nf.test.http's handler answered nil".into()));
    };
    reply_of(&answer, &call.url).ok_or_else(|| {
        unanswered(
            "nf.test.http's handler answered something that isn't { status, headers, body }".into(),
        )
    })
}
