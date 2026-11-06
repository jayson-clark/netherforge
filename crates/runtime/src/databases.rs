//! Package databases (Kotlin's `PackageDatabases`, `PackageSchemas`, `SqlText`, `DatabasesConfig` and
//! `DatabaseImpl`): `nf.db()`, the calling package's own SQLite file, and the `Database` handle's `query`,
//! `execute` and `transaction`.
//!
//! - **One file each**, `<plugin folder>/databases/<namespace>.db` (in memory when the server gives no folder), apart
//!   from the runtime's store, so a package's schema, size and failures are its own. Each runs on a lane of its own
//!   (`database:<namespace>`): one package's statements run in the order they were given and never in the way of
//!   another's. Its connection can attach no other database (`SQLITE_LIMIT_ATTACHED` 0), and `ATTACH`, `VACUUM` and
//!   the pragmas that name a folder are refused before they run.
//! - **Migrations** (`migrations/NNN_name.sql`, the packages' too) are applied in order as the session starts and when
//!   one is added on a reload, each once (the database's `migrations` table, scoped by namespace). A package whose
//!   migrations can't all be applied gets no database at all until that's fixed: its scripts' calls answer
//!   `nil, err` with the reason, a `migration.failed` problem on the file that caused it.
//! - **What a script could get wrong in how it asks** (SQL that isn't one statement, a value SQL has no value for) is
//!   an error at its line, found before anything starts; what only the database can say is the work's failure,
//!   `nil, err`.
//! - **Numbers** follow the skill's rule: a whole number in the safe range binds as an integer, any other as a real;
//!   an integer column's value past the safe range comes back as a string of its digits.
//!
//! Not yet: the MySQL and PostgreSQL connections the owner names in `databases:` (`nf.db("network")`) are read and
//! checked (who may open one), but the Rust runtime has no driver for them, so their work fails saying so; and a
//! package's own database on such a connection (`own-databases`) stays its SQLite file.

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::{Arc, Mutex, mpsc};

use mlua::Value;
use netherforge_api::api::DatabaseApi;
use netherforge_api::{ApiError, ApiResult, Pending, handles, shapes};
use netherforge_format::problem::{Problem, codes};
use rusqlite::Connection;
use rusqlite::types::{ToSqlOutput, Value as Sql, ValueRef};

use crate::session::{Define, Service};
use crate::shared::Shared;
use crate::{Room, Scripts};

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// The largest whole number a script's number holds exactly.
const MAX_SAFE: f64 = 9_007_199_254_740_991.0;
/// SQLite's default limit on `?`s in one statement.
const MAX_VALUES: i64 = 32_766;
/// How long a write waits for another connection's lock (someone's sqlite3 on the file).
const BUSY_MILLIS: u64 = 5_000;

// ---- SQL text ------------------------------------------------------------------

/// Why `sql` isn't one statement, or none when it is: a `;` ends it, and only whitespace and comments may follow.
pub fn sql_problem(sql: &str) -> Option<&'static str> {
    let chars: Vec<char> = sql.chars().collect();
    let (mut ended, mut content) = (false, false);
    let mut i = 0;
    let starts = |i: usize, s: &str| {
        s.chars()
            .enumerate()
            .all(|(k, c)| chars.get(i + k) == Some(&c))
    };
    let find = |from: usize, s: &str| (from..chars.len()).find(|&k| starts(k, s));
    while i < chars.len() {
        let c = chars[i];
        if c == ';' {
            if !content {
                return Some("it is empty");
            }
            ended = true;
            i += 1;
        } else if c.is_whitespace() {
            i += 1;
        } else if c == '-' && starts(i, "--") {
            i = find(i, "\n").map_or(chars.len(), |k| k + 1);
        } else if c == '/' && starts(i, "/*") {
            i = find(i + 2, "*/").map_or(chars.len(), |k| k + 2);
        } else {
            if ended {
                return Some(
                    "it has more than one statement: give one statement per call (a transaction takes several)",
                );
            }
            content = true;
            if matches!(c, '\'' | '"' | '`' | '[') {
                let close = if c == '[' { ']' } else { c };
                i = (i + 1..chars.len())
                    .find(|&k| chars[k] == close)
                    .map_or(chars.len(), |k| k + 1);
            } else {
                i += 1;
            }
        }
    }
    (!content).then_some("it is empty")
}

