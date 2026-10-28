---
name: editor-ui
description: How the editor's React side (apps/editor/src) is put together - its layers and folders, the Backend contract and the memory backend, the registry of kinds, tabs, commands and panels, styling, the workspace store and its invariants (format is the only writer, undo per document, gestures, view state, validation in a worker), undo for refactors, external changes, references, adding an inspector field or a new resource editor end to end, and vitest vs Playwright. Its area skills (editor-workbench, editor-lua, editor-packages, editor-viewports, editor-resource-editors) hold the rest. Read before changing anything in apps/editor/src or apps/editor/e2e.
---

# The editor UI

React 19 + zustand + react-three-fiber + Monaco, in a Tauri webview. It
talks to the world only through `Backend` (`src/core/backend/types.ts`), and to
project files only through `format` (`src/core/format.ts`).

## The other editor skills

This skill holds what every change in `apps/editor/src` needs. The rest is by area:

| Skill                   | Holds                                                                                                                          |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| editor-workbench        | "The workbench": docks, outlines, the explorer, settings, widgets, menus and shortcuts, the palette, zoom                      |
| editor-lua              | "The debugger" and "Lua: lua-language-server"                                                                                  |
| editor-packages         | "Packages (dependencies)": package paths, read-only packages, templates, dependencies' resources                               |
| editor-viewports        | "Viewports", "Timelines", the transforms from format, keyed lists and galleries, block models, map previews, glyphs            |
| editor-resource-editors | "The resource editors" (menus, dialogs, packs, items, recipes, loot, advancements, particles, cutscenes, terrains) and screens |

`node tools/skill-paths.mjs` (part of `pnpm lint`) fails when one of these skills names
a file under `src/` that isn't there.

## Where things are

`src/` is layered; a layer imports only from itself and those above it in
this list, and every import across folders uses the `@/` alias
(`@/core/paths`; `no-restricted-imports` refuses `../`). `eslint-plugin-boundaries` enforces the layers (`LAYERS` in
`eslint.config.js`, resolving `@/` through the TypeScript resolver): a test
may import any layer and `testing/`, `testing/` builds on `core/`, `state/` and
`minecraft/`, and `app/` may load the memory backend (`testing/backend/`) for
Playwright's build. Something two layers need lives in the higher one (the
image facts and the default font in `core/`, `isEditableTarget` in `ui/`,
the window's shape in `minecraft/window`).

