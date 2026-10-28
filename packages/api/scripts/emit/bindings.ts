/**
 * The binding model: how each value crosses between Lua and Kotlin, worked
 * out once from the spec's types so the Lua and Kotlin emitters can't
 * disagree.
 *
 * Every node of the type grammar has one codec ([Codec]), used the same way
 * wherever the type appears: a parameter, a return, an option table's field,
 * an event's payload or a writable field. An argument crosses as the one Lua
 * value it is, and Kotlin decides what it is (the runtime's `LuaCodec`s), so a
 * list of shapes, a map of shapes or a union is no more work than a string.
 * The one exception is speed: a `Vec3` parameter or return crosses as its
 * three numbers ([ParamBinding.spread]), since transforms are set and read
 * every tick.
 *
 * A type that has no codec is an error here, at `pnpm generate`, rather than
 * code that doesn't compile.
 */
import { asyncValue } from '../../src/async.ts'
import { acceptsNil, parseLuaType, withoutNil, type TypeNode } from '../../src/luaType.ts'
import { requirementSentence } from '../../src/requirements.ts'
import type { ApiSpec, CommandArgumentType, EventSpec, Fn, LuaClass } from '../../src/types.ts'
import { eventAlias, eventedClasses } from './common.ts'

/** How one value crosses: one node per node of the type grammar. */
export type Codec =
  | { kind: 'string' }
  | { kind: 'number' }
  | { kind: 'integer' }
  | { kind: 'boolean' }
  | { kind: 'function' }
  /** `any`, or `table` (checked to be one): a Lua value Kotlin reads lazily, or anything Kotlin hands back. */
  | { kind: 'any'; table: boolean }
  /** A union of string literals (`"clear"|"rain"`): a string that must be one of them. */
  | { kind: 'choice'; choices: string[] }
  | { kind: 'optional'; inner: Codec }
  | { kind: 'list'; item: Codec }
  | { kind: 'map'; key: Codec; value: Codec }
  /**
   * Any other union: its members tried in order. [name] joins its members' (`LocationOrVec3`):
   * Kotlin's sealed interface for it, or, when every member is a handle class
   * ([handlesOnly]), the name of its codec, Kotlin's type being `LuaHandle`.
   */
  | { kind: 'union'; name: string; members: Codec[]; handlesOnly: boolean }
  | { kind: 'handle'; cls: HandleClass }
  /** A type whose codec the runtime writes by hand ([RUNTIME_TYPES]): `Vec3`, `Location`, `Item`, `ItemMatch`, `Text`. */
  | { kind: 'runtime'; name: string }
  /** A shape: a plain table, read and pushed as its generated data class. */
  | { kind: 'shape'; name: string }

/** A type the runtime crosses itself: its Kotlin type and the `LuaCodecs` member that crosses it. */
export interface RuntimeType {
  kotlin: string
  /** The Kotlin type spelled in full, for a union's case, which may shadow the short name. */
  qualified: string
  codec: string
}

/**
 * The value types the spec declares (`Vec3`, `Location`) and the shapes with a
 * hand-written conversion (`Item`, checked against the server, and `ItemMatch`,
 * a partial one), and the type aliases (`Text`, a string the runtime reads and
 * pushes with its glyph tags spelled for the script): each needs a codec in the
 * runtime's `LuaCodecs`, and a value type or alias missing from here is an error
 * at generate.
 */
export const RUNTIME_TYPES: Record<string, RuntimeType> = {
  Vec3: { kotlin: 'Vec3', qualified: 'dev.netherforge.format.math.Vec3', codec: 'LuaCodecs.VEC3' },
  Location: {
    kotlin: 'LuaLocation',
    qualified: 'dev.netherforge.plugin.binding.LuaLocation',
    codec: 'LuaCodecs.LOCATION',
  },
  Item: {
    kotlin: 'ItemData',
    qualified: 'dev.netherforge.plugin.platform.ItemData',
    codec: 'LuaCodecs.ITEM',
  },
  ItemMatch: {
    kotlin: 'ItemMatch',
    qualified: 'dev.netherforge.plugin.item.ItemMatch',
    codec: 'LuaCodecs.ITEM_MATCH',
  },
  Text: { kotlin: 'String', qualified: 'kotlin.String', codec: 'LuaCodecs.TEXT' },
}

