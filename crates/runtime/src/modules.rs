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
//!
//! **Packages.** The packages the project depends on have modules too, named
//! as the runtime names a package's resources (`acme:api`), their files at
//! package paths (`acme:modules/api/init.lua`), each in a scope of the
//! package's own, so its calls are held to the package's `requires`. A bare
//! name is in the caller's own package; `require("acme:api")` names a module of
//! package `acme`, which the caller's package must depend on and `acme` must
//! export (`exports.modules`). A module's folder may be in group folders
//! (`modules/(combat)/sword`): its place says where.

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use std::rc::{Rc, Weak};

use mlua::{Function, IntoLuaMulti, MultiValue, Value};
use netherforge_luau::{Failure, ScopeId};

use crate::scripts::Scripts;

/// The file a module's folder starts with.
pub const ENTRY: &str = "init.lua";

/// What a package (or the project) says about its modules: what it depends on, and which of its modules others
/// may require.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct PackageModules {
    pub dependencies: BTreeSet<String>,
    pub exports: BTreeSet<String>,
}

/// The project as the runtime sees it: its files' text by project path (a package's at package paths,
/// `acme:modules/api/init.lua`), and its modules.
#[derive(Clone, Debug, Default)]
pub struct Project {
    pub files: BTreeMap<String, String>,
    /// The project's namespace: whose its own code is.
    pub home: String,
    /// Each module's folder by its id (`sword` at `modules/(combat)/sword`, `acme:api` at
    /// `acme:modules/api`).
    pub places: BTreeMap<String, String>,
    /// The project's and each package's dependencies and exported modules, by namespace.
    pub packages: BTreeMap<String, PackageModules>,
}

impl Project {
    /// The project's own `files` alone, its modules found by their folders (`modules/<id>/`).
    pub fn of(files: BTreeMap<String, String>) -> Project {
        let places = files
            .keys()
            .filter_map(|path| {
                let (id, _) = path.strip_prefix("modules/")?.split_once('/')?;
                Some((id.to_owned(), format!("modules/{id}")))
            })
            .collect();
        Project {
            files,
            places,
            ..Project::default()
        }
    }

    /// Every module, by id.
    pub fn modules(&self) -> BTreeSet<String> {
        self.places.keys().cloned().collect()
    }

    /// Where module `id` is: its place, or `modules/` itself (in its package) when it has none.
    fn place(&self, id: &str) -> String {
        if let Some(place) = self.places.get(id) {
            return place.clone();
        }
        match split_package(id) {
            (Some(ns), local) => package_path(ns, &format!("modules/{local}")),
            (None, local) => format!("modules/{local}"),
        }
    }

    fn init_path(&self, id: &str) -> String {
        format!("{}/{ENTRY}", self.place(id))
    }

    fn has_init(&self, id: &str) -> bool {
        self.files.contains_key(&self.init_path(id))
    }
}

/// A path's (or a module id's) package, and what it is inside it.
fn split_package(path: &str) -> (Option<&str>, &str) {
    netherforge_format::kinds::split_package(path)
}

