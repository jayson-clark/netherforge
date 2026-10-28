//! Finding a Java 25+ runtime, or downloading Eclipse Temurin into
//! `<data>/jdks/`.
//!
//! Search order: JAVA_HOME, JDKs we downloaded, `java` on PATH, then the usual
//! install folders per OS. A JDK home's `release` file gives the version
//! without starting a JVM; otherwise we run `java -version` (which prints to
//! stderr) and parse it. On macOS `/usr/bin/java` is a stub that fails when no
//! JDK is installed; that just parses as "not found".

use std::path::{Path, PathBuf};
use std::time::Duration;

use serde::Deserialize;

use crate::app::dirs::AppDirs;
use crate::error::{Context, Error, Result, bail};
use crate::platform::{Os, env_path, path_dirs};

use super::{PrepareStep, Progress, download, progress};

pub const REQUIRED_MAJOR: u32 = 25;

/// The major version in `java -version` output: `openjdk version "25.0.1"`,
/// `java version "1.8.0_391"` (→ 8), `openjdk version "26-ea"`.
pub fn parse_version_output(output: &str) -> Option<u32> {
    output.lines().find_map(|line| {
        if !line.contains(" version \"") {
            return None;
        }
        let quoted = line.split('"').nth(1)?;
        major_of(quoted)
    })
}

/// The major version in a JDK's `release` file (`JAVA_VERSION="25.0.1"`).
pub fn parse_release_file(text: &str) -> Option<u32> {
    text.lines().find_map(|line| {
        let value = line.strip_prefix("JAVA_VERSION=")?;
        major_of(value.trim().trim_matches('"'))
    })
}

fn major_of(version: &str) -> Option<u32> {
    let mut parts = version.split(|c: char| !c.is_ascii_digit());
    let first: u32 = parts.next()?.parse().ok()?;
    if first == 1 {
        parts.next()?.parse().ok()
    } else {
        Some(first)
    }
}

pub fn executable_name(os: Os) -> &'static str {
    if os == Os::Windows {
        "java.exe"
    } else {
        "java"
    }
}

/// The `java` binary inside a JDK folder (a home, or a macOS `.jdk` bundle).
pub fn java_in(dir: &Path, os: Os) -> Option<PathBuf> {
    let exe = executable_name(os);
    [
        dir.join("bin").join(exe),
        dir.join("Contents/Home/bin").join(exe),
    ]
    .into_iter()
    .find(|p| p.is_file())
}

/// Where to look; a pure function of the environment so it's testable.
#[derive(Debug, Clone)]
pub struct JavaEnv {
    pub os: Os,
    pub home: PathBuf,
    /// `/` for real; a temp folder in tests.
    pub system_root: PathBuf,
    pub java_home: Option<PathBuf>,
    pub path_dirs: Vec<PathBuf>,
    pub managed_jdks: PathBuf,
    /// Windows `%ProgramFiles%` and `%LOCALAPPDATA%`.
    pub program_files: Option<PathBuf>,
    pub local_appdata: Option<PathBuf>,
}

impl JavaEnv {
    pub fn current(dirs: &AppDirs) -> Self {
        let os = Os::current();
        Self {
            os,
            home: dirs::home_dir().unwrap_or_default(),
            system_root: os.system_root(),
            java_home: env_path("JAVA_HOME"),
            path_dirs: path_dirs(),
            managed_jdks: dirs.jdks(),
            program_files: env_path("ProgramFiles"),
            local_appdata: env_path("LOCALAPPDATA"),
        }
    }
}

/// Subfolders of [dir], newest-looking names first.
fn children(dir: &Path) -> Vec<PathBuf> {
    let mut list: Vec<PathBuf> = std::fs::read_dir(dir)
        .map(|entries| {
            entries
                .flatten()
                .map(|e| e.path())
                .filter(|p| p.is_dir())
                .collect()
        })
        .unwrap_or_default();
    list.sort();
    list.reverse();
    list
}

