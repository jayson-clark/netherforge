//! The MySQL and PostgreSQL servers the owner names in `databases:` (Kotlin's `RemoteDatabases`, `RemoteDatabase` and
//! `OwnDatabase`): `nf.db("network")`, and a package's own database on such a server (`own-databases`).
//!
//! - **One pool each**, made the first time work asks for a connection, shared by every package granted it; at most
//!   `pool-size` connections, a statement waiting at most ten seconds for a free one. A server that's down doesn't
//!   fail the plugin: the first statement says so, and the next tries again. Work runs on a lane per package and
//!   connection (`database:<namespace>:<name>`), so one package's statements to one connection run in order.
//! - **What a script sees** is the SQLite handle's: `?` placeholders (PostgreSQL's `$1` are written for it), values
//!   bound as text the server reads as the column's type, and rows of integers, reals and strings (a boolean is 1 or
//!   0, a decimal a real, a date or time its ISO 8601 text; bytes are an error, as on SQLite).
//! - **Every statement has 30 seconds**: a script's query to a server that stopped answering must not hold a lane.
//! - **A package's own database** is a space of its own named from its namespace. PostgreSQL: a schema called the
//!   namespace, which every unit of work (a `db:query`, a `db:execute`, a whole `db:transaction`) picks for itself as
//!   one transaction (`set_config('search_path', ..., true)`, which is `SET LOCAL`), so it works behind a transaction
//!   pooler. MySQL: a database `<database>_<namespace>` beside the configured one, `USE`d while the work runs and put
//!   back after. Its migrations run once across every server sharing it, under a lock the database holds.

use std::collections::BTreeSet;
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use crate::databases::{Cell, ConnectionConfig, Migration, Param, Row, SslMode, Statement};
use netherforge_api::shapes;

/// How long a statement waits for a connection, a new one included.
const CONNECT: Duration = Duration::from_secs(10);
/// How long one statement may run.
const STATEMENT_SECONDS: u64 = 30;
/// How long a server waits for another to finish migrating before giving up.
const LOCK_SECONDS: u64 = 120;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Dialect {
    Mysql,
    Postgres,
}

impl Dialect {
    pub fn of(kind: &str) -> Dialect {
        if kind == "postgres" {
            Dialect::Postgres
        } else {
            Dialect::Mysql
        }
    }

    /// As migration folders name it.
    pub fn id(self) -> &'static str {
        match self {
            Dialect::Mysql => "mysql",
            Dialect::Postgres => "postgres",
        }
    }
}

enum Conn {
    My(Box<mysql::Conn>),
    Pg(Box<postgres::Client>),
}

#[derive(Default)]
struct PoolState {
    idle: Vec<Conn>,
    /// Connections made and not yet broken, idle or lent out.
    count: usize,
}

/// What the work on a connection is: what `Database`'s three calls ask.
pub enum Work {
    Query(Statement),
    Execute(Statement),
    Transaction(Vec<Statement>),
}

/// What it answered.
pub enum Answer {
    Rows(Vec<Row>),
    Result(shapes::DatabaseResult),
    Results(Vec<shapes::DatabaseResult>),
}

/// One named connection's pool.
pub struct Remote {
    pub name: String,
    pub config: ConnectionConfig,
    pub dialect: Dialect,
    state: Mutex<PoolState>,
    freed: Condvar,
    /// What went wrong reaching the server, for the server's log (a script hears only which connection): taken on the
    /// main thread.
    pub logs: Mutex<Vec<String>>,
    /// The own databases whose space this server has made.
    made: Mutex<BTreeSet<String>>,
}

/// A connection lent out, given back as it's dropped (unless it broke).
struct Lent<'r> {
    remote: &'r Remote,
    conn: Option<Conn>,
    broken: bool,
}

impl Drop for Lent<'_> {
    fn drop(&mut self) {
        let mut state = self.remote.state.lock().unwrap_or_else(|e| e.into_inner());
        match self.conn.take() {
            Some(conn) if !self.broken => state.idle.push(conn),
            _ => state.count -= 1,
        }
        self.remote.freed.notify_one();
    }
}

impl Remote {
    pub fn new(name: &str, config: ConnectionConfig) -> Remote {
        Remote {
            name: name.to_owned(),
            dialect: Dialect::of(&config.kind),
            config,
            state: Mutex::default(),
            freed: Condvar::new(),
            logs: Mutex::default(),
            made: Mutex::default(),
        }
    }

    fn unreachable(&self, why: impl std::fmt::Display) -> String {
        self.logs
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .push(format!(
                "NetherForge couldn't reach the database connection \"{}\" ({:?}): {why}",
                self.name, self.config
            ));
        format!(
            "the database connection \"{}\" couldn't be reached (the server's log says why)",
            self.name
        )
    }

