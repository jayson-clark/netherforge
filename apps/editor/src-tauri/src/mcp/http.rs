//! The HTTP side of MCP's Streamable HTTP transport, the stateless subset:
//! every message is a `POST /mcp` answered with one JSON body (no SSE stream,
//! no sessions, nothing pushed from the server), which is all the tools need.
//! What's in the body is the UI's MCP server's business ([Forward]); this only
//! decides who may send one and frames the answer: a request's response is the
//! reply, a body with no requests in it is `202 Accepted`.
//!
//! It listens on 127.0.0.1 only, and refuses what a web page could send: a
//! `Host` that isn't loopback (DNS rebinding), an `Origin` that isn't loopback,
//! and anything but `application/json` (a "simple" cross-site POST would be
//! `text/plain`; a JSON one needs a CORS preflight, which gets no CORS headers
//! back). Past those, every request needs the install's bearer token
//! ([super::token]): `401` without it.

use std::convert::Infallible;
use std::sync::Arc;
use std::time::Duration;

use bytes::Bytes;
use futures_util::future::join_all;
use http_body_util::{BodyExt, Full, Limited};
use hyper::body::Incoming;
use hyper::header::{self, HeaderMap, HeaderValue};
use hyper::{Method, Request, Response, StatusCode};
use hyper_util::rt::TokioIo;
use serde_json::{Value, json};
use tokio::net::TcpListener;

use super::Forward;
use super::token::authorizes;

pub const PATH: &str = "/mcp";
const MAX_BODY: usize = 4 * 1024 * 1024;

/// Accepts connections until the task is aborted.
pub async fn serve<F: Forward>(listener: TcpListener, pipe: Arc<F>, token: Arc<str>) {
    let port = listener.local_addr().map(|a| a.port()).unwrap_or(0);
    loop {
        let stream = match listener.accept().await {
            Ok((stream, _)) => stream,
            // Out of file descriptors and the like: don't spin.
            Err(_) => {
                tokio::time::sleep(Duration::from_millis(100)).await;
                continue;
            }
        };
        let pipe = pipe.clone();
        let token = token.clone();
        tokio::spawn(async move {
            let service = hyper::service::service_fn(move |request| {
                let pipe = pipe.clone();
                let token = token.clone();
                async move { Ok::<_, Infallible>(respond(request, port, &*pipe, &token).await) }
            });
            let _ = hyper::server::conn::http1::Builder::new()
                .serve_connection(TokioIo::new(stream), service)
                .await;
        });
    }
}

type Reply = Response<Full<Bytes>>;

fn reply(status: StatusCode, body: impl Into<Bytes>, content_type: Option<&'static str>) -> Reply {
    let mut response = Response::new(Full::new(body.into()));
    *response.status_mut() = status;
    if let Some(content_type) = content_type {
        response
            .headers_mut()
            .insert(header::CONTENT_TYPE, HeaderValue::from_static(content_type));
    }
    response
}

fn text(status: StatusCode, message: &'static str) -> Reply {
    reply(status, message, Some("text/plain; charset=utf-8"))
}

fn json_reply(status: StatusCode, value: &Value) -> Reply {
    reply(status, value.to_string(), Some("application/json"))
}

/// A JSON-RPC error about the body itself, which has no id to answer to.
fn body_error(code: i64, message: String) -> Reply {
    json_reply(
        StatusCode::BAD_REQUEST,
        &json!({ "jsonrpc": "2.0", "id": null, "error": { "code": code, "message": message } }),
    )
}

/// `localhost`, `127.0.0.1` or `[::1]`, with this port or none.
fn is_loopback_authority(authority: &str, port: Option<u16>) -> bool {
    let authority = authority.trim().to_ascii_lowercase();
    let (name, given_port) = match authority.rsplit_once(':') {
        // `[::1]` alone has colons but no port after the bracket.
        Some((name, p)) if !p.ends_with(']') => (name.to_string(), Some(p.to_string())),
        _ => (authority.clone(), None),
    };
    let loopback = matches!(name.as_str(), "localhost" | "127.0.0.1" | "[::1]");
    let port_ok = match (given_port, port) {
        (None, _) | (_, None) => true,
        (Some(given), Some(port)) => given == port.to_string(),
    };
    loopback && port_ok
}

