/**
 * Runs lua-language-server over real scripts with the stubs as they're generated now, configured
 * the way a project is (`examples/showcase/.luarc.json`, with the stubs as its library), and expects
 * no diagnostics: the examples, plus `luals-probe/`, which touches events on every class, `Vec3`
 * maths both ways round, tasks, commands, saved data, worlds and blocks, entities and players,
 * inventories, boss bars and sidebars; and every `example` in the spec.
 *
 * Runs the LuaLS the editor ships (`node tools/luals.mjs` fetches it; `pnpm test` does that
 * first), or the one in `LUA_LANGUAGE_SERVER`, or one on the PATH. Without any, these are skipped
 * locally (saying why) and fail when `CI` is set.
 */
import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { afterAll, describe, expect, it } from 'vitest'
import { api } from '../../src/spec/index.ts'
import { terrainApi } from '../../src/terrain.ts'
import { luals, lualsSurfaces } from './luals.ts'
import { terrainStubs } from './terrain.ts'

const root = path.resolve(import.meta.dirname, '../../../..')
const example = path.join(root, 'examples/showcase')
const probe = path.join(import.meta.dirname, 'luals-probe')

function findBinary(): string | undefined {
  const fromEnv = process.env.LUA_LANGUAGE_SERVER
  if (fromEnv) return fromEnv
  const name = process.platform === 'win32' ? 'lua-language-server.exe' : 'lua-language-server'
  return [
    path.join(root, 'apps/editor/src-tauri/lua-language-server/bin', name),
    ...(process.env.PATH ?? '').split(path.delimiter).map((dir) => path.join(dir, name)),
  ].find((it) => existsSync(it))
}

/** Which kind of script an example on a class runs in, when it isn't a centity's. */
const SURFACE_OF: Record<string, string> = {
  Menu: 'menu',
  Slot: 'menu',
  Dialog: 'dialog',
  Button: 'dialog',
  ProjectItem: 'item',
  ProjectBlock: 'block',
  Screen: 'screen',
  Playback: 'screen',
  'nf.screens': 'screen',
}
const SURFACE_CLASS: Record<string, string> = {
  centity: 'Centity',
  menu: 'Menu',
  dialog: 'Dialog',
  item: 'ProjectItem',
  block: 'ProjectBlock',
  screen: 'Screen',
}

/**
 * The names examples use without defining them, as if from the code around them, with their
 * types. A name whose type depends on where it is (`event`) is `any`.
 */
const FREE_NAMES: Record<string, string> = {
  player: 'Player',
  owner: 'Player',
  other: 'Player',
  world: 'World',
  arena: 'World',
  border: 'WorldBorder',
  block: 'Block',
  door: 'Centity',
  crate: 'Node',
  ball: 'Node',
  balloon: 'Node',
  button: 'Node',
  node: 'Node',
  bot: 'Bot',
  zombie: 'Mob',
  pet: 'Mob',
  boss: 'Mob',
  bar: 'BossBar',
  red: 'Team',
  staff: 'Player',
  template: 'MenuTemplate',
  rng: 'Random',
  effect: 'Effect',
  stream: 'HttpStream',
  feed: 'HttpStream',
  socket: 'WebSocket',
  flow: 'OAuthFlow',
  response: 'WebResponse',
  instance: 'WasmInstance',
  engine: 'WasmProcess',
  replay_id: 'string',
  sequence: 'integer',
  v: 'Vec3',
  forward: 'Vec3',
  target: 'Vec3',
  direction: 'Vec3',
  eye: 'Vec3',
  here: 'Vec3',
  event: 'any',
  saved: 'any',
  balance: 'integer',
  coins: 'integer',
  kills: 'integer',
  health: 'number',
  time_left: 'number',
  round_length: 'number',
}

