# Contributing

NetherForge is open source under the Apache License 2.0, and contributions are
welcome: bug reports, fixes, features, documentation, and support for new
Minecraft versions.

Everything is in one repository on [GitHub](https://github.com/jayson-clark/netherforge):

| Folder      | What it is                                                                                                |
| ----------- | --------------------------------------------------------------------------------------------------------- |
| `apps/`     | What we ship: the desktop editor (Tauri 2) and the Paper plugin (a runtime plus one adapter per version). |
| `packages/` | Shared libraries: `format` (the file format, Kotlin Multiplatform) and `api` (the Lua API spec).          |
| `docs/`     | This site, including the [file-format spec](format/index.md) and the [API reference](reference/index.md). |
| `examples/` | Sample projects, which are also the test fixtures.                                                        |
| `tools/`    | Build, test, lint and release scripts, and the Gradle build.                                              |

## Where to start

- [`CONTRIBUTING.md`](https://github.com/jayson-clark/netherforge/blob/main/.github/CONTRIBUTING.md)
  covers setup on each OS (JDK 25, Node 22 and pnpm, Rust, the Tauri
  prerequisites), building, testing, and what a good change looks like.
- [`AGENTS.md`](https://github.com/jayson-clark/netherforge/blob/main/AGENTS.md)
  has the repository map and the rules every change follows. It's written for
  coding agents and people alike.
- The [guides in `.claude/skills/`](https://github.com/jayson-clark/netherforge/tree/main/.claude/skills)
  go deep on each area: the project format, the plugin runtime, the Lua API,
  hot reload, the editor UI, the Tauri backend, testing, Minecraft versions,
  releases. Read the one for the area you're changing.

```sh
pnpm install
pnpm build
pnpm test
pnpm lint
```

## Working on these docs

```sh
pnpm docs:dev      # live preview at http://localhost:5173
pnpm docs:build    # what CI runs; fails on dead links
```

- **Write for people using NetherForge**, not working on it. Say what
  something does and what it means for a project, not how the engine does it:
  no class names, threads, caches or protocols. That belongs in the skills
  and code comments.
- **Guides** are in `docs/guide/`, listed by section in
  `docs/.vitepress/config.ts`. Describe behaviour that exists, and check it
  against the code or the spec.
- **The file format** in `docs/format/` is the specification the format
  library implements. Change it in the same pull request as the format.
  `pnpm test` checks the pages against the checker (`docs/tests/`), so a page
  can't drift from the format:
  - every `json` block is a **whole file** and has to validate as its kind.
    Its `"$schema"` line names the kind; a whole file without one says so,
    ` ```json kind=bundle `.
  - a **snippet** (one key, one entry of a map) is marked ` ```json partial `
    and isn't checked.
  - every key the JSON Schema of a kind has must appear on that kind's page
    (in backticks, or as a key in an example). Add a key to the format and
    this fails until the page says what it does. A kind whose page is
    shared (item stacks, a project's manifest) is listed in the test's `PAGES`.
- **The Lua API reference** in `docs/reference/` is generated from
  `packages/api/src/spec/` by `pnpm generate`. Don't edit it here: change the spec and
  regenerate. Which section a page is in, and how a class's methods are
  grouped on its page, is `packages/api/scripts/emit/layout.ts`;
  `pnpm generate` fails, saying what to add there, when something new isn't
  placed.
