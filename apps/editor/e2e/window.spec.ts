import { expect, type Locator, type Page } from '@playwright/test'
import { openExample, test } from './helpers'

interface Point {
  x: number
  y: number
}

/**
 * The middle of the widest stretch of [bar], halfway down, that none of its
 * children covers, other than the ones marked to drag. Worked out from the
 * children's boxes, so it's empty whatever the fonts make of the bar's
 * width (a fixed fraction of it isn't, on another OS).
 */
async function emptyPoint(bar: Locator): Promise<Point> {
  const box = (await bar.boundingBox())!
  const children = await bar.locator(':scope > :not([data-tauri-drag-region])').all()
  const spans = (await Promise.all(children.map((it) => it.boundingBox())))
    .filter((it) => it !== null && it.width > 0)
    .map((it) => [it!.x, it!.x + it!.width] as const)
    .sort((a, b) => a[0] - b[0])
  let widest = { from: 0, to: 0 }
  let from = box.x
  for (const [start, end] of [...spans, [box.x + box.width, box.x + box.width] as const]) {
    if (start - from > widest.to - widest.from) widest = { from, to: start }
    from = Math.max(from, end)
  }
  // A gap of a few pixels could be the bar's own spacing between two controls.
  expect(widest.to - widest.from, 'the widest empty stretch of the bar').toBeGreaterThan(8)
  return { x: (widest.from + widest.to) / 2, y: box.y + box.height / 2 }
}

/** Whether the element at [point] would drag the window. */
function dragsAt(page: Page, { x, y }: Point): Promise<boolean> {
  // A string, as elsewhere in e2e: these tests don't compile against the DOM.
  return page.evaluate(
    `document.elementFromPoint(${x}, ${y})?.hasAttribute('data-tauri-drag-region') ?? false`,
  )
}

/** The middle of [element]. */
async function middleOf(element: Locator): Promise<Point> {
  const box = (await element.boundingBox())!
  return { x: box.x + box.width / 2, y: box.y + box.height / 2 }
}

// A drag region is only the element marked, not its children, so the empty
// parts of a title bar must be marked themselves, and its controls must not be.
test('the empty parts of the title bar drag the window, on the welcome screen too', async ({
  page,
}) => {
  await page.goto('/?fast')
  await expect(page.getByRole('list', { name: 'Recent projects' })).toBeVisible()
  const bare = page.locator('main > div[data-tauri-drag-region]')
  expect(await dragsAt(page, await emptyPoint(bare))).toBe(true)

  await openExample(page)
  const toolbar = page.getByRole('toolbar', { name: 'Main toolbar' })
  expect(await dragsAt(page, await emptyPoint(toolbar))).toBe(true)
  const settings = toolbar.getByRole('button', { name: 'Settings', exact: true })
  expect(await dragsAt(page, await middleOf(settings))).toBe(false)
})
