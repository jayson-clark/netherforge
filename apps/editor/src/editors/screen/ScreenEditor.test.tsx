import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { modelOf } from '@/core/store/documents'
import type { ScreenFile } from '@/core/format'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { ScreenEditor } from './ScreenEditor'

const BOARD = 'screens/lobby_board/screen.json'

let app: AppStores

beforeEach(async () => {
  ;({ app } = await openExampleApp())
  await app.workspace.getState().openFile(BOARD)
  render(
    <AppProvider app={app}>
      <ScreenEditor path={BOARD} />
    </AppProvider>,
  )
})

afterEach(cleanup)

const board = () => modelOf<ScreenFile>(app.workspace.getState().docs[BOARD])!
const inspector = () => within(screen.getByRole('complementary', { name: 'Inspector' }))

describe('the screen editor', () => {
  it('draws its maps, and resizes it', async () => {
    const user = userEvent.setup()
    expect(screen.getByRole('img', { name: '3 by 2 maps' })).toBeDefined()
    const across = inspector().getByRole('textbox', { name: 'Maps across' })
    await user.clear(across)
    await user.type(across, '5{Enter}')
    expect(board().width).toBe(5)
    expect(await screen.findByRole('img', { name: '5 by 2 maps' })).toBeDefined()
  })

  it('moves where it hangs, and takes it down', async () => {
    const user = userEvent.setup()
    await user.selectOptions(inspector().getByRole('combobox', { name: 'Facing' }), 'east')
    expect(board().place?.facing).toBe('east')
    await user.click(inspector().getByRole('checkbox', { name: 'Where it hangs enabled' }))
    expect(board()).not.toHaveProperty('place')
  })

  it("plays one of the project's videos from the start", async () => {
    const user = userEvent.setup()
    await user.click(inspector().getByRole('checkbox', { name: 'Video enabled' }))
    expect(board().video).toEqual({ video: 'bounce' })
    await user.click(inspector().getByRole('checkbox', { name: 'Loops' }))
    expect(board().video).toEqual({ video: 'bounce', loop: false })
  })
})
