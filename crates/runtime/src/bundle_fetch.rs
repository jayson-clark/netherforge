//! A bundle run by reference, not by folder (Kotlin's `BundleFetch`): `project:
//! https://example.com/arena-1.4.0.nfbundle#sha256=<hex>` in `config.yml` (what `netherforge publish` prints).
//! Downloaded once, checked against its SHA-256, unpacked into `bundles/<sha256>/` in the plugin's folder, and from
//! then on read from there; the bundle's own checks (its packages' hashes, its signature when one is required) are then
//! as for any bundle. The reference names the bytes, so a changed file at the URL is refused rather than run.

use std::io::{Read as _, Write as _};
use std::path::{Path, PathBuf};
use std::time::Duration;

use sha2::{Digest as _, Sha256};

/// What marks a folder unpacked whole.
const DONE: &str = ".netherforge-fetched";
const MAX_BYTES: u64 = 1024 * 1024 * 1024;
const MAX_FILES: usize = 100_000;

/// Whether `project` (the `project` setting) names a bundle by URL.
pub fn is_reference(project: &str) -> bool {
    project.starts_with("https://") || project.starts_with("http://")
}

/// The SHA-256 `reference` names, lowercase, or why it names none.
pub fn expected_sha256(reference: &str) -> Result<String, String> {
    let expected = reference
        .split_once("#sha256=")
        .map(|(_, h)| h.to_lowercase())
        .unwrap_or_default();
    if expected.len() == 64 && expected.bytes().all(|b| b.is_ascii_hexdigit()) {
        Ok(expected)
    } else {
        Err(format!(
            "the project {reference} names no #sha256=<the archive's SHA-256> (netherforge publish prints it)"
        ))
    }
}

/// The folder of the bundle `reference` names, in `cache`: fetched and unpacked the first time. An error says why not.
pub fn folder(reference: &str, cache: &Path, log: &dyn Fn(&str)) -> Result<PathBuf, String> {
    let url = reference.split('#').next().unwrap_or(reference);
    let expected = expected_sha256(reference)?;
    let folder = cache.join(&expected);
    if folder.join(DONE).is_file() {
        return Ok(folder);
    }
    log(&format!(
        "NetherForge: fetching the project's bundle from {url}"
    ));
    let agent = ureq::Agent::config_builder()
        .timeout_connect(Some(Duration::from_secs(15)))
        .timeout_global(Some(Duration::from_secs(300)))
        .build()
        .new_agent();
    let mut response = agent.get(url).call().map_err(|e| format!("{url}: {e}"))?;
    let mut body = response.body_mut().with_config().limit(MAX_BYTES).reader();
    let mut bytes = Vec::new();
    body.read_to_end(&mut bytes).map_err(|e| {
        format!(
            "{url}: {e} (a bundle is at most {} MB)",
            MAX_BYTES / 1024 / 1024
        )
    })?;
    let actual: String = Sha256::digest(&bytes)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect();
    if actual != expected {
        return Err(format!(
            "{url} isn't the bundle the project names: its SHA-256 is {actual}, not {expected}"
        ));
    }
    std::fs::create_dir_all(cache).map_err(|e| format!("{}: {e}", cache.display()))?;
    unpack(&bytes, &folder)?;
    log(&format!(
        "NetherForge: unpacked the project's bundle into {}",
        folder.display()
    ));
    Ok(folder)
}

