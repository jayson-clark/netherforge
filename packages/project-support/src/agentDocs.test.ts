import { describe, expect, it } from 'vitest'
import { agentDocFiles } from './agentDocs.ts'
import { agentDocPaths } from './bundled.ts'

describe('agent docs', () => {
  it('bundles the guides, the format spec and the API reference, and indexes every page', async () => {
    expect(agentDocPaths).toEqual(expect.arrayContaining(['format/centity.md', 'reference/nf.md']))
    expect(agentDocPaths.some((it) => it.startsWith('guide/'))).toBe(true)
    const files = await agentDocFiles('1.2.3', null, null)
    const readme = files['.netherforge/docs/README.md']!
    expect(readme).toMatch(/^# NetherForge 1\.2\.3/)
    for (const path of agentDocPaths) {
      expect(files[`.netherforge/docs/${path}`]).toBeDefined()
      expect(readme).toContain(`](${path})`)
    }
    expect(Object.keys(files).at(-1)).toBe('.netherforge/docs/README.md')
  })

  it("points at lua-language-server's stubs rather than keeping a copy of its own", async () => {
    const files = await agentDocFiles('1.2.3', null, null)
    expect(Object.keys(files).filter((it) => it.endsWith('.lua'))).toEqual([])
    expect(files['.netherforge/docs/README.md']).toContain('`../luals/nf.lua`')
  })

  it('writes the netherforge command beside the docs, when it has one, and says how to run it', async () => {
    const files = await agentDocFiles('1.2.3', null, '#!/usr/bin/env node')
    expect(files['.netherforge/bin/netherforge.mjs']).toBe('#!/usr/bin/env node')
    expect(files['.netherforge/docs/README.md']).toContain('netherforge.mjs check')
    const without = await agentDocFiles('1.2.3', null, null)
    expect('.netherforge/bin/netherforge.mjs' in without).toBe(false)
    expect(without['.netherforge/docs/README.md']).not.toContain('netherforge.mjs check')
  })

  it('writes nothing when the README already there is this bundle', async () => {
    const readme = (await agentDocFiles('1.2.3', null, null))['.netherforge/docs/README.md']!
    expect(await agentDocFiles('1.2.3', readme, null)).toEqual({})
    expect(Object.keys(await agentDocFiles('1.2.4', readme, null)).length).toBeGreaterThan(0)
  })
})
