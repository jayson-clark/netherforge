import {
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { LUARC } from '@netherforge/format/constants'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { run } from './main.ts'
import { setup } from './setup.ts'

const example = path.resolve(import.meta.dirname, '../../../examples/showcase')

let tmp: string
let project: string
let env: NodeJS.ProcessEnv

beforeEach(() => {
  tmp = mkdtempSync(path.join(os.tmpdir(), 'netherforge-setup-'))
  project = path.join(tmp, 'project')
  // A fresh clone: no .netherforge/, which isn't committed.
  cpSync(example, project, {
    recursive: true,
    filter: (source) => path.relative(example, source) !== '.netherforge',
  })
  cpSync(path.resolve(example, '../library'), path.join(tmp, 'library'), { recursive: true })
  env = { NETHERFORGE_DATA_DIR: path.join(tmp, 'data') }
})
afterEach(() => rmSync(tmp, { recursive: true, force: true }))

const read = (file: string) => readFileSync(path.join(project, file), 'utf8')

describe('setup', () => {
  it("writes .netherforge/ as the editor does: the schemas, LuaLS's library and the docs", async () => {
    const outcome = await run(['setup', project], env)
    expect(outcome.code).toBe(0)
    expect(outcome.out[0]).toMatch(/^Set up \.netherforge\/ for NetherForge /)
    // The schema every file's $schema points at.
    const centity = JSON.parse(read('centities/crate/centity.json')) as { $schema: string }
    const schema = path.resolve(project, 'centities/crate', centity.$schema)
    expect(JSON.parse(readFileSync(schema, 'utf8'))).toHaveProperty('$schema')
    // The API's stubs, marked for what netherforge.json doesn't allow, and the surfaces.
    expect(read('.netherforge/luals/nf.lua')).toMatch(/^---@meta/)
    for (const surface of ['centity', 'menu', 'dialog'])
      expect(existsSync(path.join(project, `.netherforge/luals/surfaces/${surface}.lua`))).toBe(
        true,
      )
    // The project's names, and what the package it depends on exports.
    const names = read('.netherforge/luals/project.lua')
    expect(names).toContain('---@alias CentityId')
    expect(names).toContain('---| "crate"')
    expect(names).toContain('"library:')
    expect(read('.netherforge/docs/README.md')).toMatch(/^# NetherForge /)
    expect(existsSync(path.join(project, '.netherforge/docs/format/centity.md'))).toBe(true)
  })

  it('marks what the project may not use, from its netherforge.json as it is now', async () => {
    const manifest = JSON.parse(read('netherforge.json')) as { requires?: Record<string, unknown> }
    manifest.requires = {}
    writeFileSync(path.join(project, 'netherforge.json'), JSON.stringify(manifest))
    await run(['setup', project], env)
    expect(read('.netherforge/luals/nf.lua')).toContain(
      '---@deprecated Needs `"requires": { "moderation": true }`',
    )
  })

  it('writes the command it was run as into .netherforge/bin/, so generators and agents have it', async () => {
    const outcome = await setup(project, { version: '1.2.3', cli: '#!/usr/bin/env node\n' }, env)
    expect(outcome.out[0]).toContain('.netherforge/bin/netherforge.mjs')
    expect(read('.netherforge/bin/netherforge.mjs')).toBe('#!/usr/bin/env node\n')
    expect(read('.netherforge/docs/README.md')).toMatch(/^# NetherForge 1\.2\.3/)
  })

  it("adds a .luarc.json to a project without one, and keeps the project's own", async () => {
    rmSync(path.join(project, '.luarc.json'))
    const outcome = await run(['setup', project], env)
    expect(read('.luarc.json')).toBe(LUARC.text)
    expect(outcome.out.at(-1)).toContain('Wrote .luarc.json')
    writeFileSync(path.join(project, '.luarc.json'), '{ "mine": true }\n')
    const again = await run(['setup', project], env)
    expect(read('.luarc.json')).toBe('{ "mine": true }\n')
    expect(again.out.join('\n')).not.toContain('.luarc.json')
  })

  it('changes nothing the second time, and removes stubs for what the project no longer uses', async () => {
    await run(['setup', project], env)
    expect((await run(['setup', project], env)).out[1]).toBe('Everything was up to date.')
    const stale = path.join(project, '.netherforge/luals/packages/gone/old/init.lua')
    mkdirSync(path.dirname(stale), { recursive: true })
    writeFileSync(stale, '---@meta gone:old')
    expect((await run(['setup', project], env)).out[1]).toBe('0 files written, 1 removed.')
    expect(existsSync(stale)).toBe(false)
  })

  it('in a workspace, sets up every project, with the network’s servers as names', async () => {
    const root = path.join(tmp, 'workspace')
    mkdirSync(path.join(root, 'servers'), { recursive: true })
    const hub = path.join(root, 'servers/hub')
    mkdirSync(path.join(hub, 'modules'), { recursive: true })
    writeFileSync(
      path.join(hub, 'netherforge.json'),
      JSON.stringify({ formatVersion: 1, name: 'Hub', namespace: 'hub', version: '0.1.0' }),
    )
    writeFileSync(
      path.join(root, 'netherforge-workspace.json'),
      JSON.stringify({
        name: 'Net',
        projects: ['servers/*'],
        network: { arrival: 'hub', servers: { hub: 'servers/hub' } },
      }),
    )
    const outcome = await run(['setup', root], env)
    expect(outcome.code).toBe(0)
    expect(outcome.out).toContain('▶ servers/hub')
    expect(readFileSync(path.join(hub, '.netherforge/luals/project.lua'), 'utf8')).toContain(
      '---| "hub" # server of servers/hub',
    )
    expect(existsSync(path.join(root, '.netherforge/schema/workspace.schema.json'))).toBe(true)
    // From the member's own folder too.
    rmSync(path.join(hub, '.netherforge'), { recursive: true })
    expect((await run(['setup', hub], env)).code).toBe(0)
    expect(readFileSync(path.join(hub, '.netherforge/luals/project.lua'), 'utf8')).toContain(
      '---| "hub"',
    )
  })
})
