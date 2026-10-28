import { expect, type Page } from '@playwright/test'
import { canonicalize } from '@netherforge/format'
import {
  files,
  openExampleWithAssets,
  openResource,
  save,
  showPanel,
  test,
  showKind,
} from './helpers'

const GROVE = 'biomes/ruby_grove.json'
const biomes = (page: Page) => page.getByRole('listbox', { name: 'Biomes' })

// What each field does to the file is BiomeEditor.test.tsx's; this is the editor from the
// explorer to a canonical file, with the landscape painted in the file's colours.
test('edits a biome: its colours drawn, an edit saved canonically', async ({ page }) => {
  await openExampleWithAssets(page)
  await openResource(page, 'ruby_grove', 'Biomes')

  const scene = page.getByRole('img', { name: 'Biome colours' })
  await expect(scene.locator('[data-color="#d94f7a"]')).toBeVisible()
  const inspector = page.getByRole('complementary', { name: 'Inspector' })
  const water = inspector.getByRole('textbox', { name: 'Water', exact: true })
  await water.fill('#3366CC')
  await water.press('Enter')
  await expect(scene.locator('[data-color="#3366cc"]')).toBeVisible()

  await save(page, 'ruby_grove')
  const written = (await files(page))[GROVE]!
  expect(written).toBe(JSON.parse(canonicalize('biome', GROVE, written)).text)
  expect(JSON.parse(written).colors.water).toBe('#3366cc')
})

test('renames a biome everywhere it is named, and a game biome stays the game’s', async ({
  page,
}) => {
  await openExampleWithAssets(page)
  await showPanel(page, 'Project')
  await showKind(page, 'Biomes')
  await biomes(page).getByRole('option', { name: 'ruby_grove', exact: true }).click()
  await page.keyboard.press('F2')
  const rename = biomes(page).getByRole('textbox', { name: 'Rename ruby_grove' })
  await rename.fill('ash_grove')
  await rename.press('Enter')
  await expect(biomes(page).getByRole('option', { name: 'ash_grove' })).toBeVisible()
  await expect
    .poll(async () => JSON.parse((await files(page))['terrain/ruby_hills.json']!).biomes)
    .toMatchObject({
      grove: { biome: 'ash_grove' },
      plains: { biome: 'minecraft:plains' },
      snowy: { biome: 'minecraft:snowy_plains' },
    })
  expect(Object.keys(await files(page))).toContain('biomes/ash_grove.json')
  await showPanel(page, 'Problems')
  await expect(page.getByText('No problems.')).toBeVisible()
})