/// Up to `count` of `sql`'s first words (identifiers, and `.` between them), past whitespace and comments.
fn leading_words(sql: &str, count: usize) -> Vec<String> {
    let chars: Vec<char> = sql.chars().collect();
    let mut words = Vec::new();
    let mut i = 0;
    let starts = |i: usize, s: &str| {
        s.chars()
            .enumerate()
            .all(|(k, c)| chars.get(i + k) == Some(&c))
    };
    while i < chars.len() && words.len() < count {
        let c = chars[i];
        if c.is_whitespace() {
            i += 1;
        } else if c == '-' && starts(i, "--") {
            i = (i..chars.len())
                .find(|&k| chars[k] == '\n')
                .map_or(chars.len(), |k| k + 1);
        } else if c == '/' && starts(i, "/*") {
            i = (i + 2..chars.len())
                .find(|&k| starts(k, "*/"))
                .map_or(chars.len(), |k| k + 2);
        } else if c == '.' {
            words.push(".".into());
            i += 1;
        } else if matches!(c, '"' | '`' | '[') {
            let close = if c == '[' { ']' } else { c };
            let end = (i + 1..chars.len())
                .find(|&k| chars[k] == close)
                .unwrap_or(chars.len());
            words.push(chars[i + 1..end].iter().collect());
            i = end + 1;
        } else if c.is_alphanumeric() || c == '_' {
            let start = i;
            while i < chars.len()
                && (chars[i].is_alphanumeric() || chars[i] == '_' || chars[i] == '$')
            {
                i += 1;
            }
            words.push(chars[start..i].iter().collect());
        } else {
            break;
        }
    }
    words
}

/// Why `sql` can't run on a package's own SQLite database: a statement that reaches another file.
pub fn sqlite_problem(sql: &str) -> Option<String> {
    let words = leading_words(sql, 4);
    let first = words.first()?.to_lowercase();
    if ["attach", "detach", "vacuum"].contains(&first.as_str()) {
        return Some(format!(
            "{} isn't allowed: a package's database is one file, and this would reach another",
            first.to_uppercase()
        ));
    }
    if first == "pragma" {
        let name = if words.get(2).map(String::as_str) == Some(".") {
            words.get(3)
        } else {
            words.get(1)
        }?
        .to_lowercase();
        if ["temp_store_directory", "data_store_directory"].contains(&name.as_str()) {
            return Some(format!(
                "PRAGMA {name} isn't allowed: it names a folder on the server's disk"
            ));
        }
    }
    None
}

// ---- `databases:` -------------------------------------------------------------

/// One `databases:` entry, as far as the Rust runtime reads it: who may open it. The password is never shown.
#[derive(Clone)]
pub struct ConnectionConfig {
    pub kind: String,
    pub host: String,
    pub port: u16,
    pub database: String,
    pub username: String,
    pub password: String,
    pub pool_size: usize,
    pub packages: BTreeSet<String>,
    pub properties: BTreeMap<String, String>,
    pub own_databases: BTreeSet<String>,
}

/// The password is never in a log line.
impl std::fmt::Debug for ConnectionConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "{} connection to {}:{}/{} as {}",
            self.kind, self.host, self.port, self.database, self.username
        )
    }
}

/// `databases:` in `config.yml`: the MySQL and PostgreSQL servers the owner lets scripts reach, by name, and the
/// entries that couldn't be read, with why.
#[derive(Clone, Debug, Default)]
pub struct DatabasesConfig {
    pub connections: BTreeMap<String, ConnectionConfig>,
    pub invalid: BTreeMap<String, String>,
}

