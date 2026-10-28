import { expect, type Page } from '@playwright/test'
import {
  chooseMenu,
  files,
  filesOf,
  openCentity,
  openExample,
  openResource,
  outside,
  showPanel,
  test,
  TOWER,
} from './helpers'

async function makeDirty(page: Page) {
  await openCentity(page, 'tower')
  // The turret: an empty pivot at the tower's top, at [0, 2, 0].
  await page.getByRole('row', { name: 'turret' }).click()
  const z = page.getByRole('textbox', { name: 'Translation Z' })
  await z.fill('4')
  await z.press('Enter')
  await expect(page.getByRole('tab', { name: 'tower (unsaved)' })).toBeVisible()
}

/** Another program rewrites the tower's name on disk. */
async function writeTheirs(page: Page, name: string) {
  await outside(
    page,
    (backend, arg) => {
      const text = backend.testFiles()[arg.path]!.replace('"Stone tower"', JSON.stringify(arg.name))
      backend.testWrite(arg.path, text)
    },
    { path: TOWER, name },
  )
}

test('an external change while dirty: keep mine, or diff and take theirs', async ({ page }) => {
  await openExample(page)
  await makeDirty(page)
  await writeTheirs(page, 'Their tower')
  const banner = page.getByRole('alert', { name: 'File changed on disk' })
  await expect(banner).toBeVisible()

  // Keep mine: the banner goes, and saving writes my version over theirs.
  await banner.getByRole('button', { name: 'Keep mine' }).click()
  await expect(banner).toHaveCount(0)
  await page.keyboard.press('ControlOrMeta+s')
  await expect(page.getByRole('tab', { name: 'tower', exact: true })).toBeVisible()
  let saved = JSON.parse((await files(page))[TOWER]!)
  expect(saved.name).toBe('Stone tower')
  expect(saved.nodes.turret.transform.translation).toEqual([0, 2, 4])

  // Again, this time looking at the diff and taking theirs.
  const z = page.getByRole('textbox', { name: 'Translation Z' })
  await z.fill('5')
  await z.press('Enter')
  await writeTheirs(page, 'Their tower')
  await banner.getByRole('button', { name: 'Diff' }).click()
  const diff = page.getByRole('dialog', { name: /disk \(left\) vs\. yours/ })
  await expect(diff).toBeVisible()
  await diff.getByRole('button', { name: 'Take theirs' }).click()

  await expect(banner).toHaveCount(0)
  await expect(page.getByText('Their tower')).toBeVisible()
  await expect(page.getByRole('tab', { name: 'tower', exact: true })).toBeVisible()
  await expect(z).toHaveValue('4')
  saved = JSON.parse((await files(page))[TOWER]!)
  expect(saved.name).toBe('Their tower')
})

// What a change on disk does to a document is the store's (workspace.test.ts); this is the
// watcher's event reaching the editor on screen.
test('a clean document shows a change on disk', async ({ page }) => {
  await openExample(page)
  await openCentity(page, 'tower')
  await writeTheirs(page, 'Renamed outside')
  await expect(page.getByText('Renamed outside')).toBeVisible()
})

