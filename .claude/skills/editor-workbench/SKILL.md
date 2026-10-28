---
name: editor-workbench
description: The editor's shell (apps/editor/src/workbench) - the Profiler and Tests panels, docks and how panels move between them, editors not laying out docks, outlines, trees, the explorer (group folders, thumbnails, the centity thumbnail studio), the settings tab, widgets (react-aria menus, context menus, dialogs, trees, tabs, the menu bar, splitters), menus and shortcuts (the one command table, buildMenus, the palette), zoom and modules. Read before changing anything in apps/editor/src/workbench or a ui/ widget.
---

# The editor workbench

Part of the editor's guide: the editor-ui skill has the layers, the registry, the store's invariants and testing, and a section named here that isn't in this skill is in one of the others it lists.

## The workbench

- **The Profiler panel** (`workbench/panels/Profiler.tsx`, `ProfileTimeline.tsx`)
  reads `core/store/profiler.ts` (`createProfiler`, in `AppStores` as
  `profiler`, `useProfiler`): it subscribes (`profiler_subscribe`) whenever
  the bridge comes up (`createRun`'s `onBridgeUp`), adds each `profiler`
  batch with the pure `addSample` (functions summed by script, kind and
  place; scopes; the last `TIMELINE_TICKS` ticks), and `reset()` starts
  over. The table is a plain `<table>` with `aria-sort` headers
  (`sortRows`); a function's place opens its script at the line. The
  timeline is a canvas of stacked bars, one per tick, the steps in
  `PHASES` order with the dataviz palette's dark categorical steps (checked
  for colour-vision separation against `--bg-panel`), a legend, and a hover
  tooltip per tick. The memory backend fakes the stream while subscribed
  (`profileEveryMs`, a batch timing every `on("<event>"` in the project's
  scripts at its line).

- **The Tests panel** (`workbench/panels/Tests.tsx`) shows the last run of the
  project's script tests: `core/store/tests.ts` (`createTests`, in `AppStores` as
  `tests`, `useTests`) calls `backend.runTests(filter)` (the `tests_run` command:
  the test runner jar with the editor's Java, see tauri-backend; the project must
  be trusted) and keeps the `TestReport` or why it couldn't run. The panel groups
  results by file with a pass or fail icon, the time, and for a failure where,
  why, the script's `log()` lines and the traceback; a click opens the file at the
  line (the failing script's own for an error). Run → Run Tests (`run.tests`)
  opens the panel and runs; the tab's badge counts what didn't pass. The memory
  backend has no Lua: `testTestReport(report)` sets what `runTests` answers (and
  applies the filter as the runner does); without one it throws `unavailable`.

- **The Network panel** (`workbench/panels/Network.tsx`) is the dev network:
  `core/store/network.ts` (`createNetwork`, in `AppStores` as `network`,
  `useNetwork`) loads the open project's layout whenever the project changes
  and keeps it, `networkCheck`'s answers, the members (`network://members` and
  `network://member`) and a `ConsoleBuffer` per member (`network://output`).
  The panel is a header (an on/off `switch`, "n of m running") over a grid of
  cards, one per member: the proxy, this project's server and each other
  server, with a `StatusDot` (`panels/ServerStatus.tsx`, shared with the
  Console), name and project fields that commit on Enter or blur, a folder
  picker, Trust folder, a console button and Remove, then a dashed Add server
  card. A refused change shows as `error` (the field keeps the text). Start and
  Stop are the run store's (the backend runs the whole network). The memory
  backend plays the members (`testNetworkOutput` adds a line).
