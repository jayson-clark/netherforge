import { FEATURES } from '@netherforge/format/constants'
import { describe, expect, it } from 'vitest'
import { asyncValue } from './async.ts'
import { namedTypes, parseLuaType } from './luaType.ts'
import { DATA_VALUES } from './spec/data.ts'
import { api } from './spec/index.ts'
import type { EventSpec, Fn, LuaType } from './types.ts'

function duplicates(names: string[]): string[] {
  return names.filter((name, index) => names.indexOf(name) !== index)
}

const values = api.values ?? []
const libraries = values.flatMap((it) => (it.library ? [it.library] : []))

/** Every class's events, with the class. */
const events: [string, EventSpec][] = api.classes.flatMap((cls) =>
  (cls.events ?? []).map((event): [string, EventSpec] => [cls.name, event]),
)

const declared = new Set([
  ...api.classes.map((it) => it.name),
  ...api.shapes.map((it) => it.name),
  ...values.map((it) => it.name),
  ...libraries.map((it) => it.name),
  ...(api.aliases ?? []).map((it) => it.name),
  // The event-name aliases the generators emit for each class with events.
  ...api.classes.filter((it) => it.events?.length).map((it) => `${it.name}.Event`),
])

const functions: [string, Fn][] = [
  ...api.classes.flatMap((cls) =>
    cls.functions.map((fn): [string, Fn] => [`${cls.name}.${fn.name}`, fn]),
  ),
  ...values.flatMap((value) =>
    value.functions.map((fn): [string, Fn] => [`${value.name}.${fn.name}`, fn]),
  ),
  ...libraries.flatMap((library) => [
    [`${library.name}()`, library.call] as [string, Fn],
    ...library.functions.map((fn): [string, Fn] => [`${library.name}.${fn.name}`, fn]),
  ]),
  ...api.shapes.flatMap((shape) =>
    shape.functions.map((fn): [string, Fn] => [`${shape.name}.${fn.name}`, fn]),
  ),
]

/** Everything with fields: classes, shapes, values and their libraries. */
const withFields = [...api.classes, ...api.shapes, ...values, ...libraries]

