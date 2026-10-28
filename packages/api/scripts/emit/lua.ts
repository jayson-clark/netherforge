/**
 * `bindings.lua`: the Lua half of every Kotlin-implemented function. For each
 * one, the method on its class (or the function on `nf`), the check that a
 * method was called with `:`, and the call into Kotlin by primitive name with
 * each argument as the one Lua value it is: Kotlin reads and checks them
 * (`LuaCodec`) and pushes back finished values. A `Vec3` is the exception,
 * crossing as its three numbers both ways (checked inline, `want_vec3` raising the error, and
 * rebuilt by the binding itself), since transforms are set and read every tick.
 *
 * A handle crosses as its key in the runtime's handle table (`self_of` hands it
 * over), and Kotlin finds the handle there.
 *
 * A function the spec marks `impl: "lua"` is hand-written, but its wrapper is generated here:
 * `self` and every argument checked from the declared types, then the body called with them (a
 * tail call). Also here: each root handle class's key accessor (`keys.Task(task)`, from its
 * `HandleSpec`), and the choice sets those checks use.
 *
 * The prelude's `api` module runs it with its core (handle cache, checks, the bodies) and gets
 * back a function that fills a scope's `nf`. The data the prelude reads (events, shapes, saved
 * handles) is `schemaLua`.
 */
import type { ApiSpec, LuaClass } from '../../src/types.ts'
import { GENERATED_BY } from './common.ts'
import {
  chainOf,
  classEvents,
  handwrittenFunctions,
  handwrittenShapes,
  isNamespace,
  type ClassBinding,
  type FnBinding,
  type HandwrittenFn,
} from './bindings.ts'

const indent = (lines: string[], by = '  ') => lines.map((line) => (line ? by + line : line))

/** The local holding the set of metatable names a handle of `cls` may have: it and every class below it. */
const acceptsOf = (cls: string) => `${cls}_is`

/**
 * The `:` check, inline: a handle's key, when it's one of `cls`'s (or of a class below it). Only
 * a mistake calls `self_of`, straight from the function, so its error is at the caller's line.
 * A method is called every tick, so this is the few instructions it can be (`ApiCostTest`).
 */
const selfCheck = (cls: string, key: string | undefined): string[] =>
  key
    ? [
        `local ${key} = ids[self]`,
        `if ${key} == nil or not ${acceptsOf(cls)}[class_of(self)] then`,
        `  ${key} = self_of(self, "${cls}")`,
        'end',
      ]
    : [
        `if ids[self] == nil or not ${acceptsOf(cls)}[class_of(self)] then`,
        `  self_of(self, "${cls}")`,
        'end',
      ]

function body(binding: FnBinding, self: string): string[] {
  const lines: string[] = []
  if (binding.method) lines.push(...selfCheck(binding.cls.name, self))
  const args = [self]
  for (const param of binding.params) {
    if (!param.spread) {
      args.push(param.name)
      continue
    }
    // A Vec3 crosses as its three numbers; the check is inline, and only a mistake calls
    // `want_vec3`, straight from the function, so its error is at the caller's line.
    const name = param.name
    const quoted = JSON.stringify(name)
    if (param.codec.kind === 'optional') {
      const parts = ['x', 'y', 'z'].map((it) => `${name}_${it}`)
      lines.push(
        `local ${parts.join(', ')}`,
        `if ${name} ~= nil then`,
        `  if genuine_mt(${name}) ~= Vec3_mt then`,
        `    want_vec3_opt(${name}, ${quoted})`,
        '  end',
        `  ${parts.join(', ')} = ${name}[1], ${name}[2], ${name}[3]`,
        'end',
      )
      args.push(...parts)
    } else {
      lines.push(`if genuine_mt(${name}) ~= Vec3_mt then`, `  want_vec3(${name}, ${quoted})`, 'end')
      args.push(`${name}[1]`, `${name}[2]`, `${name}[3]`)
    }
  }
  const returns = binding.returns
  if (returns.kind === 'async') {
    // Both forms at once: the callback, or the running task woken by the prelude's waker. The
    // primitive is called straight from here, so a bad argument is located at the script's line.
    // A method's wait belongs to the scope whose code called it (a namespace function's, to
    // the scope whose `nf` it is, like everything else it does).
    const what = `${binding.cls.name}${binding.method ? ':' : '.'}${binding.fn.name}`
    const begun = binding.method ? 'waker, task, token, scope' : 'waker, task, token'
    lines.push(
      `local ${begun} = async.begin(${JSON.stringify(what)}, callback)`,
      `local wait_id = prim[${JSON.stringify(binding.primitive)}](${[...args, ...(binding.method ? ['scope'] : []), 'waker'].join(', ')})`,
      'if task ~= nil then',
      '  local value, err = async.wait(task, token, wait_id)',
      '  return value, err',
      'end',
    )
    return lines
  }
  const call = `prim[${JSON.stringify(binding.primitive)}](${args.join(', ')})`
  if (returns.kind === 'none') lines.push(call)
  // A nil x is a single nil, for an optional return. The vector is made here rather than by a
  // call, which would cost every transform read a few more instructions.
  else if (returns.kind === 'one' && returns.spread)
    lines.push(
      `local ${SPREAD_RETURN.join(', ')} = ${call}`,
      `if ${SPREAD_RETURN[0]} == nil then`,
      '  return nil',
      'end',
      `return with_mt({ ${SPREAD_RETURN.join(', ')} }, Vec3_mt)`,
    )
  else lines.push(`return ${call}`)
  return lines
}

