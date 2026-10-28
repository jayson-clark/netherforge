import { act, cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { ask, DialogHost } from './dialogs'

afterEach(cleanup)

function setup() {
  const user = userEvent.setup()
  render(
    <>
      <button type="button">Opener</button>
      <DialogHost />
    </>,
  )
  const opener = screen.getByRole('button', { name: 'Opener' })
  opener.focus()
  return { user, opener }
}

describe('ask.confirm', () => {
  it('focuses its confirm button, keeps Tab inside, and Escape answers false and gives focus back', async () => {
    const { user, opener } = setup()
    let answer: Promise<boolean | 'alternative'> | undefined
    act(() => {
      answer = ask.confirm({
        title: 'Delete tower',
        message: 'It goes for good.',
        confirmLabel: 'Delete',
        alternative: 'Archive',
      })
    })
    const dialog = screen.getByRole('dialog', { name: 'Delete tower' })
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Delete' }))
    // Tab cycles through the dialog's buttons and never leaves it.
    for (let i = 0; i < 4; i++) {
      await user.tab()
      expect(dialog.contains(document.activeElement)).toBe(true)
    }
    await user.keyboard('{Escape}')
    expect(await answer).toBe(false)
    expect(screen.queryByRole('dialog')).toBeNull()
    await act(() => new Promise(requestAnimationFrame))
    expect(document.activeElement).toBe(opener)
  })

  it('answers the button pressed', async () => {
    const { user } = setup()
    let answer: Promise<boolean | 'alternative'> | undefined
    act(() => {
      answer = ask.confirm({ title: 'Unsaved', message: 'Save?', alternative: "Don't save" })
    })
    await user.click(screen.getByRole('button', { name: "Don't save" }))
    expect(await answer).toBe('alternative')
  })
})

describe('ask.prompt', () => {
  it('commits a valid value with Enter and says why an invalid one is refused', async () => {
    const { user } = setup()
    let answer: Promise<string | null> | undefined
    act(() => {
      answer = ask.prompt({
        title: 'New centity',
        label: 'Id',
        initial: 'tower',
        validate: (value) => (/^[a-z_]+$/.test(value) ? null : 'Lowercase letters and _'),
      })
    })
    const input = screen.getByRole('textbox', { name: 'Id' })
    expect(document.activeElement).toBe(input)
    await user.clear(input)
    await user.type(input, 'Bad{Enter}')
    expect(screen.getByText('Lowercase letters and _').id).toBe(
      input.getAttribute('aria-describedby'),
    )
    expect(screen.getByRole('button', { name: 'OK' })).toHaveProperty('disabled', true)
    await user.clear(input)
    await user.type(input, 'barrel{Enter}')
    expect(await answer).toBe('barrel')
  })

  it('a click outside cancels', async () => {
    const { user } = setup()
    let answer: Promise<string | null> | undefined
    act(() => {
      answer = ask.prompt({ title: 'Rename', label: 'Name' })
    })
    await user.click(document.body)
    expect(await answer).toBeNull()
  })
})
