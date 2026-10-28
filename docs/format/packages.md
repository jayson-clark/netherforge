# Packages

A **package** is a project another project uses. There's nothing else to
it: any project can be one, with the same files, the same editor and the same
rules. A project either **depends** on a package (it uses the package as it
is, read-only, and gets its updates) or **copies** something out of one into
itself (it becomes the project's own, to change as it likes).

Dependencies are found by path, as folders next to the project, or fetched
from git repositories.

## Depending on a package

`netherforge.json` names each package the project uses by its namespace, and
says where it is:

```json partial
{
  "namespace": "shop",
  "dependencies": {
    "acme_economy": { "path": "../economy" },
    "acme_quests": { "git": "https://github.com/acme/quests.git", "rev": "v1.2.0" }
  }
}
```

| Key            | Meaning                                                                                                                                                                                                                                                                                                                                                            |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `dependencies` | Optional. The packages the project uses, each under its namespace: the name the project's files and scripts use for it (`acme_economy:coin`, `require("acme_economy:api")`). A name is a usable namespace, not the project's own and not a reserved one (`package.name`). Each names exactly one source: `path`, or `git` (`package.source`).                      |
| `…path`        | The package's folder, relative to this project's folder, `/` between its parts: `../economy`, `vendor/economy`. `..` is fine (a package is usually a sibling folder); an absolute path, a drive letter or a backslash isn't (`package.path`). The folder must hold a project (`package.missing`) whose `namespace` is the dependency's name (`package.namespace`). |
| `…git`         | A git repository holding the package at its top: `https://…`, `ssh://…`, `user@host:path` (`git@github.com:acme/quests.git`) or `file://…`. Plain `http://` and `git://` aren't fetched from, since anyone on the way could swap the code (`package.git-url`).                                                                                                     |
| `…rev`         | Optional, with `git`: the branch, tag or full commit to use (`main`, `v1.2.0`); the repository's default branch when absent (`package.git-rev`). The lock pins the commit it resolved to.                                                                                                                                                                          |

A package's own `dependencies` are followed too, each path relative to the
package's folder, so a project uses the whole tree. A package from git can
depend on other git packages but not on a folder by `path`, which isn't there
for whoever fetches it (`package.git-path`). Resolving it gives **one
package per namespace**: two different packages with one namespace anywhere
in the tree are a conflict (`package.conflict`, naming what depends on each),
and packages that depend on each other in a loop are an error
(`package.cycle`). A server runs every package in the tree.

## What a package exports

A package decides what other projects may use of it:

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

`exports` lists resources by the folder their kind lives in (`modules`,
`items`, `dialogs`, `resource_packs`, `centities`, `menus`, `particles`, `recipes`,
`loot`, `advancements`, `structures`, `wasm`, `worlds`); an exported resource pack exports every skin, glyph, item
model, tooltip and sound in it. Every id must be one the package has
(`package.export-missing`) and every key a kind's folder
(`package.export-kind`). Lists are kept in id order.

Everything else is the package's own. A project that depends on it can name
only what it exports: a reference to anything else of its is an error
(`reference.not-exported`), and so is a `require` of a module it doesn't
export. Inside the package, its own resources are named as any project names
its own, without a namespace.

Scripts follow the same rules at run time: a script names its own package's
things bare and a dependency's exported ones as `namespace:id`, and anything
else is an error naming the package. Names the API hands back are written as
the reading script's package writes them. See
[Names across packages](../reference/packages.md).

## Each package on its own

A package is validated as itself, in its own namespace, against what its own
dependencies export: the project depending on it can't make it valid or
invalid. Its problems show up alongside the project's at **package paths**,
`<namespace>:<path>` (`acme_economy:items/coin/item.json`); a project path
never holds `:`, so the two can't be confused. Scripts' errors in a package
say where they are the same way.

On a server, a package's resources run beside the project's, named as the
project names them: the project's item `ruby` and the package's
`acme_economy:coin`. Each package's resource packs are built into the one resource pack
in the package's own namespace (`assets/acme_economy/…`).

## `netherforge.lock`

Beside `netherforge.json`, the lock says what the dependencies resolved to,
so everyone working on the project, and every bundle built from it, uses the
same packages. Commit it.

```json
{
  "$schema": ".netherforge/schema/lock.schema.json",
  "packages": {
    "acme_economy": {
      "version": "1.4.0",
      "source": {
        "type": "path",
        "path": "../economy"
      },
      "hash": "sha256:9f2c…"
    }
  }
}
```

| Key        | Meaning                                                                                                                                                                                                                                                                                                         |
| ---------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `packages` | Every package in the tree, the dependencies' dependencies included, by namespace.                                                                                                                                                                                                                               |
| `version`  | The package's `version` from its `netherforge.json`.                                                                                                                                                                                                                                                            |
| `source`   | Where it came from, by `type`: `path` with the folder relative to this project's (`path`); `git` with the repository's `url`, the dependency's `rev` (absent for the default branch) and the `commit` it resolved to. `registry` (`url`) is reserved for what comes later, so a lock naming one is out of date. |
| `hash`     | The package's [content hash](#content-hash).                                                                                                                                                                                                                                                                    |

The editor writes the lock whenever the dependencies resolve to something it
doesn't say (a new dependency, a changed package), and so does
`netherforge lock`. While it's missing or out of date, loading the project
warns (`lock.missing`, `lock.stale`), and `netherforge build` refuses to build.

### Packages from git

A git dependency is used at the commit the lock pins for as long as its
`git` and `rev` are what the lock says: a branch moving on changes nothing
until you update (**Update from git** on the package in the editor's
explorer, or `netherforge lock --update`), which resolves every git
dependency's `rev` afresh and rewrites the lock. Change a dependency's `rev`
and it's resolved again the next time the project loads.

The editor and `netherforge` fetch with your own `git`, so your credentials
and SSH keys work as they do for everything else. Fetched packages are kept in
the editor's data folder (`packages/git/`), shared by every project. A package
from git is someone else's code, so nothing in its repository runs while it's
fetched (no hooks, no filters), and links and submodules are left out.

Every load checks the package's files against the lock: a package from git
that isn't what the lock pins isn't loaded (`package.hash`). If the lock is
right, the downloaded copy was changed: delete
`packages/git/checkouts/<commit>` in the editor's data folder and it's
fetched again. A repository that
can't be fetched (it's gone, the network is down, the `rev` isn't there) is
`package.git`, with git's own words.

### Content hash

A package's `hash` in the lock is a fingerprint of what the package _is_: its
`netherforge.json` and every file of every resource, but no hidden file,
README, agent notes or lock. If any of those files changes, the hash does too.
`fonts/default.json` isn't in it either: the editor writes that file from the
Minecraft client imported on the computer it runs on, and only the project's
own is ever read, so opening a package in the editor never changes its hash. (It's the SHA-256 of a listing of each file's
SHA-256 and path, in path order, the layout Go's `dirhash` uses.)

## Bundles

`netherforge build` writes a **bundle**: one folder holding the project and
every package it depends on, which a production server runs as it is. A
server outside the editor never resolves a dependency itself: give it a
bundle. A project with dependencies that isn't one is refused
(`runtime.unbundled`); a project without dependencies can still run straight
from its folder.

```
build/shop-1.0.0/
  netherforge-bundle.json
  shop/                 the project: netherforge.json and its resources
  acme_economy/         each package, in a folder named after its namespace
```

```json kind=bundle
{
  "formatVersion": 1,
  "project": "shop",
  "packages": {
    "acme_economy": { "version": "1.4.0", "hash": "sha256:9f2c…" },
    "shop": { "version": "1.0.0", "hash": "sha256:51e0…" }
  }
}
```

A bundle holds only what the [content hash](#content-hash) covers, plus each
`fonts/default.json` (the server measures text with the project's), and the
hashes it lists, the project's own included, are checked when the server
starts: a package whose files don't hash to its entry isn't run
(`runtime.bundle-hash`). Point the plugin's `project` setting (or
`-Dnetherforge.project`) at the bundle's folder.

## Copying instead

**Copy into project**, on a dependency's resource in the editor's explorer,
brings that resource into the project under an id of its own. Its references
are rewritten so they still name what they named: the package's own things
become `<namespace>:…` (which works where the package exports them), and
references to the copied resource itself name the copy. The copy is the
project's from then on; the dependency stays as it was.

## Templates

The editor's project templates (a minigame, a shop, an RPG mob) aren't
packages a project depends on: adding one copies its files into the project as
its own, and adds what it requires to the project's `requires`. See
[Starting from a template](../guide/templates.md).
