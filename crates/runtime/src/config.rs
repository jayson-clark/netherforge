//! Where a runtime finds its project and keeps its state (Kotlin's `RuntimeConfig.read`): the one place the plugin's
//! `config.yml` is read. The adapter hands over the file's values without understanding them (a section is an object,
//! a list an array, a scalar as YAML gives it: `settings`), so every adapter reads it alike; `read_yaml` turns the
//! file's text into those values.
//!
//! Two shapes in practice. The editor's dev server is started with `-Dnetherforge.project=<dir>` (and
//! `-Dnetherforge.bridge.port`), read from `properties` and `env`; it ignores `project:` and `resource-pack:`, and its
//! bots are always on. A production server names its project in `project:`, relative to the server's folder, and
//! resolves no packages. Both keep their state in the plugin's folder.
//!
//! A number that isn't a positive one is the default, as Kotlin's are; a value that can't be acted on at all (an
//! `http.allow-private` entry that isn't one, an S3 store without its bucket) is an error, since an allowance the owner
//! meant but that silently didn't apply would look like a bug in the package.
//!
//! A `project:` that's a bundle's URL is fetched into `bundles/` in the plugin's folder the first time
//! ([`crate::bundle_fetch`]), and run from there.

use std::path::{Path, PathBuf};

use netherforge_format::bridge as properties;
use netherforge_format::names;
use netherforge_format::policy::Policy;
use serde_json::{Map, Value};

use crate::databases::{ConnectionConfig, DatabasesConfig, SslMode};
use crate::network::NetworkConfig;
use crate::session::Config;

/// The port the plugin serves the resource pack on when `resource-pack.port` isn't set.
pub const DEFAULT_PACK_PORT: u16 = 8163;

/// What the adapter logs when there's no project to run.
pub const NO_PROJECT: &str = "No project to run. Set `project:` in plugins/NetherForge/config.yml to a NetherForge project folder and restart.";

/// Where the server runs: its folder (what `project:` is relative to) and the address it's bound to (`server-ip`,
/// empty for every interface).
#[derive(Clone, Debug)]
pub struct ServerAddress {
    pub directory: PathBuf,
    pub ip: String,
}

/// The editor's bridge on a dev server: its port and the token it proves.
#[derive(Clone, Debug, PartialEq)]
pub struct BridgeConfig {
    pub port: u16,
    pub token: String,
}

/// `performance:`: when a script is slow enough to warn about, and the time scripts may take a tick.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PerformanceConfig {
    pub warn_millis: f64,
    pub warn_ticks: i64,
    pub max_tick_millis: f64,
}

impl Default for PerformanceConfig {
    fn default() -> PerformanceConfig {
        PerformanceConfig {
            warn_millis: 10.0,
            warn_ticks: 20,
            max_tick_millis: 25.0,
        }
    }
}

/// `bundles:`: whether a bundle must be signed by a key the server trusts.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct BundlesConfig {
    pub require_signature: bool,
    pub trusted_keys: Vec<String>,
}

/// `metrics:`: the Prometheus endpoint, off unless it has a port.
#[derive(Clone, Debug, PartialEq)]
pub struct MetricsConfig {
    pub port: Option<u16>,
    pub bind: String,
}

/// `control:`: the local control endpoint for the tool that runs the server, off unless set.
#[derive(Clone, Debug, PartialEq)]
pub struct ControlConfig {
    pub socket: Option<PathBuf>,
    pub port: Option<u16>,
    pub token_file: PathBuf,
    pub stall_seconds: i64,
}

/// How long the control endpoint waits before it calls a tick stalled, by default.
pub const DEFAULT_STALL_SECONDS: i64 = 10;

