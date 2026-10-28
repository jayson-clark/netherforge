import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { modelOf } from '@/core/store/documents'
import type { ScreenFontFile } from '@/core/format'
import { AppProvider, type AppStores } from '@/state/providers'
import { DialogHost } from '@/ui/dialogs'
import { openExampleApp } from '@/testing/workspace'
import { ScreenFontEditor } from './ScreenFontEditor'

const PIXEL = 'screen_fonts/pixel/font.json'

let app: AppStores

beforeEach(async () => {
  ;({ app } = await openExampleApp())
  await app.workspace.getState().openFile(PIXEL)
  render(
    <AppProvider app={app}>
      <ScreenFontEditor path={PIXEL} />
      <DialogHost />
    </AppProvider>,
  )
})

afterEach(cleanup)

const font = () => modelOf<ScreenFontFile>(app.workspace.getState().docs[PIXEL])!
const inspector = () => within(screen.getByRole('complementary', { name: 'Inspector' }))

describe('the screen font editor', () => {
  it("draws a glyph's pixels, a click turning one on or off", async () => {
    const user = userEvent.setup()
    const corner = screen.getByRole('gridcell', { name: '0 1,1' })
    expect(corner.getAttribute('aria-pressed')).toBe('false')
    await user.click(corner)
    expect(font().glyphs?.['0']?.[1]).toBe('###')
  })

  it('adds a character, picks it and removes it', async () => {
    const user = userEvent.setup()
    await user.click(inspector().getByRole('button', { name: 'Add a character' }))
    await user.type(await screen.findByRole('textbox', { name: 'Character' }), 'A{Enter}')
    expect(font().glyphs?.A).toEqual(['...', '...', '...', '...', '...'])
    expect(inspector().getByRole('option', { name: 'A' }).getAttribute('aria-selected')).toBe(
      'true',
    )
    await user.click(inspector().getByRole('button', { name: 'Remove A' }))
    expect(font().glyphs).not.toHaveProperty('A')
  })
})
