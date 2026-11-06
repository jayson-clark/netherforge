//! `nf.web` on the Rust runtime: routes answered over real HTTP to the plugin's listener (ticking while the request
//! waits), the gate's refusals, webhook signatures, and a login flow up to its token request.

use std::cell::RefCell;
use std::collections::BTreeSet;
use std::io::{Read as _, Write as _};
use std::net::{SocketAddr, TcpStream};
use std::sync::{Arc, mpsc};
use std::time::{Duration, Instant};

use netherforge_format::package::NoPackages;
use netherforge_format::project::ProjectSource;
use netherforge_platform::{FakePlatform, FakePlatformLog, PlatformError, PlatformLog, ServerInfo};
use netherforge_runtime::http::api::NetHooks;
use netherforge_runtime::session::{Config, Session};
use netherforge_runtime::web::gate::WebConfig;

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

fn alex() -> netherforge_platform::PlayerRef {
    netherforge_platform::PlayerRef {
        uuid: netherforge_platform::Uuid(77),
        name: "Alex".into(),
    }
}

/// Alex is online.
impl netherforge_platform::FakePlayerOps for Fake {
    fn online(&self) -> Vec<netherforge_platform::PlayerRef> {
        vec![alex()]
    }
    fn get(&self, uuid: netherforge_platform::Uuid) -> Option<netherforge_platform::PlayerRef> {
        (uuid == alex().uuid).then(alex)
    }
}

impl netherforge_platform::FakePermissionOps for Fake {
    fn apply(&self, _: netherforge_platform::Uuid, _: std::collections::HashMap<String, bool>) {}
}

impl netherforge_platform::FakeItemViewOps for Fake {
    fn refresh(&self, _: netherforge_platform::Uuid) -> bool {
        true
    }
}

impl netherforge_platform::FakeResourcePackOps for Fake {}

impl FakePlatform for Fake {
    fn players(&self) -> &dyn netherforge_platform::PlayerOps {
        self
    }
    fn teams(&self) -> &dyn netherforge_platform::TeamOps {
        self
    }
    fn permissions(&self) -> &dyn netherforge_platform::PermissionOps {
        self
    }
    fn item_views(&self) -> &dyn netherforge_platform::ItemViewOps {
        self
    }
    fn resource_packs(&self) -> &dyn netherforge_platform::ResourcePackOps {
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

impl ProjectSource for Files {
    fn files(&self) -> &BTreeSet<String> {
        self.names
    }
    fn read(&self, path: &str) -> Option<String> {
        self.texts.get(path).cloned()
    }
}

fn project(module: &str) -> Files {
    let manifest = r#"{ "formatVersion": 1, "name": "Demo", "namespace": "demo", "version": "1.0.0", "minecraft": "26.3", "requires": { "web": true, "http": ["auth.test"] } }"#;
    let files = [
        ("netherforge.json".to_owned(), manifest.to_owned()),
        ("modules/site/init.lua".to_owned(), module.to_owned()),
    ];
    Files {
        names: Box::leak(Box::new(files.iter().map(|(p, _)| p.clone()).collect())),
        texts: files.into_iter().collect(),
    }
}

type Rc = std::rc::Rc<Fake>;

fn start<'a>(files: &'a Files, platform: &Rc, web: WebConfig, net: NetHooks) -> Session<'a> {
    Session::start_configured(
        platform.clone(),
        files,
        &NoPackages,
        std::rc::Rc::new(netherforge_runtime::store::Store::in_memory()),
        Config {
            web,
            net,
            ..Config::default()
        },
    )
}

fn enabled() -> WebConfig {
    WebConfig {
        enabled: true,
        ..WebConfig::default()
    }
}

/// What a script printed, without where.
fn printed(platform: &Fake) -> Vec<String> {
    platform
        .take()
        .into_iter()
        .filter_map(|line| {
            let rest = line.strip_prefix("info [module site] ")?;
            Some(
                rest.rsplit_once(" (modules/")
                    .map_or(rest, |(t, _)| t)
                    .to_owned(),
            )
        })
        .collect()
}