/// Everything `config.yml` (and a dev server's properties) say: what a session runs with, and what the plugin around
/// it does.
#[derive(Clone)]
pub struct RuntimeConfig {
    /// The project's folder (holding `netherforge.json`), or a bundle's.
    pub project: PathBuf,
    /// The plugin's folder, where the runtime keeps its state.
    pub state_dir: PathBuf,
    /// Set only on the editor's dev server.
    pub bridge: Option<BridgeConfig>,
    /// What a session runs with: its folders and every area's limits.
    pub session: Config,
    pub performance: PerformanceConfig,
    /// The owner's policy over what projects may require (`policy.json` beside `config.yml`), or none; or why it
    /// couldn't be read, in which case no project runs.
    pub policy: Option<Policy>,
    pub policy_problem: Option<String>,
    pub bundles: BundlesConfig,
    pub metrics: MetricsConfig,
    pub control: ControlConfig,
    /// Whether the project's dependencies are found where `netherforge.json` points: on the dev server.
    pub resolves_packages: bool,
    /// The editor's package cache, on a dev server.
    pub package_cache: Option<PathBuf>,
    /// `packages.provided`: folders of packages this server provides, on a production server.
    pub provided_packages: Vec<PathBuf>,
    /// How fast the wall clock runs: 1, but on the integration test's dev servers.
    pub wall_clock_rate: f64,
}

/// `-Dnetherforge.test.wall-clock-rate=60`, read on a dev server only.
pub const WALL_CLOCK_RATE_PROPERTY: &str = "netherforge.test.wall-clock-rate";

/// `config.yml`'s text as the plain values `read` takes.
pub fn read_yaml(text: &str) -> Result<Value, String> {
    if text.trim().is_empty() {
        return Ok(Value::Object(Map::new()));
    }
    serde_yaml_ng::from_str::<Value>(text).map_err(|e| format!("config.yml can't be read: {e}"))
}

fn section<'a>(settings: &'a Value, key: &str) -> &'a Value {
    static EMPTY: Value = Value::Null;
    settings
        .get(key)
        .filter(|v| v.is_object())
        .unwrap_or(&EMPTY)
}

fn text(section: &Value, key: &str) -> Option<String> {
    let value = section.get(key)?;
    let text = match value {
        Value::String(s) => s.clone(),
        Value::Null => return None,
        Value::Number(n) => n.to_string(),
        Value::Bool(b) => b.to_string(),
        _ => return None,
    };
    (!text.trim().is_empty()).then_some(text)
}

fn int(section: &Value, key: &str) -> Option<i64> {
    match section.get(key)? {
        Value::Number(n) => n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)),
        Value::String(s) => s.trim().parse().ok(),
        _ => None,
    }
}

fn double(section: &Value, key: &str) -> Option<f64> {
    match section.get(key)? {
        Value::Number(n) => n.as_f64(),
        Value::String(s) => s.trim().parse().ok(),
        _ => None,
    }
}

fn boolean(section: &Value, key: &str) -> Option<bool> {
    match section.get(key)? {
        Value::Bool(b) => Some(*b),
        Value::String(s) => match s.trim().to_lowercase().as_str() {
            "true" => Some(true),
            "false" => Some(false),
            _ => None,
        },
        _ => None,
    }
}

/// A positive number at `key`, or the default.
fn positive<T: TryFrom<i64>>(section: &Value, key: &str, default: T) -> T {
    int(section, key)
        .filter(|n| *n > 0)
        .and_then(|n| T::try_from(n).ok())
        .unwrap_or(default)
}