- **`core/`** No React. `backend/` (`generated/bindings.ts`, every command, event and payload type, generated from Rust by tauri-specta (see tauri-backend); `types.ts`, the `Backend` interface, those types re-exported, `BackendError` with its `code`; `tauri.ts` calling the generated commands; `projectBytes.ts`; `contract.json`/`contract.test.ts`, which hold the memory backend in `testing/backend/` to the Rust side), `format.ts` (typed wrapper over `@netherforge/format`'s JSON-string exports; nothing else imports `@netherforge/format`), `paths.ts`, `draft.ts` (`setKey`: an optional key set, or deleted for `undefined` or `''`; every editor's), `schemas.ts`, `agentDocs.ts`, `image.ts` (a picture's size and alpha, loaded once per URL), `defaultFont.ts` (`fonts/default.json` from the import), `debug/` (`client.ts`, the Debug Adapter Protocol client: see "The debugger"), `luals/` (what lua-language-server is given: `stubs.ts` the project's names, `follow.ts` keeping `.netherforge/luals/` written, `settings.ts` the client's `Lua` settings, `transport.ts` the JSON-RPC connection over the backend), `validation/` (the validation worker: `requests.ts` what the main thread sends, only what changed (`ChangeTracker`); `validator.ts` the worker's side over format's `ProjectValidator`; `validation.worker.ts` exposing it with Comlink; `client.ts` `validationWorker()`), `mcp/` (the MCP server agents call: the official SDK's `McpServer` with `registerTool` + zod schemas in `tools.ts`, `BackendTransport` over `mcp://message`/`mcpSend` in `transport.ts`, `.mcp.json` with the token in `config.ts`; `ToolError` → `isError`; tests drive it with `MemoryBackend.testMcpCall`, which plays the Rust pipe), and `store/`: `workspace.ts` (`createWorkspace`, one zustand store composed of slices, each a module owning its state and actions and reaching the others only through `get()`: `project.ts` the project, what's known about it, read-only paths, notices, hot reload; `files.ts` create, copy, rename, delete and following references; `documents.ts` documents and undo; `refactors.ts` project-level undo (a rename, move, delete or copy as one transaction); `tabs.ts` tabs and focus requests; `views.ts` per-document view state and selection; `validation.ts` validation through the worker; `packages.ts` the dependencies, `netherforge.lock` and copying from a package; `external.ts` changes on disk; `slice.ts` what they share; `followPaths` tells what's kept by path outside the workspace about renames and deletes), `history.ts`, `run.ts`, `network.ts` (the dev network: see "The Network panel"), `debug.ts` (breakpoints and the debugger's session: see "The debugger"), `updates.ts`, `packs.ts`, `layout.ts` (the workbench's docks: sizes, which show, and where each panel is, the tree `docks.ts` keeps and changes purely; explorer view, tree expansion and open tabs, remembered per project in localStorage by `followProject`).
- **`ui/`** The design system, each piece with its own `.module.css`: `Button`/`IconButton`, `layout` (`Spacer`, `Divider`, `Bar`, `PanelHeader`, `Stack`), `text` (`Hint`, `Empty`, `Muted`, `Tone`, `Mono`), `Badge` (`Count`, `ErrorCount`, `DirtyDot`), `Callout`/`Banner`, `fields` (`Row`, `Section`, number/vec3/text/select fields, `JsonObjectField` (any JSON object: `properties`, a stack's `data`), `Check`, `FieldGroup`, `FormLabel`/`FormError`/`FormActions`), `dialogs` (`Modal`, `ask.prompt`/`ask.confirm`), `ContextMenu` (`openMenu(event, items)`), `editable.ts` (`isEditableTarget`: typing in a field or Monaco), `Keys` (a shortcut as the menus write it, `Mod+Shift+S`, drawn one key at a time; `caps` for keycaps), `Tree`, `Pane`, `Splitter`, `Icon` (Lucide's icons under the app's names: every icon goes through its `ICONS` table, so a new one is an import and an entry there; never inline an `<svg>` icon), `cx`. Menus, dialogs, the tree and tabs are react-aria-components, splitters react-resizable-panels (see "Widgets"). Nothing here knows the project.
- **`state/`** The stores in React: `providers.tsx` (`createApp(backend)` wires the stores; `AppProvider`, `useApp`, `useWorkspace`/`useRun`/`useUpdates`/`useResourcePacks`/`useLayout`, `useBackend`), `useResourcePacks.ts` (selectors over the resource packs store, `useProjectFileUrl`), `useExpanded.ts` (`usePane`, `useTreeExpansion`).
- **`minecraft/`** Minecraft itself, shared by every editor: `client/` (client assets: `model.ts`, `geometry.ts`, `render.ts`, `boxes.ts`, `blockMesh.ts`, `bake.ts`, `atlas.ts`, `iconRender.ts`, `MeshGroups`, `models.tsx` (`BlockModel`, `ItemModel`, `Placeholder` in a scene), `useClientAssets`, `usePickerIds`), `item/` (`ItemForm`, the shared `ItemDef` form, with its equipment look and slot pickers (`setEquipment`); `ItemIcon`; `ItemTooltip` and `tooltip.ts`, the game's tooltip, drawn because WebKit never shows `title`; `icon.ts`; `item.ts` pure edits; `projectItems.ts`, files as they are now via `currentText`/`parseCached`), `window/` (`window.ts` vanilla window layout and a menu's `shapeOf`, `WindowFrame`), `text/` (`minimessage.ts`, `MiniText`/`TextPreview`, `glyphs.ts`: `useGlyphMap`, `glyphAdvancesOf`), `nbt/` (NBTify read and written as Java Edition's files are, and value accessors; it's patched, `patches/nbtify@*.patch`, to check an array's length before allocating it), `structure/`, `world/` (level.dat, Anvil regions, chunk sections, LZ4, the terrain worker).
- **`editors/`** One folder per resource editor (`centity/`, `menu/`, `dialog/`, `resource_pack/`, `particles/`, `cutscene/`, `terrain/`, `item/`, `recipe/`, `loot/`, `structure/`, `world/`, `script/` (Monaco: `CodeEditor`, `monaco.ts`, `luaClient.ts`; see "Lua"), `project/`), each with its pure `ops.ts`, its editor and its outline (and its thumbnail and its `view.ts`, the registration of its per-document view, when it has them); `contributions.ts` (what can be contributed: `KindContribution`, `TabView`, `KindCommand`, `DockPanel`, `ViewStateSpec`, `CommandContext`); `registry.tsx` (`KIND_CONTRIBUTIONS`, one entry per kind, and the file tabs; see "Contributions"); `views.ts` (a document's view typed by its kind: `useViewState`, `useSelection`, `usePrimary`, `useView`/`viewOf`); `shared/` (`EditorScreen`/`EditorBar`/`Stage`/`Page`, `InspectorPanel`, `viewport/` (see "Viewports": `Viewport3D`, `ViewportHost`, `ViewportCanvas`, `scenes.ts`, `camera.ts`, `Gizmo`, and `parts` for the toolbar, notes and fallback), `timeline/` (see "Timelines": `Timeline`, `time.ts`, `keys.ts`, `usePlayback`), `KeyedList` (entries kept by name, as an outline pane, under a `NameRule`: parts' names by default, `ID_NAMES` for a loot table's pools), `Gallery` (a gallery split into sections of cards), `entries.module.css`, `ScriptField`, `ServerWorlds`, `modelDoc`, `RawDocView`, `useFocusRequests`, `focus.ts`).
- **`workbench/`** The shell: `contributions.tsx` (`TAB_VIEWS`, every tab type's view including the settings', and `DOCK_PANELS`), `Workbench` (docks and splitters), `toolbar/` (title bar, run controls), `editorArea/` (tabs, banners), `outline/` (the left dock: `subject.ts`, `FilesPane`, `fileTree.ts`), `docks/` (inspector and bottom docks), `explorer/` (the project explorer: `folders.ts`, tiles, thumbnails), `panels/` (Problems, Console, Instances, Profiler, Debug), `debugStops.ts`, `quickOpen/`, `settings/` (the settings tab: `SettingsScreen`, its pages in the outline), `welcome/`, `resourceActions.ts`, `shortcuts.ts`.
- **`app/`** `main.tsx`, `App.tsx`, `backend.ts` (`createBackend`: the Rust backend, or the seeded memory backend in Playwright's build), `styles/` (`tokens.css`, `base.css`: the only global CSS).
- **`testing/`** `backend/`: the memory backend (`memory.ts`; `memoryPlugin.ts`, the fake plugin's bridge answers, bots, players and profiler; `memoryDebug.ts`, its debug adapter; `jvmArgs.ts` and `nfv.ts` as the Rust side's; `seed.ts` bundling `examples/showcase`, `seeded.ts`), for vitest and Playwright only. Hand-written fixtures: `clientFixture.ts` (`?assets=fixture`), example files, NBT and region builders; `workspace.ts`, the tests' shared set-up (`openExampleWorkspace`, `openExampleApp`, `settle`; see "Testing").

Folder names: an editor's folder is its kind's id (`resource_pack/`, `game_block/`,
`dimension_type/`: snake_case, as format's `KindId`), so a kind and its folder read the
same; every other folder is camelCase (`editorArea/`, `quickOpen/`). Keep both. A big
editor or inspector is a folder of modules, one per section, rather than one file
(`editors/terrain/inspector/`, `editors/centity/inspector/`); a module a component and a
store or command both need lives outside the component (`workbench/toolbar/startServer.ts`,
`workbench/welcome/welcomeState.ts`), since the command table importing a component
makes an import cycle through the menus.

## Contributions

Everything a kind, a tab type, a dock panel or a kind's command adds to the
workbench is registered once, and the shell only reads the registries:

- **Kinds**: `KIND_CONTRIBUTIONS` in `editors/registry.tsx`, one entry per
  kind in format's generated `KINDS` (`satisfies` a mapped type over
  `KindId`, so a kind without one is a type error, and a kind whose files
  aren't Lua must have a `view`). An entry is the kind's title, one, icon,
  `tone`, id-prompt `message`; its tab's `view` (lazy: the JSON kinds'
  editors and the binary kinds' screens alike, given the tab's path); its
  `outline`; its `thumbnail` (and a `studio` the workbench mounts while it
  has work, through `useSyncExternalStore`); `viewState` (see below); and
  `commands`. Its key
  order is the explorer's. `KIND_ORDER` and `kindContribution(kind)` read it.
  A module has no view: it opens its entry (`opensAs`) in a script tab.
- **Tabs**: `TAB_VIEWS` (`workbench/contributions.tsx`), a `Record<TabType,
TabView>`: the kinds' tabs and the file tabs (`script`, `json`, `project`)
  from the registry, and `settings`. A view says its icon, its strip label,
  whether a document backs it (banners, save, undo, read-only), whether it's
  a resource's own (the tab shows the thumbnail) and, for a tab that isn't
  inside a resource, its outline (`{ subtitle, view }`). `EditorArea`,
  `TabStrip` and `OutlineDock` have no switch over tab types.
- **Commands**: a kind's `KindCommand`s get the id `<kind>.<name>` and run
  through the same table as `COMMANDS` (`workbench/menus/commands.ts`). Every
  command has a `when(c)`: when it can run (the menus grey it out otherwise).
  A `resource` command acts on the active tab's resource of its kind (its own
  tab or a file in it), so it's enabled only then; the palette lists it per
  resource (`<kind>.<name>:<id>`), its `menu` places it (`'run'`), and
  `toolbar` gives it a button beside the dev server's controls
  (`TOOLBAR_COMMANDS`; Spawn at me). A `kind` command needs no resource
  (Stop Particle Effects).
- **Dock panels**: `DOCK_PANELS` (the outline and the inspector are panels
  too), each with a persistent `id` (the layout keeps where each panel is by
  it), its view, `badge` and View-menu item (`view.panel:<id>`).
  `showPanel(layout, id)` (the store's `reveal`) shows one wherever it's
  docked. Where each starts is `DEFAULT_DOCKS` in `core/store/docks.ts`,
  which names every panel: a new panel goes in both (`readDocks` drops
  stored ids that are gone and puts panels a stored layout lacks where they
  start).
- **Per-document view state**: a kind registers a `ViewStateSpec<V, S>`
  (`editors/<kind>/view.ts`): its view's `initial` state `V` (a centity's
  clip, playhead, gizmo; a particle preview's tick and loop; a recipe's grid
  offset; every 3D kind's `camera`, see "Viewports"; a map's radius), what its selection holds `S` (node names, slot indexes, dialog
  entries, resource pack picks, particle emitters and keys), each item's `key`, and
  `opensInside` (what opening a non-text file inside one of its resources
  selects: a resource pack's pictures and sounds). `ViewStateOf<K>`/`SelectionOf<K>`
  type a kind's from its entry, and `editors/views.ts` reads and changes a
  document's view through them (`useViewState('centity', path)`,
  `usePrimary`, `useView(kind, path).select(...)`). The workspace keeps it
  (`core/store/views.ts`): see "View state" below.
- **Inspectors** stay part of their editor's view, portalled into the dock
  (`InspectorPanel`), because they share the editor's state; the dock itself
  is generic.
- **Read-only documents** (a dependency package's): `setReadOnly(path,
reason)` marks a file or folder; `readOnlyReason(readOnly, path)` is the
  innermost reason. The store refuses (with a notice) to edit, save, create,
  copy into, rename or delete one, and rename-references skips it. The editor
  area shows a "Read-only: …" banner and wraps the view in a disabled
  `<fieldset>` (so every control in any view is disabled), the inspector dock
  does the same, and Monaco is set `readOnly`.

`editors/registry.node.test.ts` reads the app with the TypeScript checker
and fails on a list of kinds anywhere but the registry (a switch, an `a ===
'x' || a === 'y'`, an array, object or union of kind ids) and on any kind id
in `core/`, `workbench/` or `app/`; an editor naming its own kind is fine.
Its `EXCEPTIONS` say why a file may (LuaLS's project names, which read a
centity's nodes and a dialog's buttons).

## Styling

Every component styles itself with a CSS module beside it
(`Thing.module.css`, camelCase classes, `cx(styles.a, on && styles.b)`).
Colours, radii and fonts come from `app/styles/tokens.css`; `base.css` holds
element defaults only. Don't add global classes. Hashed class names mean
nothing outside the component: find elements by role and accessible name,
or by a `data-*` attribute (`data-twisty`, `data-skin`, `data-path`,
`data-inspector`, `data-editor`), never by class. A selector must never
return a fresh `[]` or `{}` (`?? []` in a selector loops forever before the
first validation): use a module-level empty constant.

## The workspace store: invariants

- **`format` is the only writer.** A model document saves as
  `JSON.stringify(model)` → `canonicalize` → `writeText`. Raw JSON (a file
  that doesn't parse, or "Edit as JSON") saves canonically if it parses and
  as typed if not, so nothing the user wrote is lost. Never format JSON by
  hand, never write a default where you can leave a key out (`ops.setChannel`
  shows how: the canonical writer keeps explicit defaults).
- **A document** (`Doc`) is either a model (`history` is authoritative,
  `raw: false`) or text (`raw: true`, `text` is authoritative: Lua, raw
  JSON). `savedText` is the last text we read from or wrote to disk;
  `savedModel` is its parse, for the dirty check (structural, so editing a
  value and back is clean again).
- **Undo is per document.** Model documents keep snapshot histories
  (`history.ts`); text documents use Monaco's own undo (one Monaco model per
  path, kept across tab switches). This stays deliberately: moving Lua/raw
  JSON onto store snapshots would lose Monaco's cursor-aware, per-keystroke
  grouping and fight its model, for no gain (a text doc has one editor). Ctrl/Cmd+Z in a text field or Monaco is
  theirs; elsewhere it's the active document's, where a refactor is one
  step too (see "Undo for refactors").
- **Gestures**: `beginGesture(path)` … edits … `endGesture(path)` make one
  undo entry. Use them for anything continuous: gizmo drags, label scrubs,
  keyframe drags. Discrete edits (Enter in a field, a select) are one
  `edit()` each. Fields commit on Enter/blur, never per keystroke.
- **Edits** go through `edit<T>(path, draft => …)` using the pure functions
  in `editors/centity/ops.ts`. The recipe gets an Immer draft: the new model shares
  every subtree it didn't touch (so snapshots are cheap and `sameJson`, the
  dirty check, mostly compares references), and a recipe that changes nothing
  commits nothing. Models aren't frozen, but treat them as immutable outside
  `edit`.
- **View state** is per document, beside it and not in it
  (`views[path]`: `selection`, oldest first, the last being the primary an
  inspector shows; `state`, only what was changed from the kind's
  `initial`). It's never saved or undone. A rename moves it with the
  documents and tabs in one `set` (`files.ts`), and it goes once neither a
  document nor a tab has its path (`liveViews`, the one subscription that
  drops views: closing a tab, deleting, a file gone from disk, closing the
  project). So it survives tab switches: a particle preview's playhead, a
  centity's clip. Selection is typed and multiple: `select(...items)`,
  `toggle(item)` (Shift or Cmd/Ctrl-click in the centity viewport, whose
  Delete removes every selected node), `mapSelection` after an edit renames
  or removes what was selected. The centity's Nodes tree selects several
  (Tree's `multiple`, bound to the view: Cmd/Ctrl-click, Shift-click,
  Shift+arrows), and Delete on a selected row deletes them all; the other
  outlines select one.
- **Validation** runs in a Web Worker (`core/validation/`), never on the
  main thread, debounced 250 ms after any change. The project as format
  reads it is every file: current in-memory text for open JSON docs (a
  model's text is worked out once per model, `modelText`), disk text for the
  rest, `null` for non-JSON, plus the cached `GameDataBundle` and the
  packages (`packages`, a `PackageInputs` from the packages slice: folders
  by location and git fetches by key). `ChangeTracker`
  sends the worker only what changed since its last request (files,
  deletions, game data, the packages when they were read again;
  `reset` for another project), so an edit sends one file. The worker holds format's `ProjectValidator`, whose
  `ProjectCache` keeps what each file validated to by its content (see
  project-format): it validates the changed file alone, then runs the checks
  across files (references, `crossCheck`) over everything, and answers the
  outline and the files it validated afresh. RPC is Comlink (`wrap` on the
  main side, `expose(…, self)` in the worker); the worker is Vite's
  `new Worker(new URL('./validation.worker.ts', import.meta.url), { type:
'module' })`, bundled with its own copy of format's JS build (~0.9 MB
  minified; importing it takes 100–200 ms, in the worker). `validateNow()`
  resolves once the problems are in the store; an answer to a request made
  before `cancelValidation` (closing the project) is dropped, and a failed
  request makes the next one start the worker over. `createWorkspace(backend,
{ validation })` takes another client factory (a test's, to watch requests
  and what was validated). `checkProject(request)` validates another project once in the same
  worker (`ValidationApi.check`, with a validator of its own: the open one's is untouched), for
  the workspace's other members. Problems carry `file`, `line` (parse errors) or
  `path` (`$.nodes.top.display.block`); `openFile(file, { line, jsonPath })`
  turns either into a focus request.
- **Save** then hot reload: if the bridge is connected, `bridgeRequest('reload', { paths: [path] })`, result logged in the console.
- **The dev bridge** is JSON-RPC (`core/bridge/client.ts`, vscode-jsonrpc
  over the backend's relay; see the hot-reload skill): `backend.bridgeRequest(method,
params)` typed by format's `BridgeRequests`, and `backend.onBridgeEvent(event,
fn)` typed by `BridgeEvents` for `console`, `problems` and `status`. Don't
  switch over message types; subscribe to the event you need. The memory
  backend answers requests itself and records each as `{ method, params }` in
  `bridgeLog`; tests push notifications with `testBridgeEvent(event, params)`.
- **The console** is a ring buffer (`core/store/consoleBuffer.ts`) the run
  store mutates in place: select `consoleVersion` and read
  `console.lines()` (cached per change) or `console.since(id)`; `append`
  takes a whole batch in one store update.

## Undo for refactors (`core/store/refactors.ts`)

A rename (or a move: the same thing), a delete, a duplicate (`copyPath`) or
a copy into the project (`copyFromPackage`) changes several files, some never
open, and what's kept by path. Each runs as one **transaction**
(`transact(label, work)`; a call inside another joins it, as a rename's
`renameReferences` does, and the resource pack outline wraps a key's rename and its
references in one), recorded as it goes:

- **Files**: `touch(path)` before anything changes a file or folder reads what's
  there (text for the text extensions, bytes for the rest); when the
  transaction ends, everything under its roots is read again. Undo writes
  each file's "before", redo its "after", so a file no tab has open comes
  back exactly; a root empty on the other side is deleted whole (no empty
  folder left).
- **Moves** (`movePaths`): documents, tabs, views and disk texts, replayed
  backwards on undo.
- **Document steps**: a `commitModel` while a transaction runs (a reference
  rewritten in an open document) is tagged with it in the document's history
  (`Step.tx`): it's never undone alone, only with its transaction.
- **A shelf** (`shelvePaths`): what was open at paths that go (a delete; a
  copy, on undo): documents with their histories and unsaved edits, tabs
  where they were in the strip (and active if one was), views. Undo or redo
  puts it back.

**How it meets per-document undo.** Every step and every transaction takes a
tick on one clock (`nextSeq`). Ctrl/Cmd+Z in a document (`undo(path)`) takes
back its own last edit or the project's last refactor, **whichever came
later**; a step tagged with a transaction undoes the whole transaction. So a
refactor is one step in every document's undo, in time order: a rename just
made is undone from whatever tab is active, and an edit made after it comes
off first. Redo takes the one undone last (the older of the document's next
step and the project's next refactor). With no model document active (a
script, a map, no tab), Edit → Undo/Redo is the project's
(`undoRefactor`/`redoRefactor`). A new refactor ends the redo stack; 100 are
kept. Two refactors started at once would join one transaction: they're
user actions, awaited one at a time.

**When something changed underneath**, undo and redo check before touching
anything, and refuse with a notice:

| Found                                                                           | Then                                                                    |
| ------------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| a file it changed differs from what it left (version control, an agent, a save) | refused; the transaction is forgotten (its steps become documents' own) |
| a file added inside a root it would take away                                   | the same                                                                |
| a document it stepped has later edits (undo)                                    | refused, kept: undo those there first                                   |
| that document's step is gone (redo after an edit)                               | refused, forgotten                                                      |
| an open document it would write over has unsaved edits                          | refused, kept: save or revert it first                                  |

Otherwise the workspace (histories, moves, saved texts, the shelf, disk texts)
changes in one `set` **before** the disk, so the watcher's echo of each write
already matches `savedText` and is ignored, as our saves are.

## External changes (`core/store/external.ts`)

The watcher reports every write, ours included. Each changed path that's
open is re-read and judged against `savedText` (a `rescan` event, when the
watcher lost events, counts every known file as changed):

| Disk now           | Clean doc                  | Dirty doc                                    |
| ------------------ | -------------------------- | -------------------------------------------- |
| equals `savedText` | ignore (our own save echo) | ignore                                       |
| different          | reload silently            | banner: keep mine / take theirs / diff       |
| gone               | close its tab              | keep it, marked deleted; saving recreates it |

"Keep mine" sets `savedText` to theirs, so the doc stays dirty and the next
save overwrites. A doc open as JSON only because it didn't parse leaves raw
mode when a reload makes it parse (`keepsRaw`). Renames move open docs,
tabs and views (`renamePath`), then follow the renamed thing in every file
that refers to it.

## References: rename, usages, delete

The editor never walks a model for references itself: format's walker does
(the project-format skill). `renameReferences(target, to)` runs format's
`renameRefs` over every project JSON file, for a `RefTarget`: `renamePath`
calls it with a `file` target for a file or folder inside a resource (a
script, textures, sounds) and a `resource` target for a resource's new id (an
item, a dialog, a resource pack, which takes every reference into it along); the resource pack
outline calls it with a `resource_pack_entry` target when a skin's or glyph's key is
renamed. An open model document gets the edit (a step of the rename's
transaction, see "Undo for refactors"), saved if it was clean; a closed file is rewritten from disk, canonical; unsaved raw text
is left alone, for validation to point at. `usagesOf(target)` is format's
`findUsages` over the project as it is now; deleting a resource from the
explorer names the files that refer to it. Resource pack references in previews
(`splitResourcePackRef`, `useSkin`, item icons, glyphs) resolve through format's
`resolveReference` in the namespace of the document on show
(`useReferenceNamespace`: a package's document's is its package's, through
`DocumentNamespace`, which the editor area sets around its view; the
project's otherwise, `useHomeNamespace`).

## Adding an inspector field

1. The key exists in `format` (and `docs/format/centity.md`) first; the
   generated type in `@netherforge/format/types` gives you its shape. An enum
   field comes with its values (`BillboardValues`) for a select's options,
   and a default or limit belongs in format's generated
   `@netherforge/format/constants` (`codegen/Constants.kt`), never a literal
   here. `core/format.ts` re-exports both.
2. Add the control in its section's module in `editors/centity/inspector/` with
   `dataPath` = its JSON path inside the node (`physics.maxSpin`), so a
   problem at `$.nodes.<n>.physics.maxSpin` focuses it.
3. Write through `editNode(target => setKey(target, 'maxSpin', value))`
   (`core/draft.ts`, the one helper every editor uses): `undefined` (or `''`) deletes the key. Pass `onGestureStart/End` if it can scrub.
4. If it changes how something looks, update the viewport too.
5. If its edit is non-trivial (a rename that must follow references), put it
   in `editors/centity/ops.ts` with a unit test.

A centity's `spawning` block is edited in the inspector's **Natural spawning**
section (`editors/centity/inspector/SpawningSection.tsx`), shown with the
Centity section when no node is selected (it belongs to the centity, not a
node). The section's switch adds or removes the block; lists are typed text
(`parseList`: commas, spaces or lines), ranges are two number fields, and
every change is a `spawning.ts` function through `workspace.edit`
(`spawning.test.ts`, `Inspector.test.tsx`), leaving a cleared key out and a
range with neither end away. Problems at `$.spawning...` select no node and
focus the field by its `centity.spawning.<key>` data path.

## Updates

`core/store/updates.ts`: `app/main.tsx` checks quietly at start (errors stay silent:
offline, a dev build, no signed release yet), Settings → Updates checks
loudly. `UpdateBanner`/`useInstallUpdate` always ask first, then
`install(stopServer)` stops the dev server and calls
`backend.installUpdate`, which relaunches. `MemoryBackend` reports none
unless built with `update` (`?update=1.2.3` in the browser).

## Adding a resource editor

Done for centities, menus, dialogs, particle effects, project items, recipes,
blocks (`editors/block/`: a form and a CSS cube drawn from `ResourcePackPreview.blocks`'s faces,
with pure `ops.ts`), biomes (`editors/biome/`: a CSS landscape painted with its colours, a hatch where
the file leaves one to the world or the climate; `ColorField`s, sections that turn a sound or the
particle on, spawns by category, spawn costs, and each step's ordered features with move buttons; ids
suggested from the game data's registries through `Datalist`s, none until it's exported; new entries start
empty rather than with a game id, so the editor names none; `biomeChoices` is what a biome field
suggests elsewhere, the project's ids before the game's, as the terrain inspector does; the project's ids there are
`ProjectOutline.registryNames` by registry folder, `BIOME_FOLDER`/`PLACED_FEATURE_FOLDER`, so a datapack's biomes and
placed features are suggested too), datapacks (`editors/datapack/`: the inspector edits `pack.mcmeta` (a plain-text description, the formats as `94`
or `94.1`, overlays); the screen lists the pack's files grouped by `data/` and each overlay with the formats it's for,
and **New file…** makes one; pure `ops.ts`; a file opens in its own JSON tab; problems show as anywhere), dimension types (`editors/dimension_type/`: a form of build limits, light and sky, and rules, the stage saying the world's block range; pure `ops.ts`, defaults from the generated `DIMENSION_TYPE_DEFAULTS`) and resource packs (whose gallery has a `blocks` section: `ENTRY_KINDS`); the same steps each time. Which kinds there are, where they live
and what format makes of them is format's kind table, `KINDS` in the
generated constants (`KindId`; `EditedKind` is every kind whose resources are
JSON, `TemplateKindId`, `ScriptedKindId` and `BinaryKindId` the others the
table derives), and which resource a path is is format's `classify`
(`core/format.ts`, cached per path). Never list kinds or match folder names
here: `core/paths.ts` turns the table into `resourceOf` (kind, id, role, rest
for any path), `locationOf` (what rename and delete move: the folder, or the
file), `mainFileOf`, `opensAs`, `resourceIdsOf` and `isFileKind`.

1. `format` gains the kind: one `KindSpec` in its registry, with its template
   (see project-format; rebuild with `node tools/gradle.mjs :format:jsLibrary`).
   `KINDS`, `classify`, `newResourceFiles(kind, id)`, `newScript(kind, …)`,
   the outline's `resources[kind]` and `tabTypeFor` follow; nothing to add in
   `core/`. Re-export the model's generated types in `core/format.ts`;
   `packages/project-support/src/schemas.test.ts` lists its schema.
2. References to it, and from it, are format's `@Ref` fields: renames,
   usages and the delete prompt follow them with nothing to add here (see
   "References" above).
3. `editors/registry.tsx`: its entry in `KIND_CONTRIBUTIONS` (keyed by
   `KindId`, so a kind without one is a type error: title, one, icon, tone,
   the id prompt's message, the lazy view and, as it needs them, its outline,
   thumbnail, view state (its `view.ts`) and commands), placed where it goes
   in the explorer. That's the whole registration: the explorer, the tab and
   its icon, the editor area, the outline, the palette, the menus and the
   toolbar all read it. Never list kinds anywhere else (the registry test
   fails).
4. The editor: an `EditorScreen` with an `EditorBar` and its canvas (a 3D
   one is a `Viewport3D`, with `camera` in its view; a keyframed one has a
   `Timeline`), its inspector in `InspectorPanel`; `useModelDoc<T>(path)` (model and `edit`;
   `RawDocView` when the model is null) and `EditAsJson` in its bar
   (`editors/shared/modelDoc.tsx`), `edit()` with pure ops,
   `useFocusRequests`, fields from `ui/fields.tsx` with `data-path`s and
   `aria-label`s, and one `ScriptField` for the resource's script (a
   resource has at most one; nodes, slots and buttons have none). New
   scripts come from format's `newScript(kind, id)`, whose first line,
   `local this = this --[[@as Centity]]`, tells lua-language-server what
   `this` is.
5. Tests: pure ops in vitest (and a canonical round trip through
   `canonicalizeModel`); the store's create/rename for the kind; one
   Playwright flow for what only the UI can show.

## Testing

- **Vitest** (`pnpm --filter @netherforge/editor test`, jsdom):
  everything pure lives in its own module so it's tested without React,
  three or Monaco: `history`, `external`, the store against a
  `MemoryBackend` (open/save/undo/gestures/conflicts/renames; `views.test.ts`
  for view state moving, going and never being undone; `refactors.test.ts`
  for undoing and redoing renames, deletes and copies, the order against
  documents' own steps, and refusals when the disk moved on), the typed views
  (`editors/views.test.ts`), the centity pose's fallback (`pose.test.ts`), `core/format.ts`
  against the real Kotlin/JS build, `fit`/`boxes`/`model` with hand-written
  asset maps, `gizmo` against `poseCentity`, the viewports store and camera
  poses (`viewport/scenes.test.ts`), the timeline's time model, keys and
  keyboard (`timeline/*.test.ts(x)`), `KeyedList` and `Gallery`, the LuaLS stubs and transport,
  field commit semantics with Testing Library. Assert behaviour; no DOM
  snapshots. Don't import Monaco in tested modules.
- **Set-up and time** (the testing skill has the rules): a test starts from
  `src/testing/workspace.ts` (`openExampleWorkspace()`, `openExampleApp()`,
  `exampleBackend()`), never a hand-built backend and `openProject`. Store
  tests whose code runs on timers use `vi.useFakeTimers()` and `settle(ms)`
  (delays are exported to be asserted at their end: `VALIDATE_DELAY_MS`,
  `SAVE_DELAY_MS`); component tests use real timers and `findBy…`/`waitFor`.
  No fixed sleeps anywhere.
- **Component tests per editor**: each resource editor with an inspector
  gets `<Kind>Editor.test.tsx`, rendering the editor in an `AppProvider` (its
  `InspectorPanel` renders in place outside a workbench; add `<DialogHost />`
  for `ask.prompt`/`ask.confirm`) and asserting on the model
  (`modelOf(docs[path])`). Pick by role and name: an input with a `list`
  (suggestions) is a `combobox`, a repeated set of fields is a named `group`.
  The e2e flow for that editor keeps only what needs a browser.
- **Workers in vitest.** jsdom has no `Worker`; `@vitest/web-worker` (a
  `setupFiles` entry in `vite.config.ts`) gives it one that runs the
  worker's module in the test's process, behind real `postMessage`s with
  structured clones. So every store test validates through the real
  validation worker module and Comlink, as the app does;
  `core/store/validation.test.ts` watches the requests and counts what each
  validation validated (one file after an edit, the cross-file problem it
  causes still found). A worker module calls `expose(…, self)`, never the
  default `globalThis`, which is the test's window there. Playwright runs the
  real worker (the invalid-edit flow checks a `validation.worker` is
  running).
- **The registry**: `editors/registry.node.test.ts` (Node, the TypeScript
  checker over `tsconfig.app.json`) keeps kinds listed only in the registry
  (see "Contributions"), and proves itself on a hand-written list.
- **Playwright** (`pnpm test:e2e`, `e2e/*.spec.ts`): the e2e build (`vite
build --mode e2e`, the only build carrying the memory backend; `pnpm dev`
  and the app always run on Rust) with `?fast` (`&assets=fixture` for real
  rendering and glyph advances). `resources.spec.ts` covers the menu, dialog and resource pack
  editors and text Fit; `centity.spec.ts` the shared canvas (one, moved between tabs,
  each tab's camera kept) and the timeline by pointer and keyboard; `window.spec.ts` finds the title
  bar's empty stretch from its children's boxes (fonts differ by OS). A tree is
  `treegrid` with `row`s (not `tree`/
  `treeitem`), the palette's field a `textbox`. Tests play
  the outside world through `outside(page, backend => …)`: `testWrite`,
  `testDelete`, `testBridgeEvent`, `testConnect`, `testFiles`. Keep it to
  ~15 flows; target by role and accessible name, which is why every control
  has one. Canonical-text assertions call `@netherforge/format` in Node.
- **Restricted mode** (workspace trust; the backend decides, see
  tauri-backend): `ProjectInfo.trusted` is the backend's answer on open, and
  `workspace.trustProject(trusted)` asks it to change (then writes
  `.mcp.json`, which is skipped for an untrusted project since it carries the
  token, and re-resolves packages, whose git fetches were refused).
  `workbench/TrustBanner` shows the banner under the toolbar and
  `askToTrust(workspace)` the confirm; `startServer(app)` asks it first. The
  Lua client (`editors/script/luaClient.ts`) runs only for a trusted project
  and follows the flag. `MemoryBackend`'s `trusted` option is the roots
  trusted before (default: every project it's given; the contract suite
  gives none, the e2e build all but `?untrusted`). `askToUntrust(workspace)`
  (same file) is the confirmed way back, `trustProject(false)` (the backend
  stops the dev server): the Run menu's `run.untrust` command and the button of
  Settings › Server's "Project trust" section (which also offers Trust).
  `trust.spec.ts` is the flow.
- **Requirements** (`netherforge.json`'s `requires`): the project editor's
  "What scripts need" section edits the project's own (`setRequired`,
  `addRequired`/`removeRequired` in `editors/project/ops.ts`, hosts and
  plugins checked against the generated `HTTP_HOST_PATTERN` and
  `PLUGIN_NAME_PATTERN`), and "What running it asks of a server" shows
  `outline.requirements`, format's sum across the tree with who declares
  each, as the server logs it.
- **Mob spawning by world** (`netherforge.json`'s `worlds`): the project
  editor's `WorldSpawning` section, a limit and an interval per spawn category
  (`SpawnCategoryValues`) in a group per world, edited with `setWorldSpawn` and
  `removeWorldSpawn` in `editors/project/ops.ts` (whole numbers of 0 or more;
  worlds and categories kept in format's order; an emptied map, world or `worlds`
  leaves the file, as format drops it). A world with nothing set isn't in the
  file, so one just added is only component state until a value is set.
- **Server-owner settings**: `editors/project/SettingsSection` (in the project
  editor) declares them (the "Add setting…" dialog; `settings.ts` holds the
  pure edits over format's `SettingDef` JSON and `readSetting`), and
  `workbench/settings/OwnerSettings` (Settings › Server-owner settings) shows
  `run.serverSettings`, which the run store fetches when the bridge connects
  and keeps through `settings_changed`; a value is sent with `run.setSetting`
  (`set_setting`), which the fake server in `MemoryBackend` validates with the
  same `readSetting`. A new connect-time bridge request shows in
  `bridgeLog`: tests that assert on it filter `settings` out like
  `profiler_subscribe`. `owner-settings.spec.ts` is the flow.
- **Screenshots** (`pnpm test:screenshots`, `e2e/screenshots/`): the
  previews drawn from format's numbers (window, skin, dialog, glyph text,
  tooltip), pixel for pixel, with the browser in the Playwright container
  (the testing skill has how, and the `--update` command). A change to a
  preview's drawing is a picture to rewrite and review; a new kind of
  preview gets a picture there. The pictures also depend on the layout and
  layering around the preview, so a workbench change can fail them with no
  preview change: text drawn at a fractional offset, or in another
  composited layer, changes anti-aliasing (greyscale vs coloured LCD), and the
  skin (nearest neighbour at 1.75×, where samples tie) picks other source
  columns when the layer it paints into starts elsewhere. The inspector paints
  into a layer of its own (it overlaps nothing composited but comes after the
  gallery's composited scroller) whose bounds are the union of what's in it,
  the inspector splitter's 7px grip included. E7 lost both (0px separators
  moved the editor area 1px; no grip moved the layer's origin 4px), which
  broke the skin, dialog and glyph pictures. Before rewriting a picture after
  a change outside the previews, find what moved: compare each preview
  ancestor's `getBoundingClientRect()` (whole pixels) and the layer tree
  (CDP `LayerTree`, enabled after the page loads) before and after.
- The memory backend stores text or bytes (`FileContents`); the seed
  bundles example PNGs (`?inline` → bytes), `testBytes`/`testWriteBytes`
  move binary files in and out of Playwright, and `projectFileUrl` is a
  `data:` URL. `fixtureGlyphAdvances` (with `?assets=fixture`) feeds text
  fitting in tests.
- `pnpm --filter @netherforge/editor lint` is `tsc -b` + eslint with the React
  Compiler rules: no setState in effect bodies (derive state or set it in a
  callback), no ref reads during render, no mutating props.
- **The memory backend and the contract.** `MemoryBackend` behaves like the
  Rust commands where the UI can tell: results, path rules, and failures as
  `BackendError`s with the same `code` (`fail('notFound', …)`). Every case
  in `core/backend/contract.json` runs on it here (`contract.test.ts`) and
  on the real commands in Rust (see the tauri-backend skill's "The contract
  suite"); when they disagree, fix the memory backend. A new backend
  behaviour the UI relies on gets a case there. UI code that reacts to a
  failure checks `isBackendError(e, 'notFound')`, never the message.
