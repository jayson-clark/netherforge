//! Modules and `require`: the Kotlin runtime's `Modules` and the prelude's
//! `scopes` (`load_path`, `make_require`), in Rust. See the rust-runtime
//! skill, "Modules".
//!
//! A module is a folder `modules/<id>/` with an `init.lua`, run in a scope of
//! its own the first time anything requires it (or when the session starts
//! them all). `require` resolves a name to a file and the scope it runs in:
//!
//! - Dots are folders, and a name may be a folder with an `init.lua`.
//! - The caller's own files come first: a module's folder, or the folder of a
//!   resource's script. A file beside a resource's script runs in the
//!   requiring scope itself, once per scope, as part of the script.
//! - Otherwise the first part names a module (`greeter.messages` is
//!   `modules/greeter/messages.lua`, `greeter` is its `init.lua`).
//!
//! A file runs once per scope it runs in, and its result is kept there, so
//! every requirer of a module gets the module that's running, state and all.

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::rc::{Rc, Weak};

use mlua::{Function, Value};
use netherforge_luau::{Failure, ScopeId};

use crate::scripts::Scripts;

/// The file a module's folder starts with.
pub const ENTRY: &str = "init.lua";

/// The project as the runtime sees it: its files' text, by project path.
#[derive(Clone, Debug, Default)]
pub struct Project {
    pub files: BTreeMap<String, String>,
}

impl Project {
    /// Every module: each folder `modules/<id>/` with an `init.lua`.
    pub fn modules(&self) -> BTreeSet<String> {
        self.files
            .keys()
            .filter_map(|path| {
                let rest = path.strip_prefix("modules/")?;
                let (id, file) = rest.split_once('/')?;
                (file == ENTRY).then(|| id.to_owned())
            })
            .collect()
    }
}

/// Whose a scope is.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Owner {
    Module(String),
    /// A resource's script (a centity's, a menu's...), by its file.
    Script(String),
}

struct Inner {
    scripts: Scripts,
    project: RefCell<Project>,
    budget: u64,
    owners: RefCell<HashMap<ScopeId, Owner>>,
    modules: RefCell<HashMap<String, ScopeId>>,
    /// By the scope a file runs in, then its path: what it returned.
    loaded: RefCell<HashMap<(ScopeId, String), Value>>,
    /// The same, while it runs: a second require then is circular.
    loading: RefCell<HashSet<(ScopeId, String)>>,
    /// The modules each scope required (other than its own).
    requires: RefCell<HashMap<ScopeId, BTreeSet<String>>>,
}

/// A room's modules. Cheap to clone: a clone is the same modules.
#[derive(Clone)]
pub struct Modules(Rc<Inner>);

fn mistake(message: impl Into<String>) -> mlua::Error {
    mlua::Error::runtime(message.into())
}

impl Modules {
    /// The modules of `project`, whose scopes' calls get `budget` steps.
    pub fn new(scripts: Scripts, project: Project, budget: u64) -> Modules {
        Modules(Rc::new(Inner {
            scripts,
            project: RefCell::new(project),
            budget,
            owners: RefCell::new(HashMap::new()),
            modules: RefCell::new(HashMap::new()),
            loaded: RefCell::new(HashMap::new()),
            loading: RefCell::new(HashSet::new()),
            requires: RefCell::new(HashMap::new()),
        }))
    }

    /// Starts every module that hasn't started (each when its turn comes, or
    /// when something requires it first), in name order.
    pub fn start(&self) {
        let ids = self.0.project.borrow().modules();
        for id in ids {
            let _ = self.module(&id);
        }
    }

    /// The scope module `id` runs in, started if it hasn't: its scope opened
    /// and its `init.lua` run. A body that fails disables the scope (until
    /// it's reloaded), and that's reported once.
    pub fn module(&self, id: &str) -> Result<ScopeId, Failure> {
        if let Some(scope) = self.0.modules.borrow().get(id) {
            return Ok(*scope);
        }
        let scope = self
            .open(
                &format!("module {id}"),
                &format!("modules/{id}"),
                Owner::Module(id.to_owned()),
            )
            .map_err(|e| Failure::of(&e))?;
        self.0.modules.borrow_mut().insert(id.to_owned(), scope);
        let path = format!("modules/{id}/{ENTRY}");
        let result = self.run_file(scope, &path);
        if let Err(failure) = &result {
            self.0.scripts.fail_body(scope, failure.clone());
        }
        result.map(|_| scope)
    }