/// The configuration a server runs with, or none when there's no project to run; an error for a value that can't be
/// acted on.
pub fn read(
    plugin_folder: &Path,
    settings: &Value,
    server: &ServerAddress,
    property: &dyn Fn(&str) -> Option<String>,
    env: &dyn Fn(&str) -> Option<String>,
) -> Result<Option<RuntimeConfig>, String> {
    let performance = performance(section(settings, "performance"));
    let mut session = Config {
        time_zone: text(section(settings, "schedules"), "time-zone"),
        data_dir: Some(plugin_folder.join("data")),
        settings_dir: Some(plugin_folder.join("settings")),
        databases_dir: Some(plugin_folder.join("databases")),
        files: files(section(settings, "files")),
        http: http(section(settings, "http"))?,
        web: web(section(settings, "web")),
        wasm: wasm(section(settings, "wasm")),
        network: network(section(settings, "network")),
        bots: bots(section(settings, "bots")),
        structures: structures(section(settings, "structures")),
        screens: screens(section(settings, "screens")),
        databases: databases(section(settings, "databases")),
        worlds: worlds(section(settings, "worlds"), server),
        performance: Some(performance),
        ..Config::default()
    };
    session.pack.state_dir = Some(plugin_folder.to_owned());
    let policy_file = plugin_folder.join(netherforge_format::policy::FILE_NAME);
    let (policy, policy_problem) = match std::fs::read_to_string(&policy_file) {
        Ok(text) => match Policy::parse(&text) {
            Ok(policy) => (Some(policy), None),
            Err(why) => (
                None,
                Some(format!("{} can't be read: {why}", policy_file.display())),
            ),
        },
        Err(_) => (None, None),
    };
    session.policy = policy.clone();
    session.policy_problem = policy_problem.clone();
    let metrics = {
        let s = section(settings, "metrics");
        MetricsConfig {
            port: int(s, "port")
                .filter(|p| (1..=65535).contains(p))
                .map(|p| p as u16),
            bind: text(s, "bind").map_or_else(|| "127.0.0.1".into(), |b| b.trim().to_owned()),
        }
    };
    let control = {
        let s = section(settings, "control");
        ControlConfig {
            socket: text(s, "socket").map(|p| normalize(&server.directory.join(p.trim()))),
            port: int(s, "port")
                .filter(|p| (1..=65535).contains(p))
                .map(|p| p as u16),
            token_file: normalize(
                &plugin_folder.join(
                    text(s, "token-file")
                        .map_or_else(|| "control.token".into(), |t| t.trim().to_owned()),
                ),
            ),
            stall_seconds: int(s, "stall-seconds")
                .filter(|n| *n > 0)
                .unwrap_or(DEFAULT_STALL_SECONDS),
        }
    };
    let base = |project: PathBuf, session: Config| RuntimeConfig {
        project,
        state_dir: plugin_folder.to_owned(),
        bridge: None,
        session,
        performance,
        policy: policy.clone(),
        policy_problem: policy_problem.clone(),
        bundles: BundlesConfig::default(),
        metrics: metrics.clone(),
        control: control.clone(),
        resolves_packages: true,
        package_cache: None,
        provided_packages: Vec::new(),
        wall_clock_rate: 1.0,
    };
    if let Some(project) = property(properties::PROJECT_PROPERTY).filter(|p| !p.trim().is_empty()) {
        let port = property(properties::PORT_PROPERTY).and_then(|p| p.trim().parse().ok());
        // Bots are always on where the project is being made: trying them is the point.
        session.bots.enabled = true;
        session.network = dev_network(session.network, property, env);
        session.network.alone.get_or_insert_with(|| "dev".into());
        let mut config = base(normalize(&absolute(Path::new(project.trim()))), session);
        config.bridge = port.map(|port| BridgeConfig {
            port,
            token: env(properties::TOKEN_ENV).unwrap_or_default(),
        });
        config.package_cache = property(properties::PACKAGE_CACHE_PROPERTY)
            .filter(|p| !p.trim().is_empty())
            .map(|p| normalize(&absolute(Path::new(p.trim()))));
        config.wall_clock_rate = property(WALL_CLOCK_RATE_PROPERTY)
            .and_then(|r| r.trim().parse::<f64>().ok())
            .filter(|r| *r > 0.0 && r.is_finite())
            .unwrap_or(1.0);
        return Ok(Some(config));
    }
    let Some(project) = text(settings, "project") else {
        return Ok(None);
    };
    let project = project.trim();
    let folder = if crate::bundle_fetch::is_reference(project) {
        crate::bundle_fetch::folder(project, &plugin_folder.join("bundles"), &|line| {
            eprintln!("{line}")
        })
        .map_err(|why| format!("project: {why}"))?
    } else {
        normalize(&server.directory.join(project))
    };
    session.pack = pack(section(settings, "resource-pack"), server, env)?;
    session.pack.state_dir = Some(plugin_folder.to_owned());
    let mut config = base(folder, session);
    let bundles = section(settings, "bundles");
    config.bundles = BundlesConfig {
        require_signature: boolean(bundles, "require-signature").unwrap_or(false),
        trusted_keys: bundles
            .get("trusted-keys")
            .and_then(Value::as_array)
            .map(|keys| {
                keys.iter()
                    .filter_map(|k| scalar(k).map(|s| s.trim().to_owned()))
                    .collect()
            })
            .unwrap_or_default(),
    };
    config.provided_packages = section(settings, "packages")
        .get("provided")
        .and_then(Value::as_array)
        .map(|folders| {
            folders
                .iter()
                .filter_map(scalar)
                .map(|f| f.trim().to_owned())
                .filter(|f| !f.is_empty())
                .map(|f| normalize(&server.directory.join(f)))
                .collect()
        })
        .unwrap_or_default();
    config.resolves_packages = false;
    Ok(Some(config))
}

