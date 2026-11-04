//! Id, name and path rules: format's `Names`. Rust's `regex` has no
//! lookahead, so a pattern's length limit is checked on its own.

use std::sync::LazyLock;

use regex::Regex;

pub const ID_RULE: &str =
    "lowercase letters, digits and _, starting with a letter or digit, at most 64 characters";
pub const NODE_NAME_RULE: &str = "letters, digits, _ and -, at most 64 characters";
pub const WORLD_NAME_RULE: &str =
    "letters, digits, _, - and ., starting with a letter, digit or _, at most 64 characters";
pub const PERMISSION_NODE_RULE: &str = "lowercase letters, digits, _ and -, in parts separated by dots (like shop.vip), at most 255 characters";
pub const HTTP_HOST_RULE: &str = "a host name in lowercase, like discord.com, or *. and one with a dot in it for any name under it, like *.example.com";
pub const PLUGIN_NAME_RULE: &str =
    "the plugin's name in lowercase: letters, digits, _ and -, at most 64 characters";
pub const NAMESPACE_RULE: &str = ID_RULE;
pub const GROUP_RULE: &str = "an id in parentheses, like (weapons)";

/// The namespaces nobody's project may take: the game's, the server's, NetherForge's.
pub const RESERVED_NAMESPACES: &[&str] = &[
    "minecraft",
    "realms",
    "brigadier",
    "c",
    "paper",
    "bukkit",
    "spigot",
    "netherforge",
    "nf",
];

macro_rules! pattern {
    ($name:ident, $re:expr) => {
        static $name: LazyLock<Regex> = LazyLock::new(|| Regex::new($re).expect("a valid pattern"));
    };
}

macro_rules! id_body {
    () => {
        "[a-z0-9][a-z0-9_]{0,63}"
    };
}
const ID_BODY: &str = id_body!();
/// An id's pattern, for a field's rule (with [`ID_RULE`] its words).
pub const ID_PATTERN: &str = concat!("^", id_body!(), "$");
/// A node's (or emitter's) name's pattern, with [`NODE_NAME_RULE`] its words.
pub const NODE_NAME_PATTERN: &str = "^[A-Za-z0-9_-]{1,64}$";
/// A world name's pattern, for a field's rule (with [`WORLD_NAME_RULE`] its words).
pub const WORLD_NAME_PATTERN: &str = "^[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}$";
const HOST_LABEL: &str = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";

pattern!(ID, ID_PATTERN);
pattern!(NODE_NAME, NODE_NAME_PATTERN);
pattern!(WORLD_NAME, WORLD_NAME_PATTERN);
pattern!(PERMISSION_NODE, "^[a-z0-9_-]+(\\.[a-z0-9_-]+)*$");
pattern!(HOST_NAME, &format!("^{HOST_LABEL}(?:\\.{HOST_LABEL})*$"));
pattern!(
    WILDCARD_HOST,
    &format!("^\\*\\.({HOST_LABEL}(?:\\.{HOST_LABEL})+)$")
);
pattern!(PLUGIN_NAME, "^[a-z0-9_-]{1,64}$");
pattern!(
    SEMVER,
    "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$"
);
pattern!(SEGMENT, "^[A-Za-z0-9_][A-Za-z0-9_.-]*$");
pattern!(LUA_FILE, "^([A-Za-z0-9_]+/)*[A-Za-z0-9_]+\\.lua$");
pattern!(GROUP, &format!("^\\({ID_BODY}\\)$"));

pub fn is_id(text: &str) -> bool {
    ID.is_match(text)
}
pub fn is_namespace(text: &str) -> bool {
    is_id(text)
}
pub fn is_version(text: &str) -> bool {
    SEMVER.is_match(text)
}
pub fn is_node_name(text: &str) -> bool {
    NODE_NAME.is_match(text)
}
pub fn is_world_name(text: &str) -> bool {
    WORLD_NAME.is_match(text)
}
pub fn is_permission_node(text: &str) -> bool {
    (1..=255).contains(&text.chars().count()) && PERMISSION_NODE.is_match(text)
}
pub fn is_host_name(text: &str) -> bool {
    (1..=253).contains(&text.chars().count()) && HOST_NAME.is_match(text)
}
/// A host, or `*.` and one with a dot in it.
pub fn is_http_host(text: &str) -> bool {
    if is_host_name(text) {
        return true;
    }
    WILDCARD_HOST
        .captures(text)
        .is_some_and(|c| (1..=253).contains(&c[1].chars().count()))
}
pub fn is_plugin_name(text: &str) -> bool {
    PLUGIN_NAME.is_match(text)
}
pub fn is_lua_file(path: &str) -> bool {
    LUA_FILE.is_match(path)
}
pub fn looks_like_group(segment: &str) -> bool {
    segment.len() >= 2 && segment.starts_with('(') && segment.ends_with(')')
}
pub fn is_group(segment: &str) -> bool {
    GROUP.is_match(segment)
}
pub fn is_relative_file(path: &str) -> bool {
    !path.is_empty()
        && path
            .split('/')
            .all(|it| it != "." && it != ".." && SEGMENT.is_match(it))
}

/// A game id, namespaced or not (`keep_inventory`, `minecraft:stone`): `GameIds.isValid`.
pub fn is_game_id(id: &str) -> bool {
    pattern!(GAME_ID, "^([a-z0-9_.-]+:)?[a-z0-9_./-]+$");
    GAME_ID.is_match(id)
}

/// A Minecraft version: `1.21.11`, `26.3`. Compared part by part, a missing part as 0.
#[derive(Clone, Debug, Eq)]
pub struct MinecraftVersion(pub Vec<u32>);

/// The oldest Minecraft version NetherForge supports.
pub const OLDEST_SUPPORTED: &str = "1.21.11";

impl MinecraftVersion {
    pub fn parse(text: &str) -> Option<MinecraftVersion> {
        pattern!(VERSION, "^\\d+(\\.\\d+){1,2}$");
        let text = text.trim();
        if !VERSION.is_match(text) {
            return None;
        }
        text.split('.')
            .map(|p| p.parse().ok())
            .collect::<Option<Vec<u32>>>()
            .map(MinecraftVersion)
    }
}

impl PartialEq for MinecraftVersion {
    fn eq(&self, other: &Self) -> bool {
        self.cmp(other) == std::cmp::Ordering::Equal
    }
}

impl Ord for MinecraftVersion {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        let n = self.0.len().max(other.0.len());
        (0..n)
            .map(|i| {
                self.0
                    .get(i)
                    .unwrap_or(&0)
                    .cmp(other.0.get(i).unwrap_or(&0))
            })
            .find(|o| o.is_ne())
            .unwrap_or(std::cmp::Ordering::Equal)
    }
}

impl PartialOrd for MinecraftVersion {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
}