impl DatabasesConfig {
    /// Why `namespace`'s scripts can't open `name`, or none when they can: what the script needs to hear, never a
    /// host, a user or a password.
    pub fn problem(&self, name: &str, namespace: &str) -> Option<String> {
        if let Some(why) = self.invalid.get(name) {
            return Some(format!(
                "the server's connection \"{name}\" can't be used: {why} (the owner fixes it in config.yml's databases)"
            ));
        }
        let Some(connection) = self.connections.get(name) else {
            let known: Vec<&str> = self.connections.keys().map(String::as_str).collect();
            return Some(format!(
                "there's no database connection called \"{name}\"{}",
                if known.is_empty() {
                    ": the server's owner hasn't set any up".to_owned()
                } else {
                    format!(" (the server's owner has set up: {})", known.join(", "))
                }
            ));
        };
        (!connection.packages.contains(namespace)).then(|| format!(
            "the connection \"{name}\" isn't open to package \"{namespace}\": the server's owner lists the packages that may use it under `databases.{name}.packages` in the plugin's config.yml"
        ))
    }
}

// ---- the files and their lanes ------------------------------------------------

/// Work for a database: its connection, or why it couldn't be opened.
type Job = Box<dyn FnOnce(Result<&mut Connection, String>) + Send>;

/// One package's database: its connection, used only on its lane.
struct PackageDatabase {
    namespace: String,
    lane: crate::http::Workers,
    connection: Arc<Mutex<Option<Connection>>>,
    file: Option<PathBuf>,
}

impl PackageDatabase {
    /// Runs `job` on the connection, on the lane, opening it the first time.
    fn submit(&self, job: Job) {
        let connection = self.connection.clone();
        let file = self.file.clone();
        let namespace = self.namespace.clone();
        self.lane.submit(move || {
            let mut held = connection.lock().unwrap_or_else(|e| e.into_inner());
            if held.is_none() {
                match open(file.as_deref()) {
                    Ok(c) => *held = Some(c),
                    Err(e) => {
                        return job(Err(format!(
                            "the database of {namespace} couldn't be opened: {e}"
                        )));
                    }
                }
            }
            job(Ok(held.as_mut().expect("opened")));
        });
    }

    /// Runs `work` on the lane and waits for its answer: for migrations as the session starts.
    fn wait<T: Send + 'static>(
        &self,
        work: impl FnOnce(Result<&mut Connection, String>) -> T + Send + 'static,
    ) -> Option<T> {
        let (send, receive) = mpsc::channel();
        self.submit(Box::new(move |c| {
            let _ = send.send(work(c));
        }));
        receive.recv().ok()
    }
}

/// The file's connection, made and checked: a file that isn't a database fails here, not at the first write.
fn open(file: Option<&std::path::Path>) -> Result<Connection, String> {
    let connection = match file {
        Some(file) => {
            if let Some(dir) = file.parent() {
                std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
            }
            Connection::open(file)
        }
        None => Connection::open_in_memory(),
    }
    .map_err(|e| e.to_string())?;
    // A package's scripts write their own SQL: ATTACH would reach any file the server can.
    connection
        .set_limit(rusqlite::limits::Limit::SQLITE_LIMIT_ATTACHED, 0)
        .map_err(|e| e.to_string())?;
    connection
        .busy_timeout(std::time::Duration::from_millis(BUSY_MILLIS))
        .map_err(|e| e.to_string())?;
    if file.is_some() {
        let _ = connection.pragma_update(None, "journal_mode", "WAL");
        let _ = connection.pragma_update(None, "synchronous", "NORMAL");
    }
    connection
        .query_row("SELECT count(*) FROM sqlite_schema", [], |_| Ok(()))
        .map_err(|e| e.to_string())?;
    Ok(connection)
}

/// One step of a database's schema: `NNN_name.sql`.
#[derive(Clone, Debug)]
struct Migration {
    version: i64,
    name: String,
    sql: String,
}

impl Migration {
    fn file_name(&self) -> String {
        format!("{:03}_{}.sql", self.version, self.name)
    }
}

