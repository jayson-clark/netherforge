//! Format's `RequirementTest` and `PolicyTest`: what `requires` grants, what
//! it's written as, the sum across a tree, and a server's policy over it.

use std::collections::{BTreeMap, BTreeSet};

use netherforge_format::manifest::Requires;
use netherforge_format::package::{OpenedPackage, PackageLookup, PackageRequest, PackageSources};
use netherforge_format::policy::{Policy, PolicyLimits};
use netherforge_format::problem::Severity;
use netherforge_format::project::{self, Project, ProjectSource};
use netherforge_format::requirement::{self, Requirement, RequirementUse, allows, declared};
use netherforge_format::{canonical, manifest};

/// Files in memory.
struct Map {
    files: BTreeSet<String>,
    text: BTreeMap<String, String>,
}

impl Map {
    fn of(files: &[(&str, &str)]) -> Map {
        let text: BTreeMap<String, String> = files
            .iter()
            .map(|(p, t)| (p.to_string(), t.to_string()))
            .collect();
        Map {
            files: text.keys().cloned().collect(),
            text,
        }
    }
}

impl ProjectSource for Map {
    fn files(&self) -> &BTreeSet<String> {
        &self.files
    }

    fn read(&self, path: &str) -> Option<String> {
        self.text.get(path).cloned()
    }
}

fn manifest(more: &str) -> String {
    format!(
        r#"{{ "formatVersion": 1, "name": "Test", "namespace": "test", "version": "1.0.0", "minecraft": "26.3"{more} }}"#
    )
}

fn load<'a>(source: &Map, packages: &'a dyn PackageSources) -> Project<'a> {
    project::load(
        source,
        project::Options {
            packages,
            ..Default::default()
        },
    )
}

fn requires(json: &str) -> Requires {
    serde_json::from_str(json).unwrap()
}

#[test]
fn ids_read_back_as_the_requirement_they_name() {
    for id in [
        "console",
        "moderation",
        "db",
        "web",
        "bots",
        "wasm",
        "network",
        "http",
        "http:discord.com",
        "plugin:vault",
    ] {
        assert_eq!(Requirement::parse(id).map(|r| r.id()).as_deref(), Some(id));
    }
    assert_eq!(
        Requirement::parse("plugin:vault"),
        Some(Requirement::Plugin("vault".into()))
    );
    for id in [
        "",
        "plugin:",
        "plugin:Vault",
        "http:",
        "http:Discord.com",
        "networks",
        "plugin",
    ] {
        assert_eq!(Requirement::parse(id), None, "{id}");
    }
}

