import { expect, type Page } from '@playwright/test'
import { canonicalize } from '@netherforge/format'
import { files, openExample, openResource, save, showPanel, test, showKind } from './helpers'

const sliceHeight = async (page: Page) =>
  (await page.getByTestId('terrain-slice').boundingBox())?.height ?? 0

test('a new dimension type is edited in its form and written canonically', async ({ page }) => {
  const errors = await openExample(page)
  await showKind(page, 'Dimension types')
  await page.getByRole('button', { name: 'New dimension type' }).click()
  const create = page.getByRole('dialog', { name: 'New dimension type' })
  await create.getByRole('textbox').fill('caves')
  await create.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByRole('tab', { name: 'caves' })).toHaveAttribute('aria-selected', 'true')
  // The template is a deep world: -128 to 319.
  const range = page.getByTestId('dimension-range')
  await expect(range).toHaveText('y -128 to 319')

  const inspector = page.getByRole('complementary', { name: 'Inspector' })
  const bottom = inspector.getByRole('textbox', { name: 'Bottom (min Y)', exact: true })
  await bottom.fill('-256')
  await bottom.press('Enter')
  const height = inspector.getByRole('textbox', { name: 'Height', exact: true })
  await height.fill('640')
  await height.press('Enter')
  await expect(range).toHaveText('y -256 to 383')
  await inspector.getByRole('combobox', { name: 'Beds work' }).selectOption('off')

  await save(page, 'caves')
  const path = 'dimension_types/caves.json'
  const written = (await files(page))[path]!
  expect(written).toBe(JSON.parse(canonicalize('dimension_type', path, written)).text)
  expect(JSON.parse(written)).toMatchObject({ minY: -256, height: 640, bedWorks: false })

  // A height the game refuses is a problem, focused on its field.
  await bottom.fill('-250')
  await bottom.press('Enter')
  await showPanel(page, 'Problems')
  await expect(page.getByText(/minY must be a multiple of 16/).first()).toBeVisible()
  expect(errors).toEqual([])
})

test("the generator's preview draws a world of the dimension a world naming it has", async ({
  page,
}) => {
  const errors = await openExample(page)
  // The example's `hills` world is made with its generator and its deep dimension (-128 to 319).
  await openResource(page, 'ruby_hills', 'Terrain')
  const heights = page.getByRole('combobox', { name: 'Preview heights' })
  // The world's heights are the ones drawn first.
  await expect(heights).toHaveValue('hills')
  await expect(heights.getByRole('option', { name: 'hills: deep (y -128 to 319)' })).toHaveCount(1)
  await expect(page.getByTestId('terrain-slice')).toBeVisible()
  await expect(page.getByTestId('terrain-previews')).not.toHaveAttribute('data-drawing', 'true')
  const deep = await sliceHeight(page)

  // The overworld's is 64 blocks shallower: the slice is drawn from its bottom.
  await heights.selectOption('')
  await expect(heights).toHaveValue('')
  await expect.poll(() => sliceHeight(page)).toBeLessThan(deep)
  expect(errors).toEqual([])
})