/// Applies the migrations `scope` hasn't had, in order, each in its own transaction: the failed one's file name
/// and why, the ones before it kept.
fn migrate(
    c: &mut Connection,
    scope: &str,
    migrations: &[Migration],
) -> Result<(), (String, String)> {
    let fail = |m: &Migration, e: rusqlite::Error| (m.file_name(), said(e));
    c.execute_batch(
        "CREATE TABLE IF NOT EXISTS migrations (scope TEXT NOT NULL, version INTEGER NOT NULL, name TEXT NOT NULL, applied TEXT NOT NULL, PRIMARY KEY (scope, version))",
    )
    .map_err(|e| (String::new(), e.to_string()))?;
    let done: BTreeSet<i64> = {
        let mut select = c
            .prepare("SELECT version FROM migrations WHERE scope = ?1")
            .map_err(|e| (String::new(), e.to_string()))?;
        select
            .query_map([scope], |r| r.get(0))
            .map_err(|e| (String::new(), e.to_string()))?
            .flatten()
            .collect()
    };
    let mut sorted = migrations.to_vec();
    sorted.sort_by_key(|m| m.version);
    for m in sorted.iter().filter(|m| !done.contains(&m.version)) {
        let tx = c.transaction().map_err(|e| fail(m, e))?;
        tx.execute_batch(&m.sql).map_err(|e| fail(m, e))?;
        tx.execute(
            "INSERT INTO migrations (scope, version, name, applied) VALUES (?1, ?2, ?3, datetime('now'))",
            rusqlite::params![scope, m.version, m.name],
        )
        .map_err(|e| fail(m, e))?;
        tx.commit().map_err(|e| fail(m, e))?;
    }
    Ok(())
}

/// The packages' own databases, and why any can't be used.
#[derive(Default)]
pub struct Databases {
    /// `<plugin folder>/databases`; none keeps every database in memory.
    pub dir: Option<PathBuf>,
    pub config: DatabasesConfig,
    open: HashMap<String, Rc<PackageDatabase>>,
    /// Why a package's database can't be used, by namespace, and the problem that says it.
    failures: BTreeMap<String, Problem>,
}

impl Databases {
    fn database(&mut self, namespace: &str) -> Rc<PackageDatabase> {
        let dir = self.dir.clone();
        self.open
            .entry(namespace.to_owned())
            .or_insert_with(|| {
                Rc::new(PackageDatabase {
                    namespace: namespace.to_owned(),
                    lane: crate::http::Workers::lane(&format!("NetherForge database:{namespace}")),
                    connection: Arc::default(),
                    file: dir.map(|d| d.join(format!("{namespace}.db"))),
                })
            })
            .clone()
    }

    /// Why `namespace`'s database can't be used (its migrations failed), or none when it can.
    pub fn unavailable(&self, namespace: &str) -> Option<String> {
        self.failures.get(namespace).map(|p| p.message.clone())
    }

    pub fn problems(&self) -> Vec<Problem> {
        self.failures.values().cloned().collect()
    }

    fn fail(
        &mut self,
        platform: &dyn netherforge_platform::Platform,
        namespace: &str,
        file: &str,
        why: String,
    ) {
        let message = format!("The database of {namespace} is unavailable: {why}");
        platform.log().error(message.clone(), None);
        self.failures.insert(
            namespace.to_owned(),
            codes::MIGRATION_FAILED.at(file, message, None),
        );
    }
}

/// A migration as the session found it: its id, its file (a project or package path), whether it has errors, and
/// its text.
type MigrationFile = (String, String, bool, Option<String>);

