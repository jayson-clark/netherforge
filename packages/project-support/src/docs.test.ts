import { readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import { agentDocFiles } from './agentDocs.ts'
import { agentDocPaths } from './bundled.ts'

describe('agent docs, against the docs folder', () => {
  it("is the docs site's own Markdown, every page and word of it, so the bundle can't drift", async () => {
    const docs = path.resolve(import.meta.dirname, '../../../docs')
    const sources = ['guide', 'format', 'reference'].flatMap((folder) =>
      readdirSync(path.join(docs, folder))
        .filter((it) => it.endsWith('.md'))
        .map((it) => `${folder}/${it}`),
    )
    expect(agentDocPaths).toEqual(sources.sort())
    const files = await agentDocFiles('1.2.3', null, null)
    for (const page of sources) {
      expect(files[`.netherforge/docs/${page}`]).toBe(readFileSync(path.join(docs, page), 'utf8'))
    }
  })
})
