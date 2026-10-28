import { expect } from '@playwright/test'
import { canonicalize } from '@netherforge/format'
import { files, openExample, openResource, save, showPanel, test, showKind } from './helpers'

const FLYBY = 'cutscenes/flyby.json'

const canonical = (path: string, text: string) =>
  JSON.parse(canonicalize('cutscene', path, text)).text as string

/** Moves the playhead to [seconds]: Home, then a tick (a twentieth of a second) a press. */
async function seek(page: import('@playwright/test').Page, seconds: number) {
  const playhead = page.getByRole('slider', { name: 'Playhead' })
  await playhead.focus()
  await page.keyboard.press('Home')
  for (let i = 0; i < Math.round(seconds * 20); i += 1) await page.keyboard.press('ArrowRight')
  await expect(page.getByLabel('Playhead time')).toHaveText(`${seconds.toFixed(2)}s`)
}

test('keys a cutscene on the timeline: the camera at the playhead, an easing, a cue, saved canonically', async ({
  page,
}) => {
  const errors = await openExample(page)
  await openResource(page, 'flyby', 'Cutscenes')
  await expect(page.getByLabel('Cutscene preview')).toBeVisible()
  // Format's own sampling: the camera's rows, with the keys the file has.
  for (const row of ['position', 'rotation', 'cues'])
    await expect(page.getByLabel(`${row} track`)).toBeVisible()
  await expect(page.getByRole('slider', { name: 'Position key 3' })).toBeVisible()
  await expect(page.getByRole('slider', { name: 'Rotation key 3' })).toBeVisible()
  await expect(page.getByRole('slider', { name: 'Cue 2' })).toBeVisible()
  await expect(page.getByRole('textbox', { name: 'Length (seconds)' })).toHaveValue('6')

  // Key the camera where it is, a quarter of the way along its first segment.
  await seek(page, 1.5)
  await page.getByRole('button', { name: 'Key position' }).click()
  await expect(page.getByRole('slider', { name: 'Position key 2' })).toHaveAttribute(
    'data-selected',
    'true',
  )
  const key = page.getByLabel('Key', { exact: true })
  await expect(key.getByRole('textbox', { name: 'Key time' })).toHaveValue('1.5')
  // Halfway between (0, 6, -12) and (10, 8, 0): it holds the pose it was keyed from.
  await expect(key.getByRole('textbox', { name: 'Position x' })).toHaveValue('5')
  await expect(key.getByRole('textbox', { name: 'Position y' })).toHaveValue('7')
  const lift = key.getByRole('textbox', { name: 'Position y' })
  await lift.fill('20')
  await lift.press('Enter')
  await key.getByRole('combobox', { name: 'Easing' }).selectOption('ease_out')

  // A cue at the playhead, named.
  await page.getByRole('button', { name: 'Add cue' }).click()
  const cue = page.getByLabel('Key', { exact: true }).getByRole('textbox', { name: 'Cue event' })
  await cue.fill('boom')
  await cue.blur()
  await expect(page.getByRole('slider', { name: 'Cue 2' })).toBeVisible()

  await save(page, 'flyby')
  const written = (await files(page))[FLYBY]!
  const model = JSON.parse(written)
  expect(model.camera.position).toHaveLength(4)
  expect(model.camera.position[1]).toEqual({ time: 1.5, value: [5, 20, -6], easing: 'ease_out' })
  expect(model.cues.map((it: { event?: string }) => it.event)).toEqual([undefined, 'boom', 'turn'])
  expect(written).toBe(canonical(FLYBY, written))
  await showPanel(page, 'Problems')
  await expect(page.getByText('No problems.')).toBeVisible()

  // Deleting a key from its fields takes it off the row.
  await page.getByRole('slider', { name: 'Position key 2' }).focus()
  await page.getByRole('button', { name: 'Delete key' }).click()
  await expect(page.getByRole('slider', { name: 'Position key 4' })).toHaveCount(0)

  // A length shorter than the keys is a problem, and the preview says it has no path.
  const length = page.getByRole('textbox', { name: 'Length (seconds)' })
  await length.fill('2')
  await length.press('Enter')
  await expect(
    page.getByText("A key's time must be from 0 to the cutscene's length (2 seconds)").first(),
  ).toBeVisible()
  await expect(
    page.getByText("This cutscene has errors, so there's no path to show."),
  ).toBeVisible()
  expect(errors).toEqual([])
})

test('a new cutscene starts as a path that plays, and its preview moves with the playhead', async ({
  page,
}) => {
  const errors = await openExample(page)
  await showKind(page, 'Cutscenes')
  await page.getByRole('button', { name: 'New cutscene' }).click()
  const create = page.getByRole('dialog', { name: 'New cutscene' })
  await create.getByRole('textbox').fill('arrival')
  await create.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByRole('tab', { name: 'arrival' })).toHaveAttribute('aria-selected', 'true')
  await expect(page.getByLabel('Playhead time')).toHaveText('0.00s')
  await showPanel(page, 'Problems')
  await expect(page.getByText('No problems.')).toBeVisible()

  // Play to the end: the playhead runs out and stops.
  await page.getByRole('button', { name: 'Play', exact: true }).click()
  await expect(page.getByLabel('Playhead time')).toHaveText('2.00s')
  await expect(page.getByRole('button', { name: 'Play', exact: true })).toBeVisible()
  // The camera frustum is in the scene at the playhead (a keyed position, drawn by format's path).
  await seek(page, 1)
  await page.getByRole('button', { name: 'Key position' }).click()
  await expect(
    page.getByLabel('Key', { exact: true }).getByRole('textbox', { name: 'Position z' }),
  ).toHaveValue('4')
  expect(errors).toEqual([])
})
