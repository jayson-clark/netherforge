import { expect } from '@playwright/test'
import { canonicalize } from '@netherforge/format'
import { files, openExampleWithAssets, outside, save, showPanel, test, showKind } from './helpers'

const QUEST = 'advancements/quest.json'

test('creates an advancement, edits its title and a criterion, and saves it canonically', async ({
  page,
}) => {
  await openExampleWithAssets(page)
  await showKind(page, 'Advancements')
  await page.getByRole('button', { name: 'New advancement' }).click()
  const create = page.getByRole('dialog', { name: 'New advancement' })
  await create.getByRole('textbox').fill('quest')
  await create.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByRole('tab', { name: 'quest' })).toHaveAttribute('aria-selected', 'true')
  expect(Object.keys(await files(page))).toContain(QUEST)

  // The outline lists its criteria; the template has one.
  const criteria = page.getByRole('treegrid', { name: 'Criteria' })
  await expect(criteria.getByRole('row')).toHaveCount(1)

  // A field of the display, in the inspector.
  const inspector = page.getByRole('complementary', { name: 'Inspector' })
  const title = inspector.getByRole('textbox', { name: 'Title' })
  await title.fill('Treasure <gold>Seeker')
  await title.press('Enter')
  await expect(page.getByRole('region', { name: 'Preview' })).toContainText('Treasure Seeker')

  // A new criterion with a trigger of the game's.
  await page.getByTestId('editor').getByRole('button', { name: 'Add criterion' }).click()
  await expect(page.getByRole('list', { name: 'Criteria' }).getByRole('listitem')).toHaveCount(2)
  const trigger = inspector.getByRole('combobox', { name: 'Trigger' })
  await trigger.fill('minecraft:tick')
  await trigger.press('Enter')
  await expect(page.getByRole('button', { name: 'Criterion: criterion' })).toContainText(
    'minecraft:tick',
  )

  await save(page, 'quest')
  const written = (await files(page))[QUEST]!
  expect(written).toBe(JSON.parse(canonicalize('advancement', QUEST, written)).text)
  const quest = JSON.parse(written)
  expect(quest.display.title).toBe('Treasure <gold>Seeker')
  expect(Object.keys(quest.criteria)).toHaveLength(2)
  expect(Object.values(quest.criteria)).toContainEqual({ trigger: 'minecraft:tick' })
})

test('restarts the dev server when saving says the server must', async ({ page }) => {
  await openExampleWithAssets(page)
  await outside(
    page,
    (backend) => {
      backend.testConnect()
      backend.testReloadRestarts()
    },
    null,
  )
  await showKind(page, 'Advancements')
  await page.getByRole('option', { name: 'treasure_hunter', exact: true }).dblclick()
  const inspector = page.getByRole('complementary', { name: 'Inspector' })
  const experience = inspector.getByRole('textbox', { name: 'Experience' })
  await experience.fill('25')
  await experience.press('Enter')
  await save(page, 'treasure_hunter')
  await showPanel(page, 'Console')
  await expect(page.getByText('Restarting the dev server')).toBeVisible()
})
