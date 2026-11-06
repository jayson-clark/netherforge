//! "Log in with…" flows (`nf.web.oauth`, Kotlin's `OAuthFlows`): OAuth 2's authorization code grant with PKCE, the
//! security-sensitive parts kept here rather than in scripts.
//!
//! A flow has two routes of its own, `GET /<namespace>/oauth/<name>/callback` and `POST .../confirm`. `begin` makes a
//! login: a random state and code verifier, tied to the player, kept here (never in the URL but the state and the
//! verifier's challenge), used once and forgotten after ten minutes. The callback finds the login by its state (an
//! unknown one is refused with no event: forged or used already) and asks the browser to confirm which Minecraft
//! player it signs in as, since the login is tied to the player who began it, not to the browser that finishes it.
//! Only once they confirm (with a single-use token from that page) is the code traded for a token at the flow's token
//! endpoint, on a worker through `nf.http`'s guarded client, its host held to the package's `requires.http`; then
//! `complete` or `fail` is raised on the flow and the browser is answered with a page saying so. A flow belongs to
//! the scope that made it; logins under way go with it.

use std::collections::BTreeMap;
use std::rc::Rc;
use std::sync::mpsc;

use base64::Engine as _;
use indexmap::IndexMap;
use mlua::Value;
use netherforge_api::codec::Codec;
use netherforge_api::handles::{self, Handle};
use netherforge_api::{codecs, shapes};
use netherforge_luau::ScopeId;
use sha2::Digest as _;

use super::gate::WebAnswer;
use super::{Answerer, WebContext, path};
use crate::events::parse_uuid;
use crate::http::fetch::{Declared, HttpCall};
use crate::scripts::{Scripts, Target};
use crate::shared::Shared;

const TOKEN_BYTES: usize = 32;
const MAX_LOGINS: usize = 1000;
/// How long a login may take: ten minutes.
pub const LOGIN_TICKS: u64 = 20 * 60 * 10;

pub fn callback_path(name: &str) -> String {
    format!("/oauth/{name}/callback")
}

pub fn confirm_path(name: &str) -> String {
    format!("/oauth/{name}/confirm")
}

#[derive(Clone)]
struct Login {
    player: handles::Player,
    name: String,
    verifier: String,
    expires: u64,
}

struct Confirmation {
    login: Login,
    code: String,
}

pub struct Flow {
    pub number: i64,
    pub scope: ScopeId,
    pub namespace: String,
    pub options: shapes::OAuthOptions,
    /// The host requirement, bound to the package that made the flow: each redirect's.
    declared: Declared,
    route: i64,
    confirm_route: i64,
    logins: IndexMap<String, Login>,
    /// Logins back from the service, waiting for the browser to confirm the player, by the confirm page's token.
    confirmations: IndexMap<String, Confirmation>,
    /// What its token endpoint answers in a test run (`nf.test.oauth_token`), where it isn't called.
    pub test_token: Option<serde_json::Value>,
}

/// A token request in flight.
struct Exchange {
    flow: i64,
    login: Login,
    waiting: i64,
    reply: mpsc::Receiver<Result<serde_json::Value, String>>,
}

/// Every flow, by number, and their token requests in flight.
#[derive(Default)]
pub struct Flows {
    flows: IndexMap<i64, Flow>,
    next: i64,
    exchanges: Vec<Exchange>,
}

impl Flows {
    pub fn find(&self, number: i64) -> Option<&Flow> {
        self.flows.get(&number)
    }

    pub fn find_mut(&mut self, number: i64) -> Option<&mut Flow> {
        self.flows.get_mut(&number)
    }
}

fn is_flow_name(name: &str) -> bool {
    (1..=32).contains(&name.len())
        && name
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-' || b == b'_')
}

fn https(text: &str, place: &str) -> Result<url::Url, String> {
    let url =
        url::Url::parse(text).map_err(|e| format!("bad argument '{place}' (not a URL: {e})"))?;
    if url.scheme() != "https" || url.host_str().is_none_or(str::is_empty) {
        return Err(format!(
            "bad argument '{place}' (an https:// URL expected, got \"{text}\")"
        ));
    }
    Ok(url)
}

fn token() -> String {
    let mut bytes = [0u8; TOKEN_BYTES];
    getrandom::fill(&mut bytes).expect("the system has randomness");
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}

