//! `nf.web`, `Route`, `WebResponse` and `OAuthFlow` (Kotlin's `NfWebImpl`). The `web` requirement is the generated
//! binding's; what's here holds each call to the calling scope's package.

use std::collections::BTreeMap;
use std::sync::Arc;

use base64::Engine as _;
use mlua::{Function, Value};
use netherforge_api::api::{NfWebApi, OAuthFlowApi, RouteApi, WebResponseApi};
use netherforge_api::handles::{self, Handle};
use netherforge_api::{ApiResult, Caller, shapes};
use netherforge_format::requirement::Requirement;

use super::gate::WebAnswer;
use super::oauth::FlowsContext;
use super::path::RoutePath;
use super::{Answerer, err};
use crate::Grants;
use crate::world::GameContext;

const REDIRECTS: &[i64] = &[301, 302, 303, 307, 308];
const SERVER_SET: &[&str] = &[
    "content-length",
    "transfer-encoding",
    "connection",
    "date",
    "keep-alive",
    "upgrade",
    "te",
    "trailer",
];

#[derive(Clone)]
pub struct WebApi {
    pub game: GameContext,
    pub grants: Grants,
}

impl WebApi {
    fn namespace(&self, scope: netherforge_luau::ScopeId) -> String {
        self.game
            .scripts
            .namespace(scope)
            .unwrap_or_else(|| self.grants.home.clone())
    }

    fn flows(&self) -> FlowsContext {
        FlowsContext::new(self.game.shared.clone(), self.game.scripts.clone())
    }

    fn status(status: i64) -> ApiResult<u16> {
        if !(100..=599).contains(&status) {
            return Err(err(format!(
                "bad argument 'status' (100 to 599 expected, got {status})"
            )));
        }
        Ok(status as u16)
    }

    fn sized(&self, bytes: Vec<u8>) -> ApiResult<Vec<u8>> {
        let most = self.game.shared.web.borrow().config.max_response_bytes;
        if bytes.len() > most {
            return Err(err(format!(
                "the body is {} bytes, over the server's limit of {most} (web.max-response-bytes in config.yml)",
                bytes.len()
            )));
        }
        Ok(bytes)
    }

    fn answer(&self, this: &handles::WebResponse, answer: WebAnswer) -> bool {
        let mut headers = answer.headers;
        headers.retain(|(n, _)| n != "x-content-type-options");
        headers.push(("x-content-type-options".into(), "nosniff".into()));
        if !headers.iter().any(|(n, _)| n == "cache-control") {
            headers.push(("cache-control".into(), "no-store".into()));
        }
        self.game.shared.web.borrow_mut().finish(
            this.number,
            WebAnswer {
                status: answer.status,
                headers,
                body: answer.body,
            },
        )
    }
}

/// Headers a script answers with: tokens, printable ASCII, none the server sets, none twice; by lowercase name.
fn checked_headers(given: BTreeMap<String, String>) -> ApiResult<Vec<(String, String)>> {
    let mut out: Vec<(String, String)> = Vec::new();
    for (name, value) in given {
        let lower = name.to_lowercase();
        if !crate::http::api::is_token(&name) {
            return Err(err(format!(
                "bad argument 'headers' (\"{name}\" isn't a header name)"
            )));
        }
        if SERVER_SET.contains(&lower.as_str()) {
            return Err(err(format!(
                "bad argument 'headers' (\"{name}\" is set by the server)"
            )));
        }
        if value
            .chars()
            .any(|c| c as u32 > 0x7e || ((c as u32) < 0x20 && c != '\t'))
        {
            return Err(err(format!(
                "bad argument 'headers' (\"{name}\" has a character a header can't hold: only printable ASCII and tabs)"
            )));
        }
        if out.iter().any(|(n, _)| *n == lower) {
            return Err(err(format!(
                "bad argument 'headers' (\"{name}\" twice: names are compared without case)"
            )));
        }
        out.push((lower, value));
    }
    Ok(out)
}

impl NfWebApi for WebApi {
    fn route(
        &self,
        caller: &Caller,
        method: String,
        path: String,
        handler: Function,
    ) -> ApiResult<handles::Route> {
        let namespace = self.namespace(caller.scope);
        let number = self
            .game
            .shared
            .web
            .borrow_mut()
            .add(
                &namespace,
                &method,
                &path,
                Some(caller.scope),
                Answerer::Script(handler),
            )
            .map_err(err)?;
        Ok(handles::Route { number })
    }

    fn url(
        &self,
        caller: &Caller,
        path: Option<String>,
        query: Option<BTreeMap<String, String>>,
    ) -> ApiResult<Option<String>> {
        let place = path.unwrap_or_else(|| "/".into());
        RoutePath::parse(&place)
            .map_err(|why| err(format!("bad argument 'path' ({why}, got \"{place}\")")))?;
        let namespace = self.namespace(caller.scope);
        Ok(self
            .game
            .shared
            .web
            .borrow()
            .url(&namespace, &place, &query.unwrap_or_default()))
    }

    fn verify_signature(
        &self,
        _: &Caller,
        body: String,
        signature: String,
        options: shapes::SignatureOptions,
    ) -> ApiResult<bool> {
        let trimmed = signature.trim();
        let prefix = options.prefix.as_deref().unwrap_or_default();
        let Some(given) = trimmed.strip_prefix(prefix) else {
            return Ok(false);
        };
        let expected = crate::encoding::hmac(&body, &options.secret, options.algorithm.as_deref());
        let bytes = if options.encoding.as_deref() == Some("base64") {
            base64::engine::general_purpose::STANDARD.decode(given).ok()
        } else {
            hex(&given.to_lowercase())
        };
        let Some(bytes) = bytes else { return Ok(false) };
        // Constant time: how much of it matched says nothing.
        Ok(bytes.len() == expected.len()
            && bytes
                .iter()
                .zip(&expected)
                .fold(0u8, |acc, (a, b)| acc | (a ^ b))
                == 0)
    }