fn scalar(value: &Value) -> Option<String> {
    match value {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        _ => None,
    }
}

fn absolute(path: &Path) -> PathBuf {
    if path.is_absolute() {
        path.to_owned()
    } else {
        std::env::current_dir().unwrap_or_default().join(path)
    }
}

/// `path` with its `.` and `..` worked out, without asking the disk.
fn normalize(path: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for part in path.components() {
        match part {
            std::path::Component::ParentDir => {
                out.pop();
            }
            std::path::Component::CurDir => {}
            other => out.push(other),
        }
    }
    out
}

/// `resource-pack:`, for a production server: the plugin serves the pack itself on every interface by default;
/// players reach it at `public-url`, or at this server's address when that's empty.
fn pack(
    s: &Value,
    server: &ServerAddress,
    env: &dyn Fn(&str) -> Option<String>,
) -> Result<crate::packs::PackConfig, String> {
    let port = int(s, "port")
        .and_then(|p| u16::try_from(p).ok())
        .unwrap_or(DEFAULT_PACK_PORT);
    let host = Some(server.ip.as_str())
        .filter(|ip| !ip.trim().is_empty() && *ip != "0.0.0.0")
        .unwrap_or("localhost");
    let storage = section(s, "storage");
    let storage = if text(storage, "type").as_deref() == Some("s3") {
        let need = |key: &str, why: &str| {
            text(storage, key).ok_or_else(|| format!("resource-pack.storage.{key} is empty{why}"))
        };
        Some(crate::pack_storage::PackStorageConfig {
            endpoint: need("endpoint", ": the store's address")?,
            bucket: need("bucket", "")?,
            prefix: text(storage, "prefix").unwrap_or_default(),
            region: text(storage, "region").unwrap_or_else(|| "auto".into()),
            public_url: need("public-url", ": where players fetch packs")?,
            access_key: text(storage, "access-key")
                .or_else(|| env("AWS_ACCESS_KEY_ID"))
                .ok_or("resource-pack.storage needs access-key (or AWS_ACCESS_KEY_ID)")?,
            secret_key: text(storage, "secret-key")
                .or_else(|| env("AWS_SECRET_ACCESS_KEY"))
                .ok_or("resource-pack.storage needs secret-key (or AWS_SECRET_ACCESS_KEY)")?,
        })
    } else {
        None
    };
    Ok(crate::packs::PackConfig {
        enabled: boolean(s, "enabled").unwrap_or(true),
        bind: text(s, "bind").unwrap_or_else(|| "0.0.0.0".into()),
        port,
        public_url: Some(text(s, "public-url").unwrap_or_else(|| format!("http://{host}:{port}"))),
        external_url: text(s, "external-url"),
        required: boolean(s, "required").unwrap_or(false),
        prompt: text(s, "prompt"),
        state_dir: None,
        storage,
    })
}

/// `http:`: what `nf.http.request` may reach and do.
fn http(s: &Value) -> Result<crate::http::HttpConfig, String> {
    let defaults = crate::http::HttpConfig::default();
    let c = section(s, "connections");
    let d = crate::http::ConnectionLimits::default();
    Ok(crate::http::HttpConfig {
        allow_private_addresses: boolean(s, "allow-private-addresses")
            .unwrap_or(defaults.allow_private_addresses),
        allow_private: allow_private(s.get("allow-private"))?,
        max_request_bytes: positive(s, "max-request-bytes", defaults.max_request_bytes),
        max_response_bytes: positive(s, "max-response-bytes", defaults.max_response_bytes),
        timeout_seconds: positive(s, "timeout-seconds", defaults.timeout_seconds),
        requests_per_minute: positive(s, "requests-per-minute", defaults.requests_per_minute),
        connections: crate::http::ConnectionLimits {
            max_per_package: positive(c, "max-per-package", d.max_per_package),
            max_total: positive(c, "max-total", d.max_total),
            max_message_bytes: positive(c, "max-message-bytes", d.max_message_bytes),
            bytes_per_minute: positive(c, "bytes-per-minute", d.bytes_per_minute),
            idle_seconds: positive(c, "idle-seconds", d.idle_seconds),
            max_queued_messages: positive(c, "max-queued-messages", d.max_queued_messages),
            messages_per_tick: positive(c, "messages-per-tick", d.messages_per_tick),
        },
    })
}

