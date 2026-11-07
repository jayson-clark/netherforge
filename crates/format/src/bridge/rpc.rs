//! JSON-RPC 2.0 (<https://www.jsonrpc.org/specification>), the dev bridge's
//! envelope: one message (or batch) per NDJSON line. Format's `JsonRpc`.
//!
//! Written here rather than taken from a crate: the spec is small, and the
//! bridge needs exactly Kotlin's reading of it (what's invalid, and the
//! answer it gets), so that both sides of a mixed bridge agree. The editor's UI
//! speaks it with `vscode-jsonrpc`.

use serde_json::{Map, Value, json};

pub const VERSION: &str = "2.0";

// The spec's codes.
pub const PARSE_ERROR: i64 = -32700;
pub const INVALID_REQUEST: i64 = -32600;
pub const METHOD_NOT_FOUND: i64 = -32601;
pub const INVALID_PARAMS: i64 = -32602;
pub const INTERNAL_ERROR: i64 = -32603;

#[derive(Clone, Debug, PartialEq)]
pub struct Error {
    pub code: i64,
    pub message: String,
    pub data: Option<Value>,
}

/// One message.
#[derive(Clone, Debug, PartialEq)]
pub enum Message {
    /// A call that wants an answer: exactly one response with the same id.
    Request {
        id: Value,
        method: String,
        params: Option<Value>,
    },
    /// A call that wants none: never answered, not even with an error.
    Notification {
        method: String,
        params: Option<Value>,
    },
    /// The answer to a request: a result or an error, never both. `id` is null only when the request's couldn't
    /// be read.
    Response {
        id: Value,
        outcome: Result<Value, Error>,
    },
    /// What isn't a message: unparseable JSON, or an object that isn't a request, notification or response.
    /// Whoever reads it answers with its error (to its id, null when it couldn't be read), as the spec asks.
    Invalid { id: Value, error: Error },
}

/// One line: a message, or a batch of them (a JSON array).
#[derive(Clone, Debug, PartialEq)]
pub enum Frame {
    Single(Message),
    Batch(Vec<Message>),
}

impl Frame {
    pub fn messages(&self) -> Vec<&Message> {
        match self {
            Frame::Single(m) => vec![m],
            Frame::Batch(ms) => ms.iter().collect(),
        }
    }
}

impl Message {
    pub fn request(id: impl Into<Value>, method: &str, params: Option<Value>) -> Message {
        Message::Request {
            id: id.into(),
            method: method.into(),
            params,
        }
    }

    pub fn notification(method: &str, params: Option<Value>) -> Message {
        Message::Notification {
            method: method.into(),
            params,
        }
    }

    pub fn ok(id: Value, result: Value) -> Message {
        Message::Response {
            id,
            outcome: Ok(result),
        }
    }

    pub fn error(id: Value, code: i64, message: impl Into<String>) -> Message {
        Message::Response {
            id,
            outcome: Err(Error {
                code,
                message: message.into(),
                data: None,
            }),
        }
    }

    /// The message as JSON, in the protocol's order (`jsonrpc`, `id`, `method`, `params`; `result` or
    /// `error`). An invalid message is answered, never sent: it's written as the error response it gets.
    pub fn encode(&self) -> Value {
        let mut out = Map::new();
        out.insert("jsonrpc".into(), json!(VERSION));
        match self {
            Message::Request { id, method, params } => {
                out.insert("id".into(), id.clone());
                out.insert("method".into(), json!(method));
                if let Some(p) = params {
                    out.insert("params".into(), p.clone());
                }
            }
            Message::Notification { method, params } => {
                out.insert("method".into(), json!(method));
                if let Some(p) = params {
                    out.insert("params".into(), p.clone());
                }
            }
            Message::Response { id, outcome } => {
                out.insert("id".into(), id.clone());
                match outcome {
                    Ok(result) => out.insert("result".into(), result.clone()),
                    Err(e) => out.insert("error".into(), encode_error(e)),
                };
            }
            Message::Invalid { id, error } => {
                out.insert("id".into(), id.clone());
                out.insert("error".into(), encode_error(error));
            }
        }
        Value::Object(out)
    }

