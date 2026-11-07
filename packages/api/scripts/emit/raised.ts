/**
 * The server's events, from the adapter to the scripts: everything an `nf` event's `raised`
 * says, written out so that adding an event is the spec and one mapping in the adapter.
 *
 *  - `platform/GameEvents.kt`: the sink the adapter calls (`GameEvents`, a method per raised
 *    event), each payload as a data class in the platform's own types (`GameEvent.<Name>`: a
 *    `PlayerRef` for a `Player`, a `UUID` for an entity, a `BlockRef` for a `Block`, a world's
 *    name for a `World`; a writable field a `var` the dispatch writes back into, a lazy one a
 *    function), `WatchedEvent`, and two plain `GameEvents` for a fake server or a recorder
 *    (`GameEventDelivery`, `GameEventFunnel`).
 *  - `api/GameEventDispatch.kt`: the runtime's side of the sink: each event's handles made
 *    (through `EventSession`, which the runtime implements), its path walked (the handle in
 *    `raised.first`, then `nf`), its payload built only when something listens, and its
 *    writable fields read back into the platform's payload within their bounds.
 *
 * A type with no platform counterpart in a raised event's payload, a writable field there's no
 * way back for, or a `raised` that contradicts its event is an error at `pnpm generate`.
 */
import type { ApiSpec, EventSpec, Raised } from '../../src/types.ts'
import { GENERATED_BY } from './common.ts'
import { camel, classEvents, type Bindings, type Codec, type ShapeBinding } from './bindings.ts'
import { constant, finish, ident, kdoc } from './kotlin.ts'

/** A handle class as the platform hands it over, and the `EventSession` function making its handle. */
const PLATFORM_HANDLES: Record<string, { type: string; make: string }> = {
  Player: { type: 'PlayerRef', make: 'player' },
  Entity: { type: 'UUID', make: 'entity' },
  Living: { type: 'UUID', make: 'living' },
  Mob: { type: 'UUID', make: 'mob' },
  Block: { type: 'BlockRef', make: 'block' },
  World: { type: 'String', make: 'world' },
  Inventory: { type: 'InventoryRef', make: 'inventory' },
}

/** A value type or shape the platform has its own type for, and the `EventSession` function making the Lua one (none: the same). */
const PLATFORM_VALUES: Record<string, { type: string; make?: string }> = {
  Location: { type: 'Location', make: 'location' },
  Item: { type: 'ItemData' },
  StatusEffect: { type: 'StatusEffectData', make: 'statusEffect' },
  // MiniMessage with its glyph tags as the server names them: the codec respells them for each script.
  Text: { type: 'String' },
}

/** The classes a handle stage can be on, and how its target comes from the field holding it. */
const ENTITY_STAGES = new Set(['Entity', 'Living', 'Mob', 'Player'])

/** One payload field as the platform has it. */
interface PlatformField {
  name: string
  kotlin: string
  codec: Codec
  doc: string
  /** Its platform type, a function's result for a lazy one. */
  type: string
  writable: boolean
  lazy: boolean
}

/** A payload as the platform has it: `GameEvent.<name>`. */
interface PlatformPayload {
  shape: ShapeBinding
  name: string
  fields: PlatformField[]
}

/** An `nf` event the server raises, worked out. */
export interface RaisedEvent {
  event: EventSpec
  raised: Raised
  /** The sink's method: `playerChangeFood`. */
  method: string
  payload: PlatformPayload
  /** The handle it's heard on first: its class's event constant, and the field holding it. */
  first?: { cls: string; constant: string; field: PlatformField }
  /** `Events.NF_…`. */
  constant: string
  /** Its `WatchedEvent`, when it's watched. */
  watched?: string
}

/** `PlayerChangeFoodEvent` → `PlayerChangeFood`: its data class in `GameEvent`. */
const platformName = (shape: string) => shape.replace(/Event$/, '')

