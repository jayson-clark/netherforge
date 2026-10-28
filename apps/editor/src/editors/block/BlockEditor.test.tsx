import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { modelOf } from '@/core/store/documents'
import type { BlockFile } from '@/core/format'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { BlockEditor } from './BlockEditor'

const ORE = 'blocks/ruby_ore/block.json'

let app: AppStores

beforeEach(async () => {
  ;({ app } = await openExampleApp())
  await app.workspace.getState().openFile(ORE)
  render(
    <AppProvider app={app}>
      <BlockEditor path={ORE} />
    </AppProvider>,
  )
})

afterEach(cleanup)

const ore = () => modelOf<BlockFile>(app.workspace.getState().docs[ORE])!
const inspector = () => within(screen.getByRole('complementary', { name: 'Inspector' }))
const faces = () => screen.getByLabelText('Block preview').querySelectorAll('[data-face]')

describe('the block editor', () => {
  it("draws its look's faces, and the plain game block once the look is taken off", async () => {
    const user = userEvent.setup()
    await waitFor(() => expect(faces()).toHaveLength(6))

    const look = inspector().getByRole('combobox', { name: 'Look' })
    expect(within(look).getByRole('option', { name: 'ui/ruby_crystal' }))
    await user.selectOptions(look, '')
    expect(ore()).not.toHaveProperty('model')
    expect(screen.getByLabelText('Drawn as a note block').textContent).toMatch(/No look yet/)
  })

  it('leaves out what is the default: any tool, no tool needed, no drops', async () => {
    const user = userEvent.setup()
    await user.selectOptions(inspector().getByRole('combobox', { name: 'Tool' }), '')
    expect(ore()).not.toHaveProperty('tool')
    await user.click(inspector().getByRole('checkbox', { name: 'Needs the tool to drop' }))
    expect(ore()).not.toHaveProperty('requiresTool')

    const drops = inspector().getByRole('combobox', { name: 'Drops' })
    await user.selectOptions(drops, 'treasure')
    expect(ore().drops).toBe('treasure')
    await user.selectOptions(drops, '')
    expect(ore()).not.toHaveProperty('drops')
  })

  it('names a reference to nothing as missing rather than dropping it', async () => {
    app.workspace.getState().edit<BlockFile>(ORE, (d) => {
      d.drops = 'gone'
    })
    const drops = await inspector().findByRole('option', { name: 'gone (missing)' })
    expect((drops as HTMLOptionElement).selected).toBe(true)
  })
})
