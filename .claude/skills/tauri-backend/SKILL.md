---
name: tauri-backend
description: How the editor's Rust backend (apps/editor/src-tauri) is organised - modules, the path-scoping rules every file command follows, adding a command end to end (tauri-specta generates the UI's bindings and the commands permission), error codes, the backend contract suite shared with MemoryBackend, the dev server's state machine and bridge, the MCP server for coding agents, the nfasset:// and nfproject:// protocols, glyph advances from the client's fonts, opening the launcher, the updater plugins, and the per-OS quirks already handled. Read before changing anything in apps/editor/src-tauri.
---

# The Tauri backend

Small and boring on purpose: files, a watcher, one child process, a socket,
and a cache. Anything that understands project _content_ lives in `format`
(through the UI), never here. The contract with the UI is generated from
here: `commands/bindings.rs` lists every command and event, and tauri-specta
writes the UI's typed bindings from it (`apps/editor/src/core/backend/generated/bindings.ts`).

## Where things are

| Module (`src/`)               | Owns                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| ----------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `commands/*`                  | The Tauri commands. Thin: unpack args, find the open project, call a module. All `async`. `bindings.rs` is the one list of commands and events (and `export`, which `cargo run --example bindings` runs); `contract.rs` is the backend contract suite's host.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `app/tools.rs`                | `Tools`, worked out once at startup and kept in `AppState`: where the plugin jars, the test runner, LuaLS, Java (`JavaEnv`), Paper (`PaperSource`) and the player's installs come from. Commands read it instead of looking things up, so the contract host hands the real commands stand-ins.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `state.rs`                    | `AppState`: dirs, `Tools`, the open project (+ its watcher), the `ServerManager`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `app/dirs.rs`                 | Config dir (`settings.json`, `recent.json`, `eula.json`) and data dir (`jdks/`, `paper/`, `minecraft/` the cache, `servers/<hash>/` each project's dev server).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `app/events.rs`               | `EventSink` (an `AppHandle` in the app, `RecordingSink` in tests) and the events with no other home. An event is its payload type, deriving `tauri_specta::Event` with its name.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `app/plugins.rs`              | Finding `NetherForge-<v>-paper-<mc>.jar` (one per Minecraft version; the project's target picks it) (the bots are in it; an older build's `NetherForgeBots-*.jar` in a server's `plugins/` counts as ours, so an install takes it away): bundled `resources/plugins/`, plus the repo's builds in dev.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `app/test_runner.rs`          | The script test runner: finding `NetherForgeTest-<v>.jar` (bundled `resources/test-runner/`, plus the repo's build in dev), running it with the Java the dev server uses (`--json`, the package cache, the project's cached game data as `--game-data`, a filter) and reading its JSON lines into a `TestReport`; `install_for_cli` copies the jar to `<data>/test-runner/` at startup, where `netherforge test` finds it. `tests_run` (`commands/tests.rs`) needs a trusted project and the project's version's game data in the cache (`cache::game_data_file`); without it, an `unavailable` error telling the user to start the dev server once, never a fallback. With `member` (a workspace member's path), that project's tests instead: `member_root`, its own manifest's version, and `trust::is_trusted` on its root (a trusted workspace trusts it). |
| `error.rs`                    | `Error { code, message }`: an `ErrorCode` the UI can act on and a sentence it shows. `bail!(NotFound, "…")`; `.context(…)` keeps the code underneath.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `fs/scope.rs`, `fs/atomic.rs` | Project paths and atomic writes. Every file command goes through `ProjectRoot::resolve`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `fs/map.rs`                   | `map_capture`: a world `save_world` named, copied from the dev server's folder (a `ProjectRoot` of its own) into `maps/<id>/` as `level.dat` + the map's overworld, leaving out `is_excluded` files (locks, `uid.dat`, Paper's `data/paper/`, players'), staged under `.netherforge/` and renamed into place; `world://capture-progress` events.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `fs/serve.rs`                 | The `nfproject://` resolver: the open project's files and its packages' as URLs (resource pack textures), scoped like `fs_*`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `watcher/`                    | `notify` + a pure `Debouncer` + `map_path`; emits `fs://changed`. Watches the root and each visible top-level folder (never `.netherforge/`, the editor's generated output); lost events become `rescan: true`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `platform.rs`                 | The OS (`Os::current`, its name, its filesystem root) and env-var helpers. The Java, install and launcher searches build their env structs from it, so tests can fake the OS.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `project.rs`, `settings.rs`   | Open/create/recent; settings and EULA records (missing or corrupt file = defaults).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| `trust.rs`                    | Workspace trust: `<config>/trusted.json`, the trusted canonical project roots (see "Workspace trust and the threat model").                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `server/`                     | `java` (find/download Temurin), `paper` (Fill v3), `setup` (the server folder), `process` (lifecycle).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `server/jvm_args.rs`          | The allowlist for the dev server's extra JVM arguments (see "JVM arguments").                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `server/network/`             | The dev network (see "Dev networks"): `layout` (per project, `<config>/networks.json`), `setup` (Velocity's folder, Paper behind it), `NetworkManager` (the proxy and other servers).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| `bridge/`                     | The dev bridge listener and relay (JSON-RPC 2.0 frames). `frames.rs` reads them with a size cap. `protocol.rs` reads only the `hello` request (token, protocol version) and the answers to the backend's own requests (`"backend:<n>"` ids); every other frame passes through as text both ways (`bridge_send`, `bridge://message`), spoken in the UI by vscode-jsonrpc.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `luals/`                      | lua-language-server for the open project: finding it (`search_paths`), `LanguageServer` (one process, generations, stdio), `framing.rs` (LSP's `Content-Length` frames), `plugin.lua` (NetherForge's LuaLS plugin). See "lua-language-server".                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| `mcp/`                        | The HTTP pipe for the UI's MCP server (the official SDK): `http.rs` (Streamable HTTP, stateless, POST only, loopback Host/Origin, the bearer token), `token.rs` (the per-install token), `UiPipe` (`mcp://message` → `mcp_send`).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| `minecraft/`                  | Install detection, client-asset import, the cache, the `nfasset://` resolver.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| `minecraft/fonts.rs`          | Default-font glyph advances from the cached font JSON + bitmaps (`glyph-advances.json`).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `minecraft/launcher.rs`       | Finding and starting the player's launcher (pure `find` over a `LauncherEnv`, tested per OS).                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |

## Path scoping (non-negotiable)

A project path is relative, `/`-separated, with no empty/`.`/`..` segments,
no `\`, no `:` (drive letters, NTFS streams), no segment ending in a dot or
a space (Windows drops them, so `x.` is `x` there) and no name Windows
reserves (`CON`, `PRN`, `AUX`, `NUL`, `COM1`-`COM9`, `LPT1`-`LPT9`, any case,
with or without an extension), on every OS, so a project opens the same
everywhere. `ProjectRoot::resolve` checks that syntax (`scope::segments`), then canonicalizes the deepest _existing_ ancestor of the target
and requires it to be under the canonical root, which catches symlinks that
point out (including dangling ones and a symlinked parent of a new file).

- Never `root.join(user_string)` anywhere else. Versions that become path
  segments (`mc_*`, `nfasset://`) go through `version::is_release` first.
- `.git` and `.netherforge` are matched by `scope::is_named`: Unicode
  lowercase, after dropping trailing dots and spaces, on every OS (macOS and
  Windows volumes are case-insensitive, so `.GIT/hooks` is `.git/hooks`). Windows
  8.3 short names (`GIT~1`) aren't matched; a project folder on a volume with
  them is the user's own risk, and git itself refuses them since 2.24.
- `fs_list` and the watcher hide `.git` (any depth) and the top-level
  `.netherforge/`. Writes, deletes and renames are narrower still
  (`fs::is_writable`): never `.git` (a hook runs on the next commit), and in
  `.netherforge/` only `UI_FOLDERS`: `schema/` (the UI's JSON Schemas), `docs/`
  and `bin/` (what coding agents read and run), `luals/` (the project's
  names for lua-language-server: `---@meta` stubs, data LuaLS parses, never
  runs), and `thumbnails/` (the explorer's cached centity pictures). `MemoryBackend` enforces the same.
  The dev server's folder isn't under the project at all (see the lifecycle
  below), so no file command reaches it.
- Writes are temp file (`.<name>.nftmp-<hex>`) + rename. The watcher and
  `fs_list` ignore those names; keep it that way or saves flicker in the UI.

## JVM arguments

The dev server's extra JVM arguments (Settings → Server) come from the
webview, and a JVM argument can run code (`-javaagent:`, `-agentpath:`,
`-XX:OnOutOfMemoryError=cmd`) or touch files (`-Xlog:...:file=`,
`-XX:HeapDumpPath=`). `settings_set` and `server_start` (the file may be hand
edited) check each against `server/jvm_args.rs` and refuse one that isn't allowed
with `ErrorCode::JvmArgument`; the settings page shows the message by the
field. Allowed: `-Xmx`/`-Xms`/`-Xmn`/`-Xss` with a size; `-XX:` switches and
numeric options from two fixed lists (GC choice and tuning, heap sizing, OOM
exit, never a path or a command); `-Dname[=value]` for a name of letters,
digits, `_ . -` outside the namespaces that change the JVM, its libraries or
the editor (`java.`, `jdk`, `sun.`, `user.`, `os.`, `netherforge.`, `log4j`...),
with no control characters in the value; and `-server`, `-Xshare:auto|off`,
`-verbose:gc`, `-Xlog:gc`, `-Xlog:gc*`. To allow another flag, add it to the
list there and in `MemoryBackend` (`jvmArgumentProblem`), with a contract
case.

## Workspace trust and the threat model

This is the editor's half of NetherForge's threat model; the server's half
(what a project's scripts can do on a server) is in the plugin-capabilities
skill's "Threat model".

**Trusted:** the editor's own binary and what it bundles (the plugin jars,
lua-language-server, `plugin.lua`), the user's config and data folders, what
the editor downloads and checks (Temurin, Paper by SHA-256, the client's
assets from the user's own install), and the user.

**Not trusted:** a project folder and everything in it (anyone can hand you
one: its manifest, scripts, `.luarc.json`, `.mcp.json`, lock, `.gitignore`),
the packages it depends on (folders beside it, git repositories and what
they hold), whatever a dev server sends back over the bridge (the plugin runs
project code), and any local program reaching the MCP port.

**Boundaries, and what each enforces:**

| Boundary                                                                    | Keeps out                                                                                                                                                                                                                                                                                                                                                                                                            |
| --------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Path scoping (`ProjectRoot::resolve`, `is_writable`)                        | a project path escaping its root (`..`, links), writes to `.git` (hooks) or the editor's generated folders                                                                                                                                                                                                                                                                                                           |
| Workspace trust (`trust.rs`, below)                                         | running anything of a project the user hasn't vetted: its dev server, LuaLS (a `.luarc.json` can name a Lua plugin LuaLS runs), git aimed at its URLs, agents acting on it                                                                                                                                                                                                                                           |
| The server folder outside the project (S1)                                  | jars from the project reaching the classpath: Paper and the plugin come from the cache and the editor                                                                                                                                                                                                                                                                                                                |
| `fs/git.rs`                                                                 | a repository's hooks, filters, submodules, links and odd paths; URLs and revs git would read as options                                                                                                                                                                                                                                                                                                              |
| `mcp/http.rs`                                                               | other users and web pages: loopback only, the bearer token, `Host`/`Origin` checks, JSON bodies only                                                                                                                                                                                                                                                                                                                 |
| The bridge's hello and frame caps                                           | anything but the server this editor started (its token, `127.0.0.1`); a peer that sends an endless line (16 KiB before the hello, 64 MiB after)                                                                                                                                                                                                                                                                      |
| `server/jvm_args.rs`                                                        | the webview setting JVM arguments that run code or touch files (`-javaagent:`, `-XX:OnOutOfMemoryError=`, `-Djava.class.path`)                                                                                                                                                                                                                                                                                       |
| The webview                                                                 | project content as code: format only parses data, the CSP allows no remote script (`'wasm-unsafe-eval'` compiles WebAssembly only, never JS); the one Lua it runs is a terrain's script in the preview's worker, on wasmoon's Lua in WebAssembly with only the generator's host functions (no files, network or JS) and a budget a call, which is why it runs for an untrusted project too (editor-resource-editors) |
| The plugin's sandbox and requirements (plugin-sandbox, plugin-capabilities) | a trusted project's scripts doing more than the Lua API and their declared `requires` allow, on the dev server as on production                                                                                                                                                                                                                                                                                      |

**Trust** is VS Code's workspace trust. A project opened from a folder
the user hasn't trusted opens and edits (files, validation, previews, the
explorer: format only reads data), but the backend refuses, with
`ErrorCode::Untrusted`, whatever runs or reaches out on its behalf:
`server_start` and `luals_start` (`AppState::trusted_root`), `package_fetch_git`
past the cache (a pinned checkout already there is read as it is; `git::fetch`
takes the trust answer and checks it only before touching a repository), and
every MCP request (`UiPipe::set_refusal`, kept in step by
`AppState::refresh_agents` on open, close and trust; a request is answered
with JSON-RPC error `-32001` and never reaches the UI's tools). The UI hides
nothing it relies on: the refusal is the backend's.

- **Kept** in `<config>/trusted.json` (`{ projects: [{ root, trustedAt }] }`),
  keyed by the canonical root `ProjectRoot` gives, so a link to a trusted
  folder is trusted and a copy or a move is not. A missing or unreadable file
  trusts nothing.
- `ProjectInfo.trusted` carries the answer on open; `project_trust(trusted)`
  sets it for the open project (and `false` stops its server and LuaLS);
  `project_create` trusts what the user just made. Re-opening the open
  project keeps the answer it has.
- A new command that runs project content, starts a process for it, or
  reaches the network for it takes `state.trusted_root()?` instead of
  `state.root()?`, and gets a contract case that it's refused untrusted.

## Commands, events and their types: tauri-specta

`commands/bindings.rs` is the one list. From it come the invoke handler
(`lib.rs` hands Tauri `builder().invoke_handler()`), the UI's bindings
(`apps/editor/src/core/backend/generated/bindings.ts`: a `commands` object,
an `events` object and every payload type) and the permission that lets
the window call the commands (`permissions/generated/commands.toml`, one
permission named `commands` allowing each one, which
`capabilities/default.json` grants). Both files are committed and
generated by `cargo run --example bindings` (`pnpm generate` runs it); the
generated-file check in `pnpm lint` reruns it with `--out` and fails when
they're stale. `build.rs` is plain `tauri_build::build()`: the permission
file is all it needs to know.

- **specta 2 is a release candidate**, so `specta`/`tauri-specta` are pinned
  with `=` (as tauri-specta asks). Bump them together.
- **Types**: every payload derives `specta::Type` next to `Serialize`, with
  `#[serde(rename_all = "camelCase")]`. u64s are exported as `number`
  (`dangerously_cast_bigints_to_number`: nothing we send nears 2^53). JSON
  the backend passes through without reading (game data, MCP messages) is
  `commands::Json` or a field marked
  `#[specta(type = specta_typescript::Unknown)]`: `unknown` to the UI, which
  types it with format's generated contract. Don't put `serde_json::Value`
  in a command's signature: specta's type for it is recursive and overflows
  the exporter's stack. Don't give a contract type `#[serde(default)]`
  either (it makes every field optional in TypeScript); `settings::load`
  reads a file over the type's defaults instead.
- **Events** are their payload type: `#[derive(Serialize, specta::Type,
tauri_specta::Event)]` with `#[tauri_specta(event_name = "fs://changed")]`,
  listed in `collect_events!`. Emit with `sink.emit(&payload)` (the name comes
  from the type); assert with `RecordingSink::named::<Payload>()`.
- **Errors**: a command rejects with `Error { code, message }`, serialized as
  `{ code, message }` and exported as `CommandError`. `TauriBackend` rethrows
  it as a `BackendError` with the same `code`. Give a new failure the most
  specific `ErrorCode` (`bail!(AlreadyExists, "{to} already exists")`); a
  new code is a variant in `error.rs` and a regenerated binding. The UI
  checks `code`, never the message.
- **Generic runtime**: a command that needs the app takes `AppHandle<R>`
  for `R: Runtime` and is listed as `name::<$runtime>`, so the same list
  builds for the app (`builder()`, Wry) and for the contract suite
  (`mock_builder()`, Tauri's `MockRuntime`). That's why the list is a macro:
  `collect_commands!` can't name an outer generic parameter.

### Packages (`fs/package.rs`)

The packages a project depends on are read-only folders outside it, named by
their location: relative to the project root (`../library`), or
`git:<commit>`, a git package's checkout in the package cache
(`AppDirs::packages`, `<data>/packages`), as format resolves them; the
backend never reads `dependencies` itself (the UI asks format which folders
it needs and which repositories to fetch). `package::open` is the one place a
location becomes a root: `git:` and a full commit (`git::location_dir`) is
`git/checkouts/<commit>/` in the cache (`notFound` until fetched); anything
else is relative, `/`-separated, `..` allowed, no backslash, drive
letter, `:` or empty segment, and the canonical folder must hold a
`netherforge.json`; the result is a `ProjectRoot` of its own, so every path
inside it is scoped exactly like a project's (a link out of the package is
refused). `package_files(location)` lists it as `fs_list` lists a project,
each file with the hex SHA-256 of its bytes (the per-file half of format's
`PackageHash`: the webview hashes format's listing of them);
`package_read_text(location, path)` reads one; `package_copy(location, from,
to)` copies a file or folder into the project (only what the listing sees),
built under a temp name beside the target and renamed into place, never over
anything, the target held to `is_writable`. Failures are `invalidPath` (a location or path that isn't one), `notFound`
(no folder, or no `netherforge.json` in it) and `alreadyExists`; the contract
suite covers them with a package beside its project (`packages` in
`contract.json`, at `../<name>`).

**Git packages (`fs/git.rs`).** `package_fetch_git(url, rev, commit)`
fetches a dependency into the cache and answers the commit: a pinned
`commit` already checked out returns at once (no network, no open project
needed); otherwise the URL's bare repository (`git/db/<16 hex of the URL's
SHA-256>/`, made with an empty template: no hooks) fetches the commit by id
(falling back to the `rev` when the server won't serve ids) or the `rev`
(`HEAD` without one) into a temp ref, keeps it as
`refs/netherforge/commits/<commit>`, and writes the commit's regular files
out of `ls-tree` + `cat-file --batch` into `git/checkouts/<commit>/` (staged
and renamed). It shells out to the user's `git` (credentials, SSH keys and
proxies work as for everything else; npm, pnpm and Go do the same; the CLI's
`apps/cli/src/git.ts` is the same steps, and both must write the same files
or the hash differs). **The threat is the repository**, so nothing of it is
run or obeyed: no checkout (no `.gitattributes` filter or LFS smudge), no
submodules, links skipped, a path with `..`, `.git` (any case), `\` or `:`
refuses the commit, URLs held to https/ssh/file/scp-like (`is_url`, format's
rule, plus `GIT_ALLOW_PROTOCOL`), `rev`s that can't read as options or
refspecs (`is_rev`), `GIT_TERMINAL_PROMPT=0` and stdin closed. Failures:
`invalid` (URL, rev or commit), `network` (git's last line), `notFound` (the
repository lacks the pinned commit), `unavailable` (no git). A dev server
gets the cache as `-Dnetherforge.packages` and only reads checkouts. The
contract suite's `repositories` are real bare repositories on the Rust side
(`$GIT/<name>`, a file URL) and `gitRepos` on the memory backend; a step's
`save` keeps an answer (the commit) for later steps as `$<name>`.

## Adding a command end to end

1. **Logic in a module**, unit-tested with temp dirs. No Tauri types there.
2. **Command** in `commands/<area>.rs`: `#[tauri::command] #[specta::specta]
pub async fn`, returning `crate::error::Result<T>`. Payload types derive
   `specta::Type`. Blocking work goes through `commands::blocking`.
3. **List it** in `commands/bindings.rs` (and an event in `collect_events!`),
   then `pnpm generate`. That's the handler, the binding and the permission.
4. **UI side**: the `Backend` method, `TauriBackend` calling
   `commands.<name>(…)` through `call` (or `done` for a command answering
   nothing), and `MemoryBackend` (see the editor-ui skill).
5. **Contract**: cases in `apps/editor/src/core/backend/contract.json` for
   what both backends must do (results, error codes, events), calling the
   new `Backend` method; the guard in `contract.test.ts` fails until it's in
   a case or in `EXCLUDED` with why. See "The contract suite".

Bridge frames are relayed as text both ways, so a new request, notification
or stream needs no Rust change: declare it in format's `Bridge.kt` and the UI
gets its types from the generated contract. Rust changes only with the
handshake (the hello, `PROTOCOL`, which `protocol.rs` checks against the
recorded session) or the backend's own requests.

## The contract suite

`MemoryBackend` (the UI's tests run on it) is checked against these
commands by one case list, `apps/editor/src/core/backend/contract.json`, in
the UI's terms: each case is a fresh backend with a small project at `$ROOT`
and steps that call `Backend` methods, listen to its events and wait for
one matching, change files as another program would, send an agent's MCP
request, or fetch a URL the backend built (`contractRunner.ts` documents the
format). Two runs, both vitest:

- `contract.test.ts` on `MemoryBackend`.
- `contract.tauri.test.ts` on `TauriBackend`, with `@tauri-apps/api`'s IPC
  mocked (`mockIPC`) to forward each command to the **contract host**, the
  ignored test `commands::contract::host`, which it starts with `cargo test`
  and which connects back over `NETHERFORGE_CONTRACT_HOST`. The host runs
  each command through Tauri's own IPC (`tauri::test::get_ipc_response`) on
  the mock runtime, built with the app's real context (`crate::context()`,
  so the capability and the generated permission are checked too) and a real
  `AppState` over temp dirs, and forwards every event the app emits (from
  `bindings::event_names()`) for the UI's listeners. So `TauriBackend`'s
  mapping onto commands, deserialization, the commands and the events are all
  the app's. Only the window is fake, and what the app finds on the computer
  (`app::tools::Tools`) are stand-ins: `examples/fake_server.rs` as the only
  Java (so the dev server starts, its "plugin" says hello on the bridge, and
  the test runner prints the case's report), an empty Paper jar, a plugin jar
  for 26.3, no LuaLS, and a fixture home for installs. A case that listens to
  `onFilesChanged` makes the host wait for the watcher to be live (a probe
  file's event, never forwarded) after opening.

When they disagree, **Rust is the truth**: fix the memory backend. Each case
starts with nothing trusted, as the app does (`trusted: []` on the memory
backend); a case that runs the server trusts the project first
(`trustProject`). `contract.test.ts` holds every `Backend` member to a case or
to `EXCLUDED` with a reason (native dialogs, the window, menus, zoom, the
updater, the launcher, LuaLS's events); those have `tauri.test.ts` and
`tauriMenu.test.ts` against mocked plugin IPC. A real Paper server stays in
`-- --ignored` (`boots_real_paper`).

## The dev server lifecycle

`stopped|crashed → preparing → starting → running → stopping → stopped`;
unexpected exit from `starting`/`running` → `crashed` with the exit code.

- **Preparing**: EULA check (refuse with a clear error; `eula.txt` is only
  written after `server_eula_accept`), plugin jar for the target version,
  Java ≥ 25 (else Temurin into `<data>/jdks/`), Paper (the build the plugin
  jar names in `META-INF/netherforge/paper.properties`, its adapter's pinned
  `serverBuild`, never `builds/latest`: sha256-verified, cached as
  `<data>/paper/paper-<v>-<build>.jar`, which then needs no network; a jar
  without that file is refused, asking for a rebuild), the server folder
  (`setup::prepare`: eula.txt, server.properties with the port forced and user edits kept, `bukkit.yml`'s
  `worlds.<level-name>.generator: NetherForge` set exactly when the project's
  `netherforge.json` names a `worlds.<level-name>.generator` (`setup::bukkit`,
  through `serde_norway`; only that key is ours, a file the server couldn't
  read is left alone, and the plugin asks for the restart that a change needs:
  see plugin-world "Terrains"), the plugin in `plugins/` (any other NetherForge jar removed),
  `netherforge-project.txt` naming the project), and a port check so
  "address in use" is a sentence, not a crash.
- **The server folder is `AppDirs::server_dir`**: `<data>/servers/<first 8
bytes of sha256(canonical project root), hex>/`, never inside the project.
  The project is untrusted (anyone can hand you one): when the server lived
  in `<project>/.netherforge/server/`, a project could ship its own
  `paper.jar` (with the marker that made the editor keep it) or jars in
  `plugins/`, and Start ran them. Now nothing from the project reaches the
  classpath: Paper runs straight from the cache (`-jar <data>/paper/…`, there
  is no `paper.jar` copy), the plugin comes from the editor's build, and the
  project's path is only `-Dnetherforge.project`, which the plugin reads as
  data. World, logs and the plugin's state (`plugins/NetherForge/`, with
  `nf.files`' `data/`) live in the server folder too. Jars a user put in that
  folder's `plugins/` themselves stay (only other NetherForge jars are
  replaced). `setup::tests::nothing_in_the_project_reaches_the_server` is the
  guard.
- **Launch**: the bridge listens on `127.0.0.1:0` _before_ Java starts; the
  port goes in `-Dnetherforge.bridge.port`, a random token in
  `NETHERFORGE_BRIDGE_TOKEN`. The server folder is the working directory.
  Each output line loses its ANSI escapes (the `strip-ansi-escapes` crate)
  before it's shown; `Done (<digit>` on stdout → `running`.
- **Generations**: every start bumps a counter; readers, the exit monitor and
  bridge callbacks carry theirs and are ignored once stale. `stop` during
  `preparing` just bumps it (the start bails at its next check).
- **Stop**: `stop` on stdin, wait 30 s, then kill. A console `stop` goes the
  same way so it isn't reported as a crash. The app's `RunEvent::Exit` and
  `project_close`/opening another project stop the server; `kill_on_drop`
  is the backstop.
- **When the editor dies without cleaning up** (a crash, a force-quit, a
  `tauri dev` rebuild), none of the above runs and the JVM is orphaned. Two
  things cover it: the plugin stops the server itself once its editor has
  been gone 15 s (see the hot-reload skill), and `server/leftover.rs` stops a
  leftover on the next start. A started server's pid goes in
  `<server folder>/netherforge-server.pid`; a pid only counts as a leftover
  if that process's command line has this project's `-Dnetherforge.project=`
  (pids get reused), and it's asked to stop (SIGTERM, which Paper treats as
  `/stop`) before being killed after 30 s.
- **Bridge**: first frame must be a `hello` request with the token within
  10 s or the socket is closed unanswered. A frame is capped
  (`bridge::MAX_FRAME_BEFORE_HELLO` 16 KiB, `MAX_FRAME` 64 MiB after; the game
  data export is about 7 MB): `FrameReader` never buffers past the cap, and a
  longer frame closes the connection and sends the reason (`frames::too_large`)
  to the console through `BridgeHandler::refused`. The plugin caps what it reads
  from the editor too (16 KiB before the answer, 16 MiB after); a hello of another protocol
  version is answered with the refusal and closed, and the sentence goes to
  the console; a newer accepted connection replaces the old one. The UI's
  frames (`bridge_send`) and the plugin's (`bridge://message`) are relayed as
  text; the UI's JSON-RPC client owns ids and timeouts. The backend's own
  requests (ids `"backend:<n>"`, 5 min) fail immediately when the connection
  drops. On hello, if the cache has no
  `server/game-data.json` for that version, or one whose `schema` isn't
  `cache::GAME_DATA_SCHEMA` (`cache::needs_export`), the backend exports and
  stores it, then emits `mc://cache-changed`. A cache of another schema reads
  as none (`read_game_data` is None, `status().server` false), and an export
  of another schema is refused rather than stored.

## Workspaces

`workspace.rs`: several projects under one `netherforge-workspace.json` (docs/format/workspace.md).
Opening a project looks up to 6 folders above it for the file and, if the file lists the project,
answers it as `ProjectInfo.workspace`; opening the workspace's own folder (a file and no `netherforge.json`) opens `entry_project` instead, the arrival server's member or the first non-package (`commands/project.rs` `workspace_entry`, mirrored in `MemoryBackend.openProject`) (`WorkspaceInfo`: root, name, trust, members, network). The
file is read leniently (format, in the UI, says what's wrong): patterns are a folder or a folder
and `/*`, never `..`, absolute or a backslash (`is_pattern`, format's `Workspaces.isPattern`), and
a member's canonical folder must be inside the workspace's, so the (untrusted) file can't name
anything elsewhere. **Trust** is the workspace's: `trust::set_workspace` records its folder in
`trusted.json`'s `workspaces`, and `is_trusted` counts any folder under a trusted workspace
(component-wise, so `polsar-other` isn't under `polsar`). Commands (`commands/workspace.rs`):
`workspace_trust` (answers the project; untrusting stops servers and LuaLS), `workspace_read`,
`workspace_write` (format's canonical text, then answers the workspace as it reads now),
`workspace_info`, `workspace_write_schema` (the only file written in a workspace's own
`.netherforge/`), and `workspace_network_enabled`/`workspace_set_network_enabled` (this
computer's switch, in `networks.json` keyed by the workspace's folder), and
`workspace_create_member` (a new project at a path relative to the workspace's folder, scoped as a
project path and refused under `.git`/`.netherforge` like a write, missing or empty, not opened;
the UI names it in the file). Each refuses `notFound` for a project that isn't in one.

**A workspace's network** (the file's `network`, switched on here): `network::plan_workspace` makes
every server a member (`Role::Peer`, its world in `<data>/networks/<workspace>/servers/<name>/`,
so moving a project keeps it; the arrival server first in Velocity's `try`), and the editor is
**attached** to the member running the open project (`NetworkManager::attach`): that member's
`server://state`, `server://output`, progress and `bridge://message` are also the editor's own
(`MemberSink`), and `server_state`, `server_command`, `bridge_send` and `map_capture` (its
`ServerManager::folder`) go to it while one is attached. Its watcher doesn't reload it (the UI's
saves do); the others' do. `state.server` (the solo server) isn't used. Opening another member of
the same workspace while its network runs keeps it running (`project_open`: no `close_project`,
LuaLS stopped, the editor re-attached by `server_running`, or to none, which answers a stopped
`server://state` saying so). `NetworkMember.attached` says which. Tests:
`runs_a_workspaces_network_attached_to_the_open_project` and two contract cases (`setup.workspace`
writes the file above the case's project, so it and its packages are a workspace).

## Dev networks

`server/network/`: the open project's server behind a Velocity proxy that runs
the proxy plugin (`NetherForge-<v>-velocity.jar`, the network's hub), beside
other servers, each running a project folder of its own. Start and Stop
(`server_start`/`server_stop`, `AppState::stop_servers`) run all of it when the
layout is `enabled`.

- **The layout** (`layout.rs`): `{ enabled, name, servers: [{ name, project }] }`,
  the user's on this computer, in `<config>/networks.json` keyed by the
  canonical root like trust. Names are `[a-z0-9_-]{1,32}` (a folder name and a
  `velocity.toml` key; never `proxy`, the proxy's member name), at most 8 other
  servers. A project is relative to the open project (`../lobby`), absolute, or
  `.`, resolved at each start to a canonical folder holding a `netherforge.json`.
  `network_layout`/`network_set_layout`/`network_check` (each server's folder,
  trust, version and problem) need only an open project.
- **Trust**: every other server's folder must be trusted (it runs that
  project's scripts); `network::plan` refuses `untrusted` before anything starts
  (every folder is resolved first, so `notFound` wins). `network_trust(name)`
  trusts the folder a layout server runs, only from a trusted project.
- **The plan** (`network::plan`): the secrets (`setup::secrets`, kept in the
  proxy's folder: `forwarding.secret` and `netherforge-hub.secret`), a free
  127.0.0.1 port for the hub and each server, the main server's `Membership`
  and a `MemberPlan` per member (the proxy first). The proxy needs the proxy
  plugin jar (found like the Paper jars, `plugins::find_proxy`; the bundle
  carries it beside them) or the start is `unavailable`.
- **Roles** (`process::Role`): `Main(Option<Membership>)` (the project's own
  server, its own folder; in a network it listens on its free port and reports
  Settings' port, where players join the proxy), `Peer { membership, dir }`
  (`<data>/networks/<hash>/servers/<name>/`; fetches its lock's git packages
  itself, `fetch_locked_git`, since the UI fetches only the open project's) and
  `Proxy` (`<data>/networks/<hash>/proxy/`; Velocity from Fill at the build the
  proxy plugin's `META-INF/netherforge/velocity.properties` names, cached as
  `paper/velocity-<v>-<build>.jar`; 512 MB; no bridge; `-Dnetherforge.project`
  only so a leftover is recognised). One `ServerManager` per member: the same
  lifecycle, generations, stop and leftovers as the main server.
- **Joining a server**: `-Dnetherforge.network.hub=127.0.0.1:<port>`,
  `-Dnetherforge.network.server=<name>` and `NETHERFORGE_NETWORK_SECRET` (the
  plugin's dev-only override of `config.yml`'s `network:`), and
  `setup::paper_member`: `server-ip=127.0.0.1`, `online-mode=false`,
  `config/paper-global.yml`'s `proxies.velocity` on with the forwarding secret
  (Paper writes its defaults around a file holding only that), marked with
  `netherforge-network.txt` so a solo start puts `online-mode`, `server-ip` and
  Velocity back. `velocity.toml` is written whole each start (Velocity's
  defaults fill the rest): `bind 0.0.0.0:<Settings' port>`, online mode, modern
  forwarding, `[servers]` with `try` the main server.
- **Members' events**: each member's `ServerManager` emits through a
  `MemberSink`, which turns `server://state` into `network://member` (the
  member's snapshot) and `server://output` into `network://output` tagged with
  its name, and drops its `bridge://message` and progress (the UI's bridge is the
  main server's). `network://members` is the whole list when a network starts
  or goes away (`clear`: a solo start, closing the project).
- **Reloads**: a peer's plugin connects to a bridge of the backend's own (which
  also keeps it alive: a plugin stops its server once its editor is gone); a
  watcher on its project sends `reload` with the changed paths (a rescan is
  `netherforge.json`) through `ServerManager::bridge_request`, and the result
  is a console line.
- **Tests**: unit tests per module, `runs_a_network_and_reloads_its_other_servers`
  (the fake server as proxy and peer), two contract cases, and the ignored
  `runs_a_real_network` (real Java, Paper, Velocity and the repo's jars: both
  servers connected per `/nfhub`).

## lua-language-server

The editor's Lua features are LuaLS speaking LSP; the backend only runs it
and moves messages (the UI side is in the editor-ui skill).

- **Commands**: `luals_start` (starts it in the open project's root,
  replacing one that runs, and returns `{ generation, plugin }`),
  `luals_send(generation, message)` (one JSON-RPC message, framed onto its
  stdin; refused for another generation), `luals_stop`. Every message it
  writes is `luals://message { generation, message }` (the JSON as a string,
  untouched); exiting on its own is `luals://exit { generation, code }`, a
  stop or a replacement isn't reported. Closing or switching the project
  and `RunEvent::Exit` stop it; `kill_on_drop` is the backstop.
- **The binary**: LuaLS isn't one executable (`bin/lua-language-server` runs
  `bin/main.lua`, which loads `script/`, `meta/`, `locale/`), so it can't be
  an `externalBin`: the whole release folder is a bundle resource,
  `lua-language-server/` (`tauri.bundle.conf.json`, packaging only, like the
  plugin jars). `tools/luals.mjs` fetches the pinned version for an OS,
  checksummed, into `src-tauri/lua-language-server/` (gitignored), which
  debug builds run directly; `tauri dev` runs it first (`--optional`: offline,
  Lua just has no language features). `luals::search_paths` looks in the
  resources, then (debug) that folder. Missing is a sentence, and the UI
  carries on without it.
- **What it writes** goes in the data folder, `<data>/luals/` (`--metapath`
  for its generated std-library stubs, `--logpath`), never the app's own
  folder, which is read-only and signed.
- **The plugin** (`plugin.lua`, `include_str!`'d, written to
  `<data>/luals/plugin.lua` on start and passed to the client as
  `Lua.runtime.plugin`): `ResolveRequire` resolves `require` from a file in
  a resource folder as the server does, beside the resource's script first
  (read from its main file's `script.file`), then falling through to
  `runtime.path` (`modules/`); and `require("library:greetings")`, a
  package's module, to the UI's stub of it under `packages=<folder>`
  (`.netherforge/luals/packages/library/greetings/init.lua`). Its arguments
  are each resource folder and main file (`centities=centity.json`) and that
  `packages=` folder, from the UI (`pluginArgs`). LuaLS asks before
  loading a plugin unless the client says it trusts it, which the editor's
  does. It's in the data folder, not the project, because the webview can't
  write anything that runs.

## The MCP server

`mcp/` serves coding agents at `http://127.0.0.1:<mcpPort>/mcp` (settings
`mcpEnabled`, default on, and `mcpPort`, default 47615, fixed so a project's
`.mcp.json` keeps working). It starts in `setup` and `settings_set` restarts it
when either changes (or retries a port that was taken); a taken port is
`McpStatus.error`, never a startup failure.

- **Rust is only the HTTP pipe.** The MCP server is the official TypeScript
  SDK's `McpServer` in the UI (`src/core/mcp/tools.ts`, tools registered with
  `registerTool` and zod schemas, which the SDK publishes as JSON Schema and
  checks every call against), next to the stores and format. The SDK does
  the whole protocol: initialize and version negotiation, ping, errors.
  `mcp/http.rs` checks the request and frames the body; `UiPipe` emits each
  JSON-RPC message as `mcp://message {message, protocolVersion}` and the
  UI's `BackendTransport` (`src/core/mcp/transport.ts`, the SDK's
  `Transport` interface) answers through `mcp_send`. A request's id is
  swapped for the pipe's own on the way in (agents' ids collide) and
  restored on the way out. No answer in 120 s is a JSON-RPC error, never a
  hang. A new tool needs no Rust change; nothing here knows MCP's methods or
  versions (the transport checks `MCP-Protocol-Version` against the SDK's
  list).
- **Stateless Streamable HTTP**: every message is a `POST` answered with one
  JSON body (202 when the body holds no request); `GET`/`OPTIONS` get 405,
  there are no sessions and no SSE, and what the SDK sends that isn't a
  response (notifications) is dropped. Batches are accepted.
- **A bearer token on every request** (`mcp/token.rs`): 32 random bytes,
  per install, in `<config>/mcp-token` (0600). Missing or wrong is `401`
  with `WWW-Authenticate: Bearer`, checked before method or body, compared
  in constant time. `McpStatus.token` gives it to the UI, which writes it as
  a header into `.mcp.json` (`core/mcp/config.ts`; created by Settings →
  Agents, brought up to date on every project open; the project template's
  `.gitignore` leaves `.mcp.json` out). Per install, not per run: agents
  read `.mcp.json` when their session starts, so a per-run token would break
  open sessions on every editor restart and rewrite a project file each
  launch. It keeps out other local users and programs that can reach the
  loopback port but not this user's files.
- **Guarded against web pages** too: binds 127.0.0.1, refuses a non-loopback
  `Host` (DNS rebinding) or `Origin`, and any non-JSON body (a cross-site
  "simple" POST).
- **Refused while the open project isn't trusted** (`UiPipe::set_refusal`,
  error `mcp::UNTRUSTED`, -32001): agents would otherwise start its server
  or run its commands unattended. The UI doesn't write `.mcp.json` (which
  carries the token) into an untrusted project either.

## Per-OS quirks already handled

- **Windows**: `rename` over a file another process holds open fails with
  access denied → retried ~1 s (`fs/atomic.rs`). Data goes in
  `%LOCALAPPDATA%` (a JDK must not roam). `dunce` strips `\\?\` from
  canonical paths. Child processes get `CREATE_NO_WINDOW`. Custom protocols
  are served as `http://nfasset.localhost/...`.
- **macOS**: FSEvents reports `/private/var/...` for a root opened as
  `/var/...` (the watcher maps both). The filesystem is usually
  case-insensitive: a case-only rename is allowed, and install folders are
  deduplicated by inode (`ATLauncher` = `atlauncher`). `/usr/bin/java` is a
  stub that fails without a JDK; JDK homes are `*.jdk/Contents/Home`.
- **All**: `tauri-build` copies `bundle.resources` even for `cargo build` and
  fails when a glob matches nothing, so the plugin-jar glob lives in
  `tauri.bundle.conf.json`, merged only for packaging
  (`pnpm tauri build --config src-tauri/tauri.bundle.conf.json` from `apps/editor/`).

## `nfasset://`

`nfasset://localhost/<version>/<path under client/>` (macOS, Linux) or
`http://nfasset.localhost/<version>/<path>` (Windows). The handler
percent-decodes the whole path first (so `convertFileSrc` output works too),
refuses escapes with 400, serves 404 for missing files, and sends
`Access-Control-Allow-Origin: *` so WebGL can use the textures. Besides the
jar's files, an import writes `index.json` (blockstate/item/font ids) for
pickers.

## `nfproject://`

`nfproject://localhost/<project path>` (macOS, Linux) or
`http://nfproject.localhost/<project path>` (Windows): the open project's
files, for `<img>` (resource pack textures, skins in previews). The handler takes the
project root from `AppState` per request, percent-decodes, refuses hidden
paths (`.git`, `.netherforge/`, except `fs::THUMBNAILS`, the cached pictures
the UI shows as images) with 404, and resolves through
`ProjectRoot::resolve` (400 on escapes). `?v=<n>` is the UI's cache buster
and isn't part of the path. Same CORS/no-cache headers as `nfasset`, so the
UI can read a texture's pixels (a skin's advance needs its alpha).
A package's file is `/:package/<location>/<path>`: the location (`../library`)
one percent-encoded segment (so `..` is never a dot segment the URL would
resolve away), split off the raw path before decoding, opened as every
package command opens one (`package::open`, which wants a `netherforge.json`
there), then resolved inside it like a project path, hidden files refused.
A project path never holds `:`, so the two can't be confused.
Binary writes are `fs_write_bytes` (`bytes` as a JSON number array: textures
are small and it needs no base64 crate), atomic like `fs_write_text`.

## Glyph advances

`format`'s `TextMetrics` needs the default font's advances, which only the
client has. `minecraft/fonts.rs` computes them the game's way: start at
`assets/minecraft/font/default.json`, follow `reference` providers (a
provider whose `filter` asks for an option to be on, `uniform`/`jp`, is
skipped: options default off), first provider to define a character wins;
`space` providers give advances directly; `bitmap` providers cut the PNG
(`<ns>:<path>` under `textures/`) into a grid of `chars` rows (`\0` = empty)
and each glyph's advance is `floor(0.5 + width × height/cellHeight) + 1`,
`width` being the rightmost column with any non-zero alpha, plus one.
`unihex`/`ttf` are skipped (format estimates what's missing). The import
writes `client/glyph-advances.json` (`{ "<code point>": px }`); failures
don't fail the import. `mc_glyph_advances` serves it, computing and storing
it on first use for imports made before it existed. The PNG decoding is the
`png` crate, normalised to 8-bit with real alpha (palettes, tRNS).

## Opening the launcher

`mc_open_launcher` starts the first launcher `launcher::find` sees: vanilla
(`Minecraft.app`; `MinecraftLauncher.exe` under Program Files, else the
Store package via `explorer.exe shell:AppsFolder\…`; `minecraft-launcher` on
PATH, `/opt`, or its flatpak), then Prism. Nothing found is a sentence the UI
shows. It never downloads Minecraft (see game-data).

## Updates

`tauri-plugin-updater` and `tauri-plugin-process` (desktop-only deps) are
registered in `lib.rs`; their permissions (`updater:default`,
`process:allow-restart`) are plugin permissions in the capability, beside
`commands`.
`tauri.conf.json` has `plugins.updater` with the GitHub `latest.json`
endpoint and an empty `pubkey`: that's fine for building and running (the
key is only used to verify a downloaded update), but it must be filled before
the first release; with the signing key set and no pubkey, the release
workflow fails (see the release skill). The UI calls the
plugins' JS API behind `Backend.checkForUpdate`/`installUpdate`.

## Videos (`video/`)

`video_convert(folder)` (trusted only: ffmpeg reads the project's file) converts a project video for screens:
ffmpeg (`Settings.ffmpeg_path`, else the first on `PATH`; `find_ffmpeg`) decodes the source to raw RGBA at the
maps' size and rate (`filter`: contain, cover or stretch), `palette.rs` dithers each frame into the map colours
of the project's version (`mapColors` in the cached game data; none is `Unavailable`), and `nfv.rs` writes the
frames (key frames every two seconds, else one rectangle round each map's changes, zlib) to a body file, then
header + index + body into `video.nfv` through a temp file renamed into place. The sound goes first (whether
there is one is in the header): `audio.ogg` and five-second pieces in `audio/`, each made aside and renamed in; a
source without sound converts silent. `video://convert-progress` reports it. `video_info(folder)` reads a header
back and hashes the source against it. Tests drive the whole pipeline with `fake_server`'s ffmpeg mode
(`-nostdin`: frames at the `scale=` asked, a silent source by name); the contract suite has `video_info` and the
refusals, and excludes the progress event (no ffmpeg in either contract run).

## Tests

`cargo test --manifest-path apps/editor/src-tauri/Cargo.toml` (no network, temp
dirs; synthetic PNGs and font JSON for glyph advances, fake launcher trees
per OS). They run on Linux, macOS and Windows alike:

- **Processes** are `examples/fake_server.rs` (`testing::fake_server()`, which
  `cargo test` builds before any test runs; `cargo test --lib` alone doesn't):
  Java running Paper (prints Paper's lines, says hello on the dev bridge,
  echoes the console, stops on `stop`), Java running the test runner (prints
  the "jar", records its arguments), lua-language-server (echoes LSP frames,
  exits on `{"method":"exit"}`), `ignore-stop`, `exit <code>`, `sleep`.
  Symlink tests are the only unix-only ones.
- **No sleeps**: wait on a condition with `testing::until` (`PATIENCE`, 30 s,
  generous for slow CI runners and free when all is well). A real watcher
  is live only once `watcher::until_watching` saw its probe file. A pending
  future is polled once (`futures_util::poll!`) rather than slept on. A
  "never happens" check is bounded by something later that does happen (a
  third LuaLS's own exit after a stopped one), never by a timer.
- **No warnings on any OS**: an import or helper used only under `cfg(unix)`
  lives inside that cfg (`pnpm lint` runs clippy on Linux only).
- The contract host above is an ignored test; without its environment it passes.

`-- --ignored` additionally downloads real Paper, lists the machine's Javas,
and boots a real Paper server (needs network and Java 25). `pnpm lint` runs
`cargo fmt --check` and `clippy --all-targets -D warnings`.

**Windows binaries need a manifest**: Tauri imports `TaskDialogIndirect`
(Common Controls v6). tauri-build embeds its manifest in the app's binary
only, so `build.rs` has the linker embed `windows-app-manifest.xml` into
every binary, test executables and examples included; without it they exit
with `STATUS_ENTRYPOINT_NOT_FOUND` (0xC0000139) before printing anything.