function fnLines(owner: string, binding: FnBinding): string[] {
  // A method's handle crosses as its key in the handle table; a namespace function passes its scope.
  const self = binding.method ? 'self_key' : 'scope'
  const params = [
    ...(binding.method ? ['self'] : []),
    ...binding.params.map((p) => p.name),
    ...(binding.returns.kind === 'async' ? ['callback'] : []),
  ]
  // A parameter named after one of the body's own locals (or the chunk's) would be shadowed by it.
  const locals = [
    self,
    ...(binding.returns.kind === 'async' ? ASYNC_LOCALS : []),
    ...(binding.returns.kind === 'one' && binding.returns.spread ? SPREAD_RETURN : []),
  ]
  for (const name of params)
    if (locals.includes(name) || CORE_NAMES.has(name))
      throw new Error(`${owner}.${binding.fn.name}: a parameter can't be called ${name}`)
  return [
    `function ${owner}.${binding.fn.name}(${params.join(', ')})`,
    ...indent(body(binding, self)),
    'end',
  ]
}

/**
 * A handle from its key values, for one the prelude makes itself (a `Task`, a world by name
 * in saved data): the runtime finds or makes its entry and says which class it is.
 */
function constructor(binding: ClassBinding): string[] {
  const fields = binding.handle!.fields.map((field) => field.name)
  return [
    `function new.${binding.cls.name}(${fields.join(', ')})`,
    `  return prim["handles.new"]("${binding.cls.name}", ${fields.join(', ')})`,
    'end',
  ]
}

/** Whether a class or one up its chain takes custom events. */
const takesCustom = (spec: ApiSpec, cls: LuaClass) =>
  chainOf(spec, cls).some((it) => it.customEvents)

/**
 * The event registry the prelude's event core checks `:on` against: each class's events, its
 * own and those it inherits (so a `Mob` has a `Living`'s `death`), the class that declares
 * each, whether each is cancellable or local, the option keys it takes, and its writable
 * fields. A value a handler assigns is checked in Kotlin, by the payload field's codec.
 */
function eventsLua(spec: ApiSpec): string[] {
  const shapes = new Map(spec.shapes.map((it) => [it.name, it]))
  const lines = [
    '',
    `-- events ${'-'.repeat(68)}`,
    '',
    '-- What `:on` accepts per class (`nf` for `nf.on`), and the classes that take custom events.',
    'local events = {',
  ]
  const evented = spec.classes.filter((cls) => classEvents(spec, cls).length)
  for (const cls of evented) {
    lines.push(`  [${JSON.stringify(cls.name)}] = {`)
    for (const { event, owner } of classEvents(spec, cls)) {
      const shape = event.payload ? shapes.get(event.payload) : undefined
      const writable = (event.writable ?? []).map((name) => {
        const field = shape?.fields.find((it) => it.name === name)
        if (!field)
          throw new Error(`${cls.name} ${event.name}: no payload field ${name} to make writable`)
        return `${name} = true`
      })
      const options = (event.options ?? []).map((name) => `${name} = true`)
      const table = (items: string[]) => (items.length ? `{ ${items.join(', ')} }` : '{}')
      const parts = [
        `owner = ${JSON.stringify(owner.name)}`,
        `cancellable = ${!!event.cancellable}`,
        `options = ${table(options)}`,
        `writable = ${table(writable)}`,
        ...(event.local ? ['local_only = true'] : []),
      ]
      lines.push(`    [${JSON.stringify(event.name)}] = { ${parts.join(', ')} },`)
    }
    lines.push('  },')
  }
  lines.push('}', 'local custom_events = {')
  for (const cls of evented)
    if (takesCustom(spec, cls)) lines.push(`  [${JSON.stringify(cls.name)}] = true,`)
  lines.push('}')
  return lines
}

