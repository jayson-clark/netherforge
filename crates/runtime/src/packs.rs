//! The project's resource pack (Kotlin's `Packs`, `PackZip` and `PackService`): every pack under `resource_packs/`
//! built into one zip with format's `PackLayout` (`build::build`, so the editor's preview and the zip agree on every
//! file), zipped so the same files always make the same bytes, and the glyph and skin characters scripts and menus
//! draw with. A pack with errors keeps the last good build (and its characters) running. `onDemand` packs each go
//! into a zip of their own, for scripts to push.
//!
//! The build lives in `Shared`, which outlives a full restart of the session's project, so a reload that changes
//! nothing changes no hash, and what each player's client was sent and said is kept with it. Players fetch a zip
//! from the plugin's own HTTP server (`PackServer`) at `<base>/<sha1>.zip`, or from a URL the server owner serves it
//! at (the plugin writes `resource-pack.zip` for them); they get it as they join and again only when the hash has
//! changed. Not yet: an S3 store (it waits for the HTTP client), and what the network knows a player loaded.

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::io::Write as _;
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use netherforge_format::Problem;
use netherforge_format::kind::resource_pack::build::{self, Entry, VideoSound};
use netherforge_format::kind::resource_pack::compiled::CompiledResourcePack;
use netherforge_format::reference::{RefKind, ResourceKey, resolve_any};
use netherforge_platform::{PackOffer, Platform, Uuid};
use sha1::Digest as _;

use crate::Room;
use crate::session::{Define, Service};
use crate::shared::Shared;

/// 2000-01-01 00:00 as DOS time and date: comfortably inside the DOS range in every time zone.
const DOS_TIME: u16 = 0;
const DOS_DATE: u16 = (20 << 9) | (1 << 5) | 1;

/// Zips a resource pack so the same files always make the same bytes (Kotlin's `PackZip`): entries in path
/// order, every one with the same date, deflated at one level, no extra fields, no comment. The SHA-1 is what tells
/// a client it already has a pack, so anything in the zip that isn't content would cost every player a download.
pub fn zip(files: &BTreeMap<String, Vec<u8>>) -> Vec<u8> {
    let mut out: Vec<u8> = Vec::new();
    let mut central: Vec<u8> = Vec::new();
    for (path, bytes) in files {
        let crc = crc32fast::hash(bytes);
        let mut deflater =
            flate2::write::DeflateEncoder::new(Vec::new(), flate2::Compression::default());
        deflater.write_all(bytes).expect("writing to memory");
        let deflated = deflater.finish().expect("writing to memory");
        let offset = out.len() as u32;
        let name = path.as_bytes();
        let header = |sig: u32, central: bool| {
            let mut h = Vec::new();
            h.extend_from_slice(&sig.to_le_bytes());
            if central {
                h.extend_from_slice(&20u16.to_le_bytes()); // made by
            }
            h.extend_from_slice(&20u16.to_le_bytes()); // needed to extract
            h.extend_from_slice(&0x0800u16.to_le_bytes()); // names are UTF-8
            h.extend_from_slice(&8u16.to_le_bytes()); // deflated
            h.extend_from_slice(&DOS_TIME.to_le_bytes());
            h.extend_from_slice(&DOS_DATE.to_le_bytes());
            h.extend_from_slice(&crc.to_le_bytes());
            h.extend_from_slice(&(deflated.len() as u32).to_le_bytes());
            h.extend_from_slice(&(bytes.len() as u32).to_le_bytes());
            h.extend_from_slice(&(name.len() as u16).to_le_bytes());
            h.extend_from_slice(&0u16.to_le_bytes()); // extra
            if central {
                h.extend_from_slice(&0u16.to_le_bytes()); // comment
                h.extend_from_slice(&0u16.to_le_bytes()); // disk
                h.extend_from_slice(&0u16.to_le_bytes()); // internal attributes
                h.extend_from_slice(&0u32.to_le_bytes()); // external attributes
                h.extend_from_slice(&offset.to_le_bytes());
            }
            h.extend_from_slice(name);
            h
        };
        out.extend(header(0x0403_4b50, false));
        out.extend_from_slice(&deflated);
        central.extend(header(0x0201_4b50, true));
    }
    let start = out.len() as u32;
    let count = files.len() as u16;
    out.extend_from_slice(&central);
    out.extend_from_slice(&0x0605_4b50u32.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());
    out.extend_from_slice(&count.to_le_bytes());
    out.extend_from_slice(&count.to_le_bytes());
    out.extend_from_slice(&(central.len() as u32).to_le_bytes());
    out.extend_from_slice(&start.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());
    out
}