describe('the API spec', () => {
  it('has unique names at every level', () => {
    expect(
      duplicates([...api.classes, ...api.shapes, ...values, ...libraries].map((it) => it.name)),
    ).toEqual([])
    expect(duplicates(api.surfaces.map((it) => it.name))).toEqual([])
    expect(duplicates(api.globals.map((it) => it.name))).toEqual([])
    for (const cls of api.classes)
      expect(duplicates((cls.events ?? []).map((it) => it.name)), cls.name).toEqual([])
    for (const cls of [...api.classes, ...api.shapes, ...values, ...libraries]) {
      expect(duplicates([...cls.functions, ...cls.fields].map((it) => it.name)), cls.name).toEqual(
        [],
      )
    }
    for (const surface of api.surfaces) {
      const shared = api.globals.map((it) => it.name)
      expect(
        surface.globals.filter((it) => shared.includes(it.name)),
        surface.name,
      ).toEqual([])
    }
    for (const [name, fn] of functions) {
      expect(duplicates(fn.params.map((it) => it.name)), name).toEqual([])
    }
  })

  it("gives every resource's script `this`, its own handle, and nothing else", () => {
    const thisOf = Object.fromEntries(
      api.surfaces.map((surface) => [
        surface.name,
        surface.globals.map((it) => `${it.name}: ${it.type}`),
      ]),
    )
    expect(thisOf).toEqual({
      centity: ['this: Centity'],
      menu: ['this: Menu'],
      dialog: ['this: Dialog'],
      item: ['this: ProjectItem'],
      block: ['this: ProjectBlock'],
      screen: ['this: Screen'],
      module: [],
    })
  })

  it('writes every type in the type grammar, naming only types it declares', () => {
    const referenced: [string, LuaType][] = [
      ...api.globals.map((it): [string, LuaType] => [`global ${it.name}`, it.type]),
      ...api.surfaces.flatMap((s) =>
        s.globals.map((it): [string, LuaType] => [`${s.name} global ${it.name}`, it.type]),
      ),
      ...withFields.flatMap((cls) =>
        cls.fields.map((it): [string, LuaType] => [`${cls.name}.${it.name}`, it.type]),
      ),
      ...values.flatMap((value) =>
        value.operators.flatMap((it): [string, LuaType][] => [
          [`${value.name} ${it.op}`, it.result],
          ...(it.operand ? [[`${value.name} ${it.op}`, it.operand] as [string, LuaType]] : []),
        ]),
      ),
      ...functions.flatMap(([name, fn]) => [
        ...fn.params.map((p): [string, LuaType] => [`${name}(${p.name})`, p.type]),
        ...fn.returns.map((r): [string, LuaType] => [`${name} returns`, r.type]),
      ]),
      ...events.flatMap(([cls, e]) =>
        e.payload ? [[`${cls} event ${e.name}`, e.payload] as [string, LuaType]] : [],
      ),
    ]
    const problems = referenced.flatMap(([where, type]) => {
      let names: string[]
      try {
        names = namedTypes(parseLuaType(type))
      } catch (error) {
        return [`${where}: ${(error as Error).message}`]
      }
      return names.filter((name) => !declared.has(name)).map((name) => `${where}: unknown ${name}`)
    })
    expect(problems).toEqual([])
  })

  it('says what identifies every handle class at the top of a chain, and nothing else has a handle', () => {
    for (const cls of api.classes) {
      // A class that extends another is keyed as the top of its chain is.
      if (!cls.methods || cls.extends) {
        expect(cls.handle, cls.name).toBeUndefined()
        continue
      }
      expect(cls.handle, cls.name).toBeDefined()
      expect(cls.handle!.key.length, cls.name).toBeGreaterThan(0)
      expect(cls.handle!.key.length, cls.name).toBeLessThanOrEqual(2)
    }
    for (const shape of api.shapes) expect(shape.handle, shape.name).toBeUndefined()
  })

  it('puts each method on the class it applies to: none says it answers nil on a class without it', () => {
    // What `extends` replaced: an `Entity` method that answered nil for an entity that isn't a mob.
    const wrongClass =
      /for an entity that isn't (living|a mob|a dropped item)|For a dropped item|A `Player` has no/
    for (const cls of api.classes)
      for (const fn of cls.functions) {
        const docs = [fn.doc, ...fn.returns.map((it) => it.doc ?? '')].join(' ')
        expect(docs, `${cls.name}.${fn.name}`).not.toMatch(wrongClass)
      }
  })

  it('implements only class functions in Lua or Kotlin', () => {
    // A shape is a plain table; only `Event`, the base of every payload, has methods, the event core's.
    for (const shape of api.shapes)
      if (shape.name !== 'Event') expect(shape.functions, shape.name).toEqual([])
    for (const fn of api.shapes.find((it) => it.name === 'Event')?.functions ?? [])
      expect(fn.impl, `Event.${fn.name}`).toBe('lua')
    // Values are Lua tables: everything about them is the prelude's.
    for (const [name, fn] of functions)
      if (
        values.some((it) => name.startsWith(`${it.name}.`)) ||
        libraries.some((it) => name.startsWith(it.name))
      )
        expect(fn.impl, name).toBe('lua')
  })

  it('names each value library for its value, and gives only unary minus no operand', () => {
    for (const value of values) {
      if (value.library) expect(value.library.name).toBe(value.name.toLowerCase())
      for (const it of value.operators)
        expect(it.operand === undefined, `${value.name} ${it.op}`).toBe(it.op === 'unm')
    }
    // A value is what its library's call returns.
    for (const value of values)
      if (value.library)
        expect(value.library.call.returns.map((it) => it.type)).toEqual([value.name])
  })

  it('documents everything', () => {
    const undocumented = [
      ...[
        ...api.classes,
        ...api.shapes,
        ...values,
        ...libraries,
        ...api.surfaces,
        ...events.map(([, it]) => it),
      ]
        .filter((it) => !it.doc)
        .map((it) => it.name),
      ...functions.filter(([, fn]) => !fn.doc).map(([name]) => name),
    ]
    expect(undocumented).toEqual([])
  })

  it('puts optional parameters last', () => {
    for (const [name, fn] of functions) {
      const firstOptional = fn.params.findIndex((p) => p.optional)
      if (firstOptional < 0) continue
      const after = fn.params.slice(firstOptional).filter((p) => !p.optional)
      expect(
        after.map((p) => p.name),
        name,
      ).toEqual([])
    }
  })

  it('describes every event payload as a shape extending Event', () => {
    for (const [cls, event] of events) {
      if (!event.payload) continue
      const shape = api.shapes.find((it) => it.name === event.payload)
      expect(shape?.extends, `${cls} ${event.name}`).toBe('Event')
    }
  })

  it("names no payload field after what every event object has (the prelude's event core refuses it)", () => {
    // `RESERVED` and `Event`'s members in the runtime's prelude/events.lua: a handler can't assign these.
    const reserved = new Set(['name', 'current', 'cancelled', 'stopped'])
    for (const [cls, event] of events) {
      const fields = api.shapes.find((it) => it.name === event.payload)?.fields ?? []
      for (const field of fields)
        expect(
          reserved.has(field.name) && event.writable?.includes(field.name),
          `${cls} ${event.name}: ${field.name}`,
        ).toBe(false)
    }
  })

  it('makes only payload fields writable, and only on payloads that have them', () => {
    for (const [cls, event] of events) {
      const fields = api.shapes.find((it) => it.name === event.payload)?.fields ?? []
      for (const name of event.writable ?? [])
        expect(
          fields.map((it) => it.name),
          `${cls} ${event.name}`,
        ).toContain(name)
      const options = api.shapes.find((it) => it.name === 'EventOptions')!.fields
      for (const name of event.options ?? [])
        expect(
          options.map((it) => it.name),
          `${cls} ${event.name}`,
        ).toContain(name)
    }
  })

  it('gives every class with events on and once, and emit where it takes custom ones', () => {
    for (const cls of api.classes) {
      const names = cls.functions.map((it) => it.name)
      const evented = (cls.events?.length ?? 0) > 0
      expect(names.includes('on'), cls.name).toBe(evented)
      expect(names.includes('once'), cls.name).toBe(evented)
      expect(names.includes('emit'), cls.name).toBe(!!cls.customEvents)
      if (cls.customEvents) expect(evented, cls.name).toBe(true)
    }
  })

  it('lets nf.wait_for wait on every class with events', () => {
    const waitFor = api.classes
      .find((it) => it.name === 'nf')!
      .functions.find((it) => it.name === 'wait_for')!
    const handle = waitFor.params.find((it) => it.name === 'handle')!.type.split('|')
    // A class below one listed is listed with it (a Mob is an Entity).
    const above = (cls: (typeof api.classes)[number]): string[] => {
      const parent = api.classes.find((it) => it.name === cls.extends)
      return parent ? [parent.name, ...above(parent)] : []
    }
    const evented = api.classes
      .filter((cls) => cls.events?.length && !above(cls).some((it) => handle.includes(it)))
      .map((it) => it.name)
    expect([...handle].sort()).toEqual([...evented].sort())
  })

  it('lists every namespace in nf, and every way a task waits in nf.task', () => {
    const nf = api.classes.find((it) => it.name === 'nf')!
    for (const ns of api.classes.filter((it) => it.name.startsWith('nf.')))
      expect(nf.doc).toContain(`\`${ns.name}\``)
    const task = nf.functions.find((it) => it.name === 'task')!
    for (const [where, fn] of functions.filter(([, fn]) => fn.waits || fn.async)) {
      const [owner, name] = [where.slice(0, where.lastIndexOf('.')), fn.name]
      const cls = api.classes.find((it) => it.name === owner)!
      expect(task.doc).toContain(cls.methods ? `${owner.toLowerCase()}:${name}` : where)
    }
  })

  it('says what a saved table keeps of every saveable handle class and its kinds', () => {
    const saveable = api.classes.filter((it) => it.saveable)
    expect(saveable.map((it) => it.name).sort()).toEqual(['Centity', 'Entity', 'World'])
    for (const cls of saveable) expect(DATA_VALUES).toContain(`\`${cls.name}\``)
  })

  it('names every bubbling event so the path can go on', () => {
    // Bubbling ends at a server-wide event of the same payload on nf, or at another class's.
    const nf = api.classes.find((it) => it.name === 'nf')!
    for (const [cls, event] of events.filter(([, it]) => it.bubbles)) {
      const onward = events.some(
        ([other, it]) => (other !== cls || it.name !== event.name) && it.payload === event.payload,
      )
      expect(onward, `${cls} ${event.name}`).toBe(true)
    }
    expect(nf.events?.some((it) => it.local)).toBe(true)
  })

  it("gates only class functions, events and shape fields, each by a feature in format's table", () => {
    // The runtime checks `since` on these; nowhere else would it be checked.
    const gates = [
      ...api.classes.flatMap((cls) => cls.functions.map((it) => it.since)),
      ...events.map(([, it]) => it.since),
      ...api.shapes.flatMap((shape) => shape.fields.map((it) => it.since)),
    ].filter((it) => it !== undefined)
    for (const since of gates) expect(Object.keys(FEATURES)).toContain(since)
    const ungated = [
      ...values.flatMap((it) => [...it.functions, ...it.fields]),
      ...libraries.flatMap((it) => [it.call, ...it.functions, ...it.fields]),
      ...api.classes.flatMap((it) => it.fields),
      ...api.shapes.flatMap((it) => it.functions),
    ]
    expect(ungated.filter((it) => it.since !== undefined)).toEqual([])
  })
})

