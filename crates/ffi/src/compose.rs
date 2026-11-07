//! The runtime as the plugin runs it (Kotlin's `NetherForgeRuntime`'s enable and disable): what
//! `nf_start` puts together on the server's platform, and `nf_stop` takes apart. See the
//! plugin-runtime skill, "The dev bridge" → Composition.

use std::cell::RefCell;
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::rc::{Rc, Weak};

use netherforge_platform::{Dynamic, Platform, PlatformEvents};
use netherforge_runtime::admin::AdminCommand;
use netherforge_runtime::bridge::{Bridge, BridgeConfig};
use netherforge_runtime::config::{self, ServerAddress};
use netherforge_runtime::network::{Link, NetworkLink};
use netherforge_runtime::profile::Profiler;
use netherforge_runtime::screen::ProjectBytes;
use netherforge_runtime::screen::video::ProjectPaths;
use netherforge_runtime::server::ServerEvents;
use netherforge_runtime::session::Session;
use netherforge_runtime::shared::Shared;
use netherforge_runtime::store::Store;

use crate::JavaPlatform;
use crate::objects::JavaRef;
use crate::wire::{Reader, Take};
use netherforge_runtime::config::RuntimeConfig;
use netherforge_runtime::project_files::{FilesConfig, ProjectFiles};

/// What the plugin starts the runtime with: the server's platform, its folders and address, and
/// `config.yml`'s values with the JVM's properties and environment.
pub struct StartArgs {
    pub platform: JavaRef,
    pub plugin_folder: PathBuf,
    pub server_folder: PathBuf,
    pub server_ip: String,
    pub settings: Dynamic,
    pub properties: HashMap<String, String>,
    pub env: HashMap<String, String>,
}

impl Take for StartArgs {
    fn take(r: &mut Reader) -> Self {
        StartArgs {
            platform: Take::take(r),
            plugin_folder: Take::take(r),
            server_folder: Take::take(r),
            server_ip: Take::take(r),
            settings: Take::take(r),
            properties: Take::take(r),
            env: Take::take(r),
        }
    }
}

/// The running runtime: what the plugin's life holds.
pub struct Running {
    pub events: Rc<ServerEvents<'static>>,
    pub platform: Rc<dyn Platform>,
    bridge: Option<Rc<Bridge>>,
    profiler: Rc<Profiler>,
    metrics: Option<Rc<netherforge_runtime::metrics::ServerMetrics>>,
    control: Option<Rc<netherforge_runtime::control::ServerControl>>,
    _admin: Option<Rc<AdminCommand>>,
}

thread_local! {
    static RUNNING: RefCell<Option<Rc<Running>>> = const { RefCell::new(None) };
}

/// The runtime this thread started, if it runs.
pub fn running() -> Option<Rc<Running>> {
    RUNNING.with(|r| r.borrow().clone())
}

fn json(value: Dynamic) -> serde_json::Value {
    match value {
        Dynamic::Null => serde_json::Value::Null,
        Dynamic::Bool(b) => b.into(),
        Dynamic::Number(n) if n.fract() == 0.0 && n.abs() < 9.0e15 => (n as i64).into(),
        Dynamic::Number(n) => {
            serde_json::Number::from_f64(n).map_or(serde_json::Value::Null, Into::into)
        }
        Dynamic::String(s) => s.into(),
        Dynamic::List(items) => items.into_iter().map(json).collect(),
        Dynamic::Map(entries) => entries
            .into_iter()
            .map(|(k, v)| (k, json(v)))
            .collect::<serde_json::Map<_, _>>()
            .into(),
    }
}

/// Where the runtime reads its project and packages: a project, or a bundle, with the server's own packages.
fn project_files(config: &RuntimeConfig) -> ProjectFiles {
    ProjectFiles::new(FilesConfig {
        configured: config.project.clone(),
        resolves: config.resolves_packages,
        package_cache: config.package_cache.clone(),
        bundles: config.bundles.clone(),
        provided: config.provided_packages.clone(),
        written_packs: config.session.data_dir.clone(),
        kept_packs: Some(config.state_dir.join("written-packs")),
    })
}

