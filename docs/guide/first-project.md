# Your first project

This page takes you from an empty folder to standing in a running dev server.

## Create the project

1. Open NetherForge and choose **Create project…**.
2. Give it a **name** (shown in the editor; you can change it later in
   `netherforge.json`), pick the **Minecraft version** it targets, and choose an
   **empty folder** for it.
3. Choose **Create**.

The editor writes a minimal project and opens it:

```
my-server/
  netherforge.json      the manifest: name, target Minecraft version, format version
  .gitignore          excludes .netherforge/
  centities/
  modules/
```

`netherforge.json` is what makes a folder a project:

```json
{
  "$schema": ".netherforge/schema/netherforge.schema.json",
  "formatVersion": 1,
  "name": "My server",
  "namespace": "my_server",
  "version": "0.1.0",
  "minecraft": "26.3"
}
```

`namespace` is the project's own name on a server: everything it registers or
saves there is named `my_server:<id>`, so it never collides with another
project's or the game's. The editor makes one from the project's name; see
[references](../format/references.md). `version` is the project's own, and
`formatVersion` is the version of the project format the files are written
in. NetherForge opens only projects in its own format version, and doesn't
migrate older ones; see [Format version](../format/project.md#format-version).

This is a good moment to make it a git repository (`git init`); see
[Using git with a project](git.md).

To open an existing project, choose **Open folder…** and pick the folder that
holds its `netherforge.json`. The example projects in the NetherForge repository
are good ones to explore: `examples/showcase` is a walk-through of everything
NetherForge does, a booth for each feature (see [The showcase](showcase.md)).

## The editor's window

- The **Project** explorer, at the bottom, shows every resource in the
  project as a tile, one kind at a time (centities, menus, dialogs, items,
  recipes, particles, cutscenes, resource packs, modules, structures, worlds). Double-click a
  tile to open it. Click it and press <kbd>F2</kbd> to rename it, or
  <kbd>Ctrl</kbd>/<kbd>Cmd</kbd>+<kbd>D</kbd> to duplicate it. Right-click it
  for everything else. The search box looks across every kind.
- As a project grows, sort a kind's resources into **groups**: **New group**
  (above the tiles) makes one, then drag tiles onto it, or right-click a
  resource, choose **Move to group…** and type a group's name (`weapons`, or
  `weapons/swords` for one inside another). A
  group shows as a folder tile; double-click it to go in, and use the path
  above the tiles (or <kbd>Backspace</kbd>) to come back out. New resources go
  in the group that's open. On disk a group is a folder in parentheses,
  `items/(weapons)/`, and it only sorts: a resource keeps its id wherever it
  is, so nothing that names it changes (see
  [group folders](../format/project.md#group-folders)).
- The **Outline**, on the left, is the inside of the resource you have open:
  a centity's animations and nodes (a block or item node shows its block or
  item), a menu's slots, a resource pack's entries. With them are its **Files** (its
  Lua scripts and anything else in its folder) as a tree you can add to,
  rename, drag around and delete from; a centity lists them above its nodes,
  so they stay close at hand. A script's tab shows its resource's outline too.
- The **Inspector**, on the right, edits whatever is selected.
- **Instances** (the centities alive on the dev server) sits under the
  Outline.
- The bottom dock shows the Project explorer, **Problems** and the
  **Console** side by side, with **Tests**, the **Profiler** and **Debug** as
  tabs behind the Console.
- Every panel, the Outline and the Inspector included, is a tab you can
  drag: onto another panel's tabs to join them, onto the edge of a panel to
  split it there (side by side or one above the other), or while dragging
  onto the strip that shows for a hidden or empty dock. Right-click a
  panel's tab to do the same from a menu. **View → Reset Layout** (or the
  reset button beside the dock buttons) puts everything back.

<kbd>Ctrl</kbd>/<kbd>Cmd</kbd>+<kbd>P</kbd> goes to any resource or file by
name, and <kbd>Ctrl</kbd>+<kbd>Tab</kbd> (with <kbd>Shift</kbd> to go the
other way) steps through your open tabs. The buttons at the top right show and hide the outline, the bottom dock
and the inspector, as do <kbd>Ctrl</kbd>/<kbd>Cmd</kbd>+<kbd>B</kbd>,
<kbd>Ctrl</kbd>/<kbd>Cmd</kbd>+<kbd>J</kbd> and
<kbd>Ctrl</kbd>/<kbd>Cmd</kbd>+<kbd>Alt</kbd>+<kbd>B</kbd>. Drag a dock's edge
to resize it. A project opens again as you left it, with the same tabs and
docks.

## Show Minecraft's textures (optional)

Open **Settings → Minecraft** (the gear at the top right, or
<kbd>Ctrl</kbd>/<kbd>Cmd</kbd>+<kbd>,</kbd>: Settings open as a tab, their
pages listed in the outline). The editor lists the Minecraft installs it found
(the official launcher, Prism Launcher, MultiMC, Modrinth App, CurseForge,
ATLauncher) and the versions they hold. **Import** the version your project
targets, and the 3D previews use your own copy of Minecraft's textures and
models. If you haven't installed that version yet, launch it once in the
Minecraft launcher, or pick a client jar by hand.

## Start the dev server

Choose **Start server** in the toolbar.

1. The first time, the editor shows Minecraft's EULA. Running a Minecraft
   server means agreeing to it; read it, then **Accept and start**. You're
   asked once.
2. The editor prepares the server: it finds Java 25 (or downloads it), downloads
   Paper for your project's Minecraft version, and sets up a server for this
   project in its own data folder (not in the project), with the NetherForge
   plugin installed.
3. Paper starts. The status reads **running** when it's ready, and the hot
   reload light turns on when the server is ready to take your changes.

The **Console** panel shows the server's output, and you can type server
commands into it. The first start for a Minecraft version also learns that version's blocks and
items, so the editor can check names and offer them in pickers.

The server's port (25565 by default), memory and extra JVM arguments are in
**Settings → Server**. The editor only accepts the arguments a server owner
tunes: memory and stack sizes (`-Xmx4G`), a list of `-XX:` garbage collector
and heap options, and `-D` properties outside the JVM's own. Anything that
loads code or writes files (`-javaagent:`, `-XX:OnOutOfMemoryError=`) is
refused, with the reason shown under the field.

## Join it

In Minecraft (the same version as the project), go to **Multiplayer → Direct
Connection** and connect to `localhost` (or `localhost:<port>` if you changed
it).

To use NetherForge's commands you need to be an operator. Type this into the
editor's Console:

```
op YourName
```

Now `/nf` (short for `/netherforge`) works in game:

| Command                             | Does                                                                      |
| ----------------------------------- | ------------------------------------------------------------------------- |
| `/nf spawn <centity> [player]`      | Spawns a centity in front of you (or the named player).                   |
| `/nf list`                          | Lists spawned centities, nearest first.                                   |
| `/nf find <centity>`                | Lists the instances of one centity.                                       |
| `/nf tp <instance>`                 | Teleports you to an instance (the first characters of its id are enough). |
| `/nf kill <instance\|centity\|all>` | Removes instances.                                                        |
| `/nf reload [paths…]`               | Reloads the whole project, or the resources owning those paths.           |
| `/nf modules`                       | Lists modules, whether each is running, and their commands.               |
| `/nf scripts`                       | Lists running scripts by what they cost the server a tick.                |

The dev server's world lives in the editor's data folder, under `servers/`
(see [where the editor keeps things](install.md#where-the-editor-keeps-things)),
so it's never part of the project. Delete that project's server folder for a
fresh world.

Next: [your first centity](first-centity.md).