/// `http.allow-private:`, a list of `{ package, host, port, requests-per-minute? }`.
fn allow_private(value: Option<&Value>) -> Result<Vec<crate::http::PrivateAllowance>, String> {
    let Some(value) = value.filter(|v| !v.is_null()) else {
        return Ok(Vec::new());
    };
    let entries = value
        .as_array()
        .ok_or("http.allow-private must be a list of { package, host, port }")?;
    entries
        .iter()
        .enumerate()
        .map(|(i, entry)| {
            let at = format!("http.allow-private[{i}]");
            if !entry.is_object() {
                return Err(format!("{at} must be {{ package, host, port }}"));
            }
            let namespace = text(entry, "package")
                .map(|n| n.trim().to_owned())
                .ok_or_else(|| format!("{at} has no package (a package's namespace)"))?;
            if !names::is_namespace(&namespace) {
                return Err(format!("{at}: \"{namespace}\" isn't a package's namespace"));
            }
            let host = text(entry, "host")
                .map(|h| h.trim().to_lowercase())
                .ok_or_else(|| format!("{at} has no host (an address, or a name)"))?;
            if host.chars().any(|c| c.is_whitespace() || c == '/') {
                return Err(format!("{at}: \"{host}\" isn't an address or a host name"));
            }
            let port = int(entry, "port")
                .filter(|p| (1..=65535).contains(p))
                .ok_or_else(|| format!("{at} needs a port from 1 to 65535"))?;
            let rate = if entry.get("requests-per-minute").is_some() {
                Some(
                    int(entry, "requests-per-minute")
                        .filter(|r| *r >= 0)
                        .ok_or_else(|| {
                            format!("{at}: requests-per-minute is a whole number, 0 for no limit")
                        })? as usize,
                )
            } else {
                None
            };
            Ok(crate::http::PrivateAllowance {
                namespace,
                host: host
                    .trim_start_matches('[')
                    .trim_end_matches(']')
                    .to_owned(),
                port: port as u16,
                requests_per_minute: rate,
            })
        })
        .collect()
}

/// `web:`: whether `nf.web` routes are served, and their limits.
fn web(s: &Value) -> crate::web::gate::WebConfig {
    let d = crate::web::gate::WebConfig::default();
    crate::web::gate::WebConfig {
        enabled: boolean(s, "enabled").unwrap_or(d.enabled),
        public_url: text(s, "public-url").map(|u| u.trim_end_matches('/').to_owned()),
        trust_proxy_headers: boolean(s, "trust-proxy-headers").unwrap_or(d.trust_proxy_headers),
        max_body_bytes: positive(s, "max-body-bytes", d.max_body_bytes),
        max_response_bytes: positive(s, "max-response-bytes", d.max_response_bytes),
        requests_per_minute_per_ip: positive(
            s,
            "requests-per-minute-per-ip",
            d.requests_per_minute_per_ip,
        ),
        max_pending: positive(s, "max-pending", d.max_pending),
        handler_timeout_seconds: positive(s, "handler-timeout-seconds", d.handler_timeout_seconds),
    }
}

/// `wasm:`: how WebAssembly modules run.
fn wasm(s: &Value) -> crate::wasm::WasmConfig {
    let d = crate::wasm::WasmConfig::default();
    crate::wasm::WasmConfig {
        threads: positive(s, "threads", d.threads),
        compile: boolean(s, "compile").unwrap_or(d.compile),
        max_module_mb: positive(s, "max-module-mb", d.max_module_mb),
        max_memory_mb: positive(s, "max-memory-mb", d.max_memory_mb),
        max_memory_total_mb: positive(s, "max-memory-total-mb", d.max_memory_total_mb),
        max_call_seconds: positive(s, "max-call-seconds", d.max_call_seconds),
        max_queued_calls_per_package: positive(
            s,
            "max-queued-calls-per-package",
            d.max_queued_calls_per_package,
        ),
        max_instances_per_package: positive(
            s,
            "max-instances-per-package",
            d.max_instances_per_package,
        ),
        max_processes_per_package: positive(
            s,
            "max-processes-per-package",
            d.max_processes_per_package,
        ),
        max_processes_total: positive(s, "max-processes-total", d.max_processes_total),
        max_output_bytes: positive(s, "max-output-bytes", d.max_output_bytes),
        // At least a character's four bytes, so a long line is cut between characters.
        max_line_bytes: positive(s, "max-line-bytes", d.max_line_bytes).max(4),
        max_queued_lines: positive(s, "max-queued-lines", d.max_queued_lines),
        lines_per_tick: positive(s, "lines-per-tick", d.lines_per_tick),
    }
}

