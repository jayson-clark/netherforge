import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { modelOf } from '@/core/store/documents'
import type { DimensionTypeFile } from '@/core/format'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { DimensionTypeEditor } from './DimensionTypeEditor'

const DEEP = 'dimension_types/deep.json'

let app: AppStores

beforeEach(async () => {
  ;({ app } = await openExampleApp())
  await app.workspace.getState().openFile(DEEP)
  render(
    <AppProvider app={app}>
      <DimensionTypeEditor path={DEEP} />
    </AppProvider>,
  )
})

afterEach(cleanup)

const deep = () => modelOf<DimensionTypeFile>(app.workspace.getState().docs[DEEP])!
const inspector = () => within(screen.getByRole('complementary', { name: 'Inspector' }))

describe('the dimension type editor', () => {
  it("says the world's block range, and follows the build limits", async () => {
    const user = userEvent.setup()
    expect(screen.getByTestId('dimension-range').textContent).toBe('y -128 to 319')

    const height = inspector().getByRole('textbox', { name: 'Height' })
    await user.clear(height)
    await user.type(height, '384{Enter}')
    expect(deep().height).toBe(384)
    expect(screen.getByTestId('dimension-range').textContent).toBe('y -128 to 255')

    // Cleared, the key goes and the overworld's is used.
    await user.clear(inspector().getByRole('textbox', { name: 'Bottom (min Y)' }))
    await user.tab()
    expect(deep()).not.toHaveProperty('minY')
    expect(screen.getByTestId('dimension-range').textContent).toBe('y -64 to 319')
  })

  it('sets a rule on, off and back to the default', async () => {
    const user = userEvent.setup()
    const beds = inspector().getByRole('combobox', { name: 'Beds work' })
    await user.selectOptions(beds, 'off')
    expect(deep().bedWorks).toBe(false)
    await user.selectOptions(beds, 'on')
    expect(deep().bedWorks).toBe(true)
    await user.selectOptions(beds, 'default')
    expect(deep()).not.toHaveProperty('bedWorks')
  })

  it('keeps the monster light range only while one end is set', async () => {
    const user = userEvent.setup()
    const from = inspector().getByRole('textbox', { name: 'Monster light from' })
    await user.type(from, '3{Enter}')
    expect(deep().monsterSpawnLight).toEqual({ min: 3 })

    await user.clear(from)
    await user.tab()
    expect(deep()).not.toHaveProperty('monsterSpawnLight')

    await user.type(inspector().getByRole('textbox', { name: 'Sky colour' }), '#102030{Enter}')
    expect(deep().colors).toEqual({ sky: '#102030' })
  })
})
