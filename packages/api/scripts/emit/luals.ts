/** `packages/api/generated/luals/nf.lua`: LuaLS `---@meta` stubs. */
import type { ApiSpec, EventSpec, Field, Fn, LuaClass, Param, ValueType } from '../../src/types.ts'
import { asyncValue } from '../../src/async.ts'
import { NAME_ALIASES } from '../../src/names.ts'
import { requirementSentence } from '../../src/requirements.ts'
import { classEvents } from './bindings.ts'
import {
  GENERATED_BY,
  SAVEABLE,
  WAITS,
  eventAlias,
  eventedClasses,
  sinceVersion,
} from './common.ts'

/** `--- ` comment lines for a (possibly multi-line) doc. */
export function comment(doc: string): string[] {
  return doc ? doc.split('\n').map((line) => `--- ${line}`.trimEnd()) : []
}

/** A `---@field` line, its doc saying the version it needs when it has one. */
function fieldLine(field: Field): string {
  const doc = field.since
    ? `${field.doc} (Since Minecraft ${sinceVersion(field.since)}.)`.trim()
    : field.doc
  return `---@field ${field.name} ${field.type} ${doc}`.trimEnd()
}

/** A parameter's type, with the `string` that names a project thing as its alias (`NodeName`). */
function paramType(p: Param): string {
  if (!p.names) return p.type
  const alias = NAME_ALIASES[p.names]
  return p.type
    .split('|')
    .map((it) => (it === 'string' ? alias : it))
    .join('|')
}

function paramLines(params: Param[]): string[] {
  return params.map(
    (p) => `---@param ${p.name}${p.optional ? '?' : ''} ${paramType(p)}${p.doc ? ` ${p.doc}` : ''}`,
  )
}

/**
 * The aliases `names` parameters are typed as, each a plain `string` here. A project's own
 * names are more of the same alias (the editor writes them into `.netherforge/luals/`), which
 * LuaLS merges with these, so the names complete and any string still type-checks.
 */
function nameAliasLines(): string[] {
  return Object.entries(NAME_ALIASES).map(
    ([kind, alias]) => `---@alias ${alias} string # A project ${kind.replace('_', ' ')}'s name.`,
  )
}