    /// A connection: an idle one, a new one while there's room, or the next one given back.
    fn lend(&self) -> Result<Lent<'_>, String> {
        let deadline = Instant::now() + CONNECT;
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        loop {
            if let Some(conn) = state.idle.pop() {
                return Ok(Lent {
                    remote: self,
                    conn: Some(conn),
                    broken: false,
                });
            }
            if state.count < self.config.pool_size {
                state.count += 1;
                drop(state);
                return match self.connect() {
                    Ok(conn) => Ok(Lent {
                        remote: self,
                        conn: Some(conn),
                        broken: false,
                    }),
                    Err(why) => {
                        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
                        state.count -= 1;
                        self.freed.notify_one();
                        Err(self.unreachable(why))
                    }
                };
            }
            let left = deadline.saturating_duration_since(Instant::now());
            if left.is_zero() {
                return Err(format!(
                    "every connection to \"{}\" was busy for {} seconds",
                    self.name,
                    CONNECT.as_secs()
                ));
            }
            state = self
                .freed
                .wait_timeout(state, left)
                .unwrap_or_else(|e| e.into_inner())
                .0;
        }
    }

    fn connect(&self) -> Result<Conn, String> {
        let c = &self.config;
        match self.dialect {
            Dialect::Mysql => {
                let opts = |tls: bool| {
                    let mut builder = mysql::OptsBuilder::new()
                        .ip_or_hostname(Some(c.host.trim_matches(['[', ']'])))
                        .tcp_port(c.port)
                        .db_name(Some(&c.database))
                        .user(Some(&c.username))
                        .pass(Some(&c.password))
                        .prefer_socket(false)
                        .tcp_connect_timeout(Some(CONNECT))
                        .read_timeout(Some(Duration::from_secs(STATEMENT_SECONDS + 5)))
                        .write_timeout(Some(Duration::from_secs(STATEMENT_SECONDS + 5)))
                        .init(vec![format!(
                            "SET SESSION max_execution_time = {}",
                            STATEMENT_SECONDS * 1000
                        )]);
                    if tls {
                        builder = builder.ssl_opts(Some(mysql::SslOpts::default()));
                    }
                    builder
                };
                let connected = match c.ssl_mode {
                    SslMode::Disable => mysql::Conn::new(opts(false)),
                    SslMode::Require => mysql::Conn::new(opts(true)),
                    // Encrypted when the server can, plain when it can't.
                    SslMode::Prefer => {
                        mysql::Conn::new(opts(true)).or_else(|_| mysql::Conn::new(opts(false)))
                    }
                };
                connected
                    .map(|conn| Conn::My(Box::new(conn)))
                    .map_err(|e| e.to_string())
            }
            Dialect::Postgres => {
                let mut config = postgres::Config::new();
                config
                    .host(c.host.trim_matches(['[', ']']))
                    .port(c.port)
                    .dbname(&c.database)
                    .user(&c.username)
                    .password(&c.password)
                    .connect_timeout(CONNECT)
                    .application_name("NetherForge")
                    .ssl_mode(match c.ssl_mode {
                        SslMode::Disable => postgres::config::SslMode::Disable,
                        SslMode::Prefer => postgres::config::SslMode::Prefer,
                        SslMode::Require => postgres::config::SslMode::Require,
                    });
                let client = if c.ssl_mode == SslMode::Disable {
                    config.connect(postgres::NoTls)
                } else {
                    let tls = tls_config()?;
                    config.connect(postgres_rustls::MakeTlsConnector::new(
                        tokio_rustls::TlsConnector::from(tls),
                    ))
                };
                client
                    .map(|c| Conn::Pg(Box::new(c)))
                    .map_err(|e| e.to_string())
            }
        }
    }

    /// Runs `work` on a connection, in `own`'s space when it's a package's own database.
    pub fn run(&self, own: Option<&str>, work: &Work) -> Result<Answer, String> {
        let mut lent = self.lend()?;
        let answer = match lent.conn.as_mut().expect("lent") {
            Conn::My(conn) => self.run_mysql(conn, own, work),
            Conn::Pg(client) => self.run_postgres(client, own, work),
        };
        // A connection the server closed is made again next time.
        if let Err(why) = &answer
            && let Some(Conn::Pg(client)) = &lent.conn
            && client.is_closed()
        {
            lent.broken = true;
            return Err(why.clone());
        }
        if answer.is_err()
            && let Some(Conn::My(conn)) = lent.conn.as_mut()
            && conn.ping().is_err()
        {
            lent.broken = true;
        }
        answer
    }

    /// The space of `namespace`'s own database: the PostgreSQL schema, or the MySQL database.
    pub fn space(&self, namespace: &str) -> String {
        match self.dialect {
            Dialect::Postgres => namespace.to_owned(),
            Dialect::Mysql => format!("{}_{namespace}", self.config.database),
        }
    }

    /// Where `namespace`'s own database is, as the log and `/nf requires` say it.
    pub fn describe(&self, namespace: &str) -> String {
        format!(
            "{} connection \"{}\" ({} {})",
            self.dialect.id(),
            self.name,
            if self.dialect == Dialect::Postgres {
                "schema"
            } else {
                "database"
            },
            self.space(namespace)
        )
    }

    fn quoted(&self, name: &str) -> String {
        match self.dialect {
            Dialect::Postgres => format!("\"{name}\""),
            Dialect::Mysql => format!("`{name}`"),
        }
    }

    fn was_made(&self, namespace: &str) -> bool {
        self.made
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .contains(namespace)
    }

    fn mark_made(&self, namespace: &str) {
        self.made
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(namespace.to_owned());
    }

    // ---- MySQL ---------------------------------------------------------------------

    fn run_mysql(
        &self,
        conn: &mut mysql::Conn,
        own: Option<&str>,
        work: &Work,
    ) -> Result<Answer, String> {
        use mysql::prelude::Queryable as _;
        let Some(namespace) = own else {
            return mysql_work(conn, work);
        };
        let space = self.space(namespace);
        if !self.was_made(namespace) {
            conn.query_drop(format!(
                "CREATE DATABASE IF NOT EXISTS {}",
                self.quoted(&space)
            ))
            .map_err(|e| e.to_string())?;
            self.mark_made(namespace);
        }
        conn.select_db(&space).map_err(|e| e.to_string())?;
        let answer = mysql_work(conn, work);
        // The pool is the connection's, shared with `nf.db("<name>")`: its catalog goes back.
        if conn.select_db(&self.config.database).is_err() {
            return Err("the connection couldn't be put back after the work".into());
        }
        answer
    }

    // ---- PostgreSQL ----------------------------------------------------------------

    fn run_postgres(
        &self,
        client: &mut postgres::Client,
        own: Option<&str>,
        work: &Work,
    ) -> Result<Answer, String> {
        if let Some(namespace) = own
            && !self.was_made(namespace)
        {
            let mut tx = client.transaction().map_err(pg_said)?;
            self.lock_postgres(&mut tx, namespace)?;
            tx.batch_execute(&format!(
                "CREATE SCHEMA IF NOT EXISTS {}",
                self.quoted(namespace)
            ))
            .map_err(pg_said)?;
            tx.commit().map_err(pg_said)?;
            self.mark_made(namespace);
        }
        // One transaction for the whole unit of work: nothing it needs is a session's, so a pooler may run each on
        // another backend.
        let mut tx = client.transaction().map_err(pg_said)?;
        tx.batch_execute(&format!(
            "SET LOCAL statement_timeout = {}",
            STATEMENT_SECONDS * 1000
        ))
        .map_err(pg_said)?;
        if let Some(namespace) = own {
            tx.query(
                "SELECT set_config('search_path', $1, true)",
                &[&self.quoted(namespace)],
            )
            .map_err(pg_said)?;
        }
        let answer = postgres_work(&mut tx, work)?;
        tx.commit().map_err(pg_said)?;
        Ok(answer)
    }

    /// The database's lock for `namespace`'s migrations (and for making its schema), held by `tx` until it ends.
    /// Tried, not waited on: a server that hangs mid-migration must not hold this one's start for ever.
    fn lock_postgres(&self, tx: &mut postgres::Transaction, namespace: &str) -> Result<(), String> {
        let key = self.lock_key(namespace);
        let deadline = Instant::now() + Duration::from_secs(LOCK_SECONDS);
        loop {
            let row = tx
                .query_one("SELECT pg_try_advisory_xact_lock($1)", &[&key])
                .map_err(pg_said)?;
            if row.get::<_, bool>(0) {
                return Ok(());
            }
            if Instant::now() > deadline {
                return Err(format!(
                    "another server held the migration lock for {namespace} for {LOCK_SECONDS} seconds"
                ));
            }
            std::thread::sleep(Duration::from_millis(100));
        }
    }

    fn digest(&self, namespace: &str) -> [u8; 32] {
        use sha2::Digest as _;
        sha2::Sha256::digest(format!(
            "netherforge migrations {} {namespace}",
            self.config.database
        ))
        .into()
    }

    /// PostgreSQL's advisory lock key: the first eight bytes of a hash of the database and namespace.
    fn lock_key(&self, namespace: &str) -> i64 {
        let d = self.digest(namespace);
        i64::from_be_bytes([d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7]])
    }

    /// MySQL's lock name, server-wide and at most 64 characters: the hash.
    fn lock_name(&self, namespace: &str) -> String {
        let d = self.digest(namespace);
        format!(
            "netherforge.{}",
            d[..16]
                .iter()
                .map(|b| format!("{b:02x}"))
                .collect::<String>()
        )
    }

    // ---- migrations ------------------------------------------------------------------

    /// Brings `namespace`'s own database up to date with `migrations` (this dialect's versions), each once across
    /// every server sharing the connection: the failed step's file name and why, the ones before it kept.
    pub fn migrate(&self, namespace: &str, migrations: &[Migration]) -> Result<(), MigrateError> {
        let mut lent = self.lend().map_err(MigrateError::Open)?;
        let mut sorted = migrations.to_vec();
        sorted.sort_by_key(|m| m.version);
        match lent.conn.as_mut().expect("lent") {
            Conn::Pg(client) => self.migrate_postgres(client, namespace, &sorted),
            Conn::My(conn) => self.migrate_mysql(conn, namespace, &sorted),
        }
    }

    fn migrate_postgres(
        &self,
        client: &mut postgres::Client,
        namespace: &str,
        migrations: &[Migration],
    ) -> Result<(), MigrateError> {
        let open = |e: postgres::Error| MigrateError::Open(pg_said(e));
        // The schema and its table, made under the lock.
        let missing: Vec<Migration> = {
            let mut tx = client.transaction().map_err(open)?;
            self.lock_postgres(&mut tx, namespace)
                .map_err(MigrateError::Open)?;
            tx.batch_execute(&format!(
                "CREATE SCHEMA IF NOT EXISTS {0}; SELECT set_config('search_path', '{0}', true); {1}",
                self.quoted(namespace).replace('\'', "''"),
                MIGRATIONS_TABLE
            ))
            .map_err(open)?;
            let done = pg_applied(&mut tx).map_err(open)?;
            ahead(&done, migrations)?;
            tx.commit().map_err(open)?;
            migrations
                .iter()
                .filter(|m| !done.contains(&m.version))
                .cloned()
                .collect()
        };
        self.mark_made(namespace);
        for migration in missing {
            // Under the lock, checking again: another server may have applied it in between.
            let mut tx = client.transaction().map_err(open)?;
            self.lock_postgres(&mut tx, namespace)
                .map_err(MigrateError::Open)?;
            tx.query(
                "SELECT set_config('search_path', $1, true)",
                &[&self.quoted(namespace)],
            )
            .map_err(open)?;
            let done = pg_applied(&mut tx).map_err(open)?;
            ahead(&done, migrations)?;
            if done.contains(&migration.version) {
                continue;
            }
            let failed =
                |e: postgres::Error| MigrateError::Failed(migration.file_name(), pg_said(e));
            tx.batch_execute(&migration.sql).map_err(failed)?;
            tx.execute(
                "INSERT INTO nf_migrations (version, name, applied) VALUES ($1, $2, $3)",
                &[&(migration.version as i32), &migration.name, &now()],
            )
            .map_err(failed)?;
            tx.commit().map_err(failed)?;
        }
        Ok(())
    }

    fn migrate_mysql(
        &self,
        conn: &mut mysql::Conn,
        namespace: &str,
        migrations: &[Migration],
    ) -> Result<(), MigrateError> {
        use mysql::prelude::Queryable as _;
        let open = |e: mysql::Error| MigrateError::Open(e.to_string());
        let lock = self.lock_name(namespace);
        let got: Option<i64> = conn
            .exec_first("SELECT GET_LOCK(?, ?)", (&lock, LOCK_SECONDS))
            .map_err(open)?;
        if got != Some(1) {
            return Err(MigrateError::Open(format!(
                "another server held the migration lock for {namespace} for {LOCK_SECONDS} seconds"
            )));
        }
        let space = self.space(namespace);
        let result = (|| {
            conn.query_drop(format!(
                "CREATE DATABASE IF NOT EXISTS {}",
                self.quoted(&space)
            ))
            .map_err(open)?;
            conn.select_db(&space).map_err(open)?;
            self.mark_made(namespace);
            conn.query_drop(MIGRATIONS_TABLE).map_err(open)?;
            let done: BTreeSet<i64> = conn
                .query::<i64, _>("SELECT version FROM nf_migrations")
                .map_err(open)?
                .into_iter()
                .collect();
            ahead(&done, migrations)?;
            for migration in migrations.iter().filter(|m| !done.contains(&m.version)) {
                let failed =
                    |e: mysql::Error| MigrateError::Failed(migration.file_name(), e.to_string());
                // MySQL commits each DDL statement as it runs: a failed step can leave part of itself behind.
                conn.query_drop("START TRANSACTION").map_err(failed)?;
                let applied = (|| {
                    for sql in crate::databases::statements(&migration.sql) {
                        conn.query_drop(sql).map_err(failed)?;
                    }
                    conn.exec_drop(
                        "INSERT INTO nf_migrations (version, name, applied) VALUES (?, ?, ?)",
                        (migration.version, &migration.name, now()),
                    )
                    .map_err(failed)?;
                    conn.query_drop("COMMIT").map_err(failed)
                })();
                if applied.is_err() {
                    let _ = conn.query_drop("ROLLBACK");
                    return applied;
                }
            }
            Ok(())
        })();
        let _ = conn.select_db(&self.config.database);
        let _ = conn.exec_drop("SELECT RELEASE_LOCK(?)", (&lock,));
        result
    }
}

