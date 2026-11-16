//! The permission nodes scripts set on players (`player:set_permission`, Kotlin's `PlayerPermissions`), kept
//! in the store under the project's namespace (by player, then node) so they outlast leaving and restarts,
//! and held on each online player through the Platform's `permissions`.
//!
//! What applies is what's kept **and** what a `netherforge.json`'s `allow.permissions` allows now (the
//! project's or any package's): a node a later edit no longer allows stays kept but stops applying, and
//! applies again if the edit is undone. Nothing applies while the project isn't running, so a refused project
//! grants nothing.

use std::collections::{BTreeMap, HashMap};
use std::rc::Rc;

use netherforge_format::manifest::Allow;
use netherforge_platform::{Platform, PlayerRef, Uuid};

use crate::Room;
use crate::session::{Define, Service};
use crate::shared::Shared;

/// What the permissions service keeps.
#[derive(Default)]
pub struct Permissions {
    /// Whose grants these are: the project's namespace.
    pub namespace: String,
    /// Every player's nodes, allowed or not.
    pub nodes: BTreeMap<Uuid, BTreeMap<String, bool>>,
    /// Each package's `allow`, by namespace, as the project read when it started.
    pub allows: BTreeMap<String, Allow>,
    pub running: bool,
}

impl Permissions {
    /// Whether any package of the tree allows `node`.
    pub fn allowed(&self, node: &str) -> bool {
        self.allows.values().any(|a| a.allows_permission(node))
    }

    /// What applies to `player` now.
    fn applying(&self, player: Uuid) -> HashMap<String, bool> {
        self.nodes
            .get(&player)
            .into_iter()
            .flatten()
            .filter(|(node, _)| self.allowed(node))
            .map(|(node, value)| (node.clone(), *value))
            .collect()
    }
}

/// Holds what applies to `player` on them, while the project runs.
pub fn apply(shared: &Shared, player: Uuid) {
    let nodes = {
        let state = shared.permissions.borrow();
        if !state.running {
            return;
        }
        state.applying(player)
    };
    shared.platform.permissions().apply(player, nodes);
}

/// Sets `node` on `player` (`player:set_permission`).
pub fn set(shared: &Shared, player: Uuid, node: &str, value: bool) {
    {
        let mut state = shared.permissions.borrow_mut();
        let namespace = state.namespace.clone();
        let mine = state.nodes.entry(player).or_default();
        if mine.insert(node.to_owned(), value) == Some(value) {
            return;
        }
        shared
            .store
            .set_permission(&namespace, player, node, Some(value));
    }
    apply(shared, player);
}

/// Takes `node` off `player`: false when nothing was set for it.
pub fn unset(shared: &Shared, player: Uuid, node: &str) -> bool {
    {
        let mut state = shared.permissions.borrow_mut();
        let namespace = state.namespace.clone();
        let Some(mine) = state.nodes.get_mut(&player) else {
            return false;
        };
        if mine.remove(node).is_none() {
            return false;
        }
        if mine.is_empty() {
            state.nodes.remove(&player);
        }
        shared.store.set_permission(&namespace, player, node, None);
    }
    apply(shared, player);
    true
}

pub struct PermissionsService {
    pub shared: Rc<Shared>,
}

impl Service for PermissionsService {
    fn name(&self) -> &'static str {
        "permissions"
    }

    /// The project runs: everyone online gets what's allowed now, before any script (so `has_permission`
    /// already answers).
    fn start(&self, _room: &Room, project: &Define) {
        let loaded = project.project;
        let namespace = loaded.namespace().to_owned();
        let allows = std::iter::once(namespace.clone())
            .chain(loaded.packages.keys().cloned())
            .filter_map(|ns| {
                let allow = loaded.manifest_of(&ns)?.allow.clone()?;
                Some((ns, allow))
            })
            .collect();
        *self.shared.permissions.borrow_mut() = Permissions {
            nodes: self.shared.store.permissions_of(&namespace),
            namespace,
            allows,
            running: true,
        };
        for player in project.platform.players().online() {
            apply(&self.shared, player.uuid);
        }
    }

    /// The project stops: everyone online loses what it set.
    fn stop(&self, _room: &Room, platform: &dyn Platform) {
        self.shared.permissions.borrow_mut().running = false;
        for player in platform.players().online() {
            platform.permissions().apply(player.uuid, HashMap::new());
        }
    }

    /// What they're kept to have, again: the server forgot it when they left.
    fn player_joined(&self, player: &PlayerRef) {
        if self
            .shared
            .permissions
            .borrow()
            .nodes
            .contains_key(&player.uuid)
        {
            apply(&self.shared, player.uuid);
        }
    }
}
