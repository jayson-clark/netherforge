import { expect, type Page } from '@playwright/test'
import { readNbt, writeNbt, type NbtRoot } from '@netherforge/structures/nbt'
import { parseStructure } from '@netherforge/structures/structure'
import { filledBlocks, structureRoot } from '../src/testing/nbtFixtures'
import { files, openExampleWithAssets, openResource, outside, test } from './helpers'

const base64 = async (root: NbtRoot) => Buffer.from(await writeNbt(root)).toString('base64')

/** Another program writes binary files into the project (base64 each). */
const writeBytes = (page: Page, files: Record<string, string>) =>
  outside(
    page,
    (backend, given) => {
      for (const [path, data] of Object.entries(given)) backend.testWriteBytes(path, data)
    },
    files,
  )

test('previews a structure, then captures it again from the dev server', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await openExampleWithAssets(page)
  // A small house of the fixture's blocks: stone bricks below, stairs on top, air in the middle.
  const house = structureRoot({
    size: [3, 2, 3],
    palette: [
      { Name: 'minecraft:stone_bricks' },
      { Name: 'minecraft:oak_stairs', Properties: { facing: 'east', half: 'bottom' } },
      { Name: 'minecraft:air' },
    ],
    blocks: [
      ...filledBlocks([3, 1, 3], 0),
      { pos: [0, 1, 0], state: 1 },
      { pos: [1, 1, 1], state: 2 },
    ],
    entities: 1,
  })
  await writeBytes(page, {
    'structures/house.nbt': await base64(house),
    // As big as a structure block saves: still drawn, as its outer walls.
    'structures/big.nbt': await base64(
      structureRoot({
        size: [48, 48, 48],
        palette: [{ Name: 'minecraft:stone' }],
        blocks: filledBlocks([48, 48, 48]),
      }),
    ),
  })

  await openResource(page, 'house', 'Structures')
  await expect(page.getByLabel('Structure size')).toHaveText('3 × 2 × 3')
  await expect(page.getByLabel('Block count')).toHaveText('11')
  await expect(page.getByLabel('Entity count')).toHaveText('1')
  await expect(page.getByLabel('Data version')).toHaveText('5023')
  const counts = page.getByRole('table', { name: 'Blocks by kind' })
  await expect(counts.getByRole('row').first()).toHaveText('minecraft:stone_bricks9')
  await expect(counts).toContainText('minecraft:oak_stairs1')
  // The preview drew the fixture's models: the bricks' hidden faces are left out.
  // The first draw is the preview's heaviest work, which a loaded machine slows.
  await expect(page.getByLabel('Faces drawn')).toContainText('hidden ones left out', {
    timeout: 15_000,
  })
  await expect(page.getByLabel('Structure preview').locator('canvas')).toBeVisible()

  await openResource(page, 'big', 'Structures')
  await expect(page.getByLabel('Structure size')).toHaveText('48 × 48 × 48')
  await expect(page.getByLabel('Faces drawn')).toContainText('Drawing 13824 faces')
  // It stays interactive: orbiting it is a drag like any other.
  const canvas = page.getByLabel('Structure preview').locator('canvas')
  const box = (await canvas.boundingBox())!
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width / 2 + 120, box.y + box.height / 2 + 30, { steps: 8 })
  await page.mouse.up()

  // Capturing house again from the dev server: corners from where a player stands.
  await page.getByRole('tab', { name: 'house.nbt', exact: true }).click()
  const capture = page.getByRole('button', { name: 'Capture and replace' })
  await expect(capture).toBeDisabled()
  await outside(
    page,
    (backend) => {
      backend.testConnect()
      backend.testPlayer('Steve', 'world', 10, 64, -4)
    },
    null,
  )
  await page.getByRole('button', { name: 'Use my position for corner 1' }).click()
  await expect(page.getByRole('textbox', { name: 'Corner 1 X' })).toHaveValue('10')
  await expect(page.getByRole('textbox', { name: 'Corner 1 Z' })).toHaveValue('-4')
  const corner = (axis: string, value: string) =>
    page.getByRole('textbox', { name: `Corner 2 ${axis}` }).fill(value)
  await corner('X', '13')
  await corner('Y', '65')
  await corner('Z', '-1')
  await page.getByRole('textbox', { name: 'Corner 2 Z' }).press('Enter')
  await expect(page.getByRole('combobox', { name: 'World' })).toHaveValue('world')
  await capture.click()
  const confirm = page.getByRole('dialog', { name: 'Replace house' })
  await confirm.getByRole('button', { name: 'Replace' }).click()
  await expect(
    page.getByRole('complementary', { name: 'Structure', exact: true }).getByRole('status'),
  ).toContainText('Captured 4 × 2 × 4 blocks')
  await expect(page.getByLabel('Structure size')).toHaveText('4 × 2 × 4')

  const written = await outside(page, (backend) => backend.testBytes('structures/house.nbt'), null)
  const captured = parseStructure(await readNbt(Buffer.from(written!, 'base64')))
  expect(captured.size).toEqual([4, 2, 4])
  const log = await outside(page, (backend) => backend.bridgeLog, null)
  expect(log).toContainEqual({
    method: 'save_structure',
    params: {
      world: 'world',
      from: { x: 10, y: 64, z: -4 },
      to: { x: 13, y: 65, z: -1 },
      entities: false,
    },
  })
  // The write hot-reloads it, as any save does.
  expect(log).toContainEqual({ method: 'reload', params: { paths: ['structures/house.nbt'] } })
  expect(errors).toEqual([])
})

