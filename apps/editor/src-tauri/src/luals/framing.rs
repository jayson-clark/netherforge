//! LSP's base protocol over a byte stream: each message is a header block
//! (`Content-Length: <n>`, optionally `Content-Type`, each line ending in
//! `\r\n`, then an empty line) followed by `n` bytes of UTF-8 JSON.
//!
//! The JSON itself passes through untouched: the webview's language client
//! reads and writes it.

use tokio::io::{AsyncBufRead, AsyncBufReadExt, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use crate::error::{Error, Result};

/// Refuses a header that claims more than this, rather than allocating it.
const MAX_MESSAGE: usize = 64 * 1024 * 1024;

/// The next message's body, or `None` at a clean end of stream (between messages).
pub async fn read_message<R: AsyncBufRead + Unpin>(reader: &mut R) -> Result<Option<String>> {
    let mut length: Option<usize> = None;
    let mut line = String::new();
    let mut first = true;
    loop {
        line.clear();
        if reader.read_line(&mut line).await? == 0 {
            if first {
                return Ok(None);
            }
            return Err(Error::msg(
                "The language server's output ended inside a header",
            ));
        }
        first = false;
        let header = line.trim_end_matches(['\r', '\n']);
        if header.is_empty() {
            break;
        }
        let Some((name, value)) = header.split_once(':') else {
            return Err(Error::msg(format!(
                "The language server sent a malformed header: {header}"
            )));
        };
        if name.trim().eq_ignore_ascii_case("content-length") {
            let n = value.trim().parse::<usize>().map_err(|_| {
                Error::msg(format!("The language server sent a bad length: {header}"))
            })?;
            length = Some(n);
        }
    }
    let length =
        length.ok_or_else(|| Error::msg("The language server sent a message without a length"))?;
    if length > MAX_MESSAGE {
        return Err(Error::msg(format!(
            "The language server sent a {length}-byte message, more than the editor accepts"
        )));
    }
    let mut body = vec![0; length];
    reader.read_exact(&mut body).await?;
    String::from_utf8(body)
        .map(Some)
        .map_err(|_| Error::msg("The language server sent a message that isn't UTF-8"))
}

/// Writes [body] as one message and flushes it.
pub async fn write_message<W: AsyncWrite + Unpin>(writer: &mut W, body: &str) -> Result<()> {
    let header = format!("Content-Length: {}\r\n\r\n", body.len());
    writer.write_all(header.as_bytes()).await?;
    writer.write_all(body.as_bytes()).await?;
    writer.flush().await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::BufReader;

    async fn read_all(bytes: &[u8]) -> Result<Vec<String>> {
        let mut reader = BufReader::new(bytes);
        let mut out = Vec::new();
        while let Some(message) = read_message(&mut reader).await? {
            out.push(message);
        }
        Ok(out)
    }

    #[tokio::test]
    async fn round_trips_messages_back_to_back() {
        let mut bytes = Vec::new();
        write_message(&mut bytes, r#"{"id":1}"#).await.unwrap();
        // Lengths are bytes, not characters.
        write_message(&mut bytes, r#"{"text":"é ✓"}"#)
            .await
            .unwrap();
        assert_eq!(
            read_all(&bytes).await.unwrap(),
            vec![r#"{"id":1}"#, r#"{"text":"é ✓"}"#]
        );
    }

    #[tokio::test]
    async fn ignores_other_headers_and_the_name_case() {
        let bytes =
            b"content-length: 2\r\nContent-Type: application/vscode-jsonrpc; charset=utf-8\r\n\r\n{}";
        assert_eq!(read_all(bytes).await.unwrap(), vec!["{}"]);
    }

    #[tokio::test]
    async fn an_empty_stream_is_the_end() {
        assert!(read_all(b"").await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn refuses_what_isnt_a_message() {
        assert!(read_all(b"Content-Type: x\r\n\r\n{}").await.is_err());
        assert!(read_all(b"Content-Length: two\r\n\r\n{}").await.is_err());
        assert!(read_all(b"Content-Length: 2\r\n").await.is_err());
        assert!(read_all(b"Content-Length: 9\r\n\r\n{}").await.is_err());
        assert!(read_all(b"garbage\r\n\r\n").await.is_err());
    }
}
