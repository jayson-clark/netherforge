//! What any host of a terrain's script shares (format's `TerrainScripts`
//! companion and `TerrainScriptFailure`): the files a script may run, the
//! paths a `require` may mean, and how a call that failed is told. The host
//! itself (the server's and the editor's Luau) is `netherforge-terrain`.

use std::collections::BTreeMap;

use super::compiled::CompiledScript;

/// A script that failed: its file (`terrain/<id>.lua`), the stage it failed in
/// (`load` for its body, or a stage's name) and the message, which starts with
/// the file and line it failed at when it knows them (a module's, if it failed
/// in one). What it failed at is the file's own result.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ScriptFailure {
    pub file: String,
    pub stage: String,
    pub message: String,
}

impl ScriptFailure {
    /// The file and line the message names, or the script's file and none when it names none.
    pub fn location(&self) -> (String, Option<u32>) {
        let mut parts = self.message.splitn(3, ':');
        if let (Some(file), Some(line), Some(_)) = (parts.next(), parts.next(), parts.next())
            && file.ends_with(".lua")
            && !file.contains(char::is_whitespace)
            && let Ok(line) = line.parse()
        {
            return (file.to_owned(), Some(line));
        }
        (self.file.clone(), None)
    }
}

/// What `script` may run, from the files of its project (or package) by their
/// own paths: the script itself and every module's Lua, which it may
/// `require`. One `read` can't read is left out.
pub fn sources_of(
    script: &CompiledScript,
    files: &[String],
    read: &dyn Fn(&str) -> Option<String>,
) -> BTreeMap<String, String> {
    files
        .iter()
        .filter(|f| **f == script.file || (f.starts_with("modules/") && f.ends_with(".lua")))
        .filter_map(|path| read(path).map(|text| (path.clone(), text)))
        .collect()
}

/// The paths module `name` (as `require` takes it) may be for code at `from`
/// (`@modules/terrain/a.lua`, or the script's own), the modules found where
/// `files` has them (`modules/(world)/rocks/init.lua` is the module `rocks`;
/// one `files` lacks is looked for in `modules/` itself): the requiring
/// module's own files first, then the module by name.
pub fn module_paths<'a>(
    name: &str,
    from: &str,
    files: impl IntoIterator<Item = &'a String>,
) -> Vec<String> {
    let parts: Vec<&str> = name.split('.').collect();
    let part =
        |p: &&str| !p.is_empty() && p.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_');
    if !parts.iter().all(part) {
        return Vec::new();
    }
    let module_kind = crate::kinds::by_id("module").expect("modules are a kind");
    let mut modules = BTreeMap::new();
    for file in files {
        if let Some(found) = crate::kinds::classify(file)
            && found.kind == Some("module")
            && found.package.is_none()
            && let Some(id) = found.id
        {
            let group = found.group.unwrap_or_default();
            modules.insert(id.clone(), module_kind.location_of(&id, &group));
        }
    }
    let location = |id: &str| {
        modules
            .get(id)
            .cloned()
            .unwrap_or_else(|| module_kind.location_of(id, ""))
    };
    let entry = crate::kind::module::ENTRY;
    let joined = parts.join("/");
    let own = crate::kinds::classify(from.trim_start_matches('@'))
        .filter(|c| c.kind == Some("module"))
        .and_then(|c| c.id);
    let mut out = Vec::new();
    if let Some(own) = own {
        let at = location(&own);
        out.push(format!("{at}/{joined}.lua"));
        out.push(format!("{at}/{joined}/{entry}"));
    }
    let module = location(parts[0]);
    let rest = parts[1..].join("/");
    if rest.is_empty() {
        out.push(format!("{module}/{entry}"));
    } else {
        out.push(format!("{module}/{rest}.lua"));
        out.push(format!("{module}/{rest}/{entry}"));
    }
    out
}