test('a console script error opens the script at its line', async ({ page }) => {
  await openExample(page)
  // An error in the file beside the tower's script that it requires: located there.
  await outside(
    page,
    (backend) =>
      backend.testBridgeEvent('console', {
        items: [
          {
            type: 'script_error',
            message: "attempt to call a nil value (method 'play_animation')",
            source: { file: 'centities/tower/turns.lua', line: 11 },
            traceback:
              'stack traceback:\n\tcentities/tower/turns.lua:11: in function <centities/tower/turns.lua:10>',
          },
        ],
      }),
    null,
  )
  await showPanel(page, 'Console')
  const console = page.getByRole('log', { name: 'Console' })
  await expect(console.getByText(/attempt to call a nil value/)).toBeVisible()
  await console.getByRole('button', { name: 'Open centities/tower/turns.lua:11' }).click()

  await expect(page.getByRole('tab', { name: 'turns.lua' })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await expect(page.locator('.monaco-editor .line-numbers.active-line-number')).toHaveText('11')
  await expect(page.locator('.monaco-editor .view-lines')).toContainText(
    'this:play_animation("spin")',
  )
})

test('runs the dev server, hot-reloads on save and spawns', async ({ page }) => {
  await openExample(page)
  await page.getByRole('button', { name: 'Start server' }).click()
  const eula = page.getByRole('dialog', { name: 'Minecraft EULA' })
  await expect(eula.getByRole('link', { name: 'Read the Minecraft EULA' })).toBeVisible()
  await expect(eula.getByRole('button', { name: 'Accept and start' })).toBeDisabled()
  await eula.getByRole('checkbox').check()
  await eula.getByRole('button', { name: 'Accept and start' }).click()

  await expect(page.getByRole('status', { name: 'Server status' })).toContainText('Running')
  await expect(page.getByRole('img', { name: 'Bridge connected' })).toBeVisible()

  // Save a script: the plugin is told to reload just that file.
  await openResource(page, 'tower')
  await filesOf(page, 'centities/tower').getByRole('row', { name: 'script.lua' }).click()
  await page.locator('.monaco-editor .view-lines').click()
  await page.keyboard.press('ControlOrMeta+End')
  await page.keyboard.type('\n-- edited')
  await page.keyboard.press('ControlOrMeta+s')
  await showPanel(page, 'Console')
  await showPanel(page, 'Console')
  await expect(
    page.getByRole('log', { name: 'Console' }).getByText(/Reloaded centity:tower/),
  ).toBeVisible()
  expect((await files(page))['centities/tower/script.lua']).toContain('-- edited')

  await openCentity(page, 'tower')
  await page.getByRole('button', { name: 'Spawn at me' }).click()
  await showPanel(page, 'Instances')
  await expect(
    page
      .getByRole('table', { name: 'Instances' })
      .getByRole('cell', { name: 'tower', exact: true }),
  ).toBeVisible()

  await page.getByRole('button', { name: 'Stop server' }).click()
  await expect(page.getByRole('status', { name: 'Server status' })).toContainText('Stopped')
})

/** The example open on the tower, the setup the workbench flows below start from. */
async function openTower(page: Page) {
  await openExample(page)
  await openCentity(page, 'tower')
}

/** The open-files tab named [name] (exactly). */
const tab = (page: Page, name: string) =>
  page.getByRole('tablist', { name: 'Open files' }).getByRole('tab', { name, exact: true })

const palette = (page: Page) => page.getByRole('textbox', { name: /run a command/ })

test("the outline's Files pane makes a file in place, renames it with F2 and duplicates it", async ({
  page,
}) => {
  await openTower(page)
  const tower = filesOf(page, 'centities/tower')

  // A new file through the tree's own input, checked as it's typed, opens in a tab.
  await tower.getByRole('row', { name: 'script.lua' }).click({ button: 'right' })
  await page.getByRole('menuitem', { name: 'New File…' }).click()
  const name = tower.getByRole('textbox', { name: 'New file name' })
  await name.fill('bad-name.lua')
  await expect(tower.getByRole('alert')).toContainText('Lua file')
  await name.fill('util/helpers.lua')
  await name.press('Enter')
  await expect(tab(page, 'helpers.lua')).toHaveAttribute('aria-selected', 'true')
  expect(Object.keys(await files(page))).toContain('centities/tower/util/helpers.lua')

  await tower.getByRole('row', { name: 'helpers.lua' }).click()
  await page.keyboard.press('F2')
  const rename = tower.getByRole('textbox', { name: 'Rename helpers.lua' })
  await rename.fill('steps.lua')
  await rename.press('Enter')
  await expect(tower.getByRole('row', { name: 'steps.lua' })).toBeVisible()
  await page.keyboard.press('ControlOrMeta+d')
  await expect(tower.getByRole('row', { name: 'steps_copy.lua' })).toBeVisible()
})

test('quick open jumps to a resource, and Ctrl+Tab and Ctrl+Shift+Tab step through the tabs', async ({
  page,
}) => {
  await openTower(page)
  await filesOf(page, 'centities/tower').getByRole('row', { name: 'script.lua' }).click()
  await expect(tab(page, 'script.lua')).toHaveAttribute('aria-selected', 'true')

  await page.keyboard.press('ControlOrMeta+p')
  await page.getByRole('textbox', { name: 'Go to resource or file' }).fill('shop')
  await page.keyboard.press('Enter')
  await expect(tab(page, 'shop')).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('treegrid', { name: 'Slots' })).toBeVisible()

  // In the strip's order, round from the last to the first, and back.
  await page.keyboard.press('Control+Tab')
  await expect(tab(page, 'tower')).toHaveAttribute('aria-selected', 'true')
  await page.keyboard.press('Control+Shift+Tab')
  await page.keyboard.press('Control+Shift+Tab')
  await expect(tab(page, 'script.lua')).toHaveAttribute('aria-selected', 'true')
})