/** Where a hand-written function's wrapper goes, and how it reaches its body. */
interface Target {
  /** The table the function is defined on: a class's local, `nf.math` inside `fill_nf`, `vec3_functions`. */
  table: string
  /** The body: where the hand-written function lives. */
  body: string
  /** What `self` is checked as: a handle class, or a value type; none for a namespace or library function. */
  self?: { handle: string } | { value: string }
  /** A namespace function passes the calling scope first, as a Kotlin one does. */
  scope: boolean
}

/** The locals a function returning a `Vec3` makes (`body`), so no parameter of one may take one. */
const SPREAD_RETURN = ['result_x', 'result_y', 'result_z']

/** The locals an async function's binding makes (`body`), so no parameter of one may take one. */
const ASYNC_LOCALS = ['waker', 'task', 'token', 'scope', 'wait_id', 'value', 'err']

/** Names the chunk's own locals have in a wrapper, so no parameter may take one. */
const CORE_NAMES = new Set([
  'ns',
  'namespaces',
  'ids',
  'accepts',
  'type_of',
  'class_of',
  'number_kind',
  'genuine_mt',
  'with_mt',
  'Vec3_mt',
  'Location_mt',
  'prim',
  'class',
  'self_of',
  'want',
  'want_opt',
  'want_integer',
  'want_integer_opt',
  'want_handle',
  'want_vec3',
  'want_vec3_opt',
  'vector_arg',
  'check_shape',
  'value_self',
  'choice',
  'async',
  'hand',
  'value_body',
  'values',
  'new',
  'keys',
  'core',
])

/** A name for a choice set that no other function's can have. */
const choiceName = (owner: string, fn: string, param: string) =>
  `${owner}_${fn}_${param}`.replace(/[^\w]+/g, '_')

/**
 * The wrapper of a hand-written function: it checks `self` and every argument from the spec's
 * types (the same errors the generated Kotlin-backed functions give, at the caller's line), then
 * tail-calls the hand-written body with them, so an error the body raises at level 2 is at the
 * caller's line too.
 */
function wrapperLines(
  owner: string,
  { fn, params }: HandwrittenFn,
  target: Target,
  sets: Map<string, string[]>,
): string[] {
  const names = params.map((it) => (it.vararg ? '...' : it.name))
  const reserved = target.scope ? 'scope' : 'self'
  if (names.includes(reserved))
    throw new Error(`${owner}.${fn.name}: a parameter can't be called ${reserved}`)
  for (const name of names)
    if (CORE_NAMES.has(name))
      throw new Error(`${owner}.${fn.name}: a parameter can't be called ${name}`)
  const lines: string[] = []
  if (target.self && 'handle' in target.self)
    lines.push(...selfCheck(target.self.handle, undefined))
  if (target.self && 'value' in target.self) {
    const value = target.self.value
    lines.push(
      `if genuine_mt(self) ~= ${value}_mt then`,
      `  value_self(self, ${JSON.stringify(value)})`,
      'end',
    )
  }
  // Each check is inline: what the value must be, and the checker (which raises the error, at
  // the caller's line, since it's called straight from here) only when it isn't.
  for (const param of params) {
    const name = param.name
    const quoted = JSON.stringify(name)
    const opt = param.optional ? '_opt' : ''
    const check = param.check
    // An optional parameter left out is fine; one given is checked as a required one is.
    const guarded = (optional: boolean, wrong: string, call: string) => [
      `if ${optional ? `${name} ~= nil and ` : ''}${wrong} then`,
      `  ${call}`,
      'end',
    ]
    switch (check.kind) {
      case 'body':
        break
      case 'type':
        lines.push(
          ...guarded(
            param.optional,
            `type_of(${name}) ~= ${JSON.stringify(check.lua)}`,
            `want${opt}(${name}, ${JSON.stringify(check.lua)}, ${quoted})`,
          ),
        )
        break
      case 'integer':
        lines.push(
          ...guarded(
            param.optional,
            `number_kind(${name}) ~= "integer"`,
            `${name} = want_integer${opt}(${name}, ${quoted})`,
          ),
        )
        break
      case 'handle':
        lines.push(
          ...guarded(
            param.optional,
            `(ids[${name}] == nil or not ${acceptsOf(check.cls)}[class_of(${name})])`,
            `want_handle(${name}, ${JSON.stringify(check.cls)}, ${quoted})`,
          ),
        )
        break
      case 'vec3':
        lines.push(
          ...guarded(
            param.optional,
            `genuine_mt(${name}) ~= Vec3_mt`,
            `vector_arg(${name}, ${quoted}, 2)`,
          ),
        )
        break
      case 'shape': {
        const call = `check_shape(${name}, ${quoted}, ${JSON.stringify(check.name)})`
        if (param.optional) lines.push(`if ${name} ~= nil then`, `  ${call}`, 'end')
        else lines.push(call)
        break
      }
      case 'choice': {
        const set = choiceName(owner, fn.name, name)
        sets.set(set, check.choices)
        lines.push(
          ...guarded(
            param.optional,
            `not ${set}.has[${name}]`,
            `choice.want${opt}(${name}, ${set}, ${quoted})`,
          ),
        )
        break
      }
    }
  }
  const forwarded = [...(target.self ? ['self'] : []), ...(target.scope ? ['scope'] : []), ...names]
  const call = `${target.body}.${fn.name}(${forwarded.join(', ')})`
  lines.push(`return ${call}`)
  return [
    `function ${target.table}.${fn.name}(${[...(target.self ? ['self'] : []), ...names].join(', ')})`,
    ...indent(lines),
    'end',
  ]
}

