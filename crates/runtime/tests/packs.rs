//! The resource pack on the Rust runtime: built from the project's packs, zipped the same way every time, and the
//! characters scripts and menus draw glyphs and skins with.

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet};
use std::io::Read as _;

use netherforge_format::game::GameData;
use netherforge_format::package::NoPackages;
use netherforge_format::project::ProjectSource;
use netherforge_platform::{FakePlatform, FakePlatformLog, PlatformError, PlatformLog, ServerInfo};
use netherforge_runtime::session::Session;

/// A game that reads resource pack format 75.
struct Game;

impl GameData for Game {
    fn minecraft_version(&self) -> &str {
        "26.3"
    }
    fn registry(&self, _: &str) -> Option<&BTreeSet<String>> {
        None
    }
    fn tag(&self, _: &str, _: &str) -> Option<&BTreeSet<String>> {
        None
    }
    fn item_durability(&self, _: &str) -> u32 {
        0
    }
    fn particle(&self, _: &str) -> Option<netherforge_format::game::ParticleData> {
        None
    }
    fn block_properties(&self, _: &str) -> Option<BTreeMap<String, Vec<String>>> {
        None
    }
    fn resource_pack_format(&self) -> Option<[i32; 2]> {
        Some([75, 0])
    }
}

#[derive(Default)]
struct Fake {
    lines: RefCell<Vec<String>>,
    /// Whether Alex is online.
    alex: RefCell<bool>,
}

fn alex() -> netherforge_platform::PlayerRef {
    netherforge_platform::PlayerRef {
        uuid: netherforge_platform::Uuid(77),
        name: "Alex".into(),
    }
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
        if *self.alex.borrow() {
            vec![alex()]
        } else {
            Vec::new()
        }
    }
    fn get(&self, uuid: netherforge_platform::Uuid) -> Option<netherforge_platform::PlayerRef> {
        (*self.alex.borrow() && uuid == alex().uuid).then(alex)
    }
    fn find(&self, name: String) -> Option<netherforge_platform::PlayerRef> {
        (*self.alex.borrow() && name == "Alex").then(alex)
    }
    fn known(&self, name: String) -> Option<netherforge_platform::PlayerRef> {
        (name == "Alex").then(alex)
    }
}

/// Offers said as they're sent.
impl netherforge_platform::FakeResourcePackOps for Fake {
    fn send(
        &self,
        player: netherforge_platform::Uuid,
        pack: netherforge_platform::PackOffer,
    ) -> bool {
        self.lines.borrow_mut().push(format!(
            "offer {} {} {} {} {}",
            player.0,
            if pack.id == netherforge_runtime::packs::pack_id() {
                "project".to_owned()
            } else {
                format!("{:x}", pack.id.0)
            },
            pack.url,
            pack.sha1,
            pack.replacing
        ));
        true
    }
    fn remove(&self, player: netherforge_platform::Uuid, id: netherforge_platform::Uuid) -> bool {
        self.lines
            .borrow_mut()
            .push(format!("removed {} {:x}", player.0, id.0));
        true
    }
}

/// Stacks' looks sent again: nothing to do here.
impl netherforge_platform::FakeItemViewOps for Fake {
    fn refresh(&self, _: netherforge_platform::Uuid) -> bool {
        true
    }
}

impl netherforge_platform::FakePermissionOps for Fake {
    fn apply(&self, _: netherforge_platform::Uuid, _: std::collections::HashMap<String, bool>) {}
}

