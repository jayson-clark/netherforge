import {
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { GAME_DATA_SCHEMA } from '@netherforge/format/constants'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { run } from './main.ts'
import { editorDataDir, hashPackage, listFiles } from './project.ts'

const example = path.resolve(import.meta.dirname, '../../../examples/showcase')

/** This test's folder (the project, the library beside it, the editor's data), removed after it. */
let tmp: string
let project: string
let library: string
let data: string
/** No game data unless a test writes some: the editor's cache stays out of it. */
let env: NodeJS.ProcessEnv

beforeEach(() => {
  tmp = mkdtempSync(path.join(os.tmpdir(), 'netherforge-cli-'))
  project = path.join(tmp, 'project')
  data = path.join(tmp, 'data')
  cpSync(example, project, {
    recursive: true,
    filter: (source) => path.relative(example, source) !== '.netherforge',
  })
  // The package examples/showcase depends on, where its netherforge.json finds it.
  library = path.join(tmp, 'library')
  cpSync(path.resolve(example, '../library'), library, { recursive: true })
  env = { NETHERFORGE_DATA_DIR: data }
})
// Most tests change the project, so each has its own copy, gone when it ends.
afterEach(() => rmSync(tmp, { recursive: true, force: true }))

const centity = () => path.join(project, 'centities/crate/centity.json')
const editJson = (
  file: string,
  recipe: (json: { nodes: Record<string, Record<string, unknown>> }) => void,
) => {
  const json = JSON.parse(readFileSync(file, 'utf8'))
  recipe(json)
  writeFileSync(file, JSON.stringify(json))
}

describe('check', () => {
  it('passes the example project and says ids went unchecked without game data', async () => {
    const outcome = await run(['check', project], env)
    expect(outcome.code).toBe(0)
    expect(outcome.out).toEqual(['No problems.'])
    expect(outcome.err.join('\n')).toContain("ids weren't checked")
  })

  it('reports problems as file:line:column: severity: message (at path) and fails', async () => {
    editJson(centity(), (json) => {
      json.nodes.box!.bogus = 1
    })
    const outcome = await run(['check', project], env)
    expect(outcome.code).toBe(1)
    expect(outcome.out[0]).toMatch(
      /^centities\/crate\/centity\.json:1:\d+: error \[parse\]: Unknown key "bogus" \(at \$\.nodes\.box\)$/,
    )
    expect(outcome.out.at(-1)).toBe('1 error, 0 warnings.')
  })

  it("checks ids against the editor's cached game data for the project's version", async () => {
    const file = path.join(data, 'minecraft/26.3/server/game-data.json')
    mkdirSync(path.dirname(file), { recursive: true })
    // A cache of another shape is as good as none.
    writeFileSync(file, JSON.stringify({ minecraft: '26.3', items: [] }))
    expect(JSON.parse((await run(['check', project, '--json'], env)).out[0]!).gameData).toBe(false)
    expect((await run(['check', project], env)).err.join('\n')).toContain("ids weren't checked")

    writeFileSync(file, JSON.stringify({ minecraft: '26.3', schema: GAME_DATA_SCHEMA }))
    const outcome = await run(['check', project, '--json'], env)
    const report = JSON.parse(outcome.out[0]!)
    expect(report.gameData).toBe(true)
    expect(report.problems).toContainEqual(
      expect.objectContaining({
        file: 'centities/crate/centity.json',
        path: '$.nodes.box.display.block',
        message: 'Minecraft 26.3 has no block "minecraft:oak_planks"',
      }),
    )
    expect(outcome.code).toBe(1)
  })

  it('finds the project from a folder inside it', async () => {
    expect((await run(['check', path.join(project, 'centities')], env)).code).toBe(0)
  })

  it('refuses a folder that is no project', async () => {
    const outcome = await run(['check', tmp], env)
    expect(outcome.code).toBe(2)
    expect(outcome.err[0]).toContain('No netherforge.json')
  })

  it("checks the packages a project depends on, and says when one isn't there", async () => {
    rmSync(library, { recursive: true })
    const outcome = await run(['check', project], env)
    expect(outcome.code).toBe(1)
    expect(outcome.out).toContain(
      "netherforge.json: error [package.missing]: There's no project at ../library (no netherforge.json there) (at $.dependencies.library)",
    )
  })
})

/** Changes the library, as working on it beside the project does: the lock no longer pins what resolves. */
const changeLibrary = () =>
  writeFileSync(path.join(library, 'modules/phrases/init.lua'), 'return { opening = "Hail" }\n')

describe('lock', () => {
  it('leaves a current lock alone and rewrites a stale one', async () => {
    expect(await run(['lock', project], env)).toMatchObject({
      code: 0,
      out: ['netherforge.lock: up to date'],
    })
    changeLibrary()
    expect((await run(['check', project], env)).out.join('\n')).toContain('[lock.stale]')
    const before = readFileSync(path.join(project, 'netherforge.lock'), 'utf8')
    expect(await run(['lock', project], env)).toMatchObject({
      code: 0,
      out: ['netherforge.lock: written'],
    })
    expect(readFileSync(path.join(project, 'netherforge.lock'), 'utf8')).not.toBe(before)
    expect(await run(['check', project], env)).toMatchObject({ code: 0, out: ['No problems.'] })
  })

  it('removes the lock of a project without dependencies', async () => {
    const manifest = path.join(project, 'netherforge.json')
    const json = JSON.parse(readFileSync(manifest, 'utf8')) as { dependencies?: unknown }
    delete json.dependencies
    writeFileSync(manifest, JSON.stringify(json))
    expect(await run(['lock', project], env)).toMatchObject({
      code: 0,
      out: ['netherforge.lock: removed (no dependencies)'],
    })
    expect(existsSync(path.join(project, 'netherforge.lock'))).toBe(false)
  })
})

describe('build', () => {
  it('writes the project and its packages as a bundle, each package what its hash covers', async () => {
    const out = path.join(project, '..', 'bundle')
    const outcome = await run(['build', project, '--out', out], env)
    expect(outcome).toMatchObject({ code: 0 })
    expect(outcome.out[0]).toMatch(/: showcase 0\.1\.0 with library\.$/)
    const bundle = JSON.parse(readFileSync(path.join(out, 'netherforge-bundle.json'), 'utf8'))
    const lock = JSON.parse(readFileSync(path.join(project, 'netherforge.lock'), 'utf8'))
    expect(bundle.project).toBe('showcase')
    expect(bundle.packages.library).toEqual({ version: '0.1.0', hash: lock.packages.library.hash })
    expect(bundle.packages.showcase.hash).toBe(hashPackage(project))
    expect(listFiles(path.join(out, 'library'))).toEqual(
      listFiles(library).filter((it) => !it.startsWith('.')),
    )
    // Only what a package is: no lock, agent notes or editor settings.
    const showcase = listFiles(path.join(out, 'showcase'))
    expect(showcase).toContain('modules/greeter/init.lua')
    expect(showcase).toContain('netherforge.json')
    for (const left of ['netherforge.lock', 'AGENTS.md', '.luarc.json', '.gitignore'])
      expect(showcase).not.toContain(left)

    // Built again over itself: a bundle is replaced, anything else is left alone.
    expect((await run(['build', project, '--out', out], env)).code).toBe(0)
    const other = path.join(project, '..', 'other')
    mkdirSync(other)
    writeFileSync(path.join(other, 'keep.txt'), 'mine')
    expect(await run(['build', project, '--out', other], env)).toMatchObject({ code: 1 })
    expect(readFileSync(path.join(other, 'keep.txt'), 'utf8')).toBe('mine')
  })

  it("bundles each default font, which the editor writes on this computer, without moving the lock's hashes", async () => {
    const font = '{ "minecraft": "26.3", "advances": { "65": 6 } }\n'
    for (const dir of [project, library]) {
      mkdirSync(path.join(dir, 'fonts'), { recursive: true })
      writeFileSync(path.join(dir, 'fonts/default.json'), font)
    }
    const out = path.join(project, '..', 'bundle')
    expect(await run(['build', project, '--out', out], env)).toMatchObject({ code: 0 })
    const bundle = JSON.parse(readFileSync(path.join(out, 'netherforge-bundle.json'), 'utf8'))
    const lock = JSON.parse(readFileSync(path.join(project, 'netherforge.lock'), 'utf8'))
    expect(bundle.packages.library.hash).toBe(lock.packages.library.hash)
    for (const name of ['showcase', 'library'])
      expect(readFileSync(path.join(out, name, 'fonts/default.json'), 'utf8')).toBe(font)
  })

  it('builds into build/<namespace>-<version> by default', async () => {
    expect((await run(['build', project], env)).code).toBe(0)
    expect(existsSync(path.join(project, 'build/showcase-0.1.0/netherforge-bundle.json'))).toBe(
      true,
    )
  })

  it("refuses a project whose lock doesn't pin what its dependencies resolve to", async () => {
    changeLibrary()
    const outcome = await run(['build', project], env)
    expect(outcome.code).toBe(1)
    expect(outcome.err.join('\n')).toContain('Run netherforge lock')
    expect(existsSync(path.join(project, 'build'))).toBe(false)
  })
})

describe('format', () => {
  it('leaves canonical files alone and rewrites the rest', async () => {
    expect(await run(['format', project, '--check'], env)).toMatchObject({ code: 0, out: [] })
    const before = readFileSync(centity(), 'utf8')
    writeFileSync(centity(), JSON.stringify(JSON.parse(before)))
    expect(await run(['format', project, '--check'], env)).toMatchObject({
      code: 1,
      out: ['centities/crate/centity.json: not canonical'],
    })
    expect((await run(['format', project], env)).out).toEqual([
      'centities/crate/centity.json: formatted',
    ])
    expect(readFileSync(centity(), 'utf8')).toBe(before)
  })

  it('sees single-file resources: a recipe is formatted like any other file', async () => {
    const recipe = path.join(project, 'recipes/ruby.json')
    const before = readFileSync(recipe, 'utf8')
    writeFileSync(recipe, JSON.stringify(JSON.parse(before)))
    expect(await run(['format', project, '--check'], env)).toMatchObject({
      code: 1,
      out: ['recipes/ruby.json: not canonical'],
    })
    expect((await run(['format', project], env)).out).toEqual(['recipes/ruby.json: formatted'])
    expect(readFileSync(recipe, 'utf8')).toBe(before)
  })

  it("reports a file that doesn't parse and leaves it as it was", async () => {
    writeFileSync(centity(), '{ nope')
    const outcome = await run(['format', project], env)
    expect(outcome.code).toBe(1)
    expect(outcome.err[0]).toMatch(/^centities\/crate\/centity\.json:1:\d+: error \[parse\]: /)
    expect(readFileSync(centity(), 'utf8')).toBe('{ nope')
  })
})

describe('project files', () => {
  it('skips .git, the top-level .netherforge and temp files, as the editor does', () => {
    for (const file of ['.git/HEAD', '.netherforge/schema/x.json', 'modules/.a.lua.nftmp-0f']) {
      mkdirSync(path.dirname(path.join(project, file)), { recursive: true })
      writeFileSync(path.join(project, file), '')
    }
    const files = listFiles(project)
    expect(files).toContain('netherforge.json')
    expect(files.some((it) => it.startsWith('.git/') || it.startsWith('.netherforge/'))).toBe(false)
    expect(files.some((it) => it.includes('nftmp'))).toBe(false)
  })

  it("checks maps and structures without reading them: a world's JSON is the game's", async () => {
    const files: Record<string, string | Buffer> = {
      'maps/arena/level.dat': Buffer.from([0x1f, 0x8b, 0, 0xff]),
      'maps/arena/players/stats/0.json': '{ not json',
      'structures/house.nbt': Buffer.from([0x1f, 0x8b, 1]),
    }
    for (const [file, contents] of Object.entries(files)) {
      mkdirSync(path.dirname(path.join(project, file)), { recursive: true })
      writeFileSync(path.join(project, file), contents)
    }
    const outcome = await run(['check', project], env)
    expect(outcome.out).toEqual(['No problems.'])
    expect(outcome.code).toBe(0)
  })

  it("holds a pack's replacement of a game texture to the game's size, from the PNG's header", async () => {
    const png = (width: number, height: number) => {
      const header = Buffer.alloc(24)
      Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).copy(header)
      header.write('IHDR', 12, 'latin1')
      header.writeUInt32BE(width, 16)
      header.writeUInt32BE(height, 20)
      return header
    }
    const files: Record<string, string | Buffer> = {
      'client/vanilla.json': JSON.stringify({
        minecraft: '26.3',
        sprites: ['hud/hotbar'],
        textures: { 'gui/container/inventory': [256, 256] },
      }),
      'resource_packs/skin/pack.json': JSON.stringify({
        hud: { replace: { 'hud/hotbar': 'gui/hotbar.png' } },
        textures: { 'gui/container/inventory': 'gui/inventory.png' },
      }),
      'resource_packs/skin/textures/gui/hotbar.png': png(182, 22),
      'resource_packs/skin/textures/gui/inventory.png': png(128, 128),
    }
    for (const [file, contents] of Object.entries(files)) {
      mkdirSync(path.dirname(path.join(project, file)), { recursive: true })
      writeFileSync(path.join(project, file), contents)
    }
    const wrong = await run(['check', project], env)
    expect(wrong.code).not.toBe(0)
    expect(wrong.out.join('\n')).toContain('[resource_pack.replace-size]')
    expect(wrong.out.join('\n')).toContain('128x128')
    // The right size passes.
    writeFileSync(
      path.join(project, 'resource_packs/skin/textures/gui/inventory.png'),
      png(256, 256),
    )
    expect((await run(['check', project], env)).out.join('\n')).not.toContain('replace-size')
  })

  it('reads each WebAssembly module and reports what it imports that the server lacks', async () => {
    // A module importing env.teleport and exporting go(), byte by byte.
    const module = Buffer.from([
      0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, 0x01, 0x04, 0x01, 0x60, 0x00, 0x00, 0x02,
      0x10, 0x01, 0x03, 0x65, 0x6e, 0x76, 0x08, 0x74, 0x65, 0x6c, 0x65, 0x70, 0x6f, 0x72, 0x74,
      0x00, 0x00, 0x03, 0x02, 0x01, 0x00, 0x07, 0x06, 0x01, 0x02, 0x67, 0x6f, 0x00, 0x01, 0x0a,
      0x04, 0x01, 0x02, 0x00, 0x0b,
    ])
    mkdirSync(path.join(project, 'wasm/engine'), { recursive: true })
    writeFileSync(path.join(project, 'wasm/engine/module.wasm'), module)
    const outcome = await run(['check', project], env)
    expect(outcome.code).not.toBe(0)
    expect(outcome.out.join('\n')).toContain('[wasm.import]')
    expect(outcome.out.join('\n')).toContain('env.teleport')
    // Bytes that aren't a module say so.
    writeFileSync(path.join(project, 'wasm/engine/module.wasm'), 'not a module')
    expect((await run(['check', project], env)).out.join('\n')).toContain('[wasm.invalid]')
  })

  it("reads a structure's generation file, which format owns, and reports what's wrong in it", async () => {
    const files: Record<string, string | Buffer> = {
      'structures/ruins.nbt': Buffer.from([0x1f, 0x8b, 1]),
      'structures/ruins.json': '{ "biomes": [] }',
    }
    for (const [file, contents] of Object.entries(files)) {
      mkdirSync(path.dirname(path.join(project, file)), { recursive: true })
      writeFileSync(path.join(project, file), contents)
    }
    const outcome = await run(['check', project], env)
    expect(outcome.out.join('\n')).toContain('structures/ruins.json')
    expect(outcome.out.join('\n')).toContain('structure.biomes')
    expect(outcome.code).toBe(1)
  })

  it("picks the editor's data folder the way the Rust side does", () => {
    expect(editorDataDir({ NETHERFORGE_DATA_DIR: '/x' }, 'linux', '/home/a')).toBe('/x')
    expect(editorDataDir({}, 'darwin', '/Users/a')).toBe(
      '/Users/a/Library/Application Support/NetherForge',
    )
    expect(editorDataDir({}, 'linux', '/home/a')).toBe('/home/a/.local/share/NetherForge')
    expect(editorDataDir({ XDG_DATA_HOME: '/xdg' }, 'linux', '/home/a')).toBe('/xdg/NetherForge')
    expect(
      editorDataDir({ LOCALAPPDATA: 'C:\\Users\\a\\AppData\\Local' }, 'win32', 'C:\\Users\\a'),
    ).toBe('C:\\Users\\a\\AppData\\Local\\NetherForge')
  })
})
