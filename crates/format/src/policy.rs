//! A server owner's (or a network's) rules over what projects may ask for,
//! whatever they ask for (format's `Policy`): `policy.json` in the plugin's
//! folder, sent by the network's hub for the server's role, or given to
//! `netherforge check --policy`. A project whose `requires` it refuses doesn't
//! load, or with `refused: degrade` loads with the refused calls failing as an
//! undeclared requirement's do. Its limits replace the owner's own where
//! they're stricter.

use serde::{Deserialize, Serialize};

use crate::problem::{Problem, Severity, codes};
use crate::project::Project;
use crate::requirement::{allows, combined};

pub const FILE_NAME: &str = "policy.json";

/// The kinds `allow` and `deny` name: a requirement's id before its `:`, `plugins` for every plugin.
pub const KINDS: [&str; 9] = [
    "console",
    "moderation",
    "db",
    "web",
    "bots",
    "wasm",
    "network",
    "http",
    "plugins",
];

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Policy {
    #[serde(default)]
    pub requires: PolicyRequires,
    #[serde(default)]
    pub limits: PolicyLimits,
}

/// What a refused requirement does.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Refused {
    /// The project doesn't load.
    #[default]
    Refuse,
    /// It loads, and the refused calls fail.
    Degrade,
}

/// What a policy allows projects to require.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PolicyRequires {
    /// The only requirement kinds a project may have; any when left out.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub allow: Option<Vec<String>>,
    /// Kinds no project may have.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub deny: Option<Vec<String>>,
    /// The only hosts (`*.example.com` for any under it) a project may declare for `http`; any when left out.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub http: Option<Vec<String>>,
    #[serde(default)]
    pub refused: Refused,
}

/// Limits a policy sets, replacing the server's own where lower; none when left out.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case", deny_unknown_fields)]
pub struct PolicyLimits {
    /// How much memory every script together may use.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub script_memory_mb: Option<i32>,
    /// The most a script may run a call (its file's `budget` can't be more).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub instruction_budget: Option<i32>,
    /// How long one call into a script may run.
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "call-milliseconds"
    )]
    pub call_millis: Option<i32>,
    /// What each package may send the network a minute.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub network_messages_per_minute: Option<i32>,
    /// What each package may make HTTP requests a minute.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub http_requests_per_minute: Option<i32>,
}

/// A requirement's kind, as `allow` and `deny` name it.
pub fn kind_of(requirement: &str) -> &str {
    if requirement.starts_with("plugin:") {
        "plugins"
    } else {
        requirement.split(':').next().unwrap_or(requirement)
    }
}

impl Policy {
    /// `text` as a policy, or what's wrong with it.
    pub fn parse(text: &str) -> Result<Policy, String> {
        let policy: Policy = serde_json::from_str(text).map_err(|e| e.to_string())?;
        let r = &policy.requires;
        for kind in r.allow.iter().chain(&r.deny).flatten() {
            if !KINDS.contains(&kind.as_str()) {
                return Err(format!(
                    "\"{kind}\" isn't a requirement's kind ({})",
                    KINDS.join(", ")
                ));
            }
        }
        Ok(policy)
    }

    /// Why `requirement` (a requirement's id: `moderation`, `http:api.example.com`, `plugin:vault`) is refused.
    pub fn refuses(&self, requirement: &str) -> Option<String> {
        let kind = kind_of(requirement);
        let r = &self.requires;
        if r.deny.iter().flatten().any(|k| k == kind) {
            return Some(format!("requires.deny lists \"{kind}\""));
        }
        if let Some(allow) = &r.allow
            && !allow.iter().any(|k| k == kind)
        {
            return Some(format!("requires.allow doesn't list \"{kind}\""));
        }
        if let (Some(hosts), Some(host)) = (&r.http, requirement.strip_prefix("http:"))
            && !hosts.iter().any(|p| allows(p, host) || p == host)
        {
            return Some(format!("requires.http doesn't list \"{host}\""));
        }
        None
    }

    /// Whether a refusal stops the project loading (true) or leaves the refused calls failing.
    pub fn stops(&self) -> bool {
        self.requires.refused == Refused::Refuse
    }

    /// What `project`'s tree declares that this refuses: a `runtime.policy` on
    /// each declaring package's manifest, an error when it stops the project
    /// loading, a warning when it degrades.
    pub fn check(&self, project: &Project) -> Vec<Problem> {
        let mut out = Vec::new();
        for use_ in combined(project) {
            let Some(why) = self.refuses(&use_.requirement) else {
                continue;
            };
            for namespace in &use_.packages {
                let file = if namespace == project.namespace() {
                    crate::manifest::FILE_NAME.to_string()
                } else {
                    crate::kinds::package_path(namespace, crate::manifest::FILE_NAME)
                };
                let message = format!(
                    "Package \"{namespace}\" requires {}, which the server's policy refuses ({why}){}",
                    use_.requirement,
                    if self.stops() {
                        ", so the project doesn't load"
                    } else {
                        ": calls that need it fail"
                    }
                );
                let mut problem =
                    codes::RUNTIME_POLICY.at(&file, message, Some("$.requires".into()));
                if !self.stops() {
                    problem.severity = Severity::Warning;
                }
                out.push(problem);
            }
        }
        out
    }

    /// This and `other` together, the stricter of each: what both allow, what either denies, the lower limit.
    pub fn and(&self, other: &Policy) -> Policy {
        fn both<T: Clone>(
            a: &Option<Vec<T>>,
            b: &Option<Vec<T>>,
            f: impl Fn(&[T], &[T]) -> Vec<T>,
        ) -> Option<Vec<T>> {
            match (a, b) {
                (Some(a), Some(b)) => Some(f(a, b)),
                (a, b) => a.clone().or_else(|| b.clone()),
            }
        }
        let common = |a: &[String], b: &[String]| {
            let mut out: Vec<String> = Vec::new();
            for k in a.iter().filter(|k| b.contains(k)) {
                if !out.contains(k) {
                    out.push(k.clone());
                }
            }
            out
        };
        let (r, o) = (&self.requires, &other.requires);
        let lower = |a: Option<i32>, b: Option<i32>| a.into_iter().chain(b).min();
        let (l, m) = (&self.limits, &other.limits);
        Policy {
            requires: PolicyRequires {
                allow: both(&r.allow, &o.allow, common),
                deny: both(&r.deny, &o.deny, |a, b| {
                    let mut out: Vec<String> = Vec::new();
                    for k in a.iter().chain(b) {
                        if !out.contains(k) {
                            out.push(k.clone());
                        }
                    }
                    out
                }),
                http: both(&r.http, &o.http, |a, b| {
                    a.iter().filter(|h| b.contains(h)).cloned().collect()
                }),
                refused: if self.stops() || other.stops() {
                    Refused::Refuse
                } else {
                    Refused::Degrade
                },
            },
            limits: PolicyLimits {
                script_memory_mb: lower(l.script_memory_mb, m.script_memory_mb),
                instruction_budget: lower(l.instruction_budget, m.instruction_budget),
                call_millis: lower(l.call_millis, m.call_millis),
                network_messages_per_minute: lower(
                    l.network_messages_per_minute,
                    m.network_messages_per_minute,
                ),
                http_requests_per_minute: lower(
                    l.http_requests_per_minute,
                    m.http_requests_per_minute,
                ),
            },
        }
    }
}
