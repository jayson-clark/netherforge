import { expect, type Locator } from '@playwright/test'
import { canonicalize } from '@netherforge/format'
import {
  chooseMenu,
  files,
  filesOf,
  openCentity,
  openExample,
  openExampleWithAssets,
  openResource,
  save,
  showPanel,
  test,
  TOWER,
  showKind,
} from './helpers'

test('opens the seeded project clean', async ({ page }) => {
  const errors = await openExample(page)
  await expect(page.getByRole('option', { name: 'lamp' })).toBeVisible()
  await expect(page.getByRole('option', { name: 'tower' })).toBeVisible()
  await showKind(page, 'Modules')
  await expect(page.getByRole('option', { name: 'greeter' })).toBeVisible()
  await showPanel(page, 'Problems')
  await expect(page.getByText('No problems.')).toBeVisible()
  // The JSON Schemas the files' $schema points at were written.
  expect(Object.keys(await files(page))).toContain('.netherforge/schema/centity.schema.json')
  expect(errors).toEqual([])
})

test('edits a translation, undoes and redoes it, and saves canonically', async ({ page }) => {
  await openExample(page)
  await openCentity(page, 'tower')
  // The turret: an empty pivot at the tower's top, at y 2.
  await page.getByRole('row', { name: 'turret' }).click()

  const y = page.getByRole('textbox', { name: 'Translation Y' })
  await y.fill('2.5')
  await y.press('Enter')
  await expect(page.getByRole('tab', { name: 'tower (unsaved)' })).toBeVisible()

  // Out of the field, whose own undo Edit → Undo would otherwise be.
  await page.getByRole('row', { name: 'turret' }).click()
  await chooseMenu(page, 'Edit', 'Undo')
  await expect(y).toHaveValue('2')
  await expect(page.getByRole('tab', { name: 'tower', exact: true })).toBeVisible()
  await page.keyboard.press('ControlOrMeta+Shift+z')
  await expect(y).toHaveValue('2.5')

  await page.keyboard.press('ControlOrMeta+s')
  await expect(page.getByRole('tab', { name: 'tower', exact: true })).toBeVisible()

  const written = (await files(page))[TOWER]!
  const model = JSON.parse(written)
  expect(model.nodes.turret.transform.translation).toEqual([0, 2.5, 0])
  // Byte-for-byte what format's canonical writer produces.
  const expected = JSON.parse(canonicalize('centity', TOWER, JSON.stringify(model))).text
  expect(written).toBe(expected)
})

