import { describe, expect, it, vi } from 'vitest'
import { openExampleApp } from '@/testing/workspace'

const FILE = 'advancements/treasure_hunter.json'

async function open() {
  const { backend, app } = await openExampleApp({ backend: { serverDelayMs: 0 } })
  await app.run.getState().connect()
  backend.testConnect()
  return { backend, app }
}

const lines = (app: Awaited<ReturnType<typeof open>>['app']) =>
  app.run
    .getState()
    .console.lines()
    .map((it) => it.text)

describe('saving what the server learns only as it starts', () => {
  it('restarts the dev server when the reload says it must', async () => {
    const { backend, app } = await open()
    backend.testReloadRestarts()
    await app.workspace.getState().openFile(FILE)
    app.workspace
      .getState()
      .setText(FILE, (await backend.readText(FILE)).replace('Hunter', 'Seeker'))
    await app.workspace.getState().save(FILE)
    await vi.waitFor(() => expect(lines(app)).toContain('Restarting the dev server'))
    expect(lines(app).some((it) => it.includes('must restart'))).toBe(true)
    await vi.waitFor(() => expect(app.run.getState().server.phase).toBe('running'))
    expect(lines(app)).toContain('[Server] Stopping server')
  })

  it('leaves the server running when the reload needs no restart', async () => {
    const { app } = await open()
    await app.workspace.getState().openFile(FILE)
    await app.workspace.getState().save(FILE)
    await vi.waitFor(() => expect(lines(app).some((it) => it.startsWith('Reloaded'))).toBe(true))
    expect(lines(app)).not.toContain('Restarting the dev server')
    expect(app.run.getState().server.phase).toBe('running')
  })
})