/**
 * A handle class as the bindings see it. Lua holds a handle as one opaque key, an integer
 * into the runtime's handle table, whose entry Kotlin keeps: the key values here, which are
 * the top of its chain's ([root]). A thing is one handle whatever class it is, and the
 * runtime says which class that is (a zombie's handle is a `Mob`, which is a `Living`, which
 * is an `Entity`).
 */
export interface HandleClass {
  name: string
  /** What its entry in the handle table is made of: its root's `handle.key`. */
  fields: { name: string; type: 'string' | 'integer' }[]
  /** The class at the top of its chain, whose `handle` keys it: itself, for one that extends nothing. */
  root: string
  /** The handle class it extends. */
  parent?: string
  /** It, then each class it is a kind of, up to [root]. */
  chain: string[]
  /** It and every class below it, parents before children: what a handle taken as it may be. */
  descendants: string[]
}

export interface ParamBinding {
  name: string
  codec: Codec
  /** A `Vec3` (or `Vec3?`) crossing as its three numbers, checked in Lua: the fast path. */
  spread: boolean
}

export type ReturnBinding =
  | { kind: 'none' }
  /** [spread]: a `Vec3` (or `Vec3?`) coming back as its three numbers, built in Lua. */
  | { kind: 'one'; codec: Codec; spread: boolean }
  /** Several returns, all there or (when any is optional) a single nil. */
  | { kind: 'tuple'; codecs: Codec[]; optional: boolean }
  /**
   * An `async` function's value, given later as `value, err` (to its callback, or to the task
   * waiting): Kotlin returns a `CompletionStage` of it, and the runtime's `Pending` delivers it.
   */
  | { kind: 'async'; codec: Codec }

export interface FnBinding {
  cls: LuaClass
  fn: Fn
  /** The key in the primitive table: `Centity.play_animation`, `nf.server.tick`. */
  primitive: string
  /** A handle method, whose first argument is the handle; otherwise a namespace function, which gets the calling script. */
  method: boolean
  params: ParamBinding[]
  returns: ReturnBinding
}

export interface ClassBinding {
  cls: LuaClass
  /** Set for a handle class. */
  handle?: HandleClass
  functions: FnBinding[]
}

/** A shape some binding reads or pushes, which gets a data class. */
export interface ShapeBinding {
  shape: LuaClass
  fields: { name: string; codec: Codec; doc: string }[]
  /** Some binding reads it from Lua (a parameter, an option table's field, an event's writable field). */
  read: boolean
  /** Some binding pushes it to Lua (a return, an event's payload). */
  pushed: boolean
}

/** A command argument type, with the codec its handler's value (and a `default` of it) crosses by. */
export interface ArgumentBinding {
  type: CommandArgumentType
  codec: Codec
}

/** Everything the emitters need: each class's functions, every shape that crosses, every union. */
export interface Bindings {
  classes: ClassBinding[]
  shapes: ShapeBinding[]
  /** Every union that isn't only string literals, by its Kotlin name. */
  unions: Map<string, Extract<Codec, { kind: 'union' }>>
  /** Every command argument type, in the spec's order. */
  commandArguments: ArgumentBinding[]
}

/** `nf` and the namespaces under it (`nf.server`): every class without methods. */
export const isNamespace = (name: string) => name === 'nf' || name.startsWith('nf.')

/**
 * Every handle class by name, its chain worked out: what it extends (a handle class, to any
 * depth, never round in a circle), and the key its root's `handle` gives it. Only a class
 * that extends nothing has a `handle`, so a thing has one key whatever class it is.
 */
