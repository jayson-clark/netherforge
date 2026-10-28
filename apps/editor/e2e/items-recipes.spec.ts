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

const RUBY = 'items/ruby/item.json'

const canonical = (kind: string, path: string, text: string) =>
  JSON.parse(canonicalize(kind, path, text)).text as string

const items = (page: Page) => page.getByRole('listbox', { name: 'Items' })

test('opens a recipe from the item it uses, renames the item everywhere, and undoes the rename', async ({
  page,
}) => {
  await openExampleWithAssets(page)
  await openResource(page, 'ruby', 'Items')
  // Its fields, the tooltip and the recipes list are ItemScreen.test.tsx's: here, its pack
  // model drawn from the client's assets, and a recipe that uses it opened from it.
  await expect(page.getByLabel('Item preview').locator('img')).toHaveCount(1)
  const recipes = page.getByRole('region', { name: 'Recipes', exact: true }).last()
  await recipes.getByRole('button', { name: 'ruby_sword' }).click()
  await expect(page.getByRole('tab', { name: 'ruby_sword' })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await expect(page.getByRole('button', { name: 'Square 2: ruby' })).toBeVisible()

  const written = (await files(page))[RUBY]!

  // Renaming the item follows it into every file that names it.
  // In place, in the project explorer: F2, type, Enter.
  await items(page).getByRole('option', { name: 'ruby', exact: true }).click()
  await page.keyboard.press('F2')
  const rename = items(page).getByRole('textbox', { name: 'Rename ruby' })
  await rename.fill('garnet')
  await rename.press('Enter')
  await expect(items(page).getByRole('option', { name: 'garnet' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Square 2: garnet' })).toBeVisible()
  await expect
    .poll(async () => JSON.parse((await files(page))['recipes/ruby_dust.json']!).ingredient)
    .toEqual({ item: 'garnet' })
  const project = await files(page)
  expect(JSON.parse(project['recipes/ruby.json']!).result).toEqual({ item: 'garnet' })
  expect(JSON.parse(project['menus/shop/menu.json']!).slots['13'].item.item).toBe('garnet')
  // The open recipe had no unsaved edits: it's edited (undoably) and saved.
  await expect(page.getByRole('tab', { name: 'ruby_sword', exact: true })).toBeVisible()
  expect(JSON.parse(project['recipes/ruby_sword.json']!).key.R).toEqual({ item: 'garnet' })
  await showPanel(page, 'Problems')
  await expect(page.getByText('No problems.')).toBeVisible()

  // One undo takes the whole rename back: the item's folder, every file that
  // named it (open or not), and the open recipe.
  const ingredient = async () =>
    JSON.parse((await files(page))['recipes/ruby_dust.json']!).ingredient as unknown
  await page.keyboard.press('ControlOrMeta+z')
  await expect(items(page).getByRole('option', { name: 'ruby', exact: true })).toBeVisible()
  await expect(items(page).getByRole('option', { name: 'garnet' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Square 2: ruby' })).toBeVisible()
  await expect.poll(ingredient).toEqual({ item: 'ruby' })
  const undone = await files(page)
  expect(undone[RUBY]).toBe(written)
  expect(undone['items/garnet/item.json']).toBeUndefined()
  expect(JSON.parse(undone['menus/shop/menu.json']!).slots['13'].item.item).toBe('ruby')
  expect(JSON.parse(undone['recipes/ruby_sword.json']!).key.R).toEqual({ item: 'ruby' })
  await expect(page.getByText('No problems.')).toBeVisible()

  // Redo makes it again, all of it.
  await page.keyboard.press('ControlOrMeta+Shift+z')
  await expect(items(page).getByRole('option', { name: 'garnet' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Square 2: garnet' })).toBeVisible()
  await expect.poll(ingredient).toEqual({ item: 'garnet' })
  const redone = await files(page)
  expect(redone[RUBY]).toBeUndefined()
  expect(redone['items/garnet/item.json']).toBe(written)
  expect(JSON.parse(redone['menus/shop/menu.json']!).slots['13'].item.item).toBe('garnet')
})

test('builds a shaped recipe by placing ingredients on the grid', async ({ page }) => {
  await openExampleWithAssets(page)
  await showKind(page, 'Recipes')
  await page.getByRole('button', { name: 'New recipe' }).click()
  const create = page.getByRole('dialog', { name: 'New recipe' })
  await expect(create.getByText('Id (the file name)')).toBeVisible()
  await create.getByRole('textbox').fill('lantern')
  await create.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByRole('tab', { name: 'lantern' })).toHaveAttribute('aria-selected', 'true')
  const path = 'recipes/lantern.json'
  expect(Object.keys(await files(page))).toContain(path)
  // The template: one stick in the first square.
  await expect(page.getByRole('button', { name: 'Square 1: stick' })).toBeVisible()

  const picker = page.getByRole('dialog', { name: /^Square/ })
  // A Minecraft item, searched for and picked with its icon.
  await page.getByRole('button', { name: 'Square 5: empty' }).click()
  await picker.getByRole('textbox', { name: 'Search items' }).fill('sti')
  await expect(picker.getByRole('button', { name: 'minecraft:stick' }).locator('img')).toHaveCount(
    1,
  )
  await picker.getByRole('button', { name: 'minecraft:stick' }).click()
  await expect(page.getByRole('button', { name: 'Square 5: stick' })).toBeVisible()

  // A project item.
  await page.getByRole('button', { name: 'Square 2: empty' }).click()
  await picker.getByRole('tab', { name: 'Project item' }).click()
  await picker.getByRole('button', { name: 'ruby', exact: true }).click()

  // A tag that isn't one: format's problem shows at the square, then a real one.
  await page.getByRole('button', { name: 'Square 8: empty' }).click()
  await picker.getByRole('tab', { name: 'Tag' }).click()
  await picker.getByRole('textbox', { name: 'Tag' }).fill('Not A Tag')
  await picker.getByRole('button', { name: 'Use' }).click()
  const problems = page.getByRole('list', { name: 'Recipe problems' })
  await expect(problems).toContainText("isn't an item tag")
  await expect(page.locator('button[aria-invalid="true"]')).toHaveCount(1)
  await page.getByRole('button', { name: /^Square 8/ }).click()
  await picker.getByRole('textbox', { name: 'Tag' }).fill('#minecraft:planks')
  await picker.getByRole('button', { name: 'Use' }).click()
  await expect(problems).toHaveCount(0)

  // Right-click empties a square; the rest stays where it was put.
  await page.getByRole('button', { name: 'Square 1: stick' }).click({ button: 'right' })
  await expect(page.getByRole('button', { name: 'Square 1: empty' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Square 2: ruby' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Square 8: #planks' })).toBeVisible()

  // The result names the project item too.
  await page
    .getByRole('complementary', { name: 'Inspector' })
    .getByRole('combobox', { name: 'Project item' })
    .selectOption('ruby')
  await page.getByRole('combobox', { name: 'Category' }).selectOption('misc')
  await save(page, 'lantern')

  const written = (await files(page))[path]!
  // The squares became a trimmed pattern; the stick kept the template's letter.
  const expected = {
    type: 'shaped',
    pattern: ['R', '#', 'P'],
    key: { '#': 'minecraft:stick', R: { item: 'ruby' }, P: '#minecraft:planks' },
    result: { item: 'ruby' },
    category: 'misc',
  }
  expect(written).toBe(canonical('recipe', path, JSON.stringify(expected)))
  await showPanel(page, 'Problems')
  await expect(page.getByText('No problems.')).toBeVisible()

  // Another station: a furnace takes the first ingredient.
  await page.getByRole('combobox', { name: 'Recipe type' }).selectOption('furnace')
  await expect(page.getByRole('button', { name: 'Input: ruby' })).toBeVisible()
  await expect(page.getByRole('textbox', { name: 'Cooking time' })).toHaveAttribute(
    'placeholder',
    '200 ticks',
  )
})
