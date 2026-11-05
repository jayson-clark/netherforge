//! How names cross between the packages a session runs, into the API and back out to scripts (Kotlin's
//! `PackageNames`), on format's `Names` (what each namespace depends on, exports, has and can name).
//!
//! - **Whose code it is**: the calling scope's package (the scope `nf` was bound to, or the running scope for a
//!   handle's method), else the project's.
//! - **In.** A name a script hands the API is resolved in the calling package and held to what a file of that
//!   package could name, then written as the server knows it: as the project names it (`ruby`,
//!   `library:gem`).
//! - **Out.** A name read back is written as the reading package writes it: bare for its own, `ns:id` for
//!   another's.
//!
//! A project with no packages has nothing to translate: every name crosses as it's written.

use netherforge_api::{ApiError, ApiResult};
use netherforge_format::project::Names;
use netherforge_format::reference::{BrokenRef, RefKind, ResourceKey, resolve, resolve_any};

/// A broken reference's words for a script: format's, starting lower-case.
pub fn for_script(broken: &BrokenRef) -> String {
    let mut chars = broken.message.chars();
    match chars.next() {
        Some(first) => first.to_lowercase().chain(chars).collect(),
        None => String::new(),
    }
}

fn err(message: impl Into<String>) -> ApiError {
    ApiError(message.into())
}

/// The names of a session's tree, as code in package `from` sees them.
pub struct Calling<'n> {
    pub names: &'n Names,
    pub from: String,
}

impl Calling<'_> {
    fn home(&self) -> &str {
        &self.names.home
    }

    /// `reference`, a resource of `kind` (a kind's id, `sky`) the calling code named, as the server names it.
    /// An error when it's another package's that the caller can't use; whether it exists is the registry's
    /// to say. Left as written when it isn't shaped like a reference.
    pub fn resource(&self, kind: &str, reference: &str) -> ApiResult<String> {
        if !self.names.spanning() {
            return Ok(reference.to_owned());
        }
        let Some(key) = resolve_any(&self.from, reference) else {
            return Ok(reference.to_owned());
        };
        if let Some(broken) = self.names.nameable(&self.from, kind, &key) {
            return Err(err(for_script(&broken)));
        }
        Ok(key.relative_to(self.home()))
    }

    /// `name`, as the server names it, as the reading package writes it.
    pub fn spell(&self, name: &str) -> String {
        if !self.names.spanning() || self.from == self.home() {
            return name.to_owned();
        }
        resolve_any(self.home(), name)
            .map_or_else(|| name.to_owned(), |key| key.relative_to(&self.from))
    }

    /// `reference`, a biome the calling code named, as the server keys it: the project's own bare is its
    /// namespace's, another package's `ns:id` (an error when the caller can't name it), the game's as written.
    pub fn biome(&self, reference: &str) -> ApiResult<String> {
        if RefKind::Biome.is_game(reference) && !reference.starts_with('#') {
            return Ok(reference.to_owned());
        }
        let key = resolve_any(&self.from, reference).ok_or_else(|| {
            err(format!(
                "\"{reference}\" isn't a biome id: {}",
                RefKind::Biome.shape()
            ))
        })?;
        if let Some(broken) = self.names.nameable(&self.from, "biome", &key) {
            return Err(err(for_script(&broken)));
        }
        Ok(key.to_string())
    }

    /// `key`, a biome as the server names it, as the reading package writes it.
    pub fn spell_biome(&self, key: &str) -> String {
        if RefKind::Biome.is_game(key) {
            return key.to_owned();
        }
        resolve_any(self.home(), key).map_or_else(|| key.to_owned(), |k| k.relative_to(&self.from))
    }

    /// `reference`, a look of `kind` (an item model, a glyph, a sound...) the calling code named, as a key.
    /// **A look is a value, not a use**: `ns:<pack>/<key>` may be any loaded package's, as long as it has it;
    /// a bare name is the caller's own. An error when the namespace is no loaded package's, or its package
    /// has no such entry (a bare name another loaded package has says how to write that one).
    pub fn look(&self, kind: RefKind, reference: &str) -> ApiResult<ResourceKey> {
        let references = self
            .names
            .references_of(&self.from)
            .ok_or_else(|| err(format!("the package \"{}\" isn't loaded", self.from)))?;
        let Some(key) = resolve(&self.from, kind, reference) else {
            let broken = references.check(kind, reference);
            return Err(err(broken.map_or_else(
                || format!("\"{reference}\" isn't {}", kind.a_noun()),
                |b| for_script(&b),
            )));
        };
        let Some(owner) = self.names.references_of(&key.namespace) else {
            return Err(err(format!(
                "no package on this server has the namespace \"{}\"",
                key.namespace
            )));
        };
        let Some(broken) = owner.check(kind, &key.relative_to(&key.namespace)) else {
            return Ok(key);
        };
        if key.namespace != self.from {
            return Err(err(format!(
                "\"{}\": {}",
                key.namespace,
                for_script(&broken)
            )));
        }
        Err(err(format!(
            "{}{}",
            for_script(&broken),
            self.elsewhere(kind, &key)
        )))
    }

    /// For a bare look its own package lacks: the loaded package that has it, and how to write it.
    fn elsewhere(&self, kind: RefKind, key: &ResourceKey) -> String {
        let path = key.relative_to(&key.namespace);
        let other = self.names.namespaces.iter().find(|(namespace, n)| {
            **namespace != key.namespace && n.references.check(kind, &path).is_none()
        });
        match other {
            Some((other, _)) => format!(
                "; package \"{other}\" has one, named \"{other}:{path}\" from outside it (nf.looks.qualify writes it)"
            ),
            None => String::new(),
        }
    }

    /// What a pack sound reference is: its key when a loaded package has that sound, else the problem when it
    /// names one's pack, which lacks it; neither when it isn't shaped like one of a loaded pack's.
    pub fn pack_sound(&self, reference: &str) -> (Option<ResourceKey>, Option<String>) {
        let Some(key) = resolve(&self.from, RefKind::Sound, reference) else {
            return (None, None);
        };
        let Some(owner) = self.names.references_of(&key.namespace) else {
            return (None, None);
        };
        let has_pack = owner
            .namespaces
            .get(&key.namespace)
            .is_some_and(|defined| defined.packs.contains(key.pack()));
        if !has_pack {
            let bare = !reference.contains(':');
            let elsewhere = if bare {
                self.elsewhere(RefKind::Sound, &key)
            } else {
                String::new()
            };
            let problem = (!elsewhere.is_empty()).then(|| {
                format!(
                    "no sound \"{reference}\" in package \"{}\"{elsewhere}",
                    self.from
                )
            });
            return (None, problem);
        }
        match self.look(RefKind::Sound, reference) {
            Ok(key) => (Some(key), None),
            Err(e) => (None, Some(e.0)),
        }
    }
}