/// Every package's migrations, by namespace: each one's id (the file's name without `.sql`), its file as a project
/// or package path, whether it has errors, and its text.
fn migrations_of(define: &Define) -> BTreeMap<String, Vec<MigrationFile>> {
    use netherforge_format::kinds::package_path;
    let project = define.project;
    let mut out: BTreeMap<String, Vec<MigrationFile>> = BTreeMap::new();
    let mut add = |namespace: &str,
                   resources: &[netherforge_format::project::Resource],
                   read: &dyn Fn(&str) -> Option<String>,
                   package: bool| {
        for r in resources.iter().filter(|r| r.found.kind.id == "migration") {
            let path = r.found.kind.path_of(&r.found.id, &r.found.group);
            let shown = if package {
                package_path(namespace, &path)
            } else {
                path.clone()
            };
            out.entry(namespace.to_owned()).or_default().push((
                r.found.id.clone(),
                shown,
                r.errors,
                read(&path),
            ));
        }
    };
    add(
        project.namespace(),
        &project.resources,
        &|p| define.source.read(p),
        false,
    );
    for (namespace, package) in &project.packages {
        let source = &package.resolved.source;
        add(
            namespace,
            &package.project.resources,
            &|p| source.read(p),
            true,
        );
    }
    for list in out.values_mut() {
        list.sort();
    }
    out
}

/// Brings `namespace`'s database up to date with its migrations, or records why it can't be.
fn apply(shared: &Shared, namespace: &str, migrations: &[(String, String, bool, Option<String>)]) {
    let platform = &*shared.platform;
    let mut databases = shared.databases.borrow_mut();
    databases.failures.remove(namespace);
    if migrations.is_empty() {
        return;
    }
    if let Some((_, path, _, _)) = migrations.iter().find(|(_, _, errors, _)| *errors) {
        databases.fail(
            platform,
            namespace,
            path,
            format!("its migrations have problems (see {path}), so the database isn't built"),
        );
        return;
    }
    let mut steps = Vec::new();
    for (id, path, _, text) in migrations {
        let Some(sql) = text else {
            databases.fail(
                platform,
                namespace,
                path,
                format!("{path} couldn't be read"),
            );
            return;
        };
        let Some(version) = netherforge_format::kind::files::migration_number(id) else {
            continue;
        };
        let name = id.split_once('_').map_or(id.as_str(), |(_, n)| n);
        steps.push(Migration {
            version: i64::from(version),
            name: name.to_owned(),
            sql: sql.clone(),
        });
    }
    let database = databases.database(namespace);
    let scope = namespace.to_owned();
    drop(databases);
    let applied = database.wait(move |c| match c {
        Ok(c) => migrate(c, &scope, &steps),
        Err(why) => Err((String::new(), why)),
    });
    let mut databases = shared.databases.borrow_mut();
    match applied {
        Some(Ok(())) => {}
        Some(Err((file, why))) => {
            let path = migrations
                .iter()
                .find(|(id, _, _, _)| format!("{id}.sql") == file)
                .map_or_else(|| migrations[0].1.clone(), |(_, p, _, _)| p.clone());
            let why = if file.is_empty() {
                why
            } else {
                format!("couldn't apply {file}: {why}")
            };
            databases.fail(platform, namespace, &path, why);
        }
        None => {
            let path = migrations[0].1.clone();
            databases.fail(
                platform,
                namespace,
                &path,
                "couldn't open the database".into(),
            );
        }
    }
}

// ---- statements ---------------------------------------------------------------

/// What the database said, without where in the text it was (the script has the text).
fn said(e: rusqlite::Error) -> String {
    match e {
        rusqlite::Error::SqlInputError { msg, .. } => msg,
        rusqlite::Error::SqliteFailure(_, Some(msg)) => msg,
        other => other.to_string(),
    }
}

/// A value a statement binds.
#[derive(Clone, Debug)]
enum Param {
    Null,
    Integer(i64),
    Real(f64),
    Text(String),
}

impl rusqlite::ToSql for Param {
    fn to_sql(&self) -> rusqlite::Result<ToSqlOutput<'_>> {
        Ok(match self {
            Param::Null => ToSqlOutput::Owned(Sql::Null),
            Param::Integer(n) => ToSqlOutput::Owned(Sql::Integer(*n)),
            Param::Real(n) => ToSqlOutput::Owned(Sql::Real(*n)),
            Param::Text(s) => ToSqlOutput::Borrowed(ValueRef::Text(s.as_bytes())),
        })
    }
}

