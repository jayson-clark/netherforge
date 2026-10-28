//! Fetching git packages into the package cache (`<data>/packages`), with the
//! user's own `git`, as npm, pnpm and Go do: their credentials, SSH keys and
//! proxy settings work as they do everywhere else. The CLI does the same,
//! step for step (`apps/cli/src/git.ts`): keep the two alike, since both must
//! write the same files for a commit or the package's hash differs.
//!
//! A fetched package is someone else's code, so nothing of the repository is
//! ever run or obeyed. It's fetched into a bare repository of our own (an
//! empty template: no hooks), and its commit's files are written out of the
//! raw objects (`ls-tree` + `cat-file --batch`), never checked out, so no
//! `.gitattributes` filter, LFS smudge, submodule or link of the repository's
//! applies. Only regular files are written: links and submodules are left
//! out, and a path that could leave the checkout (`..`, `.git` in any case,
//! `\`, `:`) refuses the whole commit. Only https, ssh and file URLs are
//! fetched from ([is_url], which format checked too), and `GIT_ALLOW_PROTOCOL`
//! holds git itself to them; nothing can prompt (`GIT_TERMINAL_PROMPT=0`,
//! stdin closed).
//!
//! The cache:
//! - `git/db/<first 16 hex of the URL's SHA-256>/`: a bare repository per
//!   URL, holding every commit fetched from it (`refs/netherforge/commits/<commit>`).
//! - `git/checkouts/<commit>/`: that commit's files (format's
//!   `Packages.gitCheckout`), written aside and renamed into place, so one
//!   is whole or absent. A dev server reads them (`-Dnetherforge.packages`).

use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

use sha2::{Digest, Sha256};

use crate::error::{Context, Error, ErrorCode, Result, bail};

/// What starts a git package's location: `git:<commit>`.
pub const LOCATION_PREFIX: &str = "git:";

/// Whether [text] is a full commit id: 40 lowercase hex digits, or 64.
pub fn is_commit(text: &str) -> bool {
    (text.len() == 40 || text.len() == 64)
        && text
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

/// Whether [url] is one NetherForge fetches from: format's `Packages.isGitUrl`.
pub fn is_url(url: &str) -> bool {
    if url.starts_with('-') || url.chars().any(|c| c.is_whitespace() || c.is_control()) {
        return false;
    }
    for scheme in ["https://", "ssh://", "file://"] {
        if let Some(rest) = url.strip_prefix(scheme) {
            return !rest.is_empty();
        }
    }
    // scp-like: user@host:path, no slash before the colon.
    let Some((user, rest)) = url.split_once('@') else {
        return false;
    };
    let Some((host, path)) = rest.split_once(':') else {
        return false;
    };
    !user.is_empty()
        && user
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
        && !host.is_empty()
        && host
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b".-".contains(&b))
        && !path.is_empty()
        && !path.starts_with('/')
}

/// Whether [rev] can be a dependency's `rev`: format's `Packages.isGitRev`.
pub fn is_rev(rev: &str) -> bool {
    let mut bytes = rev.bytes();
    let first_ok = bytes
        .next()
        .is_some_and(|b| b.is_ascii_alphanumeric() || b == b'_');
    first_ok
        && rev
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"_./+-".contains(&b))
        && !rev.contains("..")
        && !rev.contains("//")
        && !rev.ends_with('/')
        && !rev.ends_with('.')
        && !rev.ends_with(".lock")
        && !rev.split('/').any(|part| part.starts_with('.'))
}

/// The checkout of [commit] in the package cache [cache].
pub fn checkout_dir(cache: &Path, commit: &str) -> PathBuf {
    cache.join("git").join("checkouts").join(commit)
}

/// The checkout a `git:<commit>` location names in [cache]; None for a folder's location.
pub fn location_dir(cache: &Path, location: &str) -> Option<Result<PathBuf>> {
    let commit = location.strip_prefix(LOCATION_PREFIX)?;
    if !is_commit(commit) {
        return Some(Err(Error::new(
            ErrorCode::InvalidPath,
            format!("\"{location}\" isn't a git package's location (git:<commit>)"),
        )));
    }
    Some(Ok(checkout_dir(cache, commit)))
}

