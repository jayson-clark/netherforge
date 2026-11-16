//! Server-owner settings (Kotlin's `settings/OwnerSettings`): what each package declares in `netherforge.json`'s
//! `settings`, and the values the server's owner gave them, kept in `<plugin folder>/settings/<namespace>.json`
//! (format's `read_values`/`write_values`). Scripts read them with `nf.config(name)`, each package only its own.
//!
//! A change (`Session::set_setting`, `set_setting_text`, `reload_settings`: what the bridge, `/nf settings` and its
//! dialog call) is written to the file at once, then the package's scripts that listen for `setting_changed` hear it
//! (only that package's), and every other scope of the package that read a changed setting is restarted through a
//! reload of its resource. Values that don't fit are a problem, never used, never written over; an unreadable file is
//! left alone. A full reload reads the files again.

use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::rc::Rc;

use netherforge_format::Problem;
use netherforge_format::manifest::SettingDef;
use netherforge_format::settings::{SettingRead, ValueProblem, read_values, write_values};
use netherforge_luau::ScopeId;
use serde_json::Value;

use crate::Room;
use crate::session::{Define, Service};
use crate::shared::Shared;

/// One package's settings: what it declares, and what the owner's file holds.
#[derive(Clone, Debug)]
struct Package {
    name: String,
    declared: Vec<(String, SettingDef)>,
    /// The file as read or last written: every key, the ones that don't fit too.
    file: serde_json::Map<String, Value>,
    /// The owner's values that fit their settings.
    values: serde_json::Map<String, Value>,
    /// Why a key of the file wasn't used, by key.
    misfits: HashMap<String, ValueProblem>,
    /// The file isn't a JSON object, so nothing in it is used (and it isn't written over).
    unreadable: bool,
}

impl Package {
    fn definition(&self, setting: &str) -> Option<&SettingDef> {
        self.declared
            .iter()
            .find(|(n, _)| n == setting)
            .map(|(_, d)| d)
    }

    fn current(&self, setting: &str) -> Option<Value> {
        self.values
            .get(setting)
            .cloned()
            .or_else(|| self.definition(setting).map(SettingDef::default_value))
    }
}

/// A setting whose value changed: its name, what it was and what it is.
#[derive(Clone, Debug, PartialEq)]
pub struct Change {
    pub setting: String,
    pub was: Value,
    pub now: Value,
}

/// Every package's settings, in `Shared::settings`.
#[derive(Default)]
pub struct Settings {
    /// `<plugin folder>/settings`: none keeps the owner's values in memory only (tests).
    pub directory: Option<PathBuf>,
    home: String,
    packages: indexmap::IndexMap<String, Package>,
    /// The settings each scope has read: what restarts it when one changes.
    reads: HashMap<ScopeId, HashSet<String>>,
}

impl Settings {
    fn manifest_of(&self, namespace: &str) -> String {
        if namespace == self.home {
            netherforge_format::manifest::FILE_NAME.into()
        } else {
            format!("{namespace}:{}", netherforge_format::manifest::FILE_NAME)
        }
    }

    fn unknown(&self, namespace: &str, setting: &str) -> String {
        let mut declared: Vec<&str> = self
            .packages
            .get(namespace)
            .map(|p| p.declared.iter().map(|(n, _)| n.as_str()).collect())
            .unwrap_or_default();
        declared.sort_unstable();
        let has = if declared.is_empty() {
            "it declares none".to_owned()
        } else {
            format!("it has {}", declared.join(", "))
        };
        format!(
            "there's no setting \"{setting}\" in {}'s settings ({has})",
            self.manifest_of(namespace)
        )
    }

    /// `nf.config(name)` for `scope`, of package `namespace`: its setting `setting` as JSON. Remembered as read.
    pub fn config(
        &mut self,
        scope: ScopeId,
        namespace: &str,
        setting: &str,
    ) -> Result<Value, String> {
        let Some(value) = self
            .packages
            .get(namespace)
            .and_then(|p| p.current(setting))
        else {
            return Err(self.unknown(namespace, setting));
        };
        self.reads.entry(scope).or_default().insert(setting.into());
        Ok(value)
    }

    /// Where the owner's values for `namespace` are kept.
    pub fn file(&self, namespace: &str) -> Option<PathBuf> {
        self.directory
            .as_ref()
            .map(|d| d.join(format!("{namespace}.json")))
    }

