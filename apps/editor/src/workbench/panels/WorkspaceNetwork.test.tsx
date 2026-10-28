import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { EXAMPLE_ROOT, LIBRARY_ROOT } from '@/testing/backend/seed'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import type { MemoryBackend } from '@/testing/backend/memory'
import { ContextMenuHost } from '@/ui/ContextMenu'
import { Toolbar } from '@/workbench/toolbar/Toolbar'
import { ConsolePanel } from './Console'
import { NetworkPanel } from './Network'
import { serverNameFor, suggestNetwork } from './WorkspaceNetwork'

afterEach(cleanup)

/** The showcase and the library beside it, as one workspace's projects. */
const WORKSPACE = JSON.stringify({ name: 'Memory net', projects: ['showcase', 'library'] })

async function show(): Promise<{ app: AppStores; backend: MemoryBackend }> {
  const example = await openExampleApp({
    backend: { eulaAccepted: true, trusted: [], workspaces: { '/memory': WORKSPACE } },
  })
  await example.app.network.getState().connect()
  await example.app.run.getState().connect()
  await example.app.workspaceFile.getState().refresh()
  render(
    <AppProvider app={example.app}>
      <Toolbar onSettings={() => {}} onQuickOpen={() => {}} />
      <NetworkPanel />
      <ConsolePanel />
      <ContextMenuHost />
    </AppProvider>,
  )
  return example
}

describe('a workspace in the editor', () => {
  it('suggests a first network from the projects that are servers', () => {
    expect(serverNameFor('servers/Chess Club')).toBe('chess-club')
    expect(
      suggestNetwork([
        { path: 'servers/game', root: '/w/servers/game', name: 'Game', package: false },
        { path: 'servers/hub', root: '/w/servers/hub', name: 'Hub', package: false },
        { path: 'packages/chess', root: '/w/packages/chess', name: 'Chess', package: true },
      ]),
    ).toEqual({ arrival: 'hub', servers: { game: 'servers/game', hub: 'servers/hub' } })
    expect(suggestNetwork([])).toBeNull()
  })

  it('trusts the whole workspace, sets up its network in the file, and runs it attached here', async () => {
    const { app, backend } = await show()
    expect(app.workspace.getState().project?.workspace?.name).toBe('Memory net')
    expect(backend.workspaceSchemas.get('/memory')).toContain('"$schema"')
    expect(
      await screen.findByRole('button', { name: /Workspace Memory net: .*Switch project/ }),
    ).toBeTruthy()

    await app.workspaceFile.getState().trust(true)
    expect(app.workspace.getState().project?.trusted).toBe(true)

    fireEvent.click(await screen.findByRole('button', { name: 'Set up a network' }))
    // The library is a package (it exports): a server is suggested for the showcase alone.
    await waitFor(() =>
      expect(app.workspaceFile.getState().file?.network).toEqual({
        arrival: 'showcase',
        servers: { showcase: 'showcase' },
      }),
    )
    expect(JSON.parse(await backend.workspaceRead()).network.arrival).toBe('showcase')

    // Another server, running the library: added, its project picked, renamed.
    fireEvent.click(screen.getByRole('button', { name: 'Add server' }))
    const added = await screen.findByRole('combobox', { name: 'Project of server showcase-2' })
    fireEvent.change(added, { target: { value: 'library' } })
    await waitFor(() =>
      expect(app.workspaceFile.getState().file?.network?.servers['showcase-2']).toBe('library'),
    )
    const rename = screen.getByRole('textbox', { name: 'Name of server showcase-2' })
    fireEvent.change(rename, { target: { value: 'library' } })
    fireEvent.blur(rename)
    await waitFor(() =>
      expect(app.workspaceFile.getState().file?.network?.servers).toEqual({
        showcase: 'showcase',
        library: 'library',
      }),
    )

    // A name that can't be one stays out of the file, saying why.
    const name = await screen.findByRole('textbox', { name: 'Name of server library' })
    fireEvent.change(name, { target: { value: 'Lib Rary' } })
    fireEvent.blur(name)
    expect((await screen.findByRole('alert')).textContent).toMatch(/can't name a server/)

    fireEvent.click(screen.getByRole('button', { name: 'Make players arrive on library' }))
    await waitFor(() => expect(app.workspaceFile.getState().file?.network?.arrival).toBe('library'))

    fireEvent.click(screen.getByRole('switch', { name: "Run the workspace's dev network" }))
    await waitFor(() => expect(app.workspaceFile.getState().networkEnabled).toBe(true))
    await app.run.getState().start()
    await waitFor(() =>
      expect(app.network.getState().members.map((m) => m.name)).toEqual([
        'proxy',
        'library',
        'showcase',
      ]),
    )
    await waitFor(() => expect(app.run.getState().server.phase).toBe('running'))
    // The Console's own entry is the attached server's, listed once.
    const switcher = await screen.findByRole('group', { name: 'Console of' })
    await waitFor(() =>
      expect(
        within(switcher)
          .getAllByRole('button')
          .map((it) => it.textContent),
      ).toEqual(['showcase', 'proxy', 'library']),
    )
  })

  it('switches to another project from the toolbar, the network staying up', async () => {
    const { app } = await show()
    await app.workspaceFile.getState().trust(true)
    await app.workspaceFile
      .getState()
      .saveNetwork({ arrival: 'showcase', servers: { showcase: 'showcase', library: 'library' } })
    await app.workspaceFile.getState().setNetworkEnabled(true)
    await app.run.getState().start()
    await waitFor(() =>
      expect(app.network.getState().members.find((m) => m.name === 'library')?.state.phase).toBe(
        'running',
      ),
    )

    fireEvent.click(screen.getByRole('button', { name: /Switch project/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Library example/ }))
    await waitFor(() => expect(app.workspace.getState().project?.root).toBe(LIBRARY_ROOT))
    await waitFor(() =>
      expect(app.network.getState().members.find((m) => m.attached)?.name).toBe('library'),
    )
    expect(app.run.getState().server.phase).toBe('running')
    expect(app.network.getState().members.every((m) => m.state.phase === 'running')).toBe(true)
    // And back: attached to the showcase's server again.
    fireEvent.click(screen.getByRole('button', { name: /Switch project/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /NetherForge Showcase/ }))
    await waitFor(() => expect(app.workspace.getState().project?.root).toBe(EXAMPLE_ROOT))
    await waitFor(() =>
      expect(app.network.getState().members.find((m) => m.attached)?.name).toBe('showcase'),
    )
  })
})
