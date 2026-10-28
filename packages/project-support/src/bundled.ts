/// <reference types="vite/client" />
/**
 * What this version of NetherForge writes into every project, bundled at
 * build time: the API's LuaLS stubs and gates (`@netherforge/api`), the JSON
 * Schemas (`@netherforge/format`) and the docs site's Markdown. Vite builds
 * both the editor and the CLI, so they're `import.meta.glob`s relative to this
 * file. The stubs and docs are lazy chunks: the editor's first screen needs
 * neither (the CLI, one file, inlines them).
 */
import type { ApiGate, ApiStubs } from './api.ts'

type Loader = () => Promise<string>

const nfLua = import.meta.glob('../../api/generated/luals/nf.lua', {
  query: '?raw',
  import: 'default',
}) as Record<string, Loader>

// A terrain script's API (terrain/<id>.lua): a file of its own, with no `nf` in it, since the script runs
// apart from the server. Its classes are locals, so nothing of it is a global anywhere else.
const terrainLua = import.meta.glob('../../api/generated/luals/terrain.lua', {
  query: '?raw',
  import: 'default',
}) as Record<string, Loader>

const surfaces = import.meta.glob('../../api/generated/luals/surfaces/*.lua', {
  query: '?raw',
  import: 'default',
}) as Record<string, Loader>

const gates = import.meta.glob('../../api/generated/luals/gates.json', {
  query: '?raw',
  import: 'default',
}) as Record<string, Loader>

const schemas = import.meta.glob('../../format/build/generated-contract/schema/*.json', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

const docs = import.meta.glob(
  ['../../../docs/guide/*.md', '../../../docs/format/*.md', '../../../docs/reference/*.md'],
  { query: '?raw', import: 'default' },
) as Record<string, Loader>

/** The last path segment: `…/surfaces/centity.lua` → `centity.lua`. */
const fileName = (key: string) => key.slice(key.lastIndexOf('/') + 1)

let loaded: Promise<ApiStubs> | null = null

/** The API's stubs (`nf.lua`, `terrain.lua`, `surfaces/*.lua`) and gates, loaded once (tried again if that failed). */
export function loadApiStubs(): Promise<ApiStubs> {
  loaded ??= (async () => {
    const files: Record<string, string> = {}
    for (const load of Object.values(nfLua)) files['nf.lua'] = await load()
    for (const load of Object.values(terrainLua)) files['terrain.lua'] = await load()
    for (const [key, load] of Object.entries(surfaces))
      files[`surfaces/${fileName(key)}`] = await load()
    const [text] = await Promise.all(Object.values(gates).map((load) => load()))
    return { files, gates: text ? (JSON.parse(text) as ApiGate[]) : [] }
  })()
  loaded.catch(() => {
    loaded = null
  })
  return loaded
}

/** The JSON Schemas format generates, by file name (`centity.schema.json`). */
export const schemaFiles: Record<string, string> = Object.fromEntries(
  Object.entries(schemas).map(([key, text]) => [fileName(key), text]),
)

/** `…/docs/guide/scripting.md` → `guide/scripting.md`. */
const docPath = (key: string) => key.slice(key.indexOf('/docs/') + '/docs/'.length)

/** The bundled docs' paths under `.netherforge/docs/`, sorted. */
export const agentDocPaths: string[] = Object.keys(docs).map(docPath).sort()

/** The docs site's pages, by path under `.netherforge/docs/` (`guide/scripting.md`). */
export async function loadDocPages(): Promise<Record<string, string>> {
  return Object.fromEntries(
    await Promise.all(
      Object.entries(docs).map(async ([key, load]) => [docPath(key), await load()] as const),
    ),
  )
}
