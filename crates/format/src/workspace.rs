//! `netherforge-workspace.json` (format's `workspace`): several projects kept
//! together, at the top of a folder that holds them (a server network's
//! repository: a project per server, packages they share). It's not a project:
//! it names them, and the dev network the editor runs from them. See
//! `docs/format/workspace.md`. And the network names scripts use
//! (`NetworkUses`), which only a workspace can check.

use serde::{Deserialize, Serialize};

use crate::canonical::Map;
use crate::lua::{Token, TokenKind, tokens};
use crate::problem::{Problem, ProblemSink, codes};

pub const FILE_NAME: &str = "netherforge-workspace.json";
pub const SCHEMA: &str = ".netherforge/schema/workspace.schema.json";

/// The roles a dev network's server may join with.
pub const ROLES: [&str; 3] = ["trusted", "standard", "isolated"];

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, schemars::JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct WorkspaceFile {
    #[serde(rename = "$schema", skip_serializing_if = "Option::is_none")]
    pub schema: Option<String>,
    /// What the workspace is called, as the editor shows it.
    pub name: String,
    /// Its projects and packages: folders relative to this file (`servers/hub`), or every project directly in a
    /// folder (`servers/` and a `*`). In the order the editor lists them.
    pub projects: Vec<String>,
    /// The dev network the editor runs: which project each server runs, and where players arrive.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub network: Option<WorkspaceNetwork>,
}

/// A workspace's dev network: its servers by name, as the proxy names them, and the one players arrive on.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize, schemars::JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct WorkspaceNetwork {
    /// The server players arrive on when they join: one of `servers`.
    pub arrival: String,
    /// Each server's name in the network → the project it runs (one of the workspace's, as `projects` names it).
    pub servers: Map<String>,
    /// What some servers are besides: by name (one of `servers`), the group, capacity and role each runs with.
    #[serde(default, skip_serializing_if = "Map::is_empty")]
    pub options: Map<WorkspaceServerOptions>,
}

/// A dev network's server as a production network would run it: an instance of `group` taking `capacity`
/// players, and joining with a credential of `role` (`trusted`, the default, `standard` or `isolated`) that owns
/// its project's namespace, so the hub holds it to what that role may do.
#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize, schemars::JsonSchema)]
#[serde(deny_unknown_fields)]
pub struct WorkspaceServerOptions {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub group: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub capacity: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub role: Option<String>,
}

impl WorkspaceFile {
    /// `text` as a workspace file, or the problem with reading it.
    pub fn parse(text: &str) -> Result<WorkspaceFile, Box<Problem>> {
        crate::read::parse(text, FILE_NAME)
    }

    /// Its canonical form: the schema set, and a project listed twice listed once, where it first was (the order
    /// is the editor's list).
    pub fn canonical(&self) -> WorkspaceFile {
        let mut projects: Vec<String> = Vec::new();
        for p in self.projects.iter().map(|p| normalize(p)) {
            if !projects.contains(&p) {
                projects.push(p);
            }
        }
        WorkspaceFile {
            schema: Some(SCHEMA.into()),
            projects,
            ..self.clone()
        }
    }

    /// The file, canonically.
    pub fn write(&self) -> String {
        crate::canonical::write(&self.canonical())
    }
}

/// A server's name in a dev network: a folder name and a `velocity.toml` key, so narrower than a network's names.
pub fn is_server_name(name: &str) -> bool {
    (1..=32).contains(&name.len())
        && name
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_' || b == b'-')
}

/// One folder name: no dots alone, no separators, nothing Windows refuses.
fn is_segment(name: &str) -> bool {
    let mut chars = name.chars();
    chars
        .next()
        .is_some_and(|c| c.is_ascii_alphanumeric() || c == '_')
        && chars.all(|c| c.is_ascii_alphanumeric() || "_. -".contains(c))
        && !name.ends_with('.')
        && !name.ends_with(' ')
}

/// `pattern` with no trailing `/` and no `./` in front, as it's compared.
pub fn normalize(pattern: &str) -> String {
    let trimmed = pattern.trim();
    trimmed
        .strip_prefix("./")
        .unwrap_or(trimmed)
        .trim_end_matches('/')
        .to_string()
}

/// Whether `pattern` can name projects: a relative folder (`servers/hub`), or one followed by `/` and a `*` for
/// every project directly in it. Never `..`, absolute, or a backslash: a workspace holds what's under it.
pub fn is_pattern(pattern: &str) -> bool {
    let normal = normalize(pattern);
    if normal.is_empty() {
        return false;
    }
    let parts: Vec<&str> = normal.split('/').collect();
    let folders = match parts.split_last() {
        Some((&"*", rest)) => rest,
        _ => &parts[..],
    };
    folders.iter().all(|f| is_segment(f))
}

fn is_wildcard(pattern: &str) -> bool {
    pattern == "*" || pattern.ends_with("/*")
}

