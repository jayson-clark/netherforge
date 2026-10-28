import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { MemoryBackend } from '@/testing/backend/memory'
import { modelOf } from '@/core/store/documents'
import type { ResourcePackFile } from '@/core/format'
import { AppProvider, type AppStores } from '@/state/providers'
import { DialogHost } from '@/ui/dialogs'
import { openExampleApp } from '@/testing/workspace'
import { ResourcePackEditor } from './ResourcePackEditor'

const PACK = 'resource_packs/ui/pack.json'
const SHOP_PNG = 'resource_packs/ui/textures/gui/shop.png'

let backend: MemoryBackend
let app: AppStores

beforeEach(async () => {
  ;({ backend, app } = await openExampleApp())
  await app.workspace.getState().openFile(PACK)
  render(
    <AppProvider app={app}>
      <ResourcePackEditor path={PACK} />
      <DialogHost />
    </AppProvider>,
  )
})

afterEach(cleanup)

const pack = () => modelOf<ResourcePackFile>(app.workspace.getState().docs[PACK])!

/** A file as the picker hands it over, with the bytes of a file in the project. */
function fileOf(name: string, path: string): File {
  const bytes = Uint8Array.from(atob(backend.testBytes(path)!), (it) => it.charCodeAt(0))
  return new File([bytes], name, { type: 'image/png' })
}

describe('the resource pack editor', () => {
  it('imports a PNG where it asks, picks it, and makes a skin from it', async () => {
    const user = userEvent.setup()
    await user.upload(screen.getByLabelText('PNG files to import'), fileOf('Banner.png', SHOP_PNG))
    const importing = await screen.findByRole('dialog', { name: 'Import Banner.png' })
    // Into the folder skins live in, its name as the game wants it.
    expect(within(importing).getByRole('textbox')).toHaveProperty('value', 'gui/banner.png')
    await user.click(within(importing).getByRole('button', { name: 'Import' }))

    const card = await screen.findByRole('row', { name: /Texture gui\/banner\.png/ })
    expect(card.getAttribute('aria-selected')).toBe('true')
    expect(backend.testBytes('resource_packs/ui/textures/gui/banner.png')).toBe(
      backend.testBytes(SHOP_PNG),
    )

    await user.click(screen.getByRole('button', { name: 'skin' }))
    const create = await screen.findByRole('dialog', { name: 'New skin' })
    expect(within(create).getByRole('textbox')).toHaveProperty('value', 'banner')
    await user.click(within(create).getByRole('button', { name: 'Create' }))
    expect(pack().skins?.banner).toEqual({ texture: 'gui/banner.png' })
    expect(
      (await screen.findByRole('row', { name: 'skin banner' })).getAttribute('aria-selected'),
    ).toBe('true')
  })

  it("refuses a file that isn't a PNG, saying so", async () => {
    const user = userEvent.setup()
    const text = new File(['not a picture'], 'notes.png', { type: 'image/png' })
    await user.upload(screen.getByLabelText('PNG files to import'), text)
    await expect.poll(() => app.workspace.getState().notice?.text).toBe("notes.png isn't a PNG")
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('asks for a new entry a key that is free and well formed', async () => {
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'New glyph' }))
    const create = await screen.findByRole('dialog', { name: 'New glyph' })
    const key = within(create).getByRole('textbox')
    await user.clear(key)
    await user.type(key, 'coin')
    expect(key.getAttribute('aria-invalid')).toBe('true')
    expect(within(create).getByText('That key is taken')).toBeTruthy()
    await user.clear(key)
    await user.type(key, 'Big Coin')
    expect(within(create).getByText('Lowercase letters, digits and _')).toBeTruthy()
    expect(within(create).getByRole('button', { name: 'Create' })).toHaveProperty('disabled', true)
    await user.clear(key)
    await user.type(key, 'gem{Enter}')
    expect(Object.keys(pack().glyphs ?? {})).toEqual(['coin', 'spark', 'wisp', 'gem'])
  })
})
