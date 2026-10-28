import { parseArgs } from 'node:util'
import { readFileSync, statSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { build, check, format, generate, lock, type Outcome } from './commands.ts'
import { findProjectRoot } from './project.ts'
import { preview } from './preview.ts'
import { gallery, render } from './render.ts'
import { setup, setupWorkspace } from './setup.ts'
import { findPlaceable, importSchematic, placeableAt, previewStructure } from './structures.ts'
import { fetchJar, findJar, test } from './test.ts'
import { eachProject, isWorkspace, readWorkspace, workspaceAround } from './workspace.ts'

declare const __NETHERFORGE_VERSION__: string

const HELP = `netherforge ${__NETHERFORGE_VERSION__}

Usage:
  netherforge setup  [dir]
      Write the project's .netherforge/ as the NetherForge editor does when it
      opens the project: the JSON Schemas its files' $schema point at,
      lua-language-server's library (the API's stubs, marked for what the
      project can't use, and the project's own names), the docs for coding
      agents, and this command (.netherforge/bin/netherforge.mjs). Adds a
      .luarc.json when the project has none. Run it on a fresh clone, and
      again after changing netherforge.json, its dependencies or its names.
  netherforge check  [dir] [--json] [--game-data <file>]
      Validate the whole project, as the editor's Problems panel does.
      Prints file:line: severity: message (at $.json.path), one per line.
      Exits 1 if there are errors. Ids are checked against the game data
      the editor's dev server exported, when it has.
  netherforge format [dir] [--check]
      Rewrite project JSON in canonical form, as the editor saves it.
      With --check, change nothing and exit 1 if a file isn't canonical.
  netherforge generate [dir]
      Run netherforge.json's "generate" commands in order, from the
      project's folder, stopping at the first that fails, then format the
      project. A generator writes the project with the SDK this file exports:
      import { project } from "../.netherforge/bin/netherforge.mjs".
  netherforge lock   [dir] [--update]
      Write netherforge.lock as the project's dependencies resolve now
      (the editor does this whenever they change). A git dependency stays
      at the commit the lock pins until its git or rev changes; --update
      resolves every one afresh (a branch's newest commit). Git packages
      are fetched with your git into the editor's package cache.
  netherforge build  [dir] [--out <folder>]
      Write a bundle: the project and the packages it depends on, for a
      production server to run (point the plugin's project setting at it).
      Default folder: build/<namespace>-<version> in the project. Refuses
      a project with errors or an out-of-date netherforge.lock.
  netherforge preview terrain/<id>.json --out <file>.png [--seed <n>]
                     [--x <n>] [--z <n>] [--size <cells>] [--step <blocks>]
                     [--scale <pixels>] [--slice x|z --at <n> [--width <n>] [--top <y>]]
                     [--min-y <n> --max-y <n> | --dimension-type <id>]
      Draw a terrain as the editor's preview does, from the same code:
      the top-down map (default), or with --slice x (along the x axis at z
      --at) or --slice z (along z at x --at) the ground through that line.
      --x and --z centre it (default 0, 0); the map is --size cells (96) of
      --step blocks (4), each --scale pixels (4; 3 for a slice); a slice is
      --width columns (128, at most 4096), drawn up to its highest block
      that isn't air, or up to --top (the highest y drawn). --seed is a
      whole number (1337). The world is --min-y (-64) up to --max-y (320,
      one above its highest block), or the
      heights of the project's dimension type --dimension-type (dimension_types/<id>.json).
      The structures its decorations place are read from structures/<name>.nbt,
      and a file with a script runs terrain/<id>.lua (and the modules it
      requires) as the server does. Prints the picture's size, what its
      colours are and what the script failed at. Problems in the file print
      as check prints them and exit 1.
  netherforge preview structures/<id>.nbt|builds/<id> --out <file>.png
                     [--view top|iso | --slice x|y|z --at <n>] [--scale <pixels>]
  netherforge preview [dir] --structure <id> --out <file>.png ...
      Draw a structure or a build as the editor's structure screens do:
      from above (default), isometric from the south-east (--view iso), or
      a slice through layer --at (counted from 0 at its smallest corner)
      along x, y or z. Each block is --scale pixels (4; 2 isometric). A
      build is read a part at a time. Prints what each colour is.
  netherforge import <file.schem|file.litematic> --as <id> [--build]
                     [--replace] [--game-data <file>]
      Import a WorldEdit or Litematica schematic into the project in this
      folder: structures/<id>.nbt when it fits in one (48 blocks a side),
      else (or with --build) builds/<id>/, a grid of parts scripts place
      with world:place_gradually. Block entities come with it; entities
      don't. Lists the blocks the game data doesn't know (an older
      version's, which the server renames as it reads them, or a mod's).
      --replace replaces what's already called <id>.
  netherforge render <item|block|centity file> --out <file>.png [--size <pixels>]
      Draw an item or a block as its inventory icon (64 pixels), or a
      centity in its base pose from the front, its right and above (256),
      as the editor draws them: the project's resource pack looks, and the
      game's own from the Minecraft client the editor imported.
  netherforge gallery [dir] [--out <folder>] [--size <pixels>]
      Draw every item, block and centity of the project into --out
      (default build/gallery), with an index.html showing them all.
  netherforge test   [dir|file] [--json] [--junit <file>] [--filter <text>]
                     [--game-data <file>]
      Run the project's *_test.lua files on a fake server, each test on a
      fresh one, and print every test with its verdict and where it failed.
      Needs Java (the one the editor downloads is found) and the test runner's
      jar: the editor puts it in its data folder, and this command downloads
      it from the release the first time (see NETHERFORGE_TEST_JAR).
      The project runs on the real game data of its Minecraft version, the
      dev server's export the editor caches (or --game-data <file>); without
      it the tests don't run.
      --json prints one JSON event per line, --junit also writes JUnit XML,
      --filter keeps the tests whose "file: name" contains the text. Giving a
      *_test.lua file runs only that file. Exits 1 if a test fails or errors,
      2 if the tests couldn't run.

[dir] is the project folder, or any folder inside it (default: here).
In a workspace's folder (one with netherforge-workspace.json), setup,
check, format, lock and test go through every project the workspace lists,
packages first, and exit 1 if any failed.
`

export async function run(argv: string[], env: NodeJS.ProcessEnv = process.env): Promise<Outcome> {
  let parsed
  try {
    parsed = parseArgs({
      args: attachNegatives(argv),
      allowPositionals: true,
      options: {
        json: { type: 'boolean' },
        check: { type: 'boolean' },
        'game-data': { type: 'string' },
        out: { type: 'string' },
        update: { type: 'boolean' },
        junit: { type: 'string' },
        filter: { type: 'string' },
        seed: { type: 'string' },
        x: { type: 'string' },
        z: { type: 'string' },
        size: { type: 'string' },
        step: { type: 'string' },
        scale: { type: 'string' },
        slice: { type: 'string' },
        at: { type: 'string' },
        width: { type: 'string' },
        top: { type: 'string' },
        'min-y': { type: 'string' },
        'max-y': { type: 'string' },
        'dimension-type': { type: 'string' },
        view: { type: 'string' },
        structure: { type: 'string' },
        as: { type: 'string' },
        build: { type: 'boolean' },
        replace: { type: 'boolean' },
        help: { type: 'boolean', short: 'h' },
        version: { type: 'boolean', short: 'v' },
      },
    })
  } catch (error) {
    return { out: [], err: [(error as Error).message, '', HELP], code: 2 }
  }
  const { values, positionals } = parsed
  if (values.version) return { out: [__NETHERFORGE_VERSION__], err: [], code: 0 }
  const [command, dir = '.', ...extra] = positionals
  if (values.help || !command || command === 'help') return { out: [HELP], err: [], code: 0 }
  if (extra.length > 0) return { out: [], err: [`Unexpected argument: ${extra[0]}`], code: 2 }
  // `netherforge test tests/shop_test.lua` runs that file's tests, from the project it's in.
  const testFile =
    command === 'test' && dir.endsWith('_test.lua') && isFile(dir) ? path.resolve(dir) : null
  // `netherforge preview terrain/hills.json` draws that file, from the project it's in; so does render.
  // `--structure <id>` names what to draw instead, and the folder is the project's.
  const previewFile =
    (command === 'preview' && values.structure === undefined) || command === 'render'
      ? path.resolve(dir)
      : null
  const inWorkspace = isWorkspace(path.resolve(dir)) && findProjectRoot(dir) !== path.resolve(dir)
  if (command === 'setup' && inWorkspace) {
    const workspace = readWorkspace(path.resolve(dir))
    setupWorkspace(workspace.root)
    const out: string[] = []
    const err: string[] = []
    let code = 0
    for (const project of workspace.projects) {
      const outcome = await setup(
        path.join(workspace.root, project),
        { version: __NETHERFORGE_VERSION__, cli: ownScript(), servers: workspace.network },
        env,
      )
      out.push('', `▶ ${project}`, ...outcome.out)
      err.push(...outcome.err)
      if (outcome.code !== 0) code = outcome.code
    }
    return { out, err, code }
  }
  // The test runner's jar for this version, downloaded from its release the first time it's needed.
  if (command === 'test' && !findJar(__NETHERFORGE_VERSION__, env)) {
    const fetched = await fetchJar(__NETHERFORGE_VERSION__, env, (line) =>
      process.stderr.write(`${line}\n`),
    )
    if ('err' in fetched) return { out: [], err: [fetched.err], code: 2 }
  }
  // In a workspace's folder (not a project's), the commands that check a project go through every one.
  const across = ['check', 'format', 'lock', 'test']
  if (across.includes(command) && !testFile && inWorkspace) {
    const workspace = readWorkspace(path.resolve(dir))
    const run = (root: string): Outcome => {
      switch (command) {
        case 'check':
          return check(
            root,
            {
              json: values.json,
              gameData: values['game-data'],
              servers: workspace.servers ?? undefined,
            },
            env,
          )
        case 'format':
          return format(root, { check: values.check })
        case 'lock':
          return lock(root, { update: values.update }, env)
        default:
          return test(
            root,
            {
              json: values.json,
              junit: values.junit,
              gameData: values['game-data'],
              filter: values.filter,
            },
            __NETHERFORGE_VERSION__,
            env,
          )
      }
    }
    if (command === 'check' && values.json) {
      const projects: Record<string, unknown> = {}
      let code = workspace.problems.some((it) => it.severity === 'error') ? 1 : 0
      for (const project of workspace.projects) {
        const outcome = run(path.join(workspace.root, project))
        projects[project] = JSON.parse(outcome.out.join('\n'))
        if (outcome.code !== 0) code = 1
      }
      const report = { workspace: workspace.problems, projects }
      return { out: [JSON.stringify(report, null, 2)], err: [], code }
    }
    return eachProject(workspace, run, command === 'test' ? (line) => console.log(line) : undefined)
  }
  // `netherforge import city.litematic` imports into the project this folder is in.
  const root = findProjectRoot(
    testFile
      ? path.dirname(testFile)
      : previewFile
        ? path.dirname(previewFile)
        : command === 'import'
          ? '.'
          : dir,
  )
  if (!root)
    return { out: [], err: [`No netherforge.json in ${dir} or any folder above it.`], code: 2 }
  switch (command) {
    case 'setup': {
      // A member of a workspace: its network's servers are names too, and the workspace's schema is written.
      const workspace = workspaceAround(root)
      if (workspace) setupWorkspace(workspace.root)
      return setup(
        root,
        { version: __NETHERFORGE_VERSION__, cli: ownScript(), servers: workspace?.network },
        env,
      )
    }
    case 'check':
      return check(root, { json: values.json, gameData: values['game-data'] }, env)
    case 'format':
      return format(root, { check: values.check })
    case 'generate':
      return generate(root)
    case 'render':
    case 'gallery': {
      const size = values.size as string | undefined
      if (size !== undefined && !/^\d+$/.test(size)) {
        return { out: [], err: [`--size is a whole number of pixels, not "${size}".`], code: 2 }
      }
      const options = { out: values.out, size: size === undefined ? undefined : Number(size) }
      return command === 'render'
        ? render(root, previewFile!, options, env)
        : gallery(root, options, env)
    }
    case 'lock':
      return lock(root, { update: values.update }, env)
    case 'build':
      return build(root, { out: values.out }, env)
    case 'preview': {
      const numbers: Record<string, number | undefined> = {}
      for (const key of [
        'x',
        'z',
        'size',
        'step',
        'scale',
        'at',
        'width',
        'top',
        'min-y',
        'max-y',
      ]) {
        const text = values[key as keyof typeof values] as string | undefined
        if (text === undefined) continue
        // Whole numbers only: Number('') and Number('1e2') would pass for one.
        if (!/^-?\d+$/.test(text)) {
          return { out: [], err: [`--${key} is a whole number, not "${text}".`], code: 2 }
        }
        numbers[key] = Number(text)
      }
      const named = values.structure
      const placeable =
        named !== undefined
          ? findPlaceable(root, named)
          : placeableAt(path.relative(root, previewFile!).split(path.sep).join('/'))
      if (named !== undefined && !placeable) {
        return {
          out: [],
          err: [
            `There's no structure or build "${named}" in the project (structures/${named}.nbt, builds/${named}/).`,
          ],
          code: 2,
        }
      }
      if (placeable) {
        return previewStructure(root, placeable, {
          view: values.view,
          slice: values.slice,
          at: numbers.at,
          scale: numbers.scale,
          out: values.out,
        })
      }
      return preview(root, previewFile!, {
        seed: values.seed,
        x: numbers.x,
        z: numbers.z,
        size: numbers.size,
        step: numbers.step,
        scale: numbers.scale,
        slice: values.slice,
        at: numbers.at,
        width: numbers.width,
        top: numbers.top,
        minY: numbers['min-y'],
        maxY: numbers['max-y'],
        dimensionType: values['dimension-type'],
        out: values.out,
      })
    }
    case 'import':
      return importSchematic(
        root,
        path.resolve(dir),
        {
          as: values.as,
          build: values.build,
          replace: values.replace,
          gameData: values['game-data'],
        },
        env,
      )
    case 'test':
      return test(
        root,
        {
          json: values.json,
          junit: values.junit,
          gameData: values['game-data'],
          filter:
            values.filter ??
            (testFile ? path.relative(root, testFile).split(path.sep).join('/') : undefined),
        },
        __NETHERFORGE_VERSION__,
        env,
      )
    default:
      return { out: [], err: [`Unknown command: ${command}`, '', HELP], code: 2 }
  }
}

/**
 * `--z -500` as `--z=-500`: parseArgs takes a value that starts with `-` for the next option, and coordinates
 * are often negative.
 */
function attachNegatives(argv: string[]): string[] {
  const out: string[] = []
  for (const arg of argv) {
    const last = out.at(-1)
    if (last !== undefined && /^--[\w-]+$/.test(last) && /^-\d+$/.test(arg)) {
      out[out.length - 1] = `${last}=${arg}`
    } else {
      out.push(arg)
    }
  }
  return out
}

/** This command's own text, when it runs as the built `netherforge.mjs`: what setup writes into `.netherforge/bin/`. */
function ownScript(): string | null {
  const file = fileURLToPath(import.meta.url)
  return path.basename(file) === 'netherforge.mjs' ? readFileSync(file, 'utf8') : null
}

function isFile(file: string): boolean {
  try {
    return statSync(file).isFile()
  } catch {
    return false
  }
}
