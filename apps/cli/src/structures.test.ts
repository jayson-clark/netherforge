import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { decode } from 'fast-png'
import { afterAll, beforeEach, describe, expect, it } from 'vitest'
import { Int16, Int32, TAG, TAG_TYPE, type CompoundTag, type Tag } from 'nbtify'
import { EMPTY_BLOCKS, GAME_DATA_SCHEMA } from '@netherforge/format/constants'
import { nbtRoot, readNbt, writeNbt } from '@netherforge/structures/nbt'
import { parseStructure } from '@netherforge/structures/structure'
import { run } from './main.ts'
import { importSchematic } from './structures.ts'

const roots: string[] = []
let project: string

const MANIFEST = JSON.stringify({
  formatVersion: 1,
  name: 'Test',
  namespace: 'test',
  version: '1.0.0',
  minecraft: '26.3',
})

beforeEach(() => {
  project = mkdtempSync(path.join(os.tmpdir(), 'netherforge-structures-'))
  roots.push(project)
  writeFileSync(path.join(project, 'netherforge.json'), MANIFEST)
})
afterAll(() => roots.forEach((it) => rmSync(it, { recursive: true, force: true })))

/** Air, as format names what's empty: what a picture leaves out. */
const AIR = EMPTY_BLOCKS[0]!

const list = (element: TAG, values: Tag[]): Tag =>
  Object.defineProperty([...values], TAG_TYPE, { value: element, enumerable: false })

/** A Sponge schematic [width] × 2 × 1: stone below, a chest at x 0 on top, air elsewhere, and a pig. */
async function schematic(width: number): Promise<string> {
  const data: number[] = []
  for (let y = 0; y < 2; y += 1)
    for (let x = 0; x < width; x += 1) data.push(y === 0 ? 1 : x === 0 ? 2 : 0)
  const root: CompoundTag = {
    Version: new Int32(2),
    DataVersion: new Int32(3955),
    Width: new Int16(width),
    Height: new Int16(2),
    Length: new Int16(1),
    Palette: {
      [AIR]: new Int32(0),
      'test:stone': new Int32(1),
      'test:chest[facing=north]': new Int32(2),
    },
    BlockData: Int8Array.from(data),
    BlockEntities: list(TAG.COMPOUND, [
      { Pos: Int32Array.from([0, 1, 0]), Id: 'test:chest', Items: 'some' },
    ]),
    Entities: list(TAG.COMPOUND, [{ Id: 'test:pig' }]),
  }
  const file = path.join(project, `in-${width}.schem`)
  writeFileSync(file, await writeNbt(nbtRoot(root, 'Schematic')))
  return file
}

/** No game data anywhere: the import says it didn't check. */
const env = () => ({ NETHERFORGE_DATA_DIR: path.join(project, 'no-editor') })

describe('import', () => {
  it('a schematic past 48 blocks becomes a build, a part per 48', async () => {
    const outcome = await importSchematic(project, await schematic(50), { as: 'city' }, env())
    expect(outcome.code).toBe(0)
    expect(outcome.out[0]).toBe(
      'builds/city/: 50 × 2 × 1 blocks in 2 parts, from a WorldEdit schematic saved in data version 3955',
    )
    expect(outcome.out).toContain('1 block entities (chests, signs…) kept with their blocks')
    expect(outcome.out).toContain('1 entities left out: an import carries blocks only')
    expect(outcome.out.at(-1)).toMatch(/^No game data/)
    const first = parseStructure(
      await readNbt(readFileSync(path.join(project, 'builds/city/0_0_0.nbt'))),
    )
    expect(first.size).toEqual([48, 2, 1])
    expect(first.blockEntities).toBe(1)
    expect(
      parseStructure(await readNbt(readFileSync(path.join(project, 'builds/city/1_0_0.nbt')))).size,
    ).toEqual([2, 2, 1])
  })

  it('a small one is a structure, unless asked for a build', async () => {
    expect((await importSchematic(project, await schematic(3), { as: 'hut' }, env())).code).toBe(0)
    expect(existsSync(path.join(project, 'structures/hut.nbt'))).toBe(true)
    expect(
      (await importSchematic(project, await schematic(3), { as: 'shed', build: true }, env())).code,
    ).toBe(0)
    expect(existsSync(path.join(project, 'builds/shed/0_0_0.nbt'))).toBe(true)
  })

  it("won't take an id that's taken, unless told to replace it", async () => {
    const file = await schematic(3)
    await importSchematic(project, file, { as: 'hut' }, env())
    const again = await importSchematic(project, file, { as: 'hut', build: true }, env())
    expect(again.err).toEqual([
      'structures/hut.nbt is already called "hut": give another --as, or --replace to replace it.',
    ])
    expect(
      (await importSchematic(project, file, { as: 'hut', build: true, replace: true }, env())).code,
    ).toBe(0)
    expect(existsSync(path.join(project, 'structures/hut.nbt'))).toBe(false)
    expect(existsSync(path.join(project, 'builds/hut/0_0_0.nbt'))).toBe(true)
  })

  it('names the blocks the game data lacks', async () => {
    const data = path.join(project, 'game.json')
    writeFileSync(
      data,
      JSON.stringify({
        minecraft: '26.3',
        schema: GAME_DATA_SCHEMA,
        blocks: { [AIR]: {}, 'test:stone': {} },
      }),
    )
    const outcome = await importSchematic(
      project,
      await schematic(3),
      { as: 'hut', gameData: data },
      env(),
    )
    expect(outcome.out.slice(-2)).toEqual([
      "1 kind of block the game doesn't have (an older version's, which the server renames as it reads the parts, or a mod's, which it leaves as air):",
      '  test:chest (1)',
    ])
  })

  it("says what's wrong with what it's given", async () => {
    expect((await importSchematic(project, 'nope.schem', { as: 'x' })).err).toEqual([
      'nope.schem: no such file.',
    ])
    expect((await importSchematic(project, 'nope.schem', {})).err).toEqual([
      'Say what to call it: --as <id>.',
    ])
    expect((await importSchematic(project, 'nope.schem', { as: 'Bad' })).err[0]).toMatch(
      /can't be an id/,
    )
    const text = path.join(project, 'notes.schem')
    writeFileSync(text, 'not nbt')
    expect((await importSchematic(project, text, { as: 'x' })).err[0]).toMatch(
      /^.*notes\.schem can't be imported/,
    )
  })
})

