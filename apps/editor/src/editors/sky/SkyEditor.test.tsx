import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { modelOf } from '@/core/store/documents'
import type { SkyFile } from '@/core/format'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { SkyEditor } from './SkyEditor'

const BLOOD_MOON = 'skies/blood_moon.json'

let app: AppStores

beforeEach(async () => {
  ;({ app } = await openExampleApp())
  await app.workspace.getState().openFile(BLOOD_MOON)
  render(
    <AppProvider app={app}>
      <SkyEditor path={BLOOD_MOON} />
    </AppProvider>,
  )
})

afterEach(cleanup)

const sky = () => modelOf<SkyFile>(app.workspace.getState().docs[BLOOD_MOON])!
const inspector = () => within(screen.getByRole('complementary', { name: 'Inspector' }))

describe('the sky editor', () => {
  it('sets a colour, and leaves an emptied one to each biome', async () => {
    const user = userEvent.setup()
    const light = inspector().getByRole('textbox', { name: 'Light on the land' })
    await user.type(light, '#FFAA00{Enter}')
    expect(sky().light).toBe('#ffaa00')

    const water = inspector().getByRole('textbox', { name: 'Water' })
    await user.clear(water)
    await user.tab()
    expect(sky()).not.toHaveProperty('water')
    // A colour written another way is put back, and nothing is written.
    const fog = inspector().getByRole('textbox', { name: 'Fog' })
    await user.clear(fog)
    await user.type(fog, 'red{Enter}')
    expect(sky().fog).toBe('#3a0606')
    expect(fog).toHaveProperty('value', '#3a0606')
  })

  it("holds the sky at night only as `darkens: false`, and rounds the fade's steps", async () => {
    const user = userEvent.setup()
    // The example's blood moon holds through the night: the box is ticked, as `darkens: false`.
    const held = inspector().getByRole('checkbox', { name: 'Holds its sky and fog at night' })
    expect(sky().darkens).toBe(false)
    expect(held).toHaveProperty('checked', true)
    // Unticked, the key goes (darkening is the default); ticked again, it's back.
    await user.click(held)
    expect(sky()).not.toHaveProperty('darkens')
    await user.click(held)
    expect(sky().darkens).toBe(false)

    await user.type(inspector().getByRole('textbox', { name: 'Steps' }), '12.6{Enter}')
    expect(sky().fade).toBe(13)
  })
})
