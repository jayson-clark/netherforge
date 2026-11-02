import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { EXAMPLE_ROOT, LIBRARY_ROOT } from '@/testing/backend/seed'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { ConsolePanel } from './Console'
import { NetworkPanel, newServerName } from './Network'

afterEach(cleanup)

async function show(trusted?: string[]): Promise<{ app: AppStores }> {
  const example = await openExampleApp({
    backend: {
      eulaAccepted: true,
      pickFolderResults: [LIBRARY_ROOT],
      ...(trusted ? { trusted } : {}),
    },
  })
  await example.app.network.getState().connect()
  await example.app.run.getState().connect()
  await example.app.network.getState().load()
  render(
    <AppProvider app={example.app}>
      <NetworkPanel />
      <ConsolePanel />
    </AppProvider>,
  )
  return example
}

/** Turns the network on with one other server, `lobby`, running the library. */
async function setUp(app: AppStores) {
  fireEvent.click(await screen.findByRole('switch', { name: 'Run the dev server as a network' }))
  await waitFor(() => expect(app.network.getState().layout?.enabled).toBe(true))
  fireEvent.click(screen.getByRole('button', { name: 'Add server' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Choose the project lobby runs' }))
  await waitFor(() =>
    expect(app.network.getState().layout?.servers).toEqual([
      { name: 'lobby', project: LIBRARY_ROOT },
    ]),
  )
}

describe('the Network panel', () => {
  it('names new servers apart from the others', () => {
    expect(newServerName({ enabled: false, name: 'main', servers: [] })).toBe('lobby')
    expect(
      newServerName({
        enabled: false,
        name: 'lobby',
        servers: [{ name: 'server-2', project: '.' }],
      }),
    ).toBe('server-3')
  })

  it('sets up a network: switched on, a server added, its folder picked and trusted', async () => {
    const { app } = await show([EXAMPLE_ROOT])
    expect(screen.getByText("Start runs this project's server on its own.")).toBeTruthy()
    await setUp(app)
    expect(screen.getByText(/Players join localhost:25565 and arrive on main/)).toBeTruthy()
    const servers = screen.getByRole('list', { name: 'Network servers' })
    expect(within(servers).getByText('Library example · Minecraft 26.3')).toBeTruthy()

    fireEvent.click(await screen.findByRole('button', { name: 'Trust folder' }))
    await waitFor(() => expect(app.network.getState().checks[0]!.trusted).toBe(true))
    expect(screen.queryByRole('button', { name: 'Trust folder' })).toBeNull()

    // A name the backend refuses stays in the field, saying why.
    const name = screen.getByRole('textbox', { name: 'Name of server lobby' })
    fireEvent.change(name, { target: { value: 'Lobby' } })
    fireEvent.blur(name)
    expect((await screen.findByRole('alert')).textContent).toMatch(/can't name a server/)

    await app.run.getState().start()
    await waitFor(() => expect(within(servers).getAllByText('Running')).toHaveLength(3))
    expect(screen.getByRole('status').textContent).toBe('3 of 3 running')

    // As a production network would run it: a group, a capacity, a role with a credential of its own.
    const group = screen.getByRole('textbox', { name: 'Group of server lobby' })
    fireEvent.change(group, { target: { value: 'arena' } })
    fireEvent.blur(group)
    await waitFor(() =>
      expect(app.network.getState().layout?.servers[0]!.options).toEqual({ group: 'arena' }),
    )
    const capacity = screen.getByRole('textbox', { name: 'Capacity of server lobby' })
    fireEvent.change(capacity, { target: { value: '16' } })
    fireEvent.blur(capacity)
    await waitFor(() =>
      expect(app.network.getState().layout?.servers[0]!.options?.capacity).toBe(16),
    )
    fireEvent.change(screen.getByRole('combobox', { name: 'Role of server lobby' }), {
      target: { value: 'standard' },
    })
    await waitFor(() =>
      expect(app.network.getState().layout?.servers[0]!.options).toEqual({
        group: 'arena',
        capacity: 16,
        role: 'standard',
      }),
    )
    // Back to trusted: the role is left out, as it's the default.
    fireEvent.change(screen.getByRole('combobox', { name: 'Role of server lobby' }), {
      target: { value: 'trusted' },
    })
    await waitFor(() =>
      expect(app.network.getState().layout?.servers[0]!.options).toEqual({
        group: 'arena',
        capacity: 16,
      }),
    )
    const bad = screen.getByRole('textbox', { name: 'Group of server lobby' })
    fireEvent.change(bad, { target: { value: 'Arena Group' } })
    fireEvent.blur(bad)
    expect((await screen.findByRole('alert')).textContent).toMatch(/can't name a group/)

    fireEvent.click(screen.getByRole('button', { name: 'Remove server lobby' }))
    await waitFor(() => expect(app.network.getState().layout?.servers).toEqual([]))
  })

  it("switches the Console between this project's server, the proxy and the others", async () => {
    const { app } = await show()
    // Alone, the Console is the project's server's and has no switcher.
    expect(screen.queryByRole('group', { name: 'Console of' })).toBeNull()
    await setUp(app)
    await app.run.getState().start()
    const switcher = await screen.findByRole('group', { name: 'Console of' })
    await waitFor(() =>
      expect(
        within(switcher)
          .getAllByRole('button')
          .map((it) => it.textContent),
      ).toEqual(['main', 'proxy', 'lobby']),
    )
    expect(
      within(switcher).getByRole('button', { name: 'main' }).getAttribute('aria-pressed'),
    ).toBe('true')

    fireEvent.click(within(switcher).getByRole('button', { name: 'proxy' }))
    const proxyLog = await screen.findByRole('log', { name: 'Console of proxy' })
    await waitFor(() => expect(proxyLog.textContent).toContain('Done'))

    // A card's console button shows that server's.
    await waitFor(() =>
      expect(
        (screen.getByRole('button', { name: "Show lobby's console" }) as HTMLButtonElement)
          .disabled,
      ).toBe(false),
    )
    fireEvent.click(screen.getByRole('button', { name: "Show lobby's console" }))
    expect(app.network.getState().viewing).toBe('lobby')
    const command = await screen.findByRole('textbox', { name: 'Command for lobby' })
    fireEvent.change(command, { target: { value: 'say hi' } })
    fireEvent.submit(command.closest('form')!)
    await waitFor(() =>
      expect(screen.getByRole('log', { name: 'Console of lobby' }).textContent).toContain(
        '> say hi',
      ),
    )
    // The project's own server's console takes its own commands again.
    fireEvent.click(within(switcher).getByRole('button', { name: 'main' }))
    expect(screen.getByRole('textbox', { name: 'Server command' })).toBeTruthy()
    expect(screen.getByRole('log', { name: 'Console' })).toBeTruthy()

    // Stopped and switched off, the switcher goes once the network does.
    await app.run.getState().stop()
    await app.network.getState().setLayout({ ...app.network.getState().layout!, enabled: false })
    await app.run.getState().start()
    await waitFor(() => expect(screen.queryByRole('group', { name: 'Console of' })).toBeNull())
  })
})
