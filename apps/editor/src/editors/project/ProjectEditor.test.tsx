import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import type { MemoryBackend } from '@/testing/backend/memory'
import { AppProvider, type AppStores } from '@/state/providers'
import { exampleFiles, exampleProject } from '@/testing/fixtures'
import { openExampleApp } from '@/testing/workspace'
import { ProjectEditor } from './ProjectEditor'

const MANIFEST = 'netherforge.json'

let backend: MemoryBackend
let app: AppStores

/** The example's manifest without its own `allow`, `requires` and `worlds`, so each test starts with none. */
function bare(): string {
  const manifest = JSON.parse(exampleFiles[MANIFEST]!)
  delete manifest.allow
  delete manifest.requires
  delete manifest.worlds
  return JSON.stringify(manifest, null, 2) + '\n'
}

beforeEach(async () => {
  ;({ backend, app } = await openExampleApp({ project: { ...exampleProject, [MANIFEST]: bare() } }))
  await app.workspace.getState().openFile(MANIFEST)
})

afterEach(cleanup)

function show() {
  render(
    <AppProvider app={app}>
      <ProjectEditor path={MANIFEST} />
    </AppProvider>,
  )
}

const saved = async () => {
  await act(() => app.workspace.getState().save(MANIFEST))
  return JSON.parse(backend.testFiles()[MANIFEST]!)
}

