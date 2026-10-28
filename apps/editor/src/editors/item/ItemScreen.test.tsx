import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { ItemFile } from '@/core/format'
import { modelOf } from '@/core/store/documents'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { ItemScreen } from './ItemScreen'
import { ItemThumbnail } from './ItemThumbnail'

const RUBY = 'items/ruby/item.json'

let app: AppStores

beforeEach(async () => {
  ;({ app } = await openExampleApp())
  await app.workspace.getState().openFile(RUBY)
})

afterEach(cleanup)

const ruby = () => modelOf<ItemFile>(app.workspace.getState().docs[RUBY])!

function show(path = RUBY) {
  render(
    <AppProvider app={app}>
      <ItemScreen path={path} />
    </AppProvider>,
  )
  return screen.getByRole('complementary', { name: 'Inspector' })
}

describe('the item screen', () => {
  it("edits the item's look, without what belongs to one stack", () => {
    const inspector = show()
    expect(
      (within(inspector).getByRole('textbox', { name: 'Item' }) as HTMLInputElement).value,
    ).toBe('minecraft:paper')
    expect(within(inspector).queryByRole('textbox', { name: 'Count' })).toBeNull()
    expect(within(inspector).queryByRole('textbox', { name: 'Damage' })).toBeNull()

    const name = within(inspector).getByRole('textbox', { name: 'Name' })
    fireEvent.change(name, { target: { value: '<dark_red>Garnet' } })
    fireEvent.keyDown(name, { key: 'Enter' })
    fireEvent.change(within(inspector).getByRole('combobox', { name: 'Rarity' }), {
      target: { value: 'epic' },
    })
    expect(ruby()).toMatchObject({ name: '<dark_red>Garnet', rarity: 'epic' })
    expect(app.workspace.getState().docs[RUBY]?.dirty).toBe(true)
    // The tooltip shows the new name.
    expect(screen.getAllByLabelText('Tooltip preview')[0]!.textContent).toContain('Garnet')
  })

  it('lists the recipes that make or take it, each opening its recipe', async () => {
    show()
    const recipes = screen.getByRole('region', { name: 'Recipes' })
    const buttons = within(recipes).getAllByRole('button')
    expect(buttons.map((it) => it.textContent)).toEqual(['ruby', 'ruby_dust', 'ruby_sword'])
    const uses = within(recipes).getAllByRole('listitem')
    expect(uses[0]!.textContent).toContain('makes it')
    expect(uses[1]!.textContent).toContain('takes it')

    fireEvent.click(within(recipes).getByRole('button', { name: 'ruby_sword' }))
    await waitFor(() =>
      expect(app.workspace.getState().tabs.map((it) => it.path)).toContain(
        'recipes/ruby_sword.json',
      ),
    )
  })

  it("places one of the project's blocks, and names one that's gone", () => {
    const inspector = show()
    const block = within(inspector).getByRole('combobox', { name: 'Places block' })
    const options = () =>
      within(block)
        .getAllByRole('option')
        .map((it) => it.textContent)
    expect(options()).toEqual([
      '(none)',
      'crystal_glass',
      'floating_lamp',
      'glow_lamp',
      'ruby_crystal',
      'ruby_ore',
    ])
    fireEvent.change(block, { target: { value: 'ruby_ore' } })
    expect(ruby().block).toBe('ruby_ore')
    fireEvent.change(block, { target: { value: '' } })
    expect(ruby().block).toBeUndefined()

    act(() =>
      app.workspace.getState().edit<ItemFile>(RUBY, (draft) => {
        draft.block = 'gone'
      }),
    )
    expect(options()).toContain('gone (missing)')
  })

  it('says when no recipe uses an item', async () => {
    await app.workspace.getState().createResource('item', 'pebble')
    await app.workspace.getState().openFile('items/pebble/item.json')
    show('items/pebble/item.json')
    expect(screen.getByText('No recipe makes or takes pebble yet.')).toBeTruthy()
  })
})

describe('the item thumbnail', () => {
  it('draws the item as a stack naming it does: its pack look', async () => {
    const { container } = render(
      <AppProvider app={app}>
        <ItemThumbnail id="ruby" size={32} fallback={null} />
      </AppProvider>,
    )
    // examples/showcase's ruby has the pack's ui/ruby look, not paper's placeholder.
    await waitFor(() => expect(container.querySelector('img')).not.toBeNull())
    expect(container.textContent).toBe('')
  })
})
