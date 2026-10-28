import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { MemoryBackend } from '@/testing/backend/memory'
import { AppProvider, type AppStores } from '@/state/providers'
import { DialogHost } from '@/ui/dialogs'
import { SettingsScreen } from './settings/SettingsScreen'
import { askToUntrust, TrustBanner } from './TrustBanner'
import { openExampleApp } from '@/testing/workspace'

let backend: MemoryBackend
let app: AppStores

beforeEach(async () => {
  localStorage.clear()
  // Every project given to the memory backend is trusted, as one the user trusted before.
  ;({ backend, app } = await openExampleApp())
})

afterEach(async () => {
  cleanup()
  await app.workspace.getState().closeProject()
})

function show() {
  render(
    <AppProvider app={app}>
      <TrustBanner />
      <DialogHost />
    </AppProvider>,
  )
}

const trusted = () => app.workspace.getState().project?.trusted

describe('askToUntrust', () => {
  it('asks first: cancelling leaves the project trusted, confirming restricts it and shows the banner', async () => {
    show()
    expect(screen.queryByRole('region', { name: 'Restricted mode' })).toBeNull()

    let answer!: Promise<boolean>
    act(() => {
      answer = askToUntrust(app.workspace)
    })
    const dialog = screen.getByRole('dialog', { name: 'Stop trusting "NetherForge Showcase"?' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(await answer).toBe(false)
    expect(trusted()).toBe(true)

    act(() => {
      answer = askToUntrust(app.workspace)
    })
    fireEvent.click(
      within(
        screen.getByRole('dialog', { name: 'Stop trusting "NetherForge Showcase"?' }),
      ).getByRole('button', { name: 'Untrust project' }),
    )
    expect(await answer).toBe(true)
    expect(trusted()).toBe(false)
    expect(screen.getByRole('region', { name: 'Restricted mode' })).toBeTruthy()
    // The backend refuses what waits for trust now.
    await expect(backend.startServer()).rejects.toMatchObject({ code: 'untrusted' })
  })

  it('has nothing to ask about a project that is not trusted', async () => {
    await app.workspace.getState().trustProject(false)
    expect(await askToUntrust(app.workspace)).toBe(true)
  })
})

describe('the Untrust button in the Server settings', () => {
  it('is the same confirmed untrusting, and gives way to Trust', async () => {
    render(
      <AppProvider app={app}>
        <SettingsScreen />
        <DialogHost />
      </AppProvider>,
    )
    // The settings tab shows the Minecraft page until the outline picks another.
    app.workspace.getState().openSettings('server')
    const section = await screen.findByRole('region', { name: 'Project trust' })
    fireEvent.click(within(section).getByRole('button', { name: 'Untrust project…' }))
    fireEvent.click(
      within(
        screen.getByRole('dialog', { name: 'Stop trusting "NetherForge Showcase"?' }),
      ).getByRole('button', { name: 'Untrust project' }),
    )
    await within(section).findByRole('button', { name: 'Trust project…' })
    expect(trusted()).toBe(false)
  })
})
