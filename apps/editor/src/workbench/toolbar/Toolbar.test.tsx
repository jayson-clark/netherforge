import { DEFAULT_DOCKS } from '@/core/store/docks'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AppInfo } from '@/core/backend/types'
import { AppProvider } from '@/state/providers'
import { openExampleApp, type ExampleOptions } from '@/testing/workspace'
import { useAppMenus } from '@/workbench/menus/useAppMenus'
import { titleBarOf } from './TitleBar'
import { Toolbar } from './Toolbar'

afterEach(cleanup)

/** The toolbar over the example, as the app on [os] (the memory backend says Linux). */
async function show(os: AppInfo['os'] = 'linux', options: ExampleOptions = {}) {
  const { backend, app } = await openExampleApp({
    ...options,
    backend: { serverDelayMs: 0, ...options.backend, appInfo: { os } },
  })
  await app.workspace.getState().init()
  await app.run.getState().connect()
  const onSettings = vi.fn()
  const onQuickOpen = vi.fn()
  // The menus the workbench builds, which the bar draws off macOS.
  function Bar() {
    useAppMenus(app)
    return <Toolbar onSettings={onSettings} onQuickOpen={onQuickOpen} />
  }
  render(
    <AppProvider app={app}>
      <Bar />
    </AppProvider>,
  )
  return { backend, app, onSettings, onQuickOpen, toolbar: screen.getByRole('toolbar') }
}

const pressed = (button: HTMLElement) => button.getAttribute('aria-pressed')

describe('the title bar', () => {
  it('is overlaid on macOS, drawn by us on Windows, and native elsewhere', () => {
    expect(titleBarOf('macos')).toBe('overlay')
    expect(titleBarOf('windows')).toBe('custom')
    expect(titleBarOf('linux')).toBe('native')
    // Before the backend has said.
    expect(titleBarOf(undefined)).toBe('native')
  })

  it("has the menus off macOS, and the window's buttons only where we draw its frame", async () => {
    const linux = await show('linux')
    const menus = await within(linux.toolbar).findByRole('menubar', { name: 'Menu' })
    expect(within(menus).getByRole('menuitem', { name: 'File' })).toBeTruthy()
    expect(within(linux.toolbar).queryByRole('group', { name: 'Window' })).toBeNull()
    cleanup()

    await show('macos')
    // macOS has its own menu bar.
    expect(screen.queryByRole('menubar')).toBeNull()
    expect(screen.queryByRole('group', { name: 'Window' })).toBeNull()
    cleanup()

    const windows = await show('windows')
    const action = vi.spyOn(windows.backend, 'windowAction')
    const controls = screen.getByRole('group', { name: 'Window' })
    fireEvent.click(within(controls).getByRole('button', { name: 'Minimize' }))
    fireEvent.click(within(controls).getByRole('button', { name: 'Maximize' }))
    fireEvent.click(within(controls).getByRole('button', { name: 'Close window' }))
    expect(action.mock.calls).toEqual([['minimize'], ['toggleMaximize'], ['close']])
  })
})

describe('the toolbar', () => {
  it('names the project, and opens quick open and the settings', async () => {
    const { toolbar, onQuickOpen, onSettings } = await show()
    expect(toolbar.textContent).toContain('NetherForge Showcase')
    fireEvent.click(within(toolbar).getByRole('button', { name: 'Search or run a command' }))
    fireEvent.click(within(toolbar).getByRole('button', { name: 'Settings' }))
    expect(onQuickOpen).toHaveBeenCalledOnce()
    expect(onSettings).toHaveBeenCalledOnce()
  })

  it('toggles each dock, showing whether it is open', async () => {
    const { app } = await show()
    const docks = screen.getByRole('group', { name: 'Docks' })
    for (const [name, key] of [
      ['Left dock', 'outlineOpen'],
      ['Bottom dock', 'bottomOpen'],
      ['Right dock', 'inspectorOpen'],
    ] as const) {
      const button = within(docks).getByRole('button', { name })
      const open = app.layout.getState()[key]
      expect(pressed(button)).toBe(String(open))
      fireEvent.click(button)
      expect(app.layout.getState()[key]).toBe(!open)
      expect(pressed(button)).toBe(String(!open))
    }
  })

  it('resets the layout', async () => {
    const { app } = await show()
    app.layout.getState().movePanel('console', { area: 'left' })
    app.layout.getState().set({ bottomOpen: false })
    fireEvent.click(
      within(screen.getByRole('group', { name: 'Docks' })).getByRole('button', {
        name: 'Reset layout',
      }),
    )
    expect(app.layout.getState()).toMatchObject({ docks: DEFAULT_DOCKS, bottomOpen: true })
  })
})

describe('the run controls', () => {
  const status = () => screen.getByRole('status', { name: 'Server status' })

  it('asks for the EULA before the first start, and starts once it is accepted', async () => {
    const { app } = await show()
    expect(status().textContent).toBe('Stopped')
    expect(screen.getByRole('img', { name: 'Bridge disconnected' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Start server' }))

    const eula = await screen.findByRole('dialog', { name: 'Minecraft EULA' })
    const accept = within(eula).getByRole('button', { name: 'Accept and start' })
    expect((accept as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(within(eula).getByRole('checkbox'))
    fireEvent.click(accept)

    await waitFor(() => expect(status().textContent).toMatch(/^Running/))
    expect(app.run.getState().eula?.accepted).toBe(true)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.getByRole('img', { name: 'Bridge connected' })).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Stop server' }))
    await waitFor(() => expect(status().textContent).toBe('Stopped'))
    expect(screen.getByRole('button', { name: 'Start server' })).toBeTruthy()
  })

  it('starts at once when the EULA was accepted before, and cancelling the EULA starts nothing', async () => {
    await show('linux', { backend: { eulaAccepted: true } })
    fireEvent.click(screen.getByRole('button', { name: 'Start server' }))
    await waitFor(() => expect(status().textContent).toMatch(/^Running/))
    expect(screen.queryByRole('dialog')).toBeNull()
    cleanup()

    const again = await show()
    fireEvent.click(screen.getByRole('button', { name: 'Start server' }))
    const eula = await screen.findByRole('dialog', { name: 'Minecraft EULA' })
    fireEvent.click(within(eula).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(again.app.run.getState().server.phase).toBe('stopped')
  })

  it("enables a kind's toolbar command for its resource on a running server", async () => {
    const { app } = await show('linux', { backend: { eulaAccepted: true } })
    const spawn = () => screen.getByRole('button', { name: 'Spawn at me' }) as HTMLButtonElement
    expect(spawn().disabled).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Start server' }))
    await waitFor(() => expect(status().textContent).toMatch(/^Running/))
    await app.workspace.getState().openFile('centities/tower/centity.json')
    await waitFor(() => expect(spawn().disabled).toBe(false))
  })
})
