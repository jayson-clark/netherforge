import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { companionOf } from '@/core/paths'
import { modelOf } from '@/core/store/documents'
import type { StructureGeneration } from '@/core/format'
import type { MemoryBackend } from '@/testing/backend/memory'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { DialogHost } from '@/ui/dialogs'
import { StructureEditor } from './StructureEditor'

const TREE = 'structures/oak_tree.nbt'
const GENERATION = companionOf('structure', 'oak_tree')!

let backend: MemoryBackend
let app: AppStores

beforeEach(async () => {
  ;({ backend, app } = await openExampleApp())
})

afterEach(cleanup)

function show(path = TREE) {
  render(
    <AppProvider app={app}>
      <StructureEditor path={path} />
      <DialogHost />
    </AppProvider>,
  )
}

const inspector = () => within(screen.getByRole('complementary', { name: 'Structure' }))

describe('the structure editor', () => {
  it('reads the .nbt: its size, and its blocks by kind', async () => {
    show()
    expect((await screen.findByLabelText('Structure size')).textContent).toMatch(
      /^\d+ × \d+ × \d+$/,
    )
    const kinds = within(inspector().getByRole('table', { name: 'Blocks by kind' }))
    expect(kinds.getByText('minecraft:oak_log'))
  })

  it("says so when there's no file yet", async () => {
    show('structures/cabin.nbt')
    expect((await screen.findByRole('status')).textContent).toBe(
      "There's no structures/cabin.nbt yet. Capture it from the dev server.",
    )
  })

  it('turns generation on as a new file, saves each edit at once, and deletes it when off', async () => {
    const user = userEvent.setup()
    show()
    await screen.findByLabelText('Structure size')
    await user.click(inspector().getByRole('checkbox', { name: 'Generates in the world enabled' }))
    await waitFor(() => expect(app.workspace.getState().files).toContain(GENERATION))

    const spacing = await inspector().findByRole('textbox', { name: 'Spacing (chunks)' })
    await user.type(spacing, '40{Enter}')
    const generation = () => modelOf<StructureGeneration>(app.workspace.getState().docs[GENERATION])
    expect(generation()?.spacing).toBe(40)
    await waitFor(() => expect(JSON.parse(backend.testFiles()[GENERATION]!).spacing).toBe(40))

    await user.click(inspector().getByRole('checkbox', { name: 'Generates in the world enabled' }))
    const asking = await screen.findByRole('dialog', { name: 'Stop generating oak_tree' })
    await user.click(within(asking).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(app.workspace.getState().files).not.toContain(GENERATION))
  })
})