impl FakePlatform for Fake {
    fn players(&self) -> &dyn netherforge_platform::PlayerOps {
        self
    }
    fn teams(&self) -> &dyn netherforge_platform::TeamOps {
        self
    }
    fn resource_packs(&self) -> &dyn netherforge_platform::ResourcePackOps {
        self
    }
    fn permissions(&self) -> &dyn netherforge_platform::PermissionOps {
        self
    }
    fn item_views(&self) -> &dyn netherforge_platform::ItemViewOps {
        self
    }
    fn game(&self) -> &dyn GameData {
        &Game
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
    names: RefCell<&'static BTreeSet<String>>,
    texts: RefCell<BTreeMap<String, String>>,
}

impl Files {
    fn new(files: &[(&str, &str)]) -> Files {
        Files {
            names: RefCell::new(Box::leak(Box::new(
                files.iter().map(|(p, _)| p.to_string()).collect(),
            ))),
            texts: RefCell::new(
                files
                    .iter()
                    .map(|(p, t)| (p.to_string(), t.to_string()))
                    .collect(),
            ),
        }
    }
    fn write(&self, path: &str, text: &str) {
        self.texts.borrow_mut().insert(path.into(), text.into());
    }
}

impl ProjectSource for Files {
    fn files(&self) -> &BTreeSet<String> {
        *self.names.borrow()
    }
    fn read(&self, path: &str) -> Option<String> {
        self.texts.borrow().get(path).cloned()
    }
}

const MANIFEST: &str = r#"{ "formatVersion": 1, "name": "Demo", "namespace": "demo", "version": "1.0.0", "minecraft": "26.3" }"#;

/// Each entry of a zip: its name and its inflated bytes, read from its local headers.
fn unzip(bytes: &[u8]) -> Vec<(String, Vec<u8>)> {
    let mut out = Vec::new();
    let mut at = 0;
    while bytes[at..at + 4] == [0x50, 0x4b, 0x03, 0x04] {
        let u16_at = |i: usize| u16::from_le_bytes([bytes[i], bytes[i + 1]]) as usize;
        let u32_at = |i: usize| u32::from_le_bytes(bytes[i..i + 4].try_into().unwrap()) as usize;
        let (compressed, name_len, extra) = (u32_at(at + 18), u16_at(at + 26), u16_at(at + 28));
        let name = String::from_utf8(bytes[at + 30..at + 30 + name_len].to_vec()).unwrap();
        let start = at + 30 + name_len + extra;
        let mut inflated = Vec::new();
        flate2::read::DeflateDecoder::new(&bytes[start..start + compressed])
            .read_to_end(&mut inflated)
            .unwrap();
        out.push((name, inflated));
        at = start + compressed;
    }
    out
}

#[test]
fn a_zip_is_the_same_bytes_for_the_same_files() {
    let files = BTreeMap::from([
        ("pack.mcmeta".to_owned(), b"{}".to_vec()),
        ("assets/a.txt".to_owned(), b"hello hello hello".to_vec()),
    ]);
    let one = netherforge_runtime::packs::zip(&files);
    let two = netherforge_runtime::packs::zip(&files);
    assert_eq!(one, two);
    assert_eq!(
        unzip(&one),
        [
            ("assets/a.txt".to_owned(), b"hello hello hello".to_vec()),
            ("pack.mcmeta".to_owned(), b"{}".to_vec()),
        ]
    );
    assert_eq!(
        netherforge_runtime::packs::sha1_hex(b"abc"),
        "a9993e364706816aba3e25717850c26c9cd0d89d"
    );
}

#[test]
fn the_pack_is_built_and_its_glyphs_drawn_until_a_pack_has_errors() {
    let pack = r#"{
        "name": "UI",
        "glyphs": { "coin": { "texture": "glyph/coin.png" } },
        "skins": { "shop": { "texture": "gui/shop.png", "height": 168, "ascent": 13, "type": "chest", "rows": 3 } }
    }"#;
    let extra = r#"{ "name": "Extra", "onDemand": true, "glyphs": { "star": { "texture": "glyph/star.png" } } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("resource_packs/ui/pack.json", pack),
        ("resource_packs/ui/textures/glyph/coin.png", "png"),
        ("resource_packs/ui/textures/gui/shop.png", "png"),
        ("resource_packs/extra/pack.json", extra),
        ("resource_packs/extra/textures/glyph/star.png", "png"),
        (
            "modules/hud/init.lua",
            r#"print(nf.text.glyph("ui/coin") ~= nil, utf8.len(nf.text.glyph("ui/coin")), nf.text.space(0), #nf.text.space(-3) > 0, pcall(nf.text.glyph, "ui/nope"))"#,
        ),
    ]);
    let platform = std::rc::Rc::new(Fake::default());
    let mut session = Session::start(platform.clone(), &files, &NoPackages);
    let started = platform.take();
    assert!(
        !started
            .iter()
            .any(|l| l.starts_with("warn") || l.starts_with("error")),
        "{started:?}"
    );
    let printed: Vec<&String> = started
        .iter()
        .filter(|l| l.starts_with("info [module"))
        .collect();
    assert_eq!(printed.len(), 1);
    assert!(
        printed[0].starts_with("info [module hud] true\t1\t\ttrue\tfalse\t"),
        "{printed:?}"
    );
    assert!(
        started
            .iter()
            .any(|l| l.starts_with("info Built the resource pack from 1 resource pack(s)")),
        "{started:?}"
    );
    let built = session
        .shared()
        .packs
        .borrow()
        .built
        .clone()
        .expect("built");
    let entries: Vec<String> = unzip(&built.bytes).into_iter().map(|(n, _)| n).collect();
    assert!(entries.contains(&"pack.mcmeta".to_owned()), "{entries:?}");
    assert!(
        session
            .shared()
            .packs
            .borrow()
            .on_demand
            .contains_key("extra")
    );
    let skin = session
        .shared()
        .packs
        .borrow()
        .skin_title("ui/shop", 0)
        .expect("a skin");
    assert!(skin.starts_with("<white><font:demo:ui/gui>"), "{skin}");

    // A pack with errors: the last good build stays, and so do its characters.
    files.write(
        "resource_packs/ui/pack.json",
        r#"{ "glyphs": { "coin": { "texture": 3 } } }"#,
    );
    session.reload(&["resource_packs/ui/pack.json".to_owned()]);
    let reloaded = platform.take();
    assert!(
        reloaded
            .iter()
            .any(|l| l
                == "warn A resource pack has errors; players keep the last good resource pack"),
        "{reloaded:?}"
    );
    assert_eq!(
        session
            .shared()
            .packs
            .borrow()
            .built
            .as_ref()
            .map(|b| b.sha1.clone()),
        Some(built.sha1)
    );
    assert!(
        session
            .shared()
            .packs
            .borrow()
            .skin_title("ui/shop", 0)
            .is_some()
    );
}

