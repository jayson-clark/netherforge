import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { AppProvider } from '@/state/providers'
import { EXAMPLE_ROOT } from '@/testing/backend/seed'
import { openExampleApp } from '@/testing/workspace'
import { ProblemsPanel } from './Problems'
import { TestsPanel } from './Tests'

const BROKEN = 'items/broken/item.json'

/** The showcase and an arena server with a broken item, as one workspace. */
async function open() {
  const example = await openExampleApp({
    backend: {
      workspaces: { '/memory': JSON.stringify({ name: 'Net', projects: ['showcase', 'arena'] }) },
    },
  })
  const { backend, app } = example
  const manifest = JSON.parse(await backend.readText('netherforge.json'))
  await backend.createProject('/memory/arena', {
    'netherforge.json': JSON.stringify({
      formatVersion: 1,
      name: 'Arena',
      namespace: 'arena',
      version: '0.1.0',
      minecraft: manifest.minecraft,
    }),
    [BROKEN]: '{ "name": ',
  })
  await app.workspace.getState().openProject(EXAMPLE_ROOT)
  await app.workspaceFile.getState().refresh()
  await waitFor(() => expect(app.workspaceFile.getState().checking).toBe(false))
  return example
}

afterEach(cleanup)

describe('Problems in a workspace', () => {
  it("lists the other projects' problems, and opening one switches to its project", async () => {
    const { app } = await open()
    render(
      <AppProvider app={app}>
        <ProblemsPanel />
      </AppProvider>,
    )
    const others = screen.getByRole('region', { name: 'Other projects in the workspace' })
    const arena = within(others).getByRole('list', { name: 'Problems in Arena' })
    fireEvent.click(within(arena).getAllByRole('button')[0]!)
    await waitFor(() => expect(app.workspace.getState().project?.root).toBe('/memory/arena'))
    await waitFor(() =>
      expect(app.workspace.getState().tabs.some((it) => it.path === BROKEN)).toBe(true),
    )
  })
})

describe('Tests in a workspace', () => {
  it("runs every project's tests and shows the others' by project", async () => {
    const { app, backend } = await open()
    await app.workspace.getState().trustWorkspace(true)
    backend.testTestReport(
      {
        results: [
          {
            file: 'arena_test.lua',
            name: 'fights',
            status: 'failed',
            durationMillis: 2,
            message: 'lost',
          },
        ],
        passed: 0,
        failed: 1,
        errored: 0,
        durationMillis: 2,
        error: null,
        problems: [],
      },
      'arena',
    )
    render(
      <AppProvider app={app}>
        <TestsPanel />
      </AppProvider>,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Run Workspace Tests' }))
    const arena = await screen.findByRole('region', { name: 'Tests of Arena' })
    expect(within(arena).getByText('lost')).toBeTruthy()
    expect(within(arena).getByText(/0 passed, 1 failed/)).toBeTruthy()
  })
})