/// `host:port`, a port alone, or `[v6]:port`: where the proxy reaches this server (`network.register.address`).
pub fn registration(text: Option<&str>) -> Option<crate::network::protocol::Registration> {
    use crate::network::protocol::Registration;
    let value = text?.trim();
    if value.is_empty() {
        return None;
    }
    if let Ok(port) = value.parse::<i32>() {
        return Some(Registration { host: None, port });
    }
    let colon = value.rfind(':');
    let bracketed = value.starts_with('[');
    let has_port = colon.is_some_and(|c| {
        c > 0
            && if bracketed {
                value.as_bytes()[c - 1] == b']'
            } else {
                value.matches(':').count() == 1
            }
    });
    let Some(colon) = colon.filter(|_| has_port) else {
        return Some(Registration {
            host: Some(value.to_owned()),
            port: -1,
        });
    };
    Some(Registration {
        host: Some(
            value[..colon]
                .trim_start_matches('[')
                .trim_end_matches(']')
                .to_owned(),
        ),
        port: value[colon + 1..].parse().unwrap_or(-1),
    })
}

/// `network:`: whether this server is part of a network and how it reaches the hub.
fn network(s: &Value) -> NetworkConfig {
    let d = NetworkConfig::default();
    let hub = text(s, "hub").and_then(|h| NetworkConfig::hub(&h));
    let credential = section(s, "credential");
    NetworkConfig {
        enabled: boolean(s, "enabled").unwrap_or(d.enabled),
        host: hub.as_ref().map_or(d.host.clone(), |h| h.0.clone()),
        port: hub.as_ref().map_or(d.port, |h| h.1),
        server_name: text(s, "server-name").map(|n| n.trim().to_owned()),
        secret: text(credential, "secret")
            .or_else(|| text(s, "secret"))
            .map(|n| n.trim().to_owned()),
        credential: text(credential, "id").map(|n| n.trim().to_owned()),
        group: text(s, "group").map(|n| n.trim().to_owned()),
        capacity: int(s, "capacity").filter(|c| *c > 0).map(|c| c as i32),
        register: registration(text(section(s, "register"), "address").as_deref()),
        idle_after_seconds: positive(s, "idle-after-seconds", 0),
        wait_for_hub_seconds: positive(s, "wait-for-hub-seconds", 0),
        require_hub: boolean(s, "require-hub").unwrap_or(false),
        require_project: boolean(s, "require-project").unwrap_or(false),
        messages_per_minute: positive(s, "messages-per-minute", d.messages_per_minute),
        request_timeout_seconds: positive(s, "request-timeout-seconds", d.request_timeout_seconds),
        messages_per_tick: positive(s, "messages-per-tick", d.messages_per_tick),
        max_queued: positive(s, "max-queued-messages", d.max_queued),
        alone: None,
    }
}

/// A dev server the editor put in its dev network: the hub, this server's name and the secret come from the editor
/// and replace `config.yml`'s; the owner's limits stay.
fn dev_network(
    network: NetworkConfig,
    property: &dyn Fn(&str) -> Option<String>,
    env: &dyn Fn(&str) -> Option<String>,
) -> NetworkConfig {
    let Some(hub) = property(properties::NETWORK_HUB_PROPERTY).and_then(|h| NetworkConfig::hub(&h))
    else {
        return network;
    };
    let given = |key: &str| {
        property(key)
            .map(|v| v.trim().to_owned())
            .filter(|v| !v.is_empty())
    };
    NetworkConfig {
        enabled: true,
        host: hub.0,
        port: hub.1,
        server_name: property(properties::NETWORK_SERVER_PROPERTY).map(|v| v.trim().to_owned()),
        secret: env(properties::NETWORK_SECRET_ENV).map(|v| v.trim().to_owned()),
        credential: given(properties::NETWORK_CREDENTIAL_PROPERTY),
        group: given(properties::NETWORK_GROUP_PROPERTY),
        capacity: given(properties::NETWORK_CAPACITY_PROPERTY)
            .and_then(|c| c.parse().ok())
            .filter(|c: &i32| *c > 0),
        ..network
    }
}

