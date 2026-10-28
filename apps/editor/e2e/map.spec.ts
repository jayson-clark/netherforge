import { expect, type Page } from '@playwright/test'
import { writeNbt, type NbtRoot } from '@netherforge/structures/nbt'
import { gameRulesRoot, levelRoot, worldGenRoot } from '../src/testing/nbtFixtures'
import {
  chunkRoot,
  regionFile,
  sectionIndices,
  type FixtureChunk,
} from '../src/testing/regionFixtures'
import { openExampleWithAssets, openResource, outside, test } from './helpers'

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

// What the inspector shows of a world and saving a dev-server world over it are
// MapEditor.test.tsx's (and what a capture copies is the backend's); this is a map's binary
// files reaching the page and opening from the explorer.
test('opens a map from the explorer and shows what its world holds', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await openExampleWithAssets(page)
  const world = 'dimensions/minecraft/overworld'
  await writeBytes(page, {
    'maps/arena/level.dat': await base64(levelRoot({ name: 'Arena', spawn: [4, 70, -8] })),
    [`maps/arena/${world}/data/minecraft/world_gen_settings.dat`]: await base64(worldGenRoot(-42n)),
    [`maps/arena/${world}/data/minecraft/game_rules.dat`]: await base64(
      gameRulesRoot({ 'minecraft:keep_inventory': true }),
    ),
    [`maps/arena/${world}/region/r.0.0.mca`]: Buffer.alloc(4096, 1).toString('base64'),
  })

  await openResource(page, 'arena', 'Maps')
  const screen = page.getByRole('complementary', { name: 'Map' })
  await expect(screen.getByLabel('World name')).toHaveText('Arena')
  await expect(screen.getByLabel('Seed')).toHaveText('-42')
  await expect(screen.getByRole('table', { name: 'Game rules' })).toContainText(
    'minecraft:keep_inventorytrue',
  )
  expect(errors).toEqual([])
})

test("draws a map's chunks around its spawn, and more as the view grows or moves", async ({
  page,
}) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await openExampleWithAssets(page)
  const region = 'maps/arena/dimensions/minecraft/overworld/region'
  // A floor of stone at y = 64 along a row of chunks, 0 to 12; chunk 0, 1 is broken.
  const floor = {
    y: 4,
    palette: ['minecraft:air', 'minecraft:stone'],
    indices: sectionIndices((_x, y) => (y === 0 ? 1 : 0)),
  }
  const row: FixtureChunk[] = []
  for (let cx = 0; cx <= 12; cx += 1) {
    row.push({ cx, cz: 0, root: chunkRoot({ x: cx, z: 0, sections: [floor] }) })
  }
  row.push({ cx: 0, cz: 1, bytes: new Uint8Array(32).fill(5) })
  await writeBytes(page, {
    'maps/arena/level.dat': await base64(levelRoot({ name: 'Arena', spawn: [8, 65, 8] })),
    [`${region}/r.0.0.mca`]: Buffer.from(await regionFile(row)).toString('base64'),
  })

  await openResource(page, 'arena', 'Maps')
  const screen = page.getByLabel('Map arena')
  const preview = screen.getByLabel('Map preview')
  await expect(preview.locator('canvas')).toBeVisible()
  const drawn = preview.getByLabel('Chunks drawn')
  // Radius 6 around chunk 0, 0 takes the row's first seven chunks: 16 × 16 floors, top and
  // bottom, their long edges, and the west end; faces toward a neighbouring chunk's stone
  // (the seventh's east one too: the eighth is read for it) are left out.
  // The first draw waits for the terrain worker to start, which a loaded machine slows.
  await expect(drawn).toContainText(`Drawing 7 chunks (${7 * 512 + 7 * 32 + 16} faces).`, {
    timeout: 15_000,
  })
  await expect(preview.getByLabel('Unreadable chunks')).toContainText(
    "1 chunk can't be read: chunk 0, 1:",
  )

  // A bigger radius reaches further along the row.
  const radius = screen.getByRole('slider', { name: 'View radius in chunks' })
  await radius.fill('10')
  await expect(drawn).toContainText('Drawing 11 chunks')
  await radius.fill('1')
  // Meshes just past the radius are kept, so shrinking it drops only the far ones.
  await expect(drawn).toContainText('Drawing 4 chunks')

  // Panning the camera loads the chunks around where it now looks.
  const canvas = preview.locator('canvas')
  const box = (await canvas.boundingBox())!
  await page.mouse.move(box.x + box.width * 0.85, box.y + box.height / 2)
  await page.mouse.down({ button: 'right' })
  await page.mouse.move(box.x + box.width * 0.15, box.y + box.height / 2, { steps: 12 })
  await page.mouse.up({ button: 'right' })
  await expect(drawn).not.toContainText('Drawing 4 chunks')
  // The Spawn button brings it back: chunks 0 and 1 again, with whichever of 2 and 3 the
  // pan kept (meshes are kept a couple of chunks past the radius).
  await screen.getByRole('button', { name: 'Spawn' }).click()
  await expect(drawn).toHaveText(/^Drawing [234] chunks/)

  // A map without region files says so instead.
  await outside(page, (backend, path) => backend.testDelete(path), `${region}/r.0.0.mca`)
  await expect(screen.getByLabel('Map preview')).toContainText('has no region files')
  expect(errors).toEqual([])
})