#[test]
fn a_declaration_grants_only_what_it_says() {
    let r = requires(
        r#"{ "moderation": true, "http": ["discord.com", "*.example.com"], "plugins": ["vault"] }"#,
    );
    let granted = |req: Requirement| req.granted_by(Some(&r));
    assert!(granted(Requirement::Moderation));
    assert!(!granted(Requirement::Db));
    assert!(!Requirement::Moderation.granted_by(None));
    assert!(!granted(Requirement::Bots));
    assert!(Requirement::Bots.granted_by(Some(&requires(r#"{ "bots": true }"#))));
    assert!(!granted(Requirement::Network));
    assert!(Requirement::Network.granted_by(Some(&requires(r#"{ "network": true }"#))));
    assert_eq!(
        Requirement::Network.declaration(),
        r#""requires": { "network": true }"#
    );
    // The spec's kind-level check: any host at all.
    assert!(granted(Requirement::Http(None)));
    assert!(!Requirement::Http(None).granted_by(Some(&requires(r#"{ "http": [] }"#))));
    let host = |h: &str| granted(Requirement::Http(Some(h.into())));
    assert!(host("discord.com"));
    assert!(host("api.example.com"));
    assert!(host("a.b.example.com"));
    assert!(!host("example.com"), "a wildcard is only what's under it");
    assert!(!host("cdn.discord.com"));
    assert!(!host("discord.com.evil.net"));
    assert!(!host("notexample.com"));
    assert!(
        allows("discord.com", "Discord.COM."),
        "DNS names compare without case or a final dot"
    );
    assert!(granted(Requirement::Plugin("vault".into())));
    assert!(!granted(Requirement::Plugin("luckperms".into())));
    assert_eq!(
        Requirement::Http(None).declaration(),
        r#""requires": { "http": ["<host>"] }"#
    );
}

#[test]
fn declarations_are_sets_in_the_manifest() {
    let text = manifest(
        r#", "requires": { "plugins": ["vault", "essentials", "vault"], "http": ["b.com", "a.com"], "db": true }"#,
    );
    let parsed: manifest::Manifest = serde_json::from_str(&text).unwrap();
    let written = canonical::write(&parsed.canonical());
    assert!(
        written.contains(r#""http": ["a.com", "b.com"]"#),
        "{written}"
    );
    assert!(
        written.contains(r#""plugins": ["essentials", "vault"]"#),
        "{written}"
    );
    let ids: Vec<String> = declared(parsed.requires.as_ref())
        .iter()
        .map(Requirement::id)
        .collect();
    assert_eq!(
        ids,
        [
            "db",
            "http:a.com",
            "http:b.com",
            "plugin:essentials",
            "plugin:vault"
        ]
    );
}

/// The packages of the tree test: each a manifest by namespace.
struct Manifests(BTreeMap<&'static str, &'static str>);

impl PackageSources for Manifests {
    fn open(&self, namespace: &str, _: &PackageRequest) -> PackageLookup<'_> {
        match self.0.get(namespace) {
            Some(text) => PackageLookup::Opened(OpenedPackage {
                source: Box::new(Map::of(&[("netherforge.json", text)])),
                hash: None,
                commit: None,
                from: None,
            }),
            None => PackageLookup::Missing(None),
        }
    }
}

/// The sum the editor and the server show: every package's, its own dependencies' too, each once with who
/// declares it.
#[test]
fn the_tree_is_summed_with_who_declares_what() {
    let packages = Manifests(BTreeMap::from([
        (
            "economy",
            r#"{ "formatVersion": 1, "name": "Economy", "namespace": "economy", "version": "1.0.0", "minecraft": "26.3",
                "requires": { "db": true, "plugins": ["vault"] } }"#,
        ),
        (
            "chat",
            r#"{ "formatVersion": 1, "name": "Chat", "namespace": "chat", "version": "1.0.0", "minecraft": "26.3",
                "requires": { "http": ["discord.com"], "db": true }, "dependencies": { "economy": { "path": "../economy" } } }"#,
        ),
    ]));
    let app = manifest(
        r#", "requires": { "moderation": true, "http": ["discord.com"] }, "dependencies": { "chat": { "path": "../chat" } }"#,
    );
    let source = Map::of(&[("netherforge.json", &app)]);
    let project = load(&source, &packages);
    let uses = |r: &str, p: &[&str]| RequirementUse {
        requirement: r.into(),
        packages: p.iter().map(|s| s.to_string()).collect(),
    };
    assert_eq!(
        requirement::combined(&project),
        [
            uses("moderation", &["test"]),
            uses("db", &["chat", "economy"]),
            uses("http:discord.com", &["test", "chat"]),
            uses("plugin:vault", &["economy"]),
        ]
    );
}

// ---- Policies ----

fn policy(text: &str) -> Policy {
    Policy::parse(text).unwrap()
}

#[test]
fn a_policy_refuses_kinds_it_denies_or_does_not_allow_and_hosts_it_does_not_list() {
    let strict = policy(
        r#"{ "requires": { "allow": ["network", "db", "http"], "deny": ["moderation"], "http": ["api.example.com", "*.cdn.example.com"] } }"#,
    );
    assert_eq!(strict.refuses("network"), None);
    assert_eq!(
        strict.refuses("moderation").as_deref(),
        Some(r#"requires.deny lists "moderation""#)
    );
    assert_eq!(
        strict.refuses("plugin:vault").as_deref(),
        Some(r#"requires.allow doesn't list "plugins""#)
    );
    assert_eq!(strict.refuses("http:api.example.com"), None);
    assert_eq!(strict.refuses("http:img.cdn.example.com"), None);
    assert_eq!(
        strict.refuses("http:evil.com").as_deref(),
        Some(r#"requires.http doesn't list "evil.com""#)
    );
    assert!(strict.stops());
}

#[test]
fn a_policy_that_is_not_one_says_why() {
    assert!(
        Policy::parse(r#"{ "requires": { "deny": ["teleport"] } }"#)
            .unwrap_err()
            .contains("teleport")
    );
    assert!(Policy::parse(r#"{ "requires": { "refused": "ignore" } }"#).is_err());
    assert!(Policy::parse("not json").is_err());
    assert!(Policy::parse(r#"{ "limits": { "script-memory": 1 } }"#).is_err());
}

#[test]
fn the_projects_refused_requirements_are_problems_on_the_manifests_that_declare_them() {
    let app = manifest(
        r#", "requires": { "moderation": true, "http": ["discord.com"], "network": true }"#,
    );
    let source = Map::of(&[("netherforge.json", &app)]);
    let project = load(&source, &netherforge_format::package::NoPackages);
    let refusing =
        policy(r#"{ "requires": { "deny": ["moderation"], "http": ["api.example.com"] } }"#);
    let problems = refusing.check(&project);
    assert_eq!(problems.len(), 2);
    assert!(
        problems
            .iter()
            .all(|p| p.code.as_deref() == Some("runtime.policy")
                && p.severity == Severity::Error
                && p.file == "netherforge.json")
    );
    assert!(problems[0].message.contains("moderation"));
    let degrading = policy(r#"{ "requires": { "deny": ["moderation"], "refused": "degrade" } }"#);
    let problems = degrading.check(&project);
    assert_eq!(problems.len(), 1);
    assert_eq!(problems[0].severity, Severity::Warning);
}

#[test]
fn two_policies_together_are_the_stricter_of_each() {
    let server = policy(
        r#"{ "requires": { "allow": ["network", "db"], "refused": "degrade" }, "limits": { "script-memory-mb": 256 } }"#,
    );
    let network = policy(
        r#"{ "requires": { "allow": ["network", "http"], "deny": ["db"] }, "limits": { "script-memory-mb": 128, "instruction-budget": 1000 } }"#,
    );
    let both = server.and(&network);
    assert_eq!(both.requires.allow, Some(vec!["network".to_string()]));
    assert_eq!(both.requires.deny, Some(vec!["db".to_string()]));
    assert!(both.stops());
    assert_eq!(
        both.limits,
        PolicyLimits {
            script_memory_mb: Some(128),
            instruction_budget: Some(1000),
            ..Default::default()
        }
    );
}

#[test]
fn hosts_and_plugin_names_have_rules() {
    use netherforge_format::names::{is_http_host, is_plugin_name};
    for host in [
        "discord.com",
        "api.example.com",
        "localhost",
        "*.example.com",
        "x-1.io",
        "203.0.113.5",
    ] {
        assert!(is_http_host(host), "{host}");
    }
    for host in [
        "",
        "*.com",
        "*",
        "Discord.com",
        "https://x.dev",
        "x.dev/path",
        "-x.com",
        "x..com",
        "x.com:443",
        "a b.com",
    ] {
        assert!(!is_http_host(host), "{host}");
    }
    assert!(is_plugin_name("vault"));
    assert!(is_plugin_name("placeholder_api-2"));
    assert!(!is_plugin_name("PlaceholderAPI"));
    assert!(!is_plugin_name("a b"));
}

#[test]
fn a_url_where_a_host_belongs_says_which_host_to_write() {
    let text =
        manifest(r#", "requires": { "http": ["wss://gateway.discord.gg/?v=10", "ftp://"] }"#);
    let source = Map::of(&[("netherforge.json", &text)]);
    let project = load(&source, &netherforge_format::package::NoPackages);
    let mut messages: Vec<String> = project
        .problems
        .iter()
        .filter(|p| p.code.as_deref() == Some("project.requires-host"))
        .map(|p| p.message.clone())
        .collect();
    messages.sort();
    assert_eq!(
        messages,
        [
            format!(
                "\"ftp://\" isn't a host ({})",
                netherforge_format::names::HTTP_HOST_RULE
            ),
            "\"wss://gateway.discord.gg/?v=10\" is a URL: list its host alone, \"gateway.discord.gg\", which covers http, https, ws and wss".to_string(),
        ]
    );
}
