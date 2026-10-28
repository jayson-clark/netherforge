//! HTTP downloads with sha256 verification. Streams to `<dest>.part`, hashing
//! as it goes, and renames into place only when the checksum matches, so a
//! cached file is always a verified one.

use std::path::{Path, PathBuf};
use std::time::Duration;

use futures_util::StreamExt;
use sha2::{Digest, Sha256};
use tokio::io::AsyncWriteExt;

use crate::error::{Context, Error, Result, bail};

pub fn http_client() -> Result<reqwest::Client> {
    reqwest::Client::builder()
        .user_agent(concat!(
            "NetherForge-Editor/",
            env!("CARGO_PKG_VERSION"),
            " (open-source Minecraft server content editor)"
        ))
        .connect_timeout(Duration::from_secs(20))
        .read_timeout(Duration::from_secs(60))
        .build()
        .map_err(|e| Error::msg(format!("Couldn't set up downloads: {e}")))
}

/// Downloads [url] to [dest], verifying [sha256] (hex) when given.
/// [progress] gets (bytes done, total if known), at most every 512 KiB.
/// [cancelled] is polled between chunks.
pub async fn download(
    client: &reqwest::Client,
    url: &str,
    dest: &Path,
    sha256: Option<&str>,
    progress: &(dyn Fn(u64, Option<u64>) + Send + Sync),
    cancelled: &(dyn Fn() -> bool + Send + Sync),
) -> Result<()> {
    let response = client
        .get(url)
        .send()
        .await
        .context(|| format!("Couldn't download {url}"))?;
    if !response.status().is_success() {
        bail!("Couldn't download {url}: HTTP {}", response.status());
    }
    let total = response.content_length();
    if let Some(dir) = dest.parent() {
        tokio::fs::create_dir_all(dir).await?;
    }
    let part = part_path(dest);
    let result = async {
        let mut file = tokio::fs::File::create(&part)
            .await
            .context(|| format!("Couldn't write {}", part.display()))?;
        let mut hasher = Sha256::new();
        let mut done = 0u64;
        let mut reported = 0u64;
        progress(0, total);
        let mut stream = response.bytes_stream();
        while let Some(chunk) = stream.next().await {
            if cancelled() {
                bail!(Cancelled, "Cancelled");
            }
            let chunk = chunk.context(|| format!("Download of {url} failed"))?;
            hasher.update(&chunk);
            file.write_all(&chunk).await?;
            done += chunk.len() as u64;
            if done - reported >= 512 * 1024 {
                reported = done;
                progress(done, total);
            }
        }
        file.flush().await?;
        file.sync_all().await?;
        drop(file);
        progress(done, total.or(Some(done)));
        if let Some(expected) = sha256 {
            let actual = hex::encode(hasher.finalize());
            if !actual.eq_ignore_ascii_case(expected) {
                bail!("The download of {url} is corrupt (sha256 {actual}, expected {expected})");
            }
        }
        crate::fs::atomic::rename_replacing(&part, dest)
            .context(|| format!("Couldn't save {}", dest.display()))
    }
    .await;
    if result.is_err() {
        let _ = tokio::fs::remove_file(&part).await;
    }
    result
}

fn part_path(dest: &Path) -> PathBuf {
    let mut name = dest.file_name().unwrap_or_default().to_os_string();
    name.push(".part");
    dest.with_file_name(name)
}

/// The sha256 of a file, hex. Runs on a blocking thread.
pub async fn sha256_file(path: &Path) -> Result<String> {
    let path = path.to_path_buf();
    tokio::task::spawn_blocking(move || -> Result<String> {
        let mut file = std::fs::File::open(&path)?;
        let mut hasher = Sha256::new();
        std::io::copy(&mut file, &mut hasher)?;
        Ok(hex::encode(hasher.finalize()))
    })
    .await
    .map_err(|e| Error::msg(e.to_string()))?
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    /// Serves [body] once per connection over plain HTTP/1.1 on localhost.
    async fn serve(body: &'static [u8]) -> String {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        tokio::spawn(async move {
            while let Ok((mut stream, _)) = listener.accept().await {
                let mut request = [0u8; 2048];
                let _ = stream.read(&mut request).await;
                let head = format!(
                    "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                    body.len()
                );
                let _ = stream.write_all(head.as_bytes()).await;
                let _ = stream.write_all(body).await;
            }
        });
        format!("http://127.0.0.1:{port}/file.jar")
    }

    #[tokio::test]
    async fn verifies_checksums() {
        let url = serve(b"hello paper").await;
        let tmp = tempfile::tempdir().unwrap();
        let dest = tmp.path().join("cache/paper.jar");
        // No proxy: a CI runner's HTTP_PROXY mustn't intercept localhost.
        let client = reqwest::Client::builder().no_proxy().build().unwrap();
        let good = hex::encode(Sha256::digest(b"hello paper"));

        let seen = std::sync::Mutex::new(Vec::new());
        download(
            &client,
            &url,
            &dest,
            Some(&good),
            &|d, t| seen.lock().unwrap().push((d, t)),
            &|| false,
        )
        .await
        .unwrap();
        assert_eq!(std::fs::read(&dest).unwrap(), b"hello paper");
        assert_eq!(seen.lock().unwrap().last(), Some(&(11, Some(11))));
        assert_eq!(sha256_file(&dest).await.unwrap(), good);

        let other = tmp.path().join("cache/other.jar");
        let bad = "0".repeat(64);
        let error = download(&client, &url, &other, Some(&bad), &|_, _| {}, &|| false)
            .await
            .unwrap_err();
        assert!(error.message().contains("corrupt"));
        assert!(!other.exists());
        assert!(!part_path(&other).exists());
    }
}