/// The listener's address, from the log's "Web routes are on, at …".
fn base(lines: &[String]) -> String {
    lines
        .iter()
        .find_map(|l| l.strip_prefix("info Web routes are on, at "))
        .expect("web routes on")
        .to_owned()
}

/// An answer: the status, the headers by lowercase name, the body.
#[derive(Debug)]
struct Answer {
    status: u16,
    headers: Vec<(String, String)>,
    body: String,
}

impl Answer {
    fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(n, _)| n == name)
            .map(|(_, v)| v.as_str())
    }
}

/// Sends `request` (raw HTTP) to `base`'s listener on another thread, ticking `session` until it's answered.
fn ask(session: &Session, base: &str, request: String) -> Answer {
    let address: SocketAddr = base.trim_start_matches("http://").parse().unwrap();
    let (send, receive) = mpsc::channel();
    std::thread::spawn(move || {
        let mut stream = TcpStream::connect(address).unwrap();
        stream.write_all(request.as_bytes()).unwrap();
        let mut text = Vec::new();
        let _ = stream.read_to_end(&mut text);
        let _ = send.send(String::from_utf8_lossy(&text).into_owned());
    });
    let started = Instant::now();
    loop {
        if let Ok(text) = receive.try_recv() {
            let (head, body) = text.split_once("\r\n\r\n").unwrap_or((&text, ""));
            let mut lines = head.lines();
            let status = lines
                .next()
                .unwrap()
                .split(' ')
                .nth(1)
                .unwrap()
                .parse()
                .unwrap();
            let headers = lines
                .filter_map(|l| l.split_once(':'))
                .map(|(n, v)| (n.trim().to_lowercase(), v.trim().to_owned()))
                .collect();
            return Answer {
                status,
                headers,
                body: body.to_owned(),
            };
        }
        assert!(started.elapsed() < Duration::from_secs(10), "no answer");
        session.tick();
        std::thread::sleep(Duration::from_millis(2));
    }
}

fn get(path: &str) -> String {
    format!("GET {path} HTTP/1.1\r\nHost: x\r\n\r\n")
}

fn post(path: &str, kind: &str, body: &str) -> String {
    format!(
        "POST {path} HTTP/1.1\r\nHost: x\r\nContent-Type: {kind}\r\nContent-Length: {}\r\n\r\n{body}",
        body.len()
    )
}