/// Starts the runtime: the events the server's adapter calls, or none when there's no project to run. An error says
/// why `config.yml` can't be acted on.
pub fn start(args: StartArgs) -> Result<Option<Rc<dyn PlatformEvents>>, String> {
    if let Some(running) = running() {
        return Ok(Some(running.events.clone()));
    }
    let platform: Rc<dyn Platform> = Rc::new(JavaPlatform::new(args.platform));
    let settings = json(args.settings);
    let properties = args.properties;
    let env = args.env;
    let server = ServerAddress {
        directory: args.server_folder.clone(),
        ip: args.server_ip,
    };
    let Some(config) = config::read(
        &args.plugin_folder,
        &settings,
        &server,
        &|key| properties.get(key).cloned(),
        &|key| env.get(key).cloned(),
    )?
    else {
        return Ok(None);
    };

    let bridge = BridgeConfig::of(&config, args.server_folder.clone())
        .map(|c| Bridge::new(platform.clone(), c));
    let session_platform = bridge.as_ref().map_or(platform.clone(), |b| b.platform());

    // The project's folder and its packages' outlive every session of the plugin's life.
    let source: &'static ProjectFiles = Box::leak(Box::new(project_files(&config)));
    std::fs::create_dir_all(&config.state_dir)
        .map_err(|e| format!("{}: {e}", config.state_dir.display()))?;
    let store = Rc::new(Store::open(&config.state_dir.join("netherforge.db"))?);

    let mut session_config = config.session.clone();
    session_config.bytes = Some(ProjectBytes(Rc::new(|path: &str| {
        netherforge_format::project::ProjectSource::bytes(source, path)
    })));
    session_config.paths = Some(ProjectPaths(Rc::new(|path: &str| source.path_of(path))));
    session_config.written_pack_files = Some(netherforge_runtime::session::WrittenPackFiles(
        Rc::new(|| source.written_pack_files()),
    ));
    session_config.provided_packages = Some(netherforge_runtime::session::WrittenPackFiles(
        Rc::new(|| {
            source
                .provided_in_use()
                .iter()
                .map(|p| {
                    format!(
                        "{} {} (from {})",
                        p.namespace,
                        p.version,
                        p.folder.display()
                    )
                })
                .collect()
        }),
    ));
    session_config.prebuilt_pack = Some(netherforge_runtime::packs::PrebuiltPack(Rc::new(|| {
        source
            .bundled_pack()
            .ok_or_else(|| source.bundled_pack_ignored())
    })));
    // The network's link is the plugin's: it outlives sessions, and the first waits for the hub.
    let link = Link(NetworkLink::new(session_config.network.clone()));
    {
        let log = platform.clone();
        link.0.start(&move |line| log.log().info(line));
    }
    netherforge_runtime::network::wait_for_hub(&link, platform.log());
    session_config.network_link = Some(link);

    // Made before it runs, so its first run already has the profiler, the bridge's debugger and the server's lookups
    // (a recipe's project item is built from its look as the session starts).
    let session = Session::configured(session_platform, source, source, store, session_config);
    let shared = session.shared().clone();
    let events = Rc::new(ServerEvents::new(session));
    let info = platform.info();
    let profiler = Rc::new(Profiler::new(
        config.state_dir.join("profiles"),
        format!(
            "Minecraft {}, NetherForge {}",
            info.minecraft_version, info.plugin_version
        ),
    ));
    events.set_profiler(profiler.clone());
    let metrics = netherforge_runtime::metrics::ServerMetrics::start(&config.metrics, &*platform)
        .map(Rc::new);
    events.set_metrics(metrics.clone());
    let control = netherforge_runtime::control::ServerControl::start(&config.control, &*platform)
        .map(Rc::new);
    events.set_control(control.clone());
    if let Some(bridge) = &bridge {
        events.attach_bridge(bridge.clone());
        if let Some(debugger) = events.debugger() {
            debugger.set_packages(source.folders());
        }
    }
    bind(&platform, &events, &shared);
    events
        .session_mut_now()
        .expect("nothing holds a session that hasn't run")
        .begin();
    let admin = AdminCommand::register(&events, config.state_dir.clone());
    if let Some(bridge) = &bridge {
        bridge.connect();
    }
    // Entities in chunks loaded before the runtime was: no load event will announce them.
    events.entities_loaded(platform.entities().loaded_tagged(), Vec::new());

    let running = Rc::new(Running {
        events: events.clone(),
        platform,
        bridge,
        profiler,
        metrics,
        control,
        _admin: admin,
    });
    RUNNING.with(|r| *r.borrow_mut() = Some(running));
    Ok(Some(events))
}

