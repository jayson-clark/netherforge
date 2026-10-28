//! The one error type commands return: a [ErrorCode] the UI can act on and a
//! human-readable sentence it shows. Context is added where it happens
//! (`.context(|| format!("Couldn't read {path}"))`) and keeps the code of the
//! error underneath.

use std::fmt;

use serde::Serialize;
use specta::Type;

/// What kind of failure a command hit. The UI branches on this, never on
/// the message; `MemoryBackend` throws the same codes, which the contract
/// suite (`commands::contract`) checks.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Type)]
#[serde(rename_all = "camelCase")]
pub enum ErrorCode {
    /// The command needs an open project and there is none.
    NoProject,
    /// A project path that isn't one: absolute, `..`, `\`, or leading out of the project.
    InvalidPath,
    /// The file, folder or thing named doesn't exist.
    NotFound,
    /// Something is already where the command would put something new.
    AlreadyExists,
    /// A path the editor may not change (`.git`, `.netherforge/` outside the UI's folders).
    ReadOnly,
    /// An argument or a file's contents aren't acceptable (settings, versions, ids, not text).
    Invalid,
    /// A JVM argument that isn't on the allowlist (`jvm_args`); the UI shows it by the setting.
    JvmArgument,
    /// The Minecraft EULA hasn't been accepted.
    EulaRequired,
    /// The open project isn't trusted, and this runs or reaches out on its behalf (see `trust`).
    Untrusted,
    /// The dev server is busy with something that rules this out (already running).
    Busy,
    /// The dev server isn't running or its plugin isn't connected.
    NotConnected,
    /// The plugin didn't answer in time.
    Timeout,
    /// The plugin answered with an error of its own.
    Plugin,
    /// The plugin doesn't have the bridge method asked for (an extension it lacks).
    UnknownMethod,
    /// Something this machine lacks (lua-language-server, a launcher, a home folder).
    Unavailable,
    /// A download or an API call failed.
    Network,
    /// A newer request or a stop cancelled this one.
    Cancelled,
    /// Anything else: a filesystem or system error.
    Other,
}

/// What a command rejects with. The UI sees it as `CommandError` (`Error` is
/// JavaScript's own), and `TauriBackend` rethrows it as a `BackendError`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Type)]
#[serde(rename = "CommandError")]
pub struct Error {
    pub code: ErrorCode,
    pub message: String,
}

pub type Result<T, E = Error> = std::result::Result<T, E>;

impl Error {
    pub fn new(code: ErrorCode, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }

    /// An error with no more specific code than [ErrorCode::Other].
    pub fn msg(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::Other, message)
    }

    pub fn code(&self) -> ErrorCode {
        self.code
    }

    pub fn message(&self) -> &str {
        &self.message
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)
    }
}

impl std::error::Error for Error {}

impl From<std::io::Error> for Error {
    fn from(error: std::io::Error) -> Self {
        use std::io::ErrorKind;
        let code = match error.kind() {
            ErrorKind::NotFound => ErrorCode::NotFound,
            ErrorKind::AlreadyExists | ErrorKind::DirectoryNotEmpty => ErrorCode::AlreadyExists,
            _ => ErrorCode::Other,
        };
        Self::new(code, error.to_string())
    }
}

impl From<reqwest::Error> for Error {
    fn from(error: reqwest::Error) -> Self {
        Self::new(ErrorCode::Network, error.to_string())
    }
}

impl From<serde_json::Error> for Error {
    fn from(error: serde_json::Error) -> Self {
        Self::new(ErrorCode::Invalid, error.to_string())
    }
}

macro_rules! from_error {
    ($($ty:ty),* $(,)?) => {$(
        impl From<$ty> for Error {
            fn from(error: $ty) -> Self {
                Self::msg(error.to_string())
            }
        }
    )*};
}

from_error!(
    zip::result::ZipError,
    tauri::Error,
    walkdir::Error,
    std::path::StripPrefixError,
    notify::Error,
    png::DecodingError,
    std::string::FromUtf8Error,
);

/// Adds a sentence of context in front of an underlying error, keeping its code.
pub trait Context<T> {
    fn context<F: FnOnce() -> String>(self, f: F) -> Result<T>;
}

impl<T, E: Into<Error>> Context<T> for std::result::Result<T, E> {
    fn context<F: FnOnce() -> String>(self, f: F) -> Result<T> {
        self.map_err(|error| {
            let error = error.into();
            Error::new(error.code, format!("{}: {}", f(), error.message))
        })
    }
}

/// A missing value is [ErrorCode::NotFound].
impl<T> Context<T> for Option<T> {
    fn context<F: FnOnce() -> String>(self, f: F) -> Result<T> {
        self.ok_or_else(|| Error::new(ErrorCode::NotFound, f()))
    }
}

/// Returns early with an [Error] built from a format string, with a code
/// first when there's a better one than [ErrorCode::Other]:
/// `bail!(NotFound, "No such file: {path}")`.
macro_rules! bail {
    ($code:ident, $($arg:tt)+) => {
        return Err($crate::error::Error::new($crate::error::ErrorCode::$code, format!($($arg)+)))
    };
    ($($arg:tt)*) => {
        return Err($crate::error::Error::msg(format!($($arg)*)))
    };
}
pub(crate) use bail;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn context_keeps_the_code_underneath() {
        let missing: std::result::Result<(), std::io::Error> =
            Err(std::io::Error::from(std::io::ErrorKind::NotFound));
        let error = missing
            .context(|| "Couldn't read a.json".into())
            .unwrap_err();
        assert_eq!(error.code, ErrorCode::NotFound);
        assert!(error.message.starts_with("Couldn't read a.json: "));
    }

    #[test]
    fn serializes_as_code_and_message() {
        let json = serde_json::to_value(Error::new(ErrorCode::ReadOnly, "no")).unwrap();
        assert_eq!(
            json,
            serde_json::json!({ "code": "readOnly", "message": "no" })
        );
    }
}
