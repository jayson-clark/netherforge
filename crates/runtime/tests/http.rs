//! `nf.http` on the Rust runtime against servers on this machine (no real internet): requests and their guard,
//! redirects and limits, streams, WebSockets, and `nf.images.download`. Scripts print; tests read the log, ticking
//! until what they wait for is there.

use std::cell::RefCell;
use std::collections::BTreeSet;
use std::io::{BufRead as _, BufReader, Read as _, Write as _};
use std::net::{IpAddr, SocketAddr, TcpListener, TcpStream};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use netherforge_format::package::NoPackages;
use netherforge_format::project::ProjectSource;
use netherforge_platform::{FakePlatform, FakePlatformLog, PlatformError, PlatformLog, ServerInfo};
use netherforge_runtime::http::api::NetHooks;
use netherforge_runtime::http::guard::{self, Kind};
use netherforge_runtime::http::split::{Message, StreamFormat, StreamSplitter};
use netherforge_runtime::http::{HttpConfig, PrivateAllowance};
use netherforge_runtime::session::{Config, Session};

#[derive(Default)]
struct Fake {
    lines: RefCell<Vec<String>>,
}

impl Fake {
    fn take(&self) -> Vec<String> {
        std::mem::take(&mut self.lines.borrow_mut())
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
}

/// No structure has put a marker in the world.
impl netherforge_platform::FakeEntityOps for Fake {
    fn structure_markers(&self) -> Vec<netherforge_platform::StructureMarker> {
        Vec::new()
    }
}

impl FakePlatform for Fake {
    fn entities(&self) -> &dyn netherforge_platform::EntityOps {
        self
    }
    fn players(&self) -> &dyn netherforge_platform::PlayerOps {
        self
    }
    fn teams(&self) -> &dyn netherforge_platform::TeamOps {
        self
    }
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
}

struct Files {
    names: &'static BTreeSet<String>,
    texts: std::collections::BTreeMap<String, String>,
}

impl Files {
    fn new(files: &[(&str, String)]) -> Files {
        Files {
            names: Box::leak(Box::new(files.iter().map(|(p, _)| p.to_string()).collect())),
            texts: files
                .iter()
                .map(|(p, t)| (p.to_string(), t.clone()))
                .collect(),
        }
    }
}

impl ProjectSource for Files {
    fn files(&self) -> &BTreeSet<String> {
        self.names
    }
    fn read(&self, path: &str) -> Option<String> {
        self.texts.get(path).cloned()
    }
}

/// A project declaring `hosts`, with one module.
fn project(hosts: &[&str], module: &str) -> Files {
    let hosts: Vec<String> = hosts.iter().map(|h| format!("\"{h}\"")).collect();
    let manifest = format!(
        r#"{{ "formatVersion": 1, "name": "Demo", "namespace": "demo", "version": "1.0.0", "minecraft": "26.3", "requires": {{ "http": [{}] }} }}"#,
        hosts.join(", ")
    );
    Files::new(&[
        ("netherforge.json", manifest),
        ("modules/net/init.lua", module.to_owned()),
    ])
}

/// What a test server was asked.
#[derive(Clone, Debug)]
struct Seen {
    method: String,
    path: String,
    headers: Vec<(String, String)>,
    body: Vec<u8>,
}

impl Seen {
    fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(n, _)| n == name)
            .map(|(_, v)| v.as_str())
    }
}

/// Reads one request off `stream`.
fn read_request(stream: &mut TcpStream) -> Option<Seen> {
    let mut reader = BufReader::new(stream.try_clone().ok()?);
    let mut line = String::new();
    reader.read_line(&mut line).ok()?;
    let mut parts = line.split_whitespace();
    let (method, path) = (parts.next()?.to_owned(), parts.next()?.to_owned());
    let mut headers = Vec::new();
    loop {
        let mut line = String::new();
        reader.read_line(&mut line).ok()?;
        let line = line.trim_end();
        if line.is_empty() {
            break;
        }
        let (name, value) = line.split_once(':')?;
        headers.push((name.trim().to_lowercase(), value.trim().to_owned()));
    }
    let length: usize = headers
        .iter()
        .find(|(n, _)| n == "content-length")
        .and_then(|(_, v)| v.parse().ok())
        .unwrap_or(0);
    let mut body = vec![0; length];
    reader.read_exact(&mut body).ok()?;
    Some(Seen {
        method,
        path,
        headers,
        body,
    })
}

