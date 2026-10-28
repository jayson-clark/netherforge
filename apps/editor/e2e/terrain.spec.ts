import { expect } from '@playwright/test'
import { canonicalize } from '@netherforge/format'
import {
  files,
  openExample,
  openResource,
  outside,
  save,
  showPanel,
  test,
  showKind,
} from './helpers'

const HILLS = 'terrain/ruby_hills.json'

const canonical = (path: string, text: string) =>
  JSON.parse(canonicalize('terrain', path, text)).text as string

/** What the map's readout says for a cell under the pointer, off the middle (simplex noise is 0 at the origin, whatever the seed). */
async function groundAtCell(page: import('@playwright/test').Page) {
  const map = page.getByTestId('terrain-map')
  const box = (await map.boundingBox())!
  await page.mouse.move(box.x + box.width * 0.3, box.y + box.height * 0.6)
  const readout = page.getByTestId('terrain-map-readout')
  await expect(readout).toContainText('ground at')
  const text = (await readout.textContent()) ?? ''
  return Number(/ground at (-?\d+)/.exec(text)![1])
}

test('draws the generator it edits: a map and a slice from format, changing as the fields do', async ({
  page,
}) => {
  const errors = await openExample(page)
  await openResource(page, 'ruby_hills', 'Terrain')
  await expect(page.getByTestId('terrain-map')).toBeVisible()
  await expect(page.getByTestId('terrain-slice')).toBeVisible()
  // The biome areas and the blocks it makes are named: the ore of the project's own block among them.
  const areas = page.getByRole('list', { name: 'Biome areas' })
  await expect(areas.getByText('snowy')).toBeVisible()
  await expect(areas.getByText('minecraft:snowy_plains')).toBeVisible()
  await expect(
    page.getByRole('list', { name: 'Blocks' }).getByText('minecraft:grass_block'),
  ).toBeVisible()

  // Its decorations are marked on the map: the flowers, and the trees of the project's structure (read from its .nbt).
  const decorations = page.getByRole('list', { name: 'Decorations' })
  await expect(decorations.getByText('flowers')).toBeVisible()
  await expect(decorations.getByText('oaks')).toBeVisible()

  // Raising the base height raises the ground the preview draws: the same seed, the same column, 30 blocks up.
  const before = await groundAtCell(page)
  const base = page.getByRole('textbox', { name: 'Base height', exact: true })
  await base.fill('96')
  await base.press('Enter')
  await expect.poll(() => groundAtCell(page)).toBe(before + 30)

  // A seed is a world: the same one again gives the same ground, another gives another.
  const seed = page.getByRole('textbox', { name: 'Seed', exact: true })
  const at = await groundAtCell(page)
  await seed.fill('7')
  await seed.press('Enter')
  await expect.poll(() => groundAtCell(page)).not.toBe(at)
  await seed.fill('1337')
  await seed.press('Enter')
  await expect.poll(() => groundAtCell(page)).toBe(at)

  // Adds an ore of the project's own block; it's in the file as the format writes it.
  await page.getByRole('button', { name: 'Add ore' }).click()
  const prompt = page.getByRole('dialog', { name: 'New ore' })
  await prompt.getByRole('textbox').fill('amber')
  await prompt.getByRole('button', { name: 'Add' }).click()
  const group = page.locator('[data-path="ores.amber"]')
  await group.getByRole('combobox', { name: 'Block of' }).selectOption('customBlock')
  await group.getByRole('combobox', { name: 'Block', exact: true }).selectOption('floating_lamp')
  await save(page, 'ruby_hills')
  const written = (await files(page))[HILLS]!
  const model = JSON.parse(written)
  expect(model.terrain.base).toBe(96)
  expect(model.ores.amber).toEqual({ customBlock: 'floating_lamp', size: 8, veins: 8 })
  expect(written).toBe(canonical(HILLS, written))

  // The lamp is drawn by a centity, which a terrain can't place: the problem is named, on the ore.
  await showPanel(page, 'Problems')
  await expect(page.getByText(/is drawn by the centity/).first()).toBeVisible()
  expect(errors).toEqual([])
})

