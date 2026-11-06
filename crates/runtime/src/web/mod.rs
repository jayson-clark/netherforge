//! `nf.web` (Kotlin's `web/` and `NfWebImpl`): routes scripts answer, their package's public address, webhook
//! signatures and login flows.
//!
//! Two halves. The listener's (`gate.rs`): served on the resource pack's HTTP server (`PackServer`), which hands
//! every request that isn't for a pack to the gate while web routes are on; the gate refuses what it can on the
//! listener's thread and queues the rest. The session's (here): each tick routes at most [`PER_TICK`] queued
//! requests, by the first segment (the namespace), then the most specific matching route of that method (`HEAD` is
//! `GET`); `404`, or `405` with `Allow`. A script's handler is called with the request (a plain table) and a
//! `WebResponse` to answer it by, then or later. A failed handler is reported and answered `500`; one whose scope has
//! stopped `503`; one not answered in time `504`. Routes are their scope's; a login flow's are the runtime's.

pub mod api;
pub mod gate;
pub mod oauth;
pub mod path;

use std::collections::BTreeMap;
use std::rc::Rc;
use std::sync::Arc;

use indexmap::IndexMap;
use mlua::{Function, Lua, Table};
use netherforge_api::ApiError;
use netherforge_api::handles::{self, Handle};
use netherforge_luau::ScopeId;
use netherforge_platform::Platform;

use crate::Room;
use crate::scripts::Scripts;
use crate::session::{Define, Service};
use crate::shared::Shared;
use gate::{WebAnswer, WebCall, WebConfig, WebGate};
use path::RoutePath;

/// Requests routed a tick, at most: the rest wait (up to the gate's limit) for the next.
pub const PER_TICK: usize = 32;
const TICKS_PER_SECOND: u64 = 20;

/// What answers a route.
#[derive(Clone)]
pub enum Answerer {
    /// A script's handler.
    Script(Function),
    /// A login flow's callback (`confirm` false) or confirmation.
    Login { flow: i64, confirm: bool },
}

pub struct Route {
    pub number: i64,
    pub namespace: String,
    pub method: String,
    pub path: RoutePath,
    /// The scope that made it; none for the runtime's (it isn't released with any).
    pub scope: Option<ScopeId>,
    pub answerer: Answerer,
}

/// A request routed and waiting for its answer.
pub struct Waiting {
    pub number: i64,
    pub call: Arc<WebCall>,
    pub route: i64,
    pub deadline: u64,
}

/// The session's web routes, the requests waiting on them, and login flows.
#[derive(Default)]
pub struct Web {
    pub config: WebConfig,
    gate: Option<Arc<WebGate>>,
    /// Where the world reaches the server's web routes, without a slash at the end; none while they're off.
    pub base: Option<String>,
    pub routes: IndexMap<i64, Route>,
    pub waiting: IndexMap<i64, Waiting>,
    next_route: i64,
    next_waiting: i64,
    pub flows: oauth::Flows,
    /// The packages that declare `requires.web`, for the warning while web routes are off.
    declaring: Vec<String>,
    /// The project's namespace: its own manifest is `netherforge.json`, a package's `<ns>:netherforge.json`.
    home: Option<String>,
}

impl Web {
    /// The door requests come through: made on first use.
    pub fn gate(&mut self) -> Arc<WebGate> {
        let config = self.config.clone();
        self.gate
            .get_or_insert_with(|| Arc::new(WebGate::new(config)))
            .clone()
    }

    /// The address of `namespace`'s `path` (and `query`), or none while web routes are off.
    pub fn url(
        &self,
        namespace: &str,
        path: &str,
        query: &BTreeMap<String, String>,
    ) -> Option<String> {
        let base = self.base.as_ref()?;
        let tail = if path == "/" { "" } else { path };
        let query = if query.is_empty() {
            String::new()
        } else {
            format!("?{}", path::encode_query(query))
        };
        Some(format!("{base}/{namespace}{tail}{query}"))
    }