/// A column's value as a script reads it.
#[derive(Clone, Debug)]
enum Cell {
    Integer(i64),
    Real(f64),
    Text(String),
}

/// One checked statement: its text and the values it binds.
#[derive(Clone, Debug)]
struct Statement {
    sql: String,
    values: Vec<Param>,
}

type Row = Vec<(String, Cell)>;

impl Statement {
    fn prepared<'c>(&self, c: &'c Connection) -> Result<rusqlite::Statement<'c>, String> {
        let mut statement = c.prepare(&self.sql).map_err(said)?;
        let wanted = statement.parameter_count();
        if self.values.len() > wanted {
            return Err(format!(
                "the statement has {wanted} ? but {} values were given",
                self.values.len()
            ));
        }
        // A Lua list can't say its last values are nil, so those not given are NULL.
        for i in 0..wanted {
            let value = self.values.get(i).cloned().unwrap_or(Param::Null);
            statement.raw_bind_parameter(i + 1, value).map_err(said)?;
        }
        Ok(statement)
    }

    fn rows(&self, c: &Connection) -> Result<Vec<Row>, String> {
        let mut statement = self.prepared(c)?;
        let names: Vec<String> = statement
            .column_names()
            .into_iter()
            .map(str::to_owned)
            .collect();
        let mut rows = statement.raw_query();
        let mut out = Vec::new();
        while let Some(row) = rows.next().map_err(said)? {
            let mut cells = Vec::new();
            for (i, name) in names.iter().enumerate() {
                let cell = match row.get_ref(i).map_err(said)? {
                    ValueRef::Null => continue,
                    ValueRef::Integer(n) => Cell::Integer(n),
                    ValueRef::Real(n) => Cell::Real(n),
                    ValueRef::Text(t) => Cell::Text(String::from_utf8_lossy(t).into_owned()),
                    ValueRef::Blob(_) => {
                        return Err(format!(
                            "column \"{name}\" holds a blob, which a script can't read: select hex({name}) instead"
                        ));
                    }
                };
                cells.push((name.clone(), cell));
            }
            out.push(cells);
        }
        Ok(out)
    }

    fn execute(&self, c: &Connection) -> Result<shapes::DatabaseResult, String> {
        let mut statement = self.prepared(c)?;
        let changes = if statement.column_count() > 0 {
            let mut rows = statement.raw_query();
            while rows.next().map_err(said)?.is_some() {}
            0
        } else {
            statement.raw_execute().map_err(said)? as i64
        };
        let last = c.last_insert_rowid();
        Ok(shapes::DatabaseResult {
            changes,
            last_insert_id: (last != 0).then_some(last),
        })
    }
}

/// What `params` binds, in order: a hole is NULL. Anything SQL has no value for is an error.
fn values(params: &Value, what: &str) -> ApiResult<Vec<Param>> {
    let table = match params {
        Value::Nil => return Ok(Vec::new()),
        Value::Table(t) => t,
        _ => {
            return Err(err(format!(
                "{what}: params is a list of values, one for each ?"
            )));
        }
    };
    let mut found = BTreeMap::new();
    for pair in table.clone().pairs::<Value, Value>() {
        let (key, value) = pair.map_err(|e| err(e.to_string()))?;
        let position = match key {
            Value::Integer(n) => n as f64,
            Value::Number(n) => n,
            _ => 0.0,
        };
        if position.fract() != 0.0 || position < 1.0 || position > MAX_VALUES as f64 {
            return Err(err(format!(
                "{what}: params is a list of values, one for each ?, but it has a key that isn't a position from 1 to {MAX_VALUES}"
            )));
        }
        let position = position as i64;
        let param = match value {
            Value::String(s) => Param::Text(s.to_string_lossy().to_string()),
            Value::Integer(n) => Param::Integer(n),
            Value::Number(n) if n.fract() == 0.0 && n.abs() <= MAX_SAFE => Param::Integer(n as i64),
            Value::Number(n) => Param::Real(n),
            Value::Boolean(b) => Param::Integer(i64::from(b)),
            other => {
                return Err(err(format!(
                    "{what}: params[{position}] is a {}, which SQL has no value for (a string, number or boolean)",
                    other.type_name()
                )));
            }
        };
        found.insert(position, param);
    }
    let last = found.keys().max().copied().unwrap_or(0);
    Ok((1..=last)
        .map(|i| found.remove(&i).unwrap_or(Param::Null))
        .collect())
}