test('a decoration added in the inspector is written and drawn, kept to the biome area it names', async ({
  page,
}) => {
  const errors = await openExample(page)
  await openResource(page, 'ruby_hills', 'Terrain')
  await expect(page.getByTestId('terrain-map')).toBeVisible()
  await page.getByRole('button', { name: 'Add decoration' }).click()
  const prompt = page.getByRole('dialog', { name: 'New decoration' })
  await prompt.getByRole('textbox').fill('boulders')
  await prompt.getByRole('button', { name: 'Add' }).click()
  const group = page.locator('[data-path="decorations.boulders"]')
  const block = group.getByRole('textbox', { name: 'Block', exact: true })
  await block.fill('minecraft:cobblestone')
  await block.press('Enter')
  const count = group.getByRole('textbox', { name: 'Tries per chunk' })
  await count.fill('3')
  await count.press('Enter')
  await group.getByRole('checkbox', { name: 'snowy' }).check()
  await expect(page.getByRole('list', { name: 'Decorations' }).getByText('boulders')).toBeVisible()
  await save(page, 'ruby_hills')
  const written = (await files(page))[HILLS]!
  expect(JSON.parse(written).decorations.boulders).toEqual({
    block: 'minecraft:cobblestone',
    count: 3,
    biomes: ['snowy'],
  })
  expect(written).toBe(canonical(HILLS, written))

  // Renaming the area renames it in the filter.
  await page
    .locator('[data-path="biomes.snowy"]')
    .getByRole('button', { name: 'Rename biome area snowy' })
    .click()
  const rename = page.getByRole('dialog', { name: 'Rename biome area' })
  await rename.getByRole('textbox').fill('tundra')
  await rename.getByRole('button', { name: 'Rename' }).click()
  await save(page, 'ruby_hills')
  const renamed = JSON.parse((await files(page))[HILLS]!)
  expect(renamed.decorations.boulders.biomes).toEqual(['tundra'])
  expect(renamed.ores.ruby.biomes).toEqual(['tundra'])
  expect(errors).toEqual([])
})

test('a new terrain starts as hills that draw, and a mistake in it says why nothing is drawn', async ({
  page,
}) => {
  const errors = await openExample(page)
  await showKind(page, 'Terrain')
  await page.getByRole('button', { name: 'New terrain' }).click()
  const create = page.getByRole('dialog', { name: 'New terrain' })
  await create.getByRole('textbox').fill('realm')
  await create.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByRole('tab', { name: 'realm' })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByTestId('terrain-map')).toBeVisible()
  await showPanel(page, 'Problems')
  await expect(page.getByText('No problems.')).toBeVisible()

  // A height the world can't have: the problem, and the preview says it has nothing to draw.
  const base = page.getByRole('textbox', { name: 'Base height', exact: true })
  await base.fill('99999')
  await base.press('Enter')
  await expect(page.getByRole('alert', { name: "The preview can't draw this" })).toContainText(
    'base must be from',
  )
  await expect(page.getByTestId('terrain-map')).toHaveCount(0)
  expect(errors).toEqual([])
})