export function handleClasses(spec: ApiSpec): Map<string, HandleClass> {
  const byName = new Map(spec.classes.filter((it) => it.methods).map((it) => [it.name, it]))
  const chainOf = (cls: LuaClass): LuaClass[] => {
    const chain = [cls]
    for (let at = cls; at.extends;) {
      const parent = byName.get(at.extends)
      if (!parent) throw new Error(`${at.name} extends ${at.extends}: both must be handle classes`)
      if (chain.includes(parent)) throw new Error(`${cls.name}'s chain goes round in a circle`)
      chain.push(parent)
      at = parent
    }
    return chain
  }
  const out = new Map<string, HandleClass>()
  for (const cls of byName.values()) {
    const chain = chainOf(cls)
    const root = chain[chain.length - 1]!
    if (cls !== root && cls.handle)
      throw new Error(
        `${cls.name} extends ${cls.extends}, so it is keyed as ${root.name} is: no \`handle\` of its own`,
      )
    const handle = root.handle
    if (!handle)
      throw new Error(`${root.name} is a handle class (methods: true) without \`handle\``)
    if (handle.key.length === 0) throw new Error(`${root.name}'s handle has no key`)
    if (handle.key.length > 2) throw new Error(`${root.name}'s handle has more than two values`)
    out.set(cls.name, {
      name: cls.name,
      fields: handle.key,
      root: root.name,
      ...(cls.extends ? { parent: cls.extends } : {}),
      chain: chain.map((it) => it.name),
      descendants: [],
    })
  }
  // Parents before children: each class's chain is walked from the top.
  const ordered = [...out.values()].sort((a, b) => a.chain.length - b.chain.length)
  for (const it of ordered) for (const name of it.chain) out.get(name)!.descendants.push(it.name)
  return out
}

/** A class and each it is a kind of, up the chain: `Mob`, `Living`, `Entity`. */
export function chainOf(spec: ApiSpec, cls: LuaClass): LuaClass[] {
  const chain = [cls]
  for (let at = cls; at.extends;) {
    const parent = spec.classes.find((it) => it.name === at.extends)
    if (!parent || chain.includes(parent)) break
    chain.push(parent)
    at = parent
  }
  return chain
}

/**
 * Every function a handle class has, its own and those it inherits up its chain, nearest
 * first, each name once: a class's own replaces one of the same name further up.
 */
export function classFunctions(spec: ApiSpec, cls: LuaClass): Fn[] {
  const seen = new Set<string>()
  return chainOf(spec, cls).flatMap((it) =>
    it.functions.filter((fn) => !seen.has(fn.name) && seen.add(fn.name)),
  )
}

/** An event as a class has it: the event, and the class up its chain that declares it. */
export interface ClassEvent {
  event: EventSpec
  owner: LuaClass
}

/**
 * Every event a class has, its own and those it inherits up its chain, nearest first, each
 * name once: a class's own replaces one of the same name further up (a `Player`'s `death` is
 * `player_death`, not a `Living`'s).
 */
export function classEvents(spec: ApiSpec, cls: LuaClass): ClassEvent[] {
  const seen = new Set<string>()
  return chainOf(spec, cls).flatMap((owner) =>
    (owner.events ?? [])
      .filter((event) => !seen.has(event.name) && seen.add(event.name))
      .map((event) => ({ event, owner })),
  )
}

/** What a union's member is called in its Kotlin name and its case: `Location`, `Vec3`, `String`, `PlayerList`. */
function memberName(codec: Codec): string {
  switch (codec.kind) {
    case 'string':
    case 'number':
    case 'integer':
    case 'boolean':
    case 'function':
      return pascal(codec.kind)
    case 'any':
      return codec.table ? 'Table' : 'Any'
    case 'choice':
      return 'Choice'
    case 'handle':
      return codec.cls.name
    case 'runtime':
    case 'shape':
      return codec.name
    case 'list':
      return `${memberName(codec.item)}List`
    case 'map':
      return `${memberName(codec.key)}To${memberName(codec.value)}Map`
    case 'optional':
    case 'union':
      throw new Error('a union member is never optional or a union itself')
  }
}