    fn oauth(
        &self,
        caller: &Caller,
        options: shapes::OAuthOptions,
    ) -> ApiResult<handles::OAuthFlow> {
        let namespace = self.namespace(caller.scope);
        let grants = self.grants.clone();
        let bound = namespace.clone();
        let declared: crate::http::fetch::Declared = Arc::new(move |host: &str| {
            grants
                .check(
                    Some(&bound),
                    &Requirement::Http(Some(host.to_owned())),
                    "nf.web.oauth",
                )
                .map_err(|e| e.0)
        });
        let grants = &self.grants;
        self.flows()
            .create(caller.scope, &namespace, options, declared, |host| {
                grants
                    .check(
                        Some(&namespace),
                        &Requirement::Http(Some(host.to_owned())),
                        "nf.web.oauth",
                    )
                    .map_err(|e| e.0)
            })
            .map_err(err)
    }
}

fn hex(text: &str) -> Option<Vec<u8>> {
    if !text.len().is_multiple_of(2) {
        return None;
    }
    (0..text.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(text.get(i..i + 2)?, 16).ok())
        .collect()
}

impl RouteApi for WebApi {
    fn remove(&self, this: &handles::Route) -> ApiResult<bool> {
        let removed = self.game.shared.web.borrow_mut().remove(this.number);
        Ok(removed)
    }
    fn exists(&self, this: &handles::Route) -> ApiResult<bool> {
        Ok(self
            .game
            .shared
            .web
            .borrow()
            .routes
            .contains_key(&this.number))
    }
}

impl WebResponseApi for WebApi {
    fn send(
        &self,
        this: &handles::WebResponse,
        status: i64,
        body: Option<String>,
        headers: Option<BTreeMap<String, String>>,
    ) -> ApiResult<bool> {
        let code = WebApi::status(status)?;
        let bytes = self.sized(body.unwrap_or_default().into_bytes())?;
        let given = checked_headers(headers.unwrap_or_default())?;
        let mut all = Vec::new();
        if !given.iter().any(|(n, _)| n == "content-type") {
            all.push(("content-type".into(), "text/plain; charset=utf-8".into()));
        }
        all.extend(given);
        Ok(self.answer(
            this,
            WebAnswer {
                status: code,
                headers: all,
                body: bytes,
            },
        ))
    }

    fn json(&self, this: &handles::WebResponse, status: i64, value: Value) -> ApiResult<bool> {
        let code = WebApi::status(status)?;
        let host = self.game.scripts.host();
        let encoded = netherforge_luau::json::encode(
            &host,
            &value,
            "value",
            "encoded",
            &netherforge_api::handle::SavedHandles,
        )
        .map_err(|e| err(format!("{e:?}")))?;
        if let Some(problem) = encoded.problems.into_iter().next() {
            return Err(err(format!("bad argument 'value' ({problem})")));
        }
        let text = encoded
            .text
            .ok_or_else(|| err("bad argument 'value' (it can't be encoded)"))?;
        let bytes = self.sized(text.into_bytes())?;
        Ok(self.answer(
            this,
            WebAnswer {
                status: code,
                headers: vec![("content-type".into(), "application/json".into())],
                body: bytes,
            },
        ))
    }

    fn redirect(
        &self,
        this: &handles::WebResponse,
        url: String,
        status: Option<i64>,
    ) -> ApiResult<bool> {
        let code = status.unwrap_or(302);
        if !REDIRECTS.contains(&code) {
            return Err(err(format!(
                "bad argument 'status' (301, 302, 303, 307 or 308 expected, got {code})"
            )));
        }
        let absolute = url.starts_with("https://") || url.starts_with("http://");
        if (!absolute && !url.starts_with('/'))
            || url.starts_with("//")
            || url.chars().any(|c| (c as u32) < 0x20 || c as u32 == 0x7f)
        {
            return Err(err(format!(
                "bad argument 'url' (an http:// or https:// URL, or a path starting with one /, expected, got \"{url}\")"
            )));
        }
        Ok(self.answer(
            this,
            WebAnswer {
                status: code as u16,
                headers: vec![("location".into(), url)],
                body: Vec::new(),
            },
        ))
    }

    fn is_pending(&self, this: &handles::WebResponse) -> ApiResult<bool> {
        Ok(self
            .game
            .shared
            .web
            .borrow()
            .waiting
            .contains_key(&this.number))
    }
}

impl OAuthFlowApi for WebApi {
    fn begin(
        &self,
        this: &handles::OAuthFlow,
        player: handles::Player,
    ) -> ApiResult<Option<String>> {
        Ok(self.flows().begin(this.number, player))
    }
    fn remove(&self, this: &handles::OAuthFlow) -> ApiResult<bool> {
        Ok(self.flows().remove(this.number))
    }
    fn on(
        &self,
        this: &handles::OAuthFlow,
        event: String,
        handler: Function,
        options: Option<shapes::EventOptions>,
    ) -> ApiResult<handles::Subscription> {
        self.game.subscribe_on(
            Handle::OAuthFlow(this.clone()),
            "OAuthFlow:on",
            event,
            handler,
            options,
            false,
        )
    }
    fn once(
        &self,
        this: &handles::OAuthFlow,
        event: String,
        handler: Function,
    ) -> ApiResult<handles::Subscription> {
        self.game.subscribe_on(
            Handle::OAuthFlow(this.clone()),
            "OAuthFlow:once",
            event,
            handler,
            None,
            true,
        )
    }
}
