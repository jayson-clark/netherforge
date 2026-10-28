import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { modelOf } from '@/core/store/documents'
import type { MenuFile } from '@/core/format'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { DialogHost } from '@/ui/dialogs'
import { MenuEditor } from './MenuEditor'

const SHOP = 'menus/shop/menu.json'

let app: AppStores

beforeEach(async () => {
  ;({ app } = await openExampleApp())
  await app.workspace.getState().openFile(SHOP)
  render(
    <AppProvider app={app}>
      <MenuEditor path={SHOP} />
      <DialogHost />
    </AppProvider>,
  )
})

afterEach(cleanup)

const shop = () => modelOf<MenuFile>(app.workspace.getState().docs[SHOP])!
const inspector = () => within(screen.getByRole('complementary', { name: 'Inspector' }))
const slot = (name: string | RegExp) => screen.getByRole('button', { name })

/** A drag from one slot to another, as the browser sends it. */
function drag(from: HTMLElement, to: HTMLElement) {
  const data = new Map<string, string>()
  const dataTransfer = {
    effectAllowed: '',
    setData: (type: string, value: string) => data.set(type, value),
    getData: (type: string) => data.get(type) ?? '',
  }
  fireEvent.dragStart(from, { dataTransfer })
  fireEvent.dragOver(to, { dataTransfer })
  fireEvent.drop(to, { dataTransfer })
  fireEvent.dragEnd(from, { dataTransfer })
}

describe('the menu editor', () => {
  it('names each slot by what it holds, a project item by its id', () => {
    expect(slot('Slot 11: minecraft:bread'))
    expect(slot('Slot 13: ruby'))
    expect(slot('Slot 0, empty'))
  })

  it('selects a slot and moves it, the selection following', async () => {
    const user = userEvent.setup()
    await user.click(slot('Slot 11: minecraft:bread'))
    expect(slot('Slot 11: minecraft:bread')).toHaveProperty('ariaPressed', 'true')

    await user.type(inspector().getByRole('textbox', { name: 'Move to' }), '20{Enter}')
    expect(shop().slots?.['20']?.item?.kind).toBe('minecraft:bread')
    expect(shop().slots).not.toHaveProperty('11')
    expect(slot('Slot 20: minecraft:bread')).toHaveProperty('ariaPressed', 'true')
  })

  it('swaps two slots dragged onto each other, as one undo step', async () => {
    drag(slot('Slot 11: minecraft:bread'), slot('Slot 15: minecraft:barrier'))
    expect(shop().slots?.['15']?.item?.kind).toBe('minecraft:bread')
    expect(shop().slots?.['11']?.item?.kind).toBe('minecraft:barrier')

    await act(() => app.workspace.getState().undo(SHOP))
    expect(shop().slots?.['11']?.item?.kind).toBe('minecraft:bread')
  })

  it('keeps slots a smaller window leaves out, and lists them so they can go', async () => {
    const user = userEvent.setup()
    await user.selectOptions(inspector().getByRole('combobox', { name: 'Rows' }), '1')
    const outside = within(screen.getByRole('note'))
    expect(outside.getByText(/4 slots are outside this window/))
    expect(shop().slots).toHaveProperty('15')

    await user.click(outside.getByRole('button', { name: 'Remove slot 15' }))
    expect(shop().slots).not.toHaveProperty('15')
  })

  it("adds an item to an empty slot from the item's id", async () => {
    const user = userEvent.setup()
    await user.click(slot('Slot 0, empty'))
    await user.click(inspector().getByRole('button', { name: 'Add item' }))
    const asking = await screen.findByRole('dialog', { name: 'Item in slot 0' })
    await user.type(within(asking).getByRole('textbox'), 'minecraft:diamond')
    await user.click(within(asking).getByRole('button', { name: 'Add' }))
    await waitFor(() =>
      expect(shop().slots?.['0']).toEqual({ item: { kind: 'minecraft:diamond' } }),
    )
  })
})