    fn package(&self, namespace: &str) -> Result<&Package, String> {
        self.packages.get(namespace).ok_or_else(|| {
            if self.packages.is_empty() {
                "no package here has settings".into()
            } else {
                let names: Vec<&str> = self.packages.keys().map(String::as_str).collect();
                format!(
                    "no package \"{namespace}\" with settings (there's {})",
                    names.join(", ")
                )
            }
        })
    }

    /// The setting `name` of package `namespace`.
    pub fn definition(&self, namespace: &str, name: &str) -> Result<SettingDef, String> {
        self.package(namespace)?
            .definition(name)
            .cloned()
            .ok_or_else(|| self.unknown(namespace, name))
    }

    /// Every package with settings, by namespace: the project's first.
    pub fn namespaces(&self) -> Vec<String> {
        self.packages.keys().cloned().collect()
    }

    /// `namespace`'s settings with their values, by name.
    pub fn values(&self, namespace: &str) -> Result<Vec<(String, SettingDef, Value)>, String> {
        let pkg = self.package(namespace)?;
        let mut all: Vec<(String, SettingDef, Value)> = pkg
            .declared
            .iter()
            .map(|(n, d)| (n.clone(), d.clone(), pkg.current(n).expect("declared")))
            .collect();
        all.sort_by(|a, b| a.0.cmp(&b.0));
        Ok(all)
    }

    /// Whether the owner set `namespace`'s `setting`; otherwise it has its default.
    pub fn is_set(&self, namespace: &str, setting: &str) -> bool {
        self.packages
            .get(namespace)
            .is_some_and(|p| p.values.contains_key(setting))
    }

    /// Every package's settings as the editor shows them (the bridge's `settings`): each with the value it runs with,
    /// whether the owner set it, and why the file's value wasn't used; each file named by `name` (relative to the
    /// server's folder, where the editor reads).
    pub fn state(
        &self,
        name: &dyn Fn(&std::path::Path) -> String,
    ) -> netherforge_format::bridge::ServerSettings {
        use netherforge_format::bridge::{Json, PackageSettings, ServerSettings, SettingState};
        let packages = self
            .packages
            .iter()
            .map(|(namespace, pkg)| PackageSettings {
                namespace: namespace.clone(),
                name: pkg.name.clone(),
                file: self
                    .file(namespace)
                    .map(|f| name(&f))
                    .unwrap_or_else(|| format!("{namespace}.json")),
                settings: pkg
                    .declared
                    .iter()
                    .map(|(setting, definition)| {
                        (
                            setting.clone(),
                            SettingState {
                                definition: definition.clone(),
                                value: Json(pkg.current(setting).expect("declared")),
                                set: pkg.values.contains_key(setting),
                                problem: pkg.misfits.get(setting).map(|p| p.message.clone()),
                            },
                        )
                    })
                    .collect(),
            })
            .collect();
        ServerSettings { packages }
    }

    /// The package's name, `netherforge.json`'s.
    pub fn package_name(&self, namespace: &str) -> Option<String> {
        self.packages.get(namespace).map(|p| p.name.clone())
    }

    fn read(&self, namespace: &str, pkg: &mut Package, warn: &dyn Fn(String)) {
        pkg.file.clear();
        pkg.values.clear();
        pkg.misfits.clear();
        pkg.unreadable = false;
        let Some(file) = self.file(namespace) else {
            return;
        };
        if !file.exists() {
            return;
        }
        let text = match std::fs::read_to_string(&file) {
            Ok(text) => text,
            Err(e) => {
                warn(format!(
                    "Couldn't read {}; {namespace}'s settings have their defaults ({e})",
                    file.display()
                ));
                pkg.unreadable = true;
                return;
            }
        };
        let declared = pkg.declared.clone();
        let Some(read) = read_values(&text, |name| {
            declared.iter().find(|(n, _)| n == name).map(|(_, d)| d)
        }) else {
            warn(format!(
                "{} isn't a JSON object, so {namespace}'s settings have their defaults",
                file.display()
            ));
            pkg.unreadable = true;
            return;
        };
        for problem in &read.problems {
            warn(format!(
                "{}: {}: {}{}",
                file.display(),
                problem.setting,
                problem.message,
                if problem.unknown {
                    ""
                } else {
                    "; it has its default"
                }
            ));
        }
        pkg.file = read.file;
        pkg.values = read.values;
        pkg.misfits = read
            .problems
            .into_iter()
            .map(|p| (p.setting.clone(), p))
            .collect();
    }

