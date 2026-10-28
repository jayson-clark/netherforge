import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { modelOf } from '@/core/store/documents'
import type { BuffFile } from '@/core/format'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { BuffEditor } from './BuffEditor'

const IRONSKIN = 'buffs/ironskin.json'

let app: AppStores

beforeEach(async () => {
  ;({ app } = await openExampleApp())
  await app.workspace.getState().openFile(IRONSKIN)
  render(
    <AppProvider app={app}>
      <BuffEditor path={IRONSKIN} />
    </AppProvider>,
  )
})

afterEach(cleanup)

const buff = () => modelOf<BuffFile>(app.workspace.getState().docs[IRONSKIN])!
const inspector = () => within(screen.getByRole('complementary', { name: 'Inspector' }))

describe('the buff editor', () => {
  it('adds a stat, changes one, and takes one off by clearing it', async () => {
    const user = userEvent.setup()
    await user.type(inspector().getByRole('textbox', { name: 'Add a stat' }), 'speed{Enter}')
    expect(buff().stats).toEqual({ defense: 8, speed: 1 })

    const defense = inspector().getByRole('textbox', { name: 'defense' })
    await user.clear(defense)
    await user.type(defense, '12{Enter}')
    expect(buff().stats).toEqual({ defense: 12, speed: 1 })

    await user.clear(inspector().getByRole('textbox', { name: 'speed' }))
    await user.tab()
    expect(buff().stats).toEqual({ defense: 12 })
    // The stage lists what it adds.
    expect(within(screen.getByRole('list', { name: 'What it adds' })).getByText('+12 defense'))
  })

  it('writes lists from commas and leaves a default out', async () => {
    const user = userEvent.setup()
    const cures = inspector().getByRole('textbox', { name: 'Cures' })
    await user.clear(cures)
    await user.type(cures, 'frail, , on_fire{Enter}')
    expect(buff().cures).toEqual(['frail', 'on_fire'])

    await user.clear(cures)
    await user.tab()
    expect(buff()).not.toHaveProperty('cures')

    const again = inspector().getByRole('combobox', { name: 'Added again' })
    await user.selectOptions(again, 'add')
    expect(buff().stacking).toBe('add')
    // The game's default is no key at all.
    await user.selectOptions(again, 'longest')
    expect(buff()).not.toHaveProperty('stacking')
  })

  it('marks it a debuff, and the stage says so', async () => {
    const user = userEvent.setup()
    await user.click(inspector().getByRole('checkbox', { name: 'A debuff' }))
    expect(buff().debuff).toBe(true)
    expect(within(screen.getByRole('img', { name: 'How the buff is shown' })).getByText('Debuff'))
    await user.click(inspector().getByRole('checkbox', { name: 'A debuff' }))
    expect(buff()).not.toHaveProperty('debuff')
  })
})
