import { describe, expect, it } from 'vitest'
import { gateReason, tailoredApiStubs, versionBefore, type ApiStubs } from './api.ts'
import { loadApiStubs } from './bundled.ts'

const stubs: ApiStubs = {
  files: {
    'nf.lua': [
      '---@meta',
      '--- Bans them.',
      'function Player:ban(options) end',
      '',
      'function nf.server.motd() end',
      '',
      'function World:shiny() end',
    ].join('\n'),
    'surfaces/centity.lua': '---@meta',
  },
  gates: [
    { function: 'Player:ban', requires: 'moderation' },
    { function: 'World:shiny', since: '26.4' },
  ],
}

describe("the API's stubs for a project", () => {
  it('compare Minecraft versions part by part', () => {
    expect(versionBefore('1.21.11', '26.1')).toBe(true)
    expect(versionBefore('26.1', '26.1.2')).toBe(true)
    expect(versionBefore('26.3', '26.3')).toBe(false)
    expect(versionBefore('26.10', '26.9')).toBe(false)
  })

  it('mark what the project can not use deprecated, saying why', () => {
    const files = tailoredApiStubs(stubs, { minecraft: '26.3', manifest: {} })
    expect(files['nf.lua']).toContain(
      [
        '--- Bans them.',
        '---@deprecated Needs `"requires": { "moderation": true }` in `netherforge.json`: without it, calling it is an error.',
        'function Player:ban(options) end',
      ].join('\n'),
    )
    expect(files['nf.lua']).toContain(
      '---@deprecated Needs Minecraft 26.4; this project targets 26.3.\nfunction World:shiny() end',
    )
    expect(files['nf.lua']).toContain('\nfunction nf.server.motd() end')
    expect(files['surfaces/centity.lua']).toBe('---@meta')
  })

  it('leave alone what the project can use', () => {
    const project = { minecraft: '26.4', manifest: { requires: { moderation: true } } }
    expect(tailoredApiStubs(stubs, project)).toEqual(stubs.files)
    expect(gateReason({ function: 'Player:ban', requires: 'moderation' }, project)).toBeNull()
  })

  it('load the generated stubs and gates from @netherforge/api', async () => {
    const api = await loadApiStubs()
    expect(api.files['nf.lua']).toMatch(/^---@meta/)
    for (const surface of ['centity', 'menu', 'dialog'])
      expect(api.files[`surfaces/${surface}.lua`]).toMatch(/^---@meta/)
    expect(api.gates).toContainEqual({ function: 'Player:ban', requires: 'moderation' })
    // Every gate names a function nf.lua declares, which is where its mark goes.
    for (const gate of api.gates)
      expect(api.files['nf.lua']).toContain(`\nfunction ${gate.function}(`)
  })
})
