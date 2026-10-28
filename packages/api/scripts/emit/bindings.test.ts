/**
 * The binding model's decisions: which functions get a binding, how each type crosses (its
 * codec), which shapes cross and which way, handle chains, what hand-written functions check,
 * and what the generator refuses. Tested on the model `bindings()` works out, mostly over tiny
 * made-up specs, not on the text the emitters write: the committed generated files are that
 * text (`pnpm lint` fails when they're stale) and the runtime's ConformanceTest holds Kotlin to
 * it. The few emitter checks here are about behaviour only the output shows (an order, a
 * refusal), matched loosely.
 */
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { asyncFunction } from '../../src/async.ts'
import { api } from '../../src/spec/index.ts'
import type { ApiSpec, Fn, LuaClass } from '../../src/types.ts'
import {
  bindings,
  classEvents,
  classFunctions,
  handwrittenFunctions,
  handwrittenShapes,
  pins,
  reachable,
  type Codec,
  type ShapeBinding,
} from './bindings.ts'
import { useFeatures } from './common.ts'
import { referencePages } from './docs.ts'
import { gatesKotlin, primitivesKotlin, shapesKotlin } from './kotlin.ts'
import { bindingsLua } from './lua.ts'
import { luals } from './luals.ts'

const handle: LuaClass = {
  name: 'Thing',
  doc: 'A thing.',
  methods: true,
  handle: { key: [{ name: 'id', type: 'string' }] },
  fields: [],
  functions: [],
}

const shape = (
  name: string,
  fields: LuaClass['fields'],
  extra: Partial<LuaClass> = {},
): LuaClass => ({ name, doc: `A ${name}.`, methods: false, functions: [], fields, ...extra })

const vec3 = {
  name: 'Vec3',
  doc: 'v',
  fields: [{ name: 'x', type: 'number', doc: 'x' }],
  functions: [],
  operators: [],
}

const fn = (more: Partial<Fn> = {}): Fn => ({
  name: 'f',
  doc: 'd',
  params: [],
  returns: [],
  ...more,
})

function spec(f: Fn, cls: Partial<LuaClass> = {}, more: Partial<ApiSpec> = {}): ApiSpec {
  return {
    globals: [],
    classes: [{ ...handle, ...cls, functions: [f] }],
    surfaces: [],
    shapes: [],
    removed: [],
    values: [vec3],
    ...more,
  }
}

/** The one function [it] binds. */
const only = (it: ApiSpec) => bindings(it).classes[0]!.functions[0]!

/** How a parameter of [type] crosses, in a spec with [more]. */
const codecOf = (type: string, more: Partial<ApiSpec> = {}, optional = false): Codec =>
  only(spec(fn({ params: [{ name: 'x', type, doc: '', optional }] }), {}, more)).params[0]!.codec

const thing = { kind: 'handle', cls: expect.objectContaining({ name: 'Thing' }) }

describe('which functions get a binding', () => {
  it('binds every function the runtime implements, and none written in Lua', () => {
    const { classes } = bindings(api)
    const kotlin = api.classes.flatMap((cls) => cls.functions.filter((it) => it.impl !== 'lua'))
    expect(classes.flatMap((it) => it.functions.map((f) => f.fn))).toEqual(kotlin)
  })

  it('keys each by its class and name; a handle method gets the handle, a namespace the caller', () => {
    const method = only(spec(fn({ name: 'use' })))
    expect(method).toMatchObject({ primitive: 'Thing.use', method: true })
    const namespaced: ApiSpec = {
      ...spec(fn()),
      classes: [
        {
          name: 'nf.server',
          doc: 'd',
          methods: false,
          fields: [],
          functions: [fn({ name: 'tick' })],
        },
      ],
    }
    expect(only(namespaced)).toMatchObject({ primitive: 'nf.server.tick', method: false })
  })
})