describe('preview: structures and builds', () => {
  const picture = (file: string) => decode(readFileSync(file))

  it('draws a build from above, its parts side by side', async () => {
    await importSchematic(project, await schematic(50), { as: 'city' }, env())
    const out = path.join(project, 'top.png')
    const outcome = await run(['preview', path.join(project, 'builds/city'), '--out', out])
    expect(outcome.err).toEqual([])
    expect(outcome.out.slice(0, 2)).toEqual([
      `${out}: 200x4`,
      'builds/city: 50 × 2 × 1 blocks, from above; empty blocks are transparent',
    ])
    expect(outcome.out.slice(2).map((it) => it.replace(/^#\w+ /, ''))).toEqual([
      'test:stone (49)',
      'test:chest (1)',
    ])
    const png = picture(out)
    expect(png.data[(0 * png.width + 199) * 4 + 3]).toBe(255)
  })

  it('finds one by --structure, and slices it or draws it isometric', async () => {
    await importSchematic(project, await schematic(3), { as: 'hut' }, env())
    const out = path.join(project, 'slice.png')
    const sliced = await run([
      'preview',
      project,
      '--structure',
      'hut',
      '--slice',
      'y',
      '--at',
      '1',
      '--scale',
      '1',
      '--out',
      out,
    ])
    expect(sliced.out[0]).toBe(`${out}: 3x1`)
    expect(sliced.out.slice(2).map((it) => it.replace(/^#\w+ /, ''))).toEqual(['test:chest (1)'])
    const iso = await run([
      'preview',
      path.join(project, 'structures/hut.nbt'),
      '--view',
      'iso',
      '--scale',
      '1',
      '--out',
      out,
    ])
    expect(iso.out[0]).toBe(`${out}: 8x8`)
    expect((await run(['preview', project, '--structure', 'nope', '--out', out])).err[0]).toMatch(
      /no structure or build "nope"/,
    )
    expect(
      (
        await run([
          'preview',
          project,
          '--structure',
          'hut',
          '--slice',
          'y',
          '--at',
          '9',
          '--out',
          out,
        ])
      ).err[0],
    ).toMatch(/0 to 1, not 9/)
    expect(
      (await run(['preview', project, '--structure', 'hut', '--view', 'side', '--out', out]))
        .err[0],
    ).toBe('--view is top or iso, not "side".')
  })

  it("a missing part is a file that can't be read", async () => {
    await importSchematic(project, await schematic(50), { as: 'city' }, env())
    rmSync(path.join(project, 'builds/city/0_0_0.nbt'))
    mkdirSync(path.join(project, 'builds/city'), { recursive: true })
    writeFileSync(path.join(project, 'builds/city/0_0_0.nbt'), 'broken')
    const outcome = await run([
      'preview',
      path.join(project, 'builds/city'),
      '--out',
      path.join(project, 'x.png'),
    ])
    expect(outcome.code).toBe(1)
    expect(outcome.err[0]).toMatch(/^builds\/city\/0_0_0\.nbt can't be read as a structure/)
  })
})
