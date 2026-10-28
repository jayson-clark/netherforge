import { act, cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AppMenu, MenuEntry } from '@/core/backend/types'
import { MenuBar } from './MenuBar'
import { useMenus } from './useAppMenus'

const item = (id: string, enabled = true): MenuEntry => ({ kind: 'item', id, label: id, enabled })

const MENUS: AppMenu[] = [
  {
    label: 'File',
    items: [
      item('new'),
      { kind: 'submenu', label: 'Open Recent', enabled: true, items: [item('a'), item('b')] },
      { kind: 'separator' },
      item('save', false),
      item('close'),
    ],
  },
  {
    label: 'View',
    items: [
      { kind: 'item', id: 'outline', label: 'Outline', enabled: true, checked: true },
      { kind: 'item', id: 'inspector', label: 'Inspector', enabled: true, checked: false },
      { kind: 'separator' },
      item('zoom'),
    ],
  },
]

let run: ReturnType<typeof vi.fn<(id: string) => void>>

beforeEach(() => {
  run = vi.fn<(id: string) => void>()
  useMenus.setState({ menus: MENUS, run })
})
afterEach(cleanup)

function setup() {
  const user = userEvent.setup()
  render(
    <>
      <MenuBar />
      <input aria-label="Field" />
    </>,
  )
  const field = screen.getByRole('textbox', { name: 'Field' })
  field.focus()
  return { user, field }
}

// A chosen item runs a frame after its menu closes.
const nextFrame = () => new Promise(requestAnimationFrame)

// The menus are portalled out of the bar: inside it are only its buttons.
const top = (name: string) =>
  within(screen.getByRole('menubar', { name: 'Menu' })).getByRole('menuitem', { name })

describe('MenuBar', () => {
  it('opens on click without taking focus from the field, and runs an item once focus is back', async () => {
    const { user, field } = setup()
    await user.click(top('File'))
    expect(screen.getByRole('menu', { name: 'File' })).toBeTruthy()
    // A disabled item is there, but can't be chosen.
    expect(screen.getByRole('menuitem', { name: 'save' }).getAttribute('aria-disabled')).toBe(
      'true',
    )
    let focusedWhenRun: Element | null = null
    run.mockImplementation(() => (focusedWhenRun = document.activeElement))
    await user.click(screen.getByRole('menuitem', { name: 'close' }))
    await act(nextFrame)
    expect(run).toHaveBeenCalledExactlyOnceWith('close')
    expect(focusedWhenRun).toBe(field)
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('walks the bar with Left and Right, and opens a submenu with Right', async () => {
    const { user, field } = setup()
    await user.click(top('File'))
    await user.keyboard('{ArrowDown}')
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: 'new' }))
    await user.keyboard('{ArrowRight}')
    expect(screen.getByRole('menu', { name: 'View' })).toBeTruthy()
    expect(screen.queryByRole('menu', { name: 'File' })).toBeNull()
    expect(document.activeElement).toBe(screen.getByRole('menuitemcheckbox', { name: 'Outline' }))
    await user.keyboard('{ArrowLeft}')
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: 'new' }))
    await user.keyboard('{ArrowDown}{ArrowRight}')
    expect(screen.getByRole('menu', { name: 'Open Recent' })).toBeTruthy()
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: 'a' }))
    await user.keyboard('{ArrowDown}{Enter}')
    await act(nextFrame)
    expect(run).toHaveBeenCalledExactlyOnceWith('b')
    expect(document.activeElement).toBe(field)
  })

  it('draws toggles as checkboxes, and Escape closes without running anything', async () => {
    const { user, field } = setup()
    await user.click(top('View'))
    expect(
      screen.getByRole('menuitemcheckbox', { name: 'Outline' }).getAttribute('aria-checked'),
    ).toBe('true')
    expect(
      screen.getByRole('menuitemcheckbox', { name: 'Inspector' }).getAttribute('aria-checked'),
    ).toBe('false')
    expect(screen.getByRole('menuitem', { name: 'zoom' })).toBeTruthy()
    await user.keyboard('{Escape}')
    await act(nextFrame)
    expect(screen.queryByRole('menu')).toBeNull()
    expect(run).not.toHaveBeenCalled()
    expect(document.activeElement).toBe(field)
  })

  it('Alt on its own opens the first menu with its first item focused', async () => {
    const { user } = setup()
    await user.keyboard('{Alt}')
    expect(screen.getByRole('menu', { name: 'File' })).toBeTruthy()
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: 'new' }))
    await act(() => user.keyboard('{Alt}'))
    expect(screen.queryByRole('menu')).toBeNull()
  })
})