/// `bots:`: whether scripts may join bots, and how many at once.
fn bots(s: &Value) -> crate::bots::BotsConfig {
    let d = crate::bots::BotsConfig::default();
    crate::bots::BotsConfig {
        enabled: boolean(s, "enabled").unwrap_or(d.enabled),
        max: positive(s, "max", d.max),
    }
}

/// `structures:`: how fast gradual placements and fills go.
fn structures(s: &Value) -> crate::structures::StructuresConfig {
    let d = crate::structures::StructuresConfig::default();
    crate::structures::StructuresConfig {
        blocks_per_tick: positive(s, "blocks-per-tick", d.blocks_per_tick),
        max_jobs_per_package: positive(s, "max-jobs-per-package", d.max_jobs_per_package),
    }
}

/// `files:`: what each package may keep in `nf.files`.
fn files(s: &Value) -> crate::files::FilesConfig {
    let d = crate::files::FilesConfig::default();
    crate::files::FilesConfig {
        max_bytes_per_package: int(s, "max-megabytes-per-package")
            .filter(|n| *n > 0)
            .map_or(d.max_bytes_per_package, |n| n as u64 * 1024 * 1024),
        max_entries_per_package: positive(s, "max-entries-per-package", d.max_entries_per_package),
    }
}

/// The fewest bytes of screens' pictures a player is sent a tick: one map and its packet.
const MIN_SCREEN_BYTES: i64 = 128 * 128 + 32;
const MAX_SCREEN_BYTES: i64 = 4 * 1024 * 1024;

/// `screens:`: how many bytes of screens' pictures a player is sent a tick.
fn screens(s: &Value) -> crate::screen::ScreensConfig {
    let d = crate::screen::ScreensConfig::default();
    crate::screen::ScreensConfig {
        bytes_per_tick: int(s, "bytes-per-tick")
            .unwrap_or(i64::from(d.bytes_per_tick))
            .clamp(MIN_SCREEN_BYTES, MAX_SCREEN_BYTES) as i32,
        ..d
    }
}

/// `worlds:`: where `nf.worlds.import` may take worlds from, and how many may be loaded at once.
fn worlds(s: &Value, server: &ServerAddress) -> crate::managed_worlds::WorldsConfig {
    let root = text(s, "import-root").map_or_else(|| "imports".into(), |r| r.trim().to_owned());
    crate::managed_worlds::WorldsConfig {
        import_root: (!root.is_empty() && root != "off")
            .then(|| normalize(&absolute(&server.directory.join(root)))),
        max_loaded: int(s, "max-loaded").filter(|n| *n > 0).unwrap_or(0) as usize,
    }
}

/// `performance:`: when a script is slow enough to warn about.
fn performance(s: &Value) -> PerformanceConfig {
    let d = PerformanceConfig::default();
    PerformanceConfig {
        warn_millis: double(s, "warn-ms").unwrap_or(d.warn_millis).max(0.0),
        warn_ticks: int(s, "warn-ticks").unwrap_or(d.warn_ticks).clamp(1, 100),
        max_tick_millis: double(s, "max-ms-per-tick")
            .unwrap_or(d.max_tick_millis)
            .max(0.0),
    }
}

const DEFAULT_POOL_SIZE: usize = 4;
const MAX_POOL_SIZE: usize = 32;

/// `databases:`: the MySQL and PostgreSQL servers the owner lets scripts reach; one that can't be used is kept with
/// why, which a script that asks for it is told.
fn databases(s: &Value) -> DatabasesConfig {
    let mut config = DatabasesConfig::default();
    let Some(entries) = s.as_object() else {
        return config;
    };
    for (name, value) in entries {
        if !names::is_id(name) {
            config.invalid.insert(
                name.clone(),
                format!("a connection's name is {}", names::ID_RULE),
            );
            continue;
        }
        if !value.is_object() {
            config.invalid.insert(
                name.clone(),
                "it isn't a section (type, host, database, username, password and packages go under it)"
                    .into(),
            );
            continue;
        }
        match connection(value) {
            Ok(connection) => {
                // A package's own database is in one place: a second connection claiming it can't be used.
                let taken = connection.own_databases.iter().find_map(|namespace| {
                    config
                        .connections
                        .iter()
                        .find(|(_, c)| c.own_databases.contains(namespace))
                        .map(|(other, _)| (namespace.clone(), other.clone()))
                });
                if let Some((namespace, other)) = taken {
                    config.invalid.insert(
                        name.clone(),
                        format!(
                            "`own-databases` has \"{namespace}\", whose own database is already on \"{other}\""
                        ),
                    );
                } else {
                    config.connections.insert(name.clone(), connection);
                }
            }
            Err(why) => {
                config.invalid.insert(name.clone(), why);
            }
        }
    }
    config
}