const MIGRATIONS_TABLE: &str = "CREATE TABLE IF NOT EXISTS nf_migrations (version INTEGER PRIMARY KEY, name VARCHAR(255) NOT NULL, applied VARCHAR(64) NOT NULL)";

/// Why an own database's migrations didn't all apply.
#[derive(Debug)]
pub enum MigrateError {
    /// The database couldn't be reached or locked.
    Open(String),
    /// A step failed: its file name and why.
    Failed(String, String),
    /// Steps applied there that this server's copy doesn't have.
    Ahead(Vec<i64>),
}

fn ahead(done: &BTreeSet<i64>, migrations: &[Migration]) -> Result<(), MigrateError> {
    let known: BTreeSet<i64> = migrations.iter().map(|m| m.version).collect();
    let ahead: Vec<i64> = done.difference(&known).copied().collect();
    if ahead.is_empty() {
        Ok(())
    } else {
        Err(MigrateError::Ahead(ahead))
    }
}

fn pg_applied(tx: &mut postgres::Transaction) -> Result<BTreeSet<i64>, postgres::Error> {
    Ok(tx
        .query("SELECT version FROM nf_migrations", &[])?
        .iter()
        .map(|r| i64::from(r.get::<_, i32>(0)))
        .collect())
}

fn now() -> String {
    chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Millis, true)
}