/** A `Vec3`, or `Vec3?`: what crosses as three numbers. */
const isVec3 = (codec: Codec) =>
  (codec.kind === 'runtime' && codec.name === 'Vec3') ||
  (codec.kind === 'optional' && codec.inner.kind === 'runtime' && codec.inner.name === 'Vec3')

export function bindings(spec: ApiSpec): Bindings {
  const handles = handleClasses(spec)
  for (const cls of spec.classes)
    if (!cls.methods && !isNamespace(cls.name))
      throw new Error(`${cls.name}: a class without methods must be nf or a namespace under it`)
  const shapes = new Map(spec.shapes.map((it) => [it.name, it]))
  for (const it of spec.values ?? [])
    if (!RUNTIME_TYPES[it.name])
      throw new Error(`value type ${it.name} has no codec in bindings.ts (RUNTIME_TYPES)`)
  for (const it of spec.aliases ?? [])
    if (!RUNTIME_TYPES[it.name])
      throw new Error(`type alias ${it.name} has no codec in bindings.ts (RUNTIME_TYPES)`)
  // Names a type can use that are aliases of `string`: each evented class's event names (`nf.Event`).
  const stringAliases = new Set(eventedClasses(spec).map(eventAlias))
  const unions = new Map<string, Extract<Codec, { kind: 'union' }>>()

  const codec = (node: TypeNode, where: string): Codec => {
    switch (node.kind) {
      case 'primitive':
        switch (node.name) {
          case 'string':
          case 'number':
          case 'integer':
          case 'boolean':
          case 'function':
            return { kind: node.name }
          case 'any':
            return { kind: 'any', table: false }
          case 'table':
            return { kind: 'any', table: true }
          default:
            throw new Error(`${where}: no binding for ${node.name}`)
        }
      case 'literal':
        return { kind: 'choice', choices: [node.value] }
      case 'named': {
        const handle = handles.get(node.name)
        if (handle) return { kind: 'handle', cls: handle }
        if (stringAliases.has(node.name)) return { kind: 'string' }
        if (RUNTIME_TYPES[node.name]) return { kind: 'runtime', name: node.name }
        if (shapes.has(node.name)) return { kind: 'shape', name: node.name }
        throw new Error(`${where}: no binding for ${node.name}`)
      }
      case 'fun':
        return { kind: 'function' }
      case 'array':
        return { kind: 'list', item: codec(node.item, where) }
      case 'map': {
        const key = codec(node.key, where)
        if (!['string', 'integer', 'choice'].includes(key.kind))
          throw new Error(`${where}: a table's keys must be strings or whole numbers`)
        return { kind: 'map', key, value: codec(node.value, where) }
      }
      case 'optional':
        return { kind: 'optional', inner: codec(node.type, where) }
      case 'union': {
        if (acceptsNil(node)) return { kind: 'optional', inner: codec(withoutNil(node), where) }
        if (node.types.every((it) => it.kind === 'literal'))
          return {
            kind: 'choice',
            choices: node.types.map((it) => (it.kind === 'literal' ? it.value : '')),
          }
        const literals = node.types.flatMap((it) => (it.kind === 'literal' ? [it.value] : []))
        const members: Codec[] = node.types
          .filter((it) => it.kind !== 'literal')
          .map((it) => codec(it, where))
        // The literals are one member, where the first of them stood.
        if (literals.length)
          members.splice(
            node.types.findIndex((it) => it.kind === 'literal'),
            0,
            { kind: 'choice', choices: literals },
          )
        const names = members.map(memberName)
        if (new Set(names).size !== names.length || (literals.length && names.includes('String')))
          throw new Error(`${where}: a union's members must be told apart by what they are`)
        const handlesOnly = members.every((it) => it.kind === 'handle')
        const union = { kind: 'union' as const, name: names.join('Or'), members, handlesOnly }
        unions.set(union.name, union)
        return union
      }
    }
  }

  // A Location's world crosses as a World handle, so the World class must be one with a single string key.
  if ((spec.values ?? []).some((it) => it.name === 'Location')) {
    const world = handles.get('World')
    if (!world || world.fields.length !== 1 || world.fields[0]!.type !== 'string')
      throw new Error('Location needs a World handle class keyed by one string (its name)')
  }

  /** A parameter's or return's codec: optional when nil is allowed (an `any` is any value already). */
  const typed = (text: string, where: string, optional = false): Codec => {
    const node = parseLuaType(text)
    const any = node.kind === 'primitive' && node.name === 'any'
    const inner = codec(acceptsNil(node) && !any ? withoutNil(node) : node, where)
    return (optional || acceptsNil(node)) && !any ? { kind: 'optional', inner } : inner
  }

  const classes = spec.classes.map((cls): ClassBinding => {
    const functions = cls.functions
      .filter((fn) => (fn.impl ?? 'kotlin') === 'kotlin')
      .map((fn): FnBinding => {
        const where = `${cls.name}.${fn.name}`
        // An async function's callback is the binding's own (the prelude's `async` core), not an argument Kotlin reads.
        const taken = fn.async ? fn.params.slice(0, -1) : fn.params
        const params = taken.map((p): ParamBinding => {
          const it = typed(p.type, `${where}(${p.name})`, p.optional)
          return { name: p.name, codec: it, spread: isVec3(it) }
        })
        if (fn.requires) requirementSentence(fn.requires, where)
        const results = fn.returns.map((r) => typed(r.type, `${where} returns`))
        let returns: ReturnBinding
        if (fn.async)
          returns = { kind: 'async', codec: typed(asyncValue(fn, where), `${where} gives`) }
        else if (results.length === 0) returns = { kind: 'none' }
        else if (results.length === 1)
          returns = { kind: 'one', codec: results[0]!, spread: isVec3(results[0]!) }
        else {
          if (results.length > 4) throw new Error(`${where}: more than four returns`)
          const optional = results.some((it) => it.kind === 'optional')
          // All there or a single nil: each value itself isn't optional.
          const codecs = results.map((it) => (it.kind === 'optional' ? it.inner : it))
          returns = { kind: 'tuple', codecs, optional }
        }
        return {
          cls,
          fn,
          primitive: `${cls.name}.${fn.name}`,
          method: cls.methods,
          params,
          returns,
        }
      })
    const handle = handles.get(cls.name)
    if (cls.saveable && (!handle || handle.root !== cls.name))
      throw new Error(
        `${cls.name}: only a handle class at the top of its chain is saveable (one below is saved as its top)`,
      )
    for (const fn of cls.functions) {
      const where = `${cls.name}.${fn.name}`
      if (fn.waits && fn.impl !== 'lua')
        throw new Error(
          `${where}: a function that waits is hand-written in the prelude's tasks module`,
        )
      if (fn.waits && fn.async)
        throw new Error(
          `${where}: an async function waits only without its callback: not \`waits\``,
        )
      if (fn.impl === 'lua' && fn.requires)
        throw new Error(
          `${where}: requires is checked by the generated binding, so not on impl lua`,
        )
    }
    return { cls, ...(handle ? { handle } : {}), functions }
  })

  // Every shape that crosses, with each field's codec, and which ways it goes.
  const crossing = new Map<string, ShapeBinding>()
  const visit = (c: Codec, read: boolean, pushed: boolean) => {
    switch (c.kind) {
      case 'optional':
        return visit(c.inner, read, pushed)
      case 'list':
        return visit(c.item, read, pushed)
      case 'map':
        visit(c.key, read, pushed)
        return visit(c.value, read, pushed)
      case 'union':
        for (const member of c.members) visit(member, read, pushed)
        return
      case 'shape': {
        let binding = crossing.get(c.name)
        if (binding && (!read || binding.read) && (!pushed || binding.pushed)) return
        const shape = shapes.get(c.name)!
        if (!binding) {
          binding = { shape, fields: [], read: false, pushed: false }
          crossing.set(c.name, binding)
          binding.fields = shape.fields.map((field) => {
            const it = codec(parseLuaType(field.type), `${shape.name}.${field.name}`)
            // A field of any type may be left out, like any field nil is allowed in.
            return {
              name: field.name,
              codec: it.kind === 'any' ? { kind: 'optional' as const, inner: it } : it,
              doc: field.doc,
            }
          })
        }
        binding.read ||= read
        binding.pushed ||= pushed
        for (const field of binding.fields) visit(field.codec, read, pushed)
        return
      }
    }
  }
  for (const binding of classes)
    for (const fn of binding.functions) {
      for (const param of fn.params) visit(param.codec, true, false)
      if (fn.returns.kind === 'one' || fn.returns.kind === 'async')
        visit(fn.returns.codec, false, true)
      if (fn.returns.kind === 'tuple') for (const it of fn.returns.codecs) visit(it, false, true)
    }
  for (const cls of eventedClasses(spec))
    for (const event of cls.events) {
      if (!event.payload) continue
      const payload = shapes.get(event.payload)
      if (!payload) throw new Error(`${cls.name} ${event.name}: no payload shape ${event.payload}`)
      visit({ kind: 'shape', name: payload.name }, false, true)
      // A writable field is read back from whatever a handler left there.
      for (const name of event.writable ?? []) {
        const field = crossing.get(payload.name)!.fields.find((it) => it.name === name)
        if (!field)
          throw new Error(`${cls.name} ${event.name}: no payload field ${name} to make writable`)
        if (field.codec.kind === 'function')
          throw new Error(`${cls.name} ${event.name}: a writable field can't be a function`)
        visit(field.codec, true, false)
      }
    }
  const commandArguments = (spec.commandArguments ?? []).map((type) => {
    const it = typed(type.value, `command argument ${type.name}`)
    if (it.kind === 'optional')
      throw new Error(
        `command argument ${type.name}: its value is never nil (a default of false is)`,
      )
    visit(it, true, false)
    return { type, codec: it }
  })
  const ordered = spec.shapes.flatMap((it) => {
    const binding = crossing.get(it.name)
    return binding ? [binding] : []
  })
  return { classes, shapes: ordered, unions, commandArguments }
}

