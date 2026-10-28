import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  documentKindOf,
  editorDataDir,
  findProjectRoot,
  gameDataFile,
  hashPackage,
  listFiles,
  packageCache,
  packageDir,
  readPackages,
  readProject,
} from './project.ts'

// How the CLI reads a project folder for format: each test builds a small one in a temp folder, and the
// editor's data folder comes from the environment and platform passed in, never the machine's.

let tmp: string
beforeEach(() => {
  tmp = mkdtempSync(path.join(os.tmpdir(), 'netherforge-cli-project-'))
})
afterEach(() => rmSync(tmp, { recursive: true, force: true }))

const manifest = (namespace: string, more = '') =>
  `{ "formatVersion": 1, "name": "P", "namespace": "${namespace}", "version": "1.0.0", "minecraft": "26.3"${more} }\n`

/** Writes [files] (project path → text) under [dir]. */
function write(dir: string, files: Record<string, string>): string {
  for (const [file, text] of Object.entries(files)) {
    mkdirSync(path.dirname(path.join(dir, file)), { recursive: true })
    writeFileSync(path.join(dir, file), text)
  }
  return dir
}

describe('listing a project', () => {
  it("lists every file as a sorted project path, leaving out git's, the editor's and half-written ones", () => {
    const root = write(tmp, {
      'netherforge.json': manifest('p'),
      'modules/b/init.lua': '',
      'modules/a/init.lua': '',
      '.git/HEAD': '',
      'modules/a/.git/HEAD': '',
      '.netherforge/schema/x.json': '',
      'modules/.netherforge/kept.lua': '',
      'modules/a/.init.lua.nftmp-0a1b': '',
    })
    expect(listFiles(root)).toEqual([
      'modules/.netherforge/kept.lua',
      'modules/a/init.lua',
      'modules/b/init.lua',
      'netherforge.json',
    ])
    expect(listFiles(path.join(tmp, 'nowhere'))).toEqual([])
  })

  it('reads the JSON and Lua format reads as text and hands it the rest as null', () => {
    const root = write(tmp, {
      'netherforge.json': manifest('p'),
      'netherforge.lock': '{}',
      'recipes/r.json': '{}',
      'modules/m/init.lua': 'return {}',
      'resource_packs/ui/textures/a.png': 'png',
      'structures/hut.nbt': 'nbt',
      'structures/hut.json': '{ "biomes": [] }',
      'maps/lobby/level.dat': 'dat',
      'maps/lobby/stats/x.json': '{ "the game": "its own" }',
      'datapacks/d/pack.mcmeta': '{ "pack": {} }',
    })
    expect(readProject(root)).toEqual({
      'datapacks/d/pack.mcmeta': '{ "pack": {} }',
      'maps/lobby/level.dat': null,
      // A world's own JSON is the game's, never read.
      'maps/lobby/stats/x.json': null,
      'modules/m/init.lua': 'return {}',
      'netherforge.json': manifest('p'),
      'netherforge.lock': '{}',
      'recipes/r.json': '{}',
      'resource_packs/ui/textures/a.png': null,
      // A structure's generation file is format's, in a binary kind's folder.
      'structures/hut.json': '{ "biomes": [] }',
      'structures/hut.nbt': null,
    })
  })

  it('knows which files are documents format writes', () => {
    expect(documentKindOf('netherforge.json')).toBe('netherforge')
    expect(documentKindOf('centities/crate/centity.json')).toBe('centity')
    expect(documentKindOf('centities/crate/main.lua')).toBeNull()
    expect(documentKindOf('notes.txt')).toBeNull()
  })

  it('finds the project a path is in', () => {
    const root = write(path.join(tmp, 'p'), { 'netherforge.json': manifest('p') })
    mkdirSync(path.join(root, 'modules/a'), { recursive: true })
    expect(findProjectRoot(path.join(root, 'modules/a'))).toBe(root)
    expect(findProjectRoot(root)).toBe(root)
    expect(findProjectRoot(tmp)).toBeNull()
  })
})