describe('how a type crosses', () => {
  it('maps the primitives one to one, and any and table to a Lua value', () => {
    for (const kind of ['string', 'number', 'integer', 'boolean', 'function'] as const)
      expect(codecOf(kind)).toEqual({ kind })
    expect(codecOf('fun(x: number)')).toEqual({ kind: 'function' })
    expect(codecOf('any')).toEqual({ kind: 'any', table: false })
    expect(codecOf('table')).toEqual({ kind: 'any', table: true })
  })

  it('makes nil allowed an optional, however it is written; any is any value already', () => {
    const optional = { kind: 'optional', inner: { kind: 'string' } }
    expect(codecOf('string?')).toEqual(optional)
    expect(codecOf('string|nil')).toEqual(optional)
    expect(codecOf('string', {}, true)).toEqual(optional)
    expect(codecOf('any', {}, true)).toEqual({ kind: 'any', table: false })
  })

  it('reads string literals as a choice, lists and maps by their parts', () => {
    expect(codecOf('"loud"|"quiet"')).toEqual({ kind: 'choice', choices: ['loud', 'quiet'] })
    expect(codecOf('number[]')).toEqual({ kind: 'list', item: { kind: 'number' } })
    expect(codecOf('table<integer, Thing>')).toEqual({
      kind: 'map',
      key: { kind: 'integer' },
      value: thing,
    })
    expect(codecOf('table<"a"|"b", string>')).toMatchObject({ key: { kind: 'choice' } })
  })

  it('names handle classes, runtime value types, aliases and shapes for what they are', () => {
    expect(codecOf('Thing')).toEqual(thing)
    expect(codecOf('Vec3')).toEqual({ kind: 'runtime', name: 'Vec3' })
    const aliased: Partial<ApiSpec> = {
      aliases: [{ name: 'Text', type: 'string', doc: 'MiniMessage.' }],
    }
    expect(codecOf('Text', aliased)).toEqual({ kind: 'runtime', name: 'Text' })
    const shaped = { shapes: [shape('Opts', [{ name: 'n', type: 'integer', doc: '' }])] }
    expect(codecOf('Opts', shaped)).toEqual({ kind: 'shape', name: 'Opts' })
  })

  it("reads a class's event names as the strings they are", () => {
    const evented: Partial<LuaClass> = { events: [{ name: 'poke', doc: 'Poked.' }] }
    const it = spec(fn({ params: [{ name: 'x', type: 'Thing.Event', doc: '' }] }), evented)
    expect(only(it).params[0]!.codec).toEqual({ kind: 'string' })
  })

  it('makes any other union one value tried member by member, the literals one member where they stood', () => {
    const union = codecOf('Vec3|"here"|Thing')
    expect(union).toEqual({
      kind: 'union',
      name: 'Vec3OrChoiceOrThing',
      members: [{ kind: 'runtime', name: 'Vec3' }, { kind: 'choice', choices: ['here'] }, thing],
      handlesOnly: false,
    })
    expect(
      bindings(spec(fn({ params: [{ name: 'x', type: 'Vec3|Thing', doc: '' }] }))).unions.has(
        'Vec3OrThing',
      ),
    ).toBe(true)
  })

  it('makes a union of handle classes only a handle of one of them', () => {
    const two: ApiSpec = {
      ...spec(fn({ params: [{ name: 'x', type: 'Thing|Other', doc: '' }] })),
    }
    two.classes.push({ ...handle, name: 'Other', functions: [] })
    expect(only(two).params[0]!.codec).toMatchObject({ kind: 'union', handlesOnly: true })
  })

  it('refuses what it has no binding for, naming where', () => {
    expect(() => codecOf('Nowhere')).toThrow(/Thing\.f\(x\): no binding for Nowhere/)
    expect(() => codecOf('table<boolean, string>')).toThrow(/keys must be strings or whole numbers/)
    // Members a value can't be told apart by: a string and a string literal, two lists.
    expect(() => codecOf('string|"a"')).toThrow(/told apart/)
    expect(() => codecOf('string[]|number[]|string[]')).toThrow(/told apart/)
  })

  it('sends a Vec3 as its three numbers both ways, and only at the top of a parameter or return', () => {
    const it = only(
      spec(
        fn({
          params: [
            { name: 'at', type: 'Vec3', doc: '' },
            { name: 'maybe', type: 'Vec3?', doc: '' },
            { name: 'many', type: 'Vec3[]', doc: '' },
          ],
          returns: [{ type: 'Vec3' }],
        }),
      ),
    )
    expect(it.params.map((p) => p.spread)).toEqual([true, true, false])
    expect(it.returns).toMatchObject({ kind: 'one', spread: true })
    // The real spec's transforms are the hot path it's for.
    const node = bindings(api).classes.find((c) => c.cls.name === 'Node')!
    const translation = node.functions.find((f) => f.fn.name === 'translation')!
    expect(translation.returns).toMatchObject({ spread: true })
  })
})

