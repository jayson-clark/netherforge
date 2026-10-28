import { expect } from '@playwright/test'
import { openExample, outside, showPanel, test } from './helpers'

test('the profiler shows where the dev server’s ticks go and opens a handler at its line', async ({
  page,
}) => {
  const errors = await openExample(page)
  await showPanel(page, 'Profiler')
  await expect(
    page.getByRole('tabpanel', { name: 'Profiler' }).getByText(/Start the dev server/),
  ).toBeVisible()

  // The bridge comes up: the editor subscribes, and the (fake) plugin streams a batch a second.
  await outside(page, (backend) => backend.testConnect(), null)
  await expect
    .poll(() => outside(page, (backend) => backend.bridgeLog, null))
    .toContainEqual({ method: 'profiler_subscribe', params: { on: true } })

  const functions = page.getByRole('table', { name: 'Functions' })
  const tower = functions.getByRole('row').filter({ hasText: 'centity tower' })
  await expect(tower.first()).toBeVisible()

  // Sorting, the tick chart and the scopes are Profiler.test.tsx's; opening Monaco at a line is here.
  await tower.first().getByRole('button', { name: 'Open centities/tower/script.lua:12' }).click()
  await expect(page.getByRole('tab', { name: 'script.lua' })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  await expect(page.locator('.monaco-editor .line-numbers.active-line-number')).toHaveText('12')
  expect(errors).toEqual([])
})