test('the settings open as a tab, with their pages in the outline', async ({ page }) => {
  await openExample(page)
  await page.getByRole('button', { name: 'Settings', exact: true }).click()
  await expect(tab(page, 'Settings')).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByRole('region', { name: 'Minecraft settings' })).toBeVisible()
  await page
    .getByRole('treegrid', { name: 'Settings pages' })
    .getByRole('row', { name: 'Agents' })
    .click()
  await expect(page.getByRole('region', { name: 'Agents settings' })).toBeVisible()
})

test('the palette finds commands only after ">", and runs one', async ({ page }) => {
  await openTower(page)
  const matches = page.getByRole('listbox', { name: 'Matches' })
  await page.keyboard.press('ControlOrMeta+Shift+p')
  await expect(palette(page)).toHaveValue('>')
  await palette(page).fill('>server')
  await expect(matches.getByRole('option', { name: /^Editor Settings: Server(?!-)/ })).toBeVisible()
  await expect(matches.getByRole('option', { name: /^tower/ })).toHaveCount(0)
  await page.keyboard.press('Escape')

  await page.keyboard.press('ControlOrMeta+p')
  await palette(page).fill('show console')
  await matches.getByRole('option', { name: /^Show Console/ }).click()
  await expect(
    page.getByRole('region', { name: 'Bottom dock' }).getByRole('tab', { name: /Console/ }),
  ).toHaveAttribute('aria-selected', 'true')
})

test('a project opens again as it was left: its tabs, the active one, and its docks', async ({
  page,
}) => {
  await openTower(page)
  await page.keyboard.press('ControlOrMeta+p')
  await page.getByRole('textbox', { name: 'Go to resource or file' }).fill('shop')
  await page.keyboard.press('Enter')
  await expect(tab(page, 'shop')).toHaveAttribute('aria-selected', 'true')
  await page.getByRole('button', { name: 'Settings', exact: true }).click()
  await tab(page, 'tower').click()

  // The outline resizes by dragging its splitter or with its arrow keys.
  const outlineWidth = async () => (await page.locator('#outline').boundingBox())!.width
  const splitter = page.getByRole('separator', { name: 'Resize the left dock' })
  const grip = (await splitter.boundingBox())!
  // A 1px line that takes its space (the previews' pixels depend on the layout around them).
  expect(grip.width).toBe(1)
  await page.mouse.move(grip.x + grip.width / 2, grip.y + 200)
  await page.mouse.down()
  await page.mouse.move(grip.x + grip.width / 2 + 100, grip.y + 200, { steps: 5 })
  await page.mouse.up()
  await expect.poll(outlineWidth).toBeCloseTo(340, 0)
  await splitter.focus()
  await page.keyboard.press('ArrowLeft')
  await expect.poll(outlineWidth).toBeLessThan(340)
  const resized = await outlineWidth()
  await page.getByRole('button', { name: 'Right dock', exact: true }).click()
  await expect(page.getByRole('region', { name: 'Inspector dock' })).toBeHidden()

  await page.keyboard.press('ControlOrMeta+Shift+p')
  await palette(page).fill('>close project')
  await page.keyboard.press('Enter')
  await page
    .getByRole('list', { name: 'Recent projects' })
    .getByRole('button', { name: /NetherForge Showcase/ })
    .click()
  await expect(tab(page, 'tower')).toHaveAttribute('aria-selected', 'true')
  await expect(tab(page, 'shop')).toBeVisible()
  await expect(tab(page, 'Settings')).toBeVisible()
  await expect(page.getByRole('region', { name: 'Inspector dock' })).toBeHidden()
  await expect.poll(outlineWidth).toBeCloseTo(resized, 0)
})