    /// Written at once, atomically (a temporary file moved over it). A file left empty goes.
    fn write(&self, namespace: &str, pkg: &Package) -> Result<(), String> {
        let Some(file) = self.file(namespace) else {
            return Ok(());
        };
        let saved = (|| {
            if pkg.file.is_empty() {
                if file.exists() {
                    std::fs::remove_file(&file)?;
                }
                return Ok(());
            }
            if let Some(parent) = file.parent() {
                std::fs::create_dir_all(parent)?;
            }
            let temp = file.with_extension("json.tmp");
            std::fs::write(&temp, write_values(&pkg.file))?;
            std::fs::rename(&temp, &file)
        })();
        saved.map_err(|e: std::io::Error| format!("couldn't save {}: {e}", file.display()))
    }

    /// Puts `values` in place at once (each already read by its setting, or none for its default), writes the file
    /// once, and gives what changed.
    pub fn set_all(
        &mut self,
        namespace: &str,
        values: Vec<(String, Option<Value>)>,
    ) -> Result<Vec<Change>, String> {
        let pkg = self.package(namespace)?;
        if pkg.unreadable {
            return Err(format!(
                "{} isn't a JSON object: fix or delete it, then /nf settings reload",
                self.file(namespace)
                    .map_or_else(|| format!("{namespace}.json"), |f| f.display().to_string())
            ));
        }
        for (name, _) in &values {
            self.definition(namespace, name)?;
        }
        let mut pkg = pkg.clone();
        let before: Vec<(String, Value)> = values
            .iter()
            .map(|(n, _)| (n.clone(), pkg.current(n).expect("declared")))
            .collect();
        for (name, value) in values {
            match value {
                None => {
                    pkg.file.remove(&name);
                    pkg.values.remove(&name);
                }
                Some(value) => {
                    pkg.file.insert(name.clone(), value.clone());
                    pkg.values.insert(name.clone(), value);
                }
            }
            pkg.misfits.remove(&name);
        }
        self.write(namespace, &pkg)?;
        let changes = before
            .into_iter()
            .filter_map(|(setting, was)| {
                let now = pkg.current(&setting).expect("declared");
                (now != was).then_some(Change { setting, was, now })
            })
            .collect();
        self.packages.insert(namespace.into(), pkg);
        Ok(changes)
    }

    /// Sets `namespace`'s setting `name` to `value` (JSON; none or null puts it back to its default) and writes the
    /// file: what changed. A setting there isn't, or a value it can't have, says why.
    pub fn set(
        &mut self,
        namespace: &str,
        name: &str,
        value: Option<&Value>,
    ) -> Result<Vec<Change>, String> {
        let definition = self.definition(namespace, name)?;
        let read = match value.filter(|v| !v.is_null()) {
            None => None,
            Some(v) => match definition.read(v) {
                SettingRead::Ok(json) => Some(json),
                SettingRead::Bad(message) => return Err(format!("{name}: {message}")),
            },
        };
        self.set_all(namespace, vec![(name.into(), read)])
    }

    /// `set`, from words typed (`/nf settings set`, a dialog's text box).
    pub fn set_text(
        &mut self,
        namespace: &str,
        name: &str,
        text: &str,
    ) -> Result<Vec<Change>, String> {
        match self.definition(namespace, name)?.parse(text) {
            SettingRead::Ok(json) => self.set_all(namespace, vec![(name.into(), Some(json))]),
            SettingRead::Bad(message) => Err(format!("{name}: {message}")),
        }
    }

    /// Reads every package's file again, giving what changed, by package.
    pub fn reload(&mut self, warn: &dyn Fn(String)) -> Vec<(String, Vec<Change>)> {
        let namespaces: Vec<String> = self.packages.keys().cloned().collect();
        let mut all = Vec::new();
        for namespace in namespaces {
            let mut pkg = self.packages[&namespace].clone();
            let before: Vec<(String, Value)> = pkg
                .declared
                .iter()
                .map(|(n, _)| (n.clone(), pkg.current(n).expect("declared")))
                .collect();
            self.read(&namespace, &mut pkg, warn);
            let changes: Vec<Change> = before
                .into_iter()
                .filter_map(|(setting, was)| {
                    let now = pkg.current(&setting).expect("declared");
                    (now != was).then_some(Change { setting, was, now })
                })
                .collect();
            self.packages.insert(namespace.clone(), pkg);
            all.push((namespace, changes));
        }
        all
    }

