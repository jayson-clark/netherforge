import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { modelOf } from '@/core/store/documents'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { DialogHost } from '@/ui/dialogs'
import type { PackMeta } from '@/core/format'
import { DatapackEditor } from './DatapackEditor'

const PACK = 'datapacks/ruby_boulders/pack.mcmeta'

let app: AppStores

beforeEach(async () => {
  ;({ app } = await openExampleApp())
  await app.workspace.getState().openFile(PACK)
  render(
    <AppProvider app={app}>
      <DatapackEditor path={PACK} />
      <DialogHost />
    </AppProvider>,
  )
})

afterEach(cleanup)

const pack = () => modelOf<PackMeta>(app.workspace.getState().docs[PACK])!
const inspector = () => within(screen.getByRole('complementary', { name: 'Inspector' }))

describe('the datapack editor', () => {
  it("lists the pack's files under data/ and each overlay", () => {
    const data = within(screen.getByRole('list', { name: 'Files in data' }))
    expect(data.getByRole('button', { name: /placed_feature\/ruby_boulders\.json$/ }))
    const overlay = within(screen.getByRole('list', { name: 'Files in mc26_3' }))
    expect(overlay.getByRole('button', { name: /feature\/ruby_boulder\.json$/ }))
  })

  it('reads a format as a number or a pair, and puts back one that is neither', async () => {
    const user = userEvent.setup()
    const newest = inspector().getAllByRole('textbox', { name: 'Newest format' })[0]!
    await user.clear(newest)
    await user.type(newest, '122.1{Enter}')
    expect(pack().pack.max_format).toEqual([122, 1])

    await user.clear(newest)
    await user.type(newest, 'soon{Enter}')
    expect(pack().pack.max_format).toEqual([122, 1])
  })

  it('adds an overlay after asking its folder, refusing one that is taken, and removes it', async () => {
    const user = userEvent.setup()
    await user.click(inspector().getByRole('button', { name: 'Add overlay' }))
    const asking = await screen.findByRole('dialog', { name: 'New overlay' })
    const folder = within(asking).getByRole('textbox')
    await user.type(folder, 'mc26_1')
    expect(within(asking).getByRole('button', { name: 'Add' })).toHaveProperty('disabled', true)
    await user.clear(folder)
    await user.type(folder, 'mc27')
    await user.click(within(asking).getByRole('button', { name: 'Add' }))
    await waitFor(() =>
      expect(pack().overlays?.entries?.map((it) => it.directory)).toEqual([
        'mc1_21',
        'mc26_1',
        'mc26_3',
        'mc27',
      ]),
    )

    await user.click(inspector().getByRole('button', { name: 'Remove overlay mc27' }))
    expect(pack().overlays?.entries).toHaveLength(3)
  })
})