fn tls_config() -> Result<Arc<rustls::ClientConfig>, String> {
    use rustls_platform_verifier::BuilderVerifierExt as _;
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|e| e.to_string())?
        .with_platform_verifier()
        .map_err(|e| e.to_string())
        .map(|b| Arc::new(b.with_no_client_auth()))
}

// ---- MySQL's statements -------------------------------------------------------------

static INSERT: std::sync::LazyLock<regex::Regex> =
    std::sync::LazyLock::new(|| regex::Regex::new(r"(?i)^\s*(?:insert|replace)\b").unwrap());

fn mysql_params(values: &[Param]) -> mysql::Params {
    if values.is_empty() {
        return mysql::Params::Empty;
    }
    mysql::Params::Positional(
        values
            .iter()
            .map(|v| match v {
                Param::Null => mysql::Value::NULL,
                Param::Integer(n) => mysql::Value::Int(*n),
                Param::Real(n) => mysql::Value::Double(*n),
                Param::Text(s) => mysql::Value::Bytes(s.clone().into_bytes()),
            })
            .collect(),
    )
}

fn mysql_rows(conn: &mut mysql::Conn, statement: &Statement) -> Result<Vec<Row>, String> {
    use mysql::consts::ColumnType as T;
    use mysql::prelude::Queryable as _;
    let mut result = conn
        .exec_iter(&statement.sql, mysql_params(&statement.values))
        .map_err(|e| e.to_string())?;
    let columns: Vec<(String, T, bool)> = result
        .columns()
        .as_ref()
        .iter()
        .map(|c| {
            (
                c.name_str().into_owned(),
                c.column_type(),
                c.character_set() == 63,
            )
        })
        .collect();
    let mut out = Vec::new();
    for row in result.by_ref() {
        let row = row.map_err(|e| e.to_string())?;
        let mut cells = Vec::new();
        for (i, (name, kind, binary)) in columns.iter().enumerate() {
            let cell = match row.as_ref(i) {
                None | Some(mysql::Value::NULL) => continue,
                Some(mysql::Value::Int(n)) => Cell::Integer(*n),
                Some(mysql::Value::UInt(n)) => match i64::try_from(*n) {
                    Ok(n) => Cell::Integer(n),
                    Err(_) => Cell::Text(n.to_string()),
                },
                Some(mysql::Value::Float(n)) => Cell::Real(f64::from(*n)),
                Some(mysql::Value::Double(n)) => Cell::Real(*n),
                Some(mysql::Value::Bytes(bytes)) => match kind {
                    T::MYSQL_TYPE_DECIMAL | T::MYSQL_TYPE_NEWDECIMAL => {
                        let text = String::from_utf8_lossy(bytes);
                        text.parse()
                            .map_or(Cell::Text(text.into_owned()), Cell::Real)
                    }
                    T::MYSQL_TYPE_BLOB
                    | T::MYSQL_TYPE_TINY_BLOB
                    | T::MYSQL_TYPE_MEDIUM_BLOB
                    | T::MYSQL_TYPE_LONG_BLOB
                    | T::MYSQL_TYPE_STRING
                    | T::MYSQL_TYPE_VAR_STRING
                    | T::MYSQL_TYPE_VARCHAR
                    | T::MYSQL_TYPE_BIT
                        if *binary =>
                    {
                        return Err(format!(
                            "column \"{name}\" holds bytes, which a script can't read: select hex({name}) instead"
                        ));
                    }
                    _ => Cell::Text(String::from_utf8_lossy(bytes).into_owned()),
                },
                Some(mysql::Value::Date(y, mo, d, h, mi, s, us)) => {
                    if matches!(kind, T::MYSQL_TYPE_DATE | T::MYSQL_TYPE_NEWDATE) {
                        Cell::Text(format!("{y:04}-{mo:02}-{d:02}"))
                    } else if *us == 0 {
                        Cell::Text(format!("{y:04}-{mo:02}-{d:02}T{h:02}:{mi:02}:{s:02}"))
                    } else {
                        Cell::Text(format!(
                            "{y:04}-{mo:02}-{d:02}T{h:02}:{mi:02}:{s:02}.{us:06}"
                        ))
                    }
                }
                Some(mysql::Value::Time(negative, days, h, mi, s, us)) => {
                    let hours = u32::from(*h) + days * 24;
                    let sign = if *negative { "-" } else { "" };
                    if *us == 0 {
                        Cell::Text(format!("{sign}{hours:02}:{mi:02}:{s:02}"))
                    } else {
                        Cell::Text(format!("{sign}{hours:02}:{mi:02}:{s:02}.{us:06}"))
                    }
                }
            };
            cells.push((name.clone(), cell));
        }
        out.push(cells);
    }
    Ok(out)
}