/// The project folders `patterns` name, in order, from `projects`: every folder under the workspace (relative,
/// `/`-separated) that holds a `netherforge.json`. A plain folder is named whether it's a project or not (the
/// check says so); a folder and `*` names the projects directly in it.
pub fn expand(patterns: &[String], projects: &[String]) -> Vec<String> {
    let mut found: Vec<String> = Vec::new();
    let mut add = |p: String| {
        if !found.contains(&p) {
            found.push(p);
        }
    };
    for raw in patterns {
        let pattern = normalize(raw);
        if !is_pattern(&pattern) {
            continue;
        }
        if !is_wildcard(&pattern) {
            add(pattern);
            continue;
        }
        let parent = pattern
            .strip_suffix('*')
            .map(|p| p.strip_suffix('/').unwrap_or(p))
            .unwrap_or_default();
        let mut inside: Vec<&String> = projects
            .iter()
            .filter(|folder| {
                if parent.is_empty() {
                    !folder.contains('/')
                } else {
                    folder
                        .strip_prefix(&format!("{parent}/"))
                        .is_some_and(|rest| !rest.contains('/'))
                }
            })
            .collect();
        inside.sort();
        for folder in inside {
            add(folder.clone());
        }
    }
    found
}

/// A workspace file as checked: its problems, and the project folders it names that are projects.
#[derive(Clone, Debug, PartialEq)]
pub struct WorkspaceCheck {
    pub members: Vec<String>,
    pub problems: Vec<Problem>,
}

/// Checks `workspace` against `projects` (as `expand` takes them) and answers its members.
pub fn check(workspace: &WorkspaceFile, projects: &[String]) -> WorkspaceCheck {
    let mut sink = ProblemSink::new(FILE_NAME);
    if workspace.name.trim().is_empty() {
        sink.report(
            codes::WORKSPACE_NAME,
            "The workspace has no name.",
            Some("$.name".into()),
        );
    }
    let mut members: Vec<String> = Vec::new();
    for (index, raw) in workspace.projects.iter().enumerate() {
        let path = Some(format!("$.projects[{index}]"));
        let pattern = normalize(raw);
        if !is_pattern(&pattern) {
            sink.report(
                codes::WORKSPACE_PROJECT_PATH,
                format!("\"{raw}\" isn't a folder in the workspace: a relative path like servers/hub, or servers/* for every project in a folder."),
                path,
            );
        } else if is_wildcard(&pattern) {
            let named = expand(std::slice::from_ref(&pattern), projects);
            if named.is_empty() {
                sink.report(
                    codes::WORKSPACE_EMPTY_PATTERN,
                    format!(
                        "No project is directly in {}.",
                        pattern.strip_suffix("/*").unwrap_or(&pattern)
                    ),
                    path,
                );
            }
            members.extend(named);
        } else if !projects.contains(&pattern) {
            sink.report(
                codes::WORKSPACE_MISSING_PROJECT,
                format!("{pattern} isn't a project: it has no netherforge.json."),
                path,
            );
        } else {
            members.push(pattern);
        }
    }
    let mut distinct: Vec<String> = Vec::new();
    for m in members {
        if !distinct.contains(&m) {
            distinct.push(m);
        }
    }
    if let Some(network) = &workspace.network {
        check_network(network, &distinct, &mut sink);
    }
    WorkspaceCheck {
        members: distinct,
        problems: sink.problems,
    }
}

fn check_network(network: &WorkspaceNetwork, members: &[String], sink: &mut ProblemSink) {
    let child = crate::json::child_path;
    for (name, project) in network.servers.iter() {
        let path = Some(child("$.network.servers", &name.0));
        if !is_server_name(&name.0) {
            sink.report(
                codes::WORKSPACE_SERVER_NAME,
                format!(
                    "\"{}\" can't name a server: lowercase letters, digits, _ and - (at most 32).",
                    name.0
                ),
                path.clone(),
            );
        }
        if !members.contains(&normalize(project)) {
            sink.report(
                codes::WORKSPACE_SERVER_PROJECT,
                format!(
                    "Server \"{}\" runs {project}, which isn't one of the workspace's projects.",
                    name.0
                ),
                path,
            );
        }
    }
    for (name, options) in network.options.iter() {
        let name = &name.0;
        let why = if network.servers.get_str(name).is_none() {
            Some(format!("\"{name}\" isn't one of the network's servers."))
        } else if let Some(group) = options.group.as_ref().filter(|g| !is_server_name(g)) {
            Some(format!(
                "Server \"{name}\"'s group \"{group}\" can't name a group: lowercase letters, digits, _ and - (at most 32)."
            ))
        } else if let Some(capacity) = options.capacity.filter(|c| *c < 1) {
            Some(format!(
                "Server \"{name}\"'s capacity is {capacity}: at least 1 player."
            ))
        } else {
            options
                .role
                .as_ref()
                .filter(|r| !ROLES.contains(&r.as_str()))
                .map(|role| {
                    format!(
                        "Server \"{name}\"'s role \"{role}\" isn't trusted, standard or isolated."
                    )
                })
        };
        if let Some(why) = why {
            sink.report(
                codes::WORKSPACE_SERVER_OPTIONS,
                why,
                Some(child("$.network.options", name)),
            );
        }
    }
    if network.servers.get_str(&network.arrival).is_none() {
        sink.report(
            codes::WORKSPACE_ARRIVAL,
            format!(
                "Players arrive on \"{}\", which isn't one of the network's servers.",
                network.arrival
            ),
            Some("$.network.arrival".into()),
        );
    }
}