fn header_str(headers: &HeaderMap, name: header::HeaderName) -> Option<&str> {
    headers.get(name).and_then(|v| v.to_str().ok())
}

/// Why a request is refused before it's read, or None.
pub fn refuse(
    method: &Method,
    path: &str,
    headers: &HeaderMap,
    port: u16,
    token: &str,
) -> Option<Reply> {
    if path != PATH {
        return Some(text(
            StatusCode::NOT_FOUND,
            "Not found. The MCP endpoint is /mcp.",
        ));
    }
    match header_str(headers, header::HOST) {
        Some(host) if is_loopback_authority(host, Some(port)) => {}
        _ => {
            return Some(text(
                StatusCode::FORBIDDEN,
                "Only loopback hosts may connect.",
            ));
        }
    }
    if let Some(origin) = header_str(headers, header::ORIGIN) {
        let authority = origin
            .strip_prefix("http://")
            .or_else(|| origin.strip_prefix("https://"));
        // Any port: the page's own, not ours.
        if !authority.is_some_and(|a| is_loopback_authority(a, None)) {
            return Some(text(
                StatusCode::FORBIDDEN,
                "Cross-origin requests aren't allowed.",
            ));
        }
    }
    if !authorizes(header_str(headers, header::AUTHORIZATION), token) {
        let mut response = text(
            StatusCode::UNAUTHORIZED,
            "Send the NetherForge editor's token as Authorization: Bearer <token>. Settings → Agents shows it, and the .mcp.json the editor writes has it.",
        );
        response
            .headers_mut()
            .insert(header::WWW_AUTHENTICATE, HeaderValue::from_static("Bearer"));
        return Some(response);
    }
    if method != Method::POST {
        let mut response = text(
            StatusCode::METHOD_NOT_ALLOWED,
            "Send JSON-RPC with POST. This server doesn't open event streams.",
        );
        response
            .headers_mut()
            .insert(header::ALLOW, HeaderValue::from_static("POST"));
        return Some(response);
    }
    let json_body = header_str(headers, header::CONTENT_TYPE).is_some_and(|ct| {
        ct.trim()
            .to_ascii_lowercase()
            .starts_with("application/json")
    });
    if !json_body {
        return Some(text(
            StatusCode::UNSUPPORTED_MEDIA_TYPE,
            "Expected Content-Type: application/json.",
        ));
    }
    None
}

/// The body of an accepted request (one JSON-RPC message, or a batch of them)
/// → the HTTP reply.
pub async fn answer<F: Forward>(body: &[u8], protocol_version: Option<String>, pipe: &F) -> Reply {
    let message: Value = match serde_json::from_slice(body) {
        Ok(message) => message,
        Err(error) => return body_error(-32700, format!("Invalid JSON: {error}")),
    };
    let response = match message {
        Value::Array(batch) if batch.is_empty() => {
            return body_error(-32600, "Empty batch".into());
        }
        Value::Array(batch) => {
            let answers = join_all(
                batch
                    .into_iter()
                    .map(|message| pipe.forward(message, protocol_version.clone())),
            )
            .await;
            let answers: Vec<Value> = answers.into_iter().flatten().collect();
            (!answers.is_empty()).then_some(Value::Array(answers))
        }
        message => pipe.forward(message, protocol_version).await,
    };
    match response {
        Some(response) => json_reply(StatusCode::OK, &response),
        None => reply(StatusCode::ACCEPTED, Bytes::new(), None),
    }
}

