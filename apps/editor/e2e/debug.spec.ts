import { expect } from '@playwright/test'
import { filesOf, openCentity, openExample, outside, showPanel, test } from './helpers'

const TOWER = 'centities/tower/script.lua'

test('F9 in the editor sets a breakpoint, a save while paused reloads once it continues, and a rename takes the breakpoint along', async ({
  page,
}) => {
  const errors = await openExample(page)
  await outside(page, (backend) => backend.testConnect(), null)
  await openCentity(page, 'tower')
  const tower = filesOf(page, 'centities/tower')
  await tower.getByRole('row', { name: 'script.lua' }).click()

  // F9 on line 9 (the click handler) sets a breakpoint, drawn in the margin and sent to the plugin.
  const code = page.locator('.monaco-editor .view-lines')
  await expect(code).toContainText('this:on("click"')
  await page.locator('.monaco-editor .view-line').nth(8).click()
  await expect(page.locator('.monaco-editor .line-numbers.active-line-number')).toHaveText('9')
  await page.keyboard.press('F9')
  await expect
    .poll(() => outside(page, (backend) => backend.testBreakpoints(), null))
    .toEqual({ [TOWER]: [9] })
  await showPanel(page, 'Debug')
  await expect(page.getByRole('list', { name: 'Breakpoints' })).toContainText(`${TOWER}:9`)

  // The plugin stops there. (What the Debug panel shows of a stop is Debug.test.tsx's.)
  await outside(page, (backend) => backend.testDebugStop(), null)
  await expect(page.getByRole('status', { name: 'Debugger status' })).toHaveText(
    `Paused on a breakpoint at ${TOWER}:9`,
  )

  // Saving while paused waits: the plugin would refuse the reload until it runs again.
  await page.locator('.monaco-editor .view-line').last().click()
  await page.keyboard.press('End')
  await page.keyboard.type('\n-- while paused')
  await page.keyboard.press('ControlOrMeta+s')
  const reloads = () =>
    outside(
      page,
      (backend) =>
        (backend.bridgeLog as { method: string }[]).filter((it) => it.method === 'reload').length,
      null,
    )
  await expect
    .poll(() => outside(page, (backend) => backend.testFiles()['centities/tower/script.lua'], null))
    .toContain('-- while paused')
  expect(await reloads()).toBe(0)

  await page
    .getByRole('toolbar', { name: 'Debugger' })
    .getByRole('button', { name: 'Continue (F5)' })
    .click()
  await expect(page.getByRole('status', { name: 'Debugger status' })).toHaveText('Running')
  await expect
    .poll(() => outside(page, (backend) => backend.dapLog.map((it) => it.command), null))
    .toContain('continue')
  await expect.poll(reloads).toBe(1)

  // Renaming the file takes its breakpoint along.
  await tower.getByRole('row', { name: 'script.lua' }).click()
  await page.keyboard.press('F2')
  const rename = tower.getByRole('textbox', { name: 'Rename script.lua' })
  await rename.fill('main.lua')
  await rename.press('Enter')
  await expect(page.getByRole('list', { name: 'Breakpoints' })).toContainText(
    'centities/tower/main.lua:9',
  )
  await expect
    .poll(() => outside(page, (backend) => backend.testBreakpoints(), null))
    .toEqual({ [TOWER]: [], 'centities/tower/main.lua': [9] })
  expect(errors).toEqual([])
})
