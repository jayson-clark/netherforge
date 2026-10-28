import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { MemoryBackend } from '@/testing/backend/memory'
import type { TestReport } from '@/core/backend/types'
import { AppProvider, type AppStores } from '@/state/providers'
import { TestsPanel } from './Tests'
import { openExampleApp } from '@/testing/workspace'

const REPORT: TestReport = {
  results: [
    {
      file: 'tests/greeter_test.lua',
      name: 'greets a player',
      status: 'passed',
      durationMillis: 12,
    },
    {
      file: 'tests/greeter_test.lua',
      name: 'counts a visit',
      status: 'failed',
      durationMillis: 3,
      message: 'Alex should have joined once',
      source: { file: 'tests/greeter_test.lua', line: 21 },
      traceback: "stack traceback:\n\ttests/greeter_test.lua:21: in local 'fn'",
      logs: ['tests/greeter_test.lua:18: joined'],
    },
    {
      file: 'tests/ranks_test.lua',
      name: 'ranks start empty',
      status: 'errored',
      durationMillis: 5,
      message: 'a script failed while the test ran: boom',
      source: { file: 'modules/ranks/init.lua', line: 9 },
    },
  ],
  passed: 1,
  failed: 1,
  errored: 1,
  durationMillis: 80,
  error: null,
  problems: [],
}

let backend: MemoryBackend
let app: AppStores

beforeEach(async () => {
  ;({ backend, app } = await openExampleApp())
  await app.workspace.getState().trustProject(true)
})

afterEach(cleanup)

const show = () =>
  render(
    <AppProvider app={app}>
      <TestsPanel />
    </AppProvider>,
  )

describe('the tests store', () => {
  it('runs the tests and keeps the report, then filters by file and name', async () => {
    backend.testTestReport(REPORT)
    await app.tests.getState().run()
    expect(app.tests.getState().report).toEqual(REPORT)

    await app.tests.getState().run('visit')
    const report = app.tests.getState().report!
    expect(report.results.map((it) => it.name)).toEqual(['counts a visit'])
    expect([report.passed, report.failed, report.errored]).toEqual([0, 1, 0])
    expect(app.tests.getState().filter).toBe('visit')
  })

  it("keeps why it couldn't run: no runner, or an untrusted project", async () => {
    await app.tests.getState().run()
    expect(app.tests.getState().report).toBeNull()
    expect(app.tests.getState().error).toContain('no test runner')

    await app.workspace.getState().trustProject(false)
    backend.testTestReport(REPORT)
    await app.tests.getState().run()
    expect(app.tests.getState().error).toMatch(/trust/i)
  })
})

describe('the Tests panel', () => {
  it('says there is no run yet, then shows each test under its file', async () => {
    backend.testTestReport(REPORT)
    show()
    expect(screen.getByText(/No run yet/)).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: /Run Tests/ }))
    await screen.findByRole('status')
    expect(screen.getByRole('status').textContent).toContain('1 passed, 1 failed, 1 errored')

    const greeter = screen.getByRole('region', { name: 'tests/greeter_test.lua' })
    expect(within(greeter).getByRole('button', { name: 'passed: greets a player' })).toBeTruthy()
    expect(within(greeter).getByRole('button', { name: 'failed: counts a visit' })).toBeTruthy()
    // A failure shows where, what, what was logged and the traceback.
    expect(within(greeter).getByText(/Alex should have joined once/)).toBeTruthy()
    expect(within(greeter).getByText('tests/greeter_test.lua:21:')).toBeTruthy()
    expect(within(greeter).getByText(/joined/, { selector: 'pre' })).toBeTruthy()
    expect(screen.getByRole('region', { name: 'tests/ranks_test.lua' })).toBeTruthy()
  })

  it("opens the script where a test failed, the failing script's own line for an error", async () => {
    backend.testTestReport(REPORT)
    show()
    fireEvent.click(screen.getByRole('button', { name: /Run Tests/ }))
    await screen.findByRole('status')
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'errored: ranks start empty' }))
    })
    await waitFor(() =>
      expect(app.workspace.getState().activeTab).toBe('script:modules/ranks/init.lua'),
    )
  })

  it("explains a run that couldn't start", async () => {
    backend.testTestReport({
      ...REPORT,
      results: [],
      passed: 0,
      failed: 0,
      errored: 0,
      error: "the project can't run: netherforge.json: error: bad",
      problems: ['netherforge.json: error: bad'],
    })
    show()
    fireEvent.click(screen.getByRole('button', { name: /Run Tests/ }))
    await screen.findByText(/The tests couldn.t run/)
    expect(screen.getByText('netherforge.json: error: bad')).toBeTruthy()
  })
})