/// [url] at [rev] (the default branch when None), or exactly [commit] when
/// the lock pins one, fetched into [cache]: the commit, whose checkout is then
/// at [checkout_dir]. A pinned commit already checked out needs no network.
pub fn fetch(
    cache: &Path,
    url: &str,
    rev: Option<&str>,
    commit: Option<&str>,
    allowed: Result<()>,
) -> Result<String> {
    if !is_url(url) {
        bail!(
            Invalid,
            "\"{url}\" isn't a repository NetherForge fetches from (https://…, ssh://…, user@host:path or file://…)"
        );
    }
    if let Some(rev) = rev
        && !is_rev(rev)
    {
        bail!(
            Invalid,
            "\"{rev}\" isn't the name of a branch, a tag or a full commit"
        );
    }
    if let Some(commit) = commit {
        if !is_commit(commit) {
            bail!(Invalid, "\"{commit}\" isn't a commit");
        }
        if checkout_dir(cache, commit).is_dir() {
            return Ok(commit.to_string());
        }
    }
    // Past the cache: [allowed] says whether git may reach the repository (trust).
    allowed?;
    let repo = database(cache, url)?;
    let commit = fetch_commit(&repo, url, rev, commit)?;
    let target = checkout_dir(cache, &commit);
    if !target.is_dir() {
        checkout(&repo, &commit, &target)?;
    }
    Ok(commit)
}

/// Runs git with [args] (and [input] on its stdin): its stdout, or what it said went wrong.
fn git(args: &[&str], input: Option<&[u8]>) -> Result<Vec<u8>> {
    let mut command = Command::new("git");
    command
        .args(args)
        .env("GIT_TERMINAL_PROMPT", "0")
        .env("GIT_ALLOW_PROTOCOL", "https:ssh:file")
        .stdin(if input.is_some() {
            Stdio::piped()
        } else {
            Stdio::null()
        })
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    let mut child = command.spawn().map_err(|e| {
        if e.kind() == std::io::ErrorKind::NotFound {
            Error::new(
                ErrorCode::Unavailable,
                "git isn't installed, or isn't on the PATH: NetherForge fetches packages with it",
            )
        } else {
            Error::from(e)
        }
    })?;
    if let Some(input) = input {
        let mut stdin = child.stdin.take().context(|| "git has no stdin".into())?;
        // Written on its own thread: git answers as it reads, and a full pipe both ways would stall.
        let input = input.to_vec();
        let writer = std::thread::spawn(move || stdin.write_all(&input));
        let output = child.wait_with_output()?;
        let _ = writer.join();
        return finish(args, output);
    }
    finish(args, child.wait_with_output()?)
}

fn finish(args: &[&str], output: std::process::Output) -> Result<Vec<u8>> {
    if output.status.success() {
        return Ok(output.stdout);
    }
    let said = String::from_utf8_lossy(&output.stderr);
    let last = said
        .lines()
        .rfind(|line| !line.trim().is_empty())
        .map(str::trim)
        .unwrap_or("");
    let what = args
        .iter()
        .find(|it| !it.starts_with('-') && **it != "-C")
        .unwrap_or(&"");
    Err(Error::new(
        ErrorCode::Network,
        if last.is_empty() {
            format!("git {what} failed")
        } else {
            last.to_string()
        },
    ))
}

fn path_arg(path: &Path) -> Result<&str> {
    path.to_str()
        .context(|| format!("{} isn't a usable path", path.display()))
}