// ---- the naming rules, the ones a machine can check --------------

/**
 * Names that break a naming rule on purpose, each with why.
 * Entries marked `TODO(slice …)` exist only until that part of the redesign
 * lands, and go with it; anything else needs a reason that will stay true.
 * One that no rule needs any more is stale, and fails the last test.
 */
const NAMING_EXCEPTIONS: Record<string, string> = {
  'Vec3.unpack': 'its whole job is handing the three numbers back separately',
  'Effect.is_active':
    "a playing effect, like a Task, is active or not: it's stopped (or runs out), not cancelled, and it isn't a thing in the world that exists",
  'Cutscene.is_active':
    "a playing cutscene, like an Effect, is active or not: it's stopped (or runs out), not cancelled, and it isn't a thing in the world that exists",
  'World.set_block':
    "a keyed setter of a block's state: `block(position)` hands back the Block handle, whose `state()` this sets",
  'ItemFood.can_always_eat': "Minecraft's own name for the food component's field",
  'ItemUseEffects.can_sprint': "Minecraft's own name for the use_effects component's field",
}

/** Abbreviations API names spell out (`position`, not `pos`), as `_`-separated words. */
const ABBREVIATIONS = [
  'arg',
  'args',
  'btn',
  'cb',
  'cfg',
  'cmd',
  'ctx',
  'dir',
  'dlg',
  'fn',
  'idx',
  'inv',
  'len',
  'ms',
  'msg',
  'num',
  'obj',
  'op',
  'pos',
  'str',
  'tps',
  'val',
]