/** The shapes a codec names directly or through lists, maps, optionals and unions: not through other shapes. */
function shapesNamed(c: Codec): string[] {
  switch (c.kind) {
    case 'optional':
      return shapesNamed(c.inner)
    case 'list':
      return shapesNamed(c.item)
    case 'map':
      return shapesNamed(c.value)
    case 'union':
      return c.members.flatMap(shapesNamed)
    case 'shape':
      return [c.name]
    default:
      return []
  }
}

/**
 * Every shape some other shape (or itself) reaches through its fields, however deep:
 * `Subcommand` reaches itself, through its `subcommands`. A shape that holds itself is a
 * data class like any, but its codec can't name the codec it's part of while that is still
 * being built, so the Kotlin emitter names such a codec lazily.
 */
export function reachable(shapes: ShapeBinding[]): Map<string, Set<string>> {
  const byName = new Map(shapes.map((it) => [it.shape.name, it]))
  const out = new Map<string, Set<string>>()
  for (const shape of shapes) {
    const seen = new Set<string>()
    const walk = (name: string) => {
      for (const field of byName.get(name)!.fields)
        for (const next of shapesNamed(field.codec))
          if (!seen.has(next)) {
            seen.add(next)
            walk(next)
          }
    }
    walk(shape.shape.name)
    out.set(shape.shape.name, seen)
  }
  return out
}