- **Workspaces in the shell.** `core/store/workspaceFile.ts` (`createWorkspaceFile`, in
  `AppStores` as `workspaceFile`, `useWorkspaceFile`) follows the open project's
  `ProjectInfo.workspace`: re-reads it (`workspaceInfo`), reads the file through format
  (`checkWorkspace` against the members' paths: `file`, `problems`), writes its schema beside it, and
  changes it (`saveNetwork` writes the file canonically with `canonicalizeModel('workspace', …)`);
  `trust` is the project store's `trustWorkspace` (as `trustProject`'s follow-up). The toolbar's
  `WorkspaceSwitcher` names the workspace and the project and opens a menu of its members (each
  saying the servers running it); picking one asks about unsaved edits (`saveAsking`) and opens it
  (`focusMember`). `askToTrust`/`askToUntrust` and the trust banner speak of the workspace when
  there is one. The Network panel for a project in a workspace is `panels/WorkspaceNetwork.tsx`:
  the file's network as cards (proxy, then the arrival server, then by name) with the name
  (renaming moves `arrival` along), the project (a select of the members), the arrival toggle, the
  console button and Remove; **Set up a network** (`suggestNetwork`: a server per member that isn't
  a package, arriving on `hub`/`lobby` or the first) and **Add server**; the switch is this
  computer's (`workspaceSetNetworkEnabled`).
- **The workspace's other projects in Problems and Tests.** `workspaceFile.checkOthers()` (run
  on every refresh, and `RECHECK_DELAY_MS` after any save, since a package edited in place changes
  what its dependents see) checks each member but the open one from disk:
  `core/store/workspaceCheck.ts` `memberCheckRequest` reads its files through the member commands,
  resolves its packages round by round (`packagesNeeded`; a path dependency on another member
  through `memberFrom`, a git one from the package cache; a folder outside the workspace is
  null), takes its own version's game data, and the validation slice's `checkProject` validates
  it once in the same worker (`ValidationApi.check`, a validator of its own). Problems lists them
  under "Other projects in the workspace", by member; a click is `focusMember` then `openFile`.
  Tests' **Run Workspace Tests** is `tests.runWorkspace(others)`: the open project's run as
  `run`'s, then `runTests(undefined, member.path)` for each (`tests_run`'s `member`; it has to be
  trusted too), kept as `others`; the badge counts theirs.
- **New projects in the workspace.** The switcher's menu ends with **New Server…**, as does the
  Network panel's **New server project…** (`askNewServer`): `newServer` makes `servers/<name>` (or
  `<name>` when no member is under `servers/`) from `newProjectFiles` with `requires.network` and
  `serverModule` (greets arrivals; a command back to the arrival server), names it in the file
  (`createMember`, `namedBy`) and adds it to the network (or starts one arriving on it).
- **The Console's server switcher**: while a network has members, the Console
  panel shows a row of them over its log (this project's server first, then the
  proxy and the others, each with its `StatusDot`); the one picked is the
  network store's `viewing` (null: this project's server), so a Network card's
  console button sets it and reveals the Console. The log, Clear and the command
  line follow it (`network.command` for a member). A member that goes away
  puts it back to this project's server. In a workspace's network the
  attached member's console is the editor's own: it's the first entry, under
  its name, and isn't listed again.

- **Docks.** Three areas, left, right and bottom (the flags keep their
  first names: `outlineOpen`, `inspectorOpen`, `bottomOpen`, `OPEN[area]`),
  each empty or a tree (`core/store/docks.ts`) of splits (`row`/`column`,
  shares adding up to 1) with tab groups at the leaves. By default: the outline over Instances on the left, the inspector on the
  right, and Project, Problems and the Console (Tests, Profiler, Debug and
  Network behind it) side by side at the bottom. `workbench/docks/DockArea.tsx` draws one:
  splits as nested `Group`s (keyed by their children, so another set lays
  out afresh), groups as react-aria tabs with native drag events through
  `Tab`'s `render` prop. A tab dropped in a tab row joins it at that gap;
  over a group's body (an overlay, only while dragging) its middle joins
  the group and within a quarter of an edge splits the group there
  (`movePanel`: into the parent when it splits the same way; groups left
  empty and one-child splits go); `AreaDrops` puts a strip at the edge for
  a dock that's empty or hidden. The tab's context menu does the same
  (Split Right/Down, Move to a dock, Hide, Reset Layout). The area's top
  right group (`cornerGroup`) has its hide button. Docks resize, hide
  (toolbar, Ctrl/Cmd+B, Ctrl/Cmd+Alt+B, Ctrl/Cmd+J), are remembered per
  project with the open tabs (`core/store/layout.ts`), and View → Reset
  Layout (`view.resetLayout`, also a toolbar button) puts docks, sizes and
  flags back. The side docks stay in their group collapsed while hidden or
  empty: removing a panel would lay the row out again from every panel's
  first size. Editors' inspectors portal into one `<fieldset>` the
  workbench keeps all session (`useInspectorTarget`), which the Inspector
  panel puts in itself while it shows, so an inspector always has a target
  wherever, or whether, its panel is.