/// A SHA-1 as lowercase hex, as Minecraft's pack hash is written.
pub fn sha1_hex(bytes: &[u8]) -> String {
    sha1::Sha1::digest(bytes)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

/// One build: the zip, its hash and where players fetch it (none when packs aren't delivered).
#[derive(Clone, Debug)]
pub struct Built {
    pub bytes: Arc<Vec<u8>>,
    pub sha1: String,
    pub url: Option<String>,
}

/// Where the resource pack is served from (`resource-pack:` in `config.yml`).
#[derive(Clone, Debug)]
pub struct PackConfig {
    /// Off: nothing is sent to players (the pack is still built, so glyphs and skins still resolve).
    pub enabled: bool,
    /// The address the built-in server listens on.
    pub bind: String,
    /// Its port; 0 picks a free one.
    pub port: u16,
    /// The base URL players fetch from, when it isn't `http://<bind>:<port>` (a public host name, a proxy).
    pub public_url: Option<String>,
    /// Serve it yourself from this URL: the plugin writes `resource-pack.zip` to its folder and sends this with its hash.
    pub external_url: Option<String>,
    /// Players who decline it are disconnected.
    pub required: bool,
    /// MiniMessage shown on the client's prompt.
    pub prompt: Option<String>,
    /// Where `resource-pack.zip` is written for an owner who serves it.
    pub state_dir: Option<PathBuf>,
}

impl Default for PackConfig {
    fn default() -> PackConfig {
        PackConfig {
            enabled: true,
            bind: "127.0.0.1".into(),
            port: 0,
            public_url: None,
            external_url: None,
            required: false,
            prompt: None,
            state_dir: None,
        }
    }
}

/// What the game says of a pack it has loaded.
pub const LOADED: &str = "loaded";

/// Java's `UUID.nameUUIDFromBytes`: a version 3 UUID, MD5 of the bytes.
pub fn name_uuid(bytes: &[u8]) -> Uuid {
    use md5::Digest as _;
    let mut hash: [u8; 16] = md5::Md5::digest(bytes).into();
    hash[6] = (hash[6] & 0x0f) | 0x30;
    hash[8] = (hash[8] & 0x3f) | 0x80;
    Uuid(u128::from_be_bytes(hash))
}

/// One pack per server, sent under one id, so a new one replaces the old on the client.
pub fn pack_id() -> Uuid {
    name_uuid(b"netherforge:resource-pack")
}

/// The id `onDemand` pack `name` is sent under, so a rebuild replaces it on the client and popping finds it.
pub fn on_demand_id(name: &str) -> Uuid {
    name_uuid(format!("netherforge:resource-pack:{name}").as_bytes())
}

type Served = Arc<Mutex<BTreeMap<String, Vec<(String, Arc<Vec<u8>>)>>>>;

/// The plugin's own HTTP server (Kotlin's `PackServer`): serving each pack at `GET /<sha1>.zip`, from a thread of
/// its own and one per request. It keeps the current build and the one before it of each pack it serves, so a
/// player who started downloading just before a rebuild still gets their bytes. Web routes come with `nf.web`.
pub struct PackServer {
    pub host: String,
    pub port: u16,
    packs: Served,
    /// Where requests that aren't for a pack go: the web routes' door, while they're on.
    web: WebSlot,
    stopping: Arc<AtomicBool>,
}

type WebSlot = Arc<Mutex<Option<Arc<crate::web::gate::WebGate>>>>;

/// Requests answered at once, at most: past it a request is answered `503` without being read.
const MAX_IN_FLIGHT: usize = 256;

impl PackServer {
    pub fn start(bind: &str, port: u16) -> std::io::Result<PackServer> {
        let listener = TcpListener::bind((bind, port))?;
        let address = listener.local_addr()?;
        let host = if address.ip().is_unspecified() {
            "127.0.0.1".to_owned()
        } else {
            address.ip().to_string()
        };
        let packs: Served = Arc::default();
        let web: WebSlot = Arc::default();
        let stopping = Arc::new(AtomicBool::new(false));
        let (served, stop, routes) = (packs.clone(), stopping.clone(), web.clone());
        let in_flight = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        std::thread::Builder::new()
            .name("NetherForge HTTP server".into())
            .spawn(move || {
                for stream in listener.incoming() {
                    if stop.load(Ordering::SeqCst) {
                        break;
                    }
                    let Ok(mut stream) = stream else { continue };
                    if in_flight.load(Ordering::SeqCst) >= MAX_IN_FLIGHT {
                        let busy = crate::web::gate::WebAnswer::text(
                            503,
                            "The server is busy: try again shortly.",
                            &[("retry-after", "5")],
                        );
                        crate::web::gate::write_answer(&mut stream, &busy, false);
                        continue;
                    }
                    let (served, routes, in_flight) =
                        (served.clone(), routes.clone(), in_flight.clone());
                    in_flight.fetch_add(1, Ordering::SeqCst);
                    let _ = std::thread::Builder::new()
                        .name("NetherForge HTTP request".into())
                        .spawn(move || {
                            answer(stream, &served, &routes);
                            in_flight.fetch_sub(1, Ordering::SeqCst);
                        });
                }
            })?;
        Ok(PackServer {
            host,
            port: address.port(),
            packs,
            web,
            stopping,
        })
    }

    /// Sends requests that aren't for a pack to `gate` (none: they're `404`).
    pub fn set_web(&self, gate: Option<Arc<crate::web::gate::WebGate>>) {
        *self.web.lock().unwrap_or_else(|e| e.into_inner()) = gate;
    }

    /// Serves `bytes` as pack `name`'s current build (`""`: the project's), keeping the one before it.
    pub fn serve(&self, sha1: &str, bytes: Arc<Vec<u8>>, name: &str) {
        let mut packs = self.packs.lock().expect("not poisoned");
        let builds = packs.entry(name.to_owned()).or_default();
        builds.retain(|(s, _)| s != sha1);
        builds.insert(0, (sha1.to_owned(), bytes));
        builds.truncate(2);
    }
}

impl Drop for PackServer {
    fn drop(&mut self) {
        self.stopping.store(true, Ordering::SeqCst);
        // Wakes the accepting thread so it sees it's stopping.
        let _ = TcpStream::connect((self.host.as_str(), self.port));
    }
}

/// One request: a pack's bytes (`/<sha1>.zip`), a web route's answer while web routes are on, or 404 (405 for a
/// pack asked for with a method that isn't GET or HEAD).
fn answer(mut stream: TcpStream, served: &Served, web: &WebSlot) {
    let _ = stream.set_read_timeout(Some(std::time::Duration::from_secs(10)));
    let Some((method, target, headers, rest)) = crate::web::gate::read_head(&mut stream) else {
        return;
    };
    let path = target.split('?').next().unwrap_or_default();
    let name = path
        .strip_prefix('/')
        .and_then(|p| p.strip_suffix(".zip"))
        .filter(|n| n.len() == 40 && n.bytes().all(|b| b.is_ascii_hexdigit()));
    let gate = web.lock().ok().and_then(|g| g.clone());
    if name.is_none()
        && let Some(gate) = gate
    {
        gate.accept(stream, &method, &target, headers, rest);
        return;
    }
    let bytes = name.and_then(|sha1| {
        let packs = served.lock().ok()?;
        packs.values().find_map(|builds| {
            builds
                .iter()
                .find(|(s, _)| s == sha1)
                .map(|(_, b)| b.clone())
        })
    });
    let head = |status: &str, length: Option<usize>| {
        let length = length.map_or(String::new(), |l| {
            format!("Content-Type: application/zip\r\nContent-Length: {l}\r\n")
        });
        format!("HTTP/1.1 {status}\r\n{length}Connection: close\r\n\r\n")
    };
    let _ = match (method.as_str(), bytes) {
        ("GET" | "HEAD", None) => stream.write_all(head("404 Not Found", Some(0)).as_bytes()),
        ("GET", Some(bytes)) => stream
            .write_all(head("200 OK", Some(bytes.len())).as_bytes())
            .and_then(|()| stream.write_all(&bytes)),
        ("HEAD", Some(bytes)) => stream.write_all(head("200 OK", Some(bytes.len())).as_bytes()),
        _ => stream.write_all(head("405 Method Not Allowed", Some(0)).as_bytes()),
    };
    let _ = stream.flush();
}

/// The pack as last built, what scripts and menus read of it, and what each player's client was sent and said.
#[derive(Default)]
pub struct Packs {
    pub config: PackConfig,
    server: Option<PackServer>,
    /// Whether the server couldn't start: said once.
    server_failed: bool,
    pub built: Option<Built>,
    /// Every pack the tree has, by the name the server knows it by, built or not.
    pub pack_files: BTreeSet<String>,
    /// The packs whose file says `onDemand`, built or not.
    pub on_demand_files: BTreeSet<String>,
    /// The project's pack's hash each online player was sent.
    sent: HashMap<Uuid, String>,
    /// The `onDemand` packs pushed to each online player, and the hash each was sent at.
    pushed: HashMap<Uuid, BTreeMap<String, String>>,
    /// What each online player's game said last about each of our packs, by the pack's id.
    statuses: HashMap<Uuid, HashMap<Uuid, String>>,
    /// Asked for by `nf.packs.rebuild`: the packs are read again at the end of the tick.
    pub wanted: bool,
    /// Each `onDemand` pack's own build, by the pack's name as the server knows it (`featured`, `library:featured`).
    pub on_demand: BTreeMap<String, Built>,
    compiled: BTreeMap<String, CompiledResourcePack>,
    /// The project's namespace, the last load's: what a pack entry's name resolves in.
    home: Option<String>,
    /// Unknown `<glyph:…>` references already reported, each once per build.
    reported_glyphs: BTreeSet<String>,
    /// Every glyph character's advance by code point, worked out from the compiled packs.
    glyph_chars: Option<BTreeMap<u32, i32>>,
}

impl Packs {
    /// Rebuilds from `project`: what's wrong with the build that the format didn't already say (the server not
    /// knowing its pack format). With no packs, blocks or videos in the tree there's no pack.
    pub fn rebuild(&mut self, platform: &dyn Platform, project: &Define) -> Vec<Problem> {
        let loaded = project.project;
        self.home = Some(loaded.namespace().to_owned());
        self.reported_glyphs.clear();
        self.pack_files = loaded
            .present("resource_pack")
            .into_iter()
            .map(|(name, _)| name)
            .collect();
        self.on_demand_files = loaded
            .present("resource_pack")
            .into_iter()
            .filter(|(_, r)| {
                r.model::<netherforge_format::kind::resource_pack::ResourcePackFile>()
                    .is_some_and(|m| m.on_demand == Some(true))
            })
            .map(|(name, _)| name)
            .collect();
        if !build::needed(loaded) {
            self.compiled.clear();
            self.glyph_chars = None;
            self.built = None;
            self.on_demand.clear();
            return Vec::new();
        }
        // A pack with errors isn't compiled: keep running the last good one.
        if !loaded.present("resource_pack").is_empty() && loaded.compiled_resource_packs.is_empty()
        {
            return Vec::new();
        }
        self.compiled = loaded.compiled_resource_packs.clone();
        self.settle_glyph_chars();
        let Some(format) = platform.game().resource_pack_format() else {
            let message = "This server doesn't say which resource pack format it uses, so no resource pack was built";
            platform.log().warn(message.to_owned());
            return vec![netherforge_format::codes::RUNTIME_PACK_FORMAT.at(
                "resource_packs",
                message,
                None,
            )];
        };
        let description = loaded
            .manifest
            .as_ref()
            .map(|m| m.name.clone())
            .filter(|n| !n.trim().is_empty())
            .unwrap_or_else(|| "NetherForge".into());
        let plan = blocks_plan(platform, project);
        let videos = VideoSound::of(loaded);
        // Its own copy: publishing a zip changes what's served while the packs are walked.
        let compiled = loaded.compiled_resource_packs.clone();
        let (separate, joined): (Vec<&CompiledResourcePack>, Vec<&CompiledResourcePack>) =
            compiled.values().partition(|p| p.on_demand);
        let read = |path: &str| read_bytes(project, path);
        let size = |path: &str| read(path).and_then(|b| png_size(&b));
        let bytes = zip(&files(
            &build::build(
                &joined,
                format,
                &description,
                plan.as_ref(),
                loaded.vanilla.as_ref(),
                &videos,
                &size,
            ),
            &read,
        ));
        let mut on_demand = BTreeMap::new();
        for pack in separate {
            let own_description = pack
                .description
                .clone()
                .or(pack.name.clone())
                .unwrap_or(pack.key.clone());
            let own = zip(&files(
                &build::build(&[pack], format, &own_description, None, None, &[], &size),
                &read,
            ));
            let sha1 = sha1_hex(&own);
            let kept = self
                .on_demand
                .get(&pack.key)
                .filter(|b| b.sha1 == sha1)
                .cloned();
            let built = match kept {
                Some(kept) => kept,
                None => {
                    let bytes = Arc::new(own);
                    let url = self.publish(platform, &bytes, &sha1, &pack.key);
                    Built { bytes, sha1, url }
                }
            };
            on_demand.insert(pack.key.clone(), built);
        }
        self.on_demand = on_demand;
        let sha1 = sha1_hex(&bytes);
        if self.built.as_ref().is_some_and(|b| b.sha1 == sha1) {
            return Vec::new();
        }
        platform.log().info(format!(
            "Built the resource pack from {} resource pack(s), {} bytes, SHA-1 {sha1}",
            joined.len(),
            bytes.len()
        ));
        let bytes = Arc::new(bytes);
        let url = self.publish(platform, &bytes, &sha1, "");
        self.built = Some(Built { bytes, sha1, url });
        Vec::new()
    }

    /// Makes `bytes` fetchable and answers the URL, or none when packs aren't delivered. `name` is an `onDemand`
    /// pack's, which only the plugin's own server can serve (an owner's URL is for the project's one zip).
    fn publish(
        &mut self,
        platform: &dyn Platform,
        bytes: &Arc<Vec<u8>>,
        sha1: &str,
        name: &str,
    ) -> Option<String> {
        if !self.config.enabled {
            return None;
        }
        if name.is_empty()
            && let Some(url) = self.config.external_url.clone()
        {
            // The owner serves it: give them the file to upload.
            if let Some(dir) = &self.config.state_dir {
                let file = dir.join("resource-pack.zip");
                let temp = dir.join("resource-pack.zip.tmp");
                let written = std::fs::create_dir_all(dir)
                    .and_then(|()| std::fs::write(&temp, bytes.as_slice()))
                    .and_then(|()| std::fs::rename(&temp, &file));
                if let Err(e) = written {
                    platform
                        .log()
                        .error(format!("Couldn't write {}: {e}", file.display()), None);
                }
            }
            return Some(url);
        }
        let public = self.config.public_url.clone();
        let server = self.listener(platform)?;
        server.serve(sha1, bytes.clone(), name);
        let base = public.unwrap_or_else(|| format!("http://{}:{}", server.host, server.port));
        Some(format!("{}/{sha1}.zip", base.trim_end_matches('/')))
    }

    /// The plugin's HTTP server, if it's running.
    pub fn server(&self) -> Option<&PackServer> {
        self.server.as_ref()
    }

    /// The plugin's HTTP server, started if it isn't yet; none when it couldn't start (said once).
    pub fn listener(&mut self, platform: &dyn Platform) -> Option<&PackServer> {
        if self.server.is_none() && !self.server_failed {
            match PackServer::start(&self.config.bind, self.config.port) {
                Ok(server) => self.server = Some(server),
                Err(e) => {
                    self.server_failed = true;
                    platform.log().error(
                        format!(
                            "Couldn't start NetherForge's HTTP server on {}:{}: {e}",
                            self.config.bind, self.config.port
                        ),
                        None,
                    );
                }
            }
        }
        self.server.as_ref()
    }

    /// Sends the current pack to `player` unless they already have this one. The first send takes every other pack
    /// they have off (what another server of a network sent); later ones replace only the project's.
    pub fn send(&mut self, platform: &dyn Platform, player: Uuid) -> bool {
        let Some(pack) = &self.built else {
            return false;
        };
        let Some(url) = pack.url.clone() else {
            return false;
        };
        if self.sent.get(&player) == Some(&pack.sha1) {
            return false;
        }
        let first = !self.sent.contains_key(&player);
        let offer = PackOffer {
            id: pack_id(),
            url,
            sha1: pack.sha1.clone(),
            required: self.config.required,
            prompt: self.config.prompt.clone(),
            replacing: first,
        };
        if !platform.resource_packs().send(player, offer) {
            return false;
        }
        self.sent.insert(player, pack.sha1.clone());
        true
    }

    /// Sends the current packs to everyone online who doesn't have them: how many players that was.
    pub fn send_all(&mut self, platform: &dyn Platform) -> usize {
        let mut count = 0;
        for player in platform.players().online() {
            let main = self.send(platform, player.uuid);
            let names: Vec<String> = self
                .pushed
                .get(&player.uuid)
                .map(|p| p.keys().cloned().collect())
                .unwrap_or_default();
            let extra = names
                .iter()
                .filter(|n| self.push(platform, player.uuid, n))
                .count();
            if main || extra > 0 {
                count += 1;
            }
        }
        count
    }

    /// Sends `player` `onDemand` pack `name` beside their others, unless they have this build. False when there's
    /// no such build, it isn't served, or they're offline.
    pub fn push(&mut self, platform: &dyn Platform, player: Uuid, name: &str) -> bool {
        let Some(pack) = self.on_demand.get(name) else {
            return false;
        };
        let Some(url) = pack.url.clone() else {
            return false;
        };
        if self.pushed.get(&player).and_then(|p| p.get(name)) == Some(&pack.sha1) {
            return true;
        }
        let offer = PackOffer {
            id: on_demand_id(name),
            url,
            sha1: pack.sha1.clone(),
            required: false,
            prompt: None,
            replacing: false,
        };
        if !platform.resource_packs().send(player, offer) {
            return false;
        }
        let sha1 = pack.sha1.clone();
        self.pushed
            .entry(player)
            .or_default()
            .insert(name.to_owned(), sha1);
        true
    }

    /// Takes `onDemand` pack `name` off `player`'s game. False when it wasn't pushed to them, or they're offline.
    pub fn pop(&mut self, platform: &dyn Platform, player: Uuid, name: &str) -> bool {
        if self
            .pushed
            .get_mut(&player)
            .and_then(|p| p.remove(name))
            .is_none()
        {
            return false;
        }
        if let Some(statuses) = self.statuses.get_mut(&player) {
            statuses.remove(&on_demand_id(name));
        }
        platform.resource_packs().remove(player, on_demand_id(name))
    }

    /// A player left: next time they join they get it again, and nothing that was pushed.
    pub fn forget(&mut self, player: Uuid) {
        self.sent.remove(&player);
        self.statuses.remove(&player);
        self.pushed.remove(&player);
    }

    /// What `player`'s game said last about the project's pack, or `onDemand` pack `name`.
    pub fn status(&self, player: Uuid, name: Option<&str>) -> Option<String> {
        let id = name.map_or_else(pack_id, on_demand_id);
        self.statuses.get(&player)?.get(&id).cloned()
    }

    /// `player`'s game says `status` about `pack` (none: it forgot). Only our packs' are kept; what to tell scripts
    /// (the `onDemand` pack's name, or none for the project's, and the status), when there's anything to.
    pub fn status_changed(
        &mut self,
        player: Uuid,
        pack: Uuid,
        status: Option<String>,
    ) -> Option<(Option<String>, String)> {
        let name = if pack == pack_id() {
            None
        } else {
            Some(
                self.on_demand
                    .keys()
                    .find(|n| on_demand_id(n) == pack)?
                    .clone(),
            )
        };
        let Some(status) = status else {
            if let Some(statuses) = self.statuses.get_mut(&player) {
                statuses.remove(&pack);
            }
            return None;
        };
        self.statuses
            .entry(player)
            .or_default()
            .insert(pack, status.clone());
        Some((name, status))
    }

    /// `reference`, an entry of `kind`'s name, as a key; none when it isn't shaped like one.
    fn key_of(&self, kind: RefKind, reference: &str) -> Option<ResourceKey> {
        let key = resolve_any(self.home.as_deref()?, reference)?;
        kind.is_path(&key.path).then_some(key)
    }

    /// The built pack `key` (an entry's, `shop:ui/coin`) is in: the project's by id, a package's as `acme:ui`.
    fn pack_of(&self, key: &ResourceKey) -> Option<&CompiledResourcePack> {
        let home = self.home.as_deref()?;
        let pack = ResourceKey {
            namespace: key.namespace.clone(),
            path: key.pack().to_owned(),
        }
        .relative_to(home);
        self.compiled.get(&pack)
    }

    /// The character glyph `key` is drawn as; none while the packs aren't built.
    pub fn glyph(&self, key: &ResourceKey) -> Option<String> {
        Some(self.pack_of(key)?.glyphs.get(key.key())?.char.clone())
    }

    /// What a `<glyph:ui/coin>` tag inserts: the glyph's character, or none (nothing) while the packs aren't built
    /// or for a glyph no pack has, which is reported once as a warning.
    pub fn text_glyph(&mut self, platform: &dyn Platform, reference: &str) -> Option<String> {
        let glyph = self
            .key_of(RefKind::Glyph, reference)
            .and_then(|k| self.glyph(&k));
        if glyph.is_none()
            && self.home.is_some()
            && self.reported_glyphs.insert(reference.to_owned())
        {
            platform.log().warn(format!(
                "<glyph:{reference}> names no glyph in the project's resource packs (a glyph is <pack>/<key>, like ui/coin), so it shows nothing"
            ));
        }
        glyph
    }

    /// The MiniMessage that draws a skin at the front of a title: the skin's characters in the pack's `gui` font,
    /// in white so the picture isn't tinted by the title's colour. None when the packs don't have it built.
    pub fn skin_title(&self, reference: &str, shift: i32) -> Option<String> {
        let key = self.key_of(RefKind::Skin, reference)?;
        let pack = self.pack_of(&key)?;
        let skin = pack.skins.get(key.key())?;
        Some(format!(
            "<white><font:{}>{}</font></white>",
            pack.gui_font(),
            skin.title_prefix(shift)
        ))
    }

    /// A glyph's advance in text (`<glyph:ui/coin>`), for measuring it; none when unknown.
    pub fn glyph_advance(&self, reference: &str) -> Option<i32> {
        let key = self.key_of(RefKind::Glyph, reference)?;
        self.pack_of(&key)?.glyphs.get(key.key())?.advance
    }

    /// How far a glyph's bare character moves the cursor, for measuring text that holds it rather than its tag;
    /// none for a character that isn't one. Every pack's, `onDemand` ones' too: glyphs are numbered across them.
    pub fn glyph_char_advance(&self, code_point: u32) -> Option<i32> {
        self.glyph_chars.as_ref()?.get(&code_point).copied()
    }

    /// Every glyph character's advance by code point, worked out once per build.
    fn settle_glyph_chars(&mut self) {
        let mut chars = BTreeMap::new();
        for pack in self.compiled.values() {
            for glyph in pack.glyphs.values() {
                let Some(advances) = &glyph.cell_advances else {
                    continue;
                };
                for (c, advance) in glyph.cells.chars().zip(advances) {
                    chars.insert(c as u32, *advance);
                }
            }
        }
        self.glyph_chars = Some(chars);
    }

    /// Each built pack's HUD, by the pack's name as the server knows it.
    pub fn huds(
        &self,
    ) -> BTreeMap<String, netherforge_format::kind::resource_pack::hud::CompiledHud> {
        self.compiled
            .iter()
            .filter_map(|(name, pack)| Some((name.clone(), pack.hud.clone()?)))
            .collect()
    }

    /// Whether pack `name` is built (none are while any has errors).
    pub fn has_pack(&self, name: &str) -> bool {
        self.compiled.contains_key(name)
    }
}

/// The states the project's blocks are held as: drawn as their models, everything else as the game draws it.
fn blocks_plan(
    platform: &dyn Platform,
    project: &Define,
) -> Option<netherforge_format::kind::carriers::Plan> {
    use netherforge_format::kind::block::{BlockFile, BlockRotation};
    let loaded = project.project;
    let every = loaded.present("block");
    if every.is_empty() {
        return None;
    }
    let running: BTreeMap<String, &BlockFile> = loaded.running::<BlockFile>().into_iter().collect();
    let mut blocks = BTreeMap::new();
    let mut carriers = BTreeMap::new();
    let mut rotations = BTreeMap::new();
    for (id, resource) in &every {
        if let Some(model) = resource.model::<BlockFile>() {
            carriers.insert(id.clone(), model.carrier.unwrap_or_default());
            rotations.insert(id.clone(), model.rotation.unwrap_or(BlockRotation::None));
        }
        blocks.insert(id.clone(), running.get(id).copied());
    }
    netherforge_format::kind::carriers::plan(
        &blocks,
        loaded.namespace(),
        Some(platform.game()),
        &carriers,
        &rotations,
    )
}

/// A file's bytes by project path, a package's (`ns:path`) from its package.
fn read_bytes(project: &Define, path: &str) -> Option<Vec<u8>> {
    match netherforge_format::kinds::split_package(path) {
        (Some(namespace), local) => project
            .project
            .packages
            .get(namespace)?
            .resolved
            .source
            .bytes(local),
        (None, _) => project.source.bytes(path),
    }
}

/// A PNG's width and height, from its header.
fn png_size(bytes: &[u8]) -> Option<(u32, u32)> {
    if bytes.len() < 24 || &bytes[..8] != b"\x89PNG\r\n\x1a\n" || &bytes[12..16] != b"IHDR" {
        return None;
    }
    let word =
        |at: usize| -> Option<u32> { Some(u32::from_be_bytes(bytes[at..at + 4].try_into().ok()?)) };
    Some((word(16)?, word(20)?))
}

/// What each path of a built layout holds, the project's files read (one that can't be is left out).
fn files(
    layout: &BTreeMap<String, Entry>,
    read: &dyn Fn(&str) -> Option<Vec<u8>>,
) -> BTreeMap<String, Vec<u8>> {
    layout
        .iter()
        .filter_map(|(path, entry)| {
            let bytes = match entry {
                Entry::Text(text) => text.clone().into_bytes(),
                Entry::Copy(project_path) => read(project_path)?,
                Entry::Bytes(bytes) => bytes.clone(),
            };
            Some((path.clone(), bytes))
        })
        .collect()
}

impl netherforge_api::api::NfPacksApi for crate::world::GameContext {
    /// The packs are read again from disk and rebuilt at the end of the tick, and sent to who lacks the new build.
    fn rebuild(&self, _caller: &netherforge_api::Caller) -> netherforge_api::ApiResult<()> {
        self.shared.packs.borrow_mut().wanted = true;
        Ok(())
    }

    /// The `onDemand` packs, by the names the calling package writes them.
    fn on_demand(
        &self,
        _caller: &netherforge_api::Caller,
    ) -> netherforge_api::ApiResult<Vec<String>> {
        let names: Vec<String> = self
            .shared
            .packs
            .borrow()
            .on_demand
            .keys()
            .cloned()
            .collect();
        Ok(names
            .iter()
            .map(|n| self.with_names(|names| names.spell(n)))
            .collect())
    }
}

/// The resource pack's part in a session: built first (menus' skins and scripts' glyphs read it), again when a
/// pack, a block, `client/vanilla.json` or a video changes.
pub struct PacksService {
    pub shared: Rc<Shared>,
    pub problems: std::cell::RefCell<Vec<Problem>>,
}

impl PacksService {
    /// Builds again, and sends it to everyone online whose client doesn't have this hash.
    fn rebuild(&self, project: &Define) {
        let platform = &*self.shared.platform;
        let mut packs = self.shared.packs.borrow_mut();
        let problems = packs.rebuild(platform, project);
        *self.problems.borrow_mut() = problems;
    }

    fn send_all(&self) -> usize {
        self.shared
            .packs
            .borrow_mut()
            .send_all(&*self.shared.platform)
    }
}

impl Service for PacksService {
    fn name(&self) -> &'static str {
        "resource pack"
    }

    fn reloads(&self) -> &'static [&'static str] {
        &["resource_pack"]
    }

    fn start(&self, _room: &Room, project: &Define) {
        self.rebuild(project);
    }

    /// Everyone online gets it, once scripts are running.
    fn after_start(&self, _room: &Room) {
        self.send_all();
    }

    fn player_joined(&self, player: &netherforge_platform::PlayerRef) {
        self.shared
            .packs
            .borrow_mut()
            .send(&*self.shared.platform, player.uuid);
    }

    fn player_quit(&self, player: &netherforge_platform::PlayerRef) {
        self.shared.packs.borrow_mut().forget(player.uuid);
    }

    /// Built again and sent to everyone online whose client doesn't have this hash.
    fn reload(&self, _room: &Room, _kind: &str, _ids: &BTreeSet<String>, project: &Define) {
        self.rebuild(project);
        self.send_all();
        let loaded = project.project;
        if !loaded.present("resource_pack").is_empty() && loaded.compiled_resource_packs.is_empty()
        {
            self.shared.platform.log().warn(
                "A resource pack has errors; players keep the last good resource pack".into(),
            );
        }
    }

    /// The blocks the pack draws change with the project's blocks, the game's own looks of their carriers with
    /// `client/vanilla.json`, and the sounds it carries with videos. Only what changed goes.
    fn reloaded(&self, _room: &Room, kinds: &BTreeSet<&str>, project: &Define) {
        if ["block", "vanilla", "video"]
            .iter()
            .any(|k| kinds.contains(k))
        {
            self.rebuild(project);
            self.send_all();
        }
    }

    fn problems(&self) -> Vec<Problem> {
        self.problems.borrow().clone()
    }
}
