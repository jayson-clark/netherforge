import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { MemoryBackend } from '@/testing/backend/memory'
import { AppProvider, type AppStores } from '@/state/providers'
import { OwnerSettings } from './OwnerSettings'
import { openExampleApp } from '@/testing/workspace'

let backend: MemoryBackend
let app: AppStores

beforeEach(async () => {
  ;({ backend, app } = await openExampleApp())
  await app.run.getState().connect()
})

afterEach(() => {
  app.run.getState().disconnect()
  cleanup()
})

function show() {
  render(
    <AppProvider app={app}>
      <OwnerSettings />
    </AppProvider>,
  )
}

const setRequests = () =>
  backend.bridgeLog.filter((it) => it.method === 'set_setting').map((it) => it.params)

describe('Settings › Server-owner settings', () => {
  it('asks for the dev server first', () => {
    show()
    expect(screen.getByRole('status', { name: 'Server-owner settings status' }).textContent).toBe(
      'Start the dev server to see and change the values it runs with.',
    )
  })

  it("shows the dev server's values and sets one, a typed one read as the server reads it", async () => {
    show()
    act(() => backend.testConnect())
    const form = await screen.findByRole('region', { name: 'NetherForge Showcase settings' })
    const rolls = within(form).getByRole('textbox', { name: 'treasure_rolls' })
    expect((rolls as HTMLInputElement).value).toBe('1')

    // Past the setting's max: refused at the field, nothing sent.
    fireEvent.change(rolls, { target: { value: '9' } })
    fireEvent.keyDown(rolls, { key: 'Enter' })
    expect(within(form).getByRole('alert').textContent).toBe(
      "treasure_rolls: 9 isn't a whole number from 1 to 5",
    )
    expect(setRequests()).toEqual([])

    fireEvent.change(rolls, { target: { value: '3' } })
    fireEvent.keyDown(rolls, { key: 'Enter' })
    await waitFor(() =>
      expect(setRequests()).toEqual([
        { namespace: 'showcase', setting: 'treasure_rolls', value: 3 },
      ]),
    )
    fireEvent.click(within(form).getByRole('checkbox', { name: 'show_welcome' }))
    await waitFor(() => expect(setRequests()).toHaveLength(2))
    expect(setRequests()[1]).toEqual({
      namespace: 'showcase',
      setting: 'show_welcome',
      value: false,
    })

    // Set ones offer their default back.
    fireEvent.click(await within(form).findByRole('button', { name: 'Reset to 1' }))
    await waitFor(() =>
      expect(setRequests()[2]).toEqual({ namespace: 'showcase', setting: 'treasure_rolls' }),
    )
    await waitFor(() => expect((rolls as HTMLInputElement).value).toBe('1'))
  })
})