    /// Adds a route. A path that isn't one, or one another route of the package takes, is the caller's mistake.
    pub fn add(
        &mut self,
        namespace: &str,
        method: &str,
        path: &str,
        scope: Option<ScopeId>,
        answerer: Answerer,
    ) -> Result<i64, String> {
        let parsed = RoutePath::parse(path)
            .map_err(|why| format!("bad argument 'path' ({why}, got \"{path}\")"))?;
        if let Some(taken) = self
            .routes
            .values()
            .find(|r| r.namespace == namespace && r.method == method && r.path.same_as(&parsed))
        {
            let whose = match taken.answerer {
                Answerer::Login { .. } => "a login flow (nf.web.oauth)",
                Answerer::Script(_) => "another route",
            };
            return Err(format!(
                "{whose} of package \"{namespace}\" already answers {method} {}",
                taken.path.text
            ));
        }
        self.next_route += 1;
        let number = self.next_route;
        self.routes.insert(
            number,
            Route {
                number,
                namespace: namespace.to_owned(),
                method: method.to_owned(),
                path: parsed,
                scope,
                answerer,
            },
        );
        Ok(number)
    }

    /// Removes route `number`: requests waiting on it are answered `503`. False when it was already gone.
    pub fn remove(&mut self, number: i64) -> bool {
        if self.routes.shift_remove(&number).is_none() {
            return false;
        }
        let doomed: Vec<i64> = self
            .waiting
            .values()
            .filter(|w| w.route == number)
            .map(|w| w.number)
            .collect();
        for waiting in doomed {
            self.finish(waiting, unavailable());
        }
        true
    }

    /// Answers waiting request `number` with `answer`, once. False when it was already answered.
    pub fn finish(&mut self, number: i64, answer: WebAnswer) -> bool {
        self.waiting
            .shift_remove(&number)
            .is_some_and(|w| w.call.answer(answer))
    }

    fn wait(&mut self, call: Arc<WebCall>, route: i64, now: u64) -> i64 {
        self.next_waiting += 1;
        let number = self.next_waiting;
        let deadline = now + self.config.handler_timeout_seconds * TICKS_PER_SECOND;
        self.waiting.insert(
            number,
            Waiting {
                number,
                call,
                route,
                deadline,
            },
        );
        number
    }
}

pub fn unavailable() -> WebAnswer {
    WebAnswer::text(503, "That isn't available right now.", &[])
}

fn not_found() -> WebAnswer {
    WebAnswer::text(404, "Nothing here.", &[])
}

/// What the router does once nothing of the routes is borrowed.
enum Routed {
    Answer(WebAnswer),
    Script {
        waiting: i64,
        scope: Option<ScopeId>,
        handler: Function,
        what: String,
        params: BTreeMap<String, String>,
    },
    Login {
        waiting: i64,
        flow: i64,
        confirm: bool,
    },
}

/// The routing that calls scripts: the session's shared state and its scripts.
#[derive(Clone)]
pub struct WebContext {
    pub shared: Rc<Shared>,
    pub scripts: Scripts,
}

impl WebContext {
    /// Routes `call` now: answered at once, or handed to its route's handler.
    pub fn route(&self, call: Arc<WebCall>) {
        let routed = self.choose(&call);
        match routed {
            Routed::Answer(answer) => {
                call.answer(answer);
            }
            Routed::Login {
                waiting,
                flow,
                confirm,
            } => oauth::FlowsContext::of(self).answer(flow, confirm, waiting),
            Routed::Script {
                waiting,
                scope,
                handler,
                what,
                params,
            } => {
                let Some(scope) = scope.filter(|s| self.scripts.live(*s)) else {
                    self.shared.web.borrow_mut().finish(waiting, unavailable());
                    return;
                };
                let host = self.scripts.host();
                let lua = host.lua().clone();
                let response = netherforge_api::handle::push_any(
                    &lua,
                    &Handle::WebResponse(handles::WebResponse { number: waiting }),
                );
                let (Ok(request), Ok(response)) = (request_table(&lua, &call, &params), response)
                else {
                    self.shared.web.borrow_mut().finish(
                        waiting,
                        WebAnswer::text(500, "The server failed answering that.", &[]),
                    );
                    return;
                };
                if let Err(failure) = self.scripts.call_kept(scope, &handler, (request, response)) {
                    self.scripts.handler_failed(scope, &what, failure);
                    self.shared.web.borrow_mut().finish(
                        waiting,
                        WebAnswer::text(500, "The server failed answering that.", &[]),
                    );
                }
            }
        }
    }

