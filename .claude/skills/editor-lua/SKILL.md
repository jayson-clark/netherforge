---
name: editor-lua
description: The editor's Lua tooling - the Debug Adapter Protocol client (DapClient, the debug store, breakpoints that follow their file, saves held while paused, the Debug panel, the memory backend's fake adapter) and lua-language-server (Monaco through @codingame/monaco-vscode-api, setupMonaco, models keyed by real URIs, the language client, the per-project API stubs and project names it is given). Read before touching apps/editor/src/core/debug, core/luals, core/store/debug.ts or editors/script.
---

# The editor's Lua: the debugger and lua-language-server

Part of the editor's guide: the editor-ui skill has the layers, the registry, the store's invariants and testing, and a section named here that isn't in this skill is in one of the others it lists.

## The debugger

The dev server's plugin is a Debug Adapter Protocol adapter (the hot-reload
and plugin-sandbox skills); the editor is its client. Every DAP message rides
one `dap` notification on the bridge, both ways: `backend.dapSend(message)`
(`BridgeClient.notify` in Tauri) and `onBridgeEvent('dap')`. Rust passes them
through like any frame. Message types are `@vscode/debugprotocol`'s
`DebugProtocol`; nothing is hand-declared.

- **`core/debug/client.ts`** (`DapClient`): `seq`/`request_seq` correlation,
  `request(command, args)` typed per command (`DapRequests`), events by name,
  a 10 s timeout (a plugin without the debugger never answers, and the store
  gives up quietly), `reset()` failing what waits when the bridge drops.
- **`core/store/debug.ts`** (`createDebug`, `app.debug`, `useDebug`): breakpoints
  as `{ path: lines }` (project paths, the ones script errors use), whether
  they're active, break on script errors (the adapter's `errors` exception
  filter), remembered per project root in localStorage (`netherforge.debug:<root>`,
  through `LayoutStorage`). It attaches whenever the bridge comes up (its own
  `onServerState`): `initialize`, `attach`, `setBreakpoints` per file,
  `setExceptionBreakpoints`, `configurationDone`; the bridge going down ends
  the session (`detached`), and the next bridge starts a new one. On
  `stopped` it fetches the stack, selects the top frame and fetches its scopes
  and the first scope's variables; anything else is fetched when it's
  expanded (`expand(reference)`), cached by reference until the server runs
  (`continued`, or the answer to `continue`/`next`/`stepIn`/`stepOut`). Stop
  (`stopDebugging`) is DAP's `disconnect` plus breakpoints deactivated;
  activating them attaches again.
- **Saves wait while paused.** The plugin refuses every request that needs
  the main thread while a breakpoint holds it, a reload included, so
  `RunHooks.holdReload` (wired in `state/providers.tsx`) queues the paths and
  the store reloads them once the server runs.