/// What an HTTP GET (or another method) of `url` answers: the status line and the body.
fn fetch(method: &str, url: &str) -> (String, Vec<u8>) {
    use std::io::Write as _;
    let rest = url.strip_prefix("http://").unwrap();
    let (host, path) = rest.split_once('/').unwrap();
    let mut stream = std::net::TcpStream::connect(host).unwrap();
    write!(stream, "{method} /{path} HTTP/1.1\r\nHost: {host}\r\n\r\n").unwrap();
    let mut answer = Vec::new();
    stream.read_to_end(&mut answer).unwrap();
    let split = answer.windows(4).position(|w| w == b"\r\n\r\n").unwrap();
    let head = String::from_utf8_lossy(&answer[..split]).to_string();
    (
        head.lines().next().unwrap().to_owned(),
        answer[split + 4..].to_vec(),
    )
}

#[test]
fn the_pack_is_served_sent_pushed_and_its_status_heard() {
    use netherforge_platform::PlatformEvents;
    let pack = r#"{ "name": "UI", "glyphs": { "coin": { "texture": "glyph/coin.png" } } }"#;
    let extra = r#"{ "name": "Extra", "onDemand": true, "glyphs": { "star": { "texture": "glyph/star.png" } } }"#;
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        ("resource_packs/ui/pack.json", pack),
        ("resource_packs/ui/textures/glyph/coin.png", "png"),
        ("resource_packs/extra/pack.json", extra),
        ("resource_packs/extra/textures/glyph/star.png", "png"),
        (
            "modules/packs/init.lua",
            r#"
nf.on("player_resource_pack_status", function(e) print("status", e.player:name(), e.pack, e.status) end)
nf.on("demo:push", function()
  local alex = nf.players.get("Alex")
  print(table.concat(nf.packs.on_demand(), " "), alex:push_resource_pack("extra"), pcall(alex.push_resource_pack, alex, "ui"))
end)
nf.on("demo:pop", function()
  local alex = nf.players.get("Alex")
  print(alex:resource_pack_status(), alex:resource_pack_status("extra"), alex:pop_resource_pack("extra"), alex:pop_resource_pack("extra"))
end)
"#,
        ),
    ]);
    let platform = std::rc::Rc::new(Fake::default());
    *platform.alex.borrow_mut() = true;
    let events = netherforge_runtime::server::ServerEvents::new(Session::start(
        platform.clone(),
        &files,
        &NoPackages,
    ));
    let started = platform.take();
    let offers: Vec<&String> = started.iter().filter(|l| l.starts_with("offer")).collect();
    assert_eq!(offers.len(), 1, "{started:?}");
    let parts: Vec<&str> = offers[0].split(' ').collect();
    assert_eq!((parts[1], parts[2], parts[5]), ("77", "project", "true"));
    let (url, sha1) = (parts[3].to_owned(), parts[4].to_owned());
    assert!(
        url.starts_with("http://127.0.0.1:") && url.ends_with(&format!("/{sha1}.zip")),
        "{url}"
    );

    // The server serves the zip, and nothing else.
    let built = events
        .session()
        .shared()
        .packs
        .borrow()
        .built
        .clone()
        .unwrap();
    assert_eq!(
        fetch("GET", &url),
        ("HTTP/1.1 200 OK".to_owned(), built.bytes.to_vec())
    );
    assert_eq!(fetch("HEAD", &url).0, "HTTP/1.1 200 OK");
    assert_eq!(
        fetch("GET", &url.replace(&sha1, &"0".repeat(40))).0,
        "HTTP/1.1 404 Not Found"
    );
    assert_eq!(fetch("POST", &url).0, "HTTP/1.1 405 Method Not Allowed");

    // Joining again with this build sends nothing; a changed pack is sent, replacing only the project's.
    events.session().player_joined(&alex());
    assert!(!platform.take().iter().any(|l| l.starts_with("offer")));
    files.write(
        "resource_packs/ui/pack.json",
        &pack.replace(
            r#""texture": "glyph/coin.png""#,
            r#""texture": "glyph/coin.png", "height": 9"#,
        ),
    );
    events
        .session_mut()
        .reload(&["resource_packs/ui/pack.json".to_owned()]);
    let resent: Vec<String> = platform
        .take()
        .into_iter()
        .filter(|l| l.starts_with("offer"))
        .collect();
    assert_eq!(resent.len(), 1, "{resent:?}");
    assert!(resent[0].ends_with(" false"), "{resent:?}");

    // A script pushes the onDemand pack; the client says it loaded both.
    let emit = |event: &str| {
        let session = events.session();
        let room = session.room().unwrap();
        room.scripts()
            .emit_custom(
                netherforge_runtime::Target::Nf,
                event.into(),
                mlua::Value::Table(room.host().lua().create_table().unwrap()),
            )
            .unwrap();
    };
    emit("demo:push");
    events.tick();
    let pushed = platform.take();
    let id = format!("{:x}", netherforge_runtime::packs::on_demand_id("extra").0);
    assert!(
        pushed
            .iter()
            .any(|l| l.starts_with(&format!("offer 77 {id} "))),
        "{pushed:?}"
    );
    assert!(
        pushed.iter().any(|l| l.contains("extra\ttrue\tfalse\t")
            && l.contains("resource pack \"ui\" isn't onDemand")),
        "{pushed:?}"
    );
    events.resource_pack_status(
        alex().uuid,
        netherforge_runtime::packs::pack_id(),
        Some("loaded".into()),
    );
    events.resource_pack_status(
        alex().uuid,
        netherforge_runtime::packs::on_demand_id("extra"),
        Some("loaded".into()),
    );
    events.resource_pack_status(
        alex().uuid,
        netherforge_platform::Uuid(5),
        Some("loaded".into()),
    );
    emit("demo:pop");
    events.tick();
    let heard: Vec<String> = platform
        .take()
        .into_iter()
        .filter_map(|l| {
            l.strip_prefix("info [module packs] ")
                .map(|l| l.rsplit_once(" (").map_or(l, |(t, _)| t).to_owned())
        })
        .collect();
    assert_eq!(
        heard,
        [
            "status\tAlex\tnil\tloaded",
            "status\tAlex\textra\tloaded",
            "loaded\tloaded\ttrue\tfalse",
        ]
    );
}