describe('what a function returns', () => {
  const returning = (...types: string[]) =>
    only(spec(fn({ returns: types.map((type) => ({ type })) }))).returns

  it('is nothing, one value, or several that are all there or a single nil', () => {
    expect(returning()).toEqual({ kind: 'none' })
    expect(returning('string')).toEqual({ kind: 'one', codec: { kind: 'string' }, spread: false })
    expect(returning('string', 'integer')).toEqual({
      kind: 'tuple',
      codecs: [{ kind: 'string' }, { kind: 'integer' }],
      optional: false,
    })
    // One optional makes the whole tuple optional; the values themselves aren't.
    expect(returning('string?', 'integer')).toEqual({
      kind: 'tuple',
      codecs: [{ kind: 'string' }, { kind: 'integer' }],
      optional: true,
    })
    expect(() => returning('string', 'string', 'string', 'string', 'string')).toThrow(
      /more than four returns/,
    )
  })

  it('is given later for an async function: its value, with the callback the binding’s own', () => {
    const fetch = asyncFunction({
      name: 'fetch',
      doc: 'Fetches.',
      params: [{ name: 'at', type: 'Vec3', doc: '' }],
      value: { name: 'thing', type: 'Thing', doc: 'the thing' },
    })
    expect(fetch.params.map((it) => it.name)).toEqual(['at', 'callback'])
    expect(fetch.params[1]!.type).toBe('fun(thing: Thing?, err: string?)')
    expect(fetch.returns.map((it) => it.type)).toEqual(['Thing?', 'string?'])
    const bound = only(spec(fetch))
    expect(bound.params.map((it) => it.name)).toEqual(['at'])
    expect(bound.returns).toEqual({ kind: 'async', codec: thing })
    // Only in the form asyncFunction gives.
    expect(() => bindings(spec({ ...fetch, returns: [{ type: 'Thing?' }] }))).toThrow(
      /declared with asyncFunction/,
    )
  })
})