/// Candidate `java` binaries, in search order, deduplicated.
pub fn candidates(env: &JavaEnv) -> Vec<PathBuf> {
    let mut homes: Vec<PathBuf> = Vec::new();
    homes.extend(env.java_home.clone());
    homes.extend(children(&env.managed_jdks));

    let mut list: Vec<PathBuf> = homes.iter().filter_map(|h| java_in(h, env.os)).collect();
    for dir in &env.path_dirs {
        let exe = dir.join(executable_name(env.os));
        if exe.is_file() {
            list.push(exe);
        }
    }

    let mut roots: Vec<PathBuf> = vec![
        env.home.join(".sdkman/candidates/java"),
        env.home.join(".jdks"),
    ];
    match env.os {
        Os::MacOs => {
            roots.push(env.system_root.join("Library/Java/JavaVirtualMachines"));
            roots.push(env.home.join("Library/Java/JavaVirtualMachines"));
            for brew in ["opt/homebrew/opt", "usr/local/opt"] {
                for formula in children(&env.system_root.join(brew)) {
                    let name = formula.file_name().unwrap_or_default().to_string_lossy();
                    if name.starts_with("openjdk") {
                        list.extend(java_in(&formula.join("libexec/openjdk.jdk"), env.os));
                    }
                }
            }
        }
        Os::Linux => {
            for dir in ["usr/lib/jvm", "usr/java", "opt/java", "opt/jdk"] {
                roots.push(env.system_root.join(dir));
            }
        }
        Os::Windows => {
            let program_files = env
                .program_files
                .clone()
                .unwrap_or_else(|| env.system_root.join("Program Files"));
            for vendor in [
                "Eclipse Adoptium",
                "Java",
                "Microsoft",
                "Zulu",
                "Amazon Corretto",
                "BellSoft",
                "Semeru",
            ] {
                roots.push(program_files.join(vendor));
            }
            if let Some(local) = &env.local_appdata {
                roots.push(local.join("Programs/Eclipse Adoptium"));
            }
        }
    }
    for root in roots {
        list.extend(children(&root).iter().filter_map(|h| java_in(h, env.os)));
    }

    let mut seen = Vec::new();
    list.retain(|p| {
        let key = dunce::canonicalize(p).unwrap_or_else(|_| p.clone());
        if seen.contains(&key) {
            false
        } else {
            seen.push(key);
            true
        }
    });
    list
}

/// The JDK home a `.../bin/java` belongs to.
fn home_of(java: &Path) -> Option<&Path> {
    java.parent()?.parent()
}

/// The major version of [java], from its `release` file or by running it.
pub async fn version_of(java: &Path) -> Option<u32> {
    if let Some(home) = home_of(java)
        && let Ok(text) = std::fs::read_to_string(home.join("release"))
        && let Some(major) = parse_release_file(&text)
    {
        return Some(major);
    }
    let mut command = tokio::process::Command::new(java);
    command.arg("-version").kill_on_drop(true);
    super::process::hide_console(&mut command);
    let output = tokio::time::timeout(Duration::from_secs(15), command.output())
        .await
        .ok()?
        .ok()?;
    let text = format!(
        "{}\n{}",
        String::from_utf8_lossy(&output.stderr),
        String::from_utf8_lossy(&output.stdout)
    );
    parse_version_output(&text)
}

/// The first Java ≥ [REQUIRED_MAJOR] in [candidates] order.
pub async fn find(env: &JavaEnv) -> Option<(PathBuf, u32)> {
    for java in candidates(env) {
        if let Some(major) = version_of(&java).await
            && major >= REQUIRED_MAJOR
        {
            return Some((java, major));
        }
    }
    None
}

// ---- Adoptium (Temurin) download -------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JdkPackage {
    pub release_name: String,
    pub name: String,
    pub link: String,
    pub checksum: String,
    pub size: u64,
}

#[derive(Deserialize)]
struct AdoptiumAsset {
    binary: AdoptiumBinary,
    release_name: String,
}

#[derive(Deserialize)]
struct AdoptiumBinary {
    package: AdoptiumPackage,
}

#[derive(Deserialize)]
struct AdoptiumPackage {
    checksum: String,
    link: String,
    name: String,
    size: u64,
}

