/**
 * Keeps the file-format pages (`docs/format/*.md`) in step with the checker the editor and the `netherforge` command
 * run (format's JS build):
 *
 * - every ```json block that is a whole file validates, as its kind, with that checker (and, where the kind has a
 *   place in a project, as part of one);
 * - every key the JSON Schema of a kind knows is mentioned on that kind's page.
 *
 * How to write the examples is in docs/contributing.md ("Examples in the format pages").
 */
import { readdirSync, readFileSync } from 'node:fs'
import path from 'node:path'
import * as nf from '@netherforge/format'
import { DOCUMENT_KINDS, KINDS } from '@netherforge/format/constants'
import type { CanonicalResult, ProjectOutline } from '@netherforge/format/types'
import { describe, expect, it } from 'vitest'

const FORMAT = path.resolve(import.meta.dirname, '../format')
const SCHEMAS = path.resolve(
  import.meta.dirname,
  '../../packages/format/build/generated-contract/schema',
)

interface Block {
  page: string
  line: number
  /** The words after the fence's language. */
  attributes: string[]
  text: string
}

const pages = readdirSync(FORMAT)
  .filter((it) => it.endsWith('.md'))
  .sort()
const pageText = (page: string) => readFileSync(path.join(FORMAT, page), 'utf8')

/** The ```json blocks of [markdown], with what follows `json` on the fence. */
function jsonBlocks(page: string, markdown: string): Block[] {
  const blocks: Block[] = []
  let open: { info: string; line: number; body: string[] } | null = null
  markdown.split('\n').forEach((line, index) => {
    const fence = /^```(.*)$/.exec(line)
    if (!fence) {
      open?.body.push(line)
    } else if (!open) {
      open = { info: fence[1]!.trim(), line: index + 1, body: [] }
    } else {
      const [language, ...attributes] = open.info.split(/\s+/)
      if (language === 'json') {
        blocks.push({ page, line: open.line, attributes, text: open.body.join('\n') })
      }
      open = null
    }
  })
  return blocks
}

type Classified = { kind: string } | 'partial' | 'unmarked'

/**
 * What a block is: `{ kind }` for a whole file (its `$schema` names the kind, or `kind=<id>` follows `json`),
 * `'partial'` for a snippet (`json partial`), or `'unmarked'` for a plain block that says neither: a mistake in the
 * page.
 */
function classify(block: Pick<Block, 'attributes' | 'text'>): Classified {
  if (block.attributes.includes('partial')) return 'partial'
  const named = block.attributes.find((it) => it.startsWith('kind='))?.slice('kind='.length)
  const schema = /"\$schema":\s*"[^"]*\/([a-z_]+)\.schema\.json"/.exec(block.text)?.[1]
  const kind = named ?? schema
  return kind ? { kind } : 'unmarked'
}

const classified = pages
  .flatMap((page) => jsonBlocks(page, pageText(page)))
  .map((block) => ({ block, class: classify(block) }))

/** What a project that holds only the example can still complain about: its neighbours aren't there. */
const NEIGHBOURS = [
  'reference.',
  'script.missing',
  'terrain.script-missing',
  'resource_pack.texture-missing',
  'screen_font.missing',
  'package.missing',
  'package.export-missing',
]

const MANIFEST = JSON.stringify({
  formatVersion: 1,
  name: 'Docs',
  version: '1.0.0',
  namespace: 'docs',
  minecraft: '26.3',
})

/** Where a project holds a [kind]'s one example, or null for a kind that isn't a resource of its own. */
function projectPathOf(kind: string): string | null {
  if (kind === 'netherforge') return 'netherforge.json'
  const spec = (KINDS as Record<string, (typeof KINDS)[keyof typeof KINDS]>)[kind]
  if (!spec || spec.contents !== 'json') return null
  return spec.layout === 'folder'
    ? `${spec.folder}/example/${spec.main}`
    : `${spec.folder}/example${spec.extension}`
}

describe('the examples in docs/format', () => {
  it('has examples to check', () => {
    expect(classified.length).toBeGreaterThan(20)
    expect(classified.some((it) => typeof it.class === 'object')).toBe(true)
  })

  it('says of every json block whether it is a whole file or a snippet', () => {
    const unmarked = classified
      .filter((it) => it.class === 'unmarked')
      .map((it) => `${it.block.page}:${it.block.line}`)
    expect(
      unmarked,
      'a ```json block is a whole file, so it has a "$schema" line (or ```json kind=<id>); a snippet is ```json partial',
    ).toEqual([])
  })

  it('tells whole files from snippets', () => {
    const text = '{ "$schema": "../.netherforge/schema/loot_table.schema.json" }'
    expect(classify({ attributes: [], text })).toEqual({ kind: 'loot_table' })
    expect(classify({ attributes: ['partial'], text })).toBe('partial')
    expect(classify({ attributes: ['kind=bundle'], text: '{}' })).toEqual({ kind: 'bundle' })
    expect(classify({ attributes: [], text: '{}' })).toBe('unmarked')
  })

  const whole = classified.flatMap((it) =>
    typeof it.class === 'object' ? [{ block: it.block, kind: it.class.kind }] : [],
  )

  it.each(whole.map((it) => [`${it.block.page}:${it.block.line} (${it.kind})`, it] as const))(
    '%s validates',
    (_, { block, kind }) => {
      expect(DOCUMENT_KINDS as readonly string[]).toContain(kind)
      const parsed = JSON.parse(
        nf.canonicalize(kind, `${kind}.json`, block.text),
      ) as CanonicalResult
      expect(parsed.problems.map((it) => it.message)).toEqual([])
      expect(parsed.text).toBeDefined()
      const file = projectPathOf(kind)
      if (file === null) return
      const files: Record<string, string> = { 'netherforge.json': MANIFEST }
      files[file] = block.text
      const outline = JSON.parse(
        nf.loadProject(JSON.stringify(files), null, null, null, null),
      ) as ProjectOutline
      const problems = outline.problems.filter(
        (it) => !NEIGHBOURS.some((prefix) => it.code?.startsWith(prefix)),
      )
      expect(
        problems.map((it) => `${it.file}: [${it.code}] ${it.message} (at ${it.path})`),
      ).toEqual([])
    },
  )
})

