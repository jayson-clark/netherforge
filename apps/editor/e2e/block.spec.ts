import { expect } from '@playwright/test'
import { canonicalize } from '@netherforge/format'
import { files, openExampleWithAssets, openResource, save, test } from './helpers'

const ORE = 'blocks/ruby_ore/block.json'

test('edits a block: its cube is drawn from its pack look, and its mining, drops and sounds are fields', async ({
  page,
}) => {
  await openExampleWithAssets(page)
  await openResource(page, 'ruby_ore', 'Blocks')

  // The cube as the pack's model draws it: a picture on each of its six faces.
  const cube = page.getByLabel('Block preview')
  await expect(cube.locator('img[data-face]')).toHaveCount(6)

  const inspector = page.getByRole('complementary', { name: 'Inspector' })
  await expect(inspector.getByRole('combobox', { name: 'Look' })).toHaveValue('ui/ruby_ore')
  const hardness = inspector.getByRole('textbox', { name: 'Hardness' })
  await expect(hardness).toHaveValue('3')
  await hardness.fill('4')
  await hardness.press('Enter')
  await inspector.getByRole('combobox', { name: 'Tool' }).selectOption('axe')
  await inspector.getByRole('combobox', { name: 'Drops' }).selectOption('')
  await inspector.getByRole('combobox', { name: 'Centity' }).selectOption('lamp')
  await expect(page.getByLabel('Block preview')).toContainText('Drawn by the centity lamp')

  await save(page, 'ruby_ore')
  const written = (await files(page))[ORE]!
  expect(written).toBe(JSON.parse(canonicalize('block', ORE, written)).text)
  const block = JSON.parse(written)
  expect(block).toMatchObject({ model: 'ui/ruby_ore', hardness: 4, tool: 'axe', centity: 'lamp' })
  expect(block.drops).toBeUndefined()
})

test('places a project block with an item: the item names it', async ({ page }) => {
  await openExampleWithAssets(page)
  await openResource(page, 'ruby_ore', 'Items')
  const inspector = page.getByRole('complementary', { name: 'Inspector' })
  await expect(inspector.getByRole('combobox', { name: 'Places block' })).toHaveValue('ruby_ore')
  await inspector.getByRole('combobox', { name: 'Places block' }).selectOption('floating_lamp')
  await save(page, 'ruby_ore')
  expect(JSON.parse((await files(page))['items/ruby_ore/item.json']!).block).toBe('floating_lamp')
})
