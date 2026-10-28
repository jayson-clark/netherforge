import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { modelOf } from '@/core/store/documents'
import type { BiomeFile } from '@/core/format'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { BiomeEditor } from './BiomeEditor'

const GROVE = 'biomes/ruby_grove.json'

let app: AppStores

beforeEach(async () => {
  ;({ app } = await openExampleApp())
  await app.workspace.getState().openFile(GROVE)
  render(
    <AppProvider app={app}>
      <BiomeEditor path={GROVE} />
    </AppProvider>,
  )
})

afterEach(cleanup)

const grove = () => modelOf<BiomeFile>(app.workspace.getState().docs[GROVE])!
const inspector = () => within(screen.getByRole('complementary', { name: 'Inspector' }))

describe('the biome editor', () => {
  it('edits a colour, and clears one back to what the world uses', async () => {
    const user = userEvent.setup()
    const water = inspector().getByRole('textbox', { name: 'Water' })
    expect(water).toHaveProperty('value', '#d94f7a')
    await user.clear(water)
    await user.type(water, '#3366CC{Enter}')
    // Written as format writes a colour: lower case.
    expect(grove().colors?.water).toBe('#3366cc')

    await user.click(inspector().getByRole('button', { name: 'Clear Fog' }))
    expect(grove().colors).not.toHaveProperty('fog')
    expect(app.workspace.getState().docs[GROVE]?.dirty).toBe(true)
  })

  // A field with suggestions (an input with a `list`) is a combobox.
  it('adds a mob of a category the biome had none of, and removes it', async () => {
    const user = userEvent.setup()
    await user.click(inspector().getByRole('button', { name: 'Add a spawn of ambient' }))
    const spawn = within(inspector().getByRole('group', { name: 'ambient spawn 1' }))
    await user.type(spawn.getByRole('combobox', { name: 'Entity' }), 'minecraft:bat{Enter}')
    expect(grove().spawns?.ambient).toEqual([{ entity: 'minecraft:bat' }])
    // The other categories are as they were.
    expect(grove().spawns?.monster).toHaveLength(2)

    await user.click(inspector().getByRole('button', { name: 'Remove ambient spawn 1' }))
    expect(grove().spawns).not.toHaveProperty('ambient')
  })

  it("adds a feature to a step, and orders a step's features as they're placed", async () => {
    const user = userEvent.setup()
    await user.click(
      inspector().getByRole('button', { name: 'Add a feature to top_layer_modification' }),
    )
    await user.type(
      inspector().getByRole('combobox', { name: 'top_layer_modification 1' }),
      'minecraft:freeze_top_layer{Enter}',
    )
    expect(grove().features?.top_layer_modification).toEqual(['minecraft:freeze_top_layer'])

    await user.click(
      inspector().getByRole('button', { name: 'Move minecraft:trees_cherry earlier' }),
    )
    expect(grove().features?.vegetal_decoration).toEqual([
      'minecraft:patch_grass_plain',
      'minecraft:trees_cherry',
      'minecraft:flower_cherry',
    ])
    // The first can't go earlier, nor the last later.
    expect(
      inspector().getByRole('button', { name: 'Move minecraft:patch_grass_plain earlier' }),
    ).toHaveProperty('disabled', true)
    expect(
      inspector().getByRole('button', { name: 'Move minecraft:flower_cherry later' }),
    ).toHaveProperty('disabled', true)
  })
})