/** An item stack (`{ "kind": …, "lore": … }`) is written the same wherever a file holds one: menu.md says how. */
const STACK = ['menu.md']

/** Which pages describe each kind of file; one that is spread over several lists them all. */
const PAGES: Record<(typeof DOCUMENT_KINDS)[number], string[]> = {
  netherforge: ['project.md', 'packages.md', 'settings.md', 'worlds.md', 'terrain.md'],
  lock: ['packages.md'],
  default_font: ['project.md'],
  vanilla: ['project.md'],
  structure_generation: ['worlds.md'],
  resource_pack: ['resource-pack.md'],
  particle_effect: ['particle-effect.md', ...STACK],
  cutscene: ['cutscene.md'],
  item: ['item.md', ...STACK],
  recipe: ['recipe.md', ...STACK],
  loot_table: ['loot.md', ...STACK],
  block: ['block.md'],
  game_block: ['game-block.md'],
  biome: ['biome.md'],
  sky: ['sky.md'],
  dimension_type: ['dimension-type.md'],
  terrain: ['terrain.md'],
  advancement: ['advancement.md'],
  menu: ['menu.md'],
  dialog: ['dialog.md', ...STACK],
  centity: ['centity.md'],
  bundle: ['packages.md'],
  datapack: ['datapack.md'],
  buff: ['buff.md'],
  screen: ['screen.md'],
  screen_font: ['screen-font.md'],
  video: ['video.md'],
  workspace: ['workspace.md'],
}

/** Every name a schema gives a property: its `properties`, and the names a map's keys may be (`propertyNames`). */
function keysOf(node: unknown, into: Set<string> = new Set()): Set<string> {
  if (Array.isArray(node)) {
    for (const item of node) keysOf(item, into)
  } else if (node && typeof node === 'object') {
    for (const [key, value] of Object.entries(node)) {
      if (key === 'properties' && value && typeof value === 'object') {
        // A map of names to their schemas: walk each schema, never the map as one (a key named `properties` isn't the keyword).
        for (const [name, schema] of Object.entries(value)) {
          into.add(name)
          keysOf(schema, into)
        }
        continue
      }
      if (key === 'propertyNames') {
        const names = (value as { enum?: unknown }).enum
        if (Array.isArray(names)) for (const name of names) into.add(String(name))
      }
      keysOf(value, into)
    }
  }
  return into
}

/**
 * A key, or a path of keys (`terrain.seaLevel`, `colors.sky`, `blocks[].pos`, `"base"`), as a page writes one in
 * code: its segments, or null for code that isn't one (`/nf settings set`, `minecraft:stone`, `0 to 15`).
 */
function keyPath(code: string): string[] | null {
  const path = code
    .trim()
    .replace(/^"(.*)"$/, '$1')
    .replace(/\[[^\]]*\]/g, '')
  if (!/^[A-Za-z_$][\w$-]*(\.[A-Za-z_$][\w$-]*)*$/.test(path)) return null
  return path.split('.')
}

/**
 * The keys an object's shape names in code, `{ "nutrition", "saturation", "canAlwaysEat"? }` or
 * `{ time, value: [x, y, z], easing? }`: each entry's key (quoted or not, `?` for optional), and the keys of an object
 * written as a value, but not what's inside a list (`x`, `y`, `z`). Null for code that isn't an object's shape.
 */
