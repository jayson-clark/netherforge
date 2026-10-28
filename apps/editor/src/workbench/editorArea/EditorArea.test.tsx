import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { AppProvider, type AppStores } from '@/state/providers'
import { MANIFEST_FILE } from '@/core/format'
import { EditorArea } from './EditorArea'
import { openExampleApp } from '@/testing/workspace'

let app: AppStores

beforeEach(async () => {
  ;({ app } = await openExampleApp())
  await app.workspace.getState().openFile(MANIFEST_FILE)
})

afterEach(cleanup)

/** Whether a control can't be used: its own `disabled`, or a disabled fieldset around it. */
const disabled = (element: HTMLElement) => element.matches(':disabled')

function show() {
  render(
    <AppProvider app={app}>
      <EditorArea />
    </AppProvider>,
  )
}

describe('EditorArea', () => {
  it("shows the active tab's registered view", async () => {
    show()
    expect(disabled(await screen.findByRole('textbox', { name: 'Name' }))).toBe(false)
    expect(screen.queryByLabelText('Read-only')).toBeNull()
  })

  it('disables a read-only document and says why', async () => {
    act(() => app.workspace.getState().setReadOnly(MANIFEST_FILE, 'from the package acme'))
    show()
    expect(disabled(await screen.findByRole('textbox', { name: 'Name' }))).toBe(true)
    expect(screen.getByLabelText('Read-only').textContent).toBe('Read-only: from the package acme.')
    act(() => app.workspace.getState().setReadOnly(MANIFEST_FILE, null))
    expect(disabled(screen.getByRole('textbox', { name: 'Name' }))).toBe(false)
  })
})