describe('packages', () => {
  it("hashes a package's content: the same files, the same hash; a changed one, another", () => {
    const a = write(path.join(tmp, 'a'), {
      'netherforge.json': manifest('lib'),
      'loot/x.json': '{}',
    })
    const b = write(path.join(tmp, 'b'), {
      'netherforge.json': manifest('lib'),
      'loot/x.json': '{}',
      '.git/HEAD': 'not content',
    })
    expect(hashPackage(a)).toMatch(/^sha256:[0-9a-f]{64}$/)
    expect(hashPackage(b)).toBe(hashPackage(a))
    write(b, { 'loot/x.json': '{ }' })
    expect(hashPackage(b)).not.toBe(hashPackage(a))
  })

  it("finds a folder package beside the project and a git one's checkout in the cache", () => {
    const cache = path.join(tmp, 'cache')
    expect(packageDir(path.join(tmp, 'app'), cache, '../lib')).toBe(path.join(tmp, 'lib'))
    const commit = 'a'.repeat(40)
    expect(packageDir(path.join(tmp, 'app'), cache, `git:${commit}`)).toBe(
      path.join(cache, 'git', 'checkouts', commit),
    )
    expect(packageCache({ NETHERFORGE_DATA_DIR: tmp })).toBe(path.join(tmp, 'packages'))
  })

  it('reads every folder package the project needs, its own dependencies too, and null where none is', () => {
    const app = write(path.join(tmp, 'app'), {
      'netherforge.json': manifest(
        'app',
        ', "dependencies": { "lib": { "path": "../lib" }, "gone": { "path": "../gone" } }',
      ),
    })
    write(path.join(tmp, 'lib'), {
      'netherforge.json': manifest('lib', ', "dependencies": { "deep": { "path": "../deep" } }'),
    })
    write(path.join(tmp, 'deep'), { 'netherforge.json': manifest('deep') })
    const read = readPackages(app, path.join(tmp, 'cache'))
    expect(Object.keys(read.folders).sort()).toEqual(['../deep', '../gone', '../lib'])
    expect(read.folders['../gone']).toBeNull()
    expect(read.folders['../lib']).toEqual({
      files: {
        'netherforge.json': manifest('lib', ', "dependencies": { "deep": { "path": "../deep" } }'),
      },
      hash: hashPackage(path.join(tmp, 'lib')),
    })
    expect(read.git).toEqual({})
  })
})

describe("the editor's data folder", () => {
  it('is NETHERFORGE_DATA_DIR when set, else the OS’s own place for app data', () => {
    expect(editorDataDir({ NETHERFORGE_DATA_DIR: '/d' }, 'linux', '/home/a')).toBe('/d')
    expect(editorDataDir({}, 'linux', '/home/a')).toBe('/home/a/.local/share/NetherForge')
    expect(editorDataDir({ XDG_DATA_HOME: '/x' }, 'linux', '/home/a')).toBe('/x/NetherForge')
    expect(editorDataDir({}, 'darwin', '/Users/a')).toBe(
      '/Users/a/Library/Application Support/NetherForge',
    )
    expect(
      editorDataDir({ LOCALAPPDATA: 'C:\\Users\\a\\AppData\\Local' }, 'win32', 'C:\\Users\\a'),
    ).toBe('C:\\Users\\a\\AppData\\Local\\NetherForge')
    expect(editorDataDir({}, 'win32', 'C:\\Users\\a')).toBe(
      'C:\\Users\\a\\AppData\\Local\\NetherForge',
    )
  })

  it("keeps each Minecraft version's game data export under it", () => {
    expect(gameDataFile('/d', '26.3')).toBe(
      path.join('/d', 'minecraft', '26.3', 'server', 'game-data.json'),
    )
  })
})
