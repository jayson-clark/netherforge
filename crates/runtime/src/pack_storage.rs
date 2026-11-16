//! `resource-pack.storage:` in `config.yml` (Kotlin's `PackStorage`): an S3-compatible store (AWS, Cloudflare R2,
//! MinIO) the server uploads its packs to, players fetching them from its public address instead of from this server.
//! Packs are named by their SHA-1, so servers running the same bundle share one upload, and one that's there already
//! isn't uploaded again. Over HTTP with AWS Signature Version 4, path-style (`<endpoint>/<bucket>/<key>`).
//!
//! The store is the owner's own, so it's reached as they wrote it: no address guard (as Kotlin's, on the JDK's
//! client), but no proxy and no redirects either.

use std::collections::BTreeMap;
use std::time::Duration;

use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PackStorageConfig {
    pub endpoint: String,
    pub bucket: String,
    pub prefix: String,
    pub region: String,
    /// Where players fetch from: the bucket's public address (a CDN in front of it, or the bucket's own).
    pub public_url: String,
    pub access_key: String,
    pub secret_key: String,
}

impl PackStorageConfig {
    /// Where players fetch the pack with `sha1`: its key encoded as the upload's (Kotlin's left a prefix's spaces in
    /// the URL).
    pub fn url_of(&self, sha1: &str) -> String {
        format!(
            "{}/{}",
            self.public_url.trim_end_matches('/'),
            encode(&self.key_of(sha1))
        )
    }

    pub fn key_of(&self, sha1: &str) -> String {
        let prefix = self.prefix.trim_matches('/');
        if prefix.is_empty() {
            format!("{sha1}.zip")
        } else {
            format!("{prefix}/{sha1}.zip")
        }
    }
}

/// The store.
pub struct PackStorage {
    config: PackStorageConfig,
    agent: ureq::Agent,
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn hmac(key: &[u8], text: &str) -> Vec<u8> {
    let mut mac = Hmac::<Sha256>::new_from_slice(key).expect("any key length");
    mac.update(text.as_bytes());
    mac.finalize().into_bytes().to_vec()
}

/// Java's `URLEncoder` with a space as `%20`, each segment of a key.
fn encode(key: &str) -> String {
    key.split('/')
        .map(crate::web::path::encode)
        .collect::<Vec<_>>()
        .join("/")
}

impl PackStorage {
    pub fn new(config: PackStorageConfig) -> PackStorage {
        let agent = ureq::Agent::config_builder()
            .proxy(None)
            .max_redirects(0)
            .http_status_as_error(false)
            .timeout_connect(Some(Duration::from_secs(15)))
            .timeout_global(Some(Duration::from_secs(120)))
            .tls_config(
                ureq::tls::TlsConfig::builder()
                    .root_certs(ureq::tls::RootCerts::PlatformVerifier)
                    .build(),
            )
            .build()
            .new_agent();
        PackStorage { config, agent }
    }

    /// Puts the pack `bytes` (whose SHA-1 is `sha1`) in the store, unless it's there already. True when it uploaded.
    pub fn upload(&self, sha1: &str, bytes: &[u8]) -> Result<bool, String> {
        let key = self.config.key_of(sha1);
        let now = chrono::Utc::now().format("%Y%m%dT%H%M%SZ").to_string();
        let head = self
            .signed("HEAD", &key, &[], None, &now)?
            .body(())
            .map_err(|e| e.to_string())?;
        let response = self.agent.run(head).map_err(|e| e.to_string())?;
        if response.status() == 200 {
            return Ok(false);
        }
        let put = self
            .signed("PUT", &key, bytes, Some("application/zip"), &now)?
            .body(bytes.to_vec())
            .map_err(|e| e.to_string())?;
        let mut response = self.agent.run(put).map_err(|e| e.to_string())?;
        let status = response.status().as_u16();
        if !(200..=299).contains(&status) {
            let body = response.body_mut().read_to_string().unwrap_or_default();
            let body: String = body.chars().take(300).collect();
            return Err(format!("the store answered {status}: {body}"));
        }
        Ok(true)
    }

    /// A request for `key` signed at `stamp` (`yyyyMMdd'T'HHmmss'Z'`, UTC).
    fn signed(
        &self,
        method: &str,
        key: &str,
        body: &[u8],
        content_type: Option<&str>,
        stamp: &str,
    ) -> Result<ureq::http::request::Builder, String> {
        let url = format!(
            "{}/{}/{}",
            self.config.endpoint.trim_end_matches('/'),
            self.config.bucket,
            encode(key)
        );
        let parsed = url::Url::parse(&url).map_err(|e| format!("{url} isn't a URL: {e}"))?;
        let authority = match parsed.port() {
            Some(port) => format!("{}:{port}", parsed.host_str().unwrap_or_default()),
            None => parsed.host_str().unwrap_or_default().to_owned(),
        };
        let (authorization, hash) = authorization(
            &self.config,
            method,
            &authority,
            parsed.path(),
            body,
            content_type,
            stamp,
        );
        let mut request = ureq::http::Request::builder()
            .method(method)
            .uri(url.as_str())
            .header("x-amz-content-sha256", hash)
            .header("x-amz-date", stamp)
            .header("Authorization", authorization);
        if let Some(kind) = content_type {
            request = request.header("Content-Type", kind);
        }
        Ok(request)
    }
}

/// The `Authorization` header for a request, and its body's hash: AWS Signature Version 4.
pub fn authorization(
    config: &PackStorageConfig,
    method: &str,
    authority: &str,
    path: &str,
    body: &[u8],
    content_type: Option<&str>,
    stamp: &str,
) -> (String, String) {
    let day = &stamp[..8];
    let hash = hex(&Sha256::digest(body));
    let mut headers = BTreeMap::from([
        ("host", authority.to_owned()),
        ("x-amz-content-sha256", hash.clone()),
        ("x-amz-date", stamp.to_owned()),
    ]);
    if let Some(kind) = content_type {
        headers.insert("content-type", kind.to_owned());
    }
    let names = headers.keys().copied().collect::<Vec<_>>().join(";");
    let listed = headers
        .iter()
        .map(|(n, v)| format!("{n}:{v}"))
        .collect::<Vec<_>>()
        .join("\n");
    let canonical = [method, path, "", &listed, "", &names, &hash].join("\n");
    let scope = format!("{day}/{}/s3/aws4_request", config.region);
    let to_sign = [
        "AWS4-HMAC-SHA256",
        stamp,
        &scope,
        &hex(&Sha256::digest(canonical.as_bytes())),
    ]
    .join("\n");
    let mut signing = hmac(format!("AWS4{}", config.secret_key).as_bytes(), day);
    for part in [config.region.as_str(), "s3", "aws4_request"] {
        signing = hmac(&signing, part);
    }
    let signature = hex(&hmac(&signing, &to_sign));
    (
        format!(
            "AWS4-HMAC-SHA256 Credential={}/{scope}, SignedHeaders={names}, Signature={signature}",
            config.access_key
        ),
        hash,
    )
}
