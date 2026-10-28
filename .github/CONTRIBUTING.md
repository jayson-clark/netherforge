# Contributing to NetherForge

Thanks for helping. This page gets you from a fresh clone to a passing test
run, and explains what we expect from a change.

## Setup

You need:

- **JDK 25** (Temurin is fine). Gradle can also download one for you.
- **Node 22+** and **pnpm 10** (`corepack enable` installs pnpm).
- **Rust** (stable, via [rustup](https://rustup.rs)) for the editor.
- The Tauri prerequisites for your OS:
  - **macOS:** Xcode Command Line Tools (`xcode-select --install`).
  - **Windows:** Microsoft C++ Build Tools and WebView2 (preinstalled on
    Windows 10 and later).
  - **Linux:** `libwebkit2gtk-4.1-dev`, `build-essential`, `libssl-dev`,
    `libayatana-appindicator3-dev`, `librsvg2-dev` (Debian/Ubuntu names).

Then:

```sh
pnpm install
pnpm build      # Kotlin format library and plugin jars, TypeScript packages
pnpm test
```

## Running things

```sh
pnpm dev                    # the editor, with hot reload of the UI
pnpm test:integration       # boots a headless Paper server with examples/showcase
```

To try the editor against real content, open `examples/showcase`. The
[run-netherforge](.claude/skills/run-netherforge/SKILL.md) guide covers starting a
dev server and joining it.

## Where things are

`AGENTS.md` has the repo map and the rules every change follows. The guides
in `.claude/skills/` go deeper on each area (the project format, the plugin
runtime, the Lua API, the editor, testing). They're written for people as
much as for coding agents, so read the one for the area you're changing.

The file format users write is specified in [`docs/format/`](docs/format).

## What a good change looks like

- **It has tests at the lowest layer that can catch its bug.** A validation
  rule gets an invalid fixture in `packages/format/testdata/invalid/`; a runtime
  behaviour gets a Lua test against the fake platform; a UI flow gets a
  Playwright test only if nothing lower can cover it. See
  [testing](.claude/skills/testing/SKILL.md).
- **`pnpm lint` and `pnpm test` pass.** CI runs them on macOS, Windows and
  Linux.
- **Generated files are regenerated, not edited.** Run `pnpm generate` after
  changing `packages/api/`.
- **A format change updates the spec** in `docs/format/` and the examples.
- **Golden updates are reviewed.** `UPDATE_GOLDEN=1 pnpm test` rewrites
  expectations; read the diff before committing it.

Keep pull requests focused, one change each, and describe what it changes for
users.

## Working with coding agents

`AGENTS.md` is the entry point for coding agents (and `CLAUDE.md` points
there). If you use one, it should follow the same skills you would.

## License

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).