function objectKeys(code: string): string[] | null {
  const text = code.trim()
  if (!text.startsWith('{') || !text.endsWith('}')) return null
  const entries: string[] = []
  let depth = 0
  let start = 1
  for (let i = 1; i < text.length - 1; i += 1) {
    const char = text[i]!
    if (char === '{' || char === '[') depth += 1
    else if (char === '}' || char === ']') depth -= 1
    else if (char === ',' && depth === 0) {
      entries.push(text.slice(start, i))
      start = i + 1
    }
  }
  entries.push(text.slice(start, text.length - 1))
  return entries.flatMap((entry) => {
    const match = /^\s*"?([A-Za-z_$][\w$-]*)"?\??\s*(?::\s*(.*))?$/s.exec(entry)
    if (!match) return []
    return [match[1]!, ...(objectKeys(match[2] ?? '') ?? [])]
  })
}

/**
 * The keys a page names, where naming one is deliberate:
 *
 * - inline code that is the key, a path of keys (`terrain.base`, `colors.sky`), an object's shape
 *   (`{ "nutrition", "saturation" }`) or one entry of one (`"invert": true`): not every word of any code span;
 * - a key of a ```json example (`"base": …`), not a quoted word in prose;
 * - a table row's first cell, whole (`| base | … |`, backticks or not), the way the reference tables list fields;
 * - a word of a heading (`## Spawning` doesn't count; `## spawning` or `` ## `spawning` `` does).
 */
function mentionsIn(markdown: string): Set<string> {
  const keys = new Set<string>()
  const add = (path: string[] | null) => path?.forEach((key) => keys.add(key))
  let fence: string | null = null
  for (const line of markdown.split('\n')) {
    const opens = /^```(\w*)/.exec(line)
    if (opens) {
      fence = fence === null ? opens[1]! : null
      continue
    }
    if (fence !== null) {
      if (fence === 'json') for (const key of line.matchAll(/"([^"\n]+)"\s*:/g)) add([key[1]!])
      continue
    }
    for (const span of line.matchAll(/`([^`\n]+)`/g)) {
      const code = span[1]!
      // One quoted entry, `"invert": true`; an unquoted `a:b` is an id, not an entry.
      const entry = /^"[\w$-]+"\s*:/.test(code) ? objectKeys(`{ ${code} }`) : null
      add(keyPath(code) ?? objectKeys(code) ?? entry)
    }
    const firstCell = /^\s*\|([^|]*)\|/.exec(line)
    if (firstCell) add(keyPath(firstCell[1]!.replaceAll('`', '')))
    const heading = /^#{1,6}\s+(.*)$/.exec(line)
    if (heading)
      for (const word of heading[1]!.replaceAll('`', '').split(/[^\w$-]+/)) add(keyPath(word))
  }
  return keys
}

describe('the keys in the schemas', () => {
  it('finds the keys of a schema and the names a page writes', () => {
    const schema = JSON.parse(readFileSync(path.join(SCHEMAS, 'terrain.schema.json'), 'utf8'))
    expect([...keysOf(schema)]).toEqual(expect.arrayContaining(['terrain', 'seaLevel', 'ores']))
    const page = [
      '## `spawning` and Sounds',
      'Set `terrain.seaLevel`, `blocks[].pos`, `"invert": true` or `"fluid"`; not blank, not "quoted": 1,',
      'not inside `/nf settings set` or `minecraft:stone`.',
      '| `light` | the light |',
      '| min, max | bounds |',
      '```json partial',
      '{ "blend": 1 }',
      '```',
      '```lua',
      'local t = { "inLua": 1 }',
      '```',
    ].join('\n')
    expect([...mentionsIn(page)].sort()).toEqual(
      [
        'blend',
        'blocks',
        'fluid',
        'invert',
        'light',
        'pos',
        'seaLevel',
        'spawning',
        'terrain',
        'and',
        'Sounds',
      ].sort(),
    )
  })

  it('has a page for every kind of file', () => {
    expect(Object.keys(PAGES).sort()).toEqual([...DOCUMENT_KINDS].sort())
    for (const page of Object.values(PAGES).flat()) expect(pages).toContain(page)
  })

  it.each(DOCUMENT_KINDS.map((kind) => [kind] as const))(
    "%s: every key is on its page's",
    (kind) => {
      const schema = JSON.parse(readFileSync(path.join(SCHEMAS, `${kind}.schema.json`), 'utf8'))
      const mentioned = new Set(PAGES[kind].flatMap((page) => [...mentionsIn(pageText(page))]))
      const missing = [...keysOf(schema)].filter((key) => key !== '$schema' && !mentioned.has(key))
      expect(
        missing.join(', '),
        `keys of ${kind} files that ${PAGES[kind].join(', ')} never names as code, in a json example, as a table's first cell or in a heading`,
      ).toBe('')
    },
  )
})
