---
name: release
description: Cutting a NetherForge release end to end - the single version (VERSION, tools/release.mjs and its lint check), CHANGELOG.md, the tag-triggered release workflow and what it attaches (plugin jars, the test runner, editor installers, latest.json, checksums), publishing the netherforge command to npm (@netherforge/cli, npm.yml, trusted publishing), code signing and notarization secrets per OS and how they degrade when missing, the Tauri updater keypair and what the editor needs for auto-update, verifying artifacts, and the docs site deploy. Read before bumping a version, touching .github/workflows/release.yml, npm.yml or docs.yml, or changing signing, the updater or the npm package.
---

# Releasing NetherForge

One version covers everything: the editor, the plugin jars and the format
library. A release is a `v<version>` tag; the release workflow first runs all
of CI on the tagged commit, then builds every artifact and attaches it to a
**draft** GitHub release, and a person publishes it. Publishing it publishes
the `netherforge` command to npm (`@netherforge/cli`).

## Where things are

| Path                            | What                                                                                             |
| ------------------------------- | ------------------------------------------------------------------------------------------------ |
| `VERSION`                       | The source of truth. Gradle reads it (`build.gradle.kts`) for the plugin jars.                   |
| `tools/release.mjs`             | Sets every version (`<version>`), checks they agree (`--check [tag]`), prints notes (`--notes`). |
| `CHANGELOG.md`                  | Keep a Changelog. A release needs a `## [<version>]` section.                                    |
| `.github/workflows/release.yml` | Tag → draft release with every artifact.                                                         |
| `.github/workflows/npm.yml`     | Release published → `@netherforge/cli` on npm.                                                   |
| `tools/npm-package.mjs`         | Stages the npm package from the built command: its own package.json, README, licence.            |
| `tools/npm-check.mjs`           | Tries the built command: `--version`, `check` on the showcase, `setup` on a fresh copy of it.    |
| `tools/release-signing.mjs`     | CI only: turns whichever signing secrets exist into env vars and a Tauri config overlay.         |
| `tools/luals.mjs`               | The pinned lua-language-server (version and sha256 per OS) the editor bundles; fetches one.      |
| `.github/workflows/docs.yml`    | PR → docs build; push to main → docs site on GitHub Pages (once Pages is set up).                |

## Version sync

`node tools/release.mjs 1.2.3` writes the version to:

- `VERSION`
- every `package.json`: the root's and each package in `pnpm-workspace.yaml`
  (`format`, `api`, `editor`, `docs`)
- `apps/editor/src-tauri/Cargo.toml` (`[package] version`) and the editor crate's
  entry in `Cargo.lock`, so `--locked` builds still work
