import { act, cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { closeMenu, ContextMenuHost, openMenu, type MenuItem } from './ContextMenu'

afterEach(() => {
  act(closeMenu)
  cleanup()
})

function setup(items: MenuItem[]) {
  const user = userEvent.setup()
  render(
    <>
      <button type="button">Owner</button>
      <ContextMenuHost />
    </>,
  )
  const owner = screen.getByRole('button', { name: 'Owner' })
  owner.focus()
  act(() => openMenu({ clientX: 10, clientY: 10, preventDefault: () => {} }, items))
  return { user, owner }
}

describe('ContextMenu', () => {
  it('focuses the first enabled item, skips disabled ones, and Enter runs one', async () => {
    const rename = vi.fn()
    const remove = vi.fn()
    const { user } = setup([
      { label: 'Cut', disabled: true, run: () => {} },
      { label: 'Rename', shortcut: 'F2', run: rename },
      'separator',
      { label: 'Delete', danger: true, run: remove },
    ])
    const menu = screen.getByRole('menu', { name: 'Context menu' })
    expect(menu).toBeTruthy()
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: 'Rename' }))
    await user.keyboard('{ArrowDown}')
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: 'Delete' }))
    // Wraps past the disabled first item.
    await user.keyboard('{ArrowDown}')
    expect(document.activeElement).toBe(screen.getByRole('menuitem', { name: 'Rename' }))
    await user.keyboard('{Enter}')
    expect(rename).toHaveBeenCalledOnce()
    expect(remove).not.toHaveBeenCalled()
    expect(screen.queryByRole('menu')).toBeNull()
  })

  it('Escape closes without running anything and gives focus back', async () => {
    const run = vi.fn()
    const { user, owner } = setup([{ label: 'Rename', run }])
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('menu')).toBeNull()
    expect(run).not.toHaveBeenCalled()
    await act(() => new Promise(requestAnimationFrame))
    expect(document.activeElement).toBe(owner)
  })

  it('a click runs an item', async () => {
    const run = vi.fn()
    const { user } = setup([{ label: 'Duplicate', run }])
    await user.click(screen.getByRole('menuitem', { name: 'Duplicate' }))
    expect(run).toHaveBeenCalledOnce()
  })
})
