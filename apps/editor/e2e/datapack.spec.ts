import { expect } from '@playwright/test'
import { openExample, openResource, outside, showPanel, test } from './helpers'

const FOLDER = 'datapacks/ruby_boulders'
const PLACED = 'data/showcase/worldgen/placed_feature/ruby_boulders.json'

test('a datapack lists its files by overlay, opens one as JSON, and what format finds shows in Problems', async ({
  page,
}) => {
  const errors = await openExample(page)
  await openResource(page, 'ruby_boulders', 'Datapacks')

  // The pack's own files, then each overlay's with the formats it's for.
  await expect(page.getByText('data/ (formats 94 to 121)')).toBeVisible()
  await expect(page.getByText('Overlay mc26_3/ (formats 121)')).toBeVisible()
  const overlay = page.getByRole('list', { name: 'Files in mc1_21' })
  await expect(
    overlay.getByRole('button', {
      name: 'mc1_21/data/showcase/worldgen/configured_feature/ruby_boulder.json',
    }),
  ).toBeVisible()

  // A file opens in a tab of its own, as the JSON it is.
  await page
    .getByRole('list', { name: 'Files in data' })
    .getByRole('button', { name: PLACED })
    .click()
  await expect(
    page
      .getByRole('tablist', { name: 'Open files' })
      .getByRole('tab', { name: 'ruby_boulders.json' }),
  ).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('.monaco-editor .view-lines')).toContainText('showcase:ruby_boulder')

  // Naming an entry the project doesn't have is a problem on that file.
  await outside(
    page,
    (backend, path) =>
      backend.testWrite(path, '{ "feature": "showcase:nothing", "placement": [] }\n'),
    `${FOLDER}/${PLACED}`,
  )
  await showPanel(page, 'Problems')
  await expect(
    page
      .getByRole('list', { name: 'Problems' })
      .getByRole('button', { name: /The project has no configured feature "showcase:nothing"/ }),
  ).toBeVisible()
  expect(errors).toEqual([])
})