#[test]
fn routes_answer_requests_by_namespace_method_and_path() {
    let module = r#"
        nf.web.route("GET", "/hello/:name", function(request, response)
            response:send(200, "hi " .. request.params.name .. " " .. (request.query.x or "-") .. " " .. request.method,
                { ["X-Mine"] = "yes" })
        end)
        nf.web.route("GET", "/hello/admin", function(request, response) response:send(200, "the admin") end)
        nf.web.route("GET", "/files/*rest", function(request, response) response:send(200, request.params.rest) end)
        nf.web.route("POST", "/form", function(request, response)
            response:json(201, { name = request.form.name, ip = request.ip, body = request.body })
        end)
        nf.web.route("GET", "/broken", function() error("oops") end)
        local later = nil
        nf.web.route("GET", "/later", function(request, response)
            print("pending", response:is_pending())
            nf.after(3, function() print("answered", response:send(202, "later"), response:is_pending(), response:send(200, "again")) end)
        end)
        nf.web.route("GET", "/go", function(_, response) response:redirect("/demo/hello/x") end)
        local gone = nf.web.route("GET", "/gone", function(_, response) response:send(200, "here") end)
        print("removed", gone:remove(), gone:exists(), gone:remove())
        print(pcall(nf.web.route, "GET", "/hello/:other", function() end))
        print(pcall(nf.web.route, "GET", "/a//b", function() end))
        print(nf.web.url("/hello/x", { b = "2 3", a = "1" }) ~= nil, pcall(nf.web.url, "nope"))
    "#;
    let files = project(module);
    let platform = Rc::default();
    let session = start(&files, &platform, enabled(), NetHooks::default());
    let started = platform.take();
    let base = base(&started);
    let said: Vec<String> = started
        .iter()
        .filter_map(|l| l.strip_prefix("info [module site] "))
        .map(|l| l.rsplit_once(" (modules/").map_or(l, |(t, _)| t).to_owned())
        .collect();
    assert_eq!(
        said,
        [
            "removed\ttrue\tfalse\tfalse".to_owned(),
            "false\tmodules/site/init.lua:20: another route of package \"demo\" already answers GET /hello/:name".to_owned(),
            "false\tmodules/site/init.lua:21: bad argument 'path' (it has an empty segment (two slashes, or one at the end), got \"/a//b\")".to_owned(),
            "true\tfalse\tmodules/site/init.lua:22: bad argument 'path' (a path starts with /, got \"nope\")".to_owned(),
        ]
    );

    let hello = ask(&session, &base, get("/demo/hello/al%20ex?x=1&x=2"));
    assert_eq!((hello.status, hello.body.as_str()), (200, "hi al ex 1 GET"));
    assert_eq!(hello.header("x-mine"), Some("yes"));
    assert_eq!(hello.header("x-content-type-options"), Some("nosniff"));
    assert_eq!(hello.header("cache-control"), Some("no-store"));
    assert_eq!(
        hello.header("content-type"),
        Some("text/plain; charset=utf-8")
    );
    assert_eq!(
        ask(&session, &base, get("/demo/hello/admin")).body,
        "the admin"
    );
    assert_eq!(
        ask(&session, &base, get("/demo/files/a/b.txt")).body,
        "a/b.txt"
    );
    let head = ask(
        &session,
        &base,
        "HEAD /demo/hello/z HTTP/1.1\r\nHost: x\r\n\r\n".to_owned(),
    );
    assert_eq!((head.status, head.body.as_str()), (200, ""));

    let form = ask(
        &session,
        &base,
        post(
            "/demo/form",
            "application/x-www-form-urlencoded",
            "name=Ann+B",
        ),
    );
    assert_eq!(form.status, 201);
    assert_eq!(form.header("content-type"), Some("application/json"));
    let json: serde_json::Value = serde_json::from_str(&form.body).unwrap();
    assert_eq!(json["name"], "Ann B");
    assert_eq!(json["ip"], "127.0.0.1");
    assert_eq!(json["body"], "name=Ann+B");

    assert_eq!(ask(&session, &base, get("/demo/nothing")).status, 404);
    assert_eq!(ask(&session, &base, get("/other/hello/x")).status, 404);
    assert_eq!(ask(&session, &base, get("/demo/gone")).status, 404);
    let wrong = ask(&session, &base, post("/demo/hello/x", "text/plain", ""));
    assert_eq!(wrong.status, 405);
    assert_eq!(wrong.header("allow"), Some("GET, HEAD"));

    platform.take();
    assert_eq!(ask(&session, &base, get("/demo/broken")).status, 500);
    session.tick();
    let reported = platform.take();
    assert!(
        reported
            .iter()
            .any(|l| l.contains("GET /demo/broken") && l.contains("oops")),
        "{reported:?}"
    );

    let later = ask(&session, &base, get("/demo/later"));
    assert_eq!((later.status, later.body.as_str()), (202, "later"));
    assert_eq!(
        printed(&platform),
        ["pending\ttrue", "answered\ttrue\tfalse\tfalse"]
    );

    let go = ask(&session, &base, get("/demo/go"));
    assert_eq!(
        (go.status, go.header("location")),
        (302, Some("/demo/hello/x"))
    );
}