/** The only one-letter words: they're the coordinates' own names (`v.x`, `with_x(x)`). */
const ONE_LETTER_WORDS = ['x', 'y', 'z']

/**
 * Verbs that start an action, which may answer whether it worked, each with
 * the functions that need it; any other boolean getter needs `is_`/`has_`/`can_`.
 * A verb no function needs any more is stale, and fails a test.
 */
const ACTION_VERBS: Record<string, string> = {
  clear:
    '`inventory:clear()`, `menu:clear()`, `sidebar:clear()`, `player:clear_title()` empty something',
  close:
    '`menu:close_all()`, `player:close_menu()`, `close_dialog()`, `close_inventory()` take a screen away',
  create: '`file:create_folder()` makes a folder',
  delete: '`file:delete()` erases a file',
  jump: '`bot:jump()` jumps',
  keep: '`centity:keep()` makes a natural centity a permanent one',
  pause: '`playback:pause()` holds a video on its frame',
  leave: '`bot:leave()` disconnects a bot',
  refresh: '`menu:refresh()` sends the window again',
  remove:
    '`entity:remove()`, `bossbar:remove()`, `template:remove()` take something out of the game',
  release: '`bot:release_item()` lets go of right-click',
  reset: "`player:reset_border()` gives a player their world's border back",
  respawn: '`bot:respawn()` presses Respawn',
  resume: '`playback:resume()` plays a paused video on',
  stop: '`effect:stop()` ends a playing effect, `mob:stop_pathing()` a walk, `bot:stop()` what a bot is doing',
  swap: "`bot:swap_hands()` swaps a bot's hands",
  swing: "`bot:swing()` swings a bot's arm",
  take: "`screen:take_down()` takes a screen's frames down",
  unban: '`player:unban()` lifts a ban',
  unbind: '`slot:unbind()` stops a menu slot showing a storage',
}

/** Every name a script writes, with where it is: functions, parameters, fields and events. */
const names: [string, string][] = [
  ...functions.flatMap(([where, fn]): [string, string][] => [
    [where, fn.name],
    ...fn.params
      .filter((p) => p.name !== '...')
      .map((p): [string, string] => [`${where}(${p.name})`, p.name]),
  ]),
  ...withFields.flatMap((cls) =>
    cls.fields.map((it): [string, string] => [`${cls.name}.${it.name}`, it.name]),
  ),
  ...events.map(([cls, it]): [string, string] => [`${cls} event ${it.name}`, it.name]),
  ...[...api.globals, ...api.surfaces.flatMap((it) => it.globals)].map((it): [string, string] => [
    `global ${it.name}`,
    it.name,
  ]),
]