- **Editors don't lay out docks.** An editor renders `EditorScreen` (marked
  `data-editor`) with its canvas, and its inspector inside `InspectorPanel`,
  which portals into the Inspector panel (`data-inspector`). Focus requests look in
  the inspector, then the editor, and reveal the Inspector panel.
- **Outlines.** A kind's `outline` in the registry renders `Pane`s of
  `Tree`s for its main file (`{ path }`, loaded by the dock first). The dock
  shows the outline of the resource the active tab belongs to, so a script's
  tab shows its resource's outline too; selecting there calls
  `select(path, …)` then `openFile(path)`. A resource's main document stays
  loaded while any tab inside that resource is open (`closeTab`).
  `FilesPane` lists the resource's folder VS Code style; the dock hands it to
  the outline as `files` (`OutlineProps`), which places it (last, or for a
  centity between Animations and the long Nodes list) and still renders it
  when the model doesn't parse. A node row's `picture` replaces its icon: a
  block or item display shows its `ItemIcon`.
- **Trees** (`ui/Tree`) own only the transient (inline rename, drag);
  selection, expansion (`useTreeExpansion`, in the layout store) and edits
  are the caller's. Keyboard: react-aria's (arrows, Left/Right close and
  open, Home/End, typeahead, Space selects; `selectOnFocus` makes arrows
  select), plus ours: Enter and double-click `onOpen`, F2, Delete/Cmd+Backspace,
  Ctrl/Cmd+D, the context-menu key. With `multiple` (`{ selection,
onChange }`, oldest first, the row acted on last being the primary:
  `selectionOrder`) it's react-aria's multiple selection, and a context
  menu on a selected row keeps the others selected.
