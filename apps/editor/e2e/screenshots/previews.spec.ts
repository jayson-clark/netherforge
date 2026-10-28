import { expect, type Locator, type Page } from '@playwright/test'
import { openExample, openResource, test } from '../helpers'

/**
 * The pixel-exact previews (a menu's window with its skin, a skin over its
 * window, a dialog, text with glyphs, a tooltip), compared with committed
 * pictures. They're drawn from format's numbers, so a picture that changes is
 * a behaviour change: review the new one (`pnpm test:screenshots --update`
 * writes it) as you would a golden file.
 *
 * Only in the pinned Playwright container (see playwright.config.ts): a
 * browser on any other machine draws text with other fonts.
 */
test.skip(
  !process.env.NETHERFORGE_SCREENSHOT_SERVER,
  'screenshots are taken in the pinned Playwright container: pnpm test:screenshots',
)

/** [preview] once every picture in it has loaded, as a screenshot named [name]. */
async function expectPicture(preview: Locator, name: string) {
  await expect(preview).toBeVisible()
  // Every picture decoded (these tests don't compile against the DOM, so the element is untyped).
  await expect
    .poll(() =>
      preview.evaluate((element) =>
        [...element.querySelectorAll('img')].every(
          (img: { complete: boolean; naturalWidth: number }) =>
            img.complete && img.naturalWidth > 0,
        ),
      ),
    )
    .toBe(true)
  await expect(preview).toHaveScreenshot(`${name}.png`)
}

/** Picks a pack entry in the pack editor's gallery: `skin shop`, `glyph coin`. */
async function pickEntry(page: Page, name: string) {
  await page.getByRole('row', { name, exact: true }).click()
  await expect(page.getByRole('row', { name, exact: true })).toHaveAttribute(
    'aria-selected',
    'true',
  )
}

test("a menu's window: its skin, title and slots", async ({ page }) => {
  await openExample(page)
  await openResource(page, 'shop')
  await expectPicture(page.getByRole('img', { name: 'Menu window' }), 'menu-window')
})

test('a skin over the window it was drawn for', async ({ page }) => {
  await openExample(page)
  await openResource(page, 'ui')
  await pickEntry(page, 'skin shop')
  await expectPicture(page.getByRole('img', { name: 'Skin over the window' }), 'skin')
})

test('a dialog', async ({ page }) => {
  await openExample(page)
  await openResource(page, 'welcome')
  await expectPicture(page.getByRole('region', { name: 'Dialog preview' }), 'dialog')
})

test('text with a glyph in it, and a tooltip', async ({ page }) => {
  await openExample(page)
  await openResource(page, 'ui')
  await pickEntry(page, 'glyph coin')
  await expectPicture(page.getByLabel('Glyph preview'), 'glyph-text')
  await pickEntry(page, 'tooltip fancy')
  await expectPicture(page.getByLabel('Tooltip preview'), 'tooltip')
})