fn package_path(namespace: &str, path: &str) -> String {
    netherforge_format::kinds::package_path(namespace, path)
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
        let ids: Vec<String> = {
            let project = self.0.project.borrow();
            project
                .modules()
                .into_iter()
                .filter(|id| project.has_init(id))
                .collect()
        };
        for id in ids {
            let _ = self.module(&id);
        }
    }

    /// The scope module `id` runs in (a package's named `ns:id`), started if it hasn't: its scope opened in
    /// its package's namespace and its `init.lua` run, if it has one. A body that fails disables the scope
    /// (until it's reloaded), and that's reported once.
    pub fn module(&self, id: &str) -> Result<ScopeId, Failure> {
        if let Some(scope) = self.0.modules.borrow().get(id) {
            return Ok(*scope);
        }
        let place = self.0.project.borrow().place(id);
        let scope = self
            .open(
                &format!("module {id}"),
                &place,
                Owner::Module(id.to_owned()),
            )
            .map_err(|e| Failure::of(&e))?;
        self.0.modules.borrow_mut().insert(id.to_owned(), scope);
        if !self.0.project.borrow().has_init(id) {
            return Ok(scope);
        }
        let path = self.0.project.borrow().init_path(id);
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
        let (scope, ran) = self.script_with(name, memory_key, file, &[]);
        ran?;
        scope.ok_or_else(|| Failure::Error {
            message: format!("no file {file}"),
            traceback: None,
        })
    }

    /// `script`, with `globals` set in its scope before its body runs (a centity's `this`): its scope (none
    /// when it couldn't open, or there's no such file), and whether its body ran to the end. A scope whose
    /// body failed stays open, disabled, for its owner to close.
    pub fn script_with(
        &self,
        name: &str,
        memory_key: &str,
        file: &str,
        globals: &[(&str, Value)],
    ) -> (Option<ScopeId>, Result<(), Failure>) {
        let scope = match self.open(name, memory_key, Owner::Script(file.to_owned())) {
            Ok(scope) => scope,
            Err(e) => return (None, Err(Failure::of(&e))),
        };
        if let Some(env) = self.0.scripts.host().env(scope) {
            for (name, value) in globals {
                if let Err(e) = env.raw_set(*name, value.clone()) {
                    self.close(scope);
                    return (None, Err(Failure::of(&e)));
                }
            }
        }
        let Some(source) = self.source(file) else {
            self.close(scope);
            return (
                None,
                Err(Failure::Error {
                    message: format!("no file {file}"),
                    traceback: None,
                }),
            );
        };
        let ran = self.0.scripts.run_body(scope, file, &source);
        (Some(scope), ran)
    }

    /// Runs the script `file` in a scope of its own, as a resource's script runs (its own files beside it, the
    /// project's modules), its calls given `budget` steps and `opened` told its scope before its body runs, without
    /// reporting a failure of its body: a test file, whose runner says what went wrong. Its scope (none when it
    /// couldn't open), and how its body ran.
    pub fn quiet_script(
        &self,
        name: &str,
        file: &str,
        budget: u64,
        opened: &dyn Fn(ScopeId),
    ) -> (Option<ScopeId>, Result<(), Failure>) {
        let scope = match self.open_with(name, file, Owner::Script(file.to_owned()), budget) {
            Ok(scope) => scope,
            Err(e) => return (None, Err(Failure::of(&e))),
        };
        opened(scope);
        let Some(source) = self.source(file) else {
            return (
                Some(scope),
                Err(Failure::Error {
                    message: format!("can't read {file}"),
                    traceback: None,
                }),
            );
        };
        let ran = self
            .0
            .scripts
            .host()
            .run::<()>(scope, budget, file, &source);
        (Some(scope), ran)
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

    /// The project's Lua as it reads now: what scripts that start from here on run and require. Nothing
    /// running restarts.
    pub fn update(&self, project: Project) {
        *self.0.project.borrow_mut() = project;
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

    /// Closes module `id`'s scope, if it runs: the first half of a reload, so what goes with the scope goes
    /// before the new version's body runs.
    pub fn unload(&self, id: &str) {
        let scope = self.0.modules.borrow().get(id).copied();
        if let Some(scope) = scope {
            self.close(scope);
        }
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
        self.open_with(name, memory_key, owner, self.0.budget)
    }

    fn open_with(
        &self,
        name: &str,
        memory_key: &str,
        owner: Owner,
        budget: u64,
    ) -> mlua::Result<ScopeId> {
        let namespace = match &owner {
            Owner::Module(id) | Owner::Script(id) => split_package(id).0.map(str::to_owned),
        };
        let scope = self
            .0
            .scripts
            .open_in(name, memory_key, budget, namespace.as_deref())?;
        self.0.owners.borrow_mut().insert(scope, owner);
        let host = self.0.scripts.host();
        let weak: Weak<Inner> = Rc::downgrade(&self.0);
        let require = host.script_function(move |lua, args: MultiValue| {
            let name = args.into_iter().next().unwrap_or(Value::Nil);
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
            modules
                .load_path(owner, &path)
                .and_then(|v| v.into_lua_multi(lua))
        })?;
        host.env(scope)
            .expect("just opened")
            .raw_set("require", require)?;
        Ok(scope)
    }

    /// Resolves `require(name)` from `caller` to a project file and the scope it runs in (see the module's
    /// docs): the caller's own files first for a bare name, then the module the first part names, in the
    /// caller's package or the one before a colon.
    fn resolve(&self, caller: ScopeId, name: &str) -> mlua::Result<(String, ScopeId)> {
        let (named, local) = split_package(name);
        let parts: Vec<&str> = local.split('.').collect();
        let valid = |part: &str| {
            !part.is_empty() && part.chars().all(|c| c.is_ascii_alphanumeric() || c == '_')
        };
        if !parts.iter().all(|p| valid(p))
            || named.is_some_and(|n| !netherforge_format::names::is_namespace(n))
        {
            return Err(mistake(format!(
                "\"{name}\" isn't a module name: use dots between folder and file names, like \"greeter.messages\", and a package's namespace before a colon, like \"acme:api\""
            )));
        }
        let home = self.0.project.borrow().home.clone();
        // The package the calling code is in (none: the project's own), where a bare name is looked for.
        let from = self.0.scripts.namespace(caller).filter(|n| *n != home);
        let pkg = match named {
            None => from.clone(),
            Some(n) if n == home => None,
            Some(n) => Some(n.to_owned()),
        };
        if pkg != from {
            self.check_exported(from.as_deref(), pkg.as_deref(), parts[0], name)?;
        }
        let joined = parts.join("/");
        let owner = self.owner(caller);
        let project = self.0.project.borrow();
        // A resource's script, whose folder holds its own files; a module's are in its folder. Only a bare name
        // looks there.
        let script = match &owner {
            Some(Owner::Script(file)) => Some(file.clone()),
            _ => None,
        };
        let caller_module = match &owner {
            Some(Owner::Module(id)) => Some(id.clone()),
            _ => None,
        };
        let own = if named.is_some() {
            None
        } else if let Some(id) = &caller_module {
            Some(project.place(id))
        } else {
            script.as_ref().map(|f| {
                f.rsplit_once('/')
                    .map_or(String::new(), |(d, _)| d.to_owned())
            })
        };
        let within = |folder: &str, file: String| {
            if folder.is_empty() {
                file
            } else {
                format!("{folder}/{file}")
            }
        };
        let own_files: Vec<String> = own
            .iter()
            .flat_map(|f| {
                [
                    within(f, format!("{joined}.lua")),
                    within(f, format!("{joined}/{ENTRY}")),
                ]
            })
            .collect();
        let id = match &pkg {
            Some(p) => package_path(p, parts[0]),
            None => parts[0].to_owned(),
        };
        let rest = parts[1..].join("/");
        let module_files: Vec<String> = if rest.is_empty() {
            vec![project.init_path(&id)]
        } else {
            let place = project.place(&id);
            vec![
                format!("{place}/{rest}.lua"),
                format!("{place}/{rest}/{ENTRY}"),
            ]
        };
        let modules = project.modules();
        let found = own_files
            .iter()
            .find(|p| {
                project.files.contains_key(*p)
                    && (script.is_some()
                        || caller_module.as_ref().is_some_and(|m| modules.contains(m)))
            })
            .map(|p| (p.clone(), true))
            .or_else(|| {
                module_files
                    .iter()
                    .find(|p| project.files.contains_key(*p) && modules.contains(&id))
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
        let module = match (&caller_module, own) {
            (Some(own_id), true) => own_id.clone(),
            _ => id,
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
        if caller_module.as_deref() != Some(module.as_str()) {
            self.0
                .requires
                .borrow_mut()
                .entry(caller)
                .or_default()
                .insert(module);
        }
        Ok((path, scope))
    }

    /// Code in package `from` (none: the project's) may require `module` of package `to` (none: the
    /// project's) only when `from` depends on `to` and `to` exports it: a package's other modules are its own.
    fn check_exported(
        &self,
        from: Option<&str>,
        to: Option<&str>,
        module: &str,
        name: &str,
    ) -> mlua::Result<()> {
        use netherforge_format::reference::{not_exported, unknown_namespace};
        let project = self.0.project.borrow();
        let requirer = from.unwrap_or(&project.home);
        let target = to.unwrap_or(&project.home);
        let depends = project
            .packages
            .get(requirer)
            .is_some_and(|p| p.dependencies.contains(target));
        if !depends {
            return Err(mistake(format!(
                "\"{name}\": {}",
                for_script(&unknown_namespace(target, requirer).message)
            )));
        }
        let Some(package) = project.packages.get(target) else {
            return Err(mistake(format!(
                "\"{name}\": the package \"{target}\" isn't loaded"
            )));
        };
        if !package.exports.contains(module) {
            return Err(mistake(for_script(
                &not_exported(target, &format!("module \"{module}\"")).message,
            )));
        }
        Ok(())
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

/// A reference's problem as a script is told it: its message, starting lower case.
fn for_script(message: &str) -> String {
    let mut chars = message.chars();
    match chars.next() {
        Some(first) => first.to_lowercase().chain(chars).collect(),
        None => String::new(),
    }
}
