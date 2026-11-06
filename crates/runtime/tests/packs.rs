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

impl FakePlatform for Fake {
    fn players(&self) -> &dyn netherforge_platform::PlayerOps {
        self
    }
    fn teams(&self) -> &dyn netherforge_platform::TeamOps {
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
