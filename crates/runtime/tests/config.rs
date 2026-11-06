//! `config.yml` read in one place (Kotlin's `RuntimeConfigTest`): a production server's project and sections, the
//! dev server's properties, the owner's policy beside it, and what can't be acted on.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use netherforge_runtime::config::{ServerAddress, read, read_yaml};

fn server(dir: &Path) -> ServerAddress {
    ServerAddress {
        directory: dir.to_owned(),
        ip: "10.0.0.5".into(),
    }
}

fn none(_: &str) -> Option<String> {
    None
}

const YAML: &str = r#"
project: projects/shop
schedules:
  time-zone: Europe/London
http:
  timeout-seconds: 30
  requests-per-minute: -5
  allow-private:
    - { package: shop, host: "[::1]", port: 8080, requests-per-minute: 10 }
  connections:
    max-total: 8
web:
  enabled: true
  public-url: https://shop.example.com/
wasm:
  threads: "3"
  max-line-bytes: 1
network:
  enabled: true
  hub: proxy.internal:7000
  server-name: lobby-1
  secret: s3cret
  wait-for-hub-seconds: 20
  require-hub: true
  register:
    address: "25566"
resource-pack:
  port: 9000
  storage:
    type: s3
    endpoint: https://s3.example.com
    bucket: packs
    public-url: https://cdn.example.com
    access-key: a
    secret-key: b
structures:
  blocks-per-tick: 100
screens:
  bytes-per-tick: 1
worlds:
  import-root: off
  max-loaded: 6
files:
  max-megabytes-per-package: 2
databases:
  network:
    type: postgres
    host: db.internal
    database: game
    username: shop
    password: hunter2
    packages: [shop]
  Bad Name:
    type: mysql
  nameless:
    host: db.internal
bundles:
  require-signature: true
  trusted-keys: [ k1 ]
packages:
  provided: [ libs/acme ]
"#;

#[test]
fn a_production_server_reads_its_project_and_every_section() {
    let dir = tempfile::tempdir().unwrap();
    let plugin = dir.path().join("plugins/NetherForge");
    std::fs::create_dir_all(&plugin).unwrap();
    let settings = read_yaml(YAML).unwrap();
    let config = read(&plugin, &settings, &server(dir.path()), &none, &none)
        .unwrap()
        .expect("a project");
    assert_eq!(config.project, dir.path().join("projects/shop"));
    assert!(!config.resolves_packages);
    assert_eq!(config.provided_packages, [dir.path().join("libs/acme")]);
    assert!(config.bundles.require_signature);
    let s = &config.session;
    assert_eq!(s.time_zone.as_deref(), Some("Europe/London"));
    assert_eq!(s.data_dir.as_deref(), Some(plugin.join("data").as_path()));
    assert_eq!(
        s.databases_dir.as_deref(),
        Some(plugin.join("databases").as_path())
    );
    assert_eq!(s.http.timeout_seconds, 30);
    // Not a positive number: the default.
    assert_eq!(s.http.requests_per_minute, 60);
    assert_eq!(s.http.connections.max_total, 8);
    assert_eq!(s.http.allow_private[0].host, "::1");
    assert_eq!(s.http.allow_private[0].requests_per_minute, Some(10));
    assert!(s.web.enabled);
    assert_eq!(
        s.web.public_url.as_deref(),
        Some("https://shop.example.com")
    );
    assert_eq!(s.wasm.threads, 3);
    assert_eq!(s.wasm.max_line_bytes, 4);
    assert_eq!(
        (s.network.host.as_str(), s.network.port),
        ("proxy.internal", 7000)
    );
    assert_eq!(s.network.wait_for_hub_seconds, 20);
    assert!(s.network.require_hub);
    assert_eq!(s.network.register.as_ref().map(|r| r.port), Some(25566));
    assert_eq!(s.pack.port, 9000);
    assert_eq!(s.pack.public_url.as_deref(), Some("http://10.0.0.5:9000"));
    assert_eq!(
        s.pack.storage.as_ref().map(|st| st.bucket.as_str()),
        Some("packs")
    );
    assert_eq!(s.pack.state_dir.as_deref(), Some(plugin.as_path()));
    assert_eq!(s.structures.blocks_per_tick, 100);
    assert_eq!(s.screens.bytes_per_tick, 128 * 128 + 32);
    assert_eq!(s.worlds.import_root, None);
    assert_eq!(s.worlds.max_loaded, 6);
    assert_eq!(s.files.max_bytes_per_package, 2 * 1024 * 1024);
    assert!(!s.bots.enabled);
    let databases = &s.databases;
    assert_eq!(databases.connections["network"].port, 5432);
    assert!(!format!("{:?}", databases.connections["network"]).contains("hunter2"));
    assert!(databases.invalid["Bad Name"].starts_with("a connection's name is"));
    assert_eq!(
        databases.invalid["nameless"],
        "`type` is missing: mysql or postgres"
    );
}

