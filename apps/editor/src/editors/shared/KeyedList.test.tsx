import { act, cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { closeMenu, ContextMenuHost } from '@/ui/ContextMenu'
import { KeyedList, nameProblem } from './KeyedList'

afterEach(() => {
  act(closeMenu)
  cleanup()
})

function Harness({
  onRename,
  onDelete,
}: {
  onRename: (from: string, to: string) => void
  onDelete: (key: string) => void
}) {
  const [selected, setSelected] = useState<string | null>(null)
  return (
    <>
      <KeyedList
        title="Emitters"
        one="emitter"
        pane={{ open: true, onToggle: () => {} }}
        list="emitters"
        lead={{ key: '', label: 'Effect', icon: 'sparkles' }}
        entries={[{ key: 'ring' }, { key: 'trail' }]}
        selected={selected}
        onSelect={setSelected}
        onAdd={() => {}}
        onRename={onRename}
        onDelete={onDelete}
      />
      <ContextMenuHost />
    </>
  )
}

const tree = () => screen.getByRole('treegrid', { name: 'Emitters' })
const row = (name: string) => within(tree()).getByRole('row', { name })

describe('KeyedList', () => {
  it("lists entries under their file path, with the pane's add button", () => {
    render(<Harness onRename={() => {}} onDelete={() => {}} />)
    expect(row('ring').getAttribute('data-path')).toBe('emitters.ring')
    expect(row('Effect').getAttribute('data-path')).toBeNull()
    expect(screen.getByRole('button', { name: 'Add emitter' })).toBeTruthy()
  })

  it('renames under the name rule and against the names taken, and deletes entries only', async () => {
    const onRename = vi.fn()
    const onDelete = vi.fn()
    const user = userEvent.setup()
    render(<Harness onRename={onRename} onDelete={onDelete} />)
    await user.click(row('ring'))
    await user.keyboard('{F2}')
    const input = screen.getByRole('textbox', { name: 'Rename ring' })
    await user.clear(input)
    await user.type(input, 'trail')
    expect(screen.getByRole('alert').textContent).toBe('An emitter with that name exists')
    await user.clear(input)
    await user.type(input, 'halo{Enter}')
    expect(onRename).toHaveBeenCalledExactlyOnceWith('ring', 'halo')

    await user.click(row('Effect'))
    await user.keyboard('{Delete}')
    expect(onDelete).not.toHaveBeenCalled()
    await user.click(row('trail'))
    await user.keyboard('{Delete}')
    expect(onDelete).toHaveBeenCalledExactlyOnceWith('trail')
  })

  it('says what a name breaks', () => {
    expect(nameProblem('ok_name', false, 'clip')).toBeNull()
    expect(nameProblem('ok_name', true, 'clip')).toBe('A clip with that name exists')
    expect(nameProblem('no spaces', false, 'clip')).toMatch(/^[A-Z]/)
  })
})