/// The bare repository [url] is fetched into, made (empty, without hooks) the first time.
fn database(cache: &Path, url: &str) -> Result<PathBuf> {
    let key = &hex::encode(Sha256::digest(url.as_bytes()))[..16];
    let repo = cache.join("git").join("db").join(key);
    if repo.is_dir() {
        return Ok(repo);
    }
    let parent = repo.parent().context(|| "the cache has no folder".into())?;
    std::fs::create_dir_all(parent).context(|| format!("Couldn't create {}", parent.display()))?;
    let staging = super::atomic::sibling_temp(&repo);
    git(
        &[
            "init",
            "--bare",
            "--quiet",
            "--template=",
            path_arg(&staging)?,
        ],
        None,
    )?;
    if std::fs::rename(&staging, &repo).is_err() {
        // Another fetch made it first.
        let _ = std::fs::remove_dir_all(&staging);
    }
    Ok(repo)
}

/// A random suffix for a temporary ref.
fn nonce() -> String {
    let mut bytes = [0u8; 6];
    getrandom::fill(&mut bytes).expect("the OS has randomness");
    hex::encode(bytes)
}

/// [what] (a commit, a rev, `HEAD`) fetched from [url] into [repo]: the commit it is.
fn fetch_into(repo: &Path, url: &str, what: &str) -> Result<String> {
    let dir = path_arg(repo)?;
    let reference = format!("refs/netherforge/fetch-{}", nonce());
    git(
        &[
            "-C",
            dir,
            "fetch",
            "--quiet",
            "--no-tags",
            "--no-recurse-submodules",
            "--no-write-fetch-head",
            "--",
            url,
            &format!("+{what}:{reference}"),
        ],
        None,
    )?;
    let resolved = git(
        &[
            "-C",
            dir,
            "rev-parse",
            "--verify",
            "--end-of-options",
            &format!("{reference}^{{commit}}"),
        ],
        None,
    );
    let _ = git(&["-C", dir, "update-ref", "-d", &reference], None);
    Ok(String::from_utf8_lossy(&resolved?).trim().to_string())
}

fn has_commit(repo: &Path, commit: &str) -> bool {
    path_arg(repo).is_ok_and(|dir| {
        git(
            &["-C", dir, "cat-file", "-e", &format!("{commit}^{{commit}}")],
            None,
        )
        .is_ok()
    })
}

/// The commit wanted, fetched into [repo]: the pinned [commit] (by id, or
/// with [rev] when the server won't hand out commits by id), or whatever
/// [rev] (the default branch: `HEAD`) is now.
fn fetch_commit(repo: &Path, url: &str, rev: Option<&str>, commit: Option<&str>) -> Result<String> {
    let commit = match commit {
        Some(commit) => {
            if !has_commit(repo, commit) {
                if fetch_into(repo, url, commit).is_err() {
                    fetch_into(repo, url, rev.unwrap_or("HEAD"))?;
                }
                if !has_commit(repo, commit) {
                    bail!(
                        NotFound,
                        "{url} has no commit {commit} (was it force-pushed away?)"
                    );
                }
            }
            commit.to_string()
        }
        None => fetch_into(repo, url, rev.unwrap_or("HEAD")).map_err(|e| {
            let hint = rev
                .map(|rev| format!(" (is \"{rev}\" a branch, a tag or a full commit there?)"))
                .unwrap_or_default();
            Error::new(e.code(), format!("{}{hint}", e.message()))
        })?,
    };
    // Kept, so the commit's objects stay in the database.
    git(
        &[
            "-C",
            path_arg(repo)?,
            "update-ref",
            &format!("refs/netherforge/commits/{commit}"),
            &commit,
        ],
        None,
    )?;
    Ok(commit)
}

/// Whether [file], a path in a commit's tree, is safe to write under the checkout on every OS.
fn is_safe_path(file: &str) -> bool {
    file.split('/').all(|part| {
        !part.is_empty()
            && part != "."
            && part != ".."
            && !part.eq_ignore_ascii_case(".git")
            && !part.contains(['\\', ':', '\0'])
    })
}

