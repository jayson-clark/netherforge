import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { modelOf } from '@/core/store/documents'
import type { CentityFile } from '@/core/format'
import { viewOf } from '@/editors/views'
import { AppProvider, type AppStores } from '@/state/providers'
import { CentityOutline } from './CentityOutline'
import { openExampleApp } from '@/testing/workspace'

const TOWER = 'centities/tower/centity.json'

let app: AppStores

beforeEach(async () => {
  ;({ app } = await openExampleApp())
  await app.workspace.getState().openFile(TOWER)
})

afterEach(cleanup)

const nodes = () => screen.getByRole('treegrid', { name: 'Nodes' })
const row = (name: string) => within(nodes()).getByRole('row', { name })

describe("a centity's node tree", () => {
  it('selects several nodes into the document view, and deletes them together', async () => {
    const user = userEvent.setup()
    render(
      <AppProvider app={app}>
        <CentityOutline path={TOWER} files={null} />
      </AppProvider>,
    )
    const view = viewOf(app.workspace, 'centity', TOWER)

    await user.click(row('cap'))
    expect(view.selection()).toEqual(['cap'])
    // Cmd-click on a Mac; jsdom isn't one, so it's Ctrl here.
    await user.keyboard('{Control>}')
    await user.click(row('flag'))
    await user.keyboard('{/Control}')
    expect(view.selection()).toEqual(['cap', 'flag'])
    expect(view.primary()).toBe('flag')
    expect(row('cap').getAttribute('aria-selected')).toBe('true')

    // Shift+arrows extend from the focused row (flag, after cap, both under turret).
    await user.keyboard('{Shift>}{ArrowUp}{ArrowUp}{/Shift}')
    expect([...view.selection()].sort()).toEqual(['cap', 'flag', 'turret'])
    expect(view.primary()).toBe('turret')

    // Delete on a selected row deletes every selected node.
    await user.click(row('cap'))
    await user.keyboard('{Control>}')
    await user.click(row('flag'))
    await user.keyboard('{/Control}{Control>}{Backspace}{/Control}')
    const model = modelOf<CentityFile>(app.workspace.getState().docs[TOWER])!
    expect(Object.keys(model.nodes ?? {})).not.toContain('flag')
    expect(Object.keys(model.nodes ?? {})).not.toContain('cap')
    expect(view.selection()).toEqual([])
  })
})