- `tools/gradle/kotlin-js-store/package-lock.json` (Kotlin/JS's npm lockfile
  records the Gradle build's version; it's committed and a build fails if it
  drifts, see the testing skill's dependency locks)
- `apps/editor/src-tauri/tauri.conf.json` only if it has a literal `version`. It
  currently says `"version": "../package.json"`, which Tauri resolves to
  `apps/editor/package.json`; keep it that way.

`pnpm lint` runs `node tools/release.mjs --check`, so a version that drifts
fails CI. The release workflow runs `--check v1.2.3`, which also requires the
tag to match `VERSION` and `CHANGELOG.md` to have the entry. If you add a
file that carries a version (a new workspace package is picked up
automatically; anything else isn't), add it to `places` in `release.mjs`.

The plugin's `paper-plugin.yml` gets `${version}` from Gradle at build time.
There's one plugin jar per supported Minecraft version,
`NetherForge-<version>-paper-<minecraft>.jar` (see the minecraft-versions
skill).

## Cutting a release

1. **Changelog.** Move what's under `## [Unreleased]` into
   `## [1.2.3] - YYYY-MM-DD`, leaving an empty `## [Unreleased]` above it.
   Write it for users: what they can do now, what changed, what broke.
2. **Bump.** `node tools/release.mjs 1.2.3` (refuses without the changelog
   entry), then `pnpm lint && pnpm test`.
3. **Commit** (`Release 1.2.3`), merge to `main` through a PR as usual.
4. **Tag** the merged commit and push the tag:
   `git tag v1.2.3 && git push origin v1.2.3`.
5. **Watch** the Release workflow. Its first job is the whole CI workflow on
   the tagged commit; nothing is drafted or built unless it passes. Each
   editor job's summary says what was signed; the last job lists every asset.
6. **Verify** the draft (below), edit the notes if needed, and **Publish**.
   Only then does `releases/latest` (and so the updater and the docs'
   download links) point at it, and npm.yml publishes `@netherforge/cli`.

A prerelease is a tag with a suffix: `v1.2.3-rc.1` (with `VERSION` and a
changelog section to match). It's marked prerelease, which `releases/latest`
skips, so it reaches neither the download links nor the updater. Use one to
test signing before a real release; delete the draft and tag afterwards (a
draft is never published, so it never reaches npm; a published prerelease goes
to npm under the `next` dist-tag, never `latest`). Its
Windows MSI is versioned `1.2.3.1` (an MSI's version is four numbers; see
`tools/release-signing.mjs`), so end a prerelease's suffix with a number.

**Re-running** a failed job is safe: the workflow finds the existing draft
and adds to it (`--clobber` for our assets; tauri-action replaces its own).
It refuses to touch a release that's already published: fix forward with a
new patch version.

## What CI produces

| Job       | Runner         | Attaches                                                                                                                                                                                    |
| --------- | -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ci        | all            | nothing: `ci.yml` called as a reusable workflow on the tagged commit; `prepare` and so every later job needs it to pass                                                                     |
| prepare   | ubuntu         | the draft release, notes from `CHANGELOG.md`                                                                                                                                                |
| plugin    | ubuntu         | `NetherForge-<v>-paper-<mc>.jar` for every adapter, `NetherForge-<v>-velocity.jar`, `NetherForgeTest-<v>.jar` (the script test runner `netherforge test` launches, and downloads from here) |
| cli       | ubuntu         | nothing: builds the command, runs `tools/npm-check.mjs`, stages and packs the npm package, so one that won't publish fails the release                                                      |
| editor    | macos-latest   | `NetherForge_<v>_universal.dmg`, the `.app.tar.gz` update bundle (+ `.sig`)                                                                                                                 |
| editor    | windows-latest | `NetherForge_<v>_x64-setup.exe`, `NetherForge_<v>_x64_en-US.msi` (+ `.sig`)                                                                                                                 |
| editor    | ubuntu-22.04   | `.AppImage` (+ `.sig`), `.deb`, `.rpm`                                                                                                                                                      |
| editor    | all            | `latest.json` (merged across platforms), only when the updater key is set                                                                                                                   |
| checksums | ubuntu         | `SHA256SUMS.txt` over everything                                                                                                                                                            |

**Why CI runs again inside the release** rather than the workflow checking
the commit's status: a tag can be pushed before (or without) CI finishing on
that commit, and a status lookup would have to poll and trust whichever run
it finds. Calling `ci.yml` (`on: workflow_call`) checks exactly the tagged
tree, with no race and no branch-protection setup; the cost is one more CI
run per release. The workflow's token is read-only by default; only the jobs
that create or upload to the release get `contents: write`, and the CI call
gets `contents: read`. Actions are pinned to commit SHAs (testing skill).

Each editor bundle also carries lua-language-server for its OS, as the
resource folder `lua-language-server/` (`tauri.bundle.conf.json`): the editor
job runs `node tools/luals.mjs --target <darwin-universal|win32-x64|linux-x64>`
first, which downloads the pinned release from LuaLS's GitHub, refuses it
unless its sha256 matches, and (macOS) joins the two architectures with
`lipo`. `pnpm bundle` locally does the same for the machine's own OS.

The script test runner jar (`:plugin:test-runner`, `NetherForgeTest-<version>.jar`,
built by `assemble` beside the plugin jars) travels the same way: it's attached
to the release, checked for the release's version, handed to the editor jobs in
the `plugin-jars` artifact (put back in `apps/plugin/test-runner/build/libs/`),
bundled into the editor under `test-runner/` (`tauri.bundle.conf.json`), and the
editor copies it to `<data>/test-runner/` at startup for the `netherforge`
command. The command needs the jar of its own version: without the editor's
copy, `netherforge test` downloads it from `<releases>/v<version>/` (GitHub's,
or `NETHERFORGE_RELEASES`), checked against that release's `SHA256SUMS.txt`, into
the same folder. So the jar stays attached, under exactly that name.

The LuaLS stubs, the JSON Schemas and the command itself aren't attached: the
command is on npm, and `netherforge setup` writes the rest into a project, as
the editor does (`@netherforge/project-support`).

Each editor bundle contains the plugin jars
(`tauri.bundle.conf.json` globs `apps/plugin/paper-*/build/libs/NetherForge-*-paper-*.jar`)
and the proxy plugin (`apps/proxy/velocity/build/libs/NetherForge-*-velocity.jar`,
for dev networks; handed over in `plugin-jars` too and put back there);
the editor installs the one for the project's target into its dev servers. They're the plugin job's own jars, handed over as the
`plugin-jars` workflow artifact and put back in those folders, so an
installer carries exactly the jars attached to the release (the editor jobs
wait for the plugin job). The plugin job checks every jar carries the
release's version. Linux builds on 22.04 so the binaries run on older glibc.
There's no Linux ARM or Windows ARM build yet.

## npm: `@netherforge/cli`

The `netherforge` command is published to npm when a release is **published**
(`npm.yml`, `on: release: published`), not when it's tagged: a draft is
reviewed first, and npm keeps every version it's given. The job checks out the
tag, checks the versions, builds the command, runs `tools/npm-check.mjs`, stages
the package with `tools/npm-package.mjs` and publishes it with provenance:
`latest` for `1.2.3`, `next` for `1.2.3-rc.1`. A version npm already has is
skipped, so it's safe to run again; run it by hand (`workflow_dispatch`, the
tag) to publish a release whose npm job failed.

The published package is staged, not `apps/cli` itself: `package.json` names
`netherforge.mjs` as the `netherforge` bin and as the package's export (the
generator SDK), has no dependencies (everything is bundled into the one file),
and its `repository` is the repository the workflow runs in, which provenance
requires. The npm package's version is `VERSION`.