fn namespaces(
    value: Option<&Value>,
    key: &str,
) -> Result<std::collections::BTreeSet<String>, String> {
    let Some(value) = value.filter(|v| !v.is_null()) else {
        return Ok(Default::default());
    };
    let list = value
        .as_array()
        .ok_or_else(|| format!("`{key}` isn't a list of package namespaces"))?;
    let names: std::collections::BTreeSet<String> =
        list.iter().map(|v| scalar(v).unwrap_or_default()).collect();
    if let Some(bad) = names.iter().find(|n| !names::is_namespace(n)) {
        return Err(format!(
            "`{key}` has \"{bad}\", which isn't a package's namespace"
        ));
    }
    Ok(names)
}

fn connection(fields: &Value) -> Result<ConnectionConfig, String> {
    let kind = text(fields, "type").ok_or("`type` is missing: mysql or postgres")?;
    let kind = kind.trim().to_lowercase();
    let default_port = match kind.as_str() {
        "mysql" => 3306,
        "postgres" => 5432,
        _ => {
            return Err(format!(
                "`type` is \"{kind}\", which isn't mysql or postgres"
            ));
        }
    };
    let host = text(fields, "host").map_or_else(|| "localhost".into(), |h| h.trim().to_owned());
    let host_ok = (host.len() <= 253
        && !host.is_empty()
        && host
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || "._-".contains(c)))
        || (host.starts_with('[')
            && host.ends_with(']')
            && host[1..host.len() - 1]
                .chars()
                .all(|c| c.is_ascii_hexdigit() || c == ':' || c == '.'));
    if !host_ok {
        return Err("`host` isn't a host name or address".into());
    }
    let port = match fields.get("port") {
        None | Some(Value::Null) => Some(default_port),
        Some(_) => int(fields, "port"),
    }
    .filter(|p| (1..=65535).contains(p))
    .ok_or("`port` isn't a port number")?;
    let database = text(fields, "database")
        .map(|d| d.trim().to_owned())
        .ok_or("`database` is missing: the name of the database on the server")?;
    if database.len() > 128
        || !database
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || "_$.-".contains(c))
    {
        return Err(
            "`database` has characters a database name doesn't (letters, digits, _, $, - and .)"
                .into(),
        );
    }
    let pool_size = match fields.get("pool-size") {
        None | Some(Value::Null) => DEFAULT_POOL_SIZE as i64,
        Some(_) => int(fields, "pool-size").ok_or("`pool-size` isn't a number")?,
    }
    .clamp(1, MAX_POOL_SIZE as i64) as usize;
    if fields.get("properties").is_some_and(|v| !v.is_null()) {
        return Err(
            "`properties` isn't read: a connection's TLS is `ssl-mode` (disable, prefer or require)".into(),
        );
    }
    let ssl_mode = match text(fields, "ssl-mode").map(|m| m.trim().to_lowercase()) {
        None => SslMode::Prefer,
        Some(mode) => match mode.as_str() {
            "disable" => SslMode::Disable,
            "prefer" => SslMode::Prefer,
            "require" => SslMode::Require,
            _ => {
                return Err(format!(
                    "`ssl-mode` is \"{mode}\", which isn't disable, prefer or require"
                ));
            }
        },
    };
    Ok(ConnectionConfig {
        kind,
        host,
        port: port as u16,
        database,
        username: text(fields, "username")
            .map(|u| u.trim().to_owned())
            .unwrap_or_default(),
        password: fields.get("password").and_then(scalar).unwrap_or_default(),
        pool_size,
        packages: namespaces(fields.get("packages"), "packages")?,
        ssl_mode,
        own_databases: namespaces(fields.get("own-databases"), "own-databases")?,
    })
}