/** The platform's type for [codec], or an error naming [where]. */
function platformType(codec: Codec, where: string): string {
  switch (codec.kind) {
    case 'string':
    case 'choice':
      return 'String'
    case 'number':
      return 'Double'
    case 'integer':
      return 'Int'
    case 'boolean':
      return 'Boolean'
    case 'optional': {
      const inner = platformType(codec.inner, where)
      return inner.endsWith('?') ? inner : `${inner}?`
    }
    case 'list':
      return `List<${platformType(codec.item, where)}>`
    case 'map':
      return `Map<${platformType(codec.key, where)}, ${platformType(codec.value, where)}>`
    case 'handle': {
      const it = PLATFORM_HANDLES[codec.cls.name]
      if (it) return it.type
      break
    }
    case 'runtime':
    case 'shape': {
      const it = PLATFORM_VALUES[codec.name]
      if (it) return it.type
      break
    }
  }
  throw new Error(
    `${where}: a raised event's payload can't hold ${describe(codec)}: the platform has no type for it`,
  )
}

function describe(codec: Codec): string {
  switch (codec.kind) {
    case 'handle':
      return `a ${codec.cls.name}`
    case 'runtime':
    case 'shape':
    case 'union':
      return `a ${codec.name}`
    default:
      return `a ${codec.kind}`
  }
}

/** Every `nf` event with `raised`, checked against its payload and the classes it names. */
export function raisedEvents(spec: ApiSpec, bound: Bindings): RaisedEvent[] {
  const nf = spec.classes.find((it) => it.name === 'nf')
  const shapes = new Map(bound.shapes.map((it) => [it.shape.name, it]))
  const raised = (nf?.events ?? []).filter(
    (it): it is EventSpec & { raised: Raised } => !!it.raised,
  )
  for (const cls of spec.classes)
    if (cls !== nf)
      for (const event of cls.events ?? [])
        if (event.raised)
          throw new Error(`${cls.name} ${event.name}: only an nf event says how it's raised`)

  // A payload is one data class for every event that raises it: what's lazy and writable is the sum.
  const lazy = new Map<string, string>()
  const writable = new Map<string, Set<string>>()
  for (const event of raised) {
    const where = `nf ${event.name}`
    if (!event.payload) throw new Error(`${where}: a raised event needs a payload`)
    for (const name of event.raised.lazy ?? []) {
      if (event.writable?.includes(name))
        throw new Error(`${where}: ${name} can't be lazy and writable`)
    }
    const key = [...(event.raised.lazy ?? [])].sort().join(',')
    const before = lazy.get(event.payload)
    if (before !== undefined && before !== key)
      throw new Error(
        `${where}: every event raising ${event.payload} must say the same fields are lazy`,
      )
    lazy.set(event.payload, key)
    const fields = writable.get(event.payload) ?? new Set()
    for (const name of event.writable ?? []) fields.add(name)
    writable.set(event.payload, fields)
  }

  const payloads = new Map<string, PlatformPayload>()
  const payloadOf = (name: string, where: string): PlatformPayload => {
    let it = payloads.get(name)
    if (it) return it
    const shape = shapes.get(name)
    if (!shape) throw new Error(`${where}: no payload shape ${name}`)
    const lazyFields = new Set(lazy.get(name)!.split(',').filter(Boolean))
    const fields = shape.fields.map((field): PlatformField => {
      const type = platformType(field.codec, `${name}.${field.name}`)
      return {
        name: field.name,
        kotlin: ident(camel(field.name)),
        codec: field.codec,
        doc: field.doc,
        type: lazyFields.has(field.name) ? `() -> ${type}` : type,
        writable: writable.get(name)!.has(field.name),
        lazy: lazyFields.has(field.name),
      }
    })
    for (const field of lazyFields)
      if (!fields.some((it) => it.name === field))
        throw new Error(`${where}: no payload field ${field} to make lazy`)
    it = { shape, name: platformName(name), fields }
    payloads.set(name, it)
    return it
  }

  return raised.map((event): RaisedEvent => {
    const where = `nf ${event.name}`
    const payload = payloadOf(event.payload!, where)
    for (const [name, bounds] of Object.entries(event.raised.bounds ?? {})) {
      const field = payload.fields.find((it) => it.name === name)
      if (!field?.writable || !event.writable?.includes(name))
        throw new Error(`${where}: bounds for ${name}, which isn't a writable field`)
      if (field.codec.kind !== 'number' && field.codec.kind !== 'integer')
        throw new Error(`${where}: bounds for ${name}, which isn't a number`)
      if (bounds.min !== undefined && bounds.max !== undefined && bounds.min > bounds.max)
        throw new Error(`${where}: ${name}'s bounds are the wrong way round`)
    }
    const first = event.raised.first ? firstStage(spec, event, payload, where) : undefined
    return {
      event,
      raised: event.raised,
      method: camel(event.name),
      payload,
      ...(first ? { first } : {}),
      constant: `NF_${constant(event.name)}`,
      ...(event.raised.watched ? { watched: constant(event.name) } : {}),
    }
  })
}