    /// Opens the scope of a resource's script, whose own files are beside
    /// `file`, and runs its body. Its memory is charged to `memory_key` (its
    /// resource: every instance together).
    pub fn script(&self, name: &str, memory_key: &str, file: &str) -> Result<ScopeId, Failure> {
        let scope = self
            .open(name, memory_key, Owner::Script(file.to_owned()))
            .map_err(|e| Failure::of(&e))?;
        let source = self.source(file).ok_or_else(|| Failure::Error {
            message: format!("no file {file}"),
            traceback: None,
        })?;
        self.0.scripts.run_body(scope, file, &source)?;
        Ok(scope)
    }

    /// Closes `scope` and forgets what it required.
    pub fn close(&self, scope: ScopeId) {
        self.0.scripts.close(scope);
        self.forget(scope);
        let module = match self.0.owners.borrow_mut().remove(&scope) {
            Some(Owner::Module(id)) => Some(id),
            _ => None,
        };
        if let Some(id) = module {
            self.0.modules.borrow_mut().remove(&id);
        }
    }

    /// Scopes other than module `id`'s own that required it, so its reload
    /// can restart them too.
    pub fn dependents(&self, id: &str) -> Vec<ScopeId> {
        let own = self.0.modules.borrow().get(id).copied();
        self.0
            .requires
            .borrow()
            .iter()
            .filter(|(scope, required)| Some(**scope) != own && required.contains(id))
            .map(|(scope, _)| *scope)
            .collect()
    }

    /// Reloads module `id` from `project`: its scope closes and starts again
    /// from the new files. Its dependents are the caller's to restart.
    pub fn reload(&self, id: &str, project: Project) -> Result<ScopeId, Failure> {
        *self.0.project.borrow_mut() = project;
        let old = self.0.modules.borrow().get(id).copied();
        if let Some(scope) = old {
            self.close(scope);
        }
        self.module(id)
    }

    /// The module scope `scope` is, if it's one.
    pub fn owner(&self, scope: ScopeId) -> Option<Owner> {
        self.0.owners.borrow().get(&scope).cloned()
    }

    fn forget(&self, scope: ScopeId) {
        self.0.loaded.borrow_mut().retain(|(s, _), _| *s != scope);
        self.0.loading.borrow_mut().retain(|(s, _)| *s != scope);
        self.0.requires.borrow_mut().remove(&scope);
    }

    fn source(&self, path: &str) -> Option<String> {
        self.0.project.borrow().files.get(path).cloned()
    }

    fn open(&self, name: &str, memory_key: &str, owner: Owner) -> mlua::Result<ScopeId> {
        let scope = self.0.scripts.open(name, memory_key, self.0.budget)?;
        self.0.owners.borrow_mut().insert(scope, owner);
        let host = self.0.scripts.host();
        let weak: Weak<Inner> = Rc::downgrade(&self.0);
        let require = host.lua().create_function(move |_, name: Value| {
            let Some(inner) = weak.upgrade() else {
                return Err(mistake("the project is gone"));
            };
            let Value::String(name) = name else {
                return Err(mistake(format!(
                    "bad argument 'name' (string expected, got {})",
                    netherforge_api::codec::type_name(&name)
                )));
            };
            let modules = Modules(inner);
            let (path, owner) = modules.resolve(scope, &name.to_string_lossy())?;
            modules.load_path(owner, &path)
        })?;
        host.env(scope)
            .expect("just opened")
            .raw_set("require", require)?;
        Ok(scope)
    }