fn mysql_execute(
    conn: &mut mysql::Conn,
    statement: &Statement,
) -> Result<shapes::DatabaseResult, String> {
    use mysql::prelude::Queryable as _;
    conn.exec_drop(&statement.sql, mysql_params(&statement.values))
        .map_err(|e| e.to_string())?;
    // A statement that inserted nothing may leave an earlier one's key behind, so only an insert's is read.
    let last = conn.last_insert_id();
    Ok(shapes::DatabaseResult {
        changes: conn.affected_rows() as i64,
        last_insert_id: (INSERT.is_match(&statement.sql) && last != 0).then_some(last as i64),
    })
}

fn mysql_work(conn: &mut mysql::Conn, work: &Work) -> Result<Answer, String> {
    use mysql::prelude::Queryable as _;
    match work {
        Work::Query(s) => mysql_rows(conn, s).map(Answer::Rows),
        Work::Execute(s) => mysql_execute(conn, s).map(Answer::Result),
        Work::Transaction(statements) => {
            conn.query_drop("START TRANSACTION")
                .map_err(|e| e.to_string())?;
            let mut results = Vec::new();
            for (i, s) in statements.iter().enumerate() {
                match mysql_execute(conn, s) {
                    Ok(r) => results.push(r),
                    Err(why) => {
                        let _ = conn.query_drop("ROLLBACK");
                        return Err(format!(
                            "statement {} failed, so nothing was changed: {why}",
                            i + 1
                        ));
                    }
                }
            }
            conn.query_drop("COMMIT").map_err(|e| e.to_string())?;
            Ok(Answer::Results(results))
        }
    }
}