function firstStage(spec: ApiSpec, event: EventSpec, payload: PlatformPayload, where: string) {
  const { class: name, event: eventName, field: fieldName } = event.raised!.first!
  const cls = spec.classes.find((it) => it.name === name)
  if (!cls) throw new Error(`${where}: heard first on ${name}, which isn't a class`)
  const own = classEvents(spec, cls).find((it) => it.event.name === eventName)
  if (!own)
    throw new Error(`${where}: heard first as ${name} ${eventName}, which ${name} doesn't have`)
  if (own.event.payload !== event.payload)
    throw new Error(
      `${where}: ${name} ${eventName} has another payload (${own.event.payload ?? 'none'})`,
    )
  const field = payload.fields.find((it) => it.name === fieldName)
  if (!field) throw new Error(`${where}: no payload field ${fieldName} to hear it on first`)
  if (field.lazy) throw new Error(`${where}: ${fieldName} is heard on first, so it can't be lazy`)
  const inner = field.codec.kind === 'optional' ? field.codec.inner : field.codec
  const holds =
    inner.kind === 'handle' ? inner.cls.name : inner.kind === 'runtime' ? inner.name : undefined
  const fits =
    name === 'World'
      ? holds === 'World' || holds === 'Block' || holds === 'Location'
      : ENTITY_STAGES.has(name) &&
        inner.kind === 'handle' &&
        (name === 'Player'
          ? holds === 'Player'
          : inner.cls.chain.includes('Entity') && holds !== 'Player')
  if (!fits)
    throw new Error(
      `${where}: ${fieldName} (${describe(field.codec)}) can't be heard on as a ${name}`,
    )
  return { cls: name, constant: `${constant(own.owner.name)}_${constant(eventName)}`, field }
}

// ---- platform/GameEvents.kt ---------------------------------------------------------

function platformHeader(): string[] {
  return [
    `// ${GENERATED_BY}`,
    '',
    'package dev.netherforge.plugin.platform',
    '',
    'import java.util.UUID',
    '',
  ]
}

/** The sink's method for [it]: what it returns says whether it ended cancelled. */
const sinkSignature = (it: RaisedEvent) =>
  `fun ${it.method}(event: GameEvent.${it.payload.name})${it.event.cancellable ? ': Boolean' : ''}`