- **Breakpoints follow their file.** The refactors slice's `followPaths`
  reports moves (a rename, a folder's, their undo and redo) and deletes; the
  store moves or drops the lines and resends both files. Edits move them with
  their line: `editors/script/breakpoints.ts` (`useBreakpoints`, called by
  `CodeEditor` after its model effect) draws them as glyph-margin decorations
  that Monaco tracks (`NeverGrowsWhenTypingAtEdges`), and after every content
  change reports their lines back (`setBreakpointLines`, a no-op when nothing
  moved). A click in the glyph margin or F9 (`netherforge.toggleBreakpoint`,
  also the Run menu's Toggle Breakpoint through `focusedCode`) toggles one;
  the selected frame's line in this file is marked whole.
- **The Debug panel** (`workbench/panels/Debug.tsx`, a `DOCK_PANELS` entry with a
  badge while paused): the toolbar, the stop line (`describeStop`), the call
  stack, breakpoints, and the variables as a `Tree` whose unfetched
  expandable rows hold one "Loading…" row (so they have a twisty) until
  `expand` brings their children. `workbench/debugStops.ts`
  (`useFollowDebugStops`, mounted by `Workbench`) shows the panel when the
  server stops and opens the stop's file at its line. The Run menu has
  Continue (F5), Step Over (F10), Step Into (F11), Step Out (Shift+F11), Pause,
  Stop Debugging, Toggle Breakpoint (F9), Breakpoints Active and Break on
  Script Errors.
- **The memory backend's adapter** (`testing/backend/memoryDebug.ts`,
  `FakeDebugAdapter`) answers every request above, records them (`dapLog`),
  keeps what it was sent (`testBreakpoints()`), stops when a test says so
  (`testDebugStop({ file, line, reason, description })`, with a fixed stack:
  locals with a nested table and a `Player` handle, an upvalue, a scope's
  `this`), and refuses main-thread bridge requests while stopped, as the plugin does.
- **Tests**: `core/debug/client.test.ts`, `core/store/debug.test.ts` (attach,
  stops and variables, steps, held reloads, renames with undo and folders,
  deletes, moved lines, deactivating, stop, a plugin that never answers,
  persistence), `workbench/panels/Debug.test.tsx`, and `e2e/debug.spec.ts`
  (F9, a stop, a table opened, a save held until Continue, a rename taking
  its breakpoint along).

## Lua: lua-language-server

Every Lua language feature (diagnostics, completion, hover, signature help,
go to definition, references, rename) comes from lua-language-server (LuaLS),
which the backend runs per open project (see tauri-backend); the editor has
no Lua analysis of its own.

- **Monaco is `@codingame/monaco-vscode-api`** (`@codingame/monaco-vscode-editor-api`
  in place of `monaco-editor`), started once in "classic" mode (Monarch
  highlighting, the standalone editor, no workbench) by
  `MonacoVscodeApiWrapper` in `editors/script/monaco.ts`, because
  `monaco-languageclient` needs VS Code's services. `setupMonaco(app)`
  resolves when editors can be made; **nothing may touch Monaco's API before
  it** (even `getModels()` starts the services with defaults, and the real
  start then fails with "Services are already initialized"). Languages are
  registered after the start; Vite pre-bundles the whole stack in one run
  (`optimizeDeps.include` in `vite.config.ts`), or dev ends up with two
  copies of VS Code's service modules.
- **Models are keyed by the file's real URI** (`modelUri(path)`, under the
  project root), which is how LuaLS names files; `pathOf(uri)` goes back. A
  file no tab has open is read through `ProjectFiles` (a read-only file
  system over the store and the backend) when a definition is in it, and
  `openEditorFunc` opens another file's definition in its tab. Files outside
  the project (the API's stubs in `.netherforge/docs/`) are only peeked.
- **The client** (`luaClient.ts`) is one `MonacoLanguageClient` per open
  project, started with the first script editor and stopped when the project
  closes or changes, over `core/luals/transport.ts` (`lualsStart`/`lualsSend`,
  `luals://message`, ignoring other generations). It answers LuaLS's
  `workspace/configuration` with `settings.ts` (the plugin path and its
  arguments: each scripted folder kind and its main file, from format's `KINDS`), and trusts the plugin
  (`initializationOptions.trustByClient`). The project's `.luarc.json` wins
  where both say something. A backend with no LuaLS (the memory backend; a
  dev build that never ran `tools/luals.mjs`) rejects `lualsStart`: the
  client is skipped and scripts are highlighted only.
- **What LuaLS knows about the project**: the API from a per-project copy
  under `.netherforge/luals/` (`nf.lua`, `surfaces/`), written by `core/luals/follow.ts` with
  `@netherforge/project-support` (`tailoredApiStubs` in its `api.ts`, from `bundled.ts`'s lazy
  bundle of the generated stubs and `gates.json`; `netherforge setup` writes the same files) with every function the project can't use marked `---@deprecated` and
  the reason: newer than the project's `minecraft` (`since`), or needing something its
  `netherforge.json` hasn't declared (`requires`, via `granted`). The copy is rewritten
  when the manifest changes, and the project's `.luarc.json` turns on `not-yieldable`
  and `hint.awaitPropagate`. `api.ts` and `luals.ts` stay free of `import.meta.glob`
  (that's `bundled.ts`), so plain Node can import them. And the project's names from `.netherforge/luals/project.lua`,
  written by `followLualsStubs` 300 ms after the outline or a document
  changes (unsaved edits included, only when the text changes): each
  `NameKind` is an alias (`NodeName`, `ButtonKey`, `CentityId`...) that
  `nf.lua` declares as `string` and this file declares again with the
  project's names, which LuaLS merges. Network names (`NetworkChannelName`,
  `NetworkMapName`, `NetworkServerName`) come from scripts and the workspace
  (`projectNames`' `NetworkNames`): the channels and maps the project's and its
  packages' scripts get by a literal (format's `networkUses`; a package's as
  `ns:name`), and the workspace network's servers, which `followLualsStubs` reads
  from the `workspaceFile` store it's given (rewriting when the network changes).
  `require` beside a resource's script
  resolves through the backend's LuaLS plugin. The packages the project
  depends on directly (`packageSources`: their outlines, and the files the
  packages slice read, modules' Lua included) add what they export: their
  names as more of the aliases (`library:gem`, `library:gems/gem`), and a stub
  per file of each exported module under `.netherforge/luals/packages/<ns>/`
  (`packageModuleStubs`), which the plugin resolves `require("library:greetings")`
  to (`packages=` in `pluginArgs`; `luals.node.test.ts` goes to a definition
  through one). Stubs of a package no longer depended on are deleted.
- **Tests**: the pure parts in vitest (`stubs.test.ts`, `transport.test.ts`);
  the real server in `luals.node.test.ts` (Node, the pinned LuaLS). Playwright
  has no LuaLS: it checks a script still opens, highlights and edits.