/// The start-up datapack for the project `config.yml` names, which the server's bootstrap hands it before it loads
/// its worlds, long before the runtime starts (Kotlin's `NetherForgeBootstrap.build`): its files, the whole datapack's
/// hash, whether the refusal on record left something out, and whether a refusal could; none when there's no project
/// or the server doesn't say its data pack format. The platform is the bootstrap's: only its datapacks and log answer.
pub fn startup_datapack(
    args: StartArgs,
) -> Result<Option<netherforge_runtime::reload::ForStart>, String> {
    let platform: Rc<dyn Platform> = Rc::new(JavaPlatform::new(args.platform));
    let settings = json(args.settings);
    let properties = args.properties;
    let env = args.env;
    let server = ServerAddress {
        directory: args.server_folder.clone(),
        ip: args.server_ip,
    };
    let Some(config) = config::read(
        &args.plugin_folder,
        &settings,
        &server,
        &|key| properties.get(key).cloned(),
        &|key| env.get(key).cloned(),
    )?
    else {
        return Ok(None);
    };
    let source = project_files(&config);
    let loaded = netherforge_format::project::load(
        &source,
        netherforge_format::project::Options {
            packages: &source,
            ..Default::default()
        },
    );
    Ok(netherforge_runtime::reload::for_start(
        &loaded, &source, &*platform,
    ))
}

/// The project's generator for world `world`, which the server asks for before it loads the world, long before the
/// runtime starts (`bukkit.yml`'s `worlds.<name>.generator: NetherForge`; Kotlin's `StartupGenerators.generatorFor`):
/// the one `netherforge.json` names for it, its id as published, or none. Every generator of the project is published
/// here, so the world's first chunks and its spawn are already the generator's; the session publishes them again as
/// it starts.
pub fn default_generator(args: StartArgs, world: &str) -> Option<String> {
    let platform: Rc<dyn Platform> = Rc::new(JavaPlatform::new(args.platform));
    let settings = json(args.settings);
    let properties = args.properties;
    let env = args.env;
    let server = ServerAddress {
        directory: args.server_folder.clone(),
        ip: args.server_ip,
    };
    let config = config::read(
        &args.plugin_folder,
        &settings,
        &server,
        &|key| properties.get(key).cloned(),
        &|key| env.get(key).cloned(),
    )
    .ok()??;
    let source = Rc::new(project_files(&config));
    let loaded = netherforge_format::project::load(
        &*source,
        netherforge_format::project::Options {
            packages: &*source,
            ..Default::default()
        },
    );
    let reference = loaded
        .manifest
        .as_ref()
        .and_then(|m| m.worlds.as_ref())
        .and_then(|w| w.0.iter().find(|(name, _)| name.0 == world))
        .and_then(|(_, config)| config.terrain.clone());
    let terrains: Vec<String> = loaded
        .everywhere("terrain")
        .into_iter()
        .map(|(id, _)| id)
        .collect();
    let id = reference
        .as_deref()
        .and_then(|r| netherforge_runtime::terrains::generator_of(&loaded, r))
        .filter(|id| terrains.contains(id));
    let log = platform.log();
    let Some(id) = id else {
        log.warn(match &reference {
            None => format!(
                "The server asks NetherForge to generate world \"{world}\", but netherforge.json names no terrain for it, so the server generates it itself"
            ),
            Some(reference) => format!(
                "netherforge.json's terrain \"{reference}\" for world \"{world}\" isn't a terrain of the project, so the server generates it itself"
            ),
        });
        netherforge_runtime::terrains::note_asked(world, None);
        return None;
    };
    let plan = netherforge_runtime::custom_blocks::plan_of(&loaded, platform.game());
    let state_of = |name: &str| plan.as_ref()?.state_of(name, None).map(|s| s.text());
    let paths = {
        let source = source.clone();
        ProjectPaths(Rc::new(move |path: &str| source.path_of(path)))
    };
    let mut failures = netherforge_runtime::terrains::Failures::default();
    let published = netherforge_runtime::terrains::resolve_generators(
        &netherforge_runtime::terrains::Publishing {
            loaded: &loaded,
            source: &*source,
            platform: &*platform,
            state_of: &state_of,
            paths: Some(paths),
        },
        &mut failures,
    );
    let has = published.generators.contains_key(&id);
    platform
        .world_manager()
        .publish_generators(published.generators);
    if !has {
        log.warn(format!(
            "Terrain \"{id}\" has errors: world \"{world}\" generates nothing until it's fixed"
        ));
    }
    log.info(format!(
        "World \"{world}\" is generated by the project's generator \"{id}\""
    ));
    netherforge_runtime::terrains::note_asked(world, Some(id.clone()));
    Some(id)
}

