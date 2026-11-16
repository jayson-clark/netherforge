//! What a package's scripts can do only when its `netherforge.json` declares
//! it (`requires`): format's `Requirement`. Its id is how the API spec's
//! `requires` names it (`console`, `moderation`, `db`, `web`, `bots`, `wasm`,
//! `network`, `http`, `plugin:vault`) and how the editor, the server's log and
//! `/nf requires` show it (`http:discord.com`).
//!
//! The one place a declaration is read: the runtime's check, the combined list
//! the editor and the server show (`combined`) and the messages saying what to
//! declare (`declaration`) all come from here.

use std::fmt;

use serde::Serialize;

use crate::manifest::Requires;
use crate::names;
use crate::project::Project;

/// One capability a package may declare.
#[derive(Clone, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum Requirement {
    /// Running server commands as the console (`nf.server.run`): with the
    /// console's permissions, so anything the server or another plugin lets the
    /// console do. Every other capability is narrower than this one.
    Console,
    /// Kicking and banning players, the whitelist, the message of the day and the most players allowed.
    Moderation,
    /// A database of the package's own (`nf.db`).
    Db,
    /// Answering web requests to the server under the package's own path (`nf.web`).
    Web,
    /// Joining fake players to the server and driving them (`nf.bots`), when the server owner allows bots.
    Bots,
    /// Running the package's WebAssembly modules (`nf.wasm`), within the server owner's limits.
    Wasm,
    /// Talking to the other servers of the network (`nf.network`).
    Network,
    /// HTTP requests to a host (lowercase), or, with none, to any host the
    /// package declares: what the spec's `requires: "http"` checks before the
    /// function knows its URL. A request then checks its own host.
    Http(Option<String>),
    /// Another plugin on the server, by its name in lowercase (`vault`). It goes with plugin interop (the roadmap
    /// drops it), on both runtimes at once.
    Plugin(String),
}

/// The flags, in the order they're shown, with their ids.
const FLAGS: [(Requirement, &str); 7] = [
    (Requirement::Console, "console"),
    (Requirement::Moderation, "moderation"),
    (Requirement::Db, "db"),
    (Requirement::Web, "web"),
    (Requirement::Bots, "bots"),
    (Requirement::Wasm, "wasm"),
    (Requirement::Network, "network"),
];

impl Requirement {
    /// `moderation`, `db`, `http`, `http:discord.com`, `plugin:vault`.
    pub fn id(&self) -> String {
        match self {
            Requirement::Http(None) => "http".into(),
            Requirement::Http(Some(host)) => format!("http:{host}"),
            Requirement::Plugin(name) => format!("plugin:{name}"),
            flag => FLAGS
                .iter()
                .find(|(r, _)| r == flag)
                .map(|(_, id)| id.to_string())
                .expect("every flag has an id"),
        }
    }

    /// How a package declares it, as messages say it: `"requires": { "http": ["discord.com"] }`.
    pub fn declaration(&self) -> String {
        match self {
            Requirement::Http(host) => format!(
                "\"requires\": {{ \"http\": [\"{}\"] }}",
                host.as_deref().unwrap_or("<host>")
            ),
            Requirement::Plugin(name) => format!("\"requires\": {{ \"plugins\": [\"{name}\"] }}"),
            flag => format!("\"requires\": {{ \"{}\": true }}", flag.id()),
        }
    }

    /// Whether `requires` (a package's) declares it.
    pub fn granted_by(&self, requires: Option<&Requires>) -> bool {
        let Some(r) = requires else { return false };
        let on = |flag: Option<bool>| flag == Some(true);
        match self {
            Requirement::Console => on(r.console),
            Requirement::Moderation => on(r.moderation),
            Requirement::Db => on(r.db),
            Requirement::Web => on(r.web),
            Requirement::Bots => on(r.bots),
            Requirement::Wasm => on(r.wasm),
            Requirement::Network => on(r.network),
            Requirement::Http(host) => {
                let hosts = r.http.as_deref().unwrap_or_default();
                match host {
                    None => !hosts.is_empty(),
                    Some(host) => hosts.iter().any(|p| allows(p, host)),
                }
            }
            Requirement::Plugin(name) => r.plugins.iter().flatten().any(|p| p == name),
        }
    }