    /// The message as one line, without its newline.
    pub fn line(&self) -> String {
        self.encode().to_string()
    }
}

fn encode_error(e: &Error) -> Value {
    let mut out = Map::new();
    out.insert("code".into(), json!(e.code));
    out.insert("message".into(), json!(e.message));
    if let Some(d) = &e.data {
        out.insert("data".into(), d.clone());
    }
    Value::Object(out)
}

/// A batch as one line, without its newline.
pub fn batch_line(messages: &[Message]) -> String {
    Value::Array(messages.iter().map(Message::encode).collect()).to_string()
}

/// A frame as one line, as it was read.
pub fn frame_line(frame: &Frame) -> String {
    match frame {
        Frame::Single(m) => m.line(),
        Frame::Batch(ms) => batch_line(ms),
    }
}

fn invalid(id: Value, why: &str) -> Message {
    Message::Invalid {
        id,
        error: Error {
            code: INVALID_REQUEST,
            message: why.into(),
            data: None,
        },
    }
}

/// Reads one line. Never fails: what isn't a message is an `Invalid`, to be answered.
pub fn decode(line: &str) -> Frame {
    let value: Value = match serde_json::from_str(line) {
        Ok(v) => v,
        Err(e) => {
            return Frame::Single(Message::Invalid {
                id: Value::Null,
                error: Error {
                    code: PARSE_ERROR,
                    message: format!("Not JSON: {e}"),
                    data: None,
                },
            });
        }
    };
    match value {
        Value::Array(items) if items.is_empty() => {
            Frame::Single(invalid(Value::Null, "An empty batch"))
        }
        Value::Array(items) => Frame::Batch(items.iter().map(message).collect()),
        other => Frame::Single(message(&other)),
    }
}

/// One message from its JSON.
pub fn message(value: &Value) -> Message {
    let Value::Object(obj) = value else {
        return invalid(Value::Null, "A message is a JSON object");
    };
    let raw_id = obj.get("id");
    let valid_id = matches!(
        raw_id,
        Some(Value::Null | Value::String(_) | Value::Number(_))
    );
    if obj.get("jsonrpc").and_then(Value::as_str) != Some(VERSION) {
        let id = if valid_id {
            raw_id.cloned().unwrap()
        } else {
            Value::Null
        };
        return invalid(id, &format!("\"jsonrpc\" must be \"{VERSION}\""));
    }
    if raw_id.is_some() && !valid_id {
        return invalid(Value::Null, "An id is a string, a number or null");
    }
    let id = raw_id.cloned().unwrap_or(Value::Null);
    if let Some(method) = obj.get("method") {
        let Some(name) = method.as_str() else {
            return invalid(id, "\"method\" must be a string");
        };
        let params = obj.get("params");
        if params.is_some_and(|p| !p.is_object() && !p.is_array()) {
            return invalid(id, "\"params\" must be an object or an array");
        }
        let params = params.cloned();
        return match raw_id {
            None => Message::Notification {
                method: name.into(),
                params,
            },
            Some(_) => Message::Request {
                id,
                method: name.into(),
                params,
            },
        };
    }
    if raw_id.is_none() {
        return invalid(
            Value::Null,
            "A message has a \"method\", or an \"id\" and a result",
        );
    }
    match (obj.get("result"), obj.get("error")) {
        (Some(result), None) => Message::ok(id, result.clone()),
        (None, Some(error)) => {
            let code = error.get("code").and_then(Value::as_i64);
            let text = error.get("message").and_then(Value::as_str);
            match (code, text) {
                (Some(code), Some(text)) if i32::try_from(code).is_ok() => Message::Response {
                    id,
                    outcome: Err(Error {
                        code,
                        message: text.into(),
                        data: error.get("data").cloned(),
                    }),
                },
                _ => invalid(id, "An error has an integer \"code\" and a \"message\""),
            }
        }
        _ => invalid(id, "A response has a \"result\" or an \"error\""),
    }
}
