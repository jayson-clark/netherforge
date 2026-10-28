import { expect } from '@playwright/test'
import { files, openExample, outside, save, test } from './helpers'

test('declares a server-owner setting in the project settings and sees it on the Server-owner settings page', async ({
  page,
}) => {
  await openExample(page)
  await page.getByRole('button', { name: 'Project settings' }).click()
  const section = page.getByRole('region', { name: 'Server-owner settings' })
  await expect(section.getByRole('group', { name: 'Setting greeting' })).toBeVisible()

  await section.getByRole('button', { name: 'Add setting…' }).click()
  const dialog = page.getByRole('dialog', { name: 'Add setting' })
  await dialog.getByRole('textbox', { name: 'Name' }).fill('max_players')
  await dialog.getByRole('combobox', { name: 'Type' }).selectOption('integer')
  await dialog.getByRole('textbox', { name: 'Description' }).fill('How many may join.')
  await dialog.getByRole('button', { name: 'Add' }).click()
  await expect(section.getByRole('group', { name: 'Setting max_players' })).toBeVisible()

  await save(page, 'netherforge.json')
  const manifest = JSON.parse((await files(page))['netherforge.json']!)
  expect(manifest.settings.max_players).toMatchObject({
    type: 'integer',
    description: 'How many may join.',
  })

  // The dev server is up: the page lists what it runs with, and sets a value through the bridge.
  await outside(page, (backend) => backend.testConnect(), null)
  await page.getByRole('button', { name: 'Settings', exact: true }).click()
  await page
    .getByRole('treegrid', { name: 'Settings pages' })
    .getByRole('row', { name: 'Server-owner settings' })
    .click()
  const row = page.locator('[data-setting="showcase:max_players"]')
  await expect(row).toBeVisible()
  await row.getByRole('textbox').fill('20')
  await row.getByRole('textbox').press('Enter')
  await expect
    .poll(() => outside(page, (backend) => backend.bridgeLog, null))
    .toContainEqual({
      method: 'set_setting',
      params: { namespace: 'showcase', setting: 'max_players', value: 20 },
    })
})
