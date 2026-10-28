import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { EXAMPLE_ROOT } from '@/testing/backend/seed'
import { openExampleApp } from '@/testing/workspace'
import { DialogHost } from '@/ui/dialogs'
import { askMoveModule, askNewServer } from './workspaceActions'

afterEach(cleanup)

async function open() {
  const example = await openExampleApp({
    backend: {
      workspaces: {
        '/memory': JSON.stringify({
          name: 'Net',
          projects: ['showcase', 'library'],
          network: { arrival: 'hub', servers: { hub: 'showcase' } },
        }),
      },
    },
  })
  await example.app.workspace.getState().openProject(EXAMPLE_ROOT)
  await example.app.workspaceFile.getState().refresh()
  render(<DialogHost />)
  return { ...example, stores: { ...example.app, backend: example.backend } }
}

describe('workspace actions', () => {
  it('ask for a new server, make it, and leave it unopened when asked to', async () => {
    const { app, stores } = await open()
    const user = userEvent.setup()
    let made: ReturnType<typeof askNewServer> | undefined
    act(() => {
      made = askNewServer(stores)
    })
    const field = await screen.findByRole('textbox')
    await user.type(field, 'hub')
    expect(await screen.findByText(/already a server/)).toBeTruthy()
    await user.clear(field)
    await user.type(field, 'arena{Enter}')
    await screen.findByRole('dialog', { name: 'arena is ready' })
    await user.keyboard('{Escape}')
    expect((await made)?.path).toBe('arena')
    expect(app.workspace.getState().project?.root).toBe(EXAMPLE_ROOT)
    expect(app.workspaceFile.getState().file?.network?.servers.arena).toBe('arena')
  })

  it('ask where a module goes, say what moving it does, and move it', async () => {
    const { app, stores } = await open()
    const user = userEvent.setup()
    let moved: Promise<boolean> | undefined
    act(() => {
      moved = askMoveModule(stores, 'greeter', 'modules/greeter')
    })
    const field = await screen.findByLabelText(
      "A project of the workspace, or a new package's folder",
    )
    await user.clear(field)
    await user.type(field, 'packages/greeting{Enter}')
    const confirm = await screen.findByRole('dialog', { name: 'Move greeter into greeting' })
    expect(confirm.textContent).toMatch(/A new package, greeting/)
    expect(confirm.textContent).toMatch(/depends on library/)
    await user.click(screen.getByRole('button', { name: 'Move' }))
    expect(await moved).toBe(true)
    await waitFor(() =>
      expect(app.workspace.getState().files.some((it) => it.startsWith('modules/greeter/'))).toBe(
        false,
      ),
    )
  })
})