test('a script the file hands stages to is made from the template, run by the preview, and its failure said', async ({
  page,
}) => {
  const errors = await openExample(page)
  await openResource(page, 'ruby_hills', 'Terrain')
  const before = await groundAtCell(page)

  // Turned on: the file says so and its script is made beside it from the template, which changes nothing yet.
  await page.getByRole('checkbox', { name: 'Hand stages to a script' }).check()
  await expect
    .poll(async () => (await files(page))['terrain/ruby_hills.lua'])
    .toContain('stages.height')
  await expect.poll(() => groundAtCell(page)).toBe(before)

  // A height stage that lifts the ground (requiring a module, as on the server) is what the preview draws.
  const write = (path: string, text: string) =>
    outside(page, (backend, arg) => backend.testWrite(arg.path, arg.text), { path, text })
  await write('modules/lift/init.lua', 'return { by = 20 }')
  await write(
    'terrain/ruby_hills.lua',
    'local lift = require("lift")\nreturn { height = function(x, z, h) return h + lift.by end }',
  )
  await expect.poll(() => groundAtCell(page)).toBe(before + 20)

  // A stage that fails is said, at its line, and the file's own ground is drawn.
  await write('terrain/ruby_hills.lua', 'return {\n  height = function() error("too high") end,\n}')
  const failed = page.getByRole('alert', { name: 'The script failed' })
  await expect(failed).toContainText('terrain/ruby_hills.lua:2: too high')
  await expect.poll(() => groundAtCell(page)).toBe(before)
  await save(page, 'ruby_hills')
  expect(JSON.parse((await files(page))[HILLS]!).script).toEqual({})

  // The failure opens the script.
  await failed.getByRole('button', { name: 'its height stage' }).click()
  await expect(page.getByRole('tab', { name: 'ruby_hills.lua' })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  expect(errors).toEqual([])
})

test('3D ground from the inspector: a 3D noise, islands the preview draws over the ground, an area of its own, and back', async ({
  page,
}) => {
  const errors = await openExample(page)
  await openResource(page, 'ruby_hills', 'Terrain')
  const before = await groundAtCell(page)
  expect(before).toBeLessThan(140)

  // The example's ground is 3D already: its grove has ledges of its own.
  const threeD = page.getByRole('checkbox', { name: '3D ground (overhangs, arches, islands)' })
  await expect(threeD).toBeChecked()
  const grove = page.locator('[data-path="biomes.grove"]')
  await expect(grove.getByRole('checkbox', { name: 'Own 3D noises' })).toBeChecked()
  await expect(
    grove.locator('[data-path="biomes.grove.terrain.density.noises.ledges"]'),
  ).toBeVisible()

  await page.getByRole('button', { name: 'Add a 3D noise', exact: true }).click()
  await page
    .getByRole('dialog', { name: 'New 3D noise' })
    .getByRole('button', { name: 'Add' })
    .click()
  const squash = page
    .locator('[data-path="terrain.density.noises.overhangs"]')
    .getByRole('textbox', { name: 'Squash' })
  await squash.fill('3')
  await squash.press('Enter')

  // Islands everywhere (the lowest threshold): the map's top is now the islands, far above the ground.
  await page.getByRole('checkbox', { name: 'Floating islands' }).check()
  const threshold = page
    .locator('[data-path="terrain.density.islands"]')
    .getByRole('textbox', { name: 'Threshold' })
  await threshold.fill('-1')
  await threshold.press('Enter')
  await expect.poll(() => groundAtCell(page)).toBeGreaterThan(140)

  // An area with none of the file's 3D noise.
  const snowy = page.locator('[data-path="biomes.snowy"]')
  await snowy.getByRole('checkbox', { name: 'Own 3D noises' }).check()
  const times = snowy.getByRole('textbox', { name: '3D noises times' })
  await times.fill('0')
  await times.press('Enter')
  await save(page, 'ruby_hills')
  const written = (await files(page))[HILLS]!
  const model = JSON.parse(written)
  expect(model.terrain.density).toEqual({
    noises: { overhangs: { noise: { frequency: 0.03, octaves: 2 }, amplitude: 8, squash: 3 } },
    islands: { threshold: -1 },
  })
  expect(model.biomes.snowy.terrain.density).toEqual({ scale: 0 })
  expect(written).toBe(canonical(HILLS, written))
  await showPanel(page, 'Problems')
  await expect(page.getByText('No problems.')).toBeVisible()

  // Flat: the ground is the heights' again, and no area keeps a density (a file without one can't have them).
  await threeD.uncheck()
  await expect.poll(() => groundAtCell(page)).toBe(before)
  await save(page, 'ruby_hills')
  const flat = JSON.parse((await files(page))[HILLS]!)
  expect(flat.terrain.density).toBeUndefined()
  expect(flat.biomes.snowy.terrain.density).toBeUndefined()
  expect(flat.biomes.grove.terrain).toEqual({})
  expect(errors).toEqual([])
})
