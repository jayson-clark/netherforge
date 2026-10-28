import { expect } from '@playwright/test'
import { chooseMenu, files, openExample, save, test } from './helpers'

test('an untrusted project edits in restricted mode, and runs once trusted', async ({ page }) => {
  await openExample(page, '&untrusted')
  const banner = page.getByRole('region', { name: 'Restricted mode' })
  await expect(banner).toContainText("You haven't trusted this project")

  // Starting the server asks to trust it first; cancelling leaves it stopped and restricted.
  await page.getByRole('button', { name: 'Start server' }).click()
  const ask = page.getByRole('dialog', { name: 'Trust "NetherForge Showcase"?' })
  await expect(ask).toContainText('Only trust a project whose authors you trust.')
  await ask.getByRole('button', { name: 'Cancel' }).click()
  await expect(page.getByRole('status', { name: 'Server status' })).toContainText('Stopped')
  await expect(banner).toBeVisible()

  await banner.getByRole('button', { name: 'Trust project…' }).click()
  await page.getByRole('button', { name: 'Trust project', exact: true }).click()
  await expect(banner).toBeHidden()

  // Trusted, it starts as any project does: the EULA comes next.
  await page.getByRole('button', { name: 'Start server' }).click()
  await expect(page.getByRole('dialog', { name: 'Minecraft EULA' })).toBeVisible()
})

test('the project settings show what the whole tree requires, with who declares it', async ({
  page,
}) => {
  await openExample(page)
  await page.getByRole('button', { name: 'Project settings' }).click()
  const table = page.getByRole('table', { name: 'Requirements across the packages' })
  await expect(table.getByRole('row')).toHaveText([
    'RequirementDeclared by',
    'dbshowcase',
    'webshowcase',
    'botsshowcase',
    'wasmshowcase',
    'http:api.example.comlibrary',
  ])

  await page.getByRole('checkbox', { name: 'Moderation' }).check()
  await expect(table.getByRole('row')).toHaveText([
    'RequirementDeclared by',
    'moderationshowcase',
    'dbshowcase',
    'webshowcase',
    'botsshowcase',
    'wasmshowcase',
    'http:api.example.comlibrary',
  ])
})

test('a trusted project can be untrusted, from the Run menu and from the Server settings', async ({
  page,
}) => {
  await openExample(page)
  const banner = page.getByRole('region', { name: 'Restricted mode' })
  await expect(banner).toBeHidden()

  // Cancelling leaves it trusted.
  await chooseMenu(page, 'Run', 'Untrust Project')
  const ask = page.getByRole('dialog', { name: 'Stop trusting "NetherForge Showcase"?' })
  await ask.getByRole('button', { name: 'Cancel' }).click()
  await expect(banner).toBeHidden()

  await chooseMenu(page, 'Run', 'Untrust Project')
  await ask.getByRole('button', { name: 'Untrust project' }).click()
  await expect(banner).toBeVisible()

  // Trust it again in the Server settings, where the button follows the state.
  await page.getByRole('button', { name: 'Settings', exact: true }).click()
  await page
    .getByRole('treegrid', { name: 'Settings pages' })
    .getByRole('row', { name: 'Server', exact: true })
    .click()
  const trust = page.getByRole('region', { name: 'Project trust' })
  await trust.getByRole('button', { name: 'Trust project…' }).click()
  await page.getByRole('button', { name: 'Trust project', exact: true }).click()
  await expect(banner).toBeHidden()
  await trust.getByRole('button', { name: 'Untrust project…' }).click()
  await ask.getByRole('button', { name: 'Untrust project' }).click()
  await expect(banner).toBeVisible()
})

test('the project settings edit mob spawning by world, and the file follows', async ({ page }) => {
  await openExample(page)
  await page.getByRole('button', { name: 'Project settings' }).click()
  const world = page.getByRole('group', { name: 'World lobby' })
  await expect(world.getByRole('textbox', { name: 'monster limit' })).toHaveValue('0')
  await world.getByRole('textbox', { name: 'animal interval', exact: true }).fill('400')
  await world.getByRole('textbox', { name: 'animal interval', exact: true }).press('Enter')
  await save(page, 'netherforge.json')
  const manifest = JSON.parse((await files(page))['netherforge.json']!)
  expect(manifest.worlds.lobby).toEqual({
    spawnLimits: { monster: 0 },
    spawnIntervals: { animal: 400 },
  })
})
