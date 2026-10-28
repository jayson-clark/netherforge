import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { modelOf } from '@/core/store/documents'
import type { LootTableFile } from '@/core/format'
import { viewOf } from '@/editors/views'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { LootEditor } from './LootEditor'

const TREASURE = 'loot/treasure.json'

let app: AppStores

beforeEach(async () => {
  ;({ app } = await openExampleApp())
  await app.workspace.getState().openFile(TREASURE)
  // The outline picks the pool, through the document's view.
  viewOf(app.workspace, 'loot_table', TREASURE).select({ pool: 'gems' })
  render(
    <AppProvider app={app}>
      <LootEditor path={TREASURE} />
    </AppProvider>,
  )
})

afterEach(cleanup)

const gems = () =>
  modelOf<LootTableFile>(app.workspace.getState().docs[TREASURE])!.pools!.gems!.entries!
const entries = () => within(screen.getByRole('list', { name: 'Entries' }))
const inspector = () => within(screen.getByRole('complementary', { name: 'Inspector' }))
/** Each rolled stack's name and count (`ruby ×2`), as a screen reader has them. */
const rolled = () =>
  within(screen.getByRole('list', { name: 'Rolled' }))
    .getAllByRole('listitem')
    .map((it) => it.getAttribute('aria-label'))

describe('the loot table editor', () => {
  it("shows each entry's share of a pick, which a new weight moves", async () => {
    const user = userEvent.setup()
    // Weights 3, 1 and 2.
    const ruby = entries().getByRole('button', { name: 'Entry: ruby' })
    expect(ruby.textContent).toContain('50% of a pick')
    expect(entries().getByRole('button', { name: 'Entry: Nothing' }).textContent).toContain('33.3%')

    await user.click(ruby)
    expect(ruby.getAttribute('aria-pressed')).toBe('true')
    const weight = inspector().getByRole('textbox', { name: 'Weight' })
    expect(weight).toHaveProperty('value', '3')
    await user.clear(weight)
    await user.type(weight, '1{Enter}')
    expect(gems()[0]!.weight).toBe(1)
    expect(ruby.textContent).toContain('25% of a pick')
  })

  it('adds an entry, selected, and a condition on it', async () => {
    const user = userEvent.setup()
    await user.click(
      within(screen.getByRole('group', { name: 'Add an entry' })).getByRole('button', {
        name: 'Nothing',
      }),
    )
    expect(entries().getAllByRole('listitem')).toHaveLength(4)
    expect(viewOf(app.workspace, 'loot_table', TREASURE).selection()).toEqual([
      { pool: 'gems', entry: 3 },
    ])

    await user.selectOptions(
      inspector().getAllByRole('combobox', { name: 'New condition' }).at(-1)!,
      'player',
    )
    await user.click(inspector().getAllByRole('button', { name: 'Add condition' }).at(-1)!)
    expect(gems()[3]).toEqual({ type: 'empty', conditions: [{ type: 'player' }] })
    expect(entries().getAllByRole('listitem').at(-1)!.textContent).toContain(
      'only if a player did it',
    )
  })

  it("rolls the table by format's roller: the same seed, the same stacks", async () => {
    const user = userEvent.setup()
    const preview = within(screen.getByRole('region', { name: 'Roll preview' }))
    await user.click(preview.getByRole('button', { name: 'Roll' }))
    const first = rolled()
    expect(first.length).toBeGreaterThan(0)
    for (const stack of first) expect(stack).toMatch(/ ×\d+$/)
    await user.click(preview.getByRole('button', { name: 'Roll' }))
    expect(rolled()).toEqual(first)

    // Next seed rolls again with the seed after, and keeps it in the document's view.
    const seed = viewOf(app.workspace, 'loot_table', TREASURE).state().seed
    await user.click(preview.getByRole('button', { name: 'Next seed' }))
    expect(viewOf(app.workspace, 'loot_table', TREASURE).state().seed).toBe(seed + 1)
    expect(preview.getByRole('textbox', { name: 'Seed' })).toHaveProperty('value', String(seed + 1))
  })
})