/// A server on loopback answering each request with what `answer` writes, and keeping what it was asked.
struct Server {
    port: u16,
    seen: Arc<Mutex<Vec<Seen>>>,
}

impl Server {
    fn url(&self, path: &str) -> String {
        format!("http://127.0.0.1:{}{path}", self.port)
    }
    fn seen(&self) -> Vec<Seen> {
        self.seen.lock().unwrap().clone()
    }
}

fn serve(answer: impl Fn(&Seen, &mut TcpStream) + Send + Sync + 'static) -> Server {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let seen: Arc<Mutex<Vec<Seen>>> = Arc::default();
    let kept = seen.clone();
    let answer = Arc::new(answer);
    std::thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(mut stream) = stream else { continue };
            let (kept, answer) = (kept.clone(), answer.clone());
            std::thread::spawn(move || {
                if let Some(request) = read_request(&mut stream) {
                    kept.lock().unwrap().push(request.clone());
                    answer(&request, &mut stream);
                }
            });
        }
    });
    Server { port, seen }
}

fn respond(stream: &mut TcpStream, status: &str, headers: &[(&str, &str)], body: &[u8]) {
    let mut head = format!(
        "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n",
        body.len()
    );
    for (name, value) in headers {
        head.push_str(&format!("{name}: {value}\r\n"));
    }
    head.push_str("\r\n");
    let _ = stream.write_all(head.as_bytes());
    let _ = stream.write_all(body);
    let _ = stream.flush();
}

fn config(http: HttpConfig) -> Config {
    Config {
        http,
        ..Config::default()
    }
}

/// Loopback allowed for the project's package on `port`.
fn allowing(port: u16) -> HttpConfig {
    HttpConfig {
        allow_private: vec![PrivateAllowance {
            namespace: "demo".into(),
            host: "127.0.0.1".into(),
            port,
            requests_per_minute: None,
        }],
        ..HttpConfig::default()
    }
}

/// Ticks until a script has printed `count` lines (at most five seconds), and gives them.
fn printed(session: &Session, platform: &Fake, count: usize) -> Vec<String> {
    let mut lines = Vec::new();
    let started = Instant::now();
    while started.elapsed() < Duration::from_secs(5) {
        for line in platform.take() {
            if let Some(rest) = line.strip_prefix("info [module net] ") {
                // Without the place it was printed from.
                let text = rest.rsplit_once(" (modules/").map_or(rest, |(t, _)| t);
                lines.push(text.to_owned());
            } else if line.starts_with("warn") || line.starts_with("error") {
                lines.push(line);
            }
        }
        if lines.len() >= count {
            return lines;
        }
        session.tick();
        std::thread::sleep(Duration::from_millis(5));
    }
    panic!("only {} of {count} lines: {lines:?}", lines.len());
}

fn start<'a>(files: &'a Files, platform: &Rc, config: Config) -> Session<'a> {
    Session::start_configured(
        platform.clone(),
        files,
        &NoPackages,
        std::rc::Rc::new(netherforge_runtime::store::Store::in_memory()),
        config,
    )
}

type Rc = std::rc::Rc<Fake>;

