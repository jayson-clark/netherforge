//! The server's metrics in Prometheus's text format at `/metrics` (Kotlin's `ServerMetrics`, `metrics:` in
//! `config.yml`): how it keeps up (TPS, the tick's time, NetherForge's share of it), players, entities, centities and
//! chunks, what each package's scripts cost and hold, what each package sends and hears on the network, its state and
//! how long it took to be ready. Gathered on the main thread once a second (all of it counted, nothing listed) and
//! served from that, on a thread of its own.

use std::cell::Cell;
use std::collections::BTreeMap;
use std::collections::HashSet;
use std::fmt::Write as _;
use std::io::{Read, Write};
use std::net::TcpListener;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use netherforge_platform::Platform;

use crate::config::MetricsConfig;
use crate::network::protocol::Lifecycle;
use crate::session::Session;

/// How often the metrics are gathered, in ticks.
const GATHER_TICKS: u64 = 20;

/// Metrics in Prometheus's text format: each metric's help and type once, then its samples with their labels.
#[derive(Default)]
pub struct Prometheus {
    out: String,
    declared: HashSet<String>,
}

/// A sample's value as Kotlin's `Prometheus` writes it: a whole count as itself, a measure to six places.
pub enum Value {
    Count(i64),
    Measure(f64),
}

impl Prometheus {
    /// A gauge (a value now) or a counter (only goes up), `name` in `netherforge_*`, with `labels`.
    pub fn sample(
        &mut self,
        name: &str,
        help: &str,
        value: Value,
        labels: &[(&str, &str)],
        counter: bool,
    ) {
        if self.declared.insert(name.to_owned()) {
            let _ = writeln!(self.out, "# HELP {name} {help}");
            let _ = writeln!(
                self.out,
                "# TYPE {name} {}",
                if counter { "counter" } else { "gauge" }
            );
        }
        self.out.push_str(name);
        if !labels.is_empty() {
            let each: Vec<String> = labels
                .iter()
                .map(|(key, label)| {
                    let escaped = label
                        .replace('\\', "\\\\")
                        .replace('"', "\\\"")
                        .replace('\n', "\\n");
                    format!("{key}=\"{escaped}\"")
                })
                .collect();
            let _ = write!(self.out, "{{{}}}", each.join(","));
        }
        let _ = match value {
            Value::Count(n) => writeln!(self.out, " {n}"),
            Value::Measure(x) => writeln!(self.out, " {x:.6}"),
        };
    }

    pub fn text(self) -> String {
        self.out
    }
}

/// The endpoint and what it serves.
pub struct ServerMetrics {
    text: Arc<Mutex<String>>,
    port: Option<u16>,
    bind: String,
    stopped: Arc<std::sync::atomic::AtomicBool>,
    /// NetherForge's own share of each tick lately: its sum, how many, and the worst, in nanoseconds.
    tick_nanos: Cell<u64>,
    tick_count: Cell<u64>,
    worst_nanos: Cell<u64>,
}

impl ServerMetrics {
    /// Listens on `config`'s port (none: off). A port that can't be listened on is said in the log.
    pub fn start(config: &MetricsConfig, platform: &dyn Platform) -> Option<ServerMetrics> {
        let port = config.port?;
        let listener = match TcpListener::bind((config.bind.as_str(), port)) {
            Ok(listener) => listener,
            Err(e) => {
                platform.log().error(
                    format!(
                        "NetherForge's metrics couldn't listen on {}:{port}",
                        config.bind
                    ),
                    Some(netherforge_platform::PlatformError {
                        message: e.to_string(),
                        cause: None,
                    }),
                );
                return None;
            }
        };
        let bound = listener.local_addr().map(|a| a.port()).unwrap_or(port);
        let text = Arc::new(Mutex::new(String::new()));
        let stopped = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let served = text.clone();
        let stop = stopped.clone();
        std::thread::Builder::new()
            .name("NetherForge metrics".into())
            .spawn(move || {
                for stream in listener.incoming().flatten() {
                    if stop.load(std::sync::atomic::Ordering::Acquire) {
                        break;
                    }
                    serve(stream, &served);
                }
            })
            .ok()?;
        platform.log().info(format!(
            "NetherForge's metrics are at http://{}:{bound}/metrics",
            config.bind
        ));
        Some(ServerMetrics {
            text,
            port: Some(bound),
            bind: config.bind.clone(),
            stopped,
            tick_nanos: Cell::new(0),
            tick_count: Cell::new(0),
            worst_nanos: Cell::new(0),
        })
    }