/** `hand.Mob` for a name that is an identifier, `hand["nf.math"]` for one that isn't. */
const handOf = (owner: string) =>
  /^\w+$/.test(owner) ? `hand.${owner}` : `hand[${JSON.stringify(owner)}]`

/** What a handle's key is made of, for the accessor's annotation: `string uuid`, `integer id`. */
const keyReturns = (binding: ClassBinding) =>
  binding.handle!.fields.map((field) => `---@return ${field.type} ${field.name}`)

/**
 * `keys.<Class>(handle)`: a handle's key values as the runtime holds them (its `handle` in the
 * spec), for the prelude's own handles (a `Task`'s id), without it keeping a table of its own.
 */
function keyAccessor(binding: ClassBinding): string[] {
  return [
    '---@param handle table',
    ...keyReturns(binding),
    `function keys.${binding.cls.name}(handle)`,
    '  return prim["handles.key"](handle)',
    'end',
  ]
}

export function bindingsLua(classes: ClassBinding[], spec: ApiSpec): string {
  const hand = handwrittenFunctions(spec)
  const sets = new Map<string, string[]>()
  const lines: string[] = [
    `-- ${GENERATED_BY}`,
    '--',
    '-- The Lua half of the API: the functions scripts call. A Kotlin-implemented one',
    '-- checks `self` (the `:` check) and calls into Kotlin by primitive name, which reads and',
    '-- checks the arguments. A hand-written one (`impl: "lua"` in the spec) checks `self` and',
    "-- every argument from its declared type, then calls its body (`hand`, the prelude's",
    '-- handwritten module), so the body holds only logic.',
    '',
    'local core = ...',
    'local prim, class, self_of = core.prim, core.class, core.self_of',
    'local want_vec3, want_vec3_opt = core.want_vec3, core.want_vec3_opt',
    'local want, want_opt, want_integer, want_integer_opt =',
    '  core.want, core.want_opt, core.want_integer, core.want_integer_opt',
    'local want_handle, vector_arg, check_shape = core.want_handle, core.vector_arg, core.check_shape',
    'local value_self, choice = core.value_self, core.choice',
    '-- `async.begin` and `async.wait`: the two forms of an async function.',
    'local async = core.async',
    "-- The hand-written bodies, and the tables the value types' methods go in.",
    'local hand, value_body, values = core.hand, core.value_body, core.values',
    '',
    '-- Handle constructors by class name, for each class at the top of its chain: the runtime',
    "-- finds or makes the handle's entry, so equal keys give the same table. And each such",
    "-- class's key values, as the runtime holds them (`keys.Task(task)` is its id).",
    'local new, keys = core.new, core.keys',
    '',
    "-- What the inline checks read: each handle's key (`ids`), each class's set of the metatable",
    '-- names its handles may have (`<Class>_is`: it and every class below it), the genuine',
    "-- metatables of the value types, and the library's own functions, so a check is a few",
    '-- instructions and only a mistake calls a checker.',
    'local ids, accepts = core.ids, core.accepts',
    'local type_of, class_of, number_kind = core.type_of, core.class_of, core.number_kind',
    'local genuine_mt, with_mt = core.genuine_mt, core.with_mt',
    'local Vec3_mt, Location_mt = core.Vec3_mt, core.Location_mt',
    ...classes
      .filter((it) => it.handle)
      .map((it) => `local ${acceptsOf(it.cls.name)} = accepts.${it.cls.name}`),
  ]
  const namespaces: ClassBinding[] = []
  const body: string[] = []
  for (const binding of classes) {
    if (!binding.handle) {
      if (!isNamespace(binding.cls.name))
        throw new Error(`no binding for namespace ${binding.cls.name}`)
      namespaces.push(binding)
      continue
    }
    const name = binding.cls.name
    // Each class's table is a local of its own block: the chunk would otherwise near Lua's
    // limit of 200 locals (`PreludeModulesTest`) as classes are added.
    const section: string[] = [
      binding.handle.parent
        ? `local ${name} = class("${name}", "${binding.handle.parent}")`
        : `local ${name} = class("${name}")`,
      ...(binding.handle.parent ? [] : ['', ...constructor(binding), '', ...keyAccessor(binding)]),
    ]
    for (const fn of binding.functions) section.push('', ...fnLines(name, fn))
    for (const fn of hand.get(name) ?? [])
      section.push(
        '',
        ...wrapperLines(
          name,
          fn,
          { table: name, body: handOf(name), self: { handle: name }, scope: false },
          sets,
        ),
      )
    body.push(
      '',
      `-- ${name} ${'-'.repeat(Math.max(4, 74 - name.length))}`,
      '',
      'do',
      ...section.map((line) => (line === '' ? line : `  ${line}`)),
      'end',
    )
  }
  // The value types' methods: `values.Vec3` is the table `__index` reads.
  for (const value of spec.values ?? []) {
    const fns = hand.get(value.name) ?? []
    if (!fns.length) continue
    body.push('', `-- ${value.name} ${'-'.repeat(Math.max(4, 74 - value.name.length))}`)
    for (const fn of fns)
      body.push(
        '',
        ...wrapperLines(
          value.name,
          fn,
          {
            table: `values.${value.name}`,
            body: `value_body.${value.name}`,
            self: { value: value.name },
            scope: false,
          },
          sets,
        ),
      )
  }
  // The functions on a value's library (`vec3.from_yaw_pitch`), which each scope's table copies.
  const libraries = (spec.values ?? []).flatMap((it) => (it.library ? [it.library.name] : []))
  for (const library of libraries) {
    body.push(
      '',
      `-- ${library} ${'-'.repeat(Math.max(4, 74 - library.length))}`,
      '',
      `local ${library}_functions = {}`,
    )
    for (const fn of hand.get(library) ?? [])
      body.push(
        '',
        ...wrapperLines(
          library,
          fn,
          { table: `${library}_functions`, body: `value_body.${library}`, scope: false },
          sets,
        ),
      )
  }
  body.push(
    '',
    `-- nf ${'-'.repeat(72)}`,
    '',
    "-- Fills a scope's `nf` with its own functions. Every function passes its implementation",
    '-- the calling scope.',
    'local function fill_nf(scope, nf)',
  )
  // A namespace's functions, on the table it's given (`ns`): its own or `nf`.
  const functionLines = (binding: ClassBinding, table: string): string[] => {
    const lines: string[] = []
    for (const fn of binding.functions) {
      if (lines.length) lines.push('')
      lines.push(...fnLines(table, fn))
    }
    for (const fn of hand.get(binding.cls.name) ?? []) {
      if (lines.length) lines.push('')
      lines.push(
        ...wrapperLines(
          binding.cls.name,
          fn,
          { table, body: handOf(binding.cls.name), scope: true },
          sets,
        ),
      )
    }
    return lines
  }
  const nf = namespaces.find((it) => it.cls.name === 'nf')
  if (!nf) throw new Error('no nf namespace')
  body.push(...indent(functionLines(nf, 'nf')), 'end')
  // Each namespace under nf by its path, filled the first time a script reaches it: a scope is
  // made for every centity spawned, and most never touch most namespaces.
  body.push(
    '',
    "-- Each namespace under `nf` by its path (`nf.server`): fills the scope's table for it with",
    '-- its functions. The prelude calls it the first time a script reaches the namespace.',
    'local namespaces = {}',
  )
  for (const binding of namespaces) {
    if (binding === nf) continue
    body.push(
      '',
      `namespaces[${JSON.stringify(binding.cls.name)}] = function(scope, ns)`,
      ...indent(functionLines(binding, 'ns')),
      'end',
    )
  }
  body.push(
    '',
    'return {',
    '  fill_nf = fill_nf,',
    '  namespaces = namespaces,',
    ...libraries.map((it) => `  ${it} = ${it}_functions,`),
    '}',
  )
  // Each choice a hand-written function takes, built once.
  if (sets.size) {
    lines.push('', '-- The choices a hand-written function takes, as sets (one lookup per check).')
    for (const [name, choices] of sets)
      lines.push(
        `local ${name} = choice.set({ ${choices.map((it) => JSON.stringify(it)).join(', ')} })`,
      )
  }
  lines.push(...body)
  return lines.join('\n')
}

