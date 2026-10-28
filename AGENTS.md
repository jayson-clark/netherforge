# NetherForge

A desktop editor and open-source engine for Minecraft server content. A
project is a folder of plain files: **centities** (composed, scripted
entities), Lua **modules**, menus, dialogs and resource packs. The
editor opens a project, runs a local Paper server with the NetherForge plugin,
and hot-reloads the project into it on save. Users version projects with git.

## Repo map

`apps/` holds what we ship, `packages/` the libraries they share, `tools/`
everything that builds, checks and releases. A new app is a folder in
`apps/` (pnpm picks up `apps/*` and `packages/*`; a Kotlin one is also
registered in `tools/gradle/settings.gradle.kts`).

| Path                           | What it is                                                                                                                                                                                                                                                 |
| ------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `apps/editor/`                 | The Tauri app. `src-tauri/` is Rust (files, watcher, server process, installs); `src/` is React.                                                                                                                                                           |
| `apps/plugin/runtime/`         | The Paper plugin's version-independent runtime: Lua host, centities, modules, hot reload.                                                                                                                                                                  |
| `apps/cli/`                    | The `netherforge` command (`check`, `format`, `lock`, `build`, `test`, `preview`): format's JS build in one Node script, which launches the test runner's jar for `test` and draws a terrain for `preview`. The editor writes it into projects for agents. |
| `apps/plugin/test-runner/`     | `NetherForgeTest-<v>.jar`: runs a project's `*_test.lua` files (`nf.test`) on the testkit's fake server, each test on a fresh one; what `netherforge test` and the editor's Tests panel run.                                                               |
| `apps/plugin/paper-common/`    | The Paper adapter on Paper's API, for every supported version (the last 1.21.x and all of 26.x).                                                                                                                                                           |
| `apps/plugin/paper-internals/` | Server code past the API (registries, goal priorities, the bots), compiled by each adapter against its own server.                                                                                                                                         |
| `apps/plugin/paper-<mc>/`      | One adapter per Minecraft version: its Paper build and what its server says its own way. A plugin jar each.                                                                                                                                                |
| `apps/plugin/integration/`     | The integration scenarios, run against every adapter on headless Paper.                                                                                                                                                                                    |
| `packages/format/`             | Kotlin Multiplatform (JVM + JS). The one definition of what a project file means: parsing, canonical writing, validation, compilers, the dev bridge protocol.                                                                                              |
| `packages/terrain-preview/`    | What the terrain preview draws (colours, pixels, the project structures a generator places), shared by the editor and `netherforge preview`.                                                                                                               |
| `packages/structures/`         | Minecraft's structure files: NBT, structures, schematics, builds cut into parts, and pictures of them, shared by the editor and `netherforge preview` and `import`.                                                                                        |
| `packages/models/`             | The game's block and item models as geometry, and drawn to pictures without a browser, shared by the editor's viewport and `netherforge render`.                                                                                                           |
| `packages/api/`                | The Lua API spec: the single source for completions, LuaLS stubs, the API docs and the runtime's Lua bindings.                                                                                                                                             |
| `packages/project-support/`    | What NetherForge writes into a project's `.netherforge/` (LuaLS's library, the JSON Schemas, the docs for agents), shared by the editor and `netherforge setup`.                                                                                           |
| `packages/network/`            | A server network's protocol and its hub (presence, channels, requests, shared maps, sending players), shared by the Paper plugin and the proxy plugin.                                                                                                     |
| `apps/proxy/velocity/`         | `NetherForge-<v>-velocity.jar`: the network's hub, run by a Velocity proxy that tells it who's online where and moves players for it.                                                                                                                      |
| `docs/`                        | The docs site (VitePress, `pnpm docs:dev`): guides, the file-format spec (`docs/format/`), and the generated Lua API reference (`docs/reference/`).                                                                                                        |
| `examples/`                    | Sample projects. Also the golden fixtures every part is tested against.                                                                                                                                                                                    |
| `tools/`                       | Cross-platform build/test/lint runners, and `release.mjs` (one version everywhere; see the release skill).                                                                                                                                                 |
| `tools/gradle/`                | The Gradle build's root: settings, wrapper, version catalog, `build-logic/` (convention plugins). Run it through `node tools/gradle.mjs <tasks>`; project paths are `:format`, `:plugin:*`.                                                                |

## Commands

Run from the repo root on any OS.

```sh
pnpm install
pnpm test               # unit + golden + contract tests for everything (~2 min)
pnpm lint               # ktlint, prettier, eslint/tsc, rustfmt, clippy, stylua, generated-file check
pnpm format             # apply the formatters
pnpm generate           # regenerate committed outputs (packages/api/, the editor's command bindings)
pnpm deps:lock          # after a Gradle dependency change: rewrite lockfiles and checksums
pnpm test:integration   # every scenario on headless Paper, for every supported Minecraft version
pnpm test:e2e           # editor UI flows (Playwright, on the memory backend)
pnpm test:screenshots   # the previews, pixel for pixel, in the Playwright container (Docker)
pnpm dev                # run the editor
pnpm docs:dev           # run the docs site
```

