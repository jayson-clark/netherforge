//! Package databases on the Rust runtime (Kotlin's `DatabaseTest`, `PackageSchemasTest`): `nf.db()` as the calling
//! package's own SQLite file on its lane, its migrations applied as the session starts, statements checked before
//! they run, and the owner's named connections held to who may open them.

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet};
use std::rc::Rc;
use std::time::{Duration, Instant};

use netherforge_format::package::NoPackages;
use netherforge_format::project::ProjectSource;
use netherforge_platform::{
    FakePlatform, FakePlatformLog, PlatformError, PlatformLog, ServerInfo, Uuid,
};
use netherforge_runtime::session::{Config, Session};

#[derive(Default)]
struct Fake {
    lines: RefCell<Vec<String>>,
}

impl Fake {
    fn lines(&self) -> Vec<String> {
        std::mem::take(&mut *self.lines.borrow_mut())
            .into_iter()
            .filter_map(|line| {
                if let Some(rest) = line.strip_prefix("info [module t] ") {
                    let text = rest.rsplit_once(" (modules/").map_or(rest, |(t, _)| t);
                    Some(text.replace('\t', " "))
                } else if line.starts_with("warn") || line.starts_with("error") {
                    Some(line)
                } else {
                    None
                }
            })
            .collect()
    }
}

impl FakePlatformLog for Fake {
    fn info(&self, message: String) {
        self.lines.borrow_mut().push(format!("info {message}"));
    }
    fn warn(&self, message: String) {
        self.lines.borrow_mut().push(format!("warn {message}"));
    }
    fn error(&self, message: String, _: Option<PlatformError>) {
        self.lines.borrow_mut().push(format!("error {message}"));
    }
}

impl netherforge_platform::FakeTeamOps for Fake {
    fn names(&self) -> Vec<String> {
        Vec::new()
    }
    fn clear_below_names(&self) {}
}

impl netherforge_platform::FakePlayerOps for Fake {
    fn online(&self) -> Vec<netherforge_platform::PlayerRef> {
        Vec::new()
    }
    fn get(&self, _: Uuid) -> Option<netherforge_platform::PlayerRef> {
        None
    }
}

impl netherforge_platform::FakeEntityOps for Fake {
    fn structure_markers(&self) -> Vec<netherforge_platform::StructureMarker> {
        Vec::new()
    }
}

impl FakePlatform for Fake {
    fn info(&self) -> ServerInfo {
        ServerInfo {
            minecraft_version: "26.3".into(),
            plugin_version: "0.0.0".into(),
            supported_targets: vec!["26.3".into()],
        }
    }
    fn log(&self) -> &dyn PlatformLog {
        self
    }
    fn players(&self) -> &dyn netherforge_platform::PlayerOps {
        self
    }
    fn teams(&self) -> &dyn netherforge_platform::TeamOps {
        self
    }
    fn entities(&self) -> &dyn netherforge_platform::EntityOps {
        self
    }
}

struct Files {
    names: &'static BTreeSet<String>,
    texts: RefCell<BTreeMap<String, String>>,
}

impl Files {
    fn new(requires: &str, extra: &[(&str, &str)], script: &str) -> Files {
        let manifest = format!(
            r#"{{ "formatVersion": 1, "name": "Shop", "namespace": "shop", "version": "1.0.0", "minecraft": "26.3", "requires": {requires} }}"#
        );
        let mut files = vec![
            ("netherforge.json".to_owned(), manifest),
            ("modules/t/init.lua".to_owned(), script.to_owned()),
        ];
        files.extend(extra.iter().map(|(p, t)| (p.to_string(), t.to_string())));
        Files {
            names: Box::leak(Box::new(files.iter().map(|(p, _)| p.clone()).collect())),
            texts: RefCell::new(files.into_iter().collect()),
        }
    }
}

impl ProjectSource for Files {
    fn files(&self) -> &BTreeSet<String> {
        self.names
    }
    fn read(&self, path: &str) -> Option<String> {
        self.texts.borrow().get(path).cloned()
    }
}

