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

/// The kinds of reference that are looks: values any package's code may hand on, by `look`'s rule.
pub const LOOKS: [RefKind; 6] = [
    RefKind::ItemModel,
    RefKind::Glyph,
    RefKind::Skin,
    RefKind::Tooltip,
    RefKind::Equipment,
    RefKind::Sound,
];

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

    /// `json`, a document of `schema` the calling code wrote (`nf.dialogs.create`'s table in the file's spelling),
    /// with every name in it as the server knows it, each held to what a file of the calling package could name
    /// (Kotlin's `PackageNames.definition`). A mistake names where it is: `place` turns the reference's JSON path
    /// into the script's words.
    pub fn definition(
        &self,
        schema: &netherforge_format::schema::Schema,
        json: &serde_json::Value,
        place: &dyn Fn(&str) -> String,
    ) -> ApiResult<serde_json::Value> {
        use netherforge_format::reference::{RefScope, find_in};
        const LOOKS: &[RefKind] = &[
            RefKind::ItemModel,
            RefKind::Glyph,
            RefKind::Skin,
            RefKind::Tooltip,
            RefKind::Equipment,
            RefKind::Sound,
        ];
        let references = self.names.references_of(&self.from);
        // Every reference checked first, so the first mistake is the one reported.
        let mut renamed: Vec<(String, String)> = Vec::new();
        for found in find_in(schema, json) {
            let spec = found.kind.spec();
            if spec.scope == RefScope::File || (spec.game && found.kind.is_game(&found.text)) {
                continue;
            }
            let tag = if found.span.is_some() {
                format!("<glyph:{}>: ", found.text)
            } else {
                String::new()
            };
            let fail = |problem: String| err(format!("{}: {tag}{problem}", place(&found.path)));
            let key = if spec.scope == RefScope::Resource {
                let Some(key) = resolve_any(&self.from, &found.text) else {
                    continue;
                };
                if let Some(kind) = spec.resource_kind
                    && let Some(broken) = self.names.nameable(&self.from, kind, &key)
                {
                    return Err(fail(for_script(&broken)));
                }
                key
            } else if LOOKS.contains(&found.kind) {
                self.look(found.kind, &found.text).map_err(|e| fail(e.0))?
            } else {
                if let Some(broken) = references.and_then(|r| r.check(found.kind, &found.text)) {
                    return Err(fail(for_script(&broken)));
                }
                match resolve(&self.from, found.kind, &found.text) {
                    Some(key) => key,
                    None => continue,
                }
            };
            let text = key.relative_to(self.home());
            if text != found.text {
                renamed.push((found.text.clone(), text));
            }
        }
        if renamed.is_empty() {
            return Ok(json.clone());
        }
        Ok(netherforge_format::reference::rewrite(
            schema,
            json,
            &mut |_, text| {
                renamed
                    .iter()
                    .find(|(from, _)| from == text)
                    .map(|(_, to)| to.clone())
            },
        ))
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

    /// `reference`, a look the calling code named, in full (`ns:<pack>/<key>`), so it means the same in any
    /// package's code (`nf.looks.qualify`). Not checked for being there: wherever it's shown checks that.
    pub fn qualify(&self, reference: &str) -> ApiResult<String> {
        resolve_any(&self.from, reference)
            .filter(|key| LOOKS.iter().any(|kind| kind.is_path(&key.path)))
            .map(|key| key.to_string())
            .ok_or_else(|| {
                err(format!(
                    "\"{reference}\" isn't a look: write <pack>/<key>, or <namespace>:<pack>/<key> for another package's"
                ))
            })
    }

    /// `reference`, a look of `kind` the calling code named, as the server names it (`ui/coin`,
    /// `library:gems/gem`).
    pub fn look_name(&self, kind: RefKind, reference: &str) -> ApiResult<String> {
        Ok(self.look(kind, reference)?.relative_to(self.home()))
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
