# Workspaces

A workspace keeps several projects together: a server network's repository,
with a project for each server and packages they share. It's one file at the
top of the folder that holds them, `netherforge-workspace.json`, and it isn't a
project itself: it names them, and the dev network the editor runs from them.

```
polsarnet/
├── netherforge-workspace.json
├── servers/
│   ├── hub/          a project (netherforge.json)
│   ├── chess/
│   └── terra/
└── packages/
    └── chess/        a package the chess server depends on
```

```json
{
  "$schema": ".netherforge/schema/workspace.schema.json",
  "name": "Polsar Network",
  "projects": ["servers/*", "packages/*"],
  "network": {
    "arrival": "hub",
    "servers": {
      "chess": "servers/chess",
      "hub": "servers/hub",
      "terra": "servers/terra"
    }
  }
}
```

| Key        | Meaning                                                                                                                                                                                                                                                                                                                                                                                                                           |
| ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `name`     | What the workspace is called, as the editor shows it (`workspace.name`).                                                                                                                                                                                                                                                                                                                                                          |
| `projects` | Its projects and packages, in the order the editor lists them: folders relative to this file (`servers/hub`), or a folder and `/*` for every project directly in it (`servers/*`). Never `..` or absolute: a workspace holds what's under it (`workspace.project-path`). A folder named on its own must hold a `netherforge.json` (`workspace.missing-project`); a `/*` that finds none is a warning (`workspace.empty-pattern`). |
| `network`  | Optional. The dev network: `servers`, each server's name as the proxy names it (lowercase letters, digits, `_` and `-`, at most 32: `workspace.server-name`) and the project it runs, one of the workspace's (`workspace.server-project`); and `arrival`, the server players arrive on when they join (`workspace.arrival`). Two servers may run the same project.                                                                |

A project in a workspace is still a project: it has its own namespace, its own
files and its own `netherforge.lock`, and a server runs it as it would anywhere.
A server depends on a package in the workspace by path, as on any folder
(`"path": "../../packages/chess"`).

## In the editor

Opening any of a workspace's projects opens the workspace. The editor finds
`netherforge-workspace.json` in a folder above the project, and the project is
one of its `projects`. Opening the workspace's own folder opens the project
players arrive on (the network's `arrival` server's), or else its first
project that isn't a package.

- **Trust** is the workspace's: trusting it trusts its folder and everything
  in it, so its projects run without asking again. A project moved out of it
  is on its own again.
- **Switching projects** keeps the dev network running. The workspace's
  projects are a list at the top of the window; the one you pick is the one the
  explorer, the outline and Problems show, and the editor's console, debugger
  and profiler follow the server running it.
- **The dev network** is the file's `network`. The Network panel edits it
  there, so everyone working on the workspace runs the same one; whether it's
  on is yours, on your computer. Each server keeps its world under its name, so
  moving a project in the workspace keeps it.
- **Packages** in the workspace are edited in place from a project that depends
  on them. Saving one reloads every server whose project uses it, and the
  dependents' locks are kept up to date.
- **Network names** complete in scripts: `nf.network.send_player(player, "`
  offers the network's servers, and `channel("` and `map("` the channels and
  maps the project's scripts and its packages' use. A player sent to a server
  the network doesn't have is a warning (`workspace.unknown-server`), in the
  editor and from `netherforge check` in the workspace's folder.
- **New Server…** in the project list makes a project for another server of
  the network beside the others (`servers/<name>`), with a module that greets
  players sent there and a command back to where players arrive, and adds it
  to the network and the file's `projects`.
- **Move to Package…** on a module moves it out of the project into a package
  of the workspace (one already there, or a new one at the folder you name):
  the project depends on the package by path and its scripts require the
  module as `namespace:id`, so its servers run it as before, and the package
  depends on the packages the module uses.
- **Problems** lists the other projects' problems too, by project, checked
  again after every save; opening one switches to its project. **Run Workspace
  Tests** in the Tests panel runs every project's tests, each on its own
  Minecraft version.

## Checking a workspace

`netherforge check`, `test`, `format` and `lock` run in the folder that holds
`netherforge-workspace.json` go through every project in it, packages first,
and fail if any does.