/**
 * Whether reading [codec] leaves a value on the Lua stack: an `any` or `table` taken is the
 * Lua value itself, read while the call lasts, and so is anything holding one, however deep.
 * A shape's own codec says so as a constant, worked out here once, so one that holds itself
 * needn't ask itself.
 */
export function pins(
  codec: Codec,
  shapes: Map<string, ShapeBinding>,
  seen = new Set<string>(),
): boolean {
  switch (codec.kind) {
    case 'any':
      return true
    case 'optional':
      return pins(codec.inner, shapes, seen)
    case 'list':
      return pins(codec.item, shapes, seen)
    case 'map':
      return pins(codec.key, shapes, seen) || pins(codec.value, shapes, seen)
    case 'union':
      return codec.members.some((it) => pins(it, shapes, seen))
    case 'shape': {
      if (seen.has(codec.name)) return false
      seen.add(codec.name)
      return shapes.get(codec.name)!.fields.some((it) => pins(it.codec, shapes, seen))
    }
    default:
      return false
  }
}

/**
 * A table a hand-written function (`impl: "lua"`) takes, for the prelude's
 * strict check: every field's kind (what Lua's `type()` says, or `integer`,
 * `Vec3` or `any`), and the fields that must be there.
 */
export interface CheckedShape {
  name: string
  fields: { name: string; kind: string }[]
  required: string[]
}