// ---- PostgreSQL's statements ----------------------------------------------------------

fn pg_said(e: postgres::Error) -> String {
    match e.as_db_error() {
        Some(db) => db.message().to_owned(),
        None => e.to_string(),
    }
}

/// A value bound as text, which the server reads as whatever type the statement wants there.
impl postgres::types::ToSql for Param {
    fn to_sql(
        &self,
        _: &postgres::types::Type,
        out: &mut bytes::BytesMut,
    ) -> Result<postgres::types::IsNull, Box<dyn std::error::Error + Sync + Send>> {
        let text = match self {
            Param::Null => return Ok(postgres::types::IsNull::Yes),
            Param::Integer(n) => n.to_string(),
            Param::Real(n) => format!("{n:?}"),
            Param::Text(s) => s.clone(),
        };
        out.extend_from_slice(text.as_bytes());
        Ok(postgres::types::IsNull::No)
    }

    fn accepts(_: &postgres::types::Type) -> bool {
        true
    }

    fn encode_format(&self, _: &postgres::types::Type) -> postgres::types::Format {
        postgres::types::Format::Text
    }

    postgres::types::to_sql_checked!();
}

/// A column's value, whatever its type.
enum Decoded {
    Cell(Cell),
    Bytes,
    Unread(String),
}