describe('project settings: what scripts need', () => {
  it('turns moderation and a database on and off, leaving no requires behind', async () => {
    show()
    const moderation = screen.getByRole('checkbox', { name: 'Moderation' })
    expect((moderation as HTMLInputElement).checked).toBe(false)
    fireEvent.click(moderation)
    fireEvent.click(screen.getByRole('checkbox', { name: 'Database' }))
    expect((await saved()).requires).toEqual({ moderation: true, db: true })
    fireEvent.click(moderation)
    fireEvent.click(screen.getByRole('checkbox', { name: 'Database' }))
    expect('requires' in (await saved())).toBe(false)
  })

  it('adds hosts and plugins, refusing what is not one', async () => {
    show()
    const host = screen.getByRole('textbox', { name: 'Host' })
    fireEvent.change(host, { target: { value: 'https://discord.com' } })
    expect(screen.getByRole('alert').textContent).toMatch(/^A host name in lowercase/)
    for (const name of ['discord.com', '*.example.com']) {
      fireEvent.change(host, { target: { value: name } })
      fireEvent.keyDown(host, { key: 'Enter' })
    }
    const plugin = screen.getByRole('textbox', { name: 'Plugin' })
    fireEvent.change(plugin, { target: { value: 'Vault' } })
    expect(screen.getByRole('alert').textContent).toMatch(/^The plugin's name in lowercase/)
    fireEvent.change(plugin, { target: { value: 'vault' } })
    fireEvent.keyDown(plugin, { key: 'Enter' })
    expect((await saved()).requires).toEqual({
      http: ['*.example.com', 'discord.com'],
      plugins: ['vault'],
    })
  })

  it('shows what the whole tree requires, with who declares it', async () => {
    // The example's library declares a host; the project, once validated, declares moderation.
    show()
    fireEvent.click(screen.getByRole('checkbox', { name: 'Moderation' }))
    await act(() => app.workspace.getState().validateNow())
    const table = screen.getByRole('table', { name: 'Requirements across the packages' })
    expect(
      within(table)
        .getAllByRole('row')
        .slice(1)
        .map((row) => row.textContent),
    ).toEqual(['moderationshowcase', 'http:api.example.comlibrary'])
  })

  it('adds permissions in order, refusing bad and repeated ones, and removes them', async () => {
    show()
    const input = screen.getByRole('textbox', { name: 'Permission' })
    const add = screen.getByRole('button', { name: 'Add permission' }) as HTMLButtonElement

    fireEvent.change(input, { target: { value: 'Shop' } })
    expect(screen.getByRole('alert').textContent).toMatch(/^Lowercase letters, digits/)
    expect(add.disabled).toBe(true)

    for (const node of ['shop.vip', 'arena']) {
      fireEvent.change(input, { target: { value: node } })
      fireEvent.keyDown(input, { key: 'Enter' })
    }
    fireEvent.change(input, { target: { value: 'arena' } })
    expect(screen.getByRole('alert').textContent).toBe('That permission is listed already')
    expect(add.disabled).toBe(true)

    const list = screen.getByRole('list', { name: 'Grantable permissions' })
    expect(
      within(list)
        .getAllByRole('listitem')
        .map((it) => it.textContent),
    ).toEqual(['arena', 'shop.vip'])
    expect((await saved()).allow).toEqual({ permissions: ['arena', 'shop.vip'] })

    fireEvent.click(screen.getByRole('button', { name: 'Remove arena' }))
    fireEvent.click(screen.getByRole('button', { name: 'Remove shop.vip' }))
    expect(within(list).getByText('None.')).toBeTruthy()
    expect('allow' in (await saved())).toBe(false)
  })
})

describe('project settings: mob spawning by world', () => {
  it('adds a world, sets and clears its limits and intervals, and removes it', async () => {
    show()
    const name = screen.getByRole('textbox', { name: 'Spawn world name' })
    fireEvent.change(name, { target: { value: 'bad name' } })
    expect(screen.getByRole('alert').textContent).toMatch(/^Letters, digits/)
    fireEvent.change(name, { target: { value: 'lobby' } })
    fireEvent.keyDown(name, { key: 'Enter' })
    const world = screen.getByRole('group', { name: 'World lobby' })
    // Nothing set yet: it isn't in the file.
    expect('worlds' in (await saved())).toBe(false)

    const set = (label: string, value: string) => {
      const field = within(world).getByRole('textbox', { name: label })
      fireEvent.change(field, { target: { value } })
      fireEvent.keyDown(field, { key: 'Enter' })
    }
    set('monster limit', '0')
    set('animal interval', '400')
    set('ambient limit', '-4')
    expect((await saved()).worlds).toEqual({
      lobby: { spawnLimits: { monster: 0, ambient: 0 }, spawnIntervals: { animal: 400 } },
    })

    set('ambient limit', '')
    set('animal interval', '')
    expect((await saved()).worlds).toEqual({ lobby: { spawnLimits: { monster: 0 } } })

    // The undo is the document's own.
    await act(() => app.workspace.getState().undo(MANIFEST))
    expect((await saved()).worlds.lobby.spawnIntervals).toEqual({ animal: 400 })

    fireEvent.click(screen.getByRole('button', { name: 'Remove world lobby' }))
    await waitFor(() => expect(screen.queryByRole('group', { name: 'World lobby' })).toBeNull())
    expect('worlds' in (await saved())).toBe(false)
  })
})

describe('project settings: server-owner settings', () => {
  it('adds a setting through its dialog, refusing a bad or taken name', async () => {
    show()
    fireEvent.click(screen.getByRole('button', { name: 'Add setting…' }))
    const dialog = screen.getByRole('dialog', { name: 'Add setting' })
    const name = within(dialog).getByRole('textbox', { name: 'Name' })
    const add = within(dialog).getByRole('button', { name: 'Add' }) as HTMLButtonElement

    fireEvent.change(name, { target: { value: 'Max-Players' } })
    expect(within(dialog).getByRole('alert').textContent).toMatch(/^A setting's name is lowercase/)
    fireEvent.change(name, { target: { value: 'greeting' } })
    expect(within(dialog).getByRole('alert').textContent).toBe(
      'The project has a setting of that name already',
    )
    expect(add.disabled).toBe(true)

    fireEvent.change(name, { target: { value: 'difficulty' } })
    fireEvent.change(within(dialog).getByRole('combobox', { name: 'Type' }), {
      target: { value: 'choice' },
    })
    // A choice needs its choices first.
    expect(add.disabled).toBe(true)
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Choices, one per line' }), {
      target: { value: 'easy\nhard\n' },
    })
    fireEvent.change(within(dialog).getByRole('textbox', { name: 'Description' }), {
      target: { value: 'How hard the waves are.' },
    })
    fireEvent.click(add)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect((await saved()).settings.difficulty).toEqual({
      type: 'choice',
      description: 'How hard the waves are.',
      default: 'easy',
      choices: ['easy', 'hard'],
    })
  })

  it('edits a setting in place and removes it', async () => {
    show()
    const rolls = screen.getByRole('group', { name: 'Setting treasure_rolls' })
    const most = within(rolls).getByRole('textbox', { name: 'Most' })
    fireEvent.change(most, { target: { value: '8' } })
    fireEvent.keyDown(most, { key: 'Enter' })
    const fallback = within(rolls).getByRole('textbox', { name: 'Default' })
    // A whole number's default stays whole, so the file still reads.
    fireEvent.change(fallback, { target: { value: '2.4' } })
    fireEvent.keyDown(fallback, { key: 'Enter' })
    expect((await saved()).settings.treasure_rolls).toMatchObject({ default: 2, min: 1, max: 8 })

    fireEvent.change(within(rolls).getByRole('combobox', { name: 'Type' }), {
      target: { value: 'boolean' },
    })
    expect((await saved()).settings.treasure_rolls).toEqual({
      type: 'boolean',
      description: 'How many times /treasure rolls the treasure loot table.',
      default: false,
    })

    fireEvent.click(screen.getByRole('button', { name: 'Remove setting treasure_rolls' }))
    expect(Object.keys((await saved()).settings).sort()).toEqual([
      'expo',
      'greeting',
      'show_welcome',
      'tour_bossbar',
    ])
  })
})