/**
 * `schema.lua`: what the prelude reads about the API as data, with nothing to call: the event
 * registry its event core checks `:on` against, the tables hand-written functions take, and
 * the handle classes a saved table may keep.
 */
export function schemaLua(spec: ApiSpec): string {
  const checked = handwrittenShapes(spec)
  const lines: string[] = [
    `-- ${GENERATED_BY}`,
    '--',
    '-- What the prelude reads about the API as data: the event registry (`events`,',
    '-- `custom_events`), the tables hand-written functions take (`shapes`) and the handle',
    '-- classes a saved table may keep (`saved_handles`).',
  ]
  lines.push(...eventsLua(spec))
  lines.push(
    '',
    `-- Event functions ${'-'.repeat(59)}`,
    '',
    '-- The classes with events of their own, and the hand-written functions each has: `on`,',
    '-- `once`, and `emit` for one that takes custom events.',
    'local event_functions = {',
    ...spec.classes
      .filter((it) => it.methods && it.events?.length)
      .map((it) => `  ${it.name} = { "on", "once"${it.customEvents ? ', "emit"' : ''} },`),
    '}',
    '',
    `-- Checked tables ${'-'.repeat(60)}`,
    '',
    "-- The tables hand-written functions take (`nf.commands.register`'s definition),",
    "-- for the prelude's strict checks: each field's kind (what `type()` says, or",
    '-- "integer", "Vec3" or "any"), and the fields that must be there.',
    'local shapes = {',
  )
  for (const shape of checked) {
    const fields = [...shape.fields].sort((a, b) => a.name.localeCompare(b.name))
    lines.push(
      `  ${shape.name} = {`,
      '    fields = {',
      ...fields.map((field) => `      ${field.name} = "${field.kind}",`),
      '    },',
      shape.required.length
        ? `    required = { ${shape.required.map((it) => `"${it}"`).join(', ')} },`
        : '    required = {},',
      '  },',
    )
  }
  lines.push(
    '}',
    '',
    `-- Saved handles ${'-'.repeat(61)}`,
    '',
    '-- The handle classes a saved table (and `nf.json`) may keep (`saveable` in the spec), each',
    '-- at the top of its chain, with the tag it is saved under: `{"$entity":[id]}`.',
    'local saved_handles = {',
    ...spec.classes
      .filter((it) => it.saveable)
      .map((it) => `  ${it.name} = ${JSON.stringify(it.name.toLowerCase())},`),
    '}',
    '',
    `-- Test-only namespaces ${'-'.repeat(54)}`,
    '',
    '-- The namespaces of `nf` that only a test run has (`testOnly` in the spec): the prelude takes them out of',
    '-- a scope unless the runtime is running a test.',
    'local test_only = {',
    ...spec.classes.filter((it) => it.testOnly).map((it) => `  ${JSON.stringify(it.name)},`),
    '}',
    '',
    'return {',
    '  events = events,',
    '  custom_events = custom_events,',
    '  event_functions = event_functions,',
    '  shapes = shapes,',
    '  saved_handles = saved_handles,',
    '  test_only = test_only,',
    '}',
  )
  return lines.join('\n')
}