    /// Stops listening: the port is let go (a connection wakes the thread waiting for one).
    pub fn stop(&self) {
        self.stopped
            .store(true, std::sync::atomic::Ordering::Release);
        if let Some(port) = self.port {
            let _ = std::net::TcpStream::connect_timeout(
                &std::net::SocketAddr::new(
                    self.bind.parse().unwrap_or([127, 0, 0, 1].into()),
                    port,
                ),
                Duration::from_millis(500),
            );
        }
    }

    /// The port it listens on.
    pub fn port(&self) -> Option<u16> {
        self.port
    }

    /// What NetherForge's part of a tick took.
    pub fn record(&self, nanos: u64) {
        self.tick_nanos.set(self.tick_nanos.get() + nanos);
        self.tick_count.set(self.tick_count.get() + 1);
        self.worst_nanos.set(self.worst_nanos.get().max(nanos));
    }

    /// The main thread, each tick: once a second, the metrics gathered again from `session`.
    pub fn tick(&self, tick: u64, session: Option<&Session>, platform: &dyn Platform) {
        if !tick.is_multiple_of(GATHER_TICKS) {
            return;
        }
        let text = self.gather(session, platform);
        *self.text.lock().unwrap_or_else(|e| e.into_inner()) = text;
        self.tick_nanos.set(0);
        self.tick_count.set(0);
        self.worst_nanos.set(0);
    }

    fn gather(&self, session: Option<&Session>, platform: &dyn Platform) -> String {
        let mut out = Prometheus::default();
        let performance = platform.performance();
        out.sample(
            "netherforge_tps",
            "Ticks a second, averaged over the last minute",
            Value::Measure(performance.ticks_per_second()),
            &[],
            false,
        );
        out.sample(
            "netherforge_tick_milliseconds",
            "How long a tick takes, averaged over the last 100",
            Value::Measure(performance.tick_milliseconds()),
            &[],
            false,
        );
        let average = match self.tick_count.get() {
            0 => 0.0,
            n => self.tick_nanos.get() as f64 / n as f64 / 1e6,
        };
        out.sample(
            "netherforge_runtime_tick_milliseconds",
            "NetherForge's share of a tick",
            Value::Measure(average),
            &[("stat", "average")],
            false,
        );
        out.sample(
            "netherforge_runtime_tick_milliseconds",
            "NetherForge's share of a tick",
            Value::Measure(self.worst_nanos.get() as f64 / 1e6),
            &[("stat", "worst")],
            false,
        );
        out.sample(
            "netherforge_players",
            "Players on this server",
            Value::Count(platform.players().online().len() as i64),
            &[],
            false,
        );
        let worlds = platform.worlds();
        for world in worlds.names() {
            out.sample(
                "netherforge_entities",
                "Entities in a world",
                Value::Count(i64::from(worlds.entity_count(world.clone()))),
                &[("world", &world)],
                false,
            );
            out.sample(
                "netherforge_chunks",
                "Loaded chunks in a world",
                Value::Count(i64::from(worlds.chunk_count(world.clone()))),
                &[("world", &world)],
                false,
            );
        }
        if let Some(session) = session {
            let shared = session.shared();
            out.sample(
                "netherforge_centities",
                "Centities alive",
                Value::Count(shared.centities.borrow().all().count() as i64),
                &[],
                false,
            );
            let home = session.project().namespace().to_owned();
            let mut by_package: BTreeMap<String, (f64, usize, usize)> = BTreeMap::new();
            if let Some(room) = session.room() {
                for entry in session.costs() {
                    let namespace = room
                        .scripts()
                        .namespace(entry.scope)
                        .unwrap_or_else(|| home.clone());
                    let sums = by_package.entry(namespace).or_default();
                    sums.0 += entry.average_millis;
                    sums.1 += entry.bytes;
                    sums.2 += 1;
                }
            }
            for (namespace, (millis, bytes, scripts)) in &by_package {
                let labels = [("package", namespace.as_str())];
                out.sample(
                    "netherforge_script_milliseconds",
                    "What a package's scripts take a tick, on average",
                    Value::Measure(*millis),
                    &labels,
                    false,
                );
                out.sample(
                    "netherforge_script_bytes",
                    "What a package's scripts hold in memory",
                    Value::Count(*bytes as i64),
                    &labels,
                    false,
                );
                out.sample(
                    "netherforge_scripts",
                    "A package's scripts running",
                    Value::Count(*scripts as i64),
                    &labels,
                    false,
                );
            }
            let network = shared.network.borrow();
            let sent: BTreeMap<_, _> = network.sent_by_package.iter().collect();
            for (namespace, count) in sent {
                out.sample(
                    "netherforge_network_sent_total",
                    "What a package sent to the network",
                    Value::Count(*count as i64),
                    &[("package", namespace)],
                    true,
                );
            }
            let heard: BTreeMap<_, _> = network.heard_by_package.iter().collect();
            for (namespace, count) in heard {
                out.sample(
                    "netherforge_network_heard_total",
                    "What reached a package from the network",
                    Value::Count(*count as i64),
                    &[("package", namespace)],
                    true,
                );
            }
            if let Some(link) = &network.link {
                out.sample(
                    "netherforge_network_connected",
                    "Whether this server is connected to its network's hub",
                    Value::Count(i64::from(link.connected())),
                    &[],
                    false,
                );
                let now = link.lifecycle();
                for state in Lifecycle::ALL {
                    out.sample(
                        "netherforge_state",
                        "Where this server is in its life",
                        Value::Count(i64::from(now == *state)),
                        &[("state", state.word())],
                        false,
                    );
                }
                if let Some(millis) = link.state.borrow().startup_millis {
                    out.sample(
                        "netherforge_startup_milliseconds",
                        "From the process starting to ready",
                        Value::Count(millis),
                        &[],
                        false,
                    );
                }
            }
        }
        out.text()
    }
}