function returnLines(fn: Fn): string[] {
  return fn.returns.map((r) => `---@return ${r.type}${r.doc ? ` # ${r.doc}` : ''}`)
}

function signature(fn: Fn): string {
  return fn.params.map((p) => p.name).join(', ')
}

/**
 * A function's stub. One that waits is `---@async`, which lua-language-server lets only another
 * async function call (`nf.task`'s callback is `async fun`): a handler that waits is flagged
 * (`not-yieldable`, with `hint.awaitPropagate` in the project's `.luarc.json`). An async one
 * gets an overload per form, so only its waiting form counts as waiting.
 */
export function fnStub(
  owner: string,
  separator: '.' | ':',
  fn: Fn,
  extra: string[] = [],
): string[] {
  return [
    ...comment(fn.doc),
    ...(fn.waits ? comment(WAITS) : []),
    ...(fn.since ? comment(`Since Minecraft ${sinceVersion(fn.since)}.`) : []),
    ...(fn.requires ? comment(requirementSentence(fn.requires, `${owner}.${fn.name}`)) : []),
    ...paramLines(fn.params),
    ...returnLines(fn),
    ...(fn.waits ? ['---@async'] : []),
    ...(fn.async ? asyncOverloads(owner, separator, fn) : []),
    ...extra,
    `function ${owner}${separator}${fn.name}(${signature(fn)}) end`,
    '',
  ]
}

/**
 * An async function's two forms, as overloads: waiting (`async`, in a task, returning
 * `value, err`) and with its callback (anywhere, returning nothing). LuaLS picks the one a
 * call matches, so a handler that waits is flagged and one that passes a callback isn't.
 */
function asyncOverloads(owner: string, separator: '.' | ':', fn: Fn): string[] {
  const value = asyncValue(fn, `${owner}.${fn.name}`)
  const callback = fn.params.at(-1)!
  const params = fn.params
    .slice(0, -1)
    .map((p) => `${p.name}${p.optional ? '?' : ''}: ${paramType(p)}`)
  const self = separator === ':' ? [`self: ${owner}`] : []
  return [
    `---@overload async fun(${[...self, ...params].join(', ')}): ${value}?, string?`,
    `---@overload fun(${[...self, ...params, `callback: ${callback.type}`].join(', ')})`,
  ]
}

/**
 * A class's event names, its own and those it inherits, as a LuaLS alias; a class that takes
 * custom events takes any string too.
 */
function aliasLine(spec: ApiSpec, cls: Evented): string {
  const names = classEvents(spec, cls).map(({ event }) => JSON.stringify(event.name))
  return `---@alias ${eventAlias(cls)} ${[...names, ...(cls.customEvents ? ['string'] : [])].join('|')}`
}

/**
 * What a custom event's handler gets: an `Event` plus the fields of the payload `emit` raised
 * it with, which no stub can know. Declared only in the stubs; the reference calls it `Event`.
 */
const CUSTOM_EVENT = 'CustomEvent'

function customEventStub(): string[] {
  return [
    ...comment(
      'A custom event (a name with a `:`, raised with `emit`): an `Event` plus whatever fields its payload had, so any field reads as `any`.',
    ),
    `---@class ${CUSTOM_EVENT}: Event`,
    '---@field [string] any',
    '',
  ]
}

type Evented = LuaClass & { events: EventSpec[] }

/** What a handler of `cls`'s events gets when LuaLS can't tell which event it is. */
const fallbackPayload = (cls: Evented) => (cls.customEvents ? CUSTOM_EVENT : 'Event')

/**
 * `on` and `once` on a class with events, typed per event so the handler's parameter is the
 * event's payload: one overload per event, after a signature that takes any of its names (and,
 * on a class with custom events, any string, with a `CustomEvent`). Its events are its own
 * and those it inherits up its chain. LuaLS matches a colon method's call against an overload
 * with `self` first, so a method's overloads name it.
 */
function onStub(spec: ApiSpec, cls: Evented, fn: Fn): string[] {
  const separator = cls.methods ? ':' : '.'
  const events = classEvents(spec, cls).map((it) => it.event)
  if (events.length === 1 && !cls.customEvents) {
    // An overload that says the same as the signature is ambiguous to LuaLS, which then
    // falls back to the signature's `fun(event: Event)`: type the one handler directly.
    return fnStub(cls.name, separator, withHandler(fn, events[0]!.payload ?? 'Event'))
  }
  const receiver = cls.methods ? `self: ${cls.name}, ` : ''
  const overloads = events.map((e) => {
    const options = fn.name === 'on' && e.options?.length ? ', options?: EventOptions' : ''
    return `---@overload fun(${receiver}event: ${JSON.stringify(e.name)}, handler: fun(event: ${e.payload ?? 'Event'})${options}): Subscription`
  })
  return fnStub(cls.name, separator, withHandler(fn, fallbackPayload(cls)), overloads)
}

function withHandler(fn: Fn, payload: string): Fn {
  const params = fn.params.map((p) =>
    p.name === 'handler' ? { ...p, type: `fun(event: ${payload})` } : p,
  )
  return { ...fn, params }
}

/**
 * `nf.wait_for(handle, event, filter)`, typed per evented class and event like `on`, so the event
 * it returns has its payload's fields. Any other name is a custom event.
 */
function waitForStub(owner: LuaClass, fn: Fn, evented: Evented[]): string[] {
  const overloads = evented.flatMap((cls) =>
    cls.events.map((e) => {
      const payload = e.payload ?? 'Event'
      return `---@overload fun(handle: ${cls.name}, event: ${JSON.stringify(e.name)}, filter?: fun(event: ${payload}): boolean): ${payload}`
    }),
  )
  const params = fn.params.map((p) =>
    p.name === 'filter' ? { ...p, type: `fun(event: ${CUSTOM_EVENT}): boolean` } : p,
  )
  const returns = fn.returns.map((r) => ({ ...r, type: CUSTOM_EVENT }))
  return fnStub(owner.name, '.', { ...fn, params, returns }, overloads)
}

/** A value type: its class with fields and operators (so `a + b` type-checks), its methods, and its library table. */
function valueStubs(value: ValueType): string[] {
  const lines = [...comment(value.doc), `---@class ${value.name}`]
  for (const field of value.fields) lines.push(fieldLine(field))
  for (const it of value.operators)
    lines.push(`---@operator ${it.op}${it.operand ? `(${it.operand})` : ''}: ${it.result}`)
  lines.push(`local ${value.name} = {}`, '')
  for (const fn of value.functions) lines.push(...fnStub(value.name, ':', fn))
  const library = value.library
  if (library) {
    const call = library.call
    const params = call.params.map((p) => `${p.name}${p.optional ? '?' : ''}: ${p.type}`)
    const returns = call.returns.map((r) => r.type).join(', ')
    lines.push(...comment(library.doc), `---@class ${library.name}`)
    for (const field of library.fields) lines.push(fieldLine(field))
    lines.push(`---@overload fun(${params.join(', ')}): ${returns}`, `${library.name} = {}`, '')
    for (const fn of library.functions) lines.push(...fnStub(library.name, '.', fn))
  }
  return lines
}

export function luals(spec: ApiSpec): string {
  const lines: string[] = [
    '---@meta',
    '-- The NetherForge Lua API, for lua-language-server (VS Code\'s "Lua" extension, Neovim\'s lua_ls).',
    `-- ${GENERATED_BY}`,
    '--',
    '-- To use it, add this folder to "Lua.workspace.library", and point require at the',
    '-- project\'s modules: "Lua.runtime.path": ["modules/?.lua", "modules/?/init.lua"].',
    `-- The sandbox removes ${spec.removed.join(', ')}.`,
    '',
    ...eventedClasses(spec).map((cls) => aliasLine(spec, cls)),
    '',
    ...nameAliasLines(),
    '',
    ...(spec.aliases ?? []).flatMap((it) => [
      ...comment(it.doc),
      `---@alias ${it.name} ${it.type}`,
      '',
    ]),
  ]

  for (const cls of spec.classes) {
    lines.push(
      ...comment(cls.doc),
      ...(cls.saveable ? comment(SAVEABLE) : []),
      `---@class ${cls.name}${cls.extends ? `: ${cls.extends}` : ''}`,
    )
    for (const field of cls.fields) lines.push(fieldLine(field))
    if (cls.methods) {
      lines.push(`local ${cls.name} = {}`, '')
    } else {
      lines.push(`${cls.name} = {}`, '')
    }
    for (const fn of cls.functions) {
      if (cls.events?.length && (fn.name === 'on' || fn.name === 'once'))
        lines.push(...onStub(spec, cls as Evented, fn))
      else if (cls.name === 'nf' && fn.name === 'wait_for')
        lines.push(...waitForStub(cls, fn, eventedClasses(spec)))
      else lines.push(...fnStub(cls.name, cls.methods ? ':' : '.', fn))
    }
  }

  for (const value of spec.values ?? []) lines.push(...valueStubs(value))

  for (const shape of spec.shapes) {
    const base = shape.extends ? `: ${shape.extends}` : ''
    lines.push(...comment(shape.doc), `---@class ${shape.name}${base}`)
    for (const field of shape.fields) lines.push(fieldLine(field))
    if (shape.functions.length) {
      lines.push(`local ${shape.name} = {}`, '')
      for (const fn of shape.functions) lines.push(...fnStub(shape.name, ':', fn))
    } else lines.push('')
  }

  if (spec.classes.some((it) => it.customEvents)) lines.push(...customEventStub())

  // Globals declared above as their own table (nf, vec3) need no `---@type` line.
  const tables = new Set([
    ...spec.classes.map((it) => it.name),
    ...(spec.values ?? []).flatMap((it) => (it.library ? [it.library.name] : [])),
  ])
  for (const global of spec.globals) {
    if (tables.has(global.name) || global.name === 'require' || global.name === 'print') continue
    lines.push(...comment(global.doc), `---@type ${global.type}`, `${global.name} = nil`, '')
  }

  // A surface's globals (`this`) mean a different class in each kind of script, but LuaLS
  // globals are workspace-wide and it ignores a `.luarc.json` in a subfolder. So here each is
  // the union of its types; a script narrows it with its first line,
  // `local this = this --[[@as Centity]]`. `surfaces/<name>.lua` says exactly what it is per surface.
  const surfaceGlobals = new Map<string, { types: string[]; where: string[] }>()
  for (const surface of spec.surfaces) {
    for (const global of surface.globals) {
      const entry = surfaceGlobals.get(global.name) ?? { types: [], where: [] }
      entry.types.push(global.type)
      entry.where.push(`\`${global.type}\` in a ${surface.name} script`)
      surfaceGlobals.set(global.name, entry)
    }
  }
  for (const [name, { types, where }] of surfaceGlobals) {
    lines.push(
      ...comment(
        `The script's own handle: ${where.join(', ')}. Nothing in a module.\n\n` +
          `Start a script with \`local ${name} = ${name} --[[@as ${types[0]}]]\` so lua-language-server knows which.`,
      ),
      `---@type ${types.join('|')}`,
      `${name} = nil`,
      '',
    )
  }
  return lines.join('\n')
}

/**
 * `packages/api/generated/luals/surfaces/<surface>.lua`: one per surface with globals of its
 * own, declaring them exactly (`this: Centity`), for tools that read one surface. LuaLS merges
 * it with the union in `nf.lua` rather than replacing it, so scripts still narrow `this` with
 * their first line.
 */
export function lualsSurfaces(spec: ApiSpec): Map<string, string> {
  const files = new Map<string, string>()
  for (const surface of spec.surfaces) {
    if (!surface.globals.length) continue
    const lines = [
      '---@meta',
      `-- What a NetherForge ${surface.name} script sees beyond nf.lua, for lua-language-server.`,
      `-- ${GENERATED_BY}`,
      '',
      ...comment(surface.doc),
      '',
    ]
    for (const global of surface.globals) {
      lines.push(...comment(global.doc), `---@type ${global.type}`, `${global.name} = nil`, '')
    }
    files.set(`${surface.name}.lua`, lines.join('\n'))
  }
  return files
}

/**
 * `packages/api/generated/luals/gates.json`: every function a project may not be able to use,
 * as `nf.lua` declares it (`Player:ban`, `nf.server.set_motd`), with the Minecraft version it
 * needs (`since`) and what it needs declared (`requires`). The editor marks each that its
 * project can't use `---@deprecated` in the copy of `nf.lua` it writes for it, so
 * lua-language-server flags a call.
 */
export function lualsGates(spec: ApiSpec): string {
  const gates = spec.classes.flatMap((cls) =>
    cls.functions
      .filter((fn) => fn.since || fn.requires)
      .map((fn) => ({
        function: `${cls.name}${cls.methods ? ':' : '.'}${fn.name}`,
        ...(fn.since ? { since: sinceVersion(fn.since) } : {}),
        ...(fn.requires ? { requires: fn.requires } : {}),
      })),
  )
  return JSON.stringify(gates, null, 2)
}