/// A row as a script reads it: an integer past the safe range as a string of its digits.
fn row_table(lua: &mlua::Lua, row: Row) -> BTreeMap<String, Value> {
    row.into_iter()
        .map(|(name, cell)| {
            let value = match cell {
                Cell::Integer(n)
                    if n.unsigned_abs() <= netherforge_api::MAX_SAFE_INTEGER as u64 =>
                {
                    Value::Number(n as f64)
                }
                Cell::Integer(n) => lua
                    .create_string(n.to_string())
                    .map(Value::String)
                    .unwrap_or(Value::Nil),
                Cell::Real(n) => Value::Number(n),
                Cell::Text(s) => lua
                    .create_string(s)
                    .map(Value::String)
                    .unwrap_or(Value::Nil),
            };
            (name, value)
        })
        .collect()
}

/// The databases' side of the API: `nf.db` and `Database`.
#[derive(Clone)]
pub struct DatabaseContext {
    pub shared: Rc<Shared>,
    pub scripts: Scripts,
}

impl DatabaseContext {
    /// `nf.db(name)`: the calling package's own database, or a connection the owner opened to it.
    pub fn db(&self, namespace: String, name: Option<String>) -> ApiResult<handles::Database> {
        let Some(name) = name else {
            return Ok(handles::Database {
                namespace,
                connection: String::new(),
            });
        };
        if let Some(problem) = self
            .shared
            .databases
            .borrow()
            .config
            .problem(&name, &namespace)
        {
            return Err(err(format!("nf.db: {problem}")));
        }
        Ok(handles::Database {
            namespace,
            connection: name,
        })
    }

    fn statement(
        &self,
        this: &handles::Database,
        what: &str,
        sql: String,
        params: &Value,
    ) -> ApiResult<Statement> {
        if let Some(problem) = sql_problem(&sql) {
            return Err(err(format!("{what}: {problem}")));
        }
        if this.connection.is_empty()
            && let Some(problem) = sqlite_problem(&sql)
        {
            return Err(err(format!("{what}: {problem}")));
        }
        Ok(Statement {
            sql,
            values: values(params, what)?,
        })
    }

    /// Runs `work` on the handle's database's lane: its value at a tick, or why there's none.
    fn run<T: Send + 'static>(
        &self,
        this: &handles::Database,
        work: impl FnOnce(&mut Connection) -> Result<T, String> + Send + 'static,
    ) -> mpsc::Receiver<Result<T, String>> {
        let (send, receive) = mpsc::channel();
        if !this.connection.is_empty() {
            let _ = send.send(Err(format!(
                "the connection \"{}\" is a MySQL or PostgreSQL server's, which the Rust runtime doesn't reach yet",
                this.connection
            )));
            return receive;
        }
        let database = {
            let mut databases = self.shared.databases.borrow_mut();
            if let Some(why) = databases.unavailable(&this.namespace) {
                let _ = send.send(Err(why));
                return receive;
            }
            databases.database(&this.namespace)
        };
        database.submit(Box::new(move |c| {
            let _ = send.send(c.and_then(work));
        }));
        receive
    }
}

/// The lane's answer as the room's future, made a script's value on the main thread by `then`.
fn answer<T: 'static, U: 'static>(
    receive: mpsc::Receiver<Result<T, String>>,
    then: impl Fn(T) -> U + 'static,
) -> Pending<U> {
    let reply = crate::http::Reply(receive);
    Box::pin(async move { reply.await.map(then).map_err(ApiError) })
}