impl<'a> postgres::types::FromSql<'a> for Decoded {
    fn from_sql(
        ty: &postgres::types::Type,
        raw: &'a [u8],
    ) -> Result<Decoded, Box<dyn std::error::Error + Sync + Send>> {
        use postgres::types::Type;
        Ok(Decoded::Cell(match *ty {
            Type::BOOL => Cell::Integer(i64::from(bool::from_sql(ty, raw)?)),
            Type::INT2 => Cell::Integer(i64::from(i16::from_sql(ty, raw)?)),
            Type::INT4 => Cell::Integer(i64::from(i32::from_sql(ty, raw)?)),
            Type::INT8 => Cell::Integer(i64::from_sql(ty, raw)?),
            Type::OID => Cell::Integer(i64::from(u32::from_sql(ty, raw)?)),
            Type::FLOAT4 => Cell::Real(f64::from(f32::from_sql(ty, raw)?)),
            Type::FLOAT8 => Cell::Real(f64::from_sql(ty, raw)?),
            Type::NUMERIC => Cell::Real(numeric(raw)?),
            Type::TEXT
            | Type::VARCHAR
            | Type::BPCHAR
            | Type::NAME
            | Type::UNKNOWN
            | Type::JSON
            | Type::XML => Cell::Text(String::from_utf8_lossy(raw).into_owned()),
            // JSONB's binary form is a version byte, then its text.
            Type::JSONB => {
                Cell::Text(String::from_utf8_lossy(raw.get(1..).unwrap_or_default()).into_owned())
            }
            Type::UUID if raw.len() == 16 => {
                let hex: String = raw.iter().map(|b| format!("{b:02x}")).collect();
                Cell::Text(format!(
                    "{}-{}-{}-{}-{}",
                    &hex[0..8],
                    &hex[8..12],
                    &hex[12..16],
                    &hex[16..20],
                    &hex[20..]
                ))
            }
            Type::DATE => Cell::Text(date(i32::from_sql(ty, raw)?)),
            Type::TIMESTAMP => Cell::Text(timestamp(i64::from_sql(ty, raw)?, false)),
            Type::TIMESTAMPTZ => Cell::Text(timestamp(i64::from_sql(ty, raw)?, true)),
            Type::TIME => Cell::Text(time(i64::from_sql(ty, raw)?)),
            Type::BYTEA => return Ok(Decoded::Bytes),
            _ if matches!(ty.kind(), postgres::types::Kind::Enum(_)) => {
                Cell::Text(String::from_utf8_lossy(raw).into_owned())
            }
            _ => return Ok(Decoded::Unread(ty.name().to_owned())),
        }))
    }

    fn accepts(_: &postgres::types::Type) -> bool {
        true
    }
}

/// PostgreSQL's binary NUMERIC: its digits in base 10000, from `weight`.
fn numeric(raw: &[u8]) -> Result<f64, Box<dyn std::error::Error + Sync + Send>> {
    let word = |i: usize| -> Result<i16, Box<dyn std::error::Error + Sync + Send>> {
        raw.get(i..i + 2)
            .map(|b| i16::from_be_bytes([b[0], b[1]]))
            .ok_or_else(|| "a numeric cut short".into())
    };
    let (count, weight, sign) = (word(0)?, word(2)?, word(4)? as u16);
    if sign == 0xC000 {
        return Ok(f64::NAN);
    }
    let mut value = 0.0;
    for i in 0..count.max(0) as usize {
        let digit = f64::from(word(8 + 2 * i)?);
        value += digit * 10_000f64.powi(i32::from(weight) - i as i32);
    }
    Ok(if sign == 0x4000 { -value } else { value })
}

fn epoch() -> chrono::NaiveDateTime {
    chrono::NaiveDate::from_ymd_opt(2000, 1, 1)
        .expect("a date")
        .and_hms_opt(0, 0, 0)
        .expect("a time")
}

fn date(days: i32) -> String {
    (epoch() + chrono::Duration::days(i64::from(days)))
        .format("%Y-%m-%d")
        .to_string()
}

fn timestamp(micros: i64, zoned: bool) -> String {
    let at = epoch() + chrono::Duration::microseconds(micros);
    let text = if at.and_utc().timestamp_subsec_micros() == 0 {
        at.format("%Y-%m-%dT%H:%M:%S").to_string()
    } else {
        at.format("%Y-%m-%dT%H:%M:%S%.6f").to_string()
    };
    if zoned { format!("{text}Z") } else { text }
}

fn time(micros: i64) -> String {
    let (seconds, micros) = (micros / 1_000_000, micros % 1_000_000);
    let text = format!(
        "{:02}:{:02}:{:02}",
        seconds / 3600,
        seconds / 60 % 60,
        seconds % 60
    );
    if micros == 0 {
        text
    } else {
        format!("{text}.{micros:06}")
    }
}

