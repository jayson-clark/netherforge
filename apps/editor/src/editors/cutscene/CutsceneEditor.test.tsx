import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { modelOf } from '@/core/store/documents'
import type { CutsceneFile } from '@/core/format'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { CutsceneEditor } from './CutsceneEditor'

const FLYBY = 'cutscenes/flyby.json'

let app: AppStores

beforeEach(async () => {
  ;({ app } = await openExampleApp())
  await app.workspace.getState().openFile(FLYBY)
  render(
    <AppProvider app={app}>
      <CutsceneEditor path={FLYBY} />
    </AppProvider>,
  )
})

afterEach(cleanup)

const flyby = () => modelOf<CutsceneFile>(app.workspace.getState().docs[FLYBY])!
const inspector = () => within(screen.getByRole('complementary', { name: 'Inspector' }))
const timeline = () => within(screen.getByLabelText('Timeline'))
const keyEditor = () => within(screen.getByLabelText('Key'))

describe('the cutscene editor', () => {
  it('selects a key on the timeline, sets its easing and deletes it', async () => {
    const user = userEvent.setup()
    await user.click(timeline().getByRole('slider', { name: 'Position key 2' }))
    expect(keyEditor().getByText('Position key 2'))

    await user.selectOptions(keyEditor().getByRole('combobox', { name: 'Easing' }), 'linear')
    // Linear is no easing at all.
    expect(flyby().camera?.position?.[1]).not.toHaveProperty('easing')

    await user.click(keyEditor().getByRole('button', { name: 'Delete key' }))
    expect(flyby().camera?.position?.map((it) => it.time)).toEqual([0, 6])
    expect(screen.queryByLabelText('Key')).toBeNull()
  })

  it('keys the position at the playhead, from where the camera is there', async () => {
    const user = userEvent.setup()
    const playhead = timeline().getByRole('slider', { name: 'Playhead' })
    fireEvent.change(playhead, { target: { value: '1.5' } })
    expect(screen.getByLabelText('Playhead time').textContent).toBe('1.50s')

    await user.click(timeline().getByRole('button', { name: 'Key position' }))
    const keys = flyby().camera?.position ?? []
    expect(keys.map((it) => it.time)).toEqual([0, 1.5, 3, 6])
    // Between the first two keys, the camera has moved off the first.
    expect(keys[1]?.value).not.toEqual([0, 6, -12])
    expect(keyEditor().getByText('Position key 2'))
  })

  it("adds a cue and edits its text, and sets the cutscene's own fields", async () => {
    const user = userEvent.setup()
    await user.click(timeline().getByRole('button', { name: 'Add cue' }))
    expect(flyby().cues).toHaveLength(3)
    const text = keyEditor().getByRole('textbox', { name: 'Cue text' })
    await user.type(text, 'Hello')
    await user.tab()
    expect(flyby().cues?.[0]).toMatchObject({ time: 0, text: 'Hello' })

    await user.click(inspector().getByRole('checkbox', { name: 'Skippable' }))
    expect(flyby()).not.toHaveProperty('skippable')
    const length = inspector().getByRole('textbox', { name: 'Length (seconds)' })
    await user.clear(length)
    await user.type(length, '8{Enter}')
    expect(flyby().length).toBe(8)
  })
})
