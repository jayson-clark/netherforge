import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { run } from './main.ts'
import { dependenciesFirst, findProjects, readWorkspace } from './workspace.ts'

let root: string
let env: NodeJS.ProcessEnv

/** A small project at [folder] under the workspace, depending on [dependencies] (namespace → path). */
function project(folder: string, namespace: string, dependencies: Record<string, string> = {}) {
  const dir = path.join(root, folder)
  mkdirSync(dir, { recursive: true })
  const manifest: Record<string, unknown> = {
    formatVersion: 1,
    name: namespace,
    namespace,
    version: '0.1.0',
    minecraft: '26.3',
  }
  if (Object.keys(dependencies).length > 0) {
    manifest.dependencies = Object.fromEntries(
      Object.entries(dependencies).map(([ns, where]) => [ns, { path: where }]),
    )
  }
  if (namespace === 'shared') manifest.exports = { modules: [] }
  writeFileSync(path.join(dir, 'netherforge.json'), JSON.stringify(manifest))
}

function workspace(value: unknown) {
  writeFileSync(path.join(root, 'netherforge-workspace.json'), JSON.stringify(value))
}

beforeEach(() => {
  root = mkdtempSync(path.join(os.tmpdir(), 'netherforge-workspace-'))
  env = { NETHERFORGE_DATA_DIR: path.join(root, '.data') }
  project('servers/hub', 'hub')
  project('servers/game', 'game', { shared: '../../packages/shared' })
  project('packages/shared', 'shared')
  mkdirSync(path.join(root, 'node_modules/x'), { recursive: true })
  writeFileSync(path.join(root, 'node_modules/x/netherforge.json'), '{}')
})
afterEach(() => rmSync(root, { recursive: true, force: true }))

describe('workspaces', () => {
  it("finds the workspace's projects, and puts the packages others depend on first", () => {
    expect(findProjects(root)).toEqual(['packages/shared', 'servers/game', 'servers/hub'])
    workspace({ name: 'Net', projects: ['servers/*', 'packages/*'] })
    const read = readWorkspace(root)
    expect(read.problems).toEqual([])
    // servers/* comes first in the file, but the package servers/game depends on goes before it.
    expect(read.projects).toEqual(['packages/shared', 'servers/game', 'servers/hub'])
    expect(dependenciesFirst(root, ['servers/hub', 'servers/game', 'packages/shared'])).toEqual([
      'servers/hub',
      'packages/shared',
      'servers/game',
    ])
  })

  it('checks every project from the workspace folder, after the workspace file itself', async () => {
    workspace({
      name: 'Net',
      projects: ['servers/*', 'packages/shared', 'missing'],
      network: { arrival: 'lobby', servers: { hub: 'servers/hub' } },
    })
    const outcome = await run(['check', root], env)
    const text = outcome.out.join('\n')
    expect(text).toContain('▶ netherforge-workspace.json')
    expect(text).toContain('[workspace.missing-project]')
    expect(text).toContain('[workspace.arrival]')
    expect(text).toContain('▶ packages/shared')
    expect(text).toContain('▶ servers/hub')
    expect(outcome.code).toBe(1)

    workspace({
      name: 'Net',
      projects: ['servers/*', 'packages/*'],
      network: { arrival: 'hub', servers: { hub: 'servers/hub' } },
    })
    const clean = await run(['check', root], env)
    expect(clean.out.at(-1)).toBe('Every project of Net passed (3).')
    expect(clean.code).toBe(0)

    const json = JSON.parse((await run(['check', root, '--json'], env)).out[0]!)
    expect(Object.keys(json.projects).sort()).toEqual([
      'packages/shared',
      'servers/game',
      'servers/hub',
    ])
  })

  it("warns of a player sent to a server the workspace's network doesn't have", async () => {
    workspace({
      name: 'Net',
      projects: ['servers/*', 'packages/*'],
      network: { arrival: 'hub', servers: { hub: 'servers/hub', game: 'servers/game' } },
    })
    mkdirSync(path.join(root, 'servers/hub/modules/portal'), { recursive: true })
    writeFileSync(
      path.join(root, 'servers/hub/modules/portal/init.lua'),
      'nf.network.send_player(player, "game")\nnf.network.send_player(player, "gmae")\n',
    )
    const outcome = await run(['check', root], env)
    const text = outcome.out.join('\n')
    expect(text).toContain('[workspace.unknown-server]')
    expect(text).toContain('"gmae"')
    expect(text).not.toContain('"game" in')
  })

  it("inside a project, a command is that project's alone", async () => {
    workspace({ name: 'Net', projects: ['servers/*'] })
    const outcome = await run(['check', path.join(root, 'servers/hub')], env)
    expect(outcome.out.join('\n')).not.toContain('▶')
  })
})