/// [commit]'s regular files out of [repo] into [target]: written aside and renamed into place.
fn checkout(repo: &Path, commit: &str, target: &Path) -> Result<()> {
    let dir = path_arg(repo)?;
    let listed = git(
        &["-C", dir, "ls-tree", "-r", "-z", "--full-tree", commit],
        None,
    )?;
    let mut files = Vec::new();
    for entry in listed.split(|b| *b == 0).filter(|it| !it.is_empty()) {
        let entry = String::from_utf8_lossy(entry);
        let (meta, file) = entry
            .split_once('\t')
            .context(|| format!("git listed {entry} oddly"))?;
        let mut meta = meta.split(' ');
        let (mode, kind, object) = (meta.next(), meta.next(), meta.next());
        // Links and submodules are left out: only a commit's plain files are a package.
        if kind != Some("blob") || !matches!(mode, Some("100644" | "100755")) {
            continue;
        }
        if !is_safe_path(file) {
            bail!(
                Invalid,
                "{commit} has a file named \"{file}\", which can't be written safely"
            );
        }
        let object = object.context(|| format!("git listed {file} without its object"))?;
        files.push((object.to_string(), file.to_string()));
    }
    let blobs = read_blobs(repo, files.iter().map(|(object, _)| object.as_str()))?;
    let parent = target
        .parent()
        .context(|| "the checkout has no folder".into())?;
    std::fs::create_dir_all(parent).context(|| format!("Couldn't create {}", parent.display()))?;
    let staging = super::atomic::sibling_temp(target);
    let result = (|| -> Result<()> {
        std::fs::create_dir_all(&staging)?;
        for ((_, file), bytes) in files.iter().zip(&blobs) {
            let to = file
                .split('/')
                .fold(staging.clone(), |path, part| path.join(part));
            if let Some(parent) = to.parent() {
                std::fs::create_dir_all(parent)?;
            }
            std::fs::write(&to, bytes).context(|| format!("Couldn't write {file}"))?;
        }
        if let Err(error) = std::fs::rename(&staging, target) {
            // Another fetch wrote it first: theirs is the same commit's files.
            if !target.is_dir() {
                return Err(error.into());
            }
        }
        Ok(())
    })();
    let _ = std::fs::remove_dir_all(&staging);
    result
}