A change isn't done until `pnpm lint` and `pnpm test` pass, and it comes with
tests at the lowest layer that can catch its bug.

## Rules that apply everywhere

- **`format` is the only writer of project JSON.** Anything that saves a
  project file goes through its canonical writer, so an unchanged file is
  never a diff.
- **Lua never goes inside JSON.** Scripts are sibling `.lua` files referenced
  by path.
- **No Minecraft data in the repo or anything we ship**, and no Minecraft ids
  as literals in logic. Game facts come through `GameData`; version
  differences through `format`'s feature table or a platform adapter.
- **The plugin never needs a fact only the game client knows.** If it would,
  the editor resolves that fact into the project as explicit data.
- **The Lua API changes in `packages/api/` first.** Edit the spec, run
  `pnpm generate` (stubs, docs and the runtime's bindings and interfaces),
  then implement what it generated in the runtime.
- **Generated files are never edited by hand.** `pnpm lint` fails if they're
  stale.
- **A format change** updates `docs/format/`, the examples and the golden
  tests in the same change.

## Skills

Focused guides live in `.claude/skills/<name>/SKILL.md`. **Read the skill for
an area before changing it.** They're plain Markdown, so any agent or person
can follow these paths.

| Skill                                                                      | Read it when you're…                                                                   |
| -------------------------------------------------------------------------- | -------------------------------------------------------------------------------------- |
| [project-format](.claude/skills/project-format/SKILL.md)                   | changing what a project file can contain, or how it's validated                        |
| [game-data](.claude/skills/game-data/SKILL.md)                             | touching anything that knows about blocks, items, shapes, fonts or textures            |
| [minecraft-versions](.claude/skills/minecraft-versions/SKILL.md)           | adding or bumping a Minecraft version, or using a version-gated feature                |
| [testing](.claude/skills/testing/SKILL.md)                                 | writing tests, updating goldens, or changing CI                                        |
| [plugin-runtime](.claude/skills/plugin-runtime/SKILL.md)                   | working in `apps/plugin/`                                                              |
| [plugin-sandbox](.claude/skills/plugin-sandbox/SKILL.md)                   | touching the Lua sandbox, the prelude, the profiler or the debugger                    |
| [plugin-capabilities](.claude/skills/plugin-capabilities/SKILL.md)         | adding a power scripts must declare: network, web, wasm, other plugins, settings       |
| [plugin-world](.claude/skills/plugin-world/SKILL.md)                       | working on blocks, terrains, worlds, structures, loot, mob goals, cutscenes or screens |
| [plugin-persistence](.claude/skills/plugin-persistence/SKILL.md)           | touching the store, saved data, migrations or anything a restart must keep             |
| [plugin-bots](.claude/skills/plugin-bots/SKILL.md)                         | working on fake players, or adding a player action or event                            |
| [lua-api](.claude/skills/lua-api/SKILL.md)                                 | adding or changing anything scripts can call                                           |
| [hot-reload](.claude/skills/hot-reload/SKILL.md)                           | touching the dev bridge or what happens when a file is saved                           |
| [entities-and-physics](.claude/skills/entities-and-physics/SKILL.md)       | working on centity runtime behaviour, hitboxes or physics                              |
| [menus-and-dialogs](.claude/skills/menus-and-dialogs/SKILL.md)             | working on menu windows, items or dialogs                                              |
| [resource-packs](.claude/skills/resource-packs/SKILL.md)                   | touching how resource packs are built, served or previewed                             |
| [editor-ui](.claude/skills/editor-ui/SKILL.md)                             | working in `apps/editor/src` (the hub: layers, store, registry, testing)               |
| [editor-workbench](.claude/skills/editor-workbench/SKILL.md)               | working on the workbench: docks, explorer, menus, widgets, palette                     |
| [editor-lua](.claude/skills/editor-lua/SKILL.md)                           | touching the editor's debugger client or lua-language-server                           |
| [editor-packages](.claude/skills/editor-packages/SKILL.md)                 | touching dependencies, package paths or templates in the editor                        |
| [editor-viewports](.claude/skills/editor-viewports/SKILL.md)               | touching viewports, timelines, block models or map previews                            |
| [editor-resource-editors](.claude/skills/editor-resource-editors/SKILL.md) | changing one resource editor (menu, recipe, terrain, cutscene…)                        |
| [tauri-backend](.claude/skills/tauri-backend/SKILL.md)                     | working in `apps/editor/src-tauri`                                                     |
| [run-netherforge](.claude/skills/run-netherforge/SKILL.md)                 | launching the editor and a dev server to check a change for real                       |
| [release](.claude/skills/release/SKILL.md)                                 | bumping the version, cutting a release, signing, the updater, the docs site            |

If a design decision took thought, write it in the relevant skill or a code
comment, not here.