const classFunctions: [string, Fn][] = [...api.classes, ...values, ...libraries].flatMap((cls) =>
  cls.functions.map((fn): [string, Fn] => [`${cls.name}.${fn.name}`, fn]),
)

const allowed = (where: string) => where in NAMING_EXCEPTIONS
const returnsBoolean = (fn: Fn) => fn.returns.length === 1 && fn.returns[0]!.type === 'boolean'
const predicate = /^(is|has|can)_/

/** The types a value may be, `nil` left out: `"a"|"b"?` → `"a"`, `"b"`. */
const members = (type: LuaType) =>
  type
    .replace(/\?$/, '')
    .replace(/^\((.*)\)$/, '$1')
    .split('|')
    .filter((it) => it !== 'nil')

/** The setters of each class and value, with the getter each pairs with (if any). */
/** A class's functions with those it inherits: a setter's getter may be up its chain (`Bot.set_sneaking`, `Player.is_sneaking`). */
const chainOf = (cls: { extends?: string; functions: Fn[] }): Fn[] => {
  const parent = cls.extends ? api.classes.find((it) => it.name === cls.extends) : undefined
  return [...(parent ? chainOf(parent) : []), ...cls.functions]
}

const setters = [...api.classes, ...values].flatMap((cls) => {
  const own = new Map(chainOf(cls).map((fn) => [fn.name, fn]))
  return cls.functions
    .filter((fn) => fn.name.startsWith('set_'))
    .map((fn) => {
      const noun = fn.name.slice('set_'.length)
      const getter = [noun, `is_${noun}`, `has_${noun}`].map((it) => own.get(it)).find(Boolean)
      return { where: `${cls.name}.${fn.name}`, noun, fn, getter }
    })
})

/**
 * The checks that honour [NAMING_EXCEPTIONS]: each lists everything that
 * breaks its rule, exceptions included, so the tests can tell a stale one.
 */
const breaking = {
  // An async function's `value, err` is the one documented exception (the "Async failure" decision).
  severalReturns: () =>
    classFunctions.filter(([, fn]) => fn.returns.length > 1 && !fn.async).map(([where]) => where),
  barePredicates: () =>
    classFunctions
      .filter(
        ([, fn]) =>
          fn.params.length === 0 &&
          returnsBoolean(fn) &&
          !predicate.test(fn.name) &&
          fn.name !== 'exists' &&
          !(fn.name.split('_')[0]! in ACTION_VERBS),
      )
      .map(([where]) => where),
  unpairedSetters: () =>
    setters.filter((it) => !it.getter && !/^(is|has)_/.test(it.noun)).map((it) => it.where),
  setterTypes: () =>
    setters
      .filter(({ fn, getter }) => {
        const value = getter && fn.params[getter.params.length]
        if (!getter || !value || getter.returns.length !== 1) return false
        const takes = new Set(members(value.type))
        return members(getter.returns[0]!.type).some((it) => !takes.has(it))
      })
      .map((it) => it.where),
  activeWithoutCancel: () =>
    [...api.classes, ...values]
      .filter(
        (cls) =>
          cls.functions.some((fn) => fn.name === 'is_active') &&
          !cls.functions.some((fn) => fn.name === 'cancel'),
      )
      .map((cls) => `${cls.name}.is_active`),
  prefixedBooleanFields: () =>
    withFields.flatMap((cls) =>
      cls.fields
        .filter((it) => members(it.type).join('|') === 'boolean' && predicate.test(it.name))
        .map((it) => `${cls.name}.${it.name}`),
    ),
}

const unexcused = (found: string[]) => found.filter((where) => !allowed(where))