    fn choose(&self, call: &Arc<WebCall>) -> Routed {
        let Some(segments) = path::segments(&call.path) else {
            return Routed::Answer(WebAnswer::text(400, "That address can't be read.", &[]));
        };
        let Some(namespace) = segments.first() else {
            return Routed::Answer(not_found());
        };
        let rest = &segments[1..];
        let method = if call.method == "HEAD" {
            "GET"
        } else {
            call.method.as_str()
        };
        let now = self.scripts.tick_count();
        let mut web = self.shared.web.borrow_mut();
        let matching: Vec<(i64, BTreeMap<String, String>)> = web
            .routes
            .values()
            .filter(|r| &r.namespace == namespace)
            .filter_map(|r| r.path.matches(rest).map(|p| (r.number, p)))
            .collect();
        let chosen = matching
            .iter()
            .filter(|(n, _)| web.routes[n].method == method)
            .min_by(|a, b| {
                path::compare_ranks(&web.routes[&a.0].path.rank(), &web.routes[&b.0].path.rank())
            })
            .cloned();
        let Some((number, params)) = chosen else {
            if matching.is_empty() {
                return Routed::Answer(not_found());
            }
            let mut allowed: Vec<String> = matching
                .iter()
                .map(|(n, _)| web.routes[n].method.clone())
                .collect();
            allowed.sort();
            allowed.dedup();
            if allowed.iter().any(|m| m == "GET") {
                allowed.push("HEAD".into());
            }
            return Routed::Answer(WebAnswer::text(
                405,
                &format!("That address doesn't take {method}."),
                &[("allow", &allowed.join(", "))],
            ));
        };
        let waiting = web.wait(call.clone(), number, now);
        let route = &web.routes[&number];
        match &route.answerer {
            Answerer::Login { flow, confirm } => Routed::Login {
                waiting,
                flow: *flow,
                confirm: *confirm,
            },
            Answerer::Script(handler) => Routed::Script {
                waiting,
                scope: route.scope,
                handler: handler.clone(),
                what: format!(
                    "{} /{}{}",
                    route.method,
                    route.namespace,
                    if route.path.text == "/" {
                        ""
                    } else {
                        route.path.text.as_str()
                    }
                ),
                params,
            },
        }
    }

    /// One tick: up to [`PER_TICK`] queued requests routed, then those waiting too long answered `504`.
    pub fn tick(&self) {
        let gate = self.shared.web.borrow_mut().gate();
        for _ in 0..PER_TICK {
            let Some(call) = gate.take() else { break };
            self.route(call);
        }
        oauth::FlowsContext::of(self).tick();
        let now = self.scripts.tick_count();
        let mut web = self.shared.web.borrow_mut();
        let late: Vec<i64> = web
            .waiting
            .values()
            .filter(|w| now >= w.deadline)
            .map(|w| w.number)
            .collect();
        for number in late {
            web.finish(
                number,
                WebAnswer::text(504, "The server took too long to answer.", &[]),
            );
        }
    }

    /// Tells `scripts` which route, response and flow handles still name something.
    pub fn install_liveness(shared: &Rc<Shared>, scripts: &Scripts) {
        let weak = Rc::downgrade(shared);
        scripts.add_liveness(Box::new(move |handle| {
            let shared = weak.upgrade()?;
            let web = shared.web.try_borrow().ok()?;
            match handle {
                Handle::Route(r) => Some(web.routes.contains_key(&r.number)),
                Handle::WebResponse(r) => Some(web.waiting.contains_key(&r.number)),
                Handle::OAuthFlow(f) => Some(web.flows.find(f.number).is_some()),
                _ => None,
            }
        }));
    }
}

/// What a handler's `request` is: a plain table, as `WebRequest` describes it.
fn request_table(
    lua: &Lua,
    call: &WebCall,
    params: &BTreeMap<String, String>,
) -> mlua::Result<Table> {
    let body = String::from_utf8_lossy(&call.body).into_owned();
    let table = lua.create_table()?;
    table.set("method", call.method.as_str())?;
    table.set("path", call.path.as_str())?;
    table.set("params", lua.create_table_from(params.clone())?)?;
    table.set(
        "query",
        lua.create_table_from(path::query(call.raw_query.as_deref()))?,
    )?;
    table.set("headers", lua.create_table_from(call.headers.clone())?)?;
    let form = call
        .headers
        .get("content-type")
        .map(|t| {
            t.split(';')
                .next()
                .unwrap_or_default()
                .trim()
                .to_ascii_lowercase()
        })
        .filter(|t| t == "application/x-www-form-urlencoded");
    if form.is_some() {
        table.set("form", lua.create_table_from(path::query(Some(&body)))?)?;
    }
    table.set("body", body)?;
    table.set("ip", call.ip.as_str())?;
    Ok(table)
}

