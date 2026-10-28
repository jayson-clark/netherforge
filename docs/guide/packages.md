# Packages and workspaces

Any project can use another one as a **package**: an economy, a quest system, a
set of items that several servers share. A **workspace** keeps several projects
together in one folder, usually a server network with a project for each server
and the packages they share.

## Using a package

In the explorer, **Dependencies** → **Add dependency…** offers two sources:

- **Folder…**: a project next to yours on disk (`../economy`). Changes to it
  show up as soon as it's saved.
- **Git repository…**: a project in a git repository, at a branch, tag or
  commit (`v1.2.0`). The editor fetches it with your git.

The package's namespace is the name your project uses for it. Its resources
appear under Dependencies, read-only, and your files and scripts name the ones
it exports as `namespace:id`:

```lua
local economy = require("acme_economy:api")
player:give_item(nf.items.create("acme_economy:coin", { count = 5 }))
```

A package decides what other projects may use with `exports` in its
`netherforge.json`. Everything else stays private to it, and naming something
it doesn't export is a problem in your project.

`netherforge.lock` pins each package to an exact commit and content hash, so
everyone working on the project, and your production server, runs the same
code. Commit it. The editor keeps it up to date as dependencies change;
`netherforge lock --update` moves git packages to their branch's newest commit.

To change something a package gives you, find it under Dependencies and use
**Copy into project**. The copy is yours from then on; the package stays
as it was.

## Making a package

A package is an ordinary project. List what other projects may use in
`exports`, by kind:

```json partial
{
  "namespace": "acme_economy",
  "exports": {
    "modules": ["api"],
    "items": ["coin"],
    "resource_packs": ["ui"]
  }
}
```

Put it in a git repository and tag releases, and others can depend on it by
URL. A package is checked on its own, against its own dependencies, so it works
the same in every project that uses it.

## Workspaces

A workspace is a folder with a `netherforge-workspace.json` that names its
projects, and optionally the dev network the editor runs from them:

```
mynetwork/
├── netherforge-workspace.json
├── servers/
│   ├── hub/
│   └── minigames/
└── packages/
    └── shared/
```

Opening any of its projects opens the whole workspace:

- **One trust** covers every project in it.
- **The project list** at the top of the window switches between them, and the
  dev network keeps running while you do.
- **Packages in the workspace are edited in place** from a project that uses
  them. Saving one reloads every server that depends on it.
- **New Server…** adds a project for another server of the network, and
  **Move to Package…** on a module moves it into a shared package, rewriting
  the `require`s that used it.
- **Problems** and **Run Workspace Tests** cover every project.

`netherforge check`, `test`, `format` and `lock` run in the workspace's folder
go through every project in it, which is all CI needs.

See [Server networks](network.md) for running the network itself, and
[Packages](../format/packages.md) and [Workspaces](../format/workspace.md) for
every key.