test('turns on generation for a structure, edits where it generates, and turns it off', async ({
  page,
}) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await openExampleWithAssets(page)
  await writeBytes(page, {
    'structures/ruins.nbt': await base64(
      structureRoot({
        size: [1, 1, 1],
        palette: [{ Name: 'minecraft:stone' }],
        blocks: filledBlocks([1, 1, 1], 0),
      }),
    ),
  })

  await openResource(page, 'ruins', 'Structures')
  const generation = page.getByRole('checkbox', { name: 'Generates in the world enabled' })
  await expect(generation).not.toBeChecked()
  expect(Object.keys(await files(page))).not.toContain('structures/ruins.json')

  // Turning it on writes the file through format: the overworld, everything else the game's default.
  // The switch shows the file, so it's on only once the write lands: a click and a wait, not check(),
  // which looks right after its click (and on a busy runner found it still off).
  await generation.click()
  await expect(generation).toBeChecked()
  await expect(page.getByRole('textbox', { name: 'Biomes' })).toHaveValue('#minecraft:is_overworld')
  await expect
    .poll(async () => (await files(page))['structures/ruins.json'])
    .toContain('"biomes": ["#minecraft:is_overworld"]')

  // Each edit is saved at once, canonically; a default goes back to being absent.
  await page.getByLabel('Spacing (chunks)').fill('24')
  await page.getByLabel('Spacing (chunks)').blur()
  await page.getByLabel('Terrain adaptation').selectOption('beard_thin')
  await expect
    .poll(async () => (await files(page))['structures/ruins.json'])
    .toContain('"terrainAdaptation": "beard_thin"')
  expect((await files(page))['structures/ruins.json']).toContain('"spacing": 24')
  await page.getByLabel('Terrain adaptation').selectOption('none')
  await expect
    .poll(async () => (await files(page))['structures/ruins.json'])
    .not.toContain('terrainAdaptation')

  // A biome the game's data lacks is a problem next to the field once the game is imported; a typo in the shape
  // (not a game id, so a reference to one of the project's) is one now.
  await page.getByRole('textbox', { name: 'Biomes' }).fill('not a biome!')
  await page.getByRole('textbox', { name: 'Biomes' }).blur()
  await expect(
    page.getByRole('status').filter({ hasText: "isn't a reference to a biome" }).first(),
  ).toBeVisible()

  // Turning it off asks, then removes the file; the structure stays.
  await generation.click()
  await page.getByRole('dialog').getByRole('button', { name: 'Delete' }).click()
  await expect
    .poll(async () => Object.keys(await files(page)))
    .not.toContain('structures/ruins.json')
  await expect(page.getByLabel('Structure size')).toHaveText('1 × 1 × 1')
  expect(errors).toEqual([])
})