#[test]
fn the_dev_server_takes_its_project_and_network_from_the_editor() {
    let dir = tempfile::tempdir().unwrap();
    let props: HashMap<&str, String> = HashMap::from([
        ("netherforge.project", "/work/shop".to_owned()),
        ("netherforge.bridge.port", "4567".to_owned()),
        ("netherforge.network.hub", "127.0.0.1:7100".to_owned()),
        ("netherforge.network.server", "dev-1".to_owned()),
        ("netherforge.test.wall-clock-rate", "60".to_owned()),
    ]);
    let env: HashMap<&str, String> = HashMap::from([
        ("NETHERFORGE_BRIDGE_TOKEN", "t0k".to_owned()),
        ("NETHERFORGE_NETWORK_SECRET", "dev-secret".to_owned()),
    ]);
    let settings = read_yaml("project: ignored\nhttp:\n  timeout-seconds: 9\n").unwrap();
    let config = read(
        dir.path(),
        &settings,
        &server(dir.path()),
        &|k| props.get(k).cloned(),
        &|k| env.get(k).cloned(),
    )
    .unwrap()
    .unwrap();
    assert_eq!(config.project, PathBuf::from("/work/shop"));
    assert_eq!(
        config.bridge.as_ref().map(|b| (b.port, b.token.as_str())),
        Some((4567, "t0k"))
    );
    assert!(config.resolves_packages);
    assert_eq!(config.wall_clock_rate, 60.0);
    assert!(config.session.bots.enabled);
    assert_eq!(config.session.http.timeout_seconds, 9);
    let network = &config.session.network;
    assert!(network.enabled);
    assert_eq!(network.server_name.as_deref(), Some("dev-1"));
    assert_eq!(network.secret.as_deref(), Some("dev-secret"));
}

#[test]
fn no_project_is_nothing_to_run_and_what_cant_be_acted_on_is_an_error() {
    let dir = tempfile::tempdir().unwrap();
    let at = server(dir.path());
    assert!(
        read(dir.path(), &read_yaml("").unwrap(), &at, &none, &none)
            .unwrap()
            .is_none()
    );
    let bad = read_yaml("project: p\nhttp:\n  allow-private:\n    - { package: shop, host: db }\n")
        .unwrap();
    assert_eq!(
        read(dir.path(), &bad, &at, &none, &none).err().as_deref(),
        Some("http.allow-private[0] needs a port from 1 to 65535")
    );
    let store =
        read_yaml("project: p\nresource-pack:\n  storage: { type: s3, endpoint: e }\n").unwrap();
    assert_eq!(
        read(dir.path(), &store, &at, &none, &none).err().as_deref(),
        Some("resource-pack.storage.bucket is empty")
    );
    // The owner's policy beside config.yml: one that can't be read runs nothing, saying why.
    std::fs::write(dir.path().join("policy.json"), "{ nope").unwrap();
    let config = read(
        dir.path(),
        &read_yaml("project: p").unwrap(),
        &at,
        &none,
        &none,
    )
    .unwrap()
    .unwrap();
    assert!(config.policy.is_none());
    assert!(
        config
            .policy_problem
            .unwrap()
            .contains("policy.json can't be read")
    );
    std::fs::write(
        dir.path().join("policy.json"),
        r#"{ "limits": { "call-milliseconds": 200 } }"#,
    )
    .unwrap();
    let config = read(
        dir.path(),
        &read_yaml("project: p").unwrap(),
        &at,
        &none,
        &none,
    )
    .unwrap()
    .unwrap();
    assert_eq!(config.policy.unwrap().limits.call_millis, Some(200));
}