/// The most `<at>` tags that can't be placed told about, once each.
const MOST_REPORTED_ATS: usize = 20;

/// Hands the server the runtime's events and the lookups it answers its text and items with (Kotlin's
/// `platform.bind` and `bindText`), each reading the session running when it's asked.
pub(crate) fn bind(
    platform: &Rc<dyn Platform>,
    events: &Rc<ServerEvents<'static>>,
    shared: &Rc<Shared>,
) {
    // The lookups read what the session shares, not the session: the server asks them while it starts, and while a
    // reload holds it.
    let weak: Weak<Shared> = Rc::downgrade(shared);
    let on =
        move |f: &dyn Fn(&Shared) -> Option<String>| -> Option<String> { f(&*weak.upgrade()?) };
    let glyphs = {
        let on = on.clone();
        Box::new(move |reference: String| {
            on(&|shared| {
                let mut packs = shared.packs.try_borrow_mut().ok()?;
                packs.text_glyph(&*shared.platform, &reference)
            })
        })
    };
    let items = {
        let weak = Rc::downgrade(shared);
        Box::new(move |reference: String| {
            let shared = weak.upgrade()?;
            let items = shared.items.try_borrow().ok()?;
            items.look(&reference)
        })
    };
    let blocks = {
        let on = on.clone();
        Box::new(move |name: String| on(&|s| s.blocks.try_borrow().ok()?.state_of(&name, None)))
    };
    let namespace = {
        let weak = Rc::downgrade(events);
        let first = events
            .try_session()
            .map(|s| s.project().namespace().to_string())
            .unwrap_or_default();
        let last = RefCell::new(first);
        Box::new(move || {
            if let Some(now) = weak.upgrade().and_then(|e| {
                e.try_session()
                    .ok()
                    .map(|s| s.project().namespace().to_string())
            }) {
                *last.borrow_mut() = now;
            }
            last.borrow().clone()
        })
    };
    platform.bind(events.clone(), glyphs, items, blocks, namespace);

    let space = {
        let on = on.clone();
        Box::new(move |px: i32| {
            on(&|s| {
                let built = s.packs.try_borrow().ok()?.built.is_some();
                built.then(|| netherforge_format::kind::resource_pack::fonts::offset(px))
            })
        })
    };
    let reported = Rc::new(RefCell::new(HashSet::<String>::new()));
    let layout = Box::new(move |text: String| {
        if !text.to_lowercase().contains("<at") {
            return text;
        }
        on(&|shared| {
            let font = shared.font.borrow();
            let packs = shared.packs.try_borrow().ok()?;
            let width = match font.as_ref() {
                Some(font) => netherforge_format::text::TextWidth::of_font(font),
                None => netherforge_format::text::TextWidth::new(|_| None),
            }
            .with_glyphs(|reference| packs.glyph_advance(reference))
            .with_glyph_chars(|code| packs.glyph_char_advance(code));
            let layout = width.layout(&text);
            for tag in &layout.unplaced {
                let mut reported = reported.borrow_mut();
                if reported.len() < MOST_REPORTED_ATS && reported.insert(tag.clone()) {
                    shared.platform.log().warn(format!(
                        "{tag} draws what it holds in line: it, or the line it's counted from, can't be measured \
                         (fonts/default.json is missing or doesn't cover a character, or a glyph's width is unknown)"
                    ));
                }
            }
            Some(layout.text.into_owned())
        })
        .unwrap_or(text)
    });
    platform.bind_text(space, layout);
}

/// Stops the runtime: the session's services, the profiler's reports written, the bridge closed.
pub fn stop() {
    let Some(running) = RUNNING.with(|r| r.borrow_mut().take()) else {
        return;
    };
    if let Some(mut session) = running.events.session_mut_now() {
        session.stop();
    }
    running.profiler.await_writes();
    if let Some(bridge) = &running.bridge {
        bridge.stop();
    }
    if let Some(control) = &running.control {
        control.stop(&*running.platform);
    }
    if let Some(metrics) = &running.metrics {
        metrics.stop();
    }
    let _ = &running.platform;
}