impl DatabaseApi for DatabaseContext {
    fn query(
        &self,
        this: &handles::Database,
        sql: String,
        params: Value,
    ) -> ApiResult<Pending<Vec<BTreeMap<String, Value>>>> {
        let statement = self.statement(this, "Database:query", sql, &params)?;
        let receive = self.run(this, move |c| statement.rows(c));
        let lua = self.scripts.host().lua().clone();
        Ok(answer(receive, move |rows: Vec<Row>| {
            rows.into_iter().map(|row| row_table(&lua, row)).collect()
        }))
    }

    fn execute(
        &self,
        this: &handles::Database,
        sql: String,
        params: Value,
    ) -> ApiResult<Pending<shapes::DatabaseResult>> {
        let statement = self.statement(this, "Database:execute", sql, &params)?;
        let receive = self.run(this, move |c| statement.execute(c));
        Ok(answer(receive, |result| result))
    }

    fn transaction(
        &self,
        this: &handles::Database,
        statements: Vec<shapes::DatabaseStatement>,
    ) -> ApiResult<Pending<Vec<shapes::DatabaseResult>>> {
        let checked = statements
            .into_iter()
            .enumerate()
            .map(|(i, s)| {
                self.statement(
                    this,
                    &format!("Database:transaction (statement {})", i + 1),
                    s.sql,
                    &s.params,
                )
            })
            .collect::<ApiResult<Vec<Statement>>>()?;
        let receive = self.run(this, move |c| {
            let tx = c.transaction().map_err(said)?;
            let mut results = Vec::new();
            for (i, statement) in checked.iter().enumerate() {
                match statement.execute(&tx) {
                    Ok(result) => results.push(result),
                    Err(why) => {
                        return Err(format!(
                            "statement {} failed, so nothing was changed: {why}",
                            i + 1
                        ));
                    }
                }
            }
            tx.commit().map_err(said)?;
            Ok(results)
        });
        Ok(answer(receive, |results| results))
    }
}

/// The packages' schemas as a session's service: migrations applied as it starts and as they're added.
pub struct DatabasesService {
    pub shared: Rc<Shared>,
    pub problems: RefCell<Vec<Problem>>,
}

impl Service for DatabasesService {
    fn name(&self) -> &'static str {
        "package databases"
    }

    fn reloads(&self) -> &'static [&'static str] {
        &["migration"]
    }

    fn start(&self, _room: &Room, project: &Define) {
        self.shared.databases.borrow_mut().failures.clear();
        for (namespace, migrations) in migrations_of(project) {
            apply(&self.shared, &namespace, &migrations);
        }
    }

    fn reload(&self, _room: &Room, _kind: &str, ids: &BTreeSet<String>, project: &Define) {
        let all = migrations_of(project);
        let home = project.project.namespace().to_owned();
        let namespaces: BTreeSet<String> = ids
            .iter()
            .map(|id| {
                netherforge_format::kinds::split_package(id)
                    .0
                    .map_or_else(|| home.clone(), str::to_owned)
            })
            .collect();
        for namespace in namespaces {
            apply(
                &self.shared,
                &namespace,
                all.get(&namespace).map_or(&[][..], Vec::as_slice),
            );
        }
    }

    fn problems(&self) -> Vec<Problem> {
        self.shared.databases.borrow().problems()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn one_statement_and_no_other_file() {
        assert_eq!(sql_problem("SELECT 1"), None);
        assert_eq!(sql_problem("SELECT ';' ; -- done"), None);
        assert_eq!(sql_problem(" ; "), Some("it is empty"));
        assert!(
            sql_problem("SELECT 1; DROP TABLE x")
                .unwrap()
                .contains("more than one")
        );
        assert_eq!(sql_problem("/* nothing */"), Some("it is empty"));
        assert!(
            sqlite_problem("attach 'x.db' as x")
                .unwrap()
                .starts_with("ATTACH isn't allowed")
        );
        assert!(sqlite_problem("PRAGMA main.temp_store_directory = '/tmp'").is_some());
        assert_eq!(sqlite_problem("PRAGMA user_version"), None);
        assert_eq!(sqlite_problem("SELECT 1"), None);
    }
}
