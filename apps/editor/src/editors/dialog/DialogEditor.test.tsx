import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { modelOf } from '@/core/store/documents'
import type { DialogFile } from '@/core/format'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { DialogEditor } from './DialogEditor'

const WELCOME = 'dialogs/welcome/dialog.json'

let app: AppStores

beforeEach(async () => {
  ;({ app } = await openExampleApp())
  await app.workspace.getState().openFile(WELCOME)
  render(
    <AppProvider app={app}>
      <DialogEditor path={WELCOME} />
    </AppProvider>,
  )
})

afterEach(cleanup)

const welcome = () => modelOf<DialogFile>(app.workspace.getState().docs[WELCOME])!

describe('the dialog editor', () => {
  it('puts the dialog on the pause screen or the quick actions key, leaving off as no key', async () => {
    const user = userEvent.setup()
    const pause = screen.getByRole('checkbox', { name: 'On the pause screen' })
    const quick = screen.getByRole('checkbox', { name: 'On the quick actions key' })
    expect(pause).toHaveProperty('checked', true)
    expect(quick).toHaveProperty('checked', false)
    await user.click(pause)
    await user.click(quick)
    // Off is no key at all, so an unchanged file is never a diff.
    expect(welcome()).not.toHaveProperty('pauseMenu')
    expect(welcome().quickActions).toBe(true)
  })

  it("renames a body element's key, and adds a button once the type has room for one", async () => {
    const user = userEvent.setup()
    await user.click(
      within(screen.getByRole('list', { name: 'Body' })).getByRole('button', {
        name: 'Message: Tell us what to call you',
      }),
    )
    // The key scripts and problems name it by.
    const key = screen.getByRole('textbox', { name: 'Key' })
    expect(key).toHaveProperty('value', 'prompt')
    await user.clear(key)
    await user.type(key, 'intro{Enter}')
    expect(welcome().body?.[0]?.key).toBe('intro')

    // A notice has its one button: a confirmation has two.
    const add = screen.getByRole('button', { name: 'Add button' })
    expect(add).toHaveProperty('disabled', true)
    await user.selectOptions(screen.getByRole('combobox', { name: 'Type' }), 'confirmation')
    await user.click(add)
    const label = screen.getByRole('textbox', { name: 'Label' })
    await user.clear(label)
    await user.type(label, 'Not now{Enter}')
    expect(welcome().type).toBe('confirmation')
    expect(welcome().buttons?.[1]).toEqual({ key: 'button', label: 'Not now' })
    // The preview draws it as the game would.
    expect(
      within(screen.getByRole('region', { name: 'Dialog preview' })).getByRole('button', {
        name: 'Button button',
      }).textContent,
    ).toContain('Not now')
  })
})
