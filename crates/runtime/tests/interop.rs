//! Vault and PlaceholderAPI on the Rust runtime (Kotlin's `InteropTest`): a declared plugin the server lacks is a
//! problem until it enables, calls check again, and placeholders belong to the script that offered them.

use std::cell::{Cell, RefCell};
use std::collections::{BTreeSet, HashMap};
use std::rc::Rc;
use std::time::{Duration, Instant};

use netherforge_format::package::NoPackages;
use netherforge_format::project::ProjectSource;
use netherforge_platform::{
    EconomyOps, FakePlatform, FakePlatformLog, PlaceholderOps, PlaceholderResolver, PlatformError,
    PlatformLog, PluginOps, ServerInfo, Uuid,
};
use netherforge_runtime::session::{Config, Session};

#[derive(Default)]
struct Fake {
    lines: RefCell<Vec<String>>,
    enabled: RefCell<BTreeSet<String>>,
    balances: RefCell<HashMap<Uuid, f64>>,
    vault: Cell<bool>,
    registered: RefCell<HashMap<String, Rc<dyn PlaceholderResolver>>>,
    /// Namespaces another plugin has.
    others: RefCell<BTreeSet<String>>,
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
    fn get(&self, uuid: Uuid) -> Option<netherforge_platform::PlayerRef> {
        (uuid == Uuid(7)).then(|| netherforge_platform::PlayerRef {
            uuid,
            name: "Alex".into(),
        })
    }
    fn known(&self, name_or_uuid: String) -> Option<netherforge_platform::PlayerRef> {
        (name_or_uuid == "Alex").then(|| netherforge_platform::PlayerRef {
            uuid: Uuid(7),
            name: "Alex".into(),
        })
    }
}

impl PluginOps for Fake {
    fn is_enabled(&self, name: String) -> bool {
        self.enabled.borrow().contains(&name.to_lowercase())
    }
}

impl EconomyOps for Fake {
    fn balance(&self, player: Uuid) -> f64 {
        self.balances.borrow().get(&player).copied().unwrap_or(0.0)
    }
    fn deposit(&self, player: Uuid, amount: f64) -> Option<f64> {
        let mut balances = self.balances.borrow_mut();
        let total = balances.entry(player).or_insert(0.0);
        *total += amount;
        Some(*total)
    }
    fn withdraw(&self, player: Uuid, amount: f64) -> Option<f64> {
        let mut balances = self.balances.borrow_mut();
        let total = balances.entry(player).or_insert(0.0);
        if *total < amount {
            return None;
        }
        *total -= amount;
        Some(*total)
    }
}

impl PlaceholderOps for Fake {
    /// `%<namespace>_<key>%` answered by what's registered; `%player_name%` by the fake itself.
    fn parse(&self, player: Option<Uuid>, text: String) -> String {
        let mut out = String::new();
        let mut rest = text.as_str();
        while let Some(start) = rest.find('%') {
            out.push_str(&rest[..start]);
            let after = &rest[start + 1..];
            let Some(end) = after.find('%') else {
                out.push_str(&rest[start..]);
                return out;
            };
            let inner = &after[..end];
            let answer = match inner.split_once('_') {
                Some(("player", "name")) => Some("Alex".to_owned()),
                Some((ns, key)) => {
                    let resolver = self.registered.borrow().get(ns).cloned();
                    resolver.and_then(|r| r.resolve(player, key.to_owned()))
                }
                None => None,
            };
            match answer {
                Some(a) => out.push_str(&a),
                None => out.push_str(&format!("%{inner}%")),
            }
            rest = &after[end + 1..];
        }
        out.push_str(rest);
        out
    }
    fn register(&self, namespace: String, resolver: Rc<dyn PlaceholderResolver>) -> bool {
        if self.others.borrow().contains(&namespace)
            || self.registered.borrow().contains_key(&namespace)
        {
            return false;
        }
        self.registered.borrow_mut().insert(namespace, resolver);
        true
    }
    fn is_registered(&self, namespace: String) -> bool {
        self.registered.borrow().contains_key(&namespace)
    }
    fn unregister(&self, namespace: String) {
        self.registered.borrow_mut().remove(&namespace);
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
    fn plugins(&self) -> &dyn PluginOps {
        self
    }
    fn placeholders(&self) -> &dyn PlaceholderOps {
        self
    }
    fn economy(&self) -> Option<&dyn EconomyOps> {
        (self.vault.get() && self.is_enabled("vault".into())).then_some(self as &dyn EconomyOps)
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
    texts: RefCell<std::collections::BTreeMap<String, String>>,
}

impl ProjectSource for Files {
    fn files(&self) -> &BTreeSet<String> {
        self.names
    }
    fn read(&self, path: &str) -> Option<String> {
        self.texts.borrow().get(path).cloned()
    }
}

fn project(plugins: &str, script: &str) -> Files {
    let manifest = format!(
        r#"{{ "formatVersion": 1, "name": "Shop", "namespace": "shop", "version": "1.0.0", "minecraft": "26.3", "requires": {{ "plugins": [{plugins}] }} }}"#
    );
    let files = [
        ("netherforge.json".to_owned(), manifest),
        ("modules/t/init.lua".to_owned(), script.to_owned()),
    ];
    Files {
        names: Box::leak(Box::new(files.iter().map(|(p, _)| p.clone()).collect())),
        texts: RefCell::new(files.into_iter().collect()),
    }
}

fn start<'a>(files: &'a Files, platform: &Rc<Fake>) -> Session<'a> {
    Session::start_configured(
        platform.clone(),
        files,
        &NoPackages,
        Rc::new(netherforge_runtime::store::Store::in_memory()),
        Config::default(),
    )
}

