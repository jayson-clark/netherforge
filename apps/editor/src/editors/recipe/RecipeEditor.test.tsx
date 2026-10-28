import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { modelOf } from '@/core/store/documents'
import type { RecipeFile } from '@/core/format'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { RecipeEditor } from './RecipeEditor'

const SWORD = 'recipes/ruby_sword.json'
const DUST = 'recipes/ruby_dust.json'

let app: AppStores

async function open(path: string) {
  ;({ app } = await openExampleApp())
  await app.workspace.getState().openFile(path)
  render(
    <AppProvider app={app}>
      <RecipeEditor path={path} />
    </AppProvider>,
  )
}

afterEach(cleanup)

const recipe = (path: string) => modelOf<RecipeFile>(app.workspace.getState().docs[path])!
const inspector = () => within(screen.getByRole('complementary', { name: 'Inspector' }))
const square = (name: string) => screen.getByRole('button', { name })

/** A drag from one square to another, as the browser sends it. */
function drag(from: HTMLElement, to: HTMLElement) {
  const dataTransfer = { effectAllowed: '', setData: () => {}, getData: () => '' }
  fireEvent.dragStart(from, { dataTransfer })
  fireEvent.dragOver(to, { dataTransfer })
  fireEvent.drop(to, { dataTransfer })
  fireEvent.dragEnd(from, { dataTransfer })
}

describe('a shaped recipe', () => {
  it('shows its pattern where it is on the grid, and clears a square on right-click', async () => {
    await open(SWORD)
    expect(square('Square 2: ruby'))
    expect(square('Square 8: stick'))

    fireEvent.contextMenu(square('Square 8: stick'))
    // The pattern and key follow the grid: the stick's row and letter go.
    expect(recipe(SWORD).pattern).toEqual(['R', 'R'])
    expect(recipe(SWORD).key).not.toHaveProperty('S')
    // It stays where it was on the grid.
    expect(square('Square 5: ruby'))
  })

  it('swaps two squares dragged onto each other, the pattern derived again', async () => {
    await open(SWORD)
    drag(square('Square 2: ruby'), square('Square 1: empty'))
    expect(recipe(SWORD).pattern).toEqual(['R ', ' R', ' S'])
    expect(square('Square 1: ruby'))
  })

  it('lays the ingredients out afresh for another type', async () => {
    const user = userEvent.setup()
    await open(SWORD)
    await user.selectOptions(screen.getByRole('combobox', { name: 'Recipe type' }), 'shapeless')
    expect(recipe(SWORD).type).toBe('shapeless')
    expect(recipe(SWORD)).not.toHaveProperty('pattern')
    expect(screen.getByRole('group', { name: /station/ })).toBeTruthy()
    expect(screen.getAllByRole('button', { name: /^Ingredient 1: / })).toHaveLength(1)
  })
})

describe('a furnace recipe', () => {
  it('edits what it gives, and leaves a cleared cooking time to the type', async () => {
    const user = userEvent.setup()
    await open(DUST)
    expect(square('Input: ruby'))

    const experience = inspector().getByRole('textbox', { name: 'Experience' })
    await user.clear(experience)
    await user.type(experience, '1.5{Enter}')
    expect(recipe(DUST).experience).toBe(1.5)

    const cooking = inspector().getByRole('textbox', { name: 'Cooking time' })
    await user.type(cooking, '100{Enter}')
    expect(recipe(DUST).cookingTime).toBe(100)
    await user.clear(cooking)
    await user.tab()
    expect(recipe(DUST)).not.toHaveProperty('cookingTime')
  })
})