pub fn adoptium_os_arch(os: Os, arch: &str) -> Result<(&'static str, &'static str)> {
    let os = match os {
        Os::MacOs => "mac",
        Os::Windows => "windows",
        Os::Linux => "linux",
    };
    let arch = match arch {
        "x86_64" => "x64",
        "aarch64" => "aarch64",
        other => bail!(
            "There's no Temurin JDK download for {other}; install Java {REQUIRED_MAJOR} yourself"
        ),
    };
    Ok((os, arch))
}

pub fn adoptium_url(os: &str, arch: &str) -> String {
    format!(
        "https://api.adoptium.net/v3/assets/latest/{REQUIRED_MAJOR}/hotspot?architecture={arch}&image_type=jdk&os={os}&vendor=eclipse"
    )
}

/// The JDK archive (not the installer) from an Adoptium `assets/latest` response.
pub fn parse_adoptium(json: &str) -> Result<JdkPackage> {
    let assets: Vec<AdoptiumAsset> =
        serde_json::from_str(json).context(|| "Unexpected answer from the Adoptium API".into())?;
    let asset = assets
        .into_iter()
        .next()
        .context(|| format!("Adoptium has no Java {REQUIRED_MAJOR} JDK for this OS"))?;
    let package = asset.binary.package;
    if !(package.name.ends_with(".tar.gz") || package.name.ends_with(".zip")) {
        bail!("Unexpected JDK archive {}", package.name);
    }
    Ok(JdkPackage {
        release_name: asset.release_name,
        name: package.name,
        link: package.link,
        checksum: package.checksum,
        size: package.size,
    })
}

/// Downloads and unpacks Temurin into `<data>/jdks/<release>/`; returns its `java`.
pub async fn install(
    dirs: &AppDirs,
    client: &reqwest::Client,
    report: Progress<'_>,
    cancelled: &(dyn Fn() -> bool + Send + Sync),
) -> Result<PathBuf> {
    let os = JavaEnv::current(dirs).os;
    let (os_name, arch) = adoptium_os_arch(os, std::env::consts::ARCH)?;
    report(progress(
        PrepareStep::Java,
        "Looking up the latest Java download",
        0,
        None,
    ));
    let json = client
        .get(adoptium_url(os_name, arch))
        .send()
        .await
        .and_then(|r| r.error_for_status())
        .context(|| "Couldn't reach the Adoptium API to download Java".into())?
        .text()
        .await?;
    let package = parse_adoptium(&json)?;
    if !is_safe_name(&package.release_name) || !is_safe_name(&package.name) {
        bail!("Unexpected JDK name {}", package.release_name);
    }

    let jdks = dirs.jdks();
    let home = jdks.join(&package.release_name);
    if let Some(java) = java_in(&home, os) {
        return Ok(java);
    }
    let archive = jdks.join(&package.name);
    let label = format!("Downloading Java ({})", package.release_name);
    download::download(
        client,
        &package.link,
        &archive,
        Some(&package.checksum),
        &|done, total| {
            report(progress(
                PrepareStep::Java,
                label.clone(),
                done,
                total.or(Some(package.size)),
            ))
        },
        cancelled,
    )
    .await?;

    report(progress(PrepareStep::Java, "Unpacking Java", 0, None));
    let (archive_clone, home_clone) = (archive.clone(), home.clone());
    tokio::task::spawn_blocking(move || unpack_jdk(&archive_clone, &home_clone))
        .await
        .map_err(|e| Error::msg(e.to_string()))??;
    let _ = std::fs::remove_file(&archive);
    java_in(&home, os)
        .context(|| format!("The Java download has no java binary ({})", home.display()))
}

fn is_safe_name(name: &str) -> bool {
    !name.is_empty() && !name.contains(['/', '\\', ':']) && name != "." && name != ".."
}