fn lines(platform: &Fake) -> Vec<String> {
    let mut lines = Vec::new();
    for line in std::mem::take(&mut *platform.lines.borrow_mut()) {
        if let Some(rest) = line.strip_prefix("info [module t] ") {
            let text = rest.rsplit_once(" (modules/").map_or(rest, |(t, _)| t);
            lines.push(text.replace('\t', " "));
        } else if line.starts_with("warn") || line.contains("The plugin") {
            lines.push(line);
        }
    }
    lines
}

fn printed(session: &Session, platform: &Fake, count: usize) -> Vec<String> {
    let mut seen = lines(platform);
    let started = Instant::now();
    while seen.len() < count && started.elapsed() < Duration::from_secs(5) {
        session.tick();
        seen.extend(lines(platform));
    }
    seen
}

fn missing(session: &Session) -> usize {
    session
        .problems()
        .iter()
        .filter(|p| p.code.as_deref() == Some("runtime.plugin-missing"))
        .count()
}

const TRY: &str = r#"local function try(f, ...) local ok, a, b = pcall(f, ...) print(ok, (tostring(a):gsub("^[^:]+:%d+: ", "")), b) end
"#;

#[test]
fn the_economy_needs_vault_and_an_economy_as_of_each_call() {
    let files = project(
        r#""vault""#,
        &format!(
            r#"{TRY}
        local alex = nf.players.get("Alex")
        nf.task(function()
            try(nf.economy.balance, alex)
            nf.wait(5)
            try(nf.economy.balance, alex)
            nf.wait(5)
            try(nf.economy.deposit, alex, 5)
            try(nf.economy.withdraw, alex, 2)
            try(nf.economy.withdraw, alex, 10)
            try(nf.economy.deposit, alex, 0)
            try(nf.economy.balance, alex)
        end)
        "#
        ),
    );
    let platform = Rc::new(Fake::default());
    let session = start(&files, &platform);
    assert_eq!(missing(&session), 1);
    assert_eq!(
        printed(&session, &platform, 2),
        [
            "warn shop need the plugin \"vault\", which isn't enabled on this server (yet)",
            "false nf.economy.balance needs the plugin \"vault\", which isn't enabled on this server nil",
        ]
    );
    // Vault enables: the problem clears at the next tick after the adapter says plugins changed; with no economy
    // registered with it, a call still can't be made.
    platform.enabled.borrow_mut().insert("vault".into());
    session.plugins_changed();
    assert_eq!(
        printed(&session, &platform, 2),
        [
            "info The plugin \"vault\" is enabled now",
            "false nf.economy.balance needs an economy: Vault is enabled, but no economy plugin has registered with it (EssentialsX, for one, does) nil",
        ]
    );
    assert_eq!(missing(&session), 0);
    platform.vault.set(true);
    assert_eq!(
        printed(&session, &platform, 5),
        [
            "true 5 nil",
            "true 3 nil",
            "true nil nil",
            "false bad argument 'amount' (nf.economy.deposit takes an amount above 0 that isn't infinite or NaN, not 0) nil",
            "true 3 nil",
        ]
    );
    platform.enabled.borrow_mut().remove("vault");
    session.plugins_changed();
    assert_eq!(
        printed(&session, &platform, 1),
        ["warn The plugin \"vault\", which packages declare they need, is no longer enabled"]
    );
    assert_eq!(missing(&session), 1);
}