#[test]
fn a_request_reaches_a_server_its_package_may_reach_and_the_answer_comes_at_a_tick() {
    let server = serve(|request, stream| {
        let body = format!(
            "{} {} {}",
            request.method,
            request.path,
            String::from_utf8_lossy(&request.body)
        );
        respond(
            stream,
            "201 Created",
            &[
                ("X-Thing", "a"),
                ("X-Thing", "b"),
                ("Content-Type", "text/plain"),
            ],
            body.as_bytes(),
        );
    });
    let module = format!(
        r#"
        nf.http.request({{ url = "{get}" }}, function(response, err)
            print(response.status, response.body, response.headers["x-thing"], response.url, err)
        end)
        nf.http.request({{ url = "{post}", method = "POST", body = "hello", headers = {{ ["X-Key"] = "k" }} }}, function(response, err)
            print(response.status, response.body)
        end)
        nf.task(function()
            local response, err = nf.http.request({{ url = "{get}" }})
            print("waited", response.status)
        end)
        print("asked")
        "#,
        get = server.url("/get?x=1"),
        post = server.url("/post"),
    );
    let files = project(&["127.0.0.1"], &module);
    let platform = Rc::default();
    let session = start(&files, &platform, config(allowing(server.port)));
    let mut lines = printed(&session, &platform, 4);
    assert_eq!(
        lines.remove(0),
        "asked",
        "nothing comes back inside the call"
    );
    lines.sort();
    assert_eq!(
        lines,
        [
            format!("201\tGET /get?x=1 \ta, b\t{}\tnil", server.url("/get?x=1")),
            "201\tPOST /post hello".to_owned(),
            "waited\t201".to_owned(),
        ]
    );
    let seen = server.seen();
    let post = seen.iter().find(|s| s.method == "POST").unwrap();
    assert_eq!(post.header("user-agent"), Some("NetherForge"));
    assert_eq!(post.header("x-key"), Some("k"));
}

#[test]
fn mistakes_in_the_call_are_errors_at_the_scripts_line() {
    let module = r#"
        local function try(options)
            local ok, err = pcall(nf.http.request, options, function() end)
            print(ok, err)
        end
        try({ url = "ftp://example.com/" })
        try({ url = "https://user:pw@example.com/" })
        try({ url = "https://example.com/", headers = { Host = "x" } })
        try({ url = "https://example.com/", headers = { ["Bad Name"] = "x" } })
        try({ url = "https://example.com/", headers = { A = "x\ny" } })
        try({ url = "https://example.com/", body = "x" })
        try({ url = "https://other.org/" })
    "#;
    let files = project(&["example.com"], module);
    let platform = Rc::default();
    let session = start(&files, &platform, Config::default());
    let lines = printed(&session, &platform, 7);
    let at = "modules/net/init.lua:3: ";
    assert_eq!(
        lines,
        [
            format!(
                "false\t{at}options.url must be an http:// or https:// URL with a host (got \"ftp://example.com/\")"
            ),
            format!(
                "false\t{at}options.url can't have a user name or password in it: send credentials in a header"
            ),
            format!("false\t{at}options.headers can't set \"Host\": the server sets it"),
            format!("false\t{at}options.headers has a name that isn't a header name: \"Bad Name\""),
            format!(
                "false\t{at}options.headers[\"A\"] has a character a header can't hold (only printable ASCII and tabs)"
            ),
            format!("false\t{at}a GET request can't have a body"),
            format!(
                "false\t{at}nf.http.request needs http:other.org, which package \"demo\" hasn't declared: add \"requires\": {{ \"http\": [\"other.org\"] }} to its netherforge.json"
            ),
        ]
    );
}

#[test]
fn the_guard_judges_the_address_connected_to() {
    let server = serve(|_, stream| respond(stream, "200 OK", &[], b"local"));
    let port = server.port;
    // `public.test` leads to a public address, which the test's dialer takes to the local server; `evil.test` leads
    // somewhere private; `mixed.test` to both.
    let hooks = NetHooks {
        resolve: Some(Arc::new(|host: &str| match host {
            "public.test" => Ok(vec!["93.184.216.34".parse().unwrap()]),
            "evil.test" => Ok(vec!["10.0.0.7".parse().unwrap()]),
            "mixed.test" => Ok(vec![
                "93.184.216.34".parse().unwrap(),
                "192.168.1.1".parse().unwrap(),
            ]),
            _ => Err(std::io::Error::other("no such name")),
        })),
        dial: Some(Arc::new(move |_: SocketAddr| {
            SocketAddr::from(([127, 0, 0, 1], port))
        })),
        own: Some(Arc::new(Vec::new)),
    };
    let module = format!(
        r#"
        for _, url in ipairs({{ "http://public.test:{port}/", "http://evil.test/", "http://mixed.test/", "http://nowhere.test/", "http://127.0.0.1:{port}/" }}) do
            nf.http.request({{ url = url }}, function(response, err)
                print(url, response and response.body, err)
            end)
        end
        "#
    );
    let files = project(
        &[
            "public.test",
            "evil.test",
            "mixed.test",
            "nowhere.test",
            "127.0.0.1",
        ],
        &module,
    );
    let platform = Rc::default();
    let session = start(
        &files,
        &platform,
        Config {
            net: hooks,
            ..Config::default()
        },
    );
    let mut lines = printed(&session, &platform, 5);
    lines.sort();
    let tail = "which nf.http.request doesn't reach (the server owner can allow private addresses with http.allow-private-addresses in config.yml, or one package one host and port with http.allow-private)";
    let mut expected = vec![
        format!("http://public.test:{port}/\tlocal\tnil"),
        format!("http://evil.test/\tnil\tevil.test leads to 10.0.0.7, a private address, {tail}"),
        format!(
            "http://mixed.test/\tnil\tmixed.test leads to 192.168.1.1, a private address, {tail}"
        ),
        "http://nowhere.test/\tnil\tnowhere.test doesn't resolve".to_owned(),
        format!("http://127.0.0.1:{port}/\tnil\t127.0.0.1 is a loopback address, {tail}"),
    ];
    expected.sort();
    assert_eq!(lines, expected);
}