describe('the shapes that cross', () => {
  it('are every shape a binding reads or pushes, however deep, in the spec’s order, each way it goes', () => {
    const it = spec(
      fn({
        params: [{ name: 'filters', type: 'table<string, Filter>', doc: '' }],
        returns: [{ type: 'Hit[]' }],
      }),
      {},
      {
        shapes: [
          shape('Hit', [
            { name: 'by', type: 'Thing', doc: '' },
            { name: 'at', type: 'Spot?', doc: '' },
          ]),
          shape('Unused', [{ name: 'x', type: 'string', doc: '' }]),
          shape('Filter', [
            { name: 'kind', type: 'string', doc: '' },
            { name: 'spot', type: 'Spot', doc: '' },
            { name: 'context', type: 'any', doc: '' },
          ]),
          shape('Spot', [{ name: 'x', type: 'number', doc: '' }]),
        ],
      },
    )
    const { shapes } = bindings(it)
    const ways = Object.fromEntries(shapes.map((s) => [s.shape.name, [s.read, s.pushed]]))
    expect(ways).toEqual({ Hit: [false, true], Filter: [true, false], Spot: [true, true] })
    // A field of any type may be left out.
    const filter = shapes.find((s) => s.shape.name === 'Filter')!
    expect(filter.fields.find((f) => f.name === 'context')!.codec).toEqual({
      kind: 'optional',
      inner: { kind: 'any', table: false },
    })
  })

  it('include each event payload, pushed, and its writable fields read back', () => {
    const evented = spec(
      fn(),
      {
        events: [
          { name: 'poke', doc: 'Poked.', payload: 'PokeEvent', cancellable: true },
          { name: 'rename', doc: 'Renamed.', payload: 'RenameEvent', writable: ['drops'] },
        ],
      },
      {
        shapes: [
          shape('PokeEvent', [{ name: 'by', type: 'Thing', doc: '' }], { extends: 'Event' }),
          shape('RenameEvent', [{ name: 'drops', type: 'Drop[]', doc: '' }], { extends: 'Event' }),
          shape('Drop', [{ name: 'count', type: 'integer', doc: '' }]),
        ],
      },
    )
    const ways = Object.fromEntries(
      bindings(evented).shapes.map((s) => [s.shape.name, [s.read, s.pushed]]),
    )
    expect(ways).toEqual({
      PokeEvent: [false, true],
      RenameEvent: [false, true],
      Drop: [true, true],
    })
  })

  it('refuse a payload that is not a shape, and a writable field that is missing or a function', () => {
    const event = (writable: string[], fields: LuaClass['fields'], payload = 'E') =>
      spec(
        fn(),
        { events: [{ name: 'e', doc: 'E.', payload, writable }] },
        { shapes: [shape('E', fields, { extends: 'Event' })] },
      )
    expect(() => bindings(event([], [], 'Nope'))).toThrow(/no payload shape Nope/)
    expect(() => bindings(event(['gone'], []))).toThrow(/no payload field gone/)
    expect(() => bindings(event(['f'], [{ name: 'f', type: 'fun()', doc: '' }]))).toThrow(
      /a writable field can't be a function/,
    )
  })

  it('know which hold themselves, and which hold a Lua value read while the call lasts', () => {
    const it = spec(
      fn({ params: [{ name: 'tree', type: 'Tree', doc: '' }] }),
      {},
      {
        shapes: [
          shape('Tree', [
            { name: 'children', type: 'table<string, Tree>?', doc: '' },
            { name: 'leaf', type: 'Leaf?', doc: '' },
          ]),
          shape('Leaf', [{ name: 'value', type: 'any', doc: '' }]),
          shape('Plain', [{ name: 'n', type: 'number', doc: '' }]),
        ],
      },
    )
    const plain = shape('Plain', [{ name: 'n', type: 'number', doc: '' }])
    const bound: ShapeBinding[] = [
      ...bindings(it).shapes,
      {
        shape: plain,
        fields: [{ name: 'n', codec: { kind: 'number' }, doc: '' }],
        read: true,
        pushed: false,
      },
    ]
    const reach = reachable(bound)
    expect([...reach.get('Tree')!].sort()).toEqual(['Leaf', 'Tree'])
    expect([...reach.get('Leaf')!]).toEqual([])
    const byName = new Map(bound.map((s) => [s.shape.name, s]))
    // A Leaf holds an `any`, so a Tree does too; a recursive shape doesn't loop.
    expect(pins({ kind: 'shape', name: 'Tree' }, byName)).toBe(true)
    expect(pins({ kind: 'shape', name: 'Plain' }, byName)).toBe(false)
    // The Kotlin emitter can't name a codec inside its own construction, so it names it lazily.
    expect(shapesKotlin(it, bindings(it))).toMatch(/LuaLazy\([^)]*\)\s*\{\s*Tree\.Codec\s*\}/)
  })
})