#[test]
fn a_call_needs_the_plugin_declared() {
    let files = project(
        "",
        &format!(r#"{TRY} try(nf.economy.balance, nf.players.get("Alex"))"#),
    );
    let platform = Rc::new(Fake::default());
    let session = start(&files, &platform);
    let seen = printed(&session, &platform, 1);
    assert!(
        seen[0].starts_with("false nf.economy.balance needs plugin:vault"),
        "{seen:?}"
    );
}

#[test]
fn placeholders_are_the_scripts_and_go_with_it() {
    let files = project(
        r#""placeholderapi""#,
        &format!(
            r#"{TRY}
        local asked = 0
        nf.placeholders.register("shop", function(key, player)
            asked = asked + 1
            if key == "self" then return nf.placeholders.parse("%shop_self%") end
            if key == "fail" then error("boom") end
            if key == "nothing" then return nil end
            return key .. ":" .. (player and player:name() or "nobody") .. ":" .. 2
        end)
        try(nf.placeholders.register, "shopstats", function() return 1.0 end)
        try(nf.placeholders.register, "shop", function() end)
        try(nf.placeholders.register, "Shop_x", function() end)
        try(nf.placeholders.register, "player", function() end)
        try(nf.placeholders.register, "shoptaken", function() end)
        print(nf.placeholders.parse("Hi %player_name%, %shop_price_apple% %shopstats_x% %shop_nothing% %shop_self%", nf.players.get("Alex")))
        print(nf.placeholders.parse("%shop_fail%"))
        "#
        ),
    );
    let platform = Rc::new(Fake::default());
    platform
        .enabled
        .borrow_mut()
        .insert("placeholderapi".into());
    platform.others.borrow_mut().insert("shoptaken".into());
    let mut session = start(&files, &platform);
    // The failure is reported as the body runs; what the body printed, as it ends.
    let seen = printed(&session, &platform, 8);
    assert_eq!(
        seen,
        [
            "warn module t: placeholder %shop_fail% failed: boom (modules/t/init.lua:7)",
            "true nil nil",
            "false the placeholder namespace \"shop\" is already registered by module t nil",
            "false bad argument 'namespace' (\"Shop_x\" can't be a placeholder namespace: lowercase letters and digits, starting with a letter, at most 32 characters, since PlaceholderAPI ends it at the first `_`) nil",
            "false bad argument 'namespace' (a package's placeholders start with its own namespace, \"shop\": \"shop\" or \"shopstats\", not \"player\") nil",
            "false the placeholder namespace \"shoptaken\" is already used by another plugin nil",
            "Hi Alex, price_apple:Alex:2 1 %shop_nothing% %shop_self%",
            "%shop_fail%",
        ]
    );
    // PlaceholderAPI reloaded and forgot them: offered again after the next plugin change.
    platform.registered.borrow_mut().clear();
    session.plugins_changed();
    session.tick();
    assert_eq!(
        platform
            .registered
            .borrow()
            .keys()
            .cloned()
            .collect::<BTreeSet<_>>(),
        BTreeSet::from(["shop".to_owned(), "shopstats".to_owned()])
    );
    // A reload of the script that offered them takes them off.
    session.reload(&["modules/t/init.lua".to_owned()]);
    session.tick();
    lines(&platform);
    files
        .texts
        .borrow_mut()
        .insert("modules/t/init.lua".into(), "print('gone')".into());
    session.reload(&["modules/t/init.lua".to_owned()]);
    assert!(platform.registered.borrow().is_empty());
}