/// Answers one request: the metrics at `/metrics`, nothing anywhere else.
fn serve(mut stream: std::net::TcpStream, text: &Mutex<String>) {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
    let mut head = Vec::new();
    let mut buffer = [0u8; 1024];
    while !head.windows(4).any(|w| w == b"\r\n\r\n") && head.len() < 16 * 1024 {
        match stream.read(&mut buffer) {
            Ok(0) | Err(_) => break,
            Ok(n) => head.extend_from_slice(&buffer[..n]),
        }
    }
    let line = String::from_utf8_lossy(&head);
    let path = line.split_whitespace().nth(1).unwrap_or("");
    let answer = if path == "/metrics" || path.starts_with("/metrics?") {
        let body = text.lock().unwrap_or_else(|e| e.into_inner()).clone();
        format!(
            "HTTP/1.1 200 OK\r\nContent-Type: text/plain; version=0.0.4; charset=utf-8\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
            body.len()
        )
    } else {
        "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_owned()
    };
    let _ = stream.write_all(answer.as_bytes());
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Kotlin's `Prometheus` writes the same text: help and type once, labels escaped, measures to six places.
    #[test]
    fn samples_are_written_as_prometheus_reads_them() {
        let mut out = Prometheus::default();
        out.sample(
            "netherforge_tps",
            "Ticks a second",
            Value::Measure(19.5),
            &[],
            false,
        );
        out.sample(
            "netherforge_chunks",
            "Loaded chunks",
            Value::Count(3),
            &[("world", "a\"b")],
            false,
        );
        out.sample(
            "netherforge_chunks",
            "Loaded chunks",
            Value::Count(4),
            &[("world", "c")],
            false,
        );
        out.sample(
            "netherforge_network_sent_total",
            "Sent",
            Value::Count(2),
            &[("package", "x")],
            true,
        );
        assert_eq!(
            out.text(),
            "# HELP netherforge_tps Ticks a second\n# TYPE netherforge_tps gauge\nnetherforge_tps 19.500000\n\
             # HELP netherforge_chunks Loaded chunks\n# TYPE netherforge_chunks gauge\nnetherforge_chunks{world=\"a\\\"b\"} 3\n\
             netherforge_chunks{world=\"c\"} 4\n# HELP netherforge_network_sent_total Sent\n\
             # TYPE netherforge_network_sent_total counter\nnetherforge_network_sent_total{package=\"x\"} 2\n"
        );
    }
}