test('Cmd/Ctrl +/- scale the UI, remembered across starts, and 0 resets it', async ({ page }) => {
  await openExample(page)
  // CSS zoom on the memory backend (the app asks the webview).
  const zoom = () => page.evaluate<string>('document.documentElement.style.zoom')
  await page.keyboard.press('ControlOrMeta+Equal')
  await page.keyboard.press('ControlOrMeta+Equal')
  await page.keyboard.press('ControlOrMeta+Minus')
  await expect.poll(zoom).toBe('1.1')
  await page.reload()
  await expect(page.getByRole('list', { name: 'Recent projects' })).toBeVisible()
  await expect.poll(zoom).toBe('1.1')
  await page.keyboard.press('ControlOrMeta+0')
  await expect.poll(zoom).toBe('1')
})

test('the menu bar toggles a dock, showing it checked, and opens quick open', async ({ page }) => {
  await openExample(page)
  // Save and undo live in the menus and on their keys, not the toolbar.
  const toolbar = page.getByRole('toolbar', { name: 'Main toolbar' })
  await expect(toolbar.getByRole('button', { name: 'Save', exact: true })).toHaveCount(0)
  await expect(toolbar.getByRole('button', { name: 'Undo' })).toHaveCount(0)

  await chooseMenu(page, 'View', 'Right Dock')
  await expect(page.getByRole('region', { name: 'Inspector dock' })).toBeHidden()
  await page.getByRole('menubar').getByRole('menuitem', { name: 'View', exact: true }).click()
  await expect(
    page.getByRole('menu', { name: 'View' }).getByRole('menuitemcheckbox', { name: /^Right Dock/ }),
  ).toHaveAttribute('aria-checked', 'false')
  await page.keyboard.press('Escape')
  await expect(page.getByRole('menu', { name: 'View' })).toBeHidden()
  await page.keyboard.press('ControlOrMeta+Alt+b')
  await expect(page.getByRole('region', { name: 'Inspector dock' })).toBeVisible()

  await chooseMenu(page, 'Edit', 'Go to Resource or File')
  await expect(page.getByRole('textbox', { name: 'Go to resource or file' })).toBeFocused()
})

test('Edit → Select All acts on the field being typed in, and on nothing outside one', async ({
  page,
}) => {
  await openTower(page)
  // The menu never takes focus, so it acts on the field.
  await page.getByRole('row', { name: 'turret' }).click()
  const y = page.getByRole('textbox', { name: 'Translation Y' })
  await y.fill('2.5')
  await chooseMenu(page, 'Edit', 'Select All')
  await expect(y).toBeFocused()
  await page.keyboard.type('3')
  await expect(y).toHaveValue('3')
  await y.press('Enter')
  await expect(tab(page, 'tower (unsaved)')).toBeVisible()

  // Outside a field there is nothing to select all of, and the page itself isn't selected.
  await page.getByRole('row', { name: 'turret' }).click()
  // (The field just left still reports its range, so start from no selection.)
  await page.evaluate('getSelection().removeAllRanges()')
  await page.keyboard.press('ControlOrMeta+a')
  await chooseMenu(page, 'Edit', 'Select All')
  expect(await page.evaluate('String(getSelection())')).toBe('')
})

test("closing an unsaved tab asks first: cancel keeps it, Don't save closes it", async ({
  page,
}) => {
  await openExample(page)
  await makeDirty(page)
  const dirty = page.getByRole('tab', { name: 'tower (unsaved)' })
  const dialog = page.getByRole('dialog', { name: 'Unsaved changes' })

  await dirty.hover()
  await dirty.getByRole('button', { name: 'Close tower' }).click()
  await dialog.getByRole('button', { name: 'Cancel' }).click()
  await expect(dirty).toBeVisible()

  await dirty.hover()
  await dirty.getByRole('button', { name: 'Close tower' }).click()
  await dialog.getByRole('button', { name: "Don't save" }).click()
  await expect(page.getByRole('tab', { name: /^tower/ })).toHaveCount(0)
  expect(JSON.parse((await files(page))[TOWER]!).nodes.turret.transform.translation).toEqual([
    0, 2, 0,
  ])
})

