import { act, cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { BUILD_LIMITS } from '@netherforge/format/constants'
import { writeNbt } from '@netherforge/structures/nbt'
import { bytesToBase64 } from '@/core/backend/base64'
import type { MemoryBackend } from '@/testing/backend/memory'
import { AppProvider, type AppStores } from '@/state/providers'
import { structureRoot } from '@/testing/nbtFixtures'
import { openExampleApp } from '@/testing/workspace'
import { DialogHost } from '@/ui/dialogs'
import { BuildEditor } from './BuildEditor'

const SIDE = BUILD_LIMITS.partSide
const HALL = 'builds/hall'

let backend: MemoryBackend
let app: AppStores

beforeEach(async () => {
  ;({ backend, app } = await openExampleApp())
  // The picture fits its panel; jsdom has no layout to observe.
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  )
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

/** A part of [size], stone at its corner. */
const part = async (size: [number, number, number]) =>
  bytesToBase64(
    await writeNbt(
      structureRoot({
        size,
        palette: [{ Name: 'minecraft:stone' }],
        blocks: [{ pos: [0, 0, 0], state: 0 }],
      }),
    ),
  )

/** Writes the build's parts into the project, as a capture would, and lists them. */
async function write(parts: Record<string, [number, number, number]>) {
  for (const [file, size] of Object.entries(parts))
    backend.testWriteBytes(`${HALL}/${file}`, await part(size))
  await act(() => app.workspace.getState().refreshFiles())
}

function show() {
  render(
    <AppProvider app={app}>
      <BuildEditor path={`${HALL}/0_0_0.nbt`} />
      <DialogHost />
    </AppProvider>,
  )
}

const inspector = () => within(screen.getByRole('complementary', { name: 'Build' }))

describe('the build editor', () => {
  it("says there's nothing yet before a capture or an import", () => {
    show()
    expect(screen.getByRole('status').textContent).toBe(
      `There's no ${HALL}/ yet. Capture it from the dev server, or import a schematic.`,
    )
  })

  it("names the parts missing from the grid, and draws nothing until they're there", async () => {
    await write({ '0_0_0.nbt': [SIDE, 5, SIDE], '1_0_1.nbt': [3, 5, 4] })
    show()
    expect(screen.getByRole('status').textContent).toBe(
      `${HALL}/ is missing 1_0_0.nbt, 0_0_1.nbt: its parts don't make a whole grid.`,
    )
    expect(screen.queryByLabelText('Build size')).toBeNull()
  })

  it('reads its size from the last part, and picks how it is drawn', async () => {
    const user = userEvent.setup()
    await write({ '0_0_0.nbt': [SIDE, 5, 4], '1_0_0.nbt': [3, 5, 4] })
    show()
    expect((await screen.findByLabelText('Build size')).textContent).toBe(`${SIDE + 3} × 5 × 4`)
    expect(inspector().getByLabelText('Part count').textContent).toBe(
      `2 (2 × 1 × 1, ${SIDE} a side)`,
    )

    await user.selectOptions(inspector().getByRole('combobox', { name: 'View' }), 'y')
    expect(inspector().getByRole('textbox', { name: 'Layer' }))
  })
})
