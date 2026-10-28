# Changelog

All notable changes to NetherForge are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and NetherForge uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). One version covers
the editor, the plugin jars, the file format library and the `netherforge`
command; see the [release guide](.claude/skills/release/SKILL.md) for how a
release is cut.

## [Unreleased]

## [0.1.0-rc.1] - 2026-10-28

The first public release, as a release candidate.

- **The editor** (macOS, Windows, Linux): every kind of project file has its
  own editor, from 3D centities, menus and dialogs to terrains, screens and
  resource packs. It has Lua editing and debugging through lua-language-server,
  and Problems and Tests panels.
- **Real Minecraft is the preview.** The editor runs a Paper dev server for the
  project and reloads each resource as you save it.
- **The Paper plugin** runs a project folder on a server with no build step,
  one jar per supported Minecraft version (the last 1.21.x and every 26.x).
  Its scripts are Lua 5.4, sandboxed, with an API generated from one spec.
- **Packages and workspaces**: depend on other projects through git, and work
  on several projects and server networks side by side.
- **The `netherforge` command** on npm (`npx @netherforge/cli`): set up, check,
  format, test and preview projects from a terminal, CI or a coding agent.
- **Coding agents**: the editor's MCP server, and `AGENTS.md` with docs in
  every project.
- **The project format**: one canonical JSON form with JSON Schemas, specified
  in `docs/format/` (`formatVersion` 1).
