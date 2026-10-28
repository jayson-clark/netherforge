/**
 * Turns the API spec into everything generated from it:
 *
 *  - `packages/api/generated/api.json`: the whole spec, as data for tools and the
 *    plugin's conformance test;
 *  - `packages/api/generated/luals/nf.lua`: LuaLS `---@meta` stubs, for completions in
 *    VS Code, Neovim and anything else that runs lua-language-server, and
 *    `luals/surfaces/*.lua`, what `this` is in each kind of script, and `luals/gates.json`,
 *    the functions a project may not be able to use, which the editor marks for its project;
 *  - `docs/reference/*.md`: the API reference;
 *  - the plugin runtime's bindings: `src/generated/lua/.../bindings.lua` (the calls
 *    into Kotlin) and `src/generated/kotlin/.../api/*.kt` (the interfaces the runtime
 *    implements, the primitives, the shapes and unions that cross, the event registry, and
 *    the server's events from the adapter's sink to the scripts: `emit/raised.ts`).
 *
 * Run with `pnpm generate` from the repo root. Output is deterministic, so a
 * second run is never a diff; `pnpm lint` checks exactly that.
 *
 * `--out <dir>` writes the same tree under `<dir>` instead of the repo root
 * (`tools/generated-check.mjs` compares one against the working tree).
 */
import { mkdirSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'
import { api } from '../src/spec/index.ts'
import { bindings } from './emit/bindings.ts'
import { REFERENCE_OWN_PAGES, referencePages } from './emit/docs.ts'
import { checkLayout } from './emit/layout.ts'
import {
  apiKotlin,
  argumentCodecsKotlin,
  argumentTypesKotlin,
  eventsKotlin,
  gatesKotlin,
  handlesKotlin,
  primitivesKotlin,
  shapesKotlin,
  unionsKotlin,
} from './emit/kotlin.ts'
import { bindingsLua, schemaLua } from './emit/lua.ts'
import { raisableKotlin } from './emit/raisable.ts'
import { dispatchKotlin, platformKotlin, raisedEvents } from './emit/raised.ts'
import { luals, lualsGates, lualsSurfaces } from './emit/luals.ts'
import { terrainReference, terrainStubs } from './emit/terrain.ts'
import { terrainApi } from '../src/terrain.ts'

const { values } = parseArgs({ options: { out: { type: 'string' } } })
const root = values.out
  ? path.resolve(values.out)
  : path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')

function write(relative: string, text: string) {
  const file = path.join(root, relative)
  mkdirSync(path.dirname(file), { recursive: true })
  writeFileSync(file, text.replace(/\n*$/, '\n'))
}

/** Writes [files] into [dir] and deletes anything else there with one of [extensions]. */
function writeDir(dir: string, files: Map<string, string>, extension: string) {
  const absolute = path.join(root, dir)
  mkdirSync(absolute, { recursive: true })
  for (const file of readdirSync(absolute)) {
    if (file.endsWith(extension) && !files.has(file)) rmSync(path.join(absolute, file))
  }
  for (const [file, text] of files) write(`${dir}/${file}`, text)
}

write('packages/api/generated/api.json', JSON.stringify(api, null, 2))
write('packages/api/generated/luals/nf.lua', luals(api))
write('packages/api/generated/luals/gates.json', lualsGates(api))
writeDir('packages/api/generated/luals/surfaces', lualsSurfaces(api), '.lua')
write('packages/api/generated/terrain.json', JSON.stringify(terrainApi, null, 2))
write('packages/api/generated/luals/terrain.lua', terrainStubs(terrainApi))
checkLayout(api, REFERENCE_OWN_PAGES)
writeDir(
  'docs/reference',
  new Map([...referencePages(api), ['terrain-scripts.md', terrainReference(terrainApi)]]),
  '.md',
)

const runtime = 'apps/plugin/runtime/src/generated'
const bound = bindings(api)
const classes = bound.classes
const raised = raisedEvents(api, bound)
write(`${runtime}/lua/dev/netherforge/plugin/lua/bindings.lua`, bindingsLua(classes, api))
write(`${runtime}/lua/dev/netherforge/plugin/lua/prelude/schema.lua`, schemaLua(api))
writeDir(
  `${runtime}/kotlin/dev/netherforge/plugin/binding`,
  new Map([
    ['LuaHandle.kt', handlesKotlin(classes)],
    ['LuaApi.kt', apiKotlin(classes)],
    ['LuaPrimitives.kt', primitivesKotlin(classes)],
    ['LuaShapes.kt', shapesKotlin(api, bound)],
    ['LuaUnions.kt', unionsKotlin(bound)],
    ['Events.kt', eventsKotlin(api)],
    ['VersionGates.kt', gatesKotlin(api)],
    ['GameEventDispatch.kt', dispatchKotlin(raised)],
    ['RaisableEvents.kt', raisableKotlin(api, raised)],
    ['ArgumentCodecs.kt', argumentCodecsKotlin(bound)],
  ]),
  '.kt',
)
writeDir(
  `${runtime}/kotlin/dev/netherforge/plugin/platform`,
  new Map([
    ['GameEvents.kt', platformKotlin(raised)],
    ['CommandArguments.kt', argumentTypesKotlin(bound)],
  ]),
  '.kt',
)
