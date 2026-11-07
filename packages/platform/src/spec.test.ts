import { readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { EXTERNAL, spec as platform, type Decl, type PlatformFile } from './index.ts'
import { readKotlin } from './kotlin.ts'
import { GENERICS, namesIn, parseType, PRIMITIVES } from './type.ts'

const kotlinDir = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  '../../../apps/plugin/platform/src/main/kotlin/dev/netherforge/plugin/platform',
)

/** A declaration without its docs: what the two sides must agree on. */
function signature(decl: Decl): unknown {
  return JSON.parse(JSON.stringify(decl, (key, value) => (key === 'doc' ? undefined : value)))
}

const declared = new Set(platform.flatMap((file) => file.decls.map((it) => it.name)))
const union = (name: string) =>
  platform.flatMap((f) => f.decls).find((d) => d.kind === 'union' && d.name === name)

function typesOf(file: PlatformFile): { where: string; type: string }[] {
  return file.decls.flatMap((decl) => {
    const fields = 'fields' in decl ? (decl.fields ?? []) : []
    const out = fields.map((f) => ({ where: `${decl.name}.${f.name}`, type: f.type }))
    if (decl.kind === 'union')
      for (const c of decl.cases)
        for (const f of c.fields)
          out.push({ where: `${decl.name}.${c.name}.${f.name}`, type: f.type })
    if (decl.kind === 'enum')
      for (const p of decl.params) out.push({ where: `${decl.name}.${p.name}`, type: p.type })
    if (decl.kind === 'interface' || decl.kind === 'object') {
      for (const p of decl.properties) out.push({ where: `${decl.name}.${p.name}`, type: p.type })
      for (const m of decl.methods) {
        out.push({ where: `${decl.name}.${m.name} returns`, type: m.returns })
        for (const p of m.params)
          out.push({ where: `${decl.name}.${m.name}(${p.name})`, type: p.type })
      }
    }
    return out
  })
}

describe('the Platform spec', () => {
  it('says what the Kotlin interfaces say, file by file, until Kotlin is generated from it', () => {
    const kotlinFiles = readdirSync(kotlinDir)
      .filter((it) => it.endsWith('.kt'))
      .sort()
    expect(platform.map((it) => it.source).sort()).toEqual(kotlinFiles)
    for (const file of platform) {
      const kotlin = readKotlin(
        readFileSync(path.join(kotlinDir, file.source), 'utf8'),
        file.source,
      )
      expect(
        kotlin.decls.map(signature),
        `${file.source}: run \`pnpm --filter @netherforge/platform extract\``,
      ).toEqual(file.decls.map(signature))
    }
  })

  it('names only types it declares, format declares, or every language has', () => {
    const unknown: string[] = []
    for (const file of platform)
      for (const { where, type } of typesOf(file)) {
        const node = parseType(type)
        for (const name of namesIn(node)) {
          // A sealed interface's case, by its full name (`InventoryRef.Player`).
          const [outer, inner] = name.split('.')
          const nested = inner && union(outer!)?.kind === 'union'
          if (
            !PRIMITIVES.has(name) &&
            !(name in GENERICS) &&
            !declared.has(name) &&
            !(name in EXTERNAL) &&
            !nested
          )
            unknown.push(`${where}: ${name}`)
        }
      }
    expect(unknown).toEqual([])
  })

  it('declares each name once', () => {
    const names = platform.flatMap((file) => file.decls.map((it) => it.name))
    expect(names.filter((it, k) => names.indexOf(it) !== k)).toEqual([])
  })
})
