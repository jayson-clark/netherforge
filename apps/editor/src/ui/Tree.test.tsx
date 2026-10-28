import { act, cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { closeMenu, ContextMenuHost } from './ContextMenu'
import { selectionOrder, Tree, type TreeNode, type TreeProps } from './Tree'

afterEach(() => {
  act(closeMenu)
  cleanup()
})

const NODES: TreeNode[] = [
  {
    id: 'lib',
    label: 'lib',
    children: [
      { id: 'lib/a.lua', label: 'a.lua' },
      { id: 'lib/deep', label: 'deep', children: [{ id: 'lib/deep/b.lua', label: 'b.lua' }] },
    ],
  },
  { id: 'script.lua', label: 'script.lua' },
]

function Harness(props: Partial<TreeProps> & { initiallyOpen?: string[] }) {
  const [open, setOpen] = useState(new Set(props.initiallyOpen ?? []))
  const [selected, setSelected] = useState<string | null>(null)
  return (
    <>
      <Tree
        label="Files"
        nodes={NODES}
        selected={selected}
        onSelect={setSelected}
        isExpanded={(node) => open.has(node.id)}
        onExpand={(id, value) => {
          const next = new Set(open)
          if (value) next.add(id)
          else next.delete(id)
          setOpen(next)
        }}
        {...props}
      />
      <ContextMenuHost />
    </>
  )
}

// react-aria's tree is a treegrid of rows.
const tree = () => screen.getByRole('treegrid', { name: 'Files' })
const row = (name: string) => within(tree()).getByRole('row', { name })
const noRow = (name: string) => within(tree()).queryByRole('row', { name })

function setup(props: Parameters<typeof Harness>[0] = {}) {
  const user = userEvent.setup()
  render(<Harness {...props} />)
  return user
}

describe('Tree', () => {
  it('opens and closes rows with the twisty and the arrow keys', async () => {
    const user = setup()
    expect(noRow('a.lua')).toBeNull()
    expect(row('lib').getAttribute('aria-expanded')).toBe('false')
    await user.click(within(row('lib')).getByRole('button', { name: /expand/i }))
    expect(row('a.lua')).toBeTruthy()
    // The twisty opens and closes; it doesn't select.
    expect(row('lib').getAttribute('aria-selected')).toBe('false')

    row('lib').focus()
    await user.keyboard('{ArrowLeft}')
    expect(noRow('a.lua')).toBeNull()
    await user.keyboard('{ArrowRight}{ArrowDown}')
    expect(document.activeElement).toBe(row('a.lua'))
    // Left from a child goes to its parent.
    await user.keyboard('{ArrowLeft}')
    expect(document.activeElement).toBe(row('lib'))
    await user.keyboard('{End}')
    expect(document.activeElement).toBe(row('script.lua'))
    await user.keyboard('{Home}')
    expect(document.activeElement).toBe(row('lib'))
  })

  it('moves focus without selecting unless asked to; Enter opens and Space selects', async () => {
    const onOpen = vi.fn()
    let user = setup({ initiallyOpen: ['lib'], onOpen })
    await user.click(row('lib'))
    expect(row('lib').getAttribute('aria-selected')).toBe('true')
    await user.keyboard('{ArrowDown}')
    expect(document.activeElement).toBe(row('a.lua'))
    expect(row('a.lua').getAttribute('aria-selected')).toBe('false')
    await user.keyboard('{Enter}')
    expect(onOpen).toHaveBeenCalledWith('lib/a.lua')
    await user.keyboard(' ')
    expect(row('a.lua').getAttribute('aria-selected')).toBe('true')
    cleanup()

    user = setup({ initiallyOpen: ['lib'], selectOnFocus: true })
    await user.click(row('lib'))
    await user.keyboard('{ArrowDown}')
    expect(row('a.lua').getAttribute('aria-selected')).toBe('true')
  })

  it('finds a row by typing its name', async () => {
    const user = setup()
    row('lib').focus()
    await user.keyboard('scr')
    expect(document.activeElement).toBe(row('script.lua'))
  })

  it('renames inline with F2: Enter commits a valid name, an invalid one says why', async () => {
    const commit = vi.fn()
    const user = setup({
      rename: (id) => ({
        initial: id,
        validate: (value) => (value.endsWith('.lua') ? null : 'Must end in .lua'),
        commit: (value) => commit(id, value),
      }),
    })
    await user.click(row('script.lua'))
    await user.keyboard('{F2}')
    const input = screen.getByRole('textbox', { name: 'Rename script.lua' })
    await user.clear(input)
    await user.type(input, 'main')
    expect(screen.getByRole('alert').textContent).toBe('Must end in .lua')
    await user.keyboard('{Enter}')
    expect(commit).not.toHaveBeenCalled()
    await user.type(input, '.lua{Enter}')
    expect(commit).toHaveBeenCalledExactlyOnceWith('script.lua', 'main.lua')
    expect(screen.queryByRole('textbox')).toBeNull()
    // Back on the tree's row.
    expect(document.activeElement).toBe(row('script.lua'))
  })

  it('Escape cancels a rename, and leaving an unchanged name commits nothing', async () => {
    const commit = vi.fn()
    const user = setup({ rename: (id) => ({ initial: id, validate: () => null, commit }) })
    await user.click(row('script.lua'))
    await user.keyboard('{F2}')
    await user.type(screen.getByRole('textbox', { name: 'Rename script.lua' }), 'x')
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('textbox')).toBeNull()
    await user.keyboard('{F2}')
    act(() => screen.getByRole('textbox', { name: 'Rename script.lua' }).blur())
    expect(commit).not.toHaveBeenCalled()
  })

  it('deletes with Delete or Cmd+Backspace, never plain Backspace, and duplicates with Cmd+D', async () => {
    const onDelete = vi.fn()
    const onDuplicate = vi.fn()
    const user = setup({ onDelete, onDuplicate })
    await user.click(row('script.lua'))
    await user.keyboard('{Backspace}')
    expect(onDelete).not.toHaveBeenCalled()
    await user.keyboard('{Control>}{Backspace}{/Control}{Delete}')
    expect(onDelete).toHaveBeenCalledTimes(2)
    await user.keyboard('{Control>}d{/Control}')
    expect(onDuplicate).toHaveBeenCalledWith('script.lua')
  })

  it('the context-menu key opens the row’s menu', async () => {
    const run = vi.fn()
    const user = setup({ menu: (id) => [{ label: `Rename ${id}`, run }] })
    await user.click(row('script.lua'))
    await user.keyboard('{ContextMenu}')
    await user.keyboard('{Enter}')
    expect(run).toHaveBeenCalledOnce()
  })

  it('creates inline inside a row', async () => {
    const commit = vi.fn()
    const cancel = vi.fn()
    const user = setup({
      initiallyOpen: ['lib'],
      create: {
        parent: 'lib',
        icon: 'file',
        label: 'New file name',
        initial: '',
        validate: (value) => (value ? null : 'Name it'),
        commit,
        cancel,
      },
    })
    const input = screen.getByRole('textbox', { name: 'New file name' })
    expect(document.activeElement).toBe(input)
    await user.type(input, 'c.lua{Enter}')
    expect(commit).toHaveBeenCalledWith('c.lua')
    expect(cancel).not.toHaveBeenCalled()
  })

  it('gives every row of a tree that moves rows a drag button, a fixed row a disabled one', () => {
    // react-aria warns (once per row, as it mounts) when a draggable row has no drag button.
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      setup({
        nodes: [...NODES, { id: 'main.json', label: 'main.json', fixed: true }],
        initiallyOpen: ['lib'],
        onMove: () => {},
        create: {
          parent: 'lib',
          icon: 'file',
          label: 'New file name',
          initial: '',
          validate: () => null,
          commit: () => {},
          cancel: () => {},
        },
      })
      const drag = (name: string) =>
        within(row(name))
          .queryAllByRole('button')
          .find((it) => it.className.includes('dragHandle'))
      expect(drag('a.lua')).toBeTruthy()
      expect(drag('main.json')).toBeTruthy()
      expect(drag('main.json')?.hasAttribute('disabled')).toBe(true)
      const warnings = warn.mock.calls.map((call) => String(call[0]))
      expect(warnings.filter((it) => it.includes('slot="drag"'))).toEqual([])
    } finally {
      warn.mockRestore()
    }
  })
})