**Authentication** is npm's trusted publishing: npm trusts this repository's
`npm.yml` through OpenID Connect (`id-token: write`), with no token stored.
Setting it up:

1. The **first** publish needs a token, since trusted publishing is set on a
   package that exists. Create a granular access token on npmjs.com that can
   publish to the `@netherforge` scope, store it as the `NPM_TOKEN` secret, and
   publish a release (or run `npm.yml` by hand on one).
2. On npmjs.com, `@netherforge/cli` → **Settings** → **Trusted publishing**:
   GitHub Actions, this repository, workflow `npm.yml`.
3. Delete the `NPM_TOKEN` secret and revoke the token. With no `NPM_TOKEN`,
   `npm.yml` publishes through OIDC (npm 11.5.1 or newer, which it installs).

Moving the repository means updating the trusted
publisher, and the `repository` the package names follows by itself.

## Signing secrets

Set them in **Settings → Secrets and variables → Actions**. Every one is
optional: whatever is missing gives an unsigned (or ad-hoc signed) build and
a line in the job summary, never a failure. Partial sets count as missing
(a certificate without its password signs nothing).

### Updater (all OSes)

| Secret                               | What                                              |
| ------------------------------------ | ------------------------------------------------- |
| `TAURI_SIGNING_PRIVATE_KEY`          | The updater private key: the key file's contents. |
| `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` | Its password, if it has one.                      |

With the key, CI turns on `bundle.createUpdaterArtifacts`, signs the update
bundles (`.sig`) and tauri-action uploads `latest.json`. Without it there's
no `latest.json`, and installed editors simply see no update.

### macOS: Developer ID and notarization

| Secret                                                  | What                                                                                                                                                |
| ------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| `APPLE_CERTIFICATE`                                     | The "Developer ID Application" certificate with its private key, exported as `.p12`, base64 (`base64 -i cert.p12 \| pbcopy`).                       |
| `APPLE_CERTIFICATE_PASSWORD`                            | The `.p12` export password.                                                                                                                         |
| `APPLE_SIGNING_IDENTITY`                                | Optional: `Developer ID Application: Name (TEAMID)`. Tauri finds it in the certificate if unset.                                                    |
| `APPLE_ID`, `APPLE_PASSWORD`, `APPLE_TEAM_ID`           | Notarization with an Apple ID: the account email, an **app-specific password** (appleid.apple.com), the team id.                                    |
| `APPLE_API_KEY`, `APPLE_API_ISSUER`, `APPLE_API_KEY_P8` | Or notarization with an App Store Connect API key: key id, issuer id, and the `.p8` file's contents. Preferred over the Apple ID when both are set. |

