//! Downloading Paper (and, for a dev network, Velocity) through PaperMC's
//! Fill API (v3), cached per build in `<data>/paper/paper-<version>-<build>.jar`
//! (`velocity-<version>-<build>.jar`).
//!
//! The build is the one the plugin jar was tested against (its
//! `META-INF/netherforge/paper.properties`, see `plugins::paper_build`), never
//! `builds/latest`: a newer Paper build can change the server internals the
//! adapter reaches into. Once cached, a build starts without the network.
//! Each download is verified against the API's sha256.

use std::path::PathBuf;

use serde::Deserialize;

use crate::app::dirs::AppDirs;
use crate::error::{Context, Result, bail};

use super::{PrepareStep, Progress, download, progress};

const API: &str = "https://fill.papermc.io/v3/projects";

/// What Fill serves that the editor runs.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Project {
    Paper,
    /// A dev network's proxy: the build the proxy plugin names (`plugins::velocity_build`).
    Velocity,
}

impl Project {
    fn id(self) -> &'static str {
        match self {
            Self::Paper => "paper",
            Self::Velocity => "velocity",
        }
    }

    fn label(self) -> &'static str {
        match self {
            Self::Paper => "Paper",
            Self::Velocity => "Velocity",
        }
    }

    fn step(self) -> PrepareStep {
        match self {
            Self::Paper => PrepareStep::Paper,
            Self::Velocity => PrepareStep::Proxy,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PaperBuild {
    pub id: u64,
    pub channel: String,
    pub name: String,
    pub sha256: String,
    pub size: u64,
    pub url: String,
}

#[derive(Deserialize)]
struct BuildJson {
    id: u64,
    #[serde(default)]
    channel: String,
    downloads: std::collections::HashMap<String, DownloadJson>,
}

#[derive(Deserialize)]
struct DownloadJson {
    name: String,
    checksums: Checksums,
    #[serde(default)]
    size: u64,
    url: String,
}

#[derive(Deserialize)]
struct Checksums {
    sha256: String,
}

pub fn build_url(minecraft: &str, build: u64) -> String {
    project_build_url(Project::Paper, minecraft, build)
}

fn project_build_url(project: Project, version: &str, build: u64) -> String {
    format!("{API}/{}/versions/{version}/builds/{build}", project.id())
}

/// The server download of a Fill `builds/<id>` response.
pub fn parse_build(json: &str) -> Result<PaperBuild> {
    let build: BuildJson =
        serde_json::from_str(json).context(|| "Unexpected answer from the PaperMC API".into())?;
    let mut downloads = build.downloads;
    let download = downloads
        .remove("server:default")
        .context(|| format!("Build {} has no server download", build.id))?;
    if download.name.contains(['/', '\\']) || !download.name.ends_with(".jar") {
        bail!("Unexpected download name {}", download.name);
    }
    Ok(PaperBuild {
        id: build.id,
        channel: build.channel,
        name: download.name,
        sha256: download.checksums.sha256,
        size: download.size,
        url: download.url,
    })
}

fn cached_name(project: Project, version: &str, build: u64) -> String {
    format!("{}-{version}-{build}.jar", project.id())
}

/// The Paper jar to run for [minecraft]'s [build], downloading it if it
/// isn't cached.
pub async fn ensure(
    dirs: &AppDirs,
    client: &reqwest::Client,
    minecraft: &str,
    build: u64,
    report: Progress<'_>,
    cancelled: &(dyn Fn() -> bool + Send + Sync),
) -> Result<PathBuf> {
    ensure_project(
        dirs,
        client,
        Project::Paper,
        minecraft,
        build,
        report,
        cancelled,
    )
    .await
}

/// The jar of [project] [version]'s [build], downloading it if it isn't cached.
pub async fn ensure_project(
    dirs: &AppDirs,
    client: &reqwest::Client,
    project: Project,
    version: &str,
    build: u64,
    report: Progress<'_>,
    cancelled: &(dyn Fn() -> bool + Send + Sync),
) -> Result<PathBuf> {
    // Only a download whose sha256 matched is renamed into place.
    let path = dirs.paper().join(cached_name(project, version, build));
    if path.is_file() {
        return Ok(path);
    }
    let name = project.label();
    report(progress(
        project.step(),
        format!("Looking up {name} {version} build {build}"),
        0,
        None,
    ));
    let found = fetch_build(client, project, version, build).await?;
    let label = format!("Downloading {name} {version} (build {})", found.id);
    download::download(
        client,
        &found.url,
        &path,
        Some(&found.sha256),
        &|done, total| {
            report(progress(
                project.step(),
                label.clone(),
                done,
                total.or(Some(found.size)),
            ))
        },
        cancelled,
    )
    .await?;
    Ok(path)
}

async fn fetch_build(
    client: &reqwest::Client,
    project: Project,
    version: &str,
    id: u64,
) -> Result<PaperBuild> {
    let name = project.label();
    let response = client
        .get(project_build_url(project, version, id))
        .send()
        .await
        .context(|| {
            format!("Couldn't reach the PaperMC API to download {name} {version} build {id}")
        })?;
    if response.status() == reqwest::StatusCode::NOT_FOUND {
        match project {
            Project::Paper => bail!("Paper has no build {id} for Minecraft {version}"),
            Project::Velocity => bail!("Velocity {version} has no build {id}"),
        }
    }
    if !response.status().is_success() {
        bail!("The PaperMC API answered HTTP {}", response.status());
    }
    let build = parse_build(&response.text().await?)?;
    if build.id != id {
        bail!("The PaperMC API answered build {} for build {id}", build.id);
    }
    Ok(build)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_the_fill_fixture() {
        let json = std::fs::read_to_string(
            std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("testdata/paper-build-latest.json"),
        )
        .unwrap();
        let build = parse_build(&json).unwrap();
        assert!(build.id > 0);
        assert_eq!(build.name, format!("paper-26.3-{}.jar", build.id));
        assert_eq!(build.sha256.len(), 64);
        assert!(build.url.starts_with("https://") && build.url.ends_with(&build.name));
        assert!(build.size > 0);
        assert_eq!(
            build_url("26.3", 152),
            "https://fill.papermc.io/v3/projects/paper/versions/26.3/builds/152"
        );
        assert_eq!(
            project_build_url(Project::Velocity, "4.3.0", 44),
            "https://fill.papermc.io/v3/projects/velocity/versions/4.3.0/builds/44"
        );
        assert_eq!(
            cached_name(Project::Velocity, "4.3.0", 44),
            "velocity-4.3.0-44.jar"
        );
    }

    #[test]
    fn rejects_odd_responses() {
        assert!(parse_build(r#"{"ok":false,"error":"version_not_found"}"#).is_err());
        assert!(parse_build(r#"{"id":1,"downloads":{}}"#).is_err());
        let traversal = r#"{"id":1,"downloads":{"server:default":{"name":"../x.jar","checksums":{"sha256":"00"},"url":"https://x"}}}"#;
        assert!(parse_build(traversal).is_err());
    }

    /// Hits the real Fill API and downloads Paper: `cargo test -- --ignored`.
    #[tokio::test]
    #[ignore = "network"]
    async fn downloads_a_paper_build() {
        let tmp = tempfile::tempdir().unwrap();
        let dirs = AppDirs::in_one(tmp.path());
        let client = download::http_client().unwrap();
        let jar = ensure(&dirs, &client, "26.3", 152, &|_| {}, &|| false)
            .await
            .unwrap();
        assert_eq!(jar.file_name().unwrap(), "paper-26.3-152.jar");
        assert!(jar.metadata().unwrap().len() > 1_000_000);
        let again = ensure(&dirs, &client, "26.3", 152, &|_| {}, &|| false)
            .await
            .unwrap();
        assert_eq!(jar, again);
        let missing = ensure(&dirs, &client, "9.9", 1, &|_| {}, &|| false).await;
        assert!(missing.unwrap_err().message().contains("no build"));
    }
}
