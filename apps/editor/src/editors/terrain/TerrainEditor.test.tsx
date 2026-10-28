import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { modelOf } from '@/core/store/documents'
import type { TerrainFile } from '@/core/format'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { DialogHost } from '@/ui/dialogs'
import { TerrainEditor } from './TerrainEditor'

const HILLS = 'terrain/ruby_hills.json'

let app: AppStores

beforeEach(async () => {
  ;({ app } = await openExampleApp())
  // The map and the slice fit their panels; jsdom has no layout to observe.
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  )
  await app.workspace.getState().openFile(HILLS)
  render(
    <AppProvider app={app}>
      <TerrainEditor path={HILLS} />
      <DialogHost />
    </AppProvider>,
  )
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

const hills = () => modelOf<TerrainFile>(app.workspace.getState().docs[HILLS])!
const inspector = () => within(screen.getByRole('complementary', { name: 'Inspector' }))
/** The fields of one named entry, found by its JSON path as a problem would be. */
const entry = (path: string) => {
  const found = document.querySelector<HTMLElement>(`[data-path="${path}"]`)
  if (!found) throw new Error(`nothing at ${path}`)
  return within(found)
}

/** Answers the name prompt that adding or renaming an entry opens. */
async function answer(title: string, name: string, confirm: string) {
  const user = userEvent.setup()
  const asking = await screen.findByRole('dialog', { name: title })
  const field = within(asking).getByRole('textbox')
  await user.clear(field)
  await user.type(field, name)
  await user.click(within(asking).getByRole('button', { name: confirm }))
}

describe('the terrain inspector', () => {
  it('adds an ore after asking its name, and edits it by itself', async () => {
    const user = userEvent.setup()
    await user.click(inspector().getByRole('button', { name: 'Add ore' }))
    await answer('New ore', 'gold', 'Add')
    await waitFor(() => expect(Object.keys(hills().ores ?? {})).toContain('gold'))

    const veins = entry('ores.gold').getByRole('textbox', { name: 'Veins per chunk' })
    await user.clear(veins)
    await user.type(veins, '3{Enter}')
    expect(hills().ores?.gold?.veins).toBe(3)
    expect(hills().ores?.iron?.veins).toBe(10)

    await user.click(inspector().getByRole('button', { name: 'Delete ore coal' }))
    expect(hills().ores).not.toHaveProperty('coal')
  })

  it('keeps an ore to the areas ticked', async () => {
    const user = userEvent.setup()
    const iron = entry('ores.iron')
    await user.click(iron.getByRole('checkbox', { name: 'grove' }))
    expect(hills().ores?.iron?.biomes).toEqual(['grove'])
    await user.click(iron.getByRole('checkbox', { name: 'grove' }))
    expect(hills().ores?.iron).not.toHaveProperty('biomes')
  })

  it('renames a biome area, and every filter that keeps to it follows', async () => {
    const user = userEvent.setup()
    await user.click(inspector().getByRole('button', { name: 'Rename biome area snowy' }))
    await answer('Rename biome area', 'frost', 'Rename')
    await waitFor(() => expect(Object.keys(hills().biomes ?? {})).toContain('frost'))
    expect(hills().biomes).not.toHaveProperty('snowy')
    expect(hills().ores?.ruby?.biomes).toEqual(['frost'])
  })

  it('moves a layer of the ground up', async () => {
    const user = userEvent.setup()
    await user.click(inspector().getAllByRole('button', { name: 'Move layer 2 up' })[0]!)
    expect(hills().layers?.map((it) => it.block)).toEqual([
      'minecraft:dirt',
      'minecraft:grass_block',
    ])
  })
})