/// `sql` with each `?` as PostgreSQL's `$1`, `$2`…, outside strings, quoted names, dollar quotes and comments; `??` is
/// a `?` (a JSON operator).
pub fn postgres_placeholders(sql: &str) -> String {
    let chars: Vec<char> = sql.chars().collect();
    let mut out = String::with_capacity(sql.len() + 8);
    let (mut i, mut n) = (0, 0);
    while i < chars.len() {
        let c = chars[i];
        match c {
            '\'' | '"' => {
                let end = (i + 1..chars.len())
                    .find(|&j| chars[j] == c)
                    .map_or(chars.len(), |j| j + 1);
                out.extend(&chars[i..end]);
                i = end;
            }
            '-' if chars.get(i + 1) == Some(&'-') => {
                let end = (i..chars.len())
                    .find(|&j| chars[j] == '\n')
                    .unwrap_or(chars.len());
                out.extend(&chars[i..end]);
                i = end;
            }
            '/' if chars.get(i + 1) == Some(&'*') => {
                let end = (i + 2..chars.len().saturating_sub(1))
                    .find(|&j| chars[j] == '*' && chars[j + 1] == '/')
                    .map_or(chars.len(), |j| j + 2);
                out.extend(&chars[i..end]);
                i = end;
            }
            '$' => {
                // A dollar quote: `$tag$ ... $tag$`.
                let tag_end = (i + 1..chars.len())
                    .find(|&j| !(chars[j].is_alphanumeric() || chars[j] == '_'));
                match tag_end {
                    Some(j)
                        if chars[j] == '$'
                            && !chars[i + 1..j].first().is_some_and(char::is_ascii_digit) =>
                    {
                        let tag = &chars[i..=j];
                        let close = (j + 1..=chars.len().saturating_sub(tag.len()))
                            .find(|&k| &chars[k..k + tag.len()] == tag)
                            .map_or(chars.len(), |k| k + tag.len());
                        out.extend(&chars[i..close]);
                        i = close;
                    }
                    _ => {
                        out.push(c);
                        i += 1;
                    }
                }
            }
            '?' if chars.get(i + 1) == Some(&'?') => {
                out.push('?');
                i += 2;
            }
            '?' => {
                n += 1;
                out.push_str(&format!("${n}"));
                i += 1;
            }
            _ => {
                out.push(c);
                i += 1;
            }
        }
    }
    out
}

fn pg_params(values: &[Param]) -> Vec<&(dyn postgres::types::ToSql + Sync)> {
    values
        .iter()
        .map(|v| v as &(dyn postgres::types::ToSql + Sync))
        .collect()
}

fn pg_rows(tx: &mut postgres::Transaction, statement: &Statement) -> Result<Vec<Row>, String> {
    let sql = postgres_placeholders(&statement.sql);
    let prepared = tx.prepare(&sql).map_err(pg_said)?;
    let wanted = prepared.params().len();
    let values = padded(&statement.values, wanted)?;
    let rows = tx.query(&prepared, &pg_params(&values)).map_err(pg_said)?;
    let mut out = Vec::new();
    for row in rows {
        let mut cells = Vec::new();
        for (i, column) in row.columns().iter().enumerate() {
            let name = column.name();
            match row.try_get::<_, Option<Decoded>>(i).map_err(pg_said)? {
                None => {}
                Some(Decoded::Cell(cell)) => cells.push((name.to_owned(), cell)),
                Some(Decoded::Bytes) => {
                    return Err(format!(
                        "column \"{name}\" holds bytes, which a script can't read: select encode({name}, 'hex') instead"
                    ));
                }
                Some(Decoded::Unread(ty)) => {
                    return Err(format!(
                        "column \"{name}\" is a {ty}, which NetherForge doesn't read: select {name}::text instead"
                    ));
                }
            }
        }
        out.push(cells);
    }
    Ok(out)
}

/// The statement's values, NULL for those not given (a Lua list can't say its last values are nil).
fn padded(values: &[Param], wanted: usize) -> Result<Vec<Param>, String> {
    if values.len() > wanted {
        return Err(format!(
            "the statement has {wanted} ? but {} values were given",
            values.len()
        ));
    }
    let mut out = values.to_vec();
    out.resize(wanted, Param::Null);
    Ok(out)
}

fn pg_execute(
    tx: &mut postgres::Transaction,
    statement: &Statement,
) -> Result<shapes::DatabaseResult, String> {
    let sql = postgres_placeholders(&statement.sql);
    let prepared = tx.prepare(&sql).map_err(pg_said)?;
    let values = padded(&statement.values, prepared.params().len())?;
    let changes = tx
        .execute(&prepared, &pg_params(&values))
        .map_err(pg_said)?;
    // PostgreSQL has no generated key: its `RETURNING` is a query's rows.
    Ok(shapes::DatabaseResult {
        changes: changes as i64,
        last_insert_id: None,
    })
}

fn postgres_work(tx: &mut postgres::Transaction, work: &Work) -> Result<Answer, String> {
    match work {
        Work::Query(s) => pg_rows(tx, s).map(Answer::Rows),
        Work::Execute(s) => pg_execute(tx, s).map(Answer::Result),
        Work::Transaction(statements) => {
            let mut results = Vec::new();
            for (i, s) in statements.iter().enumerate() {
                match pg_execute(tx, s) {
                    Ok(r) => results.push(r),
                    Err(why) => {
                        return Err(format!(
                            "statement {} failed, so nothing was changed: {why}",
                            i + 1
                        ));
                    }
                }
            }
            Ok(Answer::Results(results))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::postgres_placeholders;

    #[test]
    fn placeholders_are_numbered_outside_strings_and_comments() {
        assert_eq!(
            postgres_placeholders("SELECT ?, '?', \"a?\", ? -- ?\n/* ? */ $$ ? $$ $t$?$t$ ?? ?"),
            "SELECT $1, '?', \"a?\", $2 -- ?\n/* ? */ $$ ? $$ $t$?$t$ ? $3"
        );
    }
}