test('Cmd/Ctrl+W asks about unsaved edits, in an editor and in a script', async ({ page }) => {
  await openExample(page)
  await makeDirty(page)
  const dialog = page.getByRole('dialog', { name: 'Unsaved changes' })

  await page.keyboard.press('ControlOrMeta+w')
  await dialog.getByRole('button', { name: "Don't save" }).click()
  await expect(page.getByRole('tab', { name: /^tower/ })).toHaveCount(0)

  await openCentity(page, 'tower')
  await page.getByRole('row', { name: 'script.lua' }).click()
  await page.locator('.monaco-editor .view-line').last().click()
  await page.keyboard.type('\n-- edited')
  await expect(page.getByRole('tab', { name: 'script.lua (unsaved)' })).toBeVisible()
  await page.keyboard.press('ControlOrMeta+w')
  await dialog.getByRole('button', { name: "Don't save" }).click()
  await expect(page.getByRole('tab', { name: /^script\.lua/ })).toHaveCount(0)
})

test('leaving a project with unsaved edits asks first, and Open Recent brings it back without them', async ({
  page,
}) => {
  await openExample(page)
  await makeDirty(page)

  await chooseMenu(page, 'File', 'Close Project')
  const dialog = page.getByRole('dialog', { name: 'Unsaved changes' })
  await dialog.getByRole('button', { name: 'Cancel' }).click()
  await expect(tab(page, 'tower (unsaved)')).toBeVisible()
  await chooseMenu(page, 'File', 'New Project')
  await dialog.getByRole('button', { name: "Don't save" }).click()
  await expect(page.getByRole('form', { name: 'Create project' })).toBeVisible()

  await page
    .getByRole('menubar', { name: 'Menu' })
    .getByRole('menuitem', { name: 'File', exact: true })
    .click()
  await page
    .getByRole('menu', { name: 'File' })
    .getByRole('menuitem', { name: 'Open Recent' })
    .hover()
  await page
    .getByRole('menu', { name: 'Open Recent' })
    .getByRole('menuitem', { name: /NetherForge Showcase/ })
    .click()
  await expect(tab(page, 'tower')).toBeVisible()
  expect(JSON.parse((await files(page))[TOWER]!).nodes.turret.transform.translation).toEqual([
    0, 2, 0,
  ])
})

test('dock panels drag between docks, split a group, and the layout resets', async ({ page }) => {
  await openExample(page)
  await openCentity(page, 'tower')
  const bottom = page.getByRole('region', { name: 'Bottom dock' })
  const right = page.getByRole('region', { name: 'Right dock' })
  await expect(bottom.getByRole('tablist')).toHaveCount(3)

  // Onto the inspector's tab: into the right dock's group.
  await bottom
    .getByRole('tab', { name: 'Console' })
    .dragTo(right.getByRole('tab', { name: 'Inspector' }))
  await expect(right.getByRole('tab', { name: 'Console' })).toHaveAttribute('aria-selected', 'true')
  await expect(right.getByRole('log', { name: 'Console' })).toBeVisible()
  await expect(bottom.getByRole('tab', { name: 'Console' })).toHaveCount(0)

  // Onto the lower edge of the right dock's panel: the group splits, one above the other.
  const body = right.getByRole('tabpanel')
  const box = (await body.boundingBox())!
  await right.getByRole('tab', { name: 'Console' }).dragTo(body, {
    targetPosition: { x: box.width / 2, y: box.height - 8 },
  })
  await expect(right.getByRole('tablist')).toHaveCount(2)
  await expect(right.getByRole('region', { name: 'Inspector dock' })).toBeVisible()
  await expect(right.getByRole('log', { name: 'Console' })).toBeVisible()

  // Into a hidden dock: it shows again with the panel.
  await page.keyboard.press('ControlOrMeta+b')
  await expect(page.getByRole('region', { name: 'Left dock' })).toBeHidden()
  // Its strip shows only once a drag has started, so the drag is by hand.
  await bottom.getByRole('tab', { name: 'Problems' }).hover()
  await page.mouse.down()
  await page.mouse.move(400, 300, { steps: 4 })
  await page.getByText('Left dock', { exact: true }).hover()
  await page.mouse.up()
  await expect(
    page.getByRole('region', { name: 'Left dock' }).getByRole('tab', { name: /Problems/ }),
  ).toBeVisible()

  await chooseMenu(page, 'View', 'Reset Layout')
  await expect(right.getByRole('tablist')).toHaveCount(1)
  await expect(bottom.getByRole('tab', { name: 'Console' })).toBeVisible()
  await expect(
    page.getByRole('region', { name: 'Left dock' }).getByRole('tab', { name: /Problems/ }),
  ).toHaveCount(0)
})