fn escape(text: &str) -> String {
    text.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

/// A small page for the browser that came back.
fn page(status: u16, text: &str, more: &str) -> WebAnswer {
    let html = format!(
        "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>Sign-in</title>\
         <body style=\"font-family:system-ui,sans-serif;max-width:32em;margin:4em auto;padding:0 1em\"><p>{}</p>{more}</body>",
        escape(text)
    );
    WebAnswer {
        status,
        headers: vec![
            ("content-type".into(), "text/html; charset=utf-8".into()),
            ("referrer-policy".into(), "no-referrer".into()),
        ],
        body: html.into_bytes(),
    }
}

/// The flows' work that reaches scripts and the network.
pub struct FlowsContext {
    shared: Rc<Shared>,
    scripts: Scripts,
}

impl FlowsContext {
    pub fn of(web: &WebContext) -> FlowsContext {
        FlowsContext {
            shared: web.shared.clone(),
            scripts: web.scripts.clone(),
        }
    }

    pub fn new(shared: Rc<Shared>, scripts: Scripts) -> FlowsContext {
        FlowsContext { shared, scripts }
    }

    /// Makes a flow for `scope` of package `namespace` from `options`, checked (errors at the script's line).
    /// `check` holds the token endpoint's host to the package's declaration; `declared` its redirects'.
    pub fn create(
        &self,
        scope: ScopeId,
        namespace: &str,
        options: shapes::OAuthOptions,
        declared: Declared,
        check: impl FnOnce(&str) -> Result<(), String>,
    ) -> Result<handles::OAuthFlow, String> {
        if !is_flow_name(&options.name) {
            return Err(format!(
                "bad argument 'options.name' (lowercase letters, digits, - and _ expected, got \"{}\")",
                options.name
            ));
        }
        https(&options.authorize_url, "options.authorize_url")?;
        let token_url = https(&options.token_url, "options.token_url")?;
        check(&crate::http::fetch::host_text(&token_url).unwrap_or_default())?;
        if options.client_id.trim().is_empty() {
            return Err("bad argument 'options.client_id' (it's empty)".into());
        }
        let mut web = self.shared.web.borrow_mut();
        if web
            .flows
            .flows
            .values()
            .any(|f| f.namespace == namespace && f.options.name == options.name)
        {
            return Err(format!(
                "package \"{namespace}\" already has a login flow named \"{}\"",
                options.name
            ));
        }
        let number = web.flows.next + 1;
        let route = web.add(
            namespace,
            "GET",
            &callback_path(&options.name),
            None,
            Answerer::Login {
                flow: number,
                confirm: false,
            },
        )?;
        let confirm_route = match web.add(
            namespace,
            "POST",
            &confirm_path(&options.name),
            None,
            Answerer::Login {
                flow: number,
                confirm: true,
            },
        ) {
            Ok(route) => route,
            Err(e) => {
                web.remove(route);
                return Err(e);
            }
        };
        web.flows.next = number;
        web.flows.flows.insert(
            number,
            Flow {
                number,
                scope,
                namespace: namespace.to_owned(),
                options,
                declared,
                route,
                confirm_route,
                logins: IndexMap::new(),
                confirmations: IndexMap::new(),
                test_token: None,
            },
        );
        Ok(handles::OAuthFlow { number })
    }

    /// The address to send `player` to, for a new login of `number`; none while web routes are off (or the flow has
    /// gone).
    pub fn begin(&self, number: i64, player: handles::Player) -> Option<String> {
        let name = parse_uuid(&player.id)
            .and_then(|uuid| self.shared.platform.players().get(uuid))
            .map_or_else(|| "this player".to_owned(), |p| p.name);
        let now = self.scripts.tick_count();
        let mut web = self.shared.web.borrow_mut();
        let (namespace, flow_name) = {
            let flow = web.flows.flows.get(&number)?;
            (flow.namespace.clone(), flow.options.name.clone())
        };
        let redirect = web.url(&namespace, &callback_path(&flow_name), &BTreeMap::new())?;
        let flow = web.flows.flows.get_mut(&number)?;
        flow.logins.retain(|_, l| l.expires > now);
        while flow.logins.len() >= MAX_LOGINS {
            flow.logins.shift_remove_index(0);
        }
        let (state, verifier) = (token(), token());
        let challenge = base64::engine::general_purpose::URL_SAFE_NO_PAD
            .encode(sha2::Sha256::digest(verifier.as_bytes()));
        flow.logins.insert(
            state.clone(),
            Login {
                player,
                name,
                verifier,
                expires: now + LOGIN_TICKS,
            },
        );
        let options = &flow.options;
        let mut query: BTreeMap<String, String> = options.parameters.clone().unwrap_or_default();
        query.insert("response_type".into(), "code".into());
        query.insert("client_id".into(), options.client_id.clone());
        query.insert("redirect_uri".into(), redirect);
        if let Some(scopes) = options.scopes.as_ref().filter(|s| !s.is_empty()) {
            query.insert("scope".into(), scopes.join(" "));
        }
        query.insert("state".into(), state);
        query.insert("code_challenge".into(), challenge);
        query.insert("code_challenge_method".into(), "S256".into());
        let base = &options.authorize_url;
        Some(format!(
            "{base}{}{}",
            if base.contains('?') { "&" } else { "?" },
            path::encode_query(&query)
        ))
    }

    /// Ends flow `number`: its routes go and logins under way are forgotten. False when it had already gone.
    pub fn remove(&self, number: i64) -> bool {
        let removed = {
            let mut web = self.shared.web.borrow_mut();
            let Some(flow) = web.flows.flows.shift_remove(&number) else {
                return false;
            };
            web.remove(flow.route);
            web.remove(flow.confirm_route);
            true
        };
        if removed
            && let Ok(target) = netherforge_api::handle::push_any(
                self.scripts.host().lua(),
                &Handle::OAuthFlow(handles::OAuthFlow { number }),
            )
        {
            self.scripts.drop_target(&Target::Handle(target));
        }
        removed
    }

    pub fn release(&self, scopes: &[ScopeId]) {
        let doomed: Vec<i64> = self
            .shared
            .web
            .borrow()
            .flows
            .flows
            .values()
            .filter(|f| scopes.contains(&f.scope))
            .map(|f| f.number)
            .collect();
        for number in doomed {
            self.remove(number);
        }
    }

    pub fn release_all(&self) {
        let all: Vec<i64> = self
            .shared
            .web
            .borrow()
            .flows
            .flows
            .keys()
            .copied()
            .collect();
        for number in all {
            self.remove(number);
        }
        self.shared.web.borrow_mut().flows.exchanges.clear();
    }

    /// A flow's route was asked: its callback or its confirmation.
    pub fn answer(&self, flow: i64, confirm: bool, waiting: i64) {
        if confirm {
            self.confirm(flow, waiting);
        } else {
            self.callback(flow, waiting);
        }
    }

    fn finish(&self, waiting: i64, answer: WebAnswer) {
        self.shared.web.borrow_mut().finish(waiting, answer);
    }

    fn callback(&self, number: i64, waiting: i64) {
        let now = self.scripts.tick_count();
        let mut web = self.shared.web.borrow_mut();
        let Some(call) = web.waiting.get(&waiting).map(|w| w.call.clone()) else {
            return;
        };
        let query = path::query(call.raw_query.as_deref());
        let login = web.flows.flows.get_mut(&number).and_then(|flow| {
            query
                .get("state")
                .and_then(|state| flow.logins.shift_remove(state))
        });
        let Some(login) = login else {
            web.finish(
                waiting,
                page(
                    400,
                    "This sign-in link has expired or was already used. Start again from the game.",
                    "",
                ),
            );
            return;
        };
        let failure = if login.expires <= now {
            Some("the login expired before it finished".to_owned())
        } else if let Some(error) = query.get("error") {
            Some(format!(
                "the service said {error}{}",
                query
                    .get("error_description")
                    .map(|d| format!(": {d}"))
                    .unwrap_or_default()
            ))
        } else if query.get("code").is_none_or(String::is_empty) {
            Some("the service sent no code".to_owned())
        } else {
            None
        };
        drop(web);
        if let Some(why) = failure {
            self.failed(number, &login, waiting, &why);
            return;
        }
        let confirm = token();
        let mut web = self.shared.web.borrow_mut();
        let Some(flow) = web.flows.flows.get_mut(&number) else {
            return;
        };
        while flow.confirmations.len() >= MAX_LOGINS {
            flow.confirmations.shift_remove_index(0);
        }
        let action = format!("/{}{}", flow.namespace, confirm_path(&flow.options.name));
        flow.confirmations.insert(
            confirm.clone(),
            Confirmation {
                login: login.clone(),
                code: query.get("code").cloned().unwrap_or_default(),
            },
        );
        let form = format!(
            "<form method=\"post\" action=\"{}\"><input type=\"hidden\" name=\"token\" value=\"{}\">\
             <button type=\"submit\">Sign in as {}</button></form>",
            escape(&action),
            escape(&confirm),
            escape(&login.name)
        );
        let text = format!(
            "This signs your account in for the Minecraft player {0}. Only continue if you are {0} and you started \
             this sign-in in the game.",
            login.name
        );
        web.finish(waiting, page(200, &text, &form));
    }

    /// The browser confirmed the player: the code is traded for a token.
    fn confirm(&self, number: i64, waiting: i64) {
        let now = self.scripts.tick_count();
        let mut web = self.shared.web.borrow_mut();
        let Some(call) = web.waiting.get(&waiting).map(|w| w.call.clone()) else {
            return;
        };
        let form = path::query(Some(&String::from_utf8_lossy(&call.body)));
        let confirmation = web.flows.flows.get_mut(&number).and_then(|flow| {
            form.get("token")
                .and_then(|t| flow.confirmations.shift_remove(t))
        });
        let Some(confirmation) = confirmation.filter(|c| c.login.expires > now) else {
            web.finish(
                waiting,
                page(
                    400,
                    "This sign-in has expired or was already used. Start again from the game.",
                    "",
                ),
            );
            return;
        };
        let Some(flow) = web.flows.flows.get(&number) else {
            return;
        };
        let redirect = web
            .url(
                &flow.namespace,
                &callback_path(&flow.options.name),
                &BTreeMap::new(),
            )
            .unwrap_or_else(|| {
                format!(
                    "http://localhost/{}{}",
                    flow.namespace,
                    callback_path(&flow.options.name)
                )
            });
        let call = token_request(
            &flow.options,
            &confirmation.code,
            &confirmation.login.verifier,
            &redirect,
        );
        let declared = flow.declared.clone();
        let test_token = flow.test_token.clone();
        drop(web);
        // In a test run the token endpoint isn't called: the test's answer (or a default token) stands in.
        if self.shared.tests.borrow().harness.is_some() {
            let (send, reply) = mpsc::channel();
            let _ = send.send(Ok(test_token.unwrap_or_else(
                || serde_json::json!({ "access_token": "test-token", "token_type": "Bearer" }),
            )));
            self.shared.web.borrow_mut().flows.exchanges.push(Exchange {
                flow: number,
                login: confirmation.login,
                waiting,
                reply,
            });
            return;
        }
        let (fetcher, workers) = {
            let mut http = self.shared.http.borrow_mut();
            (http.fetcher(), http.workers())
        };
        let (send, reply) = mpsc::channel();
        match call {
            Ok(call) => workers.submit(move || {
                let answer = fetcher.fetch(call, &[], &declared).and_then(|reply| {
                    match serde_json::from_str::<serde_json::Value>(&reply.body) {
                        Ok(value @ serde_json::Value::Object(_)) => Ok(value),
                        _ => Err(format!(
                            "the token endpoint answered {} with something that isn't a JSON object",
                            reply.status
                        )),
                    }
                });
                let _ = send.send(answer);
            }),
            Err(why) => {
                let _ = send.send(Err(why));
            }
        }
        self.shared.web.borrow_mut().flows.exchanges.push(Exchange {
            flow: number,
            login: confirmation.login,
            waiting,
            reply,
        });
    }

    /// Token requests that have answered: the flow's `complete` or `fail`, and the browser's page.
    pub fn tick(&self) {
        let done: Vec<(Exchange, Result<serde_json::Value, String>)> = {
            let mut web = self.shared.web.borrow_mut();
            let mut done = Vec::new();
            let mut left = Vec::new();
            for exchange in web.flows.exchanges.drain(..) {
                match exchange.reply.try_recv() {
                    Ok(answer) => done.push((exchange, answer)),
                    Err(mpsc::TryRecvError::Empty) => left.push(exchange),
                    Err(mpsc::TryRecvError::Disconnected) => {
                        done.push((exchange, Err("the token request was stopped".into())));
                    }
                }
            }
            web.flows.exchanges = left;
            done
        };
        for (exchange, answer) in done {
            self.exchanged(exchange, answer);
        }
    }

    fn exchanged(&self, exchange: Exchange, answer: Result<serde_json::Value, String>) {
        if self.shared.web.borrow().flows.find(exchange.flow).is_none() {
            self.finish(
                exchange.waiting,
                page(503, "Sign-in isn't available right now.", ""),
            );
            return;
        }
        let token = match answer {
            Err(why) => return self.failed(exchange.flow, &exchange.login, exchange.waiting, &why),
            Ok(token) => token,
        };
        let string = |key: &str| token.get(key).and_then(|v| v.as_str()).map(str::to_owned);
        let Some(access) = string("access_token") else {
            let why = string("error_description")
                .or_else(|| string("error"))
                .unwrap_or_else(|| "no access token".into());
            return self.failed(
                exchange.flow,
                &exchange.login,
                exchange.waiting,
                &format!("the service gave no token: {why}"),
            );
        };
        self.finish(
            exchange.waiting,
            page(
                200,
                "Signed in. You can close this tab and go back to the game.",
                "",
            ),
        );
        let expires_in = token.get("expires_in").and_then(|v| {
            v.as_i64()
                .or_else(|| v.as_str().and_then(|s| s.parse().ok()))
        });
        let flow = handles::OAuthFlow {
            number: exchange.flow,
        };
        let payload = shapes::OAuthCompleteEvent {
            flow: flow.clone(),
            player: exchange.login.player.clone(),
            access_token: access,
            token_type: string("token_type"),
            refresh_token: string("refresh_token"),
            expires_in,
            scope: string("scope"),
            id_token: string("id_token"),
        };
        let lua = self.scripts.host().lua().clone();
        if let Ok(Value::Table(fields)) = codecs::OAuthCompleteEvent.push(&lua, payload) {
            self.raise(&flow, "complete", fields);
        }
    }

    fn failed(&self, number: i64, login: &Login, waiting: i64, why: &str) {
        self.finish(
            waiting,
            page(
                400,
                &format!("Signing in didn't work: {why}. Start again from the game."),
                "",
            ),
        );
        let flow = handles::OAuthFlow { number };
        let payload = shapes::OAuthFailEvent {
            flow: flow.clone(),
            player: login.player.clone(),
            error: why.to_owned(),
        };
        let lua = self.scripts.host().lua().clone();
        if let Ok(Value::Table(fields)) = codecs::OAuthFailEvent.push(&lua, payload) {
            self.raise(&flow, "fail", fields);
        }
    }

    fn raise(&self, flow: &handles::OAuthFlow, event: &str, fields: mlua::Table) {
        if let Ok(target) = netherforge_api::handle::push_any(
            self.scripts.host().lua(),
            &Handle::OAuthFlow(flow.clone()),
        ) {
            self.scripts.raise(&Target::Handle(target), event, fields);
        }
    }
}

fn token_request(
    options: &shapes::OAuthOptions,
    code: &str,
    verifier: &str,
    redirect: &str,
) -> Result<HttpCall, String> {
    let url = url::Url::parse(&options.token_url).map_err(|e| e.to_string())?;
    let mut form: BTreeMap<String, String> = BTreeMap::from([
        ("grant_type".into(), "authorization_code".into()),
        ("code".into(), code.into()),
        ("redirect_uri".into(), redirect.into()),
        ("code_verifier".into(), verifier.into()),
    ]);
    let mut headers = BTreeMap::from([
        (
            "Content-Type".to_owned(),
            "application/x-www-form-urlencoded".to_owned(),
        ),
        ("Accept".to_owned(), "application/json".to_owned()),
    ]);
    match (&options.client_auth, &options.client_secret) {
        (Some(auth), Some(secret)) if auth == "basic" => {
            let pair = format!(
                "{}:{}",
                path::encode(&options.client_id),
                path::encode(secret)
            );
            headers.insert(
                "Authorization".into(),
                format!(
                    "Basic {}",
                    base64::engine::general_purpose::STANDARD.encode(pair)
                ),
            );
        }
        (_, secret) => {
            form.insert("client_id".into(), options.client_id.clone());
            if let Some(secret) = secret {
                form.insert("client_secret".into(), secret.clone());
            }
        }
    }
    Ok(HttpCall {
        method: "POST".into(),
        url,
        headers,
        body: Some(path::encode_query(&form).into_bytes()),
    })
}
