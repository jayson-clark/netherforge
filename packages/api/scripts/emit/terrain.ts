/**
 * What `pnpm generate` makes of the terrain script API (`src/terrain.ts`): its LuaLS stubs, its reference
 * page and its JSON, for format's test of the Lua that implements it.
 */
import type { LuaClass } from '../../src/types.ts'
import type { TerrainApiSpec } from '../../src/terrain.ts'
import { GENERATED_BY } from './common.ts'
import { fieldsTable, fnDoc } from './docs.ts'
import { comment, fnStub } from './luals.ts'

/** The receiver a page's examples call a class's functions on: `terrain.noise`, `chunk:fill`. */
const receiver = (cls: LuaClass) => cls.name.toLowerCase()

/**
 * `packages/api/generated/luals/terrain.lua`. Every class is declared on a local table, so nothing here is a
 * global of other scripts; a script says what its `...` is (`---@type Terrain`) and what it returns
 * (`---@type TerrainStages`), as `terrain/<id>.lua`'s template does.
 */
export function terrainStubs(spec: TerrainApiSpec): string {
  const lines = [
    '---@meta',
    "-- A NetherForge terrain's script (terrain/<id>.lua), for lua-language-server: what the script",
    '-- is given as `...` and what it returns. It runs apart from the server: of nf.lua only `vec3`, `Vec3`',
    '-- and `nf.math` are there.',
    `-- ${GENERATED_BY}`,
    `-- The sandbox removes ${spec.removed.join(', ')}.`,
    '',
  ]
  for (const cls of spec.classes) {
    lines.push(...comment(cls.doc), `---@class ${cls.name}`, `local ${cls.name} = {}`, '')
    for (const fn of cls.functions) lines.push(...fnStub(cls.name, cls.methods ? ':' : '.', fn))
  }
  for (const shape of spec.shapes) {
    lines.push(...comment(shape.doc), `---@class ${shape.name}`)
    for (const field of shape.fields)
      lines.push(`---@field ${field.name}? ${field.type} ${field.doc}`.trimEnd())
    lines.push('')
  }
  return lines.join('\n')
}

/** `docs/reference/terrain-scripts.md`. */
export function terrainReference(spec: TerrainApiSpec): string {
  const parts = [
    `<!-- ${GENERATED_BY} -->`,
    '',
    '# Terrain scripts',
    '',
    "What a terrain's script (`terrain/<id>.lua`, named by its file's [`script`](../format/terrain.md#script)) can call. It isn't a server script: it runs while chunks are being made, and in the editor's preview, so it has nothing of the server (of `nf`, only `nf.math`), and gives the same blocks for a seed every time. Its body is given the world as `...` and returns its stages:",
    '',
    '```lua',
    '---@type Terrain',
    'local terrain = ...',
    'local ridges = terrain.noise("ridges")',
    '',
    '---@type TerrainStages',
    'local stages = {}',
    '',
    'function stages.height(x, z, height)',
    '  return height + math.max(0, ridges:at(x, z)) * 12',
    'end',
    '',
    'return stages',
    '```',
    '',
    `Lua 5.4's basics are there (\`string\`, \`table\`, \`math\`, \`utf8\`, \`coroutine\`, \`pairs\`, \`pcall\`, \`setmetatable\`, …), \`vec3\` and \`nf.math\` as in other scripts, and \`require\` of the project's modules; the sandbox removes ${spec.removed.map((it) => `\`${it}\``).join(', ')}. \`math.random\` is seeded for each call from the world's seed and where the call is (the column, the chunk), so it's the same there every time.`,
    '',
    '## Stages',
    '',
    spec.shapes.map((shape) => `${shape.doc}\n\n${fieldsTable(shape)}`).join('\n\n'),
    '',
  ]
  for (const cls of spec.classes) {
    parts.push(`## ${cls.name}`, '', cls.doc, '')
    for (const fn of cls.functions)
      parts.push(fnDoc(receiver(cls), cls.methods ? ':' : '.', fn, '###'))
  }
  return parts.join('\n')
}