/// What a name a script gives `nf.network` names.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum NetworkUseKind {
    /// A server it sends players to (`send_player(player, "chess")`).
    Server,
    /// A channel it gets (`channel("games")`).
    Channel,
    /// A shared map it gets (`map("seats")`).
    #[serde(rename = "network_map")]
    Map,
}

/// A name a script gives `nf.network` as a string literal, and its line.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct NetworkUse {
    pub kind: NetworkUseKind,
    pub name: String,
    pub line: usize,
}

fn is_punct(token: &Token, text: &str) -> bool {
    token.kind == TokenKind::Punct && token.text == text
}

/// The network names scripts use, read from their source: every call of
/// `send_player`, `channel` or `map` on `nf.network` (or on a local named
/// `network`) whose name argument is a string literal on its own. Only
/// literals count: a name worked out at run time is the script's own business.
pub fn network_uses(source: &str) -> Vec<NetworkUse> {
    let tokens = tokens(source);
    let mut out = Vec::new();
    for i in 2..tokens.len() {
        let call = &tokens[i];
        if call.kind != TokenKind::Name || !is_punct(&tokens[i - 1], ".") {
            continue;
        }
        if tokens[i - 2].kind != TokenKind::Name || tokens[i - 2].text != "network" {
            continue;
        }
        let kind = match call.text.as_str() {
            "send_player" => NetworkUseKind::Server,
            "channel" => NetworkUseKind::Channel,
            "map" => NetworkUseKind::Map,
            _ => continue,
        };
        let index = if kind == NetworkUseKind::Server { 1 } else { 0 };
        if let Some(name) = argument(&tokens, i + 1, index) {
            out.push(NetworkUse {
                kind,
                name: name.text.clone(),
                line: name.line,
            });
        }
    }
    out
}

/// The `index`th argument of the call whose arguments start at `at` (its `(`, or a string literal called with
/// no parentheses), when it's a string literal and nothing more.
fn argument(tokens: &[Token], at: usize, index: usize) -> Option<&Token> {
    let open = tokens.get(at)?;
    if open.kind == TokenKind::String {
        return (index == 0).then_some(open);
    }
    if !is_punct(open, "(") {
        return None;
    }
    let mut depth = 0;
    let mut argument = 0;
    let mut start = at + 1;
    for (k, token) in tokens.iter().enumerate().skip(at + 1) {
        let punct = token.kind == TokenKind::Punct;
        if punct && ["(", "{", "["].contains(&token.text.as_str()) {
            depth += 1;
        } else if punct && [")", "}", "]"].contains(&token.text.as_str()) && depth > 0 {
            depth -= 1;
        } else if depth == 0 && (is_punct(token, ",") || is_punct(token, ")")) {
            if argument == index {
                return match &tokens[start..k] {
                    [only] if only.kind == TokenKind::String => Some(only),
                    _ => None,
                };
            }
            if is_punct(token, ")") {
                return None;
            }
            argument += 1;
            start = k + 1;
        }
    }
    None
}

/// What's wrong with the network names in `scripts` (Lua source by project path) given the workspace's dev
/// network's `servers`: a player sent to a server it doesn't have, at its line.
pub fn check_network_uses<'a>(
    scripts: impl IntoIterator<Item = (&'a str, &'a str)>,
    servers: &[String],
) -> Vec<Problem> {
    let mut sorted: Vec<&String> = servers.iter().collect();
    sorted.sort();
    let has = sorted
        .iter()
        .map(|s| s.as_str())
        .collect::<Vec<_>>()
        .join(", ");
    let mut problems = Vec::new();
    for (file, source) in scripts {
        for use_ in network_uses(source) {
            if use_.kind != NetworkUseKind::Server || servers.contains(&use_.name) {
                continue;
            }
            let message = if servers.is_empty() {
                format!(
                    "There's no server \"{}\" in the workspace's network.",
                    use_.name
                )
            } else {
                format!(
                    "There's no server \"{}\" in the workspace's network: it has {has}.",
                    use_.name
                )
            };
            let mut problem = codes::WORKSPACE_UNKNOWN_SERVER.at(file, message, None);
            problem.line = Some(use_.line as u32);
            problems.push(problem);
        }
    }
    problems
}