    /// Every package's settings as the project declares them, each file read.
    fn define(&mut self, project: &Define, warn: &dyn Fn(String)) {
        self.packages.clear();
        self.reads.clear();
        self.home = project.project.namespace().to_owned();
        let mut namespaces = vec![self.home.clone()];
        let mut others: Vec<String> = crate::session::grants(project.project)
            .requires
            .keys()
            .filter(|n| **n != self.home)
            .cloned()
            .collect();
        others.sort();
        namespaces.extend(others);
        for namespace in namespaces {
            let Some(manifest) = project.project.manifest_of(&namespace) else {
                continue;
            };
            let Some(settings) = manifest.settings.as_ref().filter(|s| !s.is_empty()) else {
                continue;
            };
            let mut pkg = Package {
                name: manifest.name.clone(),
                declared: settings
                    .iter()
                    .map(|(k, v)| (k.to_string(), v.clone()))
                    .collect(),
                file: Default::default(),
                values: Default::default(),
                misfits: HashMap::new(),
                unreadable: false,
            };
            self.read(&namespace, &mut pkg, warn);
            self.packages.insert(namespace, pkg);
        }
    }

    /// The scopes that read any of `settings`.
    pub fn readers(&self, settings: &HashSet<String>) -> Vec<ScopeId> {
        self.reads
            .iter()
            .filter(|(_, read)| read.iter().any(|s| settings.contains(s)))
            .map(|(scope, _)| *scope)
            .collect()
    }

    fn problems(&self) -> Vec<Problem> {
        use netherforge_format::codes;
        let mut out = Vec::new();
        for (namespace, pkg) in &self.packages {
            let manifest = self.manifest_of(namespace);
            let file = format!("settings/{namespace}.json");
            if pkg.unreadable {
                out.push(codes::SETTINGS_FILE.at(
                    &manifest,
                    format!(
                        "The server's {file} isn't a JSON object, so {namespace}'s settings have their defaults"
                    ),
                    Some("$.settings".into()),
                ));
                continue;
            }
            for problem in pkg.misfits.values() {
                out.push(if problem.unknown {
                    codes::SETTINGS_UNKNOWN.at(
                        &manifest,
                        format!(
                            "The server's {file} sets \"{}\", which isn't a setting here",
                            problem.setting
                        ),
                        Some("$.settings".into()),
                    )
                } else {
                    codes::SETTINGS_VALUE.at(
                        &manifest,
                        format!(
                            "The server's {file} sets {} to what it can't be ({}), so it has its default",
                            problem.setting, problem.message
                        ),
                        Some(netherforge_format::json::child_path(
                            "$.settings",
                            &problem.setting,
                        )),
                    )
                });
            }
        }
        out
    }
}

/// A setting's value as a script gets it: a boolean, a number or a string.
pub fn to_lua(lua: &mlua::Lua, json: &Value) -> mlua::Value {
    match json {
        Value::Bool(b) => mlua::Value::Boolean(*b),
        Value::Number(n) => mlua::Value::Number(n.as_f64().unwrap_or(0.0)),
        Value::String(s) => lua
            .create_string(s)
            .map_or(mlua::Value::Nil, mlua::Value::String),
        _ => mlua::Value::Nil,
    }
}

/// The settings as a session's service: read as the project starts, forgotten with each scope.
pub struct SettingsService {
    pub shared: Rc<Shared>,
}

impl Service for SettingsService {
    fn name(&self) -> &'static str {
        "settings"
    }

    fn start(&self, _room: &Room, project: &Define) {
        let log = self.shared.platform.log();
        self.shared
            .settings
            .borrow_mut()
            .define(project, &|m| log.warn(m));
    }

    fn scope_released(&self, scopes: &[ScopeId]) {
        let mut settings = self.shared.settings.borrow_mut();
        for scope in scopes {
            settings.reads.remove(scope);
        }
    }

    fn problems(&self) -> Vec<Problem> {
        self.shared.settings.borrow().problems()
    }
}