/// Unpacks a `.tar.gz` or `.zip` JDK into [home], flattening its single top folder.
pub fn unpack_jdk(archive: &Path, home: &Path) -> Result<()> {
    let temp = crate::fs::atomic::sibling_temp(home);
    let result = (|| -> Result<()> {
        std::fs::create_dir_all(&temp)?;
        let file = std::fs::File::open(archive)?;
        let name = archive.to_string_lossy();
        if name.ends_with(".zip") {
            zip::ZipArchive::new(std::io::BufReader::new(file))?.extract(&temp)?;
        } else {
            let mut tar = tar::Archive::new(flate2::read::GzDecoder::new(file));
            tar.set_preserve_permissions(true);
            tar.unpack(&temp)
                .context(|| format!("Couldn't unpack {}", archive.display()))?;
        }
        let entries: Vec<PathBuf> = std::fs::read_dir(&temp)?
            .flatten()
            .map(|e| e.path())
            .filter(|p| {
                !p.file_name()
                    .is_some_and(|n| n.to_string_lossy().starts_with("._"))
            })
            .collect();
        let top = match entries.as_slice() {
            [single] if single.is_dir() => single.clone(),
            _ => temp.clone(),
        };
        if home.exists() {
            std::fs::remove_dir_all(home)?;
        }
        std::fs::rename(&top, home)?;
        make_executable(&home.join("bin"));
        make_executable(&home.join("Contents/Home/bin"));
        Ok(())
    })();
    let _ = std::fs::remove_dir_all(&temp);
    result
}

#[cfg(unix)]
fn make_executable(bin: &Path) {
    use std::os::unix::fs::PermissionsExt;
    for entry in std::fs::read_dir(bin).into_iter().flatten().flatten() {
        if let Ok(meta) = entry.metadata() {
            let mut perms = meta.permissions();
            perms.set_mode(perms.mode() | 0o755);
            let _ = std::fs::set_permissions(entry.path(), perms);
        }
    }
}