/**
 * Every shape a hand-written function's parameters reach (`nf.commands.register`'s
 * definition, and the argument and subcommand tables inside it), so the prelude
 * checks them against the spec's fields rather than a list of its own.
 */
export function handwrittenShapes(spec: ApiSpec): CheckedShape[] {
  const shapes = new Map(spec.shapes.map((it) => [it.name, it]))
  const values = new Set((spec.values ?? []).map((it) => it.name))
  // An alias is checked as the primitive it is.
  const aliases = new Map((spec.aliases ?? []).map((it) => [it.name, it.type]))
  const out = new Map<string, CheckedShape>()
  const kind = (node: TypeNode, where: string, visit: (name: string) => void): string => {
    switch (node.kind) {
      case 'primitive':
        if (
          ['string', 'number', 'integer', 'boolean', 'any', 'table', 'function'].includes(node.name)
        )
          return node.name
        throw new Error(`${where}: no check for ${node.name}`)
      case 'literal':
        return 'string'
      case 'optional':
        return kind(node.type, where, visit)
      case 'fun':
        return 'function'
      case 'array':
      case 'map': {
        const item = node.kind === 'array' ? node.item : node.value
        if (item.kind === 'named' && shapes.has(item.name)) visit(item.name)
        return 'table'
      }
      case 'named':
        if (values.has(node.name)) return node.name
        if (aliases.has(node.name)) return aliases.get(node.name)!
        if (shapes.has(node.name)) {
          visit(node.name)
          return 'table'
        }
        throw new Error(`${where}: no check for ${node.name}`)
      case 'union':
        if (node.types.every((it) => it.kind === 'literal')) return 'string'
        throw new Error(`${where}: no check for a union of types`)
    }
  }
  const visit = (name: string) => {
    if (out.has(name)) return
    const shape = shapes.get(name)!
    const result: CheckedShape = { name, fields: [], required: [] }
    out.set(name, result)
    for (const field of shape.fields) {
      const node = parseLuaType(field.type)
      result.fields.push({ name: field.name, kind: kind(node, `${name}.${field.name}`, visit) })
      if (!acceptsNil(node)) result.required.push(field.name)
    }
  }
  for (const cls of spec.classes)
    for (const fn of cls.functions)
      if (fn.impl === 'lua')
        for (const param of fn.params) {
          const node = withoutNil(parseLuaType(param.type))
          if (node.kind === 'named' && shapes.has(node.name)) visit(node.name)
        }
  return [...out.values()].sort((a, b) => a.name.localeCompare(b.name))
}

/**
 * How the generated wrapper of a hand-written (`impl: "lua"`) function checks one argument,
 * from its declared type, before the hand-written body runs.
 */
