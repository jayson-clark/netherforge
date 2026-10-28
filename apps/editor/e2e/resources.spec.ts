import { readFileSync } from 'node:fs'
import { expect } from '@playwright/test'
import { canonicalize, fitTextHitbox } from '@netherforge/format'
import { fixtureGlyphAdvances } from '../src/testing/clientFixture'
import {
  chooseMenu,
  files,
  openCentity,
  openExample,
  openExampleWithAssets,
  openResource,
  outside,
  save,
  test,
  showPanel,
  showKind,
} from './helpers'

const SHOP = 'menus/shop/menu.json'
const WELCOME = 'dialogs/welcome/dialog.json'
const PACK = 'resource_packs/ui/pack.json'

const canonical = (kind: string, path: string, text: string) =>
  JSON.parse(canonicalize(kind, path, text)).text as string

test('edits a menu slot item and saves it canonically', async ({ page }) => {
  const errors = await openExample(page)
  await openResource(page, 'shop')
  await page.getByRole('button', { name: 'Slot 11: minecraft:bread' }).click()
  const count = page.getByRole('textbox', { name: 'Count' })
  await expect(count).toHaveValue('4')
  await count.fill('8')
  await count.press('Enter')
  await page.getByRole('textbox', { name: 'Name' }).first().fill('<gold>Fresh bread')
  await page.getByRole('textbox', { name: 'Name' }).first().press('Enter')
  // Item components: a rarity, an attribute modifier and food.
  await page.getByRole('combobox', { name: 'Rarity' }).selectOption('rare')
  await page.getByRole('button', { name: 'Add modifier' }).click()
  const amount = page.getByRole('textbox', { name: 'Modifier 1 amount' })
  await amount.fill('2')
  await amount.press('Enter')
  await page.getByRole('combobox', { name: 'Modifier 1 slot' }).selectOption('off_hand')
  const nutrition = page.getByRole('textbox', { name: 'Nutrition' })
  await nutrition.fill('6')
  await nutrition.press('Enter')
  await expect(page.getByRole('tab', { name: 'shop (unsaved)' })).toBeVisible()
  await save(page, 'shop')

  const written = (await files(page))[SHOP]!
  const slot = JSON.parse(written).slots['11']
  expect(slot.item).toMatchObject({
    kind: 'minecraft:bread',
    count: 8,
    name: '<gold>Fresh bread',
    rarity: 'rare',
    attributeModifiers: [
      { attribute: 'minecraft:attack_damage', amount: 2, operation: 'add_value', slot: 'off_hand' },
    ],
    food: { nutrition: 6, saturation: 0 },
  })
  expect(written).toBe(canonical('menu', SHOP, written))

  // A hopper is one row of five: the shop's slots past it are listed outside the window.
  await page.getByRole('combobox', { name: 'Type' }).selectOption('hopper')
  await expect(page.getByRole('button', { name: 'Slot 4, empty' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Slot 5, empty' })).toHaveCount(0)
  expect(errors).toEqual([])
})

test('drags an item to another slot as one undo step', async ({ page }) => {
  await openExample(page)
  await openResource(page, 'shop')
  const before = (await files(page))[SHOP]!
  await page
    .getByRole('button', { name: 'Slot 11: minecraft:bread' })
    .dragTo(page.getByRole('button', { name: 'Slot 0, empty' }))
  await expect(page.getByRole('button', { name: 'Slot 0: minecraft:bread' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Slot 11, empty' })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'shop (unsaved)' })).toBeVisible()

  await page.keyboard.press('ControlOrMeta+z')
  await expect(page.getByRole('button', { name: 'Slot 11: minecraft:bread' })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'shop', exact: true })).toBeVisible()
  await chooseMenu(page, 'Edit', 'Redo')
  await expect(page.getByRole('button', { name: 'Slot 0: minecraft:bread' })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'shop (unsaved)' })).toBeVisible()
  await save(page, 'shop')
  const slots = JSON.parse((await files(page))[SHOP]!).slots
  expect(Object.keys(slots)).toEqual(['0', '12', '13', '15'])
  expect((await files(page))[SHOP]).not.toBe(before)
})

// The dialog's fields (its keys, type, buttons, where it shows) are DialogEditor.test.tsx's;
// this is swapping its script, which opens the new one in a tab, and the file saved clean.
test('gives a dialog a new script, opened in its tab, and saves it canonically', async ({
  page,
}) => {
  await openExample(page)
  await openResource(page, 'welcome')
  // One script per dialog: swap the example's for a new one.
  await page.getByRole('button', { name: 'Unlink' }).click()
  await page.getByRole('button', { name: 'New script' }).click()
  const prompt = page.getByRole('dialog', { name: 'New script' })
  await expect(prompt.getByRole('textbox')).toHaveValue('script_2.lua')
  await prompt.getByRole('button', { name: 'Create' }).click()
  // Creating the script opens it.
  await expect(page.getByRole('tab', { name: 'script_2.lua' })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await page.getByRole('tab', { name: 'welcome (unsaved)' }).click()
  await save(page, 'welcome')

  const project = await files(page)
  expect(JSON.parse(project[WELCOME]!).script).toEqual({ file: 'script_2.lua' })
  const script = project['dialogs/welcome/script_2.lua']!
  expect(script.startsWith('local this = this --[[@as Dialog]]\n')).toBe(true)
  expect(script).toContain('this:on("press", function(event)')
  expect(project[WELCOME]).toBe(canonical('dialog', WELCOME, project[WELCOME]!))
  await showPanel(page, 'Problems')
  await expect(page.getByText('No problems.')).toBeVisible()
})

test('imports a texture into a pack and uses it as a menu skin', async ({ page }) => {
  await openExample(page)
  await openResource(page, 'ui')
  const png = readFileSync(
    new URL('../../../examples/showcase/resource_packs/ui/textures/gui/shop.png', import.meta.url),
  )
  await page.getByLabel('PNG files to import').setInputFiles({
    name: 'Banner.png',
    mimeType: 'image/png',
    buffer: png,
  })
  const importing = page.getByRole('dialog', { name: 'Import Banner.png' })
  await expect(importing.getByRole('textbox')).toHaveValue('gui/banner.png')
  await importing.getByRole('button', { name: 'Import' }).click()

  // The gallery's sections are grids of cards: the imported picture is the one picked.
  await expect(page.getByRole('row', { name: /Texture gui\/banner\.png/ })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  expect(
    await outside(page, (b) => b.testBytes('resource_packs/ui/textures/gui/banner.png'), null),
  ).toBe(png.toString('base64'))
  await page.getByRole('button', { name: 'skin', exact: true }).click()
  const create = page.getByRole('dialog', { name: 'New skin' })
  await expect(create.getByRole('textbox')).toHaveValue('banner')
  await create.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByRole('row', { name: 'skin banner', exact: true })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await expect(page.getByRole('img', { name: 'Skin over the window' })).toBeVisible()
  await save(page, 'ui')

  await openResource(page, 'shop')
  await page.getByRole('combobox', { name: 'Skin' }).selectOption('ui/banner')
  await expect(
    page.getByRole('img', { name: 'Menu window' }).locator('img[data-skin]'),
  ).toHaveAttribute('src', /^data:image\/png;base64,/)
  await save(page, 'shop')

  const project = await files(page)
  expect(JSON.parse(project[PACK]!).skins.banner).toEqual({ texture: 'gui/banner.png' })
  expect(JSON.parse(project[SHOP]!).skin).toBe('ui/banner')
  expect(project[PACK]).toBe(canonical('resource_pack', PACK, project[PACK]!))
  await showPanel(page, 'Problems')
  await expect(page.getByText('No problems.')).toBeVisible()
})

test('fits a fixed text display hitbox with the imported glyph advances', async ({ page }) => {
  await openExampleWithAssets(page)
  await openCentity(page, 'lamp')
  await page.getByRole('row', { name: 'label' }).click()
  const fit = page.getByRole('button', { name: 'Fit to display' })
  await page.getByRole('checkbox', { name: 'Hitbox enabled' }).check()
  // A billboard turns to face each viewer: it has no one shape to fit.
  await expect(fit).toBeDisabled()
  await page.getByRole('combobox', { name: 'Billboard' }).selectOption('fixed')
  await fit.click()
  await expect(page.getByText('Fitted to')).toBeVisible()
  await save(page, 'lamp')

  const label = JSON.parse((await files(page))['centities/lamp/centity.json']!).nodes.label
  const expected = JSON.parse(
    fitTextHitbox(
      JSON.stringify({ type: 'text', text: '<yellow>Lamp', billboard: 'fixed' }),
      JSON.stringify(fixtureGlyphAdvances),
      null,
    ),
  ).box
  expect(label.hitbox).toEqual({ boxes: [expected], fittedTo: 'text:<yellow>Lamp' })
  // "Lamp" is four 6-pixel glyphs: 24 pixels, a font pixel being 1/40 block.
  expect(expected.max[0] - expected.min[0]).toBeCloseTo((24 + 1) / 40)
  await showPanel(page, 'Problems')
  await expect(page.getByText('No problems.')).toBeVisible()
})

test('builds a particle effect: an emitter, a ring, a radius curve keyed on the timeline', async ({
  page,
}) => {
  await openExample(page)
  await showKind(page, 'Particle effects')
  await page.getByRole('button', { name: 'New particle effect' }).click()
  const create = page.getByRole('dialog', { name: 'New particle effect' })
  await create.getByRole('textbox').fill('pulse')
  await create.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByRole('tab', { name: 'pulse' })).toHaveAttribute('aria-selected', 'true')

  await page.getByRole('button', { name: 'Add emitter' }).click()
  const add = page.getByRole('dialog', { name: 'New emitter' })
  await add.getByRole('textbox').fill('minecraft:end_rod')
  await add.getByRole('button', { name: 'Add' }).click()
  await expect(page.getByRole('row', { name: 'emitter' })).toHaveAttribute('aria-selected', 'true')
  await page.getByRole('combobox', { name: 'Shape' }).selectOption('ring')
  await page.getByRole('combobox', { name: 'Add curve' }).selectOption('radius')
  // The radius moved into the curve: the inspector now shows its key.
  await expect(page.getByRole('textbox', { name: 'Value' })).toHaveValue('1')

  // Seek to the end of the timeline and key the radius there.
  const playhead = page.getByRole('slider', { name: 'Playhead' })
  await playhead.focus()
  await page.keyboard.press('End')
  await expect(page.getByLabel('Playhead tick')).toHaveText('20 / 20')
  const curve = page.getByLabel('emitter radius curve', { exact: true })
  const box = (await curve.boundingBox())!
  // A double-click keys the curve where it is (a press on a row moves the playhead there).
  await curve.dblclick({ position: { x: box.width - 1, y: 5 } })
  const value = page.getByRole('textbox', { name: 'Value' })
  await expect(page.getByRole('textbox', { name: 'Tick' })).toHaveValue('20')
  await value.fill('3')
  await value.press('Enter')
  await save(page, 'pulse')

  const path = 'particles/pulse/effect.json'
  const written = (await files(page))[path]!
  expect(JSON.parse(written).emitters.emitter).toEqual({
    particle: 'minecraft:end_rod',
    burst: 1,
    shape: { type: 'ring' },
    curves: {
      radius: [
        { time: 0, value: 1 },
        { time: 20, value: 3 },
      ],
    },
  })
  expect(written).toBe(canonical('particle_effect', path, written))
  await showPanel(page, 'Problems')
  await expect(page.getByText('No problems.')).toBeVisible()

  // The preview and what's selected are the document's view: another tab and back keeps them.
  await openCentity(page, 'tower')
  await page.getByRole('tab', { name: 'pulse' }).click()
  await expect(page.getByLabel('Playhead tick')).toHaveText('20 / 20')
  await expect(page.getByRole('textbox', { name: 'Tick' })).toHaveValue('20')

  // Played on the dev server once it's up, looping as the timeline's toggle says, then stopped.
  const play = page.getByRole('button', { name: 'Play on server' })
  await expect(play).toBeDisabled()
  await outside(page, (backend) => backend.testConnect(), null)
  await page.getByRole('checkbox', { name: 'Loop preview' }).check()
  await play.click()
  await page.getByRole('button', { name: 'Stop', exact: true }).click()
  await expect
    .poll(() => outside(page, (backend) => backend.bridgeLog.slice(-2), null))
    .toEqual([
      { method: 'play_particle_effect', params: { effect: 'pulse', loop: true } },
      { method: 'stop_particle_effects' },
    ])
})

test('a migration opens as SQL, highlighted', async ({ page }) => {
  const errors = await openExample(page)
  await showPanel(page, 'Project')
  await showKind(page, 'Migrations')
  await page.getByRole('option', { name: '001_init', exact: true }).first().dblclick()
  await expect(
    page.getByRole('tablist', { name: 'Open files' }).getByRole('tab', { name: '001_init.sql' }),
  ).toHaveAttribute('aria-selected', 'true')

  const code = page.locator('.monaco-editor .view-lines')
  await expect(code).toContainText('CREATE TABLE visits')
  // Keywords, types and comments each get a token style of their own (plain text has one).
  // Monaco tokenizes after the text shows, so on a slow runner the first look sees plain text.
  await expect
    .poll(() =>
      code.evaluate(
        (lines) =>
          new Set([...lines.querySelectorAll('span[class^="mtk"]')].map((it) => it.className)).size,
      ),
    )
    .toBeGreaterThan(2)
  expect(errors).toEqual([])
})