#[test]
fn redirects_are_followed_hop_by_hop_and_held_to_the_declaration() {
    let server = serve(move |request, stream| match request.path.as_str() {
        "/start" => respond(stream, "302 Found", &[("Location", "/next")], b""),
        "/next" => respond(
            stream,
            "200 OK",
            &[],
            format!(
                "{} {}",
                request.method,
                request.header("authorization").unwrap_or("-")
            )
            .as_bytes(),
        ),
        "/away" => respond(
            stream,
            "307 Temporary Redirect",
            &[("Location", "http://elsewhere.test/")],
            b"",
        ),
        "/loop" => respond(stream, "302 Found", &[("Location", "/loop")], b""),
        _ => respond(stream, "404 Not Found", &[], b""),
    });
    let module = format!(
        r#"
        nf.http.request({{ url = "{start}", method = "POST", body = "x", headers = {{ Authorization = "secret" }} }}, function(response, err)
            print("start", response.status, response.body, response.url)
        end)
        nf.http.request({{ url = "{away}" }}, function(response, err) print("away", err) end)
        nf.http.request({{ url = "{loop}" }}, function(response, err) print("loop", err) end)
        "#,
        start = server.url("/start"),
        away = server.url("/away"),
        loop = server.url("/loop"),
    );
    let files = project(&["127.0.0.1"], &module);
    let platform = Rc::default();
    let session = start(&files, &platform, config(allowing(server.port)));
    let mut lines = printed(&session, &platform, 3);
    lines.sort();
    assert_eq!(
        lines,
        [
            "away\tit was redirected to elsewhere.test, and nf.http.request needs http:elsewhere.test, which package \"demo\" hasn't declared: add \"requires\": { \"http\": [\"elsewhere.test\"] } to its netherforge.json".to_owned(),
            "loop\tit was redirected more than 5 times".to_owned(),
            // A 302 turns the POST into a GET; the same host keeps its credentials.
            format!("start\t200\tGET secret\t{}", server.url("/next")),
        ]
    );
}

