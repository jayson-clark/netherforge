import { expect } from '@playwright/test'
import { files, filesOf, openExample, outside, showPanel, test, showKind } from './helpers'

test('shows a dependency read-only and copies its item into the project', async ({ page }) => {
  const errors = await openExample(page)
  await showPanel(page, 'Project')
  await showKind(page, 'Package library')

  const pkg = page.getByRole('list', { name: 'Package library' })
  await expect(pkg.getByRole('listitem', { name: 'library:gem', exact: true })).toBeVisible()
  await expect(pkg.getByRole('listitem', { name: 'library:greetings' })).toBeVisible()
  // Read-only: nothing there renames or deletes.
  await expect(page.getByText('read-only')).toBeVisible()
  await expect(pkg.getByRole('button', { name: /rename|delete/i })).toHaveCount(0)

  // Opened, it's the package's as it is: read-only, every control disabled.
  await pkg.getByRole('listitem', { name: 'library:gem', exact: true }).dblclick()
  await expect(page.getByLabel('Read-only')).toContainText('"library"')

  await pkg.getByRole('button', { name: 'Copy library:gem into project' }).click()
  const id = page.getByRole('textbox', { name: 'Id (the folder name)' })
  await expect(id).toHaveValue('gem')
  await id.fill('shiny_gem')
  await page.getByRole('button', { name: 'Copy', exact: true }).click()

  // The copy is the project's, shown among its items, naming the library's model in full.
  await expect(page.getByRole('option', { name: 'shiny_gem', exact: true })).toBeVisible()
  const written = (await files(page))['items/shiny_gem/item.json']!
  expect(JSON.parse(written).itemModel).toBe('library:gems/gem')
  expect((await files(page))['items/shiny_gem/script.lua']).toContain('The gem hums.')
  expect(errors).toEqual([])
})

test("shows a dependency's resource with its own files, pictures and looks", async ({ page }) => {
  const errors = await openExample(page)
  await showKind(page, 'Package library')
  const pkg = page.getByRole('list', { name: 'Package library' })

  // Its item: the outline lists the package's files, and its look is the package's pack's.
  await pkg.getByRole('listitem', { name: 'library:gem', exact: true }).dblclick()
  const itemFiles = filesOf(page, 'library:items/gem')
  await expect(itemFiles.getByRole('row', { name: 'item.json' })).toBeVisible()
  await expect(itemFiles.getByRole('row', { name: 'script.lua' })).toBeVisible()
  await expect(page.getByRole('combobox', { name: 'Item model' })).toHaveValue('gems/gem')
  await expect(page.getByRole('combobox', { name: 'Item model' })).not.toContainText('missing')

  // Its pack: read from the package (nothing reads resource_packs/gems from the project), its pictures there.
  await pkg
    .getByRole('group', { name: 'Resource packs' })
    .getByRole('listitem', { name: 'library:gems' })
    .dblclick()
  await expect(page.getByRole('tab', { name: 'gems', selected: true })).toBeVisible()
  await expect(
    filesOf(page, 'library:resource_packs/gems').getByRole('row', { name: 'pack.json' }),
  ).toBeVisible()
  await expect(page.getByText(/Couldn't read/)).toHaveCount(0)
  await expect(page.locator('img[src^="data:image/png"]').first()).toBeVisible()
  expect(errors).toEqual([])
})

test("draws a dependency's centity with its own pack's looks", async ({ page }) => {
  const errors = await openExample(page)
  // A package kept inside the project's folder, whose centity shows its own pack's item look.
  const pkg: Record<string, string> = {
    'netherforge.json': JSON.stringify({
      formatVersion: 1,
      name: 'Board',
      namespace: 'board',
      version: '1.0.0',
      minecraft: '26.3',
    }),
    'centities/piece/centity.json': JSON.stringify({
      nodes: { piece: { display: { type: 'model', model: 'board/pawn' } } },
    }),
    'resource_packs/board/pack.json': JSON.stringify({ items: { pawn: { texture: 'pawn.png' } } }),
  }
  for (const [path, text] of Object.entries(pkg)) {
    await outside(page, (b, [p, t]) => b.testWrite(p!, t!), [`vendor/board/${path}`, text])
  }
  // A 1×1 PNG.
  const png =
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg=='
  await outside(page, (b, [p, d]) => b.testWriteBytes(p!, d!), [
    'vendor/board/resource_packs/board/textures/pawn.png',
    png,
  ])
  const manifest = JSON.parse((await files(page))['netherforge.json']!)
  manifest.dependencies = { ...manifest.dependencies, board: { path: 'vendor/board' } }
  await outside(page, (b, t) => b.testWrite('netherforge.json', t), JSON.stringify(manifest))

  await showKind(page, 'Package board')
  await page
    .getByRole('list', { name: 'Package board' })
    .getByRole('listitem', { name: 'board:piece' })
    .dblclick()
  // `board/pawn` is the package's own look, in the outline (outside the editor area) as in the editor.
  const node = page.getByRole('treegrid', { name: 'Nodes' }).getByRole('row', { name: /piece/ })
  await expect(node.locator('img[src^="data:image/png"]')).toBeVisible()
  expect(errors).toEqual([])
})

test("adds a template's resources to the project as its own", async ({ page }) => {
  const errors = await openExample(page)
  await showPanel(page, 'Project')
  await showKind(page, 'Items')
  await page.getByRole('button', { name: 'Add template…' }).click()
  await page.getByRole('menuitem', { name: /^RPG mob:/ }).click()

  // The project's own, not a dependency: listed with its items, and what it requires required.
  await expect(page.getByRole('option', { name: 'goblin_ear', exact: true })).toBeVisible()
  const written = await files(page)
  expect(written['centities/goblin/centity.json']).toBeDefined()
  expect(written['modules/slayers/init.lua']).toBeDefined()
  expect(written['tests/goblin_test.lua']).toBeDefined()
  const manifest = JSON.parse(written['netherforge.json']!)
  expect(manifest.dependencies.template_rpg_mob).toBeUndefined()
  expect(manifest.requires.db).toBe(true)
  expect(errors).toEqual([])
})