/** Every `example` in the spec, with where it is and the class (or shape or value) it's on. */
function specExamples(): { where: string; owner: string; example: string }[] {
  const out: { where: string; owner: string; example: string }[] = []
  const add = (owner: string, where: string, example: string | undefined) => {
    if (example) out.push({ where, owner, example })
  }
  for (const cls of [...api.classes, ...api.shapes]) {
    for (const fn of cls.functions) add(cls.name, `${cls.name}.${fn.name}`, fn.example)
    for (const event of cls.events ?? []) add(cls.name, `${cls.name} ${event.name}`, event.example)
  }
  for (const value of api.values ?? []) {
    for (const fn of value.functions) add(value.name, `${value.name}.${fn.name}`, fn.example)
    const library = value.library
    if (library) {
      add(value.name, `${library.name}()`, library.call.example)
      for (const fn of library.functions) add(value.name, `${library.name}.${fn.name}`, fn.example)
    }
  }
  return out
}

const binary = findBinary()
const MISSING =
  'there is no lua-language-server: run node tools/luals.mjs (pnpm test does), or set LUA_LANGUAGE_SERVER'
// Straight to stderr: vitest drops a skipped file's console output.
if (!binary && !process.env.CI)
  process.stderr.write(`Skipping the lua-language-server check: ${MISSING}.\n`)

// CI always has it (pnpm test fetches it first), so there a missing one is a failure, never a quiet skip.
describe.runIf(!binary && process.env.CI)('lua-language-server in CI', () => {
  it('is there to check the stubs', () => {
    throw new Error(`CI is set but ${MISSING}`)
  })
})

const work = mkdtempSync(path.join(tmpdir(), 'netherforge-luals-'))
afterAll(() => rmSync(work, { recursive: true, force: true }))

/**
 * The example's own `.luarc.json`, with the stubs generated from the spec now as its library
 * (`nf.lua`, the surfaces, and `terrain.lua` for terrain scripts, as the editor gives a project),
 * plus [extra] library files and settings.
 */
function config(extra: { library?: Record<string, string>; settings?: object } = {}): string {
  const library = path.join(work, `library-${Math.random()}`)
  mkdirSync(path.join(library, 'surfaces'), { recursive: true })
  writeFileSync(path.join(library, 'nf.lua'), luals(api))
  writeFileSync(path.join(library, 'terrain.lua'), terrainStubs(terrainApi))
  for (const [name, text] of lualsSurfaces(api))
    writeFileSync(path.join(library, 'surfaces', name), text)
  for (const [name, text] of Object.entries(extra.library ?? {}))
    writeFileSync(path.join(library, name), text)
  const settings = JSON.parse(readFileSync(path.join(example, '.luarc.json'), 'utf8'))
  settings['workspace.library'] = [library]
  Object.assign(settings, extra.settings)
  const file = path.join(work, `luarc-${Math.random()}.json`)
  writeFileSync(file, JSON.stringify(settings))
  return file
}

/**
 * How long one LuaLS run may take. A run is usually about 2 s, but several
 * times that on a loaded machine, so each test gets the same allowance
 * rather than vitest's 5 s default.
 */
const LUALS_TIMEOUT = 60_000

/** `file:line [code] message` for every diagnostic LuaLS reports in `folder`. */
function check(folder: string, configFile = config()): string[] {
  const out = path.join(work, `${path.basename(folder)}-${Math.random()}.json`)
  const result = spawnSync(
    binary!,
    [
      `--check=${folder}`,
      `--configpath=${configFile}`,
      '--checklevel=Hint',
      '--check_format=json',
      `--check_out_path=${out}`,
      `--logpath=${path.join(work, 'log')}`,
    ],
    { encoding: 'utf8', timeout: LUALS_TIMEOUT },
  )
  if (!existsSync(out))
    throw new Error(`lua-language-server wrote no report:\n${result.stdout}${result.stderr}`)
  const text = readFileSync(out, 'utf8').trim()
  const report: Record<
    string,
    { code: string; message: string; range: { start: { line: number } } }[]
  > = text ? JSON.parse(text) : {}
  return Object.entries(report).flatMap(([uri, diagnostics]) =>
    diagnostics.map(
      (d) =>
        `${path.relative(folder, new URL(uri).pathname)}:${d.range.start.line + 1} [${d.code}] ${d.message.split('\n')[0]}`,
    ),
  )
}