export type ParamCheck =
  /** Lua's own `type()`: `want(x, "string", "x")`. */
  | { kind: 'type'; lua: 'string' | 'number' | 'boolean' | 'function' | 'table' }
  /** A whole number, which arrives as an integer. */
  | { kind: 'integer' }
  /** A handle of the class, or of one below it. */
  | { kind: 'handle'; cls: string }
  /** A genuine `Vec3`. */
  | { kind: 'vec3' }
  /** A table of a spec shape: only its fields, each of its kind (`handwrittenShapes`). */
  | { kind: 'shape'; name: string }
  /** A string that must be one of these. */
  | { kind: 'choice'; choices: string[] }
  /** Nothing the type grammar says is enough (a union of classes, `any`): the body checks it. */
  | { kind: 'body' }

export interface HandwrittenParam {
  name: string
  /** Nil is allowed. */
  optional: boolean
  /** `...`: any number of extra arguments, passed on as they are. */
  vararg: boolean
  check: ParamCheck
}

/** A hand-written function with what its wrapper checks. */
export interface HandwrittenFn {
  fn: Fn
  params: HandwrittenParam[]
}

/**
 * Every hand-written function, by its owner: a handle class or a namespace by name, a value
 * type by name (`Vec3`), and a value's library (`vec3`) by its own. Each argument gets a check
 * its type can express, so the hand-written body only holds logic.
 */
export function handwrittenFunctions(spec: ApiSpec): Map<string, HandwrittenFn[]> {
  const handles = handleClasses(spec)
  const shapes = new Set(spec.shapes.map((it) => it.name))
  const aliases = new Map((spec.aliases ?? []).map((it) => [it.name, it.type]))
  const stringAliases = new Set(eventedClasses(spec).map(eventAlias))
  const checkOf = (node: TypeNode): ParamCheck => {
    switch (node.kind) {
      case 'primitive':
        switch (node.name) {
          case 'string':
          case 'number':
          case 'boolean':
          case 'function':
          case 'table':
            return { kind: 'type', lua: node.name }
          case 'integer':
            return { kind: 'integer' }
          default:
            return { kind: 'body' }
        }
      case 'literal':
        return { kind: 'choice', choices: [node.value] }
      case 'fun':
        return { kind: 'type', lua: 'function' }
      case 'array':
      case 'map':
        return { kind: 'type', lua: 'table' }
      case 'named':
        if (handles.has(node.name)) return { kind: 'handle', cls: node.name }
        if (stringAliases.has(node.name) || aliases.has(node.name))
          return { kind: 'type', lua: 'string' }
        if (node.name === 'Vec3') return { kind: 'vec3' }
        if (shapes.has(node.name)) return { kind: 'shape', name: node.name }
        return { kind: 'body' }
      case 'union':
        if (node.types.every((it) => it.kind === 'literal'))
          return {
            kind: 'choice',
            choices: node.types.map((it) => (it.kind === 'literal' ? it.value : '')),
          }
        return { kind: 'body' }
      default:
        return { kind: 'body' }
    }
  }
  const bind = (fn: Fn): HandwrittenFn => ({
    fn,
    params: fn.params.map((param): HandwrittenParam => {
      const node = parseLuaType(param.type)
      const any = node.kind === 'primitive' && node.name === 'any'
      return {
        name: param.name,
        optional: !!param.optional || (acceptsNil(node) && !any),
        vararg: param.name === '...',
        check: param.name === '...' || any ? { kind: 'body' } : checkOf(withoutNil(node)),
      }
    }),
  })
  const out = new Map<string, HandwrittenFn[]>()
  const add = (owner: string, fns: Fn[]) => {
    const lua = fns.filter((fn) => fn.impl === 'lua')
    if (lua.length) out.set(owner, lua.map(bind))
  }
  for (const cls of spec.classes) add(cls.name, cls.functions)
  for (const value of spec.values ?? []) {
    add(value.name, value.functions)
    if (value.library) add(value.library.name, value.library.functions)
  }
  return out
}

/** `play_animation` → `playAnimation`. */
export function camel(name: string): string {
  return name.replace(/[_.]([a-z0-9])/g, (_, c: string) => c.toUpperCase())
}

/** `Centity` → `Centity`, `nf` → `Nf`. */
export function pascal(name: string): string {
  return name[0]!.toUpperCase() + camel(name.slice(1))
}
