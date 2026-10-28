---
name: editor-packages
description: How the editor handles a project's dependencies - the packages slice (refreshPackages, git fetches at the lock's pin, syncLock, updatePackages), package paths (ns:path) and read-only packages, building paths that keep their package (inPackage, filesIn), the explorer's Dependencies and Copy into project, templates (applyTemplate), adding a dependency, and a dependency's resources rendering wherever the project's do. Read before touching apps/editor/src/core/store/packages.ts, core/templates.ts or explorer/Dependencies.tsx.
---

# The editor's packages and templates

Part of the editor's guide: the editor-ui skill has the layers, the registry, the store's invariants and testing, and a section named here that isn't in this skill is in one of the others it lists.

## Packages (dependencies)

A project's dependencies (`netherforge.json`'s `dependencies`, see
docs/format/packages.md) are read-only folders: beside it (`path`), or a git
package's checkout in the package cache (`git`). A package's **location**
says which: `../library`, or `git:<commit>`; every backend package command
takes it. The workspace's packages slice
(`core/store/packages.ts`): `refreshPackages` (on open, when the manifest or
the lock changes, on a rescan) asks format what resolving the tree needs
(`packagesNeeded`, one round at a time, since a package's own dependencies
are only known once it's read): git dependencies to fetch, each at the
commit the lock pins when it does (`packageFetchGit`, answered `{ commit }` or
`{ error }` by the request's `key`; `fetchingPackages` is set meanwhile), and
folders to read, each through the backend (`packageFiles`, `packageReadText`:
JSON as text, the rest null, as the project's own) and hashed (`packageHash`:
the backend's per-file SHA-256, format's listing, WebCrypto's SHA-256 in
`core/sha256.ts`). The result is the store's `packages` (`PackageInputs`:
`folders` by location, null for nothing there, and `git` by key), cached and
handed to every `loadProject`; the outline then has `packages` (by namespace:
location, origin, version, resources, exports) and `lock`.
`syncLock` keeps `netherforge.lock` what format says it should be (written when
it differs, deleted once the manifest names no dependency, never over a dirty
lock doc, never when a git package isn't what the lock pinned (format's lock
is null then); then hot-reloaded, which the plugin treats as a full reload,
and the packages refreshed, so a git dependency is asked for at its new pin).
`updatePackages` (the explorer's **Update from git** on a git package)
resolves the tree with the lock left out, so every `rev` is fetched afresh,
and writes the lock that makes. `describeOrigin` is a package's source in
words. `isProjectJson` counts `netherforge.lock` as JSON format reads.

**Building a path from a package path keeps the package.** `resourceOf(path).pkg` is the package a path is in
(null for the project's), and `inPackage(pkg, path)` writes a path built from a resource (`mainFileOf`,
`locationOf`, `opensAs`) back into it: the outline's subject, tabs, and anything else that derives one path from
another go through it, or a dependency's resource reads the project's file of the same name. A resource's file list
is `filesIn(s, pkg)` (the package's files by package path, from the packages slice; `s.files` for the project), and
a pack's id from a path is `packOf(path)` (`library:gems`), which `texturesOf`/`soundFilesOf` and the pack path
helpers take. `DocumentNamespace` is set by the editor area; whatever renders a document's content outside it
provides it again: the outline dock (`subject.pkg`) and `Viewport3D` (its scene content goes to the workbench's one
canvas). Pack entry pickers read `useResourcePackOutlines()`, the document's own packs.

A package's files are named by **package path**, `ns:path`
(`library:items/gem/item.json`): problems in a package and script errors in
it come that way. Each package the outline has is marked read-only as a
whole (`setReadOnly('library:', …)`; a mark ending in `:` covers its package
paths, `markPackages` after every validation), and `loadDoc` reads a package
path through `packageReadText`, so opening one (a problem, the explorer)
shows it in its kind's own view, read-only, as E1's read-only documents are.
The explorer's **Dependencies** (under the kinds,
`explorer/Dependencies.tsx`, its groups from the registry's explorer
folders) lists each package's resources; double-click opens one read-only,
and **Copy into project**: `copyFromPackage` copies the folder (`packageCopy`),
then rewrites each JSON document with format's `moveRefs` (the package's own
things become `ns:…`, the resource itself the copy), then validates and
hot-reloads.