describe('the naming rules', () => {
  it('spells functions, parameters, fields, events and globals in snake_case', () => {
    const snake = /^[a-z][a-z0-9]*(_[a-z0-9]+)*$/
    expect(names.filter(([, name]) => !snake.test(name))).toEqual([])
  })

  it('spells types in PascalCase, and namespaces as nf.<name>', () => {
    const pascal = /^[A-Z][a-zA-Z0-9]*$/
    const types = [...api.classes.filter((it) => it.methods), ...api.shapes, ...values].map(
      (it) => it.name,
    )
    expect(types.filter((name) => !pascal.test(name))).toEqual([])
    const namespaces = api.classes.filter((it) => !it.methods).map((it) => it.name)
    expect(namespaces.filter((name) => !/^nf(\.[a-z]+)*$/.test(name))).toEqual([])
  })

  it('spells words out', () => {
    const abbreviated = names.filter(([, name]) =>
      name
        .split('_')
        .some(
          (word) =>
            ABBREVIATIONS.includes(word) || (word.length === 1 && !ONE_LETTER_WORDS.includes(word)),
        ),
    )
    expect(abbreviated).toEqual([])
  })

  it("names a player_* event's payload Player…Event", () => {
    const nf = api.classes.find((it) => it.name === 'nf')!
    const misnamed = (nf.events ?? []).filter(
      (it) => it.name.startsWith('player_') && it.payload && !/^Player\w*Event$/.test(it.payload),
    )
    expect(misnamed.map((it) => `${it.name}: ${it.payload}`)).toEqual([])
  })

  it('says what caused an event with cause, and reason only for why something ended', () => {
    // `reason` is only on an ending's payload: an `end` event's (a particle effect's) or a `*_end` one's (a flight's).
    const endPayloads = new Set(
      events
        .filter(([, it]) => it.name === 'end' || it.name.endsWith('_end'))
        .map(([, it]) => it.payload),
    )
    const payloads = new Set(events.map(([, it]) => it.payload))
    const wrong = api.shapes
      .filter((it) => payloads.has(it.name))
      .flatMap((shape) =>
        shape.fields
          .filter((it) => it.name === 'reason' && !endPayloads.has(shape.name))
          .map((it) => `${shape.name}.${it.name}`),
      )
    expect(wrong).toEqual([])
  })

  it('names getters as nouns, without get_', () => {
    expect(classFunctions.filter(([, fn]) => fn.name.startsWith('get_')).map(([w]) => w)).toEqual(
      [],
    )
  })

  it('returns one value from every function', () => {
    expect(unexcused(breaking.severalReturns())).toEqual([])
  })

  it('declares every async function with asyncFunction: a last callback, and value, err', () => {
    const async = classFunctions.filter(([, fn]) => fn.async)
    expect(async.map(([where]) => where)).toContain('nf.worlds.copy')
    for (const [where, fn] of async) {
      expect(() => asyncValue(fn, where)).not.toThrow()
      expect(fn.impl ?? 'kotlin', where).toBe('kotlin')
    }
  })

  it('names boolean getters is_, has_ or can_, and only those', () => {
    expect(unexcused(breaking.barePredicates())).toEqual([])
    const notBoolean = classFunctions.filter(
      ([, fn]) => (predicate.test(fn.name) || fn.name === 'exists') && !returnsBoolean(fn),
    )
    expect(notBoolean.map(([where]) => where)).toEqual([])
  })

  it('keeps is_active for what can be cancelled; anything else that goes says exists()', () => {
    expect(unexcused(breaking.activeWithoutCancel())).toEqual([])
  })

  it('pairs every setter with its getter, booleans without their prefix', () => {
    const prefixed = setters
      .filter((it) => /^(is|has)_/.test(it.noun))
      .map((it) => `${it.where}: drop the prefix`)
    expect(prefixed).toEqual([])
    expect(unexcused(breaking.unpairedSetters())).toEqual([])
  })

  it('has every setter take what its getter returns', () => {
    expect(unexcused(breaking.setterTypes())).toEqual([])
  })

  it('names boolean fields as bare adjectives', () => {
    expect(unexcused(breaking.prefixedBooleanFields())).toEqual([])
  })

  it('keeps the exceptions to real ones', () => {
    // An exception for a name that's gone, or one that no longer breaks a rule, is stale.
    const needed = new Set(Object.values(breaking).flatMap((check) => check()))
    expect(Object.keys(NAMING_EXCEPTIONS).filter((where) => !needed.has(where))).toEqual([])
  })

  it('keeps the action verbs to ones a function needs', () => {
    const used = new Set(
      classFunctions
        .filter(
          ([, fn]) => fn.params.length === 0 && returnsBoolean(fn) && !predicate.test(fn.name),
        )
        .map(([, fn]) => fn.name.split('_')[0]!),
    )
    expect(Object.keys(ACTION_VERBS).filter((verb) => !used.has(verb))).toEqual([])
  })
})