#[cfg(not(unix))]
fn make_executable(_bin: &Path) {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_java_version_output() {
        let cases = [
            (
                "openjdk version \"25.0.1\" 2025-10-21 LTS\nOpenJDK Runtime Environment Temurin-25.0.1+8 (build 25.0.1+8-LTS)\n",
                Some(25),
            ),
            ("openjdk version \"25\" 2025-09-16\n", Some(25)),
            ("java version \"21.0.4\" 2024-07-16 LTS\n", Some(21)),
            (
                "java version \"1.8.0_391\"\nJava(TM) SE Runtime Environment\n",
                Some(8),
            ),
            ("openjdk version \"26-ea\" 2026-03-17\n", Some(26)),
            (
                "Picked up JAVA_TOOL_OPTIONS: -Dfile.encoding=UTF-8\nopenjdk version \"25.0.4.1\" 2026-08-18\n",
                Some(25),
            ),
            (
                "The operation couldn’t be completed. Unable to locate a Java Runtime.\n",
                None,
            ),
            ("", None),
        ];
        for (text, expected) in cases {
            assert_eq!(parse_version_output(text), expected, "{text}");
        }
    }

    #[test]
    fn parses_release_files() {
        assert_eq!(
            parse_release_file("IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"25.0.4.1\"\n"),
            Some(25)
        );
        assert_eq!(parse_release_file("JAVA_VERSION=\"1.8.0_392\""), Some(8));
        assert_eq!(parse_release_file("OS_NAME=\"Darwin\""), None);
    }

    fn fake_jdk(home: &Path, os: Os, version: &str) {
        let bin = home.join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        std::fs::write(bin.join(executable_name(os)), "").unwrap();
        std::fs::write(
            home.join("release"),
            format!("JAVA_VERSION=\"{version}\"\n"),
        )
        .unwrap();
    }

    fn env(os: Os, tmp: &Path) -> JavaEnv {
        JavaEnv {
            os,
            home: tmp.join("home"),
            system_root: tmp.join("root"),
            java_home: None,
            path_dirs: vec![],
            managed_jdks: tmp.join("data/jdks"),
            program_files: None,
            local_appdata: None,
        }
    }

    #[tokio::test]
    async fn finds_the_first_new_enough_java_on_macos() {
        let tmp = tempfile::tempdir().unwrap();
        let e = env(Os::MacOs, tmp.path());
        let vms = e.system_root.join("Library/Java/JavaVirtualMachines");
        fake_jdk(
            &vms.join("temurin-21.jdk/Contents/Home"),
            Os::MacOs,
            "21.0.4",
        );
        fake_jdk(
            &vms.join("temurin-25.jdk/Contents/Home"),
            Os::MacOs,
            "25.0.1",
        );
        let brew = e
            .system_root
            .join("opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home");
        fake_jdk(&brew, Os::MacOs, "26");

        let found = candidates(&e);
        assert_eq!(found.len(), 3);
        let (java, major) = find(&e).await.unwrap();
        assert_eq!(major, 26, "homebrew comes before the system folder");
        assert!(java.starts_with(&brew));

        // JAVA_HOME wins when it's new enough, and is skipped when it isn't.
        let mut e2 = e.clone();
        e2.java_home = Some(vms.join("temurin-25.jdk/Contents/Home"));
        assert_eq!(find(&e2).await.unwrap().1, 25);
        e2.java_home = Some(vms.join("temurin-21.jdk/Contents/Home"));
        assert_eq!(find(&e2).await.unwrap().1, 26);
    }

    #[tokio::test]
    async fn finds_managed_and_os_specific_jdks() {
        let tmp = tempfile::tempdir().unwrap();
        let e = env(Os::Linux, tmp.path());
        fake_jdk(
            &e.system_root.join("usr/lib/jvm/java-17-openjdk"),
            Os::Linux,
            "17.0.2",
        );
        assert!(find(&e).await.is_none());
        fake_jdk(
            &e.managed_jdks.join("jdk-25.0.4.1+1"),
            Os::Linux,
            "25.0.4.1",
        );
        let (java, _) = find(&e).await.unwrap();
        assert!(java.starts_with(&e.managed_jdks));

        let w = env(Os::Windows, tmp.path());
        let adoptium = w
            .system_root
            .join("Program Files/Eclipse Adoptium/jdk-25.0.1.8-hotspot");
        fake_jdk(&adoptium, Os::Windows, "25.0.1");
        let found = candidates(&w);
        assert!(
            found
                .iter()
                .any(|p| p.ends_with("bin/java.exe") && p.starts_with(&adoptium))
        );
    }

    #[test]
    fn parses_the_adoptium_fixture() {
        let json = std::fs::read_to_string(
            Path::new(env!("CARGO_MANIFEST_DIR")).join("testdata/adoptium-latest-windows-x64.json"),
        )
        .unwrap();
        let package = parse_adoptium(&json).unwrap();
        assert!(package.release_name.starts_with("jdk-25"));
        assert!(package.name.ends_with(".zip"));
        assert!(package.link.starts_with("https://"));
        assert_eq!(package.checksum.len(), 64);
        assert!(package.size > 0);
        assert!(parse_adoptium("[]").is_err());
        assert_eq!(
            adoptium_os_arch(Os::MacOs, "aarch64").unwrap(),
            ("mac", "aarch64")
        );
        assert!(adoptium_os_arch(Os::Linux, "riscv64").is_err());
    }

    #[test]
    fn unpacks_a_tar_gz_jdk_and_flattens_it() {
        let tmp = tempfile::tempdir().unwrap();
        let archive = tmp.path().join("jdk.tar.gz");
        {
            let gz = flate2::write::GzEncoder::new(
                std::fs::File::create(&archive).unwrap(),
                flate2::Compression::fast(),
            );
            let mut tar = tar::Builder::new(gz);
            let mut header = tar::Header::new_gnu();
            let body = b"#!/bin/sh\n";
            header.set_size(body.len() as u64);
            header.set_mode(0o755);
            header.set_cksum();
            tar.append_data(&mut header, "jdk-25+36/bin/java", &body[..])
                .unwrap();
            tar.into_inner().unwrap().finish().unwrap();
        }
        let home = tmp.path().join("jdks/jdk-25+36");
        unpack_jdk(&archive, &home).unwrap();
        assert!(java_in(&home, Os::Linux).is_some());
        assert_eq!(
            std::fs::read_dir(tmp.path().join("jdks")).unwrap().count(),
            1
        );
    }
    /// Looks for Java on this machine: `cargo test -- --ignored --nocapture`.
    #[tokio::test]
    #[ignore = "depends on the machine"]
    async fn finds_java_on_this_machine() {
        let tmp = tempfile::tempdir().unwrap();
        let env = JavaEnv::current(&AppDirs::in_one(tmp.path()));
        for java in candidates(&env) {
            println!("{} -> {:?}", java.display(), version_of(&java).await);
        }
        println!("chosen: {:?}", find(&env).await);
    }
}