**A workspace's packages are edited in place.** A dependency whose location is one of the open
project's workspace's projects (`workspaceMemberAt(project, location)`: the location resolved from
the project's folder, never a git package) isn't marked read-only (`markPackages` skips it), so its
documents open and edit as the project's do; saving one (`save` → `writePackageFile`) writes into
that project (`workspaceMemberWriteText`, by its path in the workspace, scoped as a project's),
then `refreshPackages` (its hash changes: `syncLock` rewrites this project's lock and the server
reloads whole) and `relockDependents` (every other workspace project whose lock names it by path
gets the new hash, canonically: each write is a change in that project, which its network server's
watcher reloads). Only files that exist are edited from a dependent; creating, renaming and deleting
are the package's own project's (the explorer's **Open its project**, `focusMember`). The
Dependencies view says **in the workspace** and offers **Open** instead of **Copy into project**.
Tests: `core/store/workspacePackages.test.ts`.

**Moving a module out into a workspace package** (`core/store/workspaceMembers.ts`, the explorer's
**Move to Package…** on a kind whose registry entry says `movesToPackage` (modules), given its folder, `workbench/workspaceActions.ts` `askMoveModule`): `planModuleMove`
says what it would do (the target member, or a new package whose folder name is its namespace;
the project's scripts that `require` it; the other project modules it requires, which stay; the
project's packages it names, which the package then depends on from where it is) and why it
can't (not text, unsaved, the project's own namespace, a folder inside a member).
`moveModuleToPackage` makes a new package (`createMember`), writes the files into its
`modules/<id>/` (its own `ns:` names bare), exports it when required, adds the dependencies, then
in the project adds the dependency by path, rewrites `require("id…")` to `require("ns:id…")`,
deletes the folder and refreshes the packages. Not undoable (it writes another project).

**Templates (W3.2).** The minigame, shop and RPG mob templates are the
projects `examples/template_*`, bundled by `core/templates.ts`
(`import.meta.glob` of the example's files, tests included, without
`netherforge.json` and `.luarc.json`; `TEMPLATES`, each with the `requires` its
manifest declares). They're starting content, not dependencies: the packages
slice's `applyTemplate(id)` writes every file into the project where the
template has it (one transaction: undo removes it all), merges the template's
`requires` into `netherforge.json` (`mergeRequires`, through the manifest's
model, then saved), and hot-reloads; it refuses, naming them, when the project
already has a resource of the same kind and id (wherever its group folder) or
a file at the same path. The explorer's **Add template…** (`templateMenu` in
`Dependencies.tsx`) and **Create project**'s **Start with** select
(`Welcome.tsx`, which calls `applyTemplate` once the project is open) offer
them. The templates' own tests are the repository's (testing skill).

**Adding a dependency.** The explorer's **Add dependency…** (`dependencyMenu`)
and the project settings' Dependencies section share
`editors/project/dependencyPrompts.ts`: **Folder…** is the native picker
(`pickFolder`), the path made relative to the project (`relativeFolder` in
`core/paths.ts`; another drive is refused) and the namespace read from the
folder's `netherforge.json` (`packageReadText`), since a dependency's name must
be its package's; **Git repository…** asks for the URL and then the namespace
(guessed by `namespaceFromUrl`). The explorer adds it with the packages slice's
`addDependency(namespace, source)` (manifest edited and saved; the usual refresh
reads the package) and shows the package; the settings page edits its open
document. The memory backend still serves a package folder inside the open
project (`folderOfOpenProject`), as the Rust side does by path.

**A dependency's resources render wherever the project's do** (L5): a menu
slot, recipe or item icon naming `library:gem`, an item picker, a glyph in
text. The pieces: the resource packs store builds every package's resource pack beside the
project's, keyed `ns:id` as format keys them (`resourcePackTexts` reads them from
`packages`; format's `compileResourcePacks` numbers glyphs across all, the
project's first); `resourcePackTexturePath('library:gems', …)` is a package path, and
`fileUrl` (and so `useProjectFileUrl`) serves one from the package's folder
(`Backend.packageFileUrl`, `nfproject://…/:package/<location>/<path>`);
`useProjectItem("library:gem")` reads the package's item file as it was read
(`packageText`) with every reference written in full (format's
`qualifyRefs`), so its look resolves beside the project's; `splitResourcePackRef`
answers a package's resource pack as `ns:id`; `useNameableIds(kind)` lists what a
project file may name of a kind: the project's ids and what its direct
dependencies (`directDependencies`) export (`exportedIds`, as `ns:id`);
`useProjectItemIds` is its items'. The
memory backend serves `examples/library` at `/memory/library`
beside `/memory/showcase`; tests take both from `exampleProjects()` and
`examplePackages` (`testing/fixtures.ts`).