/// Ticks until the scripts have said `count` lines (or five seconds go by).
fn printed(session: &Session, platform: &Fake, count: usize) -> Vec<String> {
    let mut seen = platform.lines();
    let started = Instant::now();
    while seen.len() < count && started.elapsed() < Duration::from_secs(5) {
        session.tick();
        seen.extend(platform.lines());
        std::thread::sleep(Duration::from_millis(1));
    }
    seen
}

const TRY: &str = r#"local function try(f, ...) local ok, a = pcall(f, ...) print(ok, (tostring(a):gsub("^[^:]+:%d+: ", ""))) end
"#;

#[test]
fn a_packages_own_database_runs_its_statements_in_order_on_its_lane() {
    let dir = tempfile::tempdir().unwrap();
    let files = Files::new(
        r#"{ "db": true }"#,
        &[(
            "migrations/001_init.sql",
            "CREATE TABLE coins (player TEXT PRIMARY KEY, amount INTEGER NOT NULL); CREATE TABLE notes (id INTEGER PRIMARY KEY, text TEXT, data BLOB);",
        )],
        &format!(
            r#"{TRY}
local db = nf.db()
nf.task(function()
  local r, err = db:execute("INSERT INTO coins (player, amount) VALUES (?, ?)", {{ "alex", 5 }})
  print("insert", r.changes, r.last_insert_id, err)
  local r2 = db:execute("INSERT INTO notes (text) VALUES (?)", {{ nil }})
  print("hole", r2.last_insert_id)
  local results, terr = db:transaction({{
    {{ sql = "UPDATE coins SET amount = amount + ? WHERE player = ?", params = {{ 10, "alex" }} }},
    {{ sql = "INSERT INTO coins (player, amount) VALUES (?, ?)", params = {{ "sam", "9007199254740993" }} }},
  }})
  print("transaction", results and #results, terr)
  local bad, berr = db:transaction({{
    {{ sql = "UPDATE coins SET amount = 0" }},
    {{ sql = "INSERT INTO coins (player, amount) VALUES ('alex', 1)" }},
  }})
  print("undone", bad, berr)
  local rows = db:query("SELECT player, amount, amount * 1.5 AS more FROM coins ORDER BY player")
  for _, row in rows do print(row.player, row.amount, row.more) end
  local notes = db:query("SELECT text FROM notes")
  print("null left out", #notes, notes[1].text)
  print("none", #db:query("SELECT data FROM notes WHERE 1 = 0"))
  print(db:query("SELEC 1"))
  print(db:execute("SELECT ?", {{ 1, 2 }}))
  print(db:query("SELECT zeroblob(2) AS b"))
end)
try(db.query, db, "SELECT 1; DROP TABLE coins", nil, print)
try(db.query, db, "ATTACH 'x.db' AS x", nil, print)
try(db.query, db, "SELECT ?", {{ print }}, print)
try(db.query, db, "SELECT ?", {{ x = 1 }}, print)
try(nf.db, "network")
"#
        ),
    );
    let platform = Rc::new(Fake::default());
    let session = Session::start_configured(
        platform.clone(),
        &files,
        &NoPackages,
        Rc::new(netherforge_runtime::store::Store::in_memory()),
        Config {
            databases_dir: Some(dir.path().to_owned()),
            ..Config::default()
        },
    );
    let lines = printed(&session, &platform, 16);
    assert_eq!(
        lines,
        [
            "false Database:query: it has more than one statement: give one statement per call (a transaction takes several)",
            "false Database:query: ATTACH isn't allowed: a package's database is one file, and this would reach another",
            "false Database:query: params[1] is a function, which SQL has no value for (a string, number or boolean)",
            "false Database:query: params is a list of values, one for each ?, but it has a key that isn't a position from 1 to 32766",
            "false nf.db: there's no database connection called \"network\": the server's owner hasn't set any up",
            "insert 1 1 nil",
            "hole 1",
            "transaction 2 nil",
            "undone nil statement 2 failed, so nothing was changed: UNIQUE constraint failed: coins.player",
            "alex 15 22.5",
            "sam 9007199254740993 13510798882111488",
            "null left out 1 nil",
            "none 0",
            "nil near \"SELEC\": syntax error",
            "nil the statement has 1 ? but 2 values were given",
            "nil column \"b\" holds a blob, which a script can't read: select hex(b) instead",
        ]
    );
    assert!(dir.path().join("shop.db").is_file());
}

#[test]
fn a_package_whose_migrations_fail_has_no_database_and_a_problem_says_why() {
    let files = Files::new(
        r#"{ "db": true }"#,
        &[
            (
                "migrations/001_init.sql",
                "CREATE TABLE coins (player TEXT);",
            ),
            (
                "migrations/002_more.sql",
                "CREATE TABLE coins (player TEXT);",
            ),
        ],
        r#"nf.db():query("SELECT 1", nil, function(rows, err) print(rows, err) end)"#,
    );
    let platform = Rc::new(Fake::default());
    let session = Session::start_configured(
        platform.clone(),
        &files,
        &NoPackages,
        Rc::new(netherforge_runtime::store::Store::in_memory()),
        Config::default(),
    );
    let lines = printed(&session, &platform, 2);
    assert_eq!(
        lines,
        [
            "error The database of shop is unavailable: couldn't apply 002_more.sql: table coins already exists",
            "nil The database of shop is unavailable: couldn't apply 002_more.sql: table coins already exists",
        ]
    );
    let problem = session
        .problems()
        .into_iter()
        .find(|p| p.code.as_deref() == Some("migration.failed"))
        .expect("a problem");
    assert_eq!(problem.file, "migrations/002_more.sql");
}

#[test]
fn a_named_connection_is_the_owners_and_open_only_to_the_packages_it_lists() {
    let files = Files::new(
        r#"{ "db": true }"#,
        &[],
        &format!(
            r#"{TRY}
try(nf.db, "network")
try(nf.db, "broken")
local db = nf.db("shared")
db:query("SELECT 1", nil, function(rows, err) print(rows, err) end)
"#
        ),
    );
    let mut config = netherforge_runtime::databases::DatabasesConfig::default();
    let connection = |packages: &[&str]| netherforge_runtime::databases::ConnectionConfig {
        kind: "postgres".into(),
        // Nothing listens there: the connection is refused at once.
        host: "127.0.0.1".into(),
        port: 1,
        database: "game".into(),
        username: "u".into(),
        password: "secret".into(),
        pool_size: 4,
        packages: packages.iter().map(|p| p.to_string()).collect(),
        ssl_mode: netherforge_runtime::databases::SslMode::Disable,
        own_databases: BTreeSet::new(),
    };
    config
        .connections
        .insert("network".into(), connection(&["other"]));
    config
        .connections
        .insert("shared".into(), connection(&["shop"]));
    config.invalid.insert(
        "broken".into(),
        "`type` is missing: mysql or postgres".into(),
    );
    let platform = Rc::new(Fake::default());
    let session = Session::start_configured(
        platform.clone(),
        &files,
        &NoPackages,
        Rc::new(netherforge_runtime::store::Store::in_memory()),
        Config {
            databases: config,
            ..Config::default()
        },
    );
    let lines = printed(&session, &platform, 4);
    assert_eq!(
        lines,
        [
            "error config.yml's databases.broken can't be used: `type` is missing: mysql or postgres",
            "false nf.db: the connection \"network\" isn't open to package \"shop\": the server's owner lists the packages that may use it under `databases.network.packages` in the plugin's config.yml",
            "false nf.db: the server's connection \"broken\" can't be used: `type` is missing: mysql or postgres (the owner fixes it in config.yml's databases)",
            "nil the database connection \"shared\" couldn't be reached (the server's log says why)",
        ]
    );
}

/// A connection to the server `NETHERFORGE_TEST_<KIND>` names (`host:port:user:database`, no password), or none:
/// the test then says it was skipped.
fn server(kind: &str, own: &[&str]) -> Option<netherforge_runtime::databases::ConnectionConfig> {
    let var = format!("NETHERFORGE_TEST_{}", kind.to_uppercase());
    let Ok(value) = std::env::var(&var) else {
        eprintln!("skipped: {var} names no {kind} server (host:port:user:database)");
        return None;
    };
    let parts: Vec<&str> = value.split(':').collect();
    Some(netherforge_runtime::databases::ConnectionConfig {
        kind: kind.into(),
        host: parts[0].into(),
        port: parts[1].parse().unwrap(),
        database: parts[3].into(),
        username: parts[2].into(),
        password: std::env::var(format!("{var}_PASSWORD")).unwrap_or_default(),
        pool_size: 2,
        packages: ["shop".to_owned()].into(),
        ssl_mode: netherforge_runtime::databases::SslMode::Disable,
        own_databases: own.iter().map(|s| s.to_string()).collect(),
    })
}

fn on_server(kind: &str, dialect_sql: &str, script: &str, count: usize) -> Option<Vec<String>> {
    let connection = server(kind, &["shop"])?;
    let files = Files::new(
        r#"{ "db": true }"#,
        &[
            (
                "migrations/001_init.sql",
                "CREATE TABLE coins (player TEXT PRIMARY KEY, amount INTEGER NOT NULL);",
            ),
            (&format!("migrations/{kind}/001_init.sql"), dialect_sql),
        ],
        script,
    );
    let mut config = netherforge_runtime::databases::DatabasesConfig::default();
    config.connections.insert("network".into(), connection);
    let platform = Rc::new(Fake::default());
    let session = Session::start_configured(
        platform.clone(),
        &files,
        &NoPackages,
        Rc::new(netherforge_runtime::store::Store::in_memory()),
        Config {
            databases: config,
            ..Config::default()
        },
    );
    Some(printed(&session, &platform, count))
}

const SERVER_SCRIPT: &str = r#"
local own, named = nf.db(), nf.db("network")
nf.task(function()
  own:execute("DELETE FROM coins")
  local r, err = own:execute("INSERT INTO coins (player, amount) VALUES (?, ?)", { "alex", 5 })
  print("insert", r and r.changes, err)
  local results, terr = own:transaction({
    { sql = "UPDATE coins SET amount = amount + ? WHERE player = ?", params = { 10, "alex" } },
    { sql = "INSERT INTO coins (player, amount) VALUES (?, ?)", params = { "sam", 2 } },
  })
  print("transaction", results and #results, terr)
  local bad, berr = own:transaction({
    { sql = "UPDATE coins SET amount = 0" },
    { sql = "INSERT INTO coins (player, amount) VALUES ('alex', 1)" },
  })
  print("undone", bad, berr ~= nil)
  for _, row in own:query("SELECT player, amount, amount * 1.5 AS more, amount > 6 AS big FROM coins ORDER BY player") do
    print(row.player, row.amount, row.more, row.big)
  end
  local rows, qerr = named:query("SELECT ? AS a, 2.5 AS b, NULL AS c", { "x" })
  print("named", rows and rows[1].a, rows and rows[1].b, rows and rows[1].c, qerr)
end)
"#;

const SERVER_LINES: [&str; 6] = [
    "insert 1 nil",
    "transaction 2 nil",
    "undone nil true",
    "alex 15 22.5 1",
    "sam 2 3 0",
    "named x 2.5 nil nil",
];

#[test]
fn a_packages_own_database_and_a_named_connection_on_postgres() {
    let Some(lines) = on_server(
        "postgres",
        "CREATE TABLE coins (player TEXT PRIMARY KEY, amount INTEGER NOT NULL); CREATE TABLE IF NOT EXISTS more (id SERIAL PRIMARY KEY);",
        SERVER_SCRIPT,
        6,
    ) else {
        return;
    };
    assert_eq!(lines, SERVER_LINES);
}

#[test]
fn a_packages_own_database_and_a_named_connection_on_mysql() {
    let Some(lines) = on_server(
        "mysql",
        "CREATE TABLE coins (player VARCHAR(64) PRIMARY KEY, amount INTEGER NOT NULL); CREATE TABLE more (id INTEGER PRIMARY KEY AUTO_INCREMENT);",
        SERVER_SCRIPT,
        6,
    ) else {
        return;
    };
    assert_eq!(lines, SERVER_LINES);
}