#[test]
fn the_owners_limits_fail_the_request_not_the_script() {
    let server = serve(|request, stream| {
        let size = if request.path == "/big" { 2000 } else { 10 };
        respond(stream, "200 OK", &[], &vec![b'x'; size]);
    });
    let module = format!(
        r#"
        nf.http.request({{ url = "{big}" }}, function(response, err) print("big", err) end)
        for i = 1, 3 do
            nf.http.request({{ url = "{small}" }}, function(response, err) print("small", response and #response.body, err) end)
        end
        local ok, err = pcall(nf.http.request, {{ url = "{small}", method = "POST", body = string.rep("y", 600) }}, function() end)
        print(ok, err)
        "#,
        big = server.url("/big"),
        small = server.url("/small"),
    );
    let files = project(&["127.0.0.1"], &module);
    let platform = Rc::default();
    let limits = HttpConfig {
        max_response_bytes: 1000,
        max_request_bytes: 500,
        requests_per_minute: 3,
        ..allowing(server.port)
    };
    let session = start(&files, &platform, config(limits));
    let mut lines = printed(&session, &platform, 5);
    lines.sort();
    assert_eq!(
        lines,
        [
            "big\tthe response is larger than http.max-response-bytes (1000 bytes)".to_owned(),
            "false\tmodules/net/init.lua:6: options.body is 600 bytes, over the server's limit of 500 (http.max-request-bytes in config.yml)".to_owned(),
            "small\t10\tnil".to_owned(),
            "small\t10\tnil".to_owned(),
            "small\tnil\tpackage \"demo\" has made 3 requests in the last minute, the server's limit (http.requests-per-minute in config.yml)".to_owned(),
        ]
    );
}

#[test]
fn a_stream_hands_over_lines_and_events_then_ends() {
    let server = serve(|request, stream| {
        let body: &[u8] = if request.path == "/events" {
            b": hello\nevent: score\ndata: 1\ndata: 2\nid: 7\n\ndata: plain\n\ndata: half"
        } else {
            b"one\r\ntwo\nthree"
        };
        let _ = stream.write_all(
            b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n",
        );
        // In two pieces, the second a little later.
        let (a, b) = body.split_at(5);
        for piece in [a, b] {
            let _ = stream.write_all(format!("{:x}\r\n", piece.len()).as_bytes());
            let _ = stream.write_all(piece);
            let _ = stream.write_all(b"\r\n");
            let _ = stream.flush();
            std::thread::sleep(Duration::from_millis(30));
        }
        let _ = stream.write_all(b"0\r\n\r\n");
    });
    let module = format!(
        r#"
        local lines = nf.http.stream({{ url = "{lines}" }})
        print("state", lines:state(), lines:url() == "{lines}")
        lines:on("open", function(e) print("open", e.status, lines:state()) end)
        lines:on("message", function(e) print("line", e.data, e.type) end)
        lines:on("end", function(e) print("end", e.reason, e.status, lines:state()) end)
        local events = nf.http.stream({{ url = "{events}", format = "events" }})
        events:on("message", function(e) print("event", e.data, e.type, e.id) end)
        events:on("end", function(e) print("events end", e.reason) end)
        "#,
        lines = server.url("/lines"),
        events = server.url("/events"),
    );
    let files = project(&["127.0.0.1"], &module);
    let platform = Rc::default();
    let session = start(&files, &platform, config(allowing(server.port)));
    let lines = printed(&session, &platform, 9);
    let of = |prefix: &str| -> Vec<String> {
        lines
            .iter()
            .filter(|l| l.starts_with(prefix))
            .cloned()
            .collect()
    };
    assert_eq!(lines[0], "state\tconnecting\ttrue");
    assert_eq!(of("open"), ["open\t200\topen"]);
    assert_eq!(
        of("line"),
        ["line\tone\tnil", "line\ttwo\tnil", "line\tthree\tnil"]
    );
    assert_eq!(of("end"), ["end\tfinished\t200\tended"]);
    assert_eq!(
        of("event\t"),
        ["event\t1\n2\tscore\t7", "event\tplain\tmessage\t7"]
    );
    assert_eq!(of("events end"), ["events end\tfinished"]);
    let seen = server.seen();
    let events = seen.iter().find(|s| s.path == "/events").unwrap();
    assert_eq!(events.header("accept"), Some("text/event-stream"));
}

#[test]
fn a_stream_its_script_closes_or_unloads_ends_and_a_limit_ends_it_at_the_next_tick() {
    // A server that sends a line, then holds the connection open.
    let server = serve(|_, stream| {
        let _ = stream
            .write_all(b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nhi!\n\r\n");
        let _ = stream.flush();
        std::thread::sleep(Duration::from_secs(10));
    });
    let module = format!(
        r#"
        local held = nf.http.stream({{ url = "{url}" }})
        held:on("message", function(e)
            print("got", e.data)
            print("closed", held:close(), held:state(), held:close())
        end)
        held:on("end", function(e) print("end", e.reason) end)
        for i = 1, 2 do
            local s = nf.http.stream({{ url = "{url}" }})
            s:on("end", function(e) print("limited", i, e.reason, e.detail) end)
        end
        "#,
        url = server.url("/held"),
    );
    let files = project(&["127.0.0.1"], &module);
    let platform = Rc::default();
    let mut limits = allowing(server.port);
    limits.connections.max_per_package = 2;
    let session = start(&files, &platform, config(limits));
    let mut lines = printed(&session, &platform, 4);
    lines.sort();
    assert_eq!(
        lines,
        [
            "closed\ttrue\tended\tfalse".to_owned(),
            "end\tclosed".to_owned(),
            "got\thi!".to_owned(),
            "limited\t2\tlimit\tpackage \"demo\" already has 2 connections open, the server's limit (http.connections.max-per-package in config.yml)".to_owned(),
        ]
    );
    // The one left open ends with its script.
    drop(session);
    let said = platform.take();
    assert!(
        said.iter()
            .any(|l| l.starts_with("info [module net] limited\t1\tunloaded")),
        "{said:?}"
    );
}

/// A WebSocket server on loopback that echoes text, says the subprotocol it picked, and closes on "bye".
#[allow(clippy::result_large_err)]
fn websocket_server() -> u16 {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    std::thread::spawn(move || {
        for stream in listener.incoming() {
            let Ok(stream) = stream else { continue };
            std::thread::spawn(move || {
                let pick =
                    |request: &tungstenite::handshake::server::Request,
                     mut response: tungstenite::handshake::server::Response| {
                        if let Some(offered) = request.headers().get("sec-websocket-protocol") {
                            let first = offered
                                .to_str()
                                .unwrap()
                                .split(',')
                                .next()
                                .unwrap()
                                .trim()
                                .to_owned();
                            response
                                .headers_mut()
                                .insert("sec-websocket-protocol", first.parse().unwrap());
                        }
                        Ok(response)
                    };
                let Ok(mut socket) = tungstenite::accept_hdr(stream, pick) else {
                    return;
                };
                while let Ok(message) = socket.read() {
                    match message {
                        tungstenite::Message::Text(text) if text.as_str() == "bye" => {
                            let _ = socket.close(Some(tungstenite::protocol::CloseFrame {
                                code: 4000.into(),
                                reason: "done".into(),
                            }));
                        }
                        tungstenite::Message::Text(text) => {
                            let _ = socket.send(tungstenite::Message::text(format!(
                                "echo {}",
                                text.as_str()
                            )));
                        }
                        tungstenite::Message::Close(_) => break,
                        _ => {}
                    }
                }
                let _ = socket.flush();
            });
        }
    });
    port
}

#[test]
fn a_websocket_opens_echoes_and_ends_either_way() {
    let port = websocket_server();
    let module = format!(
        r#"
        local socket = nf.http.websocket({{ url = "ws://127.0.0.1:{port}/chat", protocols = {{ "v2.chat", "v1" }} }})
        socket:on("open", function(e)
            print("open", e.protocol, socket:protocol(), socket:state())
            socket:send("hello")
        end)
        socket:on("message", function(e)
            print("message", e.data)
            socket:send("bye")
        end)
        socket:on("end", function(e) print("end", e.reason, e.code, e.detail) end)
        local mine = nf.http.websocket({{ url = "ws://127.0.0.1:{port}/" }})
        mine:on("open", function() print("mine closed", mine:close(4001, "enough")) end)
        mine:on("end", function(e) print("mine end", e.reason) end)
        print(pcall(nf.http.websocket, {{ url = "ws://127.0.0.1:{port}/", headers = {{ ["Sec-WebSocket-Key"] = "x" }} }}))
        print(pcall(mine.close, mine, 1001))
        "#
    );
    let files = project(&["127.0.0.1"], &module);
    let platform = Rc::default();
    let session = start(&files, &platform, config(allowing(port)));
    let lines = printed(&session, &platform, 7);
    assert_eq!(
        lines[..2],
        [
            "false\tmodules/net/init.lua:15: options.headers can't set \"Sec-WebSocket-Key\": the server sets it (offer subprotocols with options.protocols)".to_owned(),
            "false\tmodules/net/init.lua:16: bad argument 'code' (1000, or 3000 to 4999 expected, got 1001)".to_owned(),
        ]
    );
    let mut rest = lines[2..].to_vec();
    rest.sort();
    assert_eq!(
        rest,
        [
            "end\tfinished\t4000\tdone",
            "message\techo hello",
            "mine closed\ttrue",
            "mine end\tclosed",
            "open\tv2.chat\tv2.chat\topen",
        ]
    );
}

#[test]
fn a_websocket_to_a_server_that_isnt_one_fails() {
    let server = serve(|_, stream| respond(stream, "301 Moved", &[("Location", "/x")], b""));
    let module = format!(
        r#"
        local socket = nf.http.websocket({{ url = "ws://127.0.0.1:{port}/" }})
        socket:on("end", function(e) print(e.reason, e.detail) end)
        local refused = nf.http.websocket({{ url = "ws://127.0.0.1:1/" }})
        refused:on("end", function(e) print(e.reason, e.detail ~= nil) end)
        "#,
        port = server.port
    );
    let files = project(&["127.0.0.1"], &module);
    let platform = Rc::default();
    let mut limits = allowing(server.port);
    limits.allow_private_addresses = true;
    let session = start(&files, &platform, config(limits));
    let mut lines = printed(&session, &platform, 2);
    lines.sort();
    assert_eq!(
        lines,
        [
            "failed\t127.0.0.1 answered 301 instead of opening a WebSocket (WebSockets don't follow redirects)",
            "failed\ttrue",
        ]
    );
}

#[test]
fn an_image_is_downloaded_decoded_and_held() {
    let mut png = Vec::new();
    image::RgbaImage::from_pixel(3, 2, image::Rgba([255, 0, 0, 255]))
        .write_to(&mut std::io::Cursor::new(&mut png), image::ImageFormat::Png)
        .unwrap();
    let server = serve(move |request, stream| match request.path.as_str() {
        "/red.png" => respond(stream, "200 OK", &[("Content-Type", "image/png")], &png),
        "/text" => respond(stream, "200 OK", &[], b"not a picture"),
        _ => respond(stream, "404 Not Found", &[], b""),
    });
    let module = format!(
        r#"
        nf.images.download({{ url = "{red}" }}, function(image, err)
            print("red", image:width(), image:height(), image:pixel(0, 0), err)
        end)
        nf.images.download({{ url = "{text}" }}, function(image, err) print("text", image, err) end)
        nf.images.download({{ url = "{gone}" }}, function(image, err) print("gone", image, err) end)
        "#,
        red = server.url("/red.png"),
        text = server.url("/text"),
        gone = server.url("/gone"),
    );
    let files = project(&["127.0.0.1"], &module);
    let platform = Rc::default();
    let session = start(&files, &platform, config(allowing(server.port)));
    let mut lines = printed(&session, &platform, 3);
    lines.sort();
    assert_eq!(
        lines,
        [
            "gone\tnil\tthe server answered 404",
            "red\t3\t2\t#FF0000\tnil",
            "text\tnil\twhat came back isn't an image (PNG, JPEG, GIF or BMP) of at most 2048 pixels either way",
        ]
    );
}

#[test]
fn addresses_are_judged_by_their_bytes() {
    let kind = |text: &str| guard::classify(text.parse::<IpAddr>().unwrap());
    assert_eq!(kind("8.8.8.8"), None);
    assert_eq!(kind("2606:4700::1111"), None);
    assert_eq!(kind("127.0.0.1"), Some(Kind::Loopback));
    assert_eq!(kind("10.1.2.3"), Some(Kind::Private));
    assert_eq!(kind("172.31.0.1"), Some(Kind::Private));
    assert_eq!(kind("172.32.0.1"), None);
    assert_eq!(kind("100.64.0.1"), Some(Kind::Shared));
    assert_eq!(kind("169.254.169.254"), Some(Kind::LinkLocal));
    assert_eq!(kind("198.18.0.1"), Some(Kind::Private));
    assert_eq!(kind("192.0.2.1"), Some(Kind::Reserved));
    assert_eq!(kind("0.0.0.0"), Some(Kind::Never));
    assert_eq!(kind("255.255.255.255"), Some(Kind::Never));
    assert_eq!(kind("224.0.0.1"), Some(Kind::Never));
    assert_eq!(kind("::"), Some(Kind::Never));
    assert_eq!(kind("::1"), Some(Kind::Loopback));
    assert_eq!(kind("::ffff:127.0.0.1"), Some(Kind::Loopback));
    assert_eq!(kind("::ffff:8.8.8.8"), None);
    assert_eq!(kind("64:ff9b::a00:1"), Some(Kind::Reserved));
    assert_eq!(kind("2002:a00:1::"), Some(Kind::Private));
    // 6to4 to a public address is still reserved, as Kotlin's guard has it.
    assert_eq!(kind("2002:808:808::"), Some(Kind::Reserved));
    assert_eq!(kind("fd00::1"), Some(Kind::UniqueLocal));
    assert_eq!(kind("fe80::1"), Some(Kind::LinkLocal));
    assert_eq!(kind("ff02::1"), Some(Kind::Never));
    assert_eq!(kind("2001:db8::1"), Some(Kind::Reserved));
    assert_eq!(guard::refusal("10.0.0.1".parse().unwrap(), true), None);
    assert_eq!(
        guard::refusal("0.0.0.0".parse().unwrap(), true),
        Some("not an address of one host")
    );
    assert_eq!(
        guard::literal("127.0.0.1"),
        Some("127.0.0.1".parse().unwrap())
    );
    for loose in ["2130706433", "127.1", "0177.0.0.1", "1.2.3.256"] {
        assert!(guard::looks_literal(loose), "{loose}");
        assert_eq!(guard::literal(loose), None, "{loose}");
    }
    assert!(!guard::looks_literal("example.com"));
    // An address in a URL is judged as one, never looked up.
    let fetcher = netherforge_runtime::http::Fetcher::with(
        HttpConfig::default(),
        Arc::new(|_: &str| panic!("looked up")),
        None,
        Arc::new(Vec::new),
    );
    let guard = fetcher.guard(&[], 80);
    assert_eq!(
        guard.addresses("::1", 80).unwrap_err().0.split(',').next(),
        Some("::1 is a loopback address")
    );
    assert!(
        guard
            .addresses("::ffff:10.0.0.1", 80)
            .unwrap_err()
            .0
            .starts_with("::ffff:10.0.0.1 is a private address")
    );
    assert!(
        guard
            .addresses("127.1", 80)
            .unwrap_err()
            .0
            .starts_with("127.1 isn't an IP address")
    );
    assert_eq!(
        guard.addresses("8.8.8.8", 80).unwrap(),
        ["8.8.8.8:80".parse::<SocketAddr>().unwrap()]
    );
}

/// Each message's data, type and id, or why splitting stopped.
type Split = Result<Vec<(String, Option<String>, Option<String>)>, String>;

fn split(format: StreamFormat, pieces: &[&[u8]], max: usize) -> Split {
    let mut splitter = StreamSplitter::new(format, max);
    let mut out = Vec::new();
    let mut emit = |m: Message| -> Result<(), ()> {
        out.push((m.data, m.r#type, m.id));
        Ok(())
    };
    for piece in pieces {
        splitter
            .feed(piece, &mut emit)
            .map_err(|e| format!("{e:?}"))?;
    }
    splitter.finish(&mut emit).map_err(|e| format!("{e:?}"))?;
    Ok(out)
}

#[test]
fn a_body_is_split_into_lines_events_or_chunks() {
    let texts = |r: Split| -> Vec<String> { r.unwrap().into_iter().map(|m| m.0).collect() };
    assert_eq!(
        texts(split(StreamFormat::Lines, &[b"a\r", b"\nb\rc\n\nd"], 100)),
        ["a", "b", "c", "", "d"]
    );
    // A character cut between two pieces is decoded whole.
    let euro = "€".as_bytes();
    assert_eq!(
        texts(split(
            StreamFormat::Chunks,
            &[&[b'x', euro[0]], &euro[1..], b"!"],
            100
        )),
        ["x", "€", "!"]
    );
    assert_eq!(
        split(
            StreamFormat::Events,
            &[b"retry: 5\nid: 1\nevent: \ndata:x\n\n: c\ndata: y\n\n"],
            100
        )
        .unwrap(),
        [
            (
                "x".to_owned(),
                Some("message".to_owned()),
                Some("1".to_owned())
            ),
            (
                "y".to_owned(),
                Some("message".to_owned()),
                Some("1".to_owned())
            ),
        ]
    );
    assert_eq!(
        split(StreamFormat::Lines, &[b"abcdef"], 3),
        Err("TooLarge(TooLarge(3))".into())
    );
}