function sinkDoc(it: RaisedEvent): string {
  const notes: string[] = [it.event.doc]
  if (it.event.cancellable) notes.push('True when it ended cancelled.')
  const writable = it.event.writable ?? []
  if (writable.length)
    notes.push(
      `What handlers made of ${writable.map((name) => `\`${camel(name)}\``).join(', ')} is written back into [event].`,
    )
  if (it.watched) notes.push(`Only while [WatchedEvent.${it.watched}] is watched.`)
  return notes.join(' ')
}

export function platformKotlin(events: RaisedEvent[]): string {
  const payloads = [...new Map(events.map((it) => [it.payload.name, it.payload])).values()]
  const watched = events.filter((it) => it.watched)
  const lines = [
    ...platformHeader(),
    '/**',
    " * The server's events scripts hear, as the adapter reports them: it maps the server's event",
    ' * to the payload ([GameEvent]), calls the method, and applies what came back (a cancellable',
    " * one's answer, and the writable fields' values, written back into the payload). The",
    ' * runtime implements it (`GameEventDispatch`). On the main thread unless the event says',
    ' * otherwise.',
    ' */',
    'interface GameEvents {',
  ]
  events.forEach((it, k) => {
    if (k) lines.push('')
    lines.push(...kdoc(sinkDoc(it), '    '), `    ${sinkSignature(it)}`)
  })
  lines.push(
    '}',
    '',
    '/**',
    " * An event's payload as the platform hands it over: in its own types (a [PlayerRef], an",
    " * entity's [UUID], a [BlockRef], a world's name), a writable field a `var` the runtime writes",
    ' * back into, and a field costly to work out a function called only when a script listens.',
    ' */',
    'sealed interface GameEvent {',
  )
  payloads.forEach((payload, k) => {
    if (k) lines.push('')
    lines.push(...kdoc(payload.shape.shape.doc, '    '), `    data class ${payload.name}(`)
    payload.fields.forEach((field, j) => {
      lines.push(
        ...kdoc(field.doc, '        '),
        `        ${field.writable ? 'var' : 'val'} ${field.kotlin}: ${field.type}${j === payload.fields.length - 1 ? '' : ','}`,
      )
    })
    lines.push('    ) : GameEvent')
  })
  lines.push(
    '}',
    '',
    '/**',
    ' * The events the server raises all the time, which the adapter listens for only while a',
    ' * script does: the runtime says when ([Platform.watch]).',
    ' */',
    'enum class WatchedEvent {',
  )
  watched.forEach((it, k) => {
    lines.push(
      ...kdoc(`[GameEvents.${it.method}].`, '    '),
      `    ${it.watched}${k === watched.length - 1 ? '' : ','}`,
    )
    if (k !== watched.length - 1) lines.push('')
  })
  lines.push(
    '}',
    '',
    '/**',
    ' * [sink] as a server delivers to it, for a fake one: a watched event only while it is in',
    ' * [watched], every other event always; nothing, and nothing cancelled, while there is no',
    ' * sink.',
    ' */',
    'class GameEventDelivery(private val watched: Set<WatchedEvent>, private val sink: () -> GameEvents?) : GameEvents {',
  )
  events.forEach((it, k) => {
    if (k) lines.push('')
    const call = `sink()?.${it.method}(event)`
    const gate = it.watched ? `WatchedEvent.${it.watched} in watched && ` : ''
    if (it.event.cancellable)
      lines.push(`    override ${sinkSignature(it)} = ${gate}${call} == true`)
    else if (it.watched)
      lines.push(
        `    override ${sinkSignature(it)} {`,
        `        if (WatchedEvent.${it.watched} in watched) ${call}`,
        '    }',
      )
    else lines.push(`    override ${sinkSignature(it)} {`, `        ${call}`, '    }')
  })
  lines.push(
    '}',
    '',
    '/**',
    " * Every event through one function, by its method's name: [heard] says whether to cancel",
    ' * a cancellable one. The payload is left as it was: nothing a handler would change.',
    ' */',
    'class GameEventFunnel(private val heard: (method: String, event: GameEvent) -> Boolean) : GameEvents {',
  )
  events.forEach((it, k) => {
    if (k) lines.push('')
    const call = `heard(${JSON.stringify(it.method)}, event)`
    if (it.event.cancellable) lines.push(`    override ${sinkSignature(it)} = ${call}`)
    else lines.push(`    override ${sinkSignature(it)} {`, `        ${call}`, '    }')
  })
  lines.push('}')
  return finish(lines)
}

// ---- api/GameEventDispatch.kt -------------------------------------------------------