describe('handle classes that extend another', () => {
  const { classes } = bindings(api)
  const byName = (name: string) => classes.find((it) => it.cls.name === name)!
  const cls = (name: string) => api.classes.find((it) => it.name === name)!

  it('form a chain of any depth, keyed as its top is', () => {
    const mob = byName('Mob').handle!
    expect(mob.chain).toEqual(['Mob', 'Living', 'Entity'])
    expect(mob.root).toBe('Entity')
    expect(mob.fields).toEqual([{ name: 'id', type: 'string' }])
    expect(byName('Entity').handle!.descendants).toEqual([
      'Entity',
      'Living',
      'DroppedItem',
      'Mob',
      'Mannequin',
      'Player',
      'Bot',
    ])
    expect(byName('Living').handle!.descendants).toEqual([
      'Living',
      'Mob',
      'Mannequin',
      'Player',
      'Bot',
    ])
  })

  it('refuse a chain that goes round, a key below the top, extending what is no handle class, or no key', () => {
    const chain = (...more: LuaClass[]): ApiSpec => ({ ...spec(fn()), classes: [handle, ...more] })
    const below = (name: string, extend: string, extra: Partial<LuaClass> = {}): LuaClass => ({
      ...handle,
      name,
      handle: undefined,
      extends: extend,
      ...extra,
    })
    expect(() => bindings(chain(below('A', 'B'), below('B', 'A')))).toThrow(/circle/)
    expect(() => bindings(chain(below('A', 'Thing', { handle: handle.handle })))).toThrow(
      /no `handle` of its own/,
    )
    expect(() => bindings(chain(below('A', 'nf')))).toThrow(/both must be handle classes/)
    expect(() => bindings(spec(fn(), { handle: undefined }))).toThrow(/without `handle`/)
  })

  it("list a class's functions with those up its chain, its own replacing theirs", () => {
    const names = classFunctions(api, cls('Mob')).map((it) => it.name)
    expect(names).toEqual(expect.arrayContaining(['set_target', 'health', 'teleport']))
    expect(names.filter((it) => it === 'on')).toHaveLength(1)
    const player = classFunctions(api, cls('Player'))
    expect(player.map((it) => it.name)).not.toContain('set_target')
    // A Player's own `name` replaces an Entity's.
    expect(player.find((it) => it.name === 'name')?.returns).toEqual([{ type: 'string' }])
  })

  it("list a class's events with those up its chain, its own replacing theirs", () => {
    const owner = (cls: LuaClass, name: string) =>
      classEvents(api, cls).find((it) => it.event.name === name)?.owner.name
    expect(owner(cls('Mob'), 'death')).toBe('Living')
    expect(owner(cls('Mob'), 'damage')).toBe('Entity')
    expect(owner(cls('Mob'), 'path_end')).toBe('Mob')
    expect(owner(cls('Player'), 'death')).toBe('Player')
    expect(owner(cls('Player'), 'heal')).toBe('Living')
    expect(owner(cls('Player'), 'path_end')).toBeUndefined()
  })

  it('may be saveable only at the top of a chain', () => {
    expect(() => bindings(spec(fn(), { saveable: true }))).not.toThrow()
    const below: ApiSpec = {
      ...spec(fn()),
      classes: [
        handle,
        { ...handle, name: 'Part', handle: undefined, extends: 'Thing', saveable: true },
      ],
    }
    expect(() => bindings(below)).toThrow(/only a handle class at the top of its chain/)
  })
})

