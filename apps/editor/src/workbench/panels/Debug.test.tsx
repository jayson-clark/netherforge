import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { MemoryBackend } from '@/testing/backend/memory'
import { AppProvider, type AppStores } from '@/state/providers'
import { DebugPanel, describeStop } from './Debug'
import { openExampleApp } from '@/testing/workspace'

const TOWER = 'centities/tower/script.lua'

let backend: MemoryBackend
let app: AppStores

beforeEach(async () => {
  window.localStorage.clear()
  ;({ backend, app } = await openExampleApp())
  await app.run.getState().connect()
  await app.debug.getState().connect()
})

afterEach(cleanup)

const show = () =>
  render(
    <AppProvider app={app}>
      <DebugPanel />
    </AppProvider>,
  )

describe('the Debug panel', () => {
  it('says why it stopped', () => {
    const frame = { id: 1, name: 'f', line: 7, column: 1, source: { path: TOWER } }
    expect(describeStop({ reason: 'breakpoint' }, frame)).toBe(
      `Paused on a breakpoint at ${TOWER}:7`,
    )
    expect(describeStop({ reason: 'exception', description: 'boom' }, frame)).toBe(
      `Paused on a script error at ${TOWER}:7: boom`,
    )
  })

  it('shows the stop, its stack and variables, opens a table, and continues', async () => {
    app.debug.getState().toggleBreakpoint(TOWER, 9)
    backend.testConnect()
    show()
    await screen.findByText('Running')
    expect(
      within(screen.getByRole('list', { name: 'Breakpoints' })).getByText(`${TOWER}:9`),
    ).toBeTruthy()

    act(() => backend.testDebugStop())
    await screen.findByText(`Paused on a breakpoint at ${TOWER}:9`)
    const stack = screen.getByRole('list', { name: 'Call stack' })
    expect(
      within(stack)
        .getAllByRole('button')
        .map((it) => it.textContent),
    ).toEqual([`on_click${TOWER}:9`, `main chunk${TOWER}:1`])

    const variables = screen.getByRole('treegrid', { name: 'Variables' })
    await within(variables).findByText('settings')
    expect(within(variables).getByText('Player Steve')).toBeTruthy()
    // A table opens lazily.
    fireEvent.click(within(variables).getByRole('button', { name: 'Expand settings' }))
    await within(variables).findByText('colors')
    expect(backend.dapLog.at(-1)).toMatchObject({
      command: 'variables',
      arguments: { variablesReference: 111 },
    })

    fireEvent.click(screen.getByRole('button', { name: 'Continue (F5)' }))
    await screen.findByText('Running')
    expect(backend.dapLog.map((it) => it.command)).toContain('continue')
    expect(screen.getAllByText('Not paused.')).toHaveLength(2)
  })

  it('removes a breakpoint from its list', async () => {
    app.debug.getState().toggleBreakpoint(TOWER, 9)
    show()
    fireEvent.click(screen.getByRole('button', { name: `Remove the breakpoint at ${TOWER}:9` }))
    expect(app.debug.getState().breakpoints).toEqual({})
    await screen.findByText(/No breakpoints/)
  })
})