#[test]
fn the_gate_refuses_what_it_can_and_a_handler_too_slow_is_answered_504() {
    let module = r#"
        nf.web.route("POST", "/in", function(request, response) response:send(200, #request.body .. "") end)
        nf.web.route("GET", "/never", function() end)
    "#;
    let files = project(module);
    let platform = Rc::default();
    let config = WebConfig {
        max_body_bytes: 10,
        handler_timeout_seconds: 1,
        requests_per_minute_per_ip: 4,
        ..enabled()
    };
    let session = start(&files, &platform, config, NetHooks::default());
    let base = base(&platform.take());
    assert_eq!(
        ask(
            &session,
            &base,
            post("/demo/in", "text/plain", "0123456789")
        )
        .body,
        "10"
    );
    assert_eq!(
        ask(
            &session,
            &base,
            post("/demo/in", "text/plain", "0123456789x")
        )
        .status,
        413
    );
    let chunked = "POST /demo/in HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n";
    assert_eq!(ask(&session, &base, chunked.to_owned()).body, "5");
    let started = Instant::now();
    assert_eq!(ask(&session, &base, get("/demo/never")).status, 504);
    assert!(started.elapsed() >= Duration::from_millis(20));
    // The fifth request this minute from this address (the refused one counted too).
    let limited = ask(&session, &base, get("/demo/never"));
    assert_eq!(
        (limited.status, limited.header("retry-after")),
        (429, Some("60"))
    );
}

#[test]
fn a_signature_is_checked_in_constant_time_and_a_bad_one_is_false() {
    let module = r#"
        local body = '{"action":"opened"}'
        local signature = nf.crypto.sign(body, { secret = "s3cret", algorithm = "sha256" })
        print(nf.web.verify_signature(body, "sha256=" .. signature, { secret = "s3cret", prefix = "sha256=" }))
        print(nf.web.verify_signature(body, string.upper(signature), { secret = "s3cret" }))
        print(nf.web.verify_signature(body, signature, { secret = "wrong" }))
        print(nf.web.verify_signature(body, "zz", { secret = "s3cret" }))
        print(nf.web.verify_signature(body, signature, { secret = "s3cret", prefix = "v1=" }))
        local b64 = nf.crypto.sign(body, { secret = "s3cret", algorithm = "sha1", encoding = "base64" })
        print(nf.web.verify_signature(body, b64, { secret = "s3cret", algorithm = "sha1", encoding = "base64" }))
    "#;
    let files = project(module);
    let platform = Rc::default();
    let _session = start(&files, &platform, WebConfig::default(), NetHooks::default());
    assert_eq!(
        printed(&platform),
        ["true", "true", "false", "false", "false", "true"]
    );
}

#[test]
fn with_web_routes_off_urls_are_nil_and_the_package_is_warned() {
    let module = r#"
        nf.web.route("GET", "/x", function() end)
        print(nf.web.url("/x"))
    "#;
    let files = project(module);
    let platform = Rc::default();
    let session = start(&files, &platform, WebConfig::default(), NetHooks::default());
    assert_eq!(printed(&platform), ["nil"]);
    let problems = session.problems();
    let web: Vec<_> = problems
        .iter()
        .filter(|p| p.code.as_deref() == Some("runtime.web-disabled"))
        .collect();
    assert_eq!(web.len(), 1, "{problems:?}");
    assert_eq!(web[0].file, "netherforge.json");
    assert_eq!(web[0].path.as_deref(), Some("$.requires.web"));
}

#[test]
fn a_login_is_begun_confirmed_and_its_token_request_fails_saying_why() {
    let module = r#"
        local flow = nf.web.oauth({
            name = "discord", authorize_url = "https://discord.test/authorize?x=1", token_url = "https://auth.test/token",
            client_id = "abc", scopes = { "identify", "email" },
        })
        print(pcall(nf.web.oauth, { name = "Bad Name", authorize_url = "https://a.test/", token_url = "https://auth.test/", client_id = "x" }))
        print(pcall(nf.web.oauth, { name = "other", authorize_url = "http://a.test/", token_url = "https://auth.test/", client_id = "x" }))
        print(pcall(nf.web.oauth, { name = "other", authorize_url = "https://a.test/", token_url = "https://elsewhere.test/", client_id = "x" }))
        print(pcall(nf.web.route, "GET", "/oauth/discord/callback", function() end))
        flow:on("complete", function(e) print("complete", e.access_token) end)
        flow:on("fail", function(e) print("fail", e.player:name(), e.error) end)
        print("begin", flow:begin(nf.players.online()[1]))
    "#;
    let files = project(module);
    let platform = Rc::default();
    // The token endpoint leads to a public address; the dialer takes it to a port nothing listens on.
    let hooks = NetHooks {
        resolve: Some(Arc::new(|_: &str| {
            Ok(vec!["93.184.216.34".parse().unwrap()])
        })),
        dial: Some(Arc::new(|_: SocketAddr| {
            SocketAddr::from(([127, 0, 0, 1], 1))
        })),
        own: Some(Arc::new(Vec::new)),
    };
    let session = start(&files, &platform, enabled(), hooks);
    let started = platform.take();
    let base = base(&started);
    let said: Vec<String> = started
        .iter()
        .filter_map(|l| l.strip_prefix("info [module site] "))
        .map(|l| l.rsplit_once(" (modules/").map_or(l, |(t, _)| t).to_owned())
        .collect();
    assert_eq!(
        said[..4],
        [
            "false\tmodules/site/init.lua:6: bad argument 'options.name' (lowercase letters, digits, - and _ expected, got \"Bad Name\")".to_owned(),
            "false\tmodules/site/init.lua:7: bad argument 'options.authorize_url' (an https:// URL expected, got \"http://a.test/\")".to_owned(),
            "false\tmodules/site/init.lua:8: nf.web.oauth needs http:elsewhere.test, which package \"demo\" hasn't declared: add \"requires\": { \"http\": [\"elsewhere.test\"] } to its netherforge.json".to_owned(),
            "false\tmodules/site/init.lua:9: a login flow (nf.web.oauth) of package \"demo\" already answers GET /oauth/discord/callback".to_owned(),
        ]
    );
    let begun = said[4].strip_prefix("begin\t").expect("a URL");
    let url = url::Url::parse(begun).unwrap();
    assert_eq!(url.host_str(), Some("discord.test"));
    let query: std::collections::BTreeMap<String, String> =
        url.query_pairs().into_owned().collect();
    assert_eq!(query["x"], "1");
    assert_eq!(query["client_id"], "abc");
    assert_eq!(query["response_type"], "code");
    assert_eq!(query["scope"], "identify email");
    assert_eq!(query["code_challenge_method"], "S256");
    assert_eq!(
        query["redirect_uri"],
        format!("{base}/demo/oauth/discord/callback")
    );
    let state = &query["state"];

    // A state nobody began: refused, no event.
    let forged = ask(
        &session,
        &base,
        get("/demo/oauth/discord/callback?state=nope&code=c"),
    );
    assert_eq!(forged.status, 400);
    // The real one: the browser is asked to confirm who it signs in as.
    let page = ask(
        &session,
        &base,
        get(&format!(
            "/demo/oauth/discord/callback?state={state}&code=the-code"
        )),
    );
    assert_eq!(page.status, 200);
    assert!(page.body.contains("Sign in as Alex"), "{}", page.body);
    let token = page
        .body
        .split("name=\"token\" value=\"")
        .nth(1)
        .and_then(|t| t.split('"').next())
        .unwrap()
        .to_owned();
    // Used once.
    assert_eq!(
        ask(
            &session,
            &base,
            get(&format!(
                "/demo/oauth/discord/callback?state={state}&code=x"
            ))
        )
        .status,
        400
    );
    let confirmed = ask(
        &session,
        &base,
        post(
            "/demo/oauth/discord/confirm",
            "application/x-www-form-urlencoded",
            &format!("token={token}"),
        ),
    );
    assert_eq!(confirmed.status, 400);
    assert!(
        confirmed.body.contains("Signing in didn't work"),
        "{}",
        confirmed.body
    );
    let events = printed(&platform);
    assert_eq!(events.len(), 1, "{events:?}");
    assert!(
        events[0].starts_with("fail\tAlex\tcouldn't connect to auth.test:443"),
        "{events:?}"
    );
}