/// Web routes' service: the listener's door set while they're on, requests routed each tick, a stopped scope's
/// routes gone, every waiting request answered `503` when the session stops.
pub struct WebService {
    pub shared: Rc<Shared>,
    pub context: std::cell::RefCell<Option<WebContext>>,
}

impl Service for WebService {
    fn name(&self) -> &'static str {
        "web routes"
    }

    fn start(&self, room: &Room, project: &Define) {
        *self.context.borrow_mut() = Some(WebContext {
            shared: self.shared.clone(),
            scripts: room.scripts().clone(),
        });
        let grants = crate::session::grants(project.project);
        let enabled = self.shared.web.borrow().config.enabled;
        let declaring: Vec<String> = grants
            .requires
            .iter()
            .filter(|(_, r)| r.as_ref().is_some_and(|r| r.web == Some(true)))
            .map(|(ns, _)| ns.clone())
            .collect();
        let (base, gate) = {
            let mut web = self.shared.web.borrow_mut();
            web.declaring = declaring;
            web.home = Some(grants.home.clone());
            (web.config.public_url.clone(), web.gate())
        };
        // A test run serves nothing: `nf.test.request` routes its requests, and its address is the owner's or
        // localhost's.
        if self.shared.tests.borrow().harness.is_some() {
            let base = base.unwrap_or_else(|| "http://localhost".into());
            self.shared.web.borrow_mut().base = Some(base.trim_end_matches('/').to_owned());
            return;
        }
        if !enabled {
            return;
        }
        let mut packs = self.shared.packs.borrow_mut();
        let Some(listener) = packs.listener(project.platform) else {
            return;
        };
        listener.set_web(Some(gate));
        let base = base
            .unwrap_or_else(|| format!("http://{}:{}", listener.host, listener.port))
            .trim_end_matches('/')
            .to_owned();
        drop(packs);
        project
            .platform
            .log()
            .info(format!("Web routes are on, at {base}"));
        self.shared.web.borrow_mut().base = Some(base);
    }

    fn tick(&self, _room: &Room, _platform: &dyn Platform) {
        let context = self.context.borrow().clone();
        if let Some(context) = context {
            context.tick();
        }
    }

    fn scope_released(&self, scopes: &[ScopeId]) {
        let context = self.context.borrow().clone();
        if let Some(context) = &context {
            oauth::FlowsContext::of(context).release(scopes);
        }
        let mut web = self.shared.web.borrow_mut();
        let doomed: Vec<i64> = web
            .routes
            .values()
            .filter(|r| r.scope.is_some_and(|s| scopes.contains(&s)))
            .map(|r| r.number)
            .collect();
        for number in doomed {
            web.remove(number);
        }
    }

    fn stop(&self, _room: &Room, _platform: &dyn Platform) {
        let context = self.context.borrow_mut().take();
        if let Some(context) = &context {
            oauth::FlowsContext::of(context).release_all();
        }
        let gate = {
            let mut web = self.shared.web.borrow_mut();
            let numbers: Vec<i64> = web.routes.keys().copied().collect();
            for number in numbers {
                web.remove(number);
            }
            let waiting: Vec<i64> = web.waiting.keys().copied().collect();
            for number in waiting {
                web.finish(number, unavailable());
            }
            web.base = None;
            web.gate.take()
        };
        if let Some(gate) = gate {
            while let Some(call) = gate.take() {
                call.answer(unavailable());
            }
        }
        if let Ok(packs) = self.shared.packs.try_borrow_mut()
            && let Some(listener) = packs.server()
        {
            listener.set_web(None);
        }
    }

    /// With web routes off, each package that declares them has a warning saying so.
    fn problems(&self) -> Vec<netherforge_format::Problem> {
        let web = self.shared.web.borrow();
        if web.config.enabled {
            return Vec::new();
        }
        web.declaring
            .iter()
            .map(|namespace| {
                let file = if web.home.as_deref() == Some(namespace.as_str()) {
                    netherforge_format::manifest::FILE_NAME.to_owned()
                } else {
                    format!("{namespace}:{}", netherforge_format::manifest::FILE_NAME)
                };
                netherforge_format::codes::RUNTIME_WEB_DISABLED.at(
                    &file,
                    "Web routes are off on this server, so this package's routes aren't served: the server owner \
                     turns them on with web.enabled in config.yml",
                    Some("$.requires.web".into()),
                )
            })
            .collect()
    }
}

/// An error a script is told at its line.
pub fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}