Tauri signs the app and its sidecars but not executables among its
resources, and notarization refuses an unsigned one, so with a certificate
the workflow signs `lua-language-server` itself first (its own temporary
keychain, hardened runtime, timestamp: "Sign lua-language-server with the
Developer ID"). Without one, it stays ad hoc signed, as `tools/luals.mjs`
leaves it. On Windows only the app and installers are Authenticode-signed;
the LuaLS `.exe` is LuaLS's own build.

Without a certificate, the app is **ad-hoc signed** (`signingIdentity: "-"`)
and not notarized: users get the "unidentified developer" prompt and must use
**Open Anyway** (the install guide says how). Notarization needs the
certificate; credentials alone do nothing.

### Windows: Authenticode

| Secret / variable                    | What                                                                           |
| ------------------------------------ | ------------------------------------------------------------------------------ |
| `WINDOWS_CERTIFICATE`                | A code-signing certificate as `.pfx`, base64 (`certutil -encode` or `base64`). |
| `WINDOWS_CERTIFICATE_PASSWORD`       | Its password.                                                                  |
| `WINDOWS_TIMESTAMP_URL` (a variable) | Optional timestamp server; default `http://timestamp.digicert.com`.            |

The workflow imports the `.pfx` into the runner's user store and passes its
thumbprint as `bundle.windows.certificateThumbprint`. Certificates that live
in a cloud HSM (Azure Trusted Signing, most EV certificates now) can't be
exported as a `.pfx`; for those, set `bundle.windows.signCommand` in the
overlay instead (see Tauri's Windows signing guide). Unsigned builds work but
SmartScreen warns until the download gathers reputation.

### Linux

Nothing to sign beyond the updater signature. The `.deb`/`.rpm` aren't
signed.

## The updater keypair

Generate it once, offline, and keep it forever:

```sh
pnpm --filter @netherforge/editor tauri signer generate -w ~/.tauri/netherforge.key
```

- `~/.tauri/netherforge.key` (private) → the `TAURI_SIGNING_PRIVATE_KEY` secret
  (paste the file's contents), its password →
  `TAURI_SIGNING_PRIVATE_KEY_PASSWORD`. Back both up somewhere safe.
- `~/.tauri/netherforge.key.pub` (public) → `plugins.updater.pubkey` in
  `apps/editor/src-tauri/tauri.conf.json` (the file's contents, as one string).
  It's public; commit it. With the private key set and the pubkey empty,
  `tools/release-signing.mjs` fails the release rather than ship an app that
  can never verify an update.

**Losing the private key strands every installed copy**: they only accept
updates signed by it, so the only way forward is a manual reinstall of a
build with a new public key. Rotating is the same: ship a release carrying
the new pubkey signed with the old key, then switch.

## What the editor needs for auto-update

Both sides are in place (the editor side is described in the
tauri-backend and editor-ui skills). What it consists of, for reference:

> **Before the first release:** `plugins.updater.pubkey` in
> `apps/editor/src-tauri/tauri.conf.json` is an empty placeholder. Paste the
> public key (below) there. An empty key builds and runs fine (it's only
> used to verify a downloaded update), but every install would fail
> verification until it's set.

1. **Rust** (`apps/editor/src-tauri/Cargo.toml`), desktop-only dependencies:

   ```toml
   [target.'cfg(any(target_os = "macos", windows, target_os = "linux"))'.dependencies]
   tauri-plugin-updater = "2"
   tauri-plugin-process = "2"
   ```

   and register them in `lib.rs`'s builder:

   ```rust
   .plugin(tauri_plugin_updater::Builder::new().build())
   .plugin(tauri_plugin_process::init())
   ```

2. **Capabilities** (`capabilities/default.json`): add `"updater:default"`
   and `"process:allow-restart"`. (Plugin permissions, not app commands: no
   `build.rs` `COMMANDS` change.)

3. **Config** (`tauri.conf.json`), a top-level `plugins` block:

   ```json
   "plugins": {
     "updater": {
       "pubkey": "<contents of netherforge.key.pub>",
       "endpoints": ["https://github.com/jayson-clark/netherforge/releases/latest/download/latest.json"],
       "windows": { "installMode": "passive" }
     }
   }
   ```

   Don't add `bundle.createUpdaterArtifacts` here: CI sets it only when the
   private key is present, so local `pnpm bundle` and nightly builds keep
   working without the key.

4. **JS**: `@tauri-apps/plugin-updater` and `@tauri-apps/plugin-process`.
   Behind the `Backend` contract (`checkForUpdate()` → version + notes or
   null; `installUpdate(onProgress)`), with `MemoryBackend` reporting no
   update (`?update=1.2.3` in the browser pretends one):

   ```ts
   import { check } from '@tauri-apps/plugin-updater'
   import { relaunch } from '@tauri-apps/plugin-process'

   const update = await check() // null when up to date
   if (update) {
     await update.downloadAndInstall() // progress events available
     await relaunch()
   }
   ```

5. **UX**: check quietly on start (and from Settings → "Check for updates"),
   offer the update with its notes, never install without asking. **Stop the
   dev server before installing**: the Windows installer closes the app, and
   a Paper process left running would hold the project's world open.

`latest.json` lists the AppImage for Linux, so `.deb`/`.rpm` users update
through a new package. The editor doesn't hide the prompt there yet: an
install attempt from a `.deb`/`.rpm` fails with the plugin's message in the
banner.

## Verifying a draft

- **Checksums**: `sha256sum -c SHA256SUMS.txt` after downloading the assets.
- **Plugin jars**: one per supported Minecraft version (the
  minecraft-versions skill lists them); `unzip -p NetherForge-1.2.3-paper-26.3.jar paper-plugin.yml`
  shows `version: '1.2.3'` and `api-version: '26.3'`. Drop it on a Paper server with
  `project:` set to `examples/showcase` and `/nf spawn tower`.
- **Proxy plugin**: `NetherForge-1.2.3-velocity.jar` (a network's hub, `apps/proxy/velocity`; the editor doesn't
  bundle it). `unzip -p NetherForge-1.2.3-velocity.jar velocity-plugin.json` shows `"version": "1.2.3"`. On a Velocity
  proxy it writes `plugins/netherforge/hub.properties` and logs the hub listening; `/nfhub` lists the servers.
- **macOS**: `codesign -dv --verbose=4 NetherForge.app` (Authority = Developer
  ID, or `Signature=adhoc`), `spctl -a -vv NetherForge.app` (accepted,
  "Notarized Developer ID"), `xcrun stapler validate NetherForge.app`. Then open
  the `.dmg` on a Mac that has never seen the app.
- **Windows**: `Get-AuthenticodeSignature .\NetherForge_1.2.3_x64-setup.exe`
  (Status `Valid`), install, run.
- **Linux**: `chmod +x` the AppImage and run it; `dpkg -I` the `.deb`.
- **Updater**: `latest.json` has `version` 1.2.3 and a `platforms` entry for
  `darwin-aarch64`, `darwin-x86_64`, `windows-x86_64` and `linux-x86_64`,
  each with a `signature` and a URL that exists. After publishing, an
  installed older version should offer the update.
- **In the app**: open a script and misspell a function: it's underlined
  (the bundled lua-language-server runs; `<data>/luals/log/` says why if not).
- **In the app**: Create project → Start server → join → spawn. The bundled
  plugin jar must be the release's version (the console's plugin line).

## Docs site

`docs.yml` builds `docs/` with VitePress on every PR and push to `main` that
touches `docs/` (so a broken build fails), and deploys it to GitHub Pages from
`main` (or by hand). One-time setup: **Settings → Pages → Source: GitHub
Actions**; until then the build asks the API whether Pages is there
(`repos/<repo>/pages` answers 404), builds anyway, skips the deploy and says
so in a notice. The workflow passes the Pages
base path (`DOCS_BASE`) and the repository (`DOCS_REPO`; links written as
`https://github.com/jayson-clark/netherforge/...` in the Markdown are rewritten to
it), plus `DOCS_URL`, the absolute site URL that `llms.txt` links pages by.
`buildEnd` (`docs/.vitepress/llms.ts`) writes `llms.txt`, `llms-full.txt` and
each page's Markdown, in sidebar order. `pnpm docs:build` locally is the same
build; dead links fail it.

## Bumping lua-language-server

Change `VERSION` in `tools/luals.mjs` and every `sha256` (the release page
lists each asset's digest; `gh api repos/LuaLS/lua-language-server/releases/latest`
prints them), run `node tools/luals.mjs`, then `pnpm test` (it drives the
real server: the stubs' check and the editor's integration test) and try
the editor. A LuaLS change can shift what the stubs need (see lua-api).

## Gotchas

- Tauri treats a signing env var that's **set but empty** as "sign with
  this" and fails. That's why the secrets go through `release-signing.mjs`
  instead of straight into the bundle step's `env:`.
- `--config` can be given several times; later files win. The bundle config
  (plugin jars) comes first, the signing overlay second.
- The overlay path uses forward slashes on Windows because tauri-action splits
  `args` itself.
- tauri-action v1 fails if a release it creates should be a draft and isn't;
  we always pass `releaseId` of our own draft instead.
- `releases/latest` ignores drafts and prereleases. That's the updater's
  safety net: nothing reaches users until a person publishes.