/// Unpacks the zip `archive` into `folder` whole, or not at all: into a folder beside it, then moved into place.
pub fn unpack(archive: &[u8], folder: &Path) -> Result<(), String> {
    let parent = folder.parent().ok_or("a bundle's folder has a parent")?;
    let partial = parent.join(format!(
        ".unpack-{}-{}",
        std::process::id(),
        folder
            .file_name()
            .map_or_else(String::new, |n| n.to_string_lossy().into_owned())
    ));
    let _ = std::fs::remove_dir_all(&partial);
    let unpacked = (|| -> Result<(), String> {
        let entries = read_zip(archive)?;
        if entries.len() > MAX_FILES {
            return Err(format!("the bundle holds more than {MAX_FILES} files"));
        }
        for (name, data) in entries {
            // Nothing outside the folder, whatever the archive says.
            let escapes = name.contains('\\')
                || name.starts_with('/')
                || name.split('/').any(|p| p == ".." || p.contains(':'));
            if escapes {
                return Err(format!("the bundle holds a path outside it: {name}"));
            }
            let target = partial.join(&name);
            if name.ends_with('/') {
                std::fs::create_dir_all(&target).map_err(|e| e.to_string())?;
                continue;
            }
            if let Some(dir) = target.parent() {
                std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
            }
            std::fs::File::create(&target)
                .and_then(|mut f| f.write_all(&data))
                .map_err(|e| format!("{}: {e}", target.display()))?;
        }
        std::fs::write(partial.join(DONE), "").map_err(|e| e.to_string())?;
        if folder.exists() {
            std::fs::remove_dir_all(folder).map_err(|e| e.to_string())?;
        }
        std::fs::rename(&partial, folder).map_err(|e| e.to_string())
    })();
    if unpacked.is_err() {
        let _ = std::fs::remove_dir_all(&partial);
    }
    unpacked
}

fn u16_at(b: &[u8], at: usize) -> Result<usize, String> {
    b.get(at..at + 2)
        .map(|s| u16::from_le_bytes([s[0], s[1]]) as usize)
        .ok_or_else(|| "the bundle isn't a whole zip".into())
}

fn u32_at(b: &[u8], at: usize) -> Result<usize, String> {
    b.get(at..at + 4)
        .map(|s| u32::from_le_bytes([s[0], s[1], s[2], s[3]]) as usize)
        .ok_or_else(|| "the bundle isn't a whole zip".into())
}

/// Every entry of a zip (stored or deflated), by name, from its central directory.
pub fn read_zip(b: &[u8]) -> Result<Vec<(String, Vec<u8>)>, String> {
    const END: u32 = 0x0605_4b50;
    let end = (0..=b.len().saturating_sub(22))
        .rev()
        .take(65_557)
        .find(|&at| u32_at(b, at).ok() == Some(END as usize))
        .ok_or("the bundle isn't a zip")?;
    let count = u16_at(b, end + 10)?;
    let mut at = u32_at(b, end + 16)?;
    let mut out = Vec::with_capacity(count.min(MAX_FILES + 1));
    for _ in 0..count {
        if u32_at(b, at)? != 0x0201_4b50 {
            return Err("the bundle's zip directory is broken".into());
        }
        let method = u16_at(b, at + 10)?;
        let packed = u32_at(b, at + 20)?;
        let size = u32_at(b, at + 24)?;
        let name_len = u16_at(b, at + 28)?;
        let extra = u16_at(b, at + 30)?;
        let comment = u16_at(b, at + 32)?;
        let local = u32_at(b, at + 42)?;
        let name = b
            .get(at + 46..at + 46 + name_len)
            .ok_or("the bundle isn't a whole zip")?;
        let name = String::from_utf8_lossy(name).into_owned();
        at += 46 + name_len + extra + comment;
        if u32_at(b, local)? != 0x0403_4b50 {
            return Err(format!("the bundle's entry {name} is broken"));
        }
        let start = local + 30 + u16_at(b, local + 26)? + u16_at(b, local + 28)?;
        let data = b
            .get(start..start + packed)
            .ok_or_else(|| format!("the bundle's entry {name} is cut short"))?;
        let data = match method {
            0 => data.to_vec(),
            8 => {
                let mut inflated = Vec::with_capacity(size.min(64 * 1024 * 1024));
                flate2::read::DeflateDecoder::new(data)
                    .take(MAX_BYTES)
                    .read_to_end(&mut inflated)
                    .map_err(|e| format!("the bundle's entry {name}: {e}"))?;
                inflated
            }
            other => {
                return Err(format!(
                    "the bundle's entry {name} is packed in a way NetherForge doesn't read ({other})"
                ));
            }
        };
        out.push((name, data));
    }
    Ok(out)
}
