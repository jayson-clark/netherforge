import { expect } from '@playwright/test'
import { canonicalize } from '@netherforge/format'
import { files, openExampleWithAssets, openResource, save, test } from './helpers'

const TREASURE = 'loot/treasure.json'

// Shares, adding entries and conditions, and rolling are LootEditor.test.tsx's; this is the
// outline picking a pool, an entry drawn with the client's icon, and the edit saved canonically.
test('edits a loot table from its outline, saves it canonically, and rolls it', async ({
  page,
}) => {
  await openExampleWithAssets(page)
  await openResource(page, 'treasure', 'Loot tables')
  const pools = page.getByRole('treegrid', { name: 'Pools' })
  await expect(pools.getByRole('row')).toHaveCount(2)
  await pools.getByRole('row', { name: 'gems' }).click()

  const ruby = page
    .getByRole('list', { name: 'Entries' })
    .getByRole('button', { name: 'Entry: ruby' })
  await expect(ruby.locator('img')).toHaveCount(1)
  await ruby.click()
  const weight = page
    .getByRole('complementary', { name: 'Inspector' })
    .getByRole('textbox', { name: 'Weight' })
  await weight.fill('1')
  await weight.press('Enter')
  await expect(ruby).toContainText('25% of a pick')

  await save(page, 'treasure')
  const written = (await files(page))[TREASURE]!
  expect(written).toBe(JSON.parse(canonicalize('loot_table', TREASURE, written)).text)
  expect(JSON.parse(written).pools.gems.entries[0].weight).toBe(1)

  // A roll by format's roller: the same seed, the same stacks (`ruby ×2`), by name and count.
  const preview = page.getByRole('region', { name: 'Roll preview' })
  await preview.getByRole('button', { name: 'Roll', exact: true }).click()
  const rolled = preview.getByRole('list', { name: 'Rolled' })
  await expect(rolled.getByRole('listitem').first()).toHaveAccessibleName(/ ×\d+$/)
  const first = await rolled.ariaSnapshot()
  await preview.getByRole('button', { name: 'Roll', exact: true }).click()
  await expect(rolled).toMatchAriaSnapshot(first)
})

test("includes a package's exported loot table by its full name", async ({ page }) => {
  await openExampleWithAssets(page)
  await openResource(page, 'treasure', 'Loot tables')
  await page.getByRole('treegrid', { name: 'Pools' }).getByRole('row', { name: 'extra' }).click()
  await page
    .getByRole('list', { name: 'Entries' })
    .getByRole('button', { name: 'Entry: Loot table library:gems' })
    .click()
  // The project's other tables, then what the packages it depends on export.
  const table = page
    .getByRole('complementary', { name: 'Inspector' })
    .getByRole('combobox', { name: 'Loot table' })
  await expect(table).toHaveValue('library:gems')
  await expect(table.getByRole('option')).toHaveText([
    'crystal_glass',
    'deepslate_rubble',
    'glow_lamp',
    'junk',
    'ruby_crystal',
    'ruby_ore',
    'shrine_chest',
    'library:gems',
  ])
})
