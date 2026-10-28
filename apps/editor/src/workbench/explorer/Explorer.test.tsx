import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { MemoryBackend } from '@/testing/backend/memory'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { closeMenu, ContextMenuHost } from '@/ui/ContextMenu'
import { DialogHost } from '@/ui/dialogs'
import { Explorer } from './Explorer'

let backend: MemoryBackend
let app: AppStores

beforeEach(async () => {
  localStorage.clear()
  ;({ backend, app } = await openExampleApp())
})

afterEach(() => {
  act(closeMenu)
  cleanup()
})

function show() {
  render(
    <AppProvider app={app}>
      <Explorer />
      <DialogHost />
      <ContextMenuHost />
    </AppProvider>,
  )
}

const kinds = () => screen.getByRole('listbox', { name: 'Resource kinds' })
const pickKind = (name: string) => fireEvent.click(within(kinds()).getByRole('option', { name }))
const search = () => screen.getByRole('searchbox', { name: 'Search resources' })
const tabs = () => app.workspace.getState().tabs.map((it) => it.path)

describe('the project explorer', () => {
  it("lists a picked kind's resources, and remembers the kind in the layout", () => {
    show()
    pickKind('Menus')
    const menus = screen.getByRole('listbox', { name: 'Menus' })
    expect(
      within(menus)
        .getAllByRole('option')
        .map((it) => it.getAttribute('aria-label')),
    ).toEqual(['altar', 'backpack', 'board', 'shop'])
    expect(
      within(kinds()).getByRole('option', { name: 'Menus' }).getAttribute('aria-selected'),
    ).toBe('true')
    expect(app.layout.getState().explorerFolder).toBe('menu')
  })

  it('searches every kind at once, and a picked kind ends the search', () => {
    show()
    pickKind('Menus')
    fireEvent.change(search(), { target: { value: 'tower' } })
    const results = screen.getByRole('listbox', { name: 'Search results' })
    expect(within(results).getByRole('option', { name: 'tower' })).toBeTruthy()
    expect(within(results).queryByRole('option', { name: 'shop' })).toBeNull()
    // No kind is picked while the results are every kind's.
    expect(within(kinds()).queryAllByRole('option', { selected: true })).toEqual([])

    fireEvent.change(search(), { target: { value: 'zzzz' } })
    expect(screen.getByText('Nothing matches “zzzz”.')).toBeTruthy()

    pickKind('Menus')
    expect((search() as HTMLInputElement).value).toBe('')
    expect(screen.getByRole('listbox', { name: 'Menus' })).toBeTruthy()
  })

  it('opens a resource on double-click, and with Enter from the keyboard', async () => {
    show()
    pickKind('Menus')
    fireEvent.doubleClick(screen.getByRole('option', { name: 'shop' }))
    await waitFor(() => expect(tabs()).toContain('menus/shop/menu.json'))

    // From the search box, ArrowDown picks the first result and Enter opens it.
    fireEvent.change(search(), { target: { value: 'welcome' } })
    fireEvent.keyDown(search(), { key: 'ArrowDown' })
    const results = screen.getByRole('listbox', { name: 'Search results' })
    expect(results).toBe(document.activeElement)
    expect(
      within(results).getByRole('option', { name: 'welcome' }).getAttribute('aria-selected'),
    ).toBe('true')
    fireEvent.keyDown(results, { key: 'Enter' })
    await waitFor(() => expect(tabs()).toContain('dialogs/welcome/dialog.json'))
  })

  it('moves the selection with the arrow keys, Home and End', () => {
    show()
    pickKind('Centities')
    const list = screen.getByRole('listbox', { name: 'Centities' })
    const ids = within(list)
      .getAllByRole('option')
      .map((it) => it.getAttribute('aria-label'))
    const selected = () =>
      within(list)
        .getAllByRole('option', { selected: true })
        .map((it) => it.getAttribute('aria-label'))
    fireEvent.keyDown(list, { key: 'End' })
    expect(selected()).toEqual([ids.at(-1)])
    fireEvent.keyDown(list, { key: 'Home' })
    expect(selected()).toEqual([ids[0]])
    fireEvent.keyDown(list, { key: 'ArrowRight' })
    expect(selected()).toEqual([ids[1]])
  })

  it('shows tiles with a size, or a list', () => {
    show()
    expect(screen.getByRole('slider', { name: 'Tile size' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Show as list' }))
    expect(app.layout.getState().explorerView).toBe('list')
    expect(screen.queryByRole('slider', { name: 'Tile size' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Show as tiles' }))
    expect(app.layout.getState().explorerView).toBe('grid')
  })

  it('renames a resource in place with F2', async () => {
    show()
    pickKind('Menus')
    const list = screen.getByRole('listbox', { name: 'Menus' })
    fireEvent.click(within(list).getByRole('option', { name: 'shop' }))
    fireEvent.keyDown(list, { key: 'F2' })
    const name = screen.getByRole('textbox', { name: 'Rename shop' })
    fireEvent.change(name, { target: { value: 'store' } })
    fireEvent.keyDown(name, { key: 'Enter' })
    await waitFor(() => expect(backend.testFiles()['menus/store/menu.json']).toBeDefined())
    expect(backend.testFiles()['menus/shop/menu.json']).toBeUndefined()
    expect(await screen.findByRole('option', { name: 'store' })).toBeTruthy()
  })

  it('duplicates a resource with Cmd/Ctrl+D, then offers to rename the copy', async () => {
    show()
    pickKind('Menus')
    const list = screen.getByRole('listbox', { name: 'Menus' })
    fireEvent.click(within(list).getByRole('option', { name: 'shop' }))
    fireEvent.keyDown(list, { key: 'd', ctrlKey: true })
    await waitFor(() => expect(backend.testFiles()['menus/shop_copy/menu.json']).toBeDefined())
    expect(await screen.findByRole('textbox', { name: 'Rename shop_copy' })).toBeTruthy()
  })
})

describe('a dependency in the explorer', () => {
  it('is listed under the kinds, with its resources read-only and what it exports marked', async () => {
    show()
    const library = await within(kinds()).findByRole('option', { name: 'Package library' })
    fireEvent.click(library)
    const pkg = screen.getByRole('list', { name: 'Package library' })
    const items = within(pkg).getByRole('group', { name: 'Items' })
    const gem = within(items).getByRole('listitem', { name: 'library:gem' })
    expect(within(gem).getByText('exported')).toBeTruthy()
    // A module the library keeps to itself (phrases) isn't marked.
    const modules = within(pkg).getByRole('group', { name: 'Modules' })
    expect(
      within(modules).getByRole('listitem', { name: 'library:greetings' }).textContent,
    ).toContain('exported')
    expect(
      within(modules).getByRole('listitem', { name: 'library:phrases' }).textContent,
    ).not.toContain('exported')
    expect(screen.getByText('read-only')).toBeTruthy()

    // Opening one opens the package's file, read-only.
    fireEvent.doubleClick(gem)
    await waitFor(() => expect(tabs()).toContain('library:items/gem/item.json'))
  })

  it('copies a resource into the project under the id asked for, and shows it there', async () => {
    show()
    fireEvent.click(await within(kinds()).findByRole('option', { name: 'Package library' }))
    fireEvent.click(screen.getByRole('button', { name: 'Copy library:gem into project' }))
    const dialog = await screen.findByRole('dialog', { name: 'Copy library:gem into the project' })
    const id = within(dialog).getByRole('textbox')
    expect((id as HTMLInputElement).value).toBe('gem')
    fireEvent.change(id, { target: { value: 'my_gem' } })
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Copy' }))
    })
    await waitFor(() => expect(backend.testFiles()['items/my_gem/item.json']).toBeDefined())
    // The project's Items, with the copy picked.
    const items = await screen.findByRole('listbox', { name: 'Items' })
    expect(
      within(items).getByRole('option', { name: 'my_gem' }).getAttribute('aria-selected'),
    ).toBe('true')
  })
})

/** What a drag carries between tiles: jsdom has no DataTransfer. */
function dataTransfer() {
  const data = new Map<string, string>()
  return {
    setData: (type: string, value: string) => void data.set(type, value),
    getData: (type: string) => data.get(type) ?? '',
    get types() {
      return [...data.keys()]
    },
    effectAllowed: 'all',
  }
}

describe('group folders in the explorer', () => {
  const items = () => screen.getByRole('listbox', { name: 'Items' })
  const shown = () =>
    within(items())
      .getAllByRole('option')
      .map((it) => it.getAttribute('aria-label'))

  it('shows a group as a folder before the resources, and goes in and back out', () => {
    show()
    pickKind('Items')
    // The example keeps its tools in items/(tools)/: the group, then the items outside it.
    expect(shown()[0]).toBe('Group tools')
    expect(shown()).not.toContain('ruby_wand')
    expect(shown()).toContain('ruby')

    fireEvent.doubleClick(within(items()).getByRole('option', { name: 'Group tools' }))
    expect(shown()).toEqual(['glow_pickaxe', 'ruby_pick', 'ruby_wand'])
    expect(screen.getByRole('navigation', { name: 'Group folders' }).textContent).toContain('tools')

    // The breadcrumb goes back up, and so does Backspace.
    fireEvent.click(screen.getByRole('button', { name: 'Items' }))
    expect(shown()[0]).toBe('Group tools')
    fireEvent.click(within(items()).getByRole('option', { name: 'Group tools' }))
    fireEvent.keyDown(items(), { key: 'Enter' })
    expect(shown()).toEqual(['glow_pickaxe', 'ruby_pick', 'ruby_wand'])
    fireEvent.keyDown(items(), { key: 'Backspace' })
    expect(shown()[0]).toBe('Group tools')
    expect(
      within(items()).getByRole('option', { name: 'Group tools' }).getAttribute('aria-selected'),
    ).toBe('true')
  })

  it('opens a resource in a group from where it is, and finds it by search', async () => {
    show()
    pickKind('Items')
    fireEvent.doubleClick(within(items()).getByRole('option', { name: 'Group tools' }))
    fireEvent.doubleClick(within(items()).getByRole('option', { name: 'ruby_wand' }))
    await waitFor(() => expect(tabs()).toContain('items/(tools)/ruby_wand/item.json'))

    fireEvent.change(search(), { target: { value: 'ruby_pi' } })
    const results = screen.getByRole('listbox', { name: 'Search results' })
    expect(within(results).getByRole('option', { name: 'ruby_pick' }).textContent).toContain(
      'tools',
    )
  })

  it('makes a new resource in the group that is open', async () => {
    show()
    pickKind('Items')
    fireEvent.doubleClick(within(items()).getByRole('option', { name: 'Group tools' }))
    fireEvent.click(screen.getByRole('button', { name: 'New item in tools' }))
    const dialog = await screen.findByRole('dialog', { name: 'New item in tools' })
    fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: 'ruby_axe' } })
    // Ids are the kind's whole: one in another group is taken here too.
    fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: 'ruby' } })
    expect(within(dialog).getByText('That id is taken')).toBeTruthy()
    fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: 'ruby_axe' } })
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Create' }))
    })
    await waitFor(() =>
      expect(backend.testFiles()['items/(tools)/ruby_axe/item.json']).toBeDefined(),
    )
    await waitFor(() => expect(tabs()).toContain('items/(tools)/ruby_axe/item.json'))
  })

  it('moves a resource dropped on a group, or on the breadcrumb, keeping its id', async () => {
    show()
    pickKind('Items')
    const transfer = dataTransfer()
    fireEvent.dragStart(within(items()).getByRole('option', { name: 'ruby' }), {
      dataTransfer: transfer,
    })
    const tools = within(items()).getByRole('option', { name: 'Group tools' })
    fireEvent.dragOver(tools, { dataTransfer: transfer })
    fireEvent.drop(tools, { dataTransfer: transfer })
    await waitFor(() => expect(backend.testFiles()['items/(tools)/ruby/item.json']).toBeDefined())
    expect(backend.testFiles()['items/ruby/item.json']).toBeUndefined()
    expect(shown()).not.toContain('ruby')

    // And back out, onto the kind in the breadcrumb.
    fireEvent.doubleClick(within(items()).getByRole('option', { name: 'Group tools' }))
    const back = dataTransfer()
    fireEvent.dragStart(within(items()).getByRole('option', { name: 'ruby' }), {
      dataTransfer: back,
    })
    const crumb = screen.getByRole('button', { name: 'Items' })
    fireEvent.dragOver(crumb, { dataTransfer: back })
    fireEvent.drop(crumb, { dataTransfer: back })
    await waitFor(() => expect(backend.testFiles()['items/ruby/item.json']).toBeDefined())
  })

  it('moves a resource to a group typed in, making it as it goes', async () => {
    show()
    pickKind('Items')
    fireEvent.contextMenu(within(items()).getByRole('option', { name: 'crown' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Move to group…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Move crown to a group' })
    const group = within(dialog).getByRole('textbox')
    fireEvent.change(group, { target: { value: 'Royal' } })
    expect(within(dialog).getByRole('button', { name: 'Move' }).hasAttribute('disabled')).toBe(true)
    fireEvent.change(group, { target: { value: 'royal/heads' } })
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Move' }))
    })
    await waitFor(() =>
      expect(backend.testFiles()['items/(royal)/(heads)/crown/item.json']).toBeDefined(),
    )
    expect(await within(items()).findByRole('option', { name: 'Group royal' })).toBeTruthy()
  })

  it('renames a group in place with F2, taking what is in it along', async () => {
    show()
    pickKind('Items')
    fireEvent.click(within(items()).getByRole('option', { name: 'Group tools' }))
    fireEvent.keyDown(items(), { key: 'F2' })
    const name = screen.getByRole('textbox', { name: 'Rename group tools' })
    fireEvent.change(name, { target: { value: 'gear' } })
    fireEvent.keyDown(name, { key: 'Enter' })
    await waitFor(() =>
      expect(backend.testFiles()['items/(gear)/ruby_wand/script.lua']).toBeDefined(),
    )
    expect(backend.testFiles()['items/(tools)/ruby_wand/script.lua']).toBeUndefined()
    expect(await within(items()).findByRole('option', { name: 'Group gear' })).toBeTruthy()
  })

  it('makes an empty group with New group, inside the open one, and deletes it', async () => {
    show()
    pickKind('Items')
    fireEvent.click(screen.getByRole('button', { name: 'New group' }))
    const dialog = await screen.findByRole('dialog', { name: 'New group of items' })
    // A group's name is an id, and one by that name is already here.
    fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: 'tools' } })
    expect(within(dialog).getByText('There is a group by that name here')).toBeTruthy()
    fireEvent.change(within(dialog).getByRole('textbox'), { target: { value: 'armour' } })
    await act(async () => {
      fireEvent.click(within(dialog).getByRole('button', { name: 'Create' }))
    })
    // Kept by a hidden file, so the empty folder is there (and git keeps it).
    await waitFor(() => expect(backend.testFiles()['items/(armour)/.gitkeep']).toBe(''))
    const armour = await within(items()).findByRole('option', { name: 'Group armour' })
    expect(armour.getAttribute('aria-selected')).toBe('true')

    // Inside it, it says it's empty, and a group made there goes in it.
    fireEvent.doubleClick(armour)
    expect(screen.getByText('This group is empty.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'New group in armour' }))
    const inner = await screen.findByRole('dialog', { name: 'New group in armour' })
    fireEvent.change(within(inner).getByRole('textbox'), { target: { value: 'helmets' } })
    await act(async () => {
      fireEvent.click(within(inner).getByRole('button', { name: 'Create' }))
    })
    await waitFor(() => expect(backend.testFiles()['items/(armour)/(helmets)/.gitkeep']).toBe(''))

    // Deleting it asks, then takes the folder and what's in it.
    fireEvent.click(screen.getByRole('button', { name: 'Items' }))
    fireEvent.contextMenu(within(items()).getByRole('option', { name: 'Group armour' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Delete group' }))
    const confirm = await screen.findByRole('dialog', { name: 'Delete group armour' })
    await act(async () => {
      fireEvent.click(within(confirm).getByRole('button', { name: 'Delete' }))
    })
    await waitFor(() =>
      expect(Object.keys(backend.testFiles()).some((it) => it.startsWith('items/(armour)'))).toBe(
        false,
      ),
    )
    expect(within(items()).queryByRole('option', { name: 'Group armour' })).toBeNull()
  })

  it("doesn't offer to group a resource pack", () => {
    show()
    pickKind('Resource packs')
    const packs = screen.getByRole('listbox', { name: 'Resource packs' })
    fireEvent.contextMenu(within(packs).getAllByRole('option')[0]!)
    expect(screen.getByRole('menuitem', { name: 'Duplicate' })).toBeTruthy()
    expect(screen.queryByRole('menuitem', { name: 'Move to group…' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'New group' })).toBeNull()
  })
})