function MultiHarness({ onChange }: { onChange: (ids: string[]) => void }) {
  const [selection, setSelection] = useState<string[]>([])
  const change = (ids: string[]) => {
    setSelection(ids)
    onChange(ids)
  }
  return (
    <Tree
      label="Files"
      nodes={NODES}
      selected={selection.at(-1) ?? null}
      onSelect={(id) => change([id])}
      multiple={{ selection, onChange: change }}
      isExpanded={() => true}
      onExpand={() => {}}
    />
  )
}

describe('Tree with several rows selected', () => {
  it('extends with Shift, toggles with Cmd/Ctrl, the row acted on last being the primary', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<MultiHarness onChange={onChange} />)
    const last = () => onChange.mock.lastCall![0] as string[]

    await user.click(row('a.lua'))
    expect(last()).toEqual(['lib/a.lua'])
    await user.keyboard('{Control>}')
    await user.click(row('script.lua'))
    await user.keyboard('{/Control}')
    expect(last()).toEqual(['lib/a.lua', 'script.lua'])
    expect(row('a.lua').getAttribute('aria-selected')).toBe('true')
    expect(row('script.lua').getAttribute('aria-selected')).toBe('true')

    // Cmd/Ctrl-click again takes it out.
    await user.keyboard('{Control>}')
    await user.click(row('a.lua'))
    await user.keyboard('{/Control}')
    expect(last()).toEqual(['script.lua'])

    // Shift+arrows extend from the focused row.
    row('script.lua').focus()
    await user.keyboard('{Shift>}{ArrowUp}{/Shift}')
    expect(last()).toEqual(['script.lua', 'lib/deep/b.lua'])

    // A plain click picks one row alone.
    await user.click(row('lib'))
    expect(last()).toEqual(['lib'])
  })
})

describe('selectionOrder', () => {
  it('keeps what stayed where it was and puts the row acted on last', () => {
    expect(selectionOrder(['a', 'b'], ['b', 'a', 'c'], 'c')).toEqual(['a', 'b', 'c'])
    expect(selectionOrder(['a', 'b', 'c'], ['a', 'c', 'b'], 'a')).toEqual(['b', 'c', 'a'])
    expect(selectionOrder(['a', 'b'], ['b'], 'a')).toEqual(['b'])
  })
})