    /// Resolves `require(name)` from `caller` to a project file and the scope
    /// it runs in.
    fn resolve(&self, caller: ScopeId, name: &str) -> mlua::Result<(String, ScopeId)> {
        let parts: Vec<&str> = name.split('.').collect();
        let valid = |part: &str| {
            !part.is_empty() && part.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
        };
        if !parts.iter().all(|p| valid(p)) {
            return Err(mistake(format!(
                "\"{name}\" isn't a module name: use dots between folder and file names, like \"greeter.messages\", and a package's namespace before a colon, like \"acme:api\""
            )));
        }
        let joined = parts.join("/");
        let owner = self.owner(caller);
        let (own_folder, script) = match &owner {
            Some(Owner::Module(id)) => (Some(format!("modules/{id}")), None),
            Some(Owner::Script(file)) => (
                Some(
                    file.rsplit_once('/')
                        .map_or(String::new(), |(f, _)| f.to_owned()),
                ),
                Some(file.clone()),
            ),
            None => (None, None),
        };
        let within = |folder: &str, file: String| {
            if folder.is_empty() {
                file
            } else {
                format!("{folder}/{file}")
            }
        };
        let own_files: Vec<String> = own_folder
            .iter()
            .flat_map(|f| {
                [
                    within(f, format!("{joined}.lua")),
                    within(f, format!("{joined}/{ENTRY}")),
                ]
            })
            .collect();
        let id = parts[0];
        let rest = parts[1..].join("/");
        let module_files: Vec<String> = if rest.is_empty() {
            vec![format!("modules/{id}/{ENTRY}")]
        } else {
            vec![
                format!("modules/{id}/{rest}.lua"),
                format!("modules/{id}/{rest}/{ENTRY}"),
            ]
        };
        let modules = self.0.project.borrow().modules();
        let project = self.0.project.borrow();
        let found = own_files
            .iter()
            .find(|p| project.files.contains_key(*p))
            .map(|p| (p.clone(), true))
            .or_else(|| {
                module_files
                    .iter()
                    .find(|p| project.files.contains_key(*p) && modules.contains(id))
                    .map(|p| (p.clone(), false))
            });
        drop(project);
        let Some((path, own)) = found else {
            let candidates: Vec<String> = own_files.iter().chain(&module_files).cloned().collect();
            let what = if script.is_some() {
                "file or module"
            } else {
                "module"
            };
            return Err(mistake(format!(
                "no {what} \"{name}\" (looked for {})",
                candidates.join(", ")
            )));
        };
        if own && let Some(script) = &script {
            // A file beside the script runs as part of it: in this scope, once per scope.
            if path == *script {
                return Err(mistake(format!(
                    "\"{name}\" is this script's own file, {path}, which is already running"
                )));
            }
            return Ok((path, caller));
        }
        let module = match (&owner, own) {
            (Some(Owner::Module(own_id)), true) => own_id.clone(),
            _ => id.to_owned(),
        };
        let started = self.0.modules.borrow().get(&module).copied();
        let scope = match started {
            Some(scope) => scope,
            None => self
                .module(&module)
                .map_err(|failure| mistake(failure.to_string()))?,
        };
        if !self.0.scripts.is_live(scope) {
            return Err(mistake(format!(
                "module \"{module}\" failed to load; fix it and save to reload"
            )));
        }
        if self.owner(caller) != Some(Owner::Module(module.clone())) {
            self.0
                .requires
                .borrow_mut()
                .entry(caller)
                .or_default()
                .insert(module);
        }
        Ok((path, scope))
    }

    /// Runs `path` in `owner`'s scope, once: what it returned (or `true`).
    fn load_path(&self, owner: ScopeId, path: &str) -> mlua::Result<Value> {
        let key = (owner, path.to_owned());
        if let Some(value) = self.0.loaded.borrow().get(&key) {
            return Ok(value.clone());
        }
        if self.0.loading.borrow().contains(&key) {
            return Err(mistake(format!("circular require of {path}")));
        }
        self.run_file(owner, path)
            .map_err(|failure| mistake(failure.to_string()))
    }

    /// Runs file `path` as `owner`'s code (whoever required it), and keeps
    /// what it returned.
    fn run_file(&self, owner: ScopeId, path: &str) -> Result<Value, Failure> {
        let source = self.source(path).ok_or_else(|| Failure::Error {
            message: format!("no file {path}"),
            traceback: None,
        })?;
        let host = self.0.scripts.host();
        let f: Function = host.load(owner, path, &source)?;
        let key = (owner, path.to_owned());
        self.0.loading.borrow_mut().insert(key.clone());
        let result = host.invoke::<Value>(owner, self.0.budget, &f, ());
        self.0.loading.borrow_mut().remove(&key);
        let value = result?;
        let value = if value.is_nil() {
            Value::Boolean(true)
        } else {
            value
        };
        // The scope may have closed while the file ran: keep nothing for it then.
        if self.0.owners.borrow().contains_key(&owner) {
            self.0.loaded.borrow_mut().insert(key, value.clone());
        }
        Ok(value)
    }
}
