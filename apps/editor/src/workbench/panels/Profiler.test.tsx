import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { MemoryBackend } from '@/testing/backend/memory'
import { AppProvider, type AppStores } from '@/state/providers'
import { ProfilerPanel } from './Profiler'
import { openExampleApp } from '@/testing/workspace'

let backend: MemoryBackend
let app: AppStores

beforeEach(async () => {
  ;({ backend, app } = await openExampleApp())
  await app.run.getState().connect()
  await app.profiler.getState().connect()
})

afterEach(cleanup)

// As main.tsx renders it: StrictMode renders twice, which catches props read only once.
const show = () =>
  render(
    <StrictMode>
      <AppProvider app={app}>
        <ProfilerPanel />
      </AppProvider>
    </StrictMode>,
  )

const ticks = Array.from({ length: 20 }, (_, i) => ({
  tick: i + 1,
  nanos: 2_000_000,
  phases: { timers: 500_000, world: 1_500_000 },
  scripts: 1_000_000,
}))

function stream() {
  act(() =>
    backend.testBridgeEvent('profiler', {
      items: [
        {
          ticks,
          handlers: [
            {
              script: 'centity tower',
              kind: 'tick',
              source: { file: 'centities/tower/script.lua', line: 9 },
              calls: 20,
              nanos: 4_000_000,
              max: 400_000,
              scopes: 2,
            },
            { script: 'module shop', kind: 'command', calls: 1, nanos: 3_000_000, max: 3_000_000 },
          ],
          scopes: [{ scope: 'module shop', nanos: 3_000_000, calls: 1, max: 3_000_000 }],
        },
      ],
    }),
  )
}

const rows = (name: string) =>
  within(screen.getByRole('table', { name }))
    .getAllByRole('row')
    .slice(1)
    .map((row) => row.textContent)

describe('the Profiler panel', () => {
  it('waits for the dev server', () => {
    show()
    expect(screen.getByText(/Start the dev server/)).toBeTruthy()
  })

  it('lists functions by total, sorts by another column, opens a function at its line, and lists scopes', async () => {
    show()
    act(() => backend.testConnect())
    stream()
    expect(screen.getByText(/20 ticks, 2\.00 ms a tick on average/)).toBeTruthy()
    expect(screen.getByRole('img', { name: /The last 20 ticks/ })).toBeTruthy()
    expect(rows('Functions')).toEqual([
      'centity tower ×2tickcentities/tower/script.lua:94.00200.200.40',
      'module shopcommandNetherForge3.0013.003.00',
    ])

    fireEvent.click(screen.getByRole('button', { name: /Max ms/ }))
    expect(rows('Functions')[0]).toMatch(/^module shop/)
    expect(screen.getByRole('columnheader', { name: /Max ms/ }).getAttribute('aria-sort')).toBe(
      'descending',
    )

    fireEvent.click(screen.getByRole('button', { name: 'Open centities/tower/script.lua:9' }))
    await waitFor(() =>
      expect(app.workspace.getState().activeTab).toBe('script:centities/tower/script.lua'),
    )

    fireEvent.click(screen.getByRole('button', { name: 'Scopes' }))
    expect(rows('Scopes')).toEqual(['module shop0.153.0013.003.00'])

    fireEvent.click(screen.getByRole('button', { name: 'Reset' }))
    expect(screen.getByText('0 ticks')).toBeTruthy()
  })
})