#[test]
fn an_owners_url_is_sent_and_the_zip_written_for_them() {
    let dir = std::env::temp_dir().join(format!("nf-pack-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    let files = Files::new(&[
        ("netherforge.json", MANIFEST),
        (
            "resource_packs/ui/pack.json",
            r#"{ "glyphs": { "coin": { "texture": "glyph/coin.png" } } }"#,
        ),
        ("resource_packs/ui/textures/glyph/coin.png", "png"),
    ]);
    let platform = std::rc::Rc::new(Fake::default());
    *platform.alex.borrow_mut() = true;
    let config = netherforge_runtime::session::Config {
        pack: netherforge_runtime::packs::PackConfig {
            external_url: Some("https://cdn.example/pack.zip".into()),
            state_dir: Some(dir.clone()),
            required: true,
            ..Default::default()
        },
        ..Default::default()
    };
    let session = Session::start_configured(
        platform.clone(),
        &files,
        &NoPackages,
        std::rc::Rc::new(netherforge_runtime::store::Store::in_memory()),
        config,
    );
    let offers: Vec<String> = platform
        .take()
        .into_iter()
        .filter(|l| l.starts_with("offer"))
        .collect();
    let built = session.shared().packs.borrow().built.clone().unwrap();
    assert_eq!(
        offers,
        [format!(
            "offer 77 project https://cdn.example/pack.zip {} true",
            built.sha1
        )]
    );
    assert_eq!(
        std::fs::read(dir.join("resource-pack.zip")).unwrap(),
        *built.bytes
    );
    let _ = std::fs::remove_dir_all(&dir);
}