describe('hand-written functions', () => {
  it('check each argument as far as its type can say, and leave the rest to the body', () => {
    const it = spec(
      fn({
        name: 'use',
        impl: 'lua',
        params: [
          { name: 'name', type: 'string', doc: '' },
          { name: 'count', type: 'integer', doc: '', optional: true },
          { name: 'other', type: 'Thing', doc: '' },
          { name: 'maybe', type: 'Thing?', doc: '' },
          { name: 'at', type: 'Vec3', doc: '' },
          { name: 'mode', type: '"loud"|"quiet"', doc: '' },
          { name: 'callback', type: 'fun(x: number)', doc: '' },
          { name: 'list', type: 'number[]', doc: '' },
          { name: 'options', type: 'Opts?', doc: '' },
          { name: 'text', type: 'Text', doc: '' },
          { name: 'anything', type: 'any', doc: '' },
          { name: 'either', type: 'Thing|Vec3', doc: '' },
          { name: '...', type: 'any', doc: '' },
        ],
      }),
      {},
      {
        shapes: [shape('Opts', [{ name: 'size', type: 'integer', doc: '' }])],
        aliases: [{ name: 'Text', type: 'string', doc: 'MiniMessage.' }],
      },
    )
    // A hand-written function has no binding of its own: the wrapper checks, the body does the rest.
    expect(bindings(it).classes[0]!.functions).toEqual([])
    const params = handwrittenFunctions(it).get('Thing')![0]!.params
    expect(
      Object.fromEntries(params.map((p) => [p.name, [p.check, p.optional, p.vararg]])),
    ).toEqual({
      name: [{ kind: 'type', lua: 'string' }, false, false],
      count: [{ kind: 'integer' }, true, false],
      other: [{ kind: 'handle', cls: 'Thing' }, false, false],
      maybe: [{ kind: 'handle', cls: 'Thing' }, true, false],
      at: [{ kind: 'vec3' }, false, false],
      mode: [{ kind: 'choice', choices: ['loud', 'quiet'] }, false, false],
      callback: [{ kind: 'type', lua: 'function' }, false, false],
      list: [{ kind: 'type', lua: 'table' }, false, false],
      options: [{ kind: 'shape', name: 'Opts' }, true, false],
      text: [{ kind: 'type', lua: 'string' }, false, false],
      anything: [{ kind: 'body' }, false, false],
      either: [{ kind: 'body' }, false, false],
      '...': [{ kind: 'body' }, false, true],
    })
  })

  it('give the prelude the fields of every table they take, nested ones too', () => {
    const shapes = handwrittenShapes(api)
    expect(shapes.map((it) => it.name)).toEqual([
      'DialogOpenOptions',
      'EventOptions',
      'GoalDefinition',
    ])
    const goal = shapes.find((it) => it.name === 'GoalDefinition')!
    expect(goal.required).toEqual(['priority'])
    expect(goal.fields).toEqual(
      expect.arrayContaining([
        { name: 'priority', kind: 'integer' },
        { name: 'controls', kind: 'table' },
        { name: 'should_start', kind: 'function' },
      ]),
    )
    expect(shapes.find((it) => it.name === 'DialogOpenOptions')!.fields).toContainEqual({
      name: 'context',
      kind: 'any',
    })
  })

  it("can't take a parameter the wrapper's own names need", () => {
    for (const name of ['hand', 'self']) {
      const it = spec(fn({ impl: 'lua', params: [{ name, type: 'number', doc: '' }] }))
      expect(() => bindingsLua(bindings(it).classes, it)).toThrow(
        new RegExp(`a parameter can't be called ${name}`),
      )
    }
    // Nor can an async binding's parameter shadow its own locals.
    for (const name of ['token', 'wait_id', 'prim']) {
      const it = spec(
        asyncFunction({
          name: 'fetch',
          doc: 'd',
          params: [{ name, type: 'string', doc: '' }],
          value: { name: 'thing', type: 'Thing', doc: 'the thing' },
        }),
      )
      expect(() => bindingsLua(bindings(it).classes, it)).toThrow(
        new RegExp(`a parameter can't be called ${name}`),
      )
    }
  })
})

describe('what the generator refuses', () => {
  it('a class without methods that is not a namespace under nf', () => {
    expect(() =>
      bindings(spec(fn(), { name: 'loose', methods: false, handle: undefined })),
    ).toThrow(/must be nf or a namespace/)
  })

  it('a value type or alias the runtime has no codec for', () => {
    expect(() => bindings(spec(fn(), {}, { values: [{ ...vec3, name: 'Quat' }] }))).toThrow(
      /value type Quat has no codec/,
    )
    expect(() =>
      bindings(spec(fn(), {}, { aliases: [{ name: 'Name', type: 'string', doc: 'd' }] })),
    ).toThrow(/type alias Name has no codec/)
  })

  it('a function that waits unless hand-written and without a callback form', () => {
    expect(() => bindings(spec(fn({ waits: true })))).toThrow(/hand-written in the prelude/)
  })

  it('a requirement that is none, or one on a hand-written function', () => {
    expect(() => bindings(spec(fn({ requires: 'plugin:' as never })))).toThrow(
      /isn't a requirement/,
    )
    expect(() => bindings(spec(fn({ requires: 'plugin:Vault' as never })))).toThrow(
      /isn't a requirement/,
    )
    expect(() => bindings(spec(fn({ requires: 'moderation', impl: 'lua' })))).toThrow(
      /not on impl lua/,
    )
  })

  it('a command argument whose value may be nil', () => {
    const it = spec(
      fn(),
      {},
      {
        commandArguments: [{ name: 'maybe', doc: 'd', value: 'string?', reading: 'word' }],
      },
    )
    expect(() => bindings(it)).toThrow(/its value is never nil/)
  })
})

