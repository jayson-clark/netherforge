import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { writeNbt, type NbtRoot } from '@netherforge/structures/nbt'
import { bytesToBase64 } from '@/core/backend/base64'
import type { MemoryBackend } from '@/testing/backend/memory'
import { AppProvider, type AppStores } from '@/state/providers'
import { DialogHost } from '@/ui/dialogs'
import { gameRulesRoot, levelRoot, worldGenRoot } from '@/testing/nbtFixtures'
import { openExampleApp } from '@/testing/workspace'
import { formatBytes, MapEditor } from './MapEditor'

const FOLDER = 'maps/arena'
const WORLD = `${FOLDER}/dimensions/minecraft/overworld`

let backend: MemoryBackend
let app: AppStores

beforeEach(async () => {
  ;({ backend, app } = await openExampleApp())
  await app.run.getState().connect()
})

afterEach(cleanup)

const base64 = async (root: NbtRoot) => bytesToBase64(await writeNbt(root))
/** [length] bytes of [fill], as base64. */
const filled = (length: number, fill = 0) => bytesToBase64(new Uint8Array(length).fill(fill))

/** Writes binary files into the project, as another program would, and lists them. */
async function write(files: Record<string, string>) {
  for (const [path, data] of Object.entries(files)) backend.testWriteBytes(path, data)
  await act(() => app.workspace.getState().refreshFiles())
}

/** The arena: a level, the overworld's seed and game rules, and [extra]. */
async function writeArena(extra: Record<string, string> = {}) {
  await write({
    [`${FOLDER}/level.dat`]: await base64(levelRoot({ name: 'Arena', spawn: [4, 70, -8] })),
    [`${WORLD}/data/minecraft/world_gen_settings.dat`]: await base64(worldGenRoot(-42n)),
    [`${WORLD}/data/minecraft/game_rules.dat`]: await base64(
      gameRulesRoot({ 'minecraft:keep_inventory': true, 'minecraft:random_tick_speed': 3 }),
    ),
    ...extra,
  })
}

function show() {
  render(
    <AppProvider app={app}>
      <MapEditor path={FOLDER} />
      <DialogHost />
    </AppProvider>,
  )
  return screen.getByRole('complementary', { name: 'Map' })
}

describe('formatBytes', () => {
  it('writes sizes as a person reads them', () => {
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(1536)).toBe('1.5 KB')
    expect(formatBytes(20 * 1024 * 1024)).toBe('20 MB')
    expect(formatBytes(3 * 1024 ** 4)).toBe('3072 GB')
  })
})

describe('the map screen', () => {
  it("says there's nothing yet, and waits for the dev server to save a world", () => {
    const inspector = show()
    expect(within(inspector).getByRole('status').textContent).toBe(
      `There's no ${FOLDER}/ yet. Save a dev-server world into it below.`,
    )
    expect(screen.getByRole('note', { name: 'Map preview' }).textContent).toMatch(
      /Save a dev-server world here/,
    )
    expect(within(inspector).getByText(/Start the dev server/)).toBeTruthy()
    const save = within(inspector).getByRole('button', { name: 'Save as map' })
    expect((save as HTMLButtonElement).disabled).toBe(true)
  })

  it("says a folder without level.dat isn't a world", async () => {
    await write({ [`${WORLD}/region/r.0.0.mca`]: filled(16) })
    const inspector = show()
    expect(within(inspector).getByRole('status').textContent).toBe(
      `${FOLDER}/ has no level.dat, so it isn't a world yet.`,
    )
  })

  it('shows what its saved data says: name, seed, spawn, dimensions, size and game rules', async () => {
    await writeArena()
    const inspector = show()
    expect((await within(inspector).findByLabelText('World name')).textContent).toBe('Arena')
    expect(within(inspector).getByLabelText('Seed').textContent).toBe('-42')
    expect(within(inspector).getByLabelText('Spawn').textContent).toBe('4, 70, -8')
    expect(within(inspector).getByLabelText('Dimensions').textContent).toBe('minecraft:overworld')
    expect((await within(inspector).findByLabelText('Size on disk')).textContent).toMatch(
      / in 3 files$/,
    )
    const rules = within(inspector).getByRole('table', { name: 'Game rules' })
    expect(
      within(rules)
        .getAllByRole('row')
        .map((it) => it.textContent),
    ).toEqual(
      expect.arrayContaining(['minecraft:keep_inventorytrue', 'minecraft:random_tick_speed3']),
    )
    // No region files: nothing to draw, and the preview says where it looked.
    expect(screen.getByRole('note', { name: 'Map preview' }).textContent).toBe(
      `${WORLD}/region/ has no region files, so there's nothing to draw.`,
    )
  })

  it('says why it reads nothing from a level.dat that is no NBT', async () => {
    await write({ [`${FOLDER}/level.dat`]: bytesToBase64(new TextEncoder().encode('not nbt')) })
    const inspector = show()
    expect((await within(inspector).findByRole('status')).textContent).toMatch(
      new RegExp(`^${FOLDER}/level.dat can't be read: `),
    )
    expect(screen.getByRole('note', { name: 'Map preview' }).textContent).toBe(
      "The map isn't drawn without a readable level.dat.",
    )
  })

  it('asks for client assets before drawing its region files', async () => {
    await writeArena({ [`${WORLD}/region/r.0.0.mca`]: filled(4096, 1) })
    show()
    expect(await screen.findByText(/client assets are imported/)).toBeTruthy()
    // The radius is only offered over a drawn map.
    expect(screen.queryByRole('slider', { name: 'View radius in chunks' })).toBeNull()
  })

  it('saves the chosen dev-server world over it once confirmed, with that world’s spawn', async () => {
    await writeArena()
    backend.testConnect()
    backend.testWorld('lobby')
    backend.testServerFiles({
      'world/level.dat': await base64(levelRoot({ name: 'world', spawn: [0, 88, 0] })),
      'world/dimensions/minecraft/lobby/data/minecraft/world_gen_settings.dat': await base64(
        worldGenRoot(7n),
      ),
    })
    const inspector = show()
    fireEvent.click(within(inspector).getByRole('button', { name: 'Refresh worlds' }))
    const world = await within(inspector).findByRole('option', { name: /lobby/ })
    fireEvent.change(within(inspector).getByRole('combobox', { name: 'World' }), {
      target: { value: (world as HTMLOptionElement).value },
    })

    // Cancelling leaves the map as it is.
    fireEvent.click(within(inspector).getByRole('button', { name: 'Save and replace' }))
    const confirm = await screen.findByRole('dialog', { name: 'Replace arena' })
    expect(confirm.textContent).toContain('with the world lobby')
    fireEvent.click(within(confirm).getByRole('button', { name: 'Cancel' }))
    expect(backend.bridgeLog.map((it) => it.method)).not.toContain('save_world')

    fireEvent.click(within(inspector).getByRole('button', { name: 'Save and replace' }))
    fireEvent.click(
      within(await screen.findByRole('dialog', { name: 'Replace arena' })).getByRole('button', {
        name: 'Replace',
      }),
    )
    expect((await within(inspector).findByRole('status')).textContent).toBe(
      `Saved lobby into ${FOLDER}/.`,
    )
    expect(backend.bridgeLog).toContainEqual({ method: 'save_world', params: { world: 'lobby' } })
    expect((await within(inspector).findByText('7')).getAttribute('aria-label')).toBe('Seed')
    // The copy's spawn is the world's own (the fake server's is 0, 64, 0), not the main world's.
    expect(within(inspector).getByLabelText('Spawn').textContent).toBe('0, 64, 0')
  })
})