async fn respond<F: Forward>(
    request: Request<Incoming>,
    port: u16,
    pipe: &F,
    token: &str,
) -> Reply {
    if let Some(refusal) = refuse(
        request.method(),
        request.uri().path(),
        request.headers(),
        port,
        token,
    ) {
        return refusal;
    }
    let protocol_version = request
        .headers()
        .get("mcp-protocol-version")
        .and_then(|v| v.to_str().ok())
        .map(String::from);
    let body = match Limited::new(request.into_body(), MAX_BODY).collect().await {
        Ok(body) => body.to_bytes(),
        Err(_) => return text(StatusCode::PAYLOAD_TOO_LARGE, "Request too large."),
    };
    answer(&body, protocol_version, pipe).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    /// Answers requests with their method; records what it was given.
    #[derive(Default)]
    struct Echo(Mutex<Vec<(Value, Option<String>)>>);

    impl Forward for Echo {
        async fn forward(&self, message: Value, protocol_version: Option<String>) -> Option<Value> {
            self.0
                .lock()
                .unwrap()
                .push((message.clone(), protocol_version));
            let id = message.get("id")?.clone();
            Some(json!({ "jsonrpc": "2.0", "id": id, "result": { "method": message["method"] } }))
        }
    }

    const TOKEN: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    const BEARER: &str = "Bearer 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    fn headers(pairs: &[(&'static str, &'static str)]) -> HeaderMap {
        let mut map = HeaderMap::new();
        for (name, value) in pairs {
            map.insert(*name, HeaderValue::from_static(value));
        }
        map
    }

    const OK: &[(&str, &str)] = &[
        ("host", "127.0.0.1:4000"),
        ("content-type", "application/json"),
        ("authorization", BEARER),
    ];

    fn status(
        method: Method,
        path: &str,
        pairs: &[(&'static str, &'static str)],
    ) -> Option<StatusCode> {
        refuse(&method, path, &headers(pairs), 4000, TOKEN).map(|r| r.status())
    }

    #[test]
    fn accepts_a_local_json_post() {
        assert_eq!(status(Method::POST, "/mcp", OK), None);
        for host in [
            "localhost:4000",
            "127.0.0.1",
            "[::1]:4000",
            "LOCALHOST:4000",
        ] {
            let pairs = [
                ("host", host),
                ("content-type", "application/json; charset=utf-8"),
                OK[2],
            ];
            assert_eq!(status(Method::POST, "/mcp", &pairs), None, "{host}");
        }
        let local_page = [OK[0], OK[1], OK[2], ("origin", "http://localhost:5173")];
        assert_eq!(status(Method::POST, "/mcp", &local_page), None);
    }

    #[test]
    fn needs_the_token() {
        let none = [OK[0], OK[1]];
        assert_eq!(
            status(Method::POST, "/mcp", &none),
            Some(StatusCode::UNAUTHORIZED)
        );
        let wrong = [OK[0], OK[1], ("authorization", "Bearer nope")];
        assert_eq!(
            status(Method::POST, "/mcp", &wrong),
            Some(StatusCode::UNAUTHORIZED)
        );
        // Before anything else is said about the request.
        assert_eq!(
            status(Method::GET, "/mcp", &none),
            Some(StatusCode::UNAUTHORIZED)
        );
        let reply = refuse(&Method::POST, "/mcp", &headers(&none), 4000, TOKEN).unwrap();
        assert_eq!(reply.headers()[header::WWW_AUTHENTICATE], "Bearer");
    }

    #[test]
    fn refuses_what_a_web_page_could_send() {
        let rebound = [
            ("host", "evil.example:4000"),
            ("content-type", "application/json"),
            OK[2],
        ];
        assert_eq!(
            status(Method::POST, "/mcp", &rebound),
            Some(StatusCode::FORBIDDEN)
        );
        let other_port = [
            ("host", "127.0.0.1:4001"),
            ("content-type", "application/json"),
            OK[2],
        ];
        assert_eq!(
            status(Method::POST, "/mcp", &other_port),
            Some(StatusCode::FORBIDDEN)
        );
        assert_eq!(
            status(
                Method::POST,
                "/mcp",
                &[("content-type", "application/json"), OK[2]]
            ),
            Some(StatusCode::FORBIDDEN),
            "no Host"
        );
        let cross = [OK[0], OK[1], OK[2], ("origin", "https://evil.example")];
        assert_eq!(
            status(Method::POST, "/mcp", &cross),
            Some(StatusCode::FORBIDDEN)
        );
        let tricky = [
            OK[0],
            OK[1],
            OK[2],
            ("origin", "http://localhost.evil.example"),
        ];
        assert_eq!(
            status(Method::POST, "/mcp", &tricky),
            Some(StatusCode::FORBIDDEN)
        );
        let simple = [OK[0], ("content-type", "text/plain"), OK[2]];
        assert_eq!(
            status(Method::POST, "/mcp", &simple),
            Some(StatusCode::UNSUPPORTED_MEDIA_TYPE)
        );
    }

    #[test]
    fn only_post_on_the_endpoint() {
        assert_eq!(status(Method::POST, "/", OK), Some(StatusCode::NOT_FOUND));
        assert_eq!(
            status(Method::GET, "/mcp", OK),
            Some(StatusCode::METHOD_NOT_ALLOWED)
        );
        assert_eq!(
            status(Method::OPTIONS, "/mcp", OK),
            Some(StatusCode::METHOD_NOT_ALLOWED)
        );
    }

    #[tokio::test]
    async fn frames_requests_notifications_and_batches() {
        let echo = Echo::default();
        let reply = answer(
            br#"{"jsonrpc":"2.0","id":1,"method":"ping"}"#,
            Some("2025-06-18".into()),
            &echo,
        )
        .await;
        assert_eq!(reply.status(), StatusCode::OK);
        assert_eq!(echo.0.lock().unwrap()[0].1.as_deref(), Some("2025-06-18"));

        let note = br#"{"jsonrpc":"2.0","method":"notifications/initialized"}"#;
        assert_eq!(
            answer(note, None, &echo).await.status(),
            StatusCode::ACCEPTED
        );

        let batch = br#"[{"jsonrpc":"2.0","id":1,"method":"a"},{"jsonrpc":"2.0","method":"n"},{"jsonrpc":"2.0","id":2,"method":"b"}]"#;
        let reply = answer(batch, None, &echo).await;
        assert_eq!(reply.status(), StatusCode::OK);
        let body = reply.into_body().collect().await.unwrap().to_bytes();
        let ids: Vec<Value> = serde_json::from_slice::<Value>(&body)
            .unwrap()
            .as_array()
            .unwrap()
            .iter()
            .map(|r| r["id"].clone())
            .collect();
        assert_eq!(ids, [json!(1), json!(2)]);

        assert_eq!(
            answer(b"{nope", None, &echo).await.status(),
            StatusCode::BAD_REQUEST
        );
        assert_eq!(
            answer(b"[]", None, &echo).await.status(),
            StatusCode::BAD_REQUEST
        );
    }

    async fn post(port: u16, authorization: Option<&str>, body: &str) -> String {
        let auth = authorization
            .map(|a| format!("Authorization: {a}\r\n"))
            .unwrap_or_default();
        let request = format!(
            "POST /mcp HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nContent-Type: application/json\r\n{auth}\
             Accept: application/json, text/event-stream\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
            body.len()
        );
        let mut stream = tokio::net::TcpStream::connect(("127.0.0.1", port))
            .await
            .unwrap();
        stream.write_all(request.as_bytes()).await.unwrap();
        let mut response = String::new();
        stream.read_to_string(&mut response).await.unwrap();
        response
    }

    /// The whole path over a real socket, as an MCP client sends it.
    #[tokio::test]
    async fn serves_over_tcp_with_the_token_only() {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let echo = Arc::new(Echo::default());
        let task = tokio::spawn(serve(listener, echo.clone(), TOKEN.into()));
        let body = r#"{"jsonrpc":"2.0","id":7,"method":"tools/list"}"#;

        let refused = post(port, None, body).await;
        assert!(refused.starts_with("HTTP/1.1 401"), "{refused}");
        let wrong = post(port, Some("Bearer 00"), body).await;
        assert!(wrong.starts_with("HTTP/1.1 401"), "{wrong}");
        assert!(echo.0.lock().unwrap().is_empty(), "never reached the tools");

        let response = post(port, Some(BEARER), body).await;
        task.abort();
        assert!(response.starts_with("HTTP/1.1 200"), "{response}");
        let json_body: Value =
            serde_json::from_str(response.split("\r\n\r\n").nth(1).unwrap()).unwrap();
        assert_eq!(json_body["result"]["method"], "tools/list");
        assert_eq!(json_body["id"], 7);
    }
}