describe('requirements', () => {
  it('are checked by the generated primitive before any argument is read', () => {
    const it = spec(
      fn({
        name: 'ban',
        params: [{ name: 'why', type: 'string', doc: '' }],
        requires: 'moderation',
      }),
    )
    const kotlin = primitivesKotlin(bindings(it).classes)
    const check = kotlin.indexOf('marshal.requires("moderation", "Thing:ban")')
    expect(check).toBeGreaterThan(-1)
    expect(check).toBeLessThan(kotlin.indexOf('"why"'))
  })

  it('say how to declare them, wherever the function is documented', () => {
    const it = spec(fn({ requires: 'plugin:vault' }))
    const needs = '`"requires": { "plugins": ["vault"] }`'
    expect(luals(it)).toContain(needs)
    expect(referencePages(it).get('thing.md')).toContain(needs)
  })
})

describe('version gates', () => {
  // Nothing in the spec needs a newer Minecraft than the oldest supported one today, so these
  // features and the table they're in are made up.
  let restore: () => void
  beforeAll(() => {
    restore = useFeatures({
      poking: { since: '27.1' },
      shaking: { since: '27.2' },
      hard_poking: { since: '27.3' },
    })
  })
  afterAll(() => restore())
  const gated = spec(
    fn({
      name: 'poke',
      params: [{ name: 'options', type: 'PokeOptions', doc: '', optional: true }],
      since: 'poking',
    }),
    { events: [{ name: 'shake', doc: 'Shaken.', since: 'shaking' }] },
    {
      shapes: [
        shape('PokeOptions', [
          { name: 'hard', type: 'boolean?', doc: 'Harder.', since: 'hard_poking' },
          { name: 'soft', type: 'boolean?', doc: 'Softer.' },
        ]),
      ],
    },
  )

  it('give the runtime every gated function, event and option field with its version, and nothing else', () => {
    const gates = gatesKotlin(gated)
    for (const entry of [
      '"Thing.poke" to "27.1"',
      '"Thing.shake" to "27.2"',
      '"PokeOptions.hard" to "27.3"',
    ])
      expect(gates).toContain(entry)
    expect(gates).not.toContain('PokeOptions.soft')
  })

  it('are shown where each is documented', () => {
    const pages = referencePages(gated)
    expect(pages.get('thing.md')).toContain('Since Minecraft 27.1')
    expect(pages.get('events.md')).toContain('Since Minecraft 27.2')
    expect(pages.get('types.md')).toContain('Since Minecraft 27.3')
    expect(luals(gated)).toContain('Since Minecraft 27.3')
  })
})

describe('command argument types', () => {
  it("cross their handler's value by its type's codec, in the spec's order", () => {
    const typed = spec(
      fn(),
      {},
      {
        commandArguments: [
          { name: 'word', doc: 'one word', value: 'string', reading: 'word' },
          { name: 'things', doc: 'some things', value: 'Thing[]', reading: 'entities' },
        ],
      },
    )
    expect(bindings(typed).commandArguments.map((it) => [it.type.name, it.codec])).toEqual([
      ['word', { kind: 'string' }],
      ['things', { kind: 'list', item: thing }],
    ])
  })

  it('are the type field of a command argument, each with its line in its doc', () => {
    const shape = api.shapes.find((it) => it.name === 'CommandArgument')!
    const type = shape.fields.find((it) => it.name === 'type')!
    expect(type.type.split('|')).toEqual(api.commandArguments!.map((it) => `"${it.name}"`))
    expect(type.doc).toContain('`"player"`: an online player by name')
  })
})