/// The raw bytes of each blob in [objects], in order, read in one `cat-file --batch`.
fn read_blobs<'a>(repo: &Path, objects: impl Iterator<Item = &'a str>) -> Result<Vec<Vec<u8>>> {
    let objects: Vec<&str> = objects.collect();
    if objects.is_empty() {
        return Ok(Vec::new());
    }
    let input = objects.join("\n") + "\n";
    let out = git(
        &["-C", path_arg(repo)?, "cat-file", "--batch"],
        Some(input.as_bytes()),
    )?;
    let mut blobs = Vec::with_capacity(objects.len());
    let mut at = 0;
    for object in objects {
        let end = out[at..]
            .iter()
            .position(|b| *b == b'\n')
            .map(|i| at + i)
            .context(|| format!("couldn't read {object}"))?;
        let header = String::from_utf8_lossy(&out[at..end]);
        let mut parts = header.split(' ');
        let (name, kind, size) = (parts.next(), parts.next(), parts.next());
        let size: usize = size.and_then(|it| it.parse().ok()).unwrap_or(usize::MAX);
        if name != Some(object) || kind != Some("blob") || end + 1 + size > out.len() {
            bail!(Other, "couldn't read {object}");
        }
        blobs.push(out[end + 1..end + 1 + size].to_vec());
        at = end + 1 + size + 1;
    }
    Ok(blobs)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Runs git in [cwd] as a test author.
    fn run(cwd: &Path, args: &[&str]) -> String {
        let output = Command::new("git")
            .args(["-c", "user.name=Test", "-c", "user.email=test@example.com"])
            .args(args)
            .current_dir(cwd)
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "git {args:?}: {}",
            String::from_utf8_lossy(&output.stderr)
        );
        String::from_utf8(output.stdout).unwrap().trim().to_string()
    }

    struct Repo {
        _tmp: tempfile::TempDir,
        work: PathBuf,
        url: String,
        cache: PathBuf,
    }

    impl Repo {
        fn write(&self, path: &str, text: &str) {
            let file = self.work.join(path);
            std::fs::create_dir_all(file.parent().unwrap()).unwrap();
            std::fs::write(file, text).unwrap();
        }

        /// Commits the work tree and pushes main (and [tag]): the new commit.
        fn publish(&self, tag: Option<&str>) -> String {
            run(&self.work, &["add", "-A"]);
            run(&self.work, &["commit", "--quiet", "-m", "change"]);
            run(
                &self.work,
                &["push", "--quiet", "--force", "origin", "main"],
            );
            if let Some(tag) = tag {
                run(&self.work, &["tag", tag]);
                run(&self.work, &["push", "--quiet", "origin", tag]);
            }
            run(&self.work, &["rev-parse", "HEAD"])
        }
    }

    /// A bare repository with one package committed on main, tagged v1.
    fn repo() -> (Repo, String) {
        let tmp = tempfile::tempdir().unwrap();
        let bare = tmp.path().join("lib.git");
        let work = tmp.path().join("work");
        run(tmp.path(), &["init", "--quiet", "--bare", "lib.git"]);
        run(
            tmp.path(),
            &["init", "--quiet", "--initial-branch=main", "work"],
        );
        run(&work, &["remote", "add", "origin", bare.to_str().unwrap()]);
        run(&bare, &["symbolic-ref", "HEAD", "refs/heads/main"]);
        let url = format!(
            "file://{}{}",
            if cfg!(windows) { "/" } else { "" },
            bare.to_str().unwrap().replace('\\', "/")
        );
        let cache = tmp.path().join("packages");
        let repo = Repo {
            _tmp: tmp,
            work,
            url,
            cache,
        };
        repo.write("netherforge.json", "{ \"namespace\": \"lib\" }\n");
        repo.write("items/gem/item.json", "{}\n");
        let first = repo.publish(Some("v1"));
        (repo, first)
    }

    fn files(dir: &Path) -> Vec<String> {
        let mut found: Vec<String> = walkdir::WalkDir::new(dir)
            .into_iter()
            .filter_map(|e| e.ok())
            .filter(|e| e.file_type().is_file())
            .map(|e| {
                e.path()
                    .strip_prefix(dir)
                    .unwrap()
                    .to_string_lossy()
                    .replace('\\', "/")
            })
            .collect();
        found.sort();
        found
    }

    #[test]
    fn fetches_a_rev_into_the_cache_and_a_pinned_commit_from_it() {
        let (repo, first) = repo();
        assert_eq!(
            fetch(&repo.cache, &repo.url, Some("v1"), None, Ok(())).unwrap(),
            first
        );
        let checkout = checkout_dir(&repo.cache, &first);
        assert_eq!(
            files(&checkout),
            ["items/gem/item.json", "netherforge.json"]
        );
        // The default branch moves on; the pin stays, and needs no repository at all.
        repo.write("items/gem/item.json", "{ \"kind\": \"x\" }\n");
        let second = repo.publish(None);
        assert_eq!(
            fetch(&repo.cache, &repo.url, None, None, Ok(())).unwrap(),
            second
        );
        assert_eq!(
            fetch(&repo.cache, "file:///nowhere", None, Some(&first), Ok(())).unwrap(),
            first
        );
        // A pinned commit that isn't in the cache is fetched by id.
        std::fs::remove_dir_all(checkout_dir(&repo.cache, &second)).unwrap();
        std::fs::remove_dir_all(repo.cache.join("git/db")).unwrap();
        assert_eq!(
            fetch(&repo.cache, &repo.url, Some("main"), Some(&second), Ok(())).unwrap(),
            second
        );
        assert_eq!(
            std::fs::read_to_string(checkout_dir(&repo.cache, &second).join("items/gem/item.json"))
                .unwrap(),
            "{ \"kind\": \"x\" }\n"
        );
    }

    #[test]
    fn reaches_a_repository_only_when_allowed_and_reads_the_cache_regardless() {
        let (repo, first) = repo();
        let refused = || {
            Err(crate::error::Error::new(
                ErrorCode::Untrusted,
                "not trusted",
            ))
        };
        let error = fetch(&repo.cache, &repo.url, Some("v1"), None, refused()).unwrap_err();
        assert_eq!(error.code(), ErrorCode::Untrusted);
        assert!(!repo.cache.join("git").exists(), "git never ran");
        // Bad input is still called bad first.
        let bad = fetch(&repo.cache, "http://example.com/x", None, None, refused()).unwrap_err();
        assert_eq!(bad.code(), ErrorCode::Invalid);
        fetch(&repo.cache, &repo.url, Some("v1"), None, Ok(())).unwrap();
        assert_eq!(
            fetch(&repo.cache, &repo.url, Some("v1"), Some(&first), refused()).unwrap(),
            first,
            "a pinned checkout in the cache needs nothing"
        );
    }

    #[test]
    fn says_what_s_missing() {
        let (repo, _) = repo();
        let missing = fetch(&repo.cache, &repo.url, Some("nope"), None, Ok(())).unwrap_err();
        assert!(missing.message().contains("\"nope\""), "{missing}");
        let gone = fetch(&repo.cache, &repo.url, None, Some(&"f".repeat(40)), Ok(())).unwrap_err();
        assert_eq!(gone.code(), ErrorCode::NotFound, "{gone}");
        for (url, rev) in [
            ("http://example.com/x", None),
            ("ext::sh -c touch% /tmp/pwned", None),
            ("-uhttps://x", None),
            (repo.url.as_str(), Some("-x")),
            (repo.url.as_str(), Some("a:b")),
        ] {
            assert_eq!(
                fetch(&repo.cache, url, rev, None, Ok(()))
                    .unwrap_err()
                    .code(),
                ErrorCode::Invalid,
                "{url} {rev:?}"
            );
        }
    }

    #[cfg(unix)]
    #[test]
    fn writes_only_regular_files_and_runs_nothing_of_the_repository() {
        let (repo, _) = repo();
        std::os::unix::fs::symlink("/etc/passwd", repo.work.join("items/gem/linked.json")).unwrap();
        repo.write(".gitattributes", "* filter=evil\n");
        repo.write("hooks/post-checkout", "#!/bin/sh\ntouch pwned\n");
        let commit = repo.publish(None);
        let fetched = fetch(&repo.cache, &repo.url, Some("main"), None, Ok(())).unwrap();
        assert_eq!(fetched, commit);
        let checkout = checkout_dir(&repo.cache, &commit);
        assert_eq!(
            files(&checkout),
            [
                ".gitattributes",
                "hooks/post-checkout",
                "items/gem/item.json",
                "netherforge.json"
            ]
        );
        // The database is bare and ours: no hooks were copied into it.
        let db = std::fs::read_dir(repo.cache.join("git/db"))
            .unwrap()
            .next()
            .unwrap()
            .unwrap()
            .path();
        assert!(!db.join("hooks").exists());
    }

    #[test]
    fn urls_revs_and_locations() {
        for good in [
            "https://github.com/acme/economy.git",
            "ssh://git@host/x",
            "git@github.com:acme/economy.git",
            "file:///tmp/x.git",
        ] {
            assert!(is_url(good), "{good}");
        }
        for bad in [
            "http://host/x",
            "git://host/x",
            "ext::sh",
            "-u",
            "https://",
            "../economy",
            "host:x",
            "git@host:/abs",
        ] {
            assert!(!is_url(bad), "{bad}");
        }
        for good in ["main", "v1.2.0", "release/1.x"] {
            assert!(is_rev(good), "{good}");
        }
        for bad in [
            "-x", "a..b", "a:b", "a//b", "a/", "a.lock", ".h", "a/.b", "",
        ] {
            assert!(!is_rev(bad), "{bad}");
        }
        let cache = Path::new("/c");
        let commit = "a".repeat(40);
        assert_eq!(
            location_dir(cache, &format!("git:{commit}"))
                .unwrap()
                .unwrap(),
            checkout_dir(cache, &commit)
        );
        assert!(location_dir(cache, "git:abc").unwrap().is_err());
        assert!(location_dir(cache, "../library").is_none());
    }
}
