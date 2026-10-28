import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { modelOf } from '@/core/store/documents'
import type { AdvancementFile } from '@/core/format'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { AdvancementEditor } from './AdvancementEditor'

const HUNTER = 'advancements/treasure_hunter.json'

let app: AppStores

beforeEach(async () => {
  ;({ app } = await openExampleApp())
  await app.workspace.getState().openFile(HUNTER)
  render(
    <AppProvider app={app}>
      <AdvancementEditor path={HUNTER} />
    </AppProvider>,
  )
})

afterEach(cleanup)

const hunter = () => modelOf<AdvancementFile>(app.workspace.getState().docs[HUNTER])!
const inspector = () => within(screen.getByRole('complementary', { name: 'Inspector' }))
const criteria = () => within(screen.getByRole('list', { name: 'Criteria' }))

describe('the advancement editor', () => {
  it('adds a criterion and selects it, and commits its conditions only when they parse', async () => {
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Add criterion' }))
    const added = Object.keys(hunter().criteria ?? {}).find(
      (it) => !['first', 'second', 'third'].includes(it),
    )!
    expect(criteria().getByRole('button', { name: `Criterion: ${added}` })).toHaveProperty(
      'ariaPressed',
      'true',
    )

    await user.type(
      inspector().getByRole('combobox', { name: 'Trigger' }),
      'minecraft:inventory_changed{Enter}',
    )
    expect(hunter().criteria?.[added]).toEqual({ trigger: 'minecraft:inventory_changed' })

    const conditions = inspector().getByRole('textbox', { name: 'Conditions (JSON)' })
    await user.click(conditions)
    await user.paste('[1]')
    await user.tab()
    expect(inspector().getByRole('alert').textContent).toBe('Conditions must be a JSON object.')
    expect(hunter().criteria?.[added]).not.toHaveProperty('conditions')

    await user.clear(conditions)
    await user.paste('{"items": []}')
    await user.tab()
    expect(hunter().criteria?.[added]?.conditions).toEqual({ items: [] })
    expect(inspector().queryByRole('alert')).toBeNull()
  })

  it('removes a criterion', async () => {
    const user = userEvent.setup()
    await user.click(criteria().getByRole('button', { name: 'Remove second' }))
    expect(Object.keys(hunter().criteria ?? {})).toEqual(['first', 'third'])
  })

  it('customizes the requirements as groups, and goes back to needing every criterion', async () => {
    const user = userEvent.setup()
    await user.click(inspector().getByRole('button', { name: 'Customize requirements' }))
    expect(hunter().requirements).toEqual([['first'], ['second'], ['third']])

    const group = inspector().getByRole('textbox', { name: 'Group 1' })
    await user.clear(group)
    await user.type(group, 'first, second{Enter}')
    await user.click(inspector().getByRole('button', { name: 'Remove group 2' }))
    expect(hunter().requirements).toEqual([['first', 'second'], ['third']])

    await user.click(inspector().getByRole('button', { name: 'Need every criterion' }))
    expect(hunter()).not.toHaveProperty('requirements')
  })

  it('turns the display off, and the preview says it is never shown', async () => {
    const user = userEvent.setup()
    await user.click(inspector().getByRole('checkbox', { name: 'Display enabled' }))
    expect(hunter()).not.toHaveProperty('display')
    expect(
      within(screen.getByRole('region', { name: 'Preview' })).getByText(/No display/),
    ).toBeTruthy()
  })
})
