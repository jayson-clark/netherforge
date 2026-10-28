import { cleanup, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { modelOf } from '@/core/store/documents'
import type { CentityFile } from '@/core/format'
import { AppProvider, type AppStores } from '@/state/providers'
import { Inspector } from './Inspector'
import { openExampleApp } from '@/testing/workspace'

const WISP = 'centities/wisp/centity.json'
const TOWER = 'centities/tower/centity.json'

let app: AppStores

beforeEach(async () => {
  ;({ app } = await openExampleApp())
  await app.workspace.getState().openFile(WISP)
  await app.workspace.getState().openFile(TOWER)
})

const unsubscribe: (() => void)[] = []

afterEach(() => {
  for (const stop of unsubscribe.splice(0)) stop()
  cleanup()
})

const model = (path: string) => modelOf<CentityFile>(app.workspace.getState().docs[path])!

/** The inspector as the editor mounts it: given the model again whenever the document changes. */
function show(path: string) {
  const tree = () => (
    <AppProvider app={app}>
      <Inspector path={path} model={model(path)} />
    </AppProvider>
  )
  const view = render(tree())
  unsubscribe.push(app.workspace.subscribe(() => view.rerender(tree())))
}

describe("the inspector's natural spawning section", () => {
  it("shows a centity's spawning block and writes edits through the store", async () => {
    const user = userEvent.setup()
    show(WISP)
    expect(screen.getByLabelText('Worlds')).toHaveProperty('value', 'world_the_end')
    expect(screen.getByLabelText('Biomes')).toHaveProperty('value', '#minecraft:is_end')
    expect(screen.getByLabelText('Weight')).toHaveProperty('value', '4')

    const weight = screen.getByLabelText('Weight')
    await user.clear(weight)
    await user.type(weight, '12')
    await user.tab()
    expect(model(WISP).spawning?.weight).toBe(12)

    // Clearing a field leaves its key out.
    await user.clear(weight)
    await user.tab()
    expect(model(WISP).spawning).not.toHaveProperty('weight')

    const worlds = screen.getByLabelText('Worlds')
    await user.clear(worlds)
    await user.type(worlds, 'a, b')
    await user.tab()
    expect(model(WISP).spawning?.worlds).toEqual(['a', 'b'])

    const lightMax = screen.getByLabelText('Light max')
    await user.clear(lightMax)
    await user.tab()
    expect(model(WISP).spawning).not.toHaveProperty('light')
  })

  it('toggles whether interaction keeps a natural centity', async () => {
    const user = userEvent.setup()
    show(WISP)
    const keep = screen.getByRole('checkbox', { name: /interaction keeps one/i })
    expect(keep).toHaveProperty('checked', false)
    await user.click(keep)
    expect(model(WISP).spawning?.keepOnInteract).toBe(true)
    await user.click(screen.getByRole('checkbox', { name: /interaction keeps one/i }))
    expect(model(WISP).spawning).not.toHaveProperty('keepOnInteract')
  })

  it('turns spawning on for a centity that has none, and off again', async () => {
    const user = userEvent.setup()
    show(TOWER)
    expect(model(TOWER).spawning).toBeUndefined()
    expect(screen.queryByLabelText('Weight')).toBeNull()
    await user.click(screen.getByRole('checkbox', { name: /natural spawning/i }))
    expect(model(TOWER).spawning).toEqual({})
    await user.click(screen.getByRole('checkbox', { name: /natural spawning/i }))
    expect(model(TOWER).spawning).toBeUndefined()
  })
})

describe("the inspector's body, health and properties", () => {
  it('turns a body on, edits it with defaults as placeholders, and off again', async () => {
    const user = userEvent.setup()
    show(TOWER)
    await user.click(screen.getByRole('checkbox', { name: 'Body enabled' }))
    expect(model(TOWER).body).toEqual({})
    const width = screen.getByLabelText('Width')
    expect(width).toHaveProperty('placeholder', '0.6')
    await user.type(width, '0.9')
    await user.tab()
    await user.click(screen.getByRole('checkbox', { name: 'Flies' }))
    await user.click(screen.getByRole('checkbox', { name: 'Collides with blocks' }))
    expect(model(TOWER).body).toEqual({ width: 0.9, fly: true, blocks: false })
    await user.click(screen.getByRole('checkbox', { name: 'Body enabled' }))
    expect(model(TOWER).body).toBeUndefined()
  })

  it('gives health a max and a bar, and leaves the defaults out', async () => {
    const user = userEvent.setup()
    show(TOWER)
    await user.click(screen.getByRole('checkbox', { name: 'Health enabled' }))
    expect(model(TOWER).health).toEqual({ max: 20 })
    await user.click(screen.getByRole('checkbox', { name: 'Removed when it dies' }))
    await user.click(screen.getByRole('checkbox', { name: 'Health bar' }))
    const segments = screen.getByLabelText('Segments')
    await user.type(segments, '99')
    await user.tab()
    expect(model(TOWER).health).toEqual({ max: 20, removeOnDeath: false, bar: { segments: 40 } })
  })

  it('writes properties when they parse as an object, and says why when they don’t', async () => {
    const user = userEvent.setup()
    show(TOWER)
    const properties = screen.getByLabelText('Properties')
    await user.type(properties, '[[1')
    await user.tab()
    expect(screen.getByRole('alert').textContent).toMatch(/^Not JSON/)
    await user.clear(properties)
    await user.type(properties, '{{"damage": 12}')
    await user.tab()
    expect(model(TOWER).properties).toEqual({ damage: 12 })
    await user.clear(screen.getByLabelText('Properties'))
    await user.tab()
    expect(model(TOWER).properties).toBeUndefined()
  })
})
