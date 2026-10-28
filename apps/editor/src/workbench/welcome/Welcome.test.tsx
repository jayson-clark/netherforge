import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { MemoryBackend } from '@/testing/backend/memory'
import { MANIFEST_FILE } from '@/core/format'
import { AppProvider, createApp } from '@/state/providers'
import { Welcome } from './Welcome'
import { useWelcome } from './welcomeState'

afterEach(() => {
  cleanup()
  useWelcome.setState({ creating: false, error: null })
})

async function createWith(template: string) {
  const backend = new MemoryBackend({ pickFolderResults: ['/memory/new'] })
  const app = createApp(backend)
  await app.workspace.getState().init()
  useWelcome.setState({ creating: true })
  render(
    <AppProvider app={app}>
      <Welcome />
    </AppProvider>,
  )
  await screen.findByRole('form', { name: 'Create project' })
  if (template) {
    fireEvent.click(screen.getByRole('radio', { name: template }))
  }
  fireEvent.click(screen.getByRole('button', { name: 'Choose…' }))
  await screen.findByText('/memory/new')
  fireEvent.click(screen.getByRole('button', { name: 'Create' }))
  return { backend, app }
}

describe('creating a project', () => {
  it("can start from a template, whose files become the project's own", async () => {
    const { backend, app } = await createWith('RPG mob')
    await waitFor(() => expect(app.workspace.getState().project?.root).toBe('/memory/new'))
    await waitFor(() =>
      expect(JSON.parse(backend.testFiles()[MANIFEST_FILE]!).requires).toEqual({ db: true }),
    )
    expect(JSON.parse(backend.testFiles()[MANIFEST_FILE]!).dependencies).toBeUndefined()
    expect(backend.testFiles()['centities/goblin/centity.json']).toBeDefined()
  })

  it('starts empty unless asked', async () => {
    const { backend, app } = await createWith('')
    await waitFor(() => expect(app.workspace.getState().project?.root).toBe('/memory/new'))
    expect(JSON.parse(backend.testFiles()[MANIFEST_FILE]!).dependencies).toBeUndefined()
    expect(backend.testFiles()['centities/goblin/centity.json']).toBeUndefined()
  })
})