- **The explorer** (with each dependency's resources read-only under the
  kinds: see "Packages") lists resources as atoms (`explorer/folders.ts`, one
  folder per registry entry: ids, location, what opens, how to create, from
  `KINDS`). **Group folders** (`items/(weapons)/`, see project-format) show as
  folder tiles before a kind's resources (in the grid, a collage of up to four of
  their resources' thumbnails, `groupPreview`, with a folder badge): Enter/double-click goes in, the
  breadcrumb or Backspace back out, a resource tile dropped on a group (or
  the breadcrumb) or **Move to group…** (`resourceActions.move`) moves it,
  F2 on a group renames its folder, **New group** makes an empty one kept by
  a hidden `.gitkeep` (`GROUP_KEEP`; `groupsOf` counts such a folder), Delete
  on a group asks and deletes it whole, and new resources go in the open group.
  A resource's place isn't its id's: `core/paths.ts` takes a `group` in
  `locationOf`/`mainFileOf`/`opensAs`/`companionOf`, finds it in a file list
  (`groupOf`, `mainFileIn`, `locationIn`, `groupsOf`, cached per list) or
  from a path (`resourceOf(..).group`), and a package's from the outline's
  `groups`. Resource packs never are in one (`KINDS[kind].groupable`). `Thumbnail` draws the kind's registered `thumbnail` over its
  icon: an item's icon, a block's look, a recipe's result, a pack's first picture, a centity drawn, an
  advancement's icon; a 2×2 `ui/Collage` of a loot table's items (`lootItems`, through the project's tables
  it rolls), a game block file's blocks or a particle effect's emitter sprites (tinted with CSS masks); a
  menu's `WindowFrame` and a dialog's `DialogPreview` in miniature (`inert`); a biome's `BiomeScene`; a
  cutscene's path from above (`topDown`); a datapack's `pack.png` or a map's `icon.png` (`folderPicture`).
  Terrains, structures, builds, dimension types, modules and migrations have none. Vanilla pictures everywhere
  come through `useClientAssets`, which draws the textures resource packs redraw (`textures` in `pack.json`)
  with the packs' pictures (`withReplacedTextures`; its `key` tells asset sets apart in caches, never
  `version`); `useGameClientAssets` is the game's own, for starting a replacement from. A kind's (or group's) tiles stay hidden (`opacity: 0`, `data-loading`) until their
  thumbnails are ready (`ui/Reveal`: a picture still being worked out calls `usePendingPicture(true)`; the
  page shows once none is and its `<img>`s have decoded, or after `MAX_WAIT_MS`), and `useLookPicture` keeps
  every look it worked out this session, so an icon shown again draws at once. Centity thumbnails are drawn one at a
  time in one hidden canvas (`editors/centity/CentityThumbnail.tsx`), keyed by
  the centity's text and the client version, so an unchanged centity is
  never drawn twice, even across sessions: each picture is saved as
  `.netherforge/thumbnails/<id>.png` with `index.json` (id → key), read once
  per project (`loadThumbnails`); a saved picture whose key matches is shown
  from disk, a changed centity keeps its old picture until the new one is
  drawn, and the index drops centities that are gone. The workbench mounts that studio (the centity's registered `studio`) while anything is queued, so the outline
  header's picture (the same `Thumbnail`) draws with the explorer hidden.
- **Settings** are a tab of their own (`openSettings(page?)`, type
  `settings`, path `SETTINGS_PATH`, which is no file: nothing loads, saves,
  validates or renames it, and the layout restores it like any tab). The
  outline lists its pages (`SettingsPages`, its `TabView`'s outline); the open page is
  what the settings path's view selects (`openSettings(page)`).
- **Widgets.** Focus, keyboard and ARIA are never hand-rolled: menus,
  context menus, dialogs, the tree, tabs and the palette are
  react-aria-components, splitters react-resizable-panels (react-aria has
  none). Style them through their CSS module and react-aria's data
  attributes (`[data-focused]`, `[data-selected]`, `[data-hovered]`,
  `[data-focus-visible]`, `[data-disabled]`, `[data-drop-target]`; a
  separator's `[data-separator='hover'|'active']`), not `:hover` or our own
  state classes. Specifics:
  - `openMenu(event, items)` draws a `Menu` in a `Popover` anchored at the
    pointer (`ContextMenuHost`); react-aria gives focus back on close.
  - `Modal` is `ModalOverlay`/`Modal`/`Dialog` with a `Heading slot="title"`:
    focus is contained and restored, Escape or a click outside closes, the
    page behind is inert. Any new dialog uses `Modal`, which has a set width (`wide` for pickers and diffs) so long text wraps, and an optional header `icon` (`ask.prompt`/`ask.confirm` take one too).
  - `Tree` is react-aria's `Tree`, a `treegrid` of `row`s (rows render from
    `items` and are cached: anything a row reads goes in `dependencies`).
    Dragging is react-aria's drag and drop (`onMove`/`canMove`; a drop _on_ a
    row that takes rows goes inside it, else beside it; a fixed row is never
    dropped). In a tree with `onMove` every row, the create row too, has a
    `slot="drag"` button for keyboard dragging, disabled on a fixed or
    renaming row: react-aria counts every row as draggable and warns about
    one mounted without it (`Tree.test.tsx` fails on that warning). Keys react-aria doesn't
    take (F2, Delete…) are read on a wrapper; Enter is caught in capture,
    before react-aria would select. An inline field stops its own key, press
    and focus events, so the tree doesn't act on them.
  - Tabs are react-aria `Tabs`/`TabList`/`Tab`/`TabPanel` (the docks'
    groups; `EditorArea`'s open files, whose drag to reorder is native
    drag events through `Tab`'s `render` prop; the explorer's kinds; the
    ingredient picker). A `TabPanel`'s id is the selected tab's.
  - The menu bar is a `MenuTrigger` per menu with a non-modal `Popover`; the
    bar itself (Left/Right between menus, Alt alone) is ours, since react-aria
    has no menubar. Its buttons don't take focus (`Pressable
preventFocusOnPress`), and a chosen item runs a frame after its menu
    closes, once focus is back where it was, so Edit acts on the focused
    field. Toggles sit in a `MenuSection selectionMode="multiple"`, so
    they're `menuitemcheckbox`es.
  - Docks are `Group`/`Panel`/`Separator` (`ui/Splitter`, double-click
    resets): sizes in pixels within `LIMITS`, a panel's `defaultSize` taken
    once when it appears (`DockPanel`; changing it would relayout mid-drag),
    written back from `onResize`. The side docks collapse rather than
    unmounting. The library sets a separator's `flex: 0 0 auto` inline, so
    `Splitter.module.css` gives the 1px line a width or height (a
    `flex-basis` is overridden and the line takes no space), and keeps its
    invisible 7px grip box: see "Testing" for why the previews depend on it.
- **Menus and shortcuts** are one table, `workbench/menus/commands.ts`
  (label, keys, `when`/checked, run), with the kinds' commands and the
  panels' View items added from their registries (`runCommand.ts`: every command,
  `runCommand`, `commandForKey`, `TOOLBAR_COMMANDS`); `buildMenus` (`buildMenus.ts`) lays out File, Edit,
  View, Run, Help, and `useAppMenus` (mounted in `App`) runs every shortcut
  a menu shows from one keydown listener. macOS gets the system menu bar
  (`backend.setAppMenu`, `core/backend/tauriMenu.ts`: built once per shape,
  then only relabelled/enabled/checked); Windows and Linux draw the same
  model in the title bar (`MenuBar`, which never takes focus, so Edit acts on
  the focused field or Monaco via `editors/script/focusedCode.ts`). Undo in a
  field marked `data-commit="clean"` is the document's. Select All works only
  in a field or Monaco: elsewhere its key is claimed and does nothing, and
  `base.css` makes the app unselectable (inputs, `code`/`pre`, console lines
  opt back in). A new command is an entry in `COMMANDS` (with a `hint` and
  `icon` for the palette) and a place in `buildMenus`; a command about one
  kind is a `KindCommand` in its registry entry instead.
- **The palette** (`quickOpen/`, Cmd/Ctrl+P; Cmd/Ctrl+Shift+P or a leading
  `>` for commands alone) lists resources (with their explorer `Thumbnail`),
  text files and `paletteCommands` (`workbench/menus/palette.ts`): every enabled command (`palette` renames
  one or, `false`, leaves it out), plus ids only it has: `settings:<page>`,
  `<kind>.<name>:<id>` (a kind's resource command on one resource, e.g.
  `centity.spawn:tower`), `file.new:<folder>`, `file.recent:<root>`. It runs
  them through `useMenus`'s `run` and `context`, as the menus do. A
  resource's own tab shows the same thumbnail.
- **Zoom**: Cmd/Ctrl +, - and 0 scale the whole UI (`useZoomShortcuts`,
  mounted in `App`, so the welcome screen zooms too) through
  `backend.setZoom`, the webview's own zoom (the memory backend sets CSS
  `zoom`). The level is this machine's, kept in localStorage
  (`core/zoom.ts`), and `main.tsx` applies it before the first frame.
- **Modules** have no editor: a module opens its `init.lua`, and its files
  are in the outline.