    /// `id` as a requirement (`plugin:vault`), or none for text that isn't one.
    pub fn parse(id: &str) -> Option<Requirement> {
        if let Some((flag, _)) = FLAGS.iter().find(|(_, i)| *i == id) {
            return Some(flag.clone());
        }
        if id == "http" {
            return Some(Requirement::Http(None));
        }
        if let Some(host) = id.strip_prefix("http:") {
            return names::is_host_name(host).then(|| Requirement::Http(Some(host.into())));
        }
        if let Some(name) = id.strip_prefix("plugin:") {
            return names::is_plugin_name(name).then(|| Requirement::Plugin(name.into()));
        }
        None
    }

    /// Where it's shown among the rest.
    fn rank(&self) -> usize {
        match self {
            Requirement::Http(_) => FLAGS.len(),
            Requirement::Plugin(_) => FLAGS.len() + 1,
            flag => FLAGS.iter().position(|(r, _)| r == flag).expect("a flag"),
        }
    }
}

impl fmt::Display for Requirement {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.id())
    }
}

/// Whether `pattern` (one of `requires.http`) allows a request to `host`: the
/// same name, or for `*.example.com` any name under `example.com` (not
/// `example.com` itself). Host names are compared in lowercase, as DNS does.
pub fn allows(pattern: &str, host: &str) -> bool {
    let lower = host.to_lowercase();
    let name = lower.strip_suffix('.').unwrap_or(&lower);
    match pattern.strip_prefix('*') {
        Some(suffix) if pattern.starts_with("*.") => name.ends_with(suffix),
        _ => name == pattern,
    }
}

/// What `requires` declares, one requirement each, in the order they're shown:
/// console, moderation, db, web, bots, wasm, network, each host, each plugin.
/// A host or plugin name that isn't one (a problem in the manifest) declares
/// nothing.
pub fn declared(requires: Option<&Requires>) -> Vec<Requirement> {
    let Some(r) = requires else {
        return Vec::new();
    };
    let mut out: Vec<Requirement> = FLAGS
        .iter()
        .map(|(flag, _)| flag.clone())
        .filter(|flag| flag.granted_by(Some(r)))
        .collect();
    let sorted = |list: &Option<Vec<String>>, keep: fn(&str) -> bool| {
        let mut names: Vec<String> = list.iter().flatten().filter(|n| keep(n)).cloned().collect();
        names.sort();
        names.dedup();
        names
    };
    out.extend(
        sorted(&r.http, names::is_http_host)
            .into_iter()
            .map(|h| Requirement::Http(Some(h))),
    );
    out.extend(
        sorted(&r.plugins, names::is_plugin_name)
            .into_iter()
            .map(Requirement::Plugin),
    );
    out
}

/// One requirement across a project's tree (`combined`): its id and the namespaces that declare it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct RequirementUse {
    pub requirement: String,
    pub packages: Vec<String>,
}

/// Every requirement declared across `project`'s whole tree (the project and
/// every package it depends on, theirs included), each with the packages that
/// declare it: what whoever runs the server agrees to by running the project.
/// In `declared`'s order, then by id; packages by namespace, the project's own
/// first.
pub fn combined(project: &Project) -> Vec<RequirementUse> {
    let namespaces =
        std::iter::once(project.namespace()).chain(project.packages.keys().map(String::as_str));
    let mut by: Vec<(Requirement, Vec<String>)> = Vec::new();
    for namespace in namespaces {
        let requires = project
            .manifest_of(namespace)
            .and_then(|m| m.requires.as_ref());
        for requirement in declared(requires) {
            match by.iter_mut().find(|(r, _)| *r == requirement) {
                Some((_, packages)) => packages.push(namespace.to_string()),
                None => by.push((requirement, vec![namespace.to_string()])),
            }
        }
    }
    by.sort_by_cached_key(|(r, _)| (r.rank(), r.id()));
    by.into_iter()
        .map(|(r, packages)| RequirementUse {
            requirement: r.id(),
            packages,
        })
        .collect()
}