describe.skipIf(!binary)(
  'lua-language-server, with the generated stubs',
  { timeout: LUALS_TIMEOUT },
  () => {
    it('finds nothing wrong with examples/showcase', () => {
      expect(check(example)).toEqual([])
    })

    // The project templates (what the editor's "Add template" offers) and the showcase's library
    // are what users learn from: their scripts and their tests stay clean as the stubs change.
    it.each(['library', 'template_minigame', 'template_shop', 'template_rpg_mob'])(
      'finds nothing wrong with examples/%s',
      (name) => {
        expect(check(path.join(root, 'examples', name))).toEqual([])
      },
    )

    it('finds nothing wrong with a script using every class', () => {
      expect(check(probe)).toEqual([])
    })

    it("finds nothing wrong with the spec's examples", () => {
      // Each example is a fragment: it may use a `player`, a `world` or an `event` from code
      // around it. Those names are declared here, typed where the name says what it is, so the
      // calls the example makes into the API are still checked. Each runs in the kind of script
      // its class belongs to, so `this` is that script's handle.
      const folder = path.join(work, 'spec-examples')
      for (const { where, owner, example: text } of specExamples()) {
        // A dialog opened from a centity's node, say, runs in the centity's script.
        const surface = /\bthis:node\(/.test(text) ? 'centity' : (SURFACE_OF[owner] ?? 'centity')
        const header = /\bthis\b/.test(text)
          ? `local this = this --[[@as ${SURFACE_CLASS[surface]}]]\n`
          : ''
        const file = path.join(folder, `${surface}s`, where.replace(/[^\w]+/g, '_'), 'script.lua')
        mkdirSync(path.dirname(file), { recursive: true })
        writeFileSync(file, header + text + '\n')
      }
      const free = Object.entries(FREE_NAMES).flatMap(([name, type]) => [
        `---@type ${type}`,
        `${name} = nil`,
      ])
      const configFile = config({
        library: { 'free-names.lua': ['---@meta', ...free].join('\n') },
        // A fragment's own locals may be there only to show the call that makes them.
        settings: { 'diagnostics.disable': ['lowercase-global', 'unused-local'] },
      })
      expect(check(folder, configFile)).toEqual([])
    })

    it('still catches mistakes', () => {
      const folder = path.join(work, 'mistakes')
      mkdirSync(path.join(folder, 'centities/bad'), { recursive: true })
      writeFileSync(
        path.join(folder, 'centities/bad/script.lua'),
        [
          'local this = this --[[@as Centity]]',
          'this:on("click", function(event) print(event.playr) end)',
          'assert(this:node("root")):on("clik", function() end)',
          '---@type number',
          'local n = 2 * vec3(1, 2, 3)',
          'print(n, os.time())',
          'local p = nf.players.get("Notch")',
          'if p then p:set_target(p) end',
          'this:on("click", function() nf.wait(20) end)',
        ].join('\n'),
      )
      const found = check(folder).join('\n')
      expect(found).toContain('Undefined field `playr`')
      expect(found).toMatch(/:3 \[param-type-mismatch\]/)
      expect(found).toContain('Cannot assign `Vec3` to `number`')
      expect(found).toContain('Undefined global `os`')
      // A player is a Living, not a Mob: no mob's methods.
      expect(found).toMatch(/:8 \[undefined-field\] Undefined field `set_target`/)
      // A handler can't wait (`nf.wait` is `---@async`): only a task can.
      expect(found).toMatch(/:9 \[not-yieldable\]/)
    })
  },
)