test('an invalid edit shows in Problems, and clicking it focuses the field', async ({ page }) => {
  await openExample(page)
  await openCentity(page, 'tower')
  await page.getByRole('row', { name: 'cap' }).click()
  const block = page.getByRole('textbox', { name: 'Block' })
  await block.fill('minecraft:oak_stairs[facing')
  await block.press('Enter')

  await showPanel(page, 'Problems')
  const problem = page
    .getByRole('list', { name: 'Problems' })
    .getByRole('button', { name: /isn't a block state/ })
  await expect(problem).toBeVisible()
  // Format validated it in the validation worker, not on the page's thread.
  expect(page.workers().map((worker) => worker.url())).toContainEqual(
    expect.stringMatching(/validation\.worker/),
  )

  // Look somewhere else, then follow the problem back.
  await page.getByRole('row', { name: 'base' }).click()
  await expect(page.getByRole('textbox', { name: 'Block' })).toHaveValue('minecraft:stone_bricks')
  await problem.click()
  await expect(page.getByRole('row', { name: 'cap' })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('textbox', { name: 'Block' })).toBeFocused()
})

test('walks the outline with the keyboard, and moves a node by dragging it', async ({ page }) => {
  await openExample(page)
  await openCentity(page, 'tower')
  const nodes = page.getByRole('treegrid', { name: 'Nodes' })
  const row = (name: string) => nodes.getByRole('row', { name })

  // The arrows select as they go (the inspector follows); Left and Right close and open.
  await row('turret').click()
  await page.keyboard.press('ArrowDown')
  await expect(row('cap')).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('textbox', { name: 'Block' })).toHaveValue(
    'minecraft:oak_stairs[facing=east]',
  )
  await page.keyboard.press('ArrowUp')
  await page.keyboard.press('ArrowLeft')
  await expect(row('cap')).toBeHidden()
  await page.keyboard.press('ArrowRight')
  await expect(row('flag')).toHaveAttribute('aria-level', '2')

  // Onto the cap: flag becomes the cap's child, one undo step.
  await row('flag').dragTo(row('cap'))
  await expect(row('flag')).toHaveAttribute('aria-level', '3')
  await save(page, 'tower')
  expect(JSON.parse((await files(page))[TOWER]!).nodes.flag.parent).toBe('cap')
  await chooseMenu(page, 'Edit', 'Undo')
  await expect(row('flag')).toHaveAttribute('aria-level', '2')
})

test('creates a centity', async ({ page }) => {
  await openExample(page)
  await page.getByRole('button', { name: 'New centity' }).click()
  const dialog = page.getByRole('dialog', { name: 'New centity' })
  await dialog.getByRole('textbox').fill('Bad Id')
  await expect(dialog.getByRole('button', { name: 'Create' })).toBeDisabled()
  await dialog.getByRole('textbox').fill('barrel')
  await dialog.getByRole('button', { name: 'Create' }).click()

  await expect(page.getByRole('tab', { name: 'barrel' })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('row', { name: 'root' })).toBeVisible()
  expect(Object.keys(await files(page))).toContain('centities/barrel/centity.json')
})

test("adds a file beside a centity's script", async ({ page }) => {
  const errors = await openExample(page)
  await openCentity(page, 'tower')
  await page.getByRole('button', { name: 'New file beside it' }).click()
  const prompt = page.getByRole('dialog', { name: 'New file beside the script' })
  await prompt.getByRole('textbox').fill('lib/steps.lua')
  await prompt.getByRole('button', { name: 'Create' }).click()
  // Creating it opens it, and it knows what `this` is, as the script does.
  await expect(page.getByRole('tab', { name: 'steps.lua' })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  const project = await files(page)
  expect(project['centities/tower/lib/steps.lua']).toMatch(
    /^local this = this --\[\[@as Centity\]\]\n/,
  )

  // The outline lists every file in the tower's folder, the open one's folder expanded.
  const tower = filesOf(page, 'centities/tower')
  await expect(tower.getByRole('row', { name: 'turns.lua' })).toBeVisible()
  await expect(tower.getByRole('row', { name: 'lib' })).toHaveAttribute('aria-expanded', 'true')
  await expect(tower.getByRole('row', { name: 'steps.lua' })).toBeVisible()

  // Without lua-language-server (the memory backend has none) a script is
  // highlighted and edits as usual; its language features are tested
  // against the real server in src/core/luals/luals.node.test.ts.
  await tower.getByRole('row', { name: 'script.lua' }).click()
  const code = page.locator('.monaco-editor .view-lines')
  await expect(code).toContainText('local turns = require("turns")')
  // Keywords, strings and comments each get a token style of their own.
  const styles = await code.evaluate(
    (lines) =>
      new Set([...lines.querySelectorAll('span[class^="mtk"]')].map((it) => it.className)).size,
  )
  expect(styles).toBeGreaterThan(2)
  await page.locator('.monaco-editor .view-line').last().click()
  await page.keyboard.press('End')
  await page.keyboard.type('\n-- checked')
  await expect(code).toContainText('-- checked')
  expect(errors).toEqual([])
})

test('fits a hitbox to a block model from the client assets', async ({ page }) => {
  await page.goto('/?fast&assets=fixture')
  await page
    .getByRole('list', { name: 'Recent projects' })
    .getByRole('button', { name: /NetherForge Showcase/ })
    .click()
  await openCentity(page, 'tower')
  await expect(page.getByText(/Blocks show as placeholders/)).toHaveCount(0)
  await page.getByRole('row', { name: 'cap' }).click()
  await page.getByRole('button', { name: 'Fit to display' }).click()
  await expect(page.getByText('Fitted to')).toBeVisible()
  await page.keyboard.press('ControlOrMeta+s')
  await expect(page.getByRole('tab', { name: 'tower', exact: true })).toBeVisible()

  const cap = JSON.parse((await files(page))[TOWER]!).nodes.cap
  expect(cap.hitbox).toEqual({
    boxes: [
      { min: [0, 0, 0], max: [1, 0.5, 1] },
      { min: [0.5, 0.5, 0], max: [1, 1, 1] },
    ],
    fittedTo: 'minecraft:oak_stairs[facing=east]',
  })
  // Changing the block afterwards makes the fit stale, which validation reports.
  await page.getByRole('textbox', { name: 'Block' }).fill('minecraft:oak_stairs[facing=west]')
  await page.getByRole('textbox', { name: 'Block' }).press('Enter')
  await showPanel(page, 'Problems')
  await expect(
    page.getByRole('list', { name: 'Problems' }).getByRole('button', { name: /fit it again/ }),
  ).toBeVisible()
})

test('keys an animation in the timeline, with the pointer and the keyboard', async ({ page }) => {
  await openExample(page)
  await openCentity(page, 'tower')
  await page.getByRole('combobox', { name: 'Animation' }).selectOption('spin')
  // `spin` turns the turret, keyed at 0, 0.6, 0.9 and 1.5 seconds.
  const key = (n: number) => page.getByRole('slider', { name: `Keyframe turret.rotation ${n}` })
  await expect(key(2)).toHaveAttribute('aria-valuetext', '0.6s, linear')

  // Clicking a key selects it; the arrows move it a tick (a twentieth of a second) at a time.
  await key(2).click()
  const time = page.getByRole('textbox', { name: 'Keyframe time' })
  await expect(time).toHaveValue('0.6')
  await page.keyboard.press('ArrowRight')
  await page.keyboard.press('ArrowRight')
  await expect(time).toHaveValue('0.7')
  await expect(key(2)).toBeFocused()
  await page.getByRole('combobox', { name: 'Easing' }).selectOption('step')
  await expect(key(2)).toHaveAttribute('aria-valuetext', '0.7s, step')

  // Delete on a focused key removes it (not the selected node).
  await key(3).focus()
  await expect(time).toHaveValue('0.9')
  await page.keyboard.press('Delete')
  await expect(key(4)).toHaveCount(0)

  // A drag retimes a key, one undo step however far it went.
  const last = (await key(3).boundingBox())!
  await page.mouse.move(last.x + last.width / 2, last.y + last.height / 2)
  await page.mouse.down()
  await page.mouse.move(last.x - 40, last.y + last.height / 2, { steps: 6 })
  await page.mouse.up()
  await expect(time).not.toHaveValue('1.5')
  await chooseMenu(page, 'Edit', 'Undo')
  await expect(time).toHaveValue('1.5')

  // The playhead is a slider too.
  const playhead = page.getByRole('slider', { name: 'Playhead' })
  await playhead.focus()
  await page.keyboard.press('End')
  await expect(page.getByLabel('Playhead time')).toHaveText('1.50s')

  await page.keyboard.press('ControlOrMeta+s')
  await expect(page.getByRole('tab', { name: 'tower', exact: true })).toBeVisible()
  const model = JSON.parse((await files(page))[TOWER]!)
  expect(model.nodes.turret).toBeDefined()
  const spin = model.animations.spin.tracks.turret.rotation
  expect(spin.map((k: { time: number }) => k.time)).toEqual([0, 0.7, 1.5])
  expect(spin[1].easing).toBe('step')
})

/**
 * A picture of [canvas] once the camera's damping has settled: two pictures
 * 150 ms apart are the same. Bounded, so a view that never stops moving fails
 * here rather than hanging the test. Damping eases per frame, and a runner
 * drawing WebGL in software draws few: macOS CI took 11 s after an orbit.
 */
async function settled(canvas: Locator): Promise<Buffer> {
  let last = await canvas.screenshot()
  await expect
    .poll(
      async () => {
        const next = await canvas.screenshot()
        const still = next.equals(last)
        last = next
        return still
      },
      { message: "the camera's damping to settle", intervals: [150], timeout: 30_000 },
    )
    .toBe(true)
  return last
}

test("every 3D view draws in one canvas, which keeps each tab's scene and camera", async ({
  page,
  context,
}) => {
  // Two waits for the camera to settle, each up to 30 s on a slow runner.
  test.slow()
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await openExampleWithAssets(page)
  // The pool table: plenty to see, and nothing in it animates by itself (the tower's flag flutters).
  await openCentity(page, 'pool')
  const viewport = page.getByLabel('3D viewport')
  const canvas = viewport.locator('canvas')
  await expect(canvas).toBeVisible()
  await canvas.evaluate((it) => (it.dataset.mark = 'the one'))

  // Orbit the table, and remember what it looks like from there.
  const box = (await canvas.boundingBox())!
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width / 2 + 160, box.y + box.height / 2 - 40, { steps: 8 })
  await page.mouse.up()
  const orbited = await settled(canvas)

  // Another tab's 3D view is the same canvas, moved there: no second WebGL context.
  await openResource(page, 'sparkle')
  const particles = page.getByLabel('Particle preview')
  await expect(particles.locator('canvas[data-mark="the one"]')).toBeVisible()
  await expect(page.locator('[data-viewport] canvas')).toHaveCount(1)

  // Back on the table: the same picture, from where the camera was left.
  await page.getByRole('tab', { name: 'pool', exact: true }).click()
  await expect(canvas).toHaveAttribute('data-mark', 'the one')
  expect((await settled(canvas)).equals(orbited)).toBe(true)

  // A picture of the view, on the clipboard.
  await viewport.getByRole('button', { name: 'Copy picture' }).click()
  await expect(page.getByText('Copied a picture of the viewport')).toBeVisible()
  const types = await page.evaluate('navigator.clipboard.read().then((items) => items[0]?.types)')
  expect(types).toContain('image/png')
  expect(errors).toEqual([])
})
