/**
 * The server's events for the Rust runtime: `raised.ts`'s two halves as Rust, from the same
 * worked-out events (`raisedEvents`).
 *
 *  - `crates/platform/src/api/game_events.rs`: the sink the adapter calls (`GameEvents`, a
 *    method per raised event, a cancellable one answering whether it ended cancelled), each
 *    payload a struct in the platform's own types (`game_event::PlayerJoin`: a `PlayerRef` for a
 *    `Player`, a `Uuid` for an entity, a `BlockRef`, a world's name; a writable field written back
 *    through `&mut`, a lazy one a closure), and `WatchedEvent`.
 *  - `crates/runtime/src/generated/game_event_dispatch.rs`: the runtime's side
 *    (`GameEventDispatch`, over the runtime's `EventSource`): the session opened once per event,
 *    its `Around` hook (what the runtime does around the event, defaulting to nothing) given the
 *    raise: each event's handles made, its path walked (the handle in `raised.first`, then `nf`),
 *    its payload built only when something listens, and its writable fields read back into the
 *    platform's payload within their bounds.
 */
import type { Codec } from './bindings.ts'
import { GENERATED_BY } from './common.ts'
import type { RaisedEvent } from './raised.ts'
import { codecExpr, finish, ident, rustdoc } from './rust.ts'

/** A handle class as the platform hands it over, and the `EventSession` function making its handle. */
const PLATFORM_HANDLES: Record<string, { type: string; make: string; byRef: boolean }> = {
  Player: { type: 'PlayerRef', make: 'player', byRef: true },
  Entity: { type: 'Uuid', make: 'entity', byRef: false },
  Living: { type: 'Uuid', make: 'living', byRef: false },
  Mob: { type: 'Uuid', make: 'mob', byRef: false },
  Block: { type: 'BlockRef', make: 'block', byRef: true },
  World: { type: 'String', make: 'world', byRef: true },
  Inventory: { type: 'InventoryRef', make: 'inventory', byRef: true },
}

/** A value type or shape the platform has its own type for, and the `EventSession` function making the Lua one. */
const PLATFORM_VALUES: Record<string, { type: string; make: string }> = {
  Location: { type: 'Location', make: 'location' },
  Item: { type: 'ItemData', make: 'item' },
  StatusEffect: { type: 'StatusEffectData', make: 'status_effect' },
  Text: { type: 'String', make: 'text' },
}

const snake = (name: string) =>
  name
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .replace(/([A-Z])([A-Z][a-z])/g, '$1_$2')
    .toLowerCase()

/** The platform's Rust type for [codec]. */
function platformRust(codec: Codec): string {
  switch (codec.kind) {
    case 'string':
    case 'choice':
      return 'String'
    case 'number':
      return 'f64'
    case 'integer':
      return 'i32'
    case 'boolean':
      return 'bool'
    case 'optional':
      return `Option<${platformRust(codec.inner)}>`
    case 'list':
      return `Vec<${platformRust(codec.item)}>`
    case 'map':
      return `HashMap<${platformRust(codec.key)}, ${platformRust(codec.value)}>`
    case 'handle':
      return PLATFORM_HANDLES[codec.cls.name]!.type
    case 'runtime':
    case 'shape':
      return PLATFORM_VALUES[codec.name]!.type
    default:
      throw new Error(`no platform type for a ${codec.kind}`)
  }
}

/** A Rust expression turning the platform's [expr] (a reference, `&T`) into what the Lua payload holds. */
function toLua(codec: Codec, reference: string, depth = 0): string {
  const it = `x${depth}`
  const expr = /^[a-z0-9_]+$/.test(reference) ? reference : `(${reference})`
  switch (codec.kind) {
    case 'string':
    case 'choice':
      return `${expr}.clone()`
    case 'number':
    case 'boolean':
      return `*${expr}`
    case 'integer':
      return `i64::from(*${expr})`
    case 'handle':
      return `s.${PLATFORM_HANDLES[codec.cls.name]!.make}(${expr})`
    case 'runtime':
    case 'shape':
      return `s.${PLATFORM_VALUES[codec.name]!.make}(${expr})`
    case 'optional':
      return `${expr}.as_ref().map(|${it}| ${toLua(codec.inner, it, depth + 1)})`
    case 'list':
      return `${expr}.iter().map(|${it}| ${toLua(codec.item, it, depth + 1)}).collect()`
    case 'map':
      return `${expr}.iter().map(|(k${depth}, v${depth})| (${toLua(codec.key, `k${depth}`, depth + 1)}, ${toLua(codec.value, `v${depth}`, depth + 1)})).collect()`
    default:
      throw new Error(`no way to Lua for a ${codec.kind}`)
  }
}

/** A Rust expression turning what a handler left ([lua], owned) back into the platform's, given [was] (`&T`). */
function fromLua(
  codec: Codec,
  lua: string,
  was: string,
  bounds: { min?: number; max?: number } | undefined,
): string {
  const float = (n: number) => (Number.isInteger(n) ? `${n}.0` : `${n}`)
  switch (codec.kind) {
    case 'string':
    case 'choice':
    case 'boolean':
      return lua
    case 'number': {
      const { min, max } = bounds ?? {}
      if (min !== undefined && max !== undefined)
        return `${lua}.clamp(${float(min)}, ${float(max)})`
      if (min !== undefined) return `${lua}.max(${float(min)})`
      if (max !== undefined) return `${lua}.min(${float(max)})`
      return lua
    }
    case 'integer': {
      const min = bounds?.min !== undefined ? `${bounds.min}` : 'i64::from(i32::MIN)'
      const max = bounds?.max !== undefined ? `${bounds.max}` : 'i64::from(i32::MAX)'
      return `${lua}.clamp(${min}, ${max}) as i32`
    }
    case 'runtime':
      if (codec.name === 'Text') return `${lua}.0`
      if (codec.name === 'Location') return `s.placed(${lua}, ${was})`
      if (codec.name === 'Item') return `s.item_back(${lua}, ${was})`
      break
    case 'optional':
      if (
        codec.inner.kind === 'string' ||
        codec.inner.kind === 'choice' ||
        codec.inner.kind === 'boolean'
      )
        return lua
      if (codec.inner.kind === 'runtime' && codec.inner.name === 'Text')
        return `${lua}.map(|t| t.0)`
      if (codec.inner.kind === 'runtime' && codec.inner.name === 'Item')
        return `s.item_back_opt(${lua}, ${was})`
      break
    case 'list':
      if (codec.item.kind === 'runtime' && codec.item.name === 'Item')
        return `s.items_back(${lua}, ${was})`
      break
  }
  throw new Error(`no way back to the platform for a writable ${codec.kind}`)
}

/** The sink's method for [it]. */
const sinkSignature = (it: RaisedEvent) =>
  `fn ${snake(it.method)}(&self, event: &mut game_event::${it.payload.name})${it.event.cancellable ? ' -> bool' : ''}`

function sinkDoc(it: RaisedEvent): string {
  const notes: string[] = [it.event.doc]
  if (it.event.cancellable) notes.push('True when it ended cancelled.')
  const writable = it.event.writable ?? []
  if (writable.length)
    notes.push(
      `What handlers made of ${writable.map((n) => `\`${n}\``).join(', ')} is written back into \`event\`.`,
    )
  if (it.watched) notes.push(`Only while \`WatchedEvent::${pascal(it.watched)}\` is watched.`)
  return notes.join(' ')
}

const pascal = (constant: string) =>
  constant
    .toLowerCase()
    .split('_')
    .map((p) => p.charAt(0).toUpperCase() + p.slice(1))
    .join('')

export function gameEventsRust(events: RaisedEvent[]): string {
  const payloads = [...new Map(events.map((it) => [it.payload.name, it.payload])).values()]
  const watched = events.filter((it) => it.watched)
  const lines = [
    `// ${GENERATED_BY}`,
    '',
    "//! The server's events scripts hear, as the adapter reports them: it maps the server's event",
    '//! to the payload (`game_event`), calls the method, and applies what came back (a cancellable',
    "//! one's answer, and the writable fields' values, written back into the payload). The",
    '//! runtime implements it (`GameEventDispatch`). On the main thread unless the event says',
    '//! otherwise.',
    '',
    '#![allow(clippy::all, unused_imports)]',
    '',
    'use std::collections::HashMap;',
    '',
    'use crate::{BlockRef, InventoryRef, ItemData, Location, PlayerRef, StatusEffectData, Uuid};',
    '',
    "/// The server's events, a method per event the runtime raises to scripts.",
    'pub trait GameEvents {',
  ]
  events.forEach((it, k) => {
    if (k) lines.push('')
    lines.push(...rustdoc(sinkDoc(it), '    '), `    ${sinkSignature(it)};`)
  })
  lines.push(
    '}',
    '',
    "/// An event's payload as the platform hands it over: in its own types, a writable field written",
    '/// back into, and a field costly to work out a closure called only when a script listens.',
    'pub mod game_event {',
    '    use super::*;',
  )
  for (const payload of payloads) {
    lines.push('', ...rustdoc(payload.shape.shape.doc, '    '), `    pub struct ${payload.name} {`)
    for (const field of payload.fields) {
      const type = platformRust(field.codec)
      lines.push(
        ...rustdoc(field.doc, '        '),
        `        pub ${ident(field.name)}: ${field.lazy ? `Box<dyn Fn() -> ${type}>` : type},`,
      )
    }
    lines.push('    }')
  }
  lines.push(
    '}',
    '',
    '/// The events the server raises all the time, which the adapter listens for only while a',
    '/// script does: the runtime says when (`Platform::watch`).',
    '#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]',
    'pub enum WatchedEvent {',
    ...watched.map(
      (it) => `    /// \`GameEvents::${snake(it.method)}\`.\n    ${pascal(it.watched!)},`,
    ),
    '}',
    '',
    'impl WatchedEvent {',
    '    pub const ALL: &[WatchedEvent] = &[',
    ...watched.map((it) => `        WatchedEvent::${pascal(it.watched!)},`),
    '    ];',
    '}',
  )
  return finish(lines)
}

/** `(class, event)` of a stage: its target's class and the event's name there. */
function stages(it: RaisedEvent): { first?: [string, string]; nf: [string, string] } {
  const nf: [string, string] = ['nf', it.event.name]
  if (!it.first) return { nf }
  const own = it.first.constant
  // `PLAYER_JOIN` of class `Player`: the event's name is its constant past the class's.
  const name = it.raised.first!.event
  void own
  return { first: [it.first.cls, name], nf }
}

/** The event's payload type and what raising it returns. */
const payloadType = (it: RaisedEvent) => `game_event::${it.payload.name}`
const returns = (it: RaisedEvent) => (it.event.cancellable ? ' -> bool' : '')

/** `GameEvents`' method: the session opened once, then its `Around` hook given the raise. */
function dispatchMethod(it: RaisedEvent): string[] {
  const unchanged = it.event.cancellable ? 'false' : ''
  const name = snake(it.method)
  return [
    `${sinkSignature(it)} {`,
    `    let Some(s) = self.0.open() else { return ${unchanged}; };`,
    `    s.${name}(event, &|event| ${name}(&s, event))`,
    '}',
  ]
}

/** `Around`'s method: by default only the raise. */
function aroundMethod(it: RaisedEvent): string[] {
  const name = snake(it.method)
  const raise = `&dyn Fn(&mut ${payloadType(it)})${returns(it)}`
  return [
    `fn ${name}(&self, event: &mut ${payloadType(it)}, raise: ${raise})${returns(it)} {`,
    '    raise(event)',
    '}',
  ]
}

/** Raising one event to an open session's scripts. */
function raiseFunction(it: RaisedEvent): string[] {
  const unchanged = it.event.cancellable ? 'return false;' : 'return;'
  const body: string[] = []
  const { first, nf } = stages(it)
  const nfStage = `(${JSON.stringify(nf[1])}, None)`
  if (first && it.first) {
    const field = it.first.field
    const optional = field.codec.kind === 'optional'
    const inner = optional && field.codec.kind === 'optional' ? field.codec.inner : field.codec
    const held =
      inner.kind === 'handle' ? inner.cls.name : inner.kind === 'runtime' ? inner.name : ''
    const value = optional ? 'x0' : `&event.${ident(field.name)}`
    const make =
      it.first.cls === 'World'
        ? held === 'World'
          ? `s.world(${value})`
          : `s.world(&${optional ? 'x0' : `event.${ident(field.name)}`}.world)`
        : `s.${PLATFORM_HANDLES[it.first.cls]!.make}(${value})`
    body.push(
      optional
        ? `let first = event.${ident(field.name)}.as_ref().map(|x0| ${make});`
        : `let first = ${make};`,
    )
    const stage = `(${JSON.stringify(first[1])}, Some(Handle::${it.first.cls}(first.clone())))`
    body.push(
      optional
        ? `let mut stages = Vec::new(); if let Some(first) = &first { stages.push((${JSON.stringify(first[1])}, Some(Handle::${it.first.cls}(first.clone())))); } stages.push(${nfStage});`
        : `let stages = [${stage}, ${nfStage}];`,
    )
  } else {
    body.push(`let stages = [${nfStage}];`)
  }
  body.push(`if !s.listening(&stages) { ${unchanged} }`)
  const shape = it.payload.shape.shape.name
  body.push(`let payload = shapes::${shape} {`)
  for (const field of it.payload.fields) {
    const value = field.lazy ? `&(event.${ident(field.name)})()` : `&event.${ident(field.name)}`
    const inner = field.codec.kind === 'optional' ? field.codec.inner : field.codec
    const reuse =
      it.first?.field === field && inner.kind === 'handle' && inner.cls.name === it.first.cls
    body.push(`    ${ident(field.name)}: ${reuse ? 'first.clone()' : toLua(field.codec, value)},`)
  }
  body.push('};')
  body.push(`let Some(out) = s.emit(&stages, codecs::${shape}, payload) else { ${unchanged} };`)
  for (const name of it.event.writable ?? []) {
    const field = it.payload.fields.find((f) => f.name === name)!
    body.push(
      `if let Some(v) = s.read(&out, ${JSON.stringify(name)}, ${codecExpr(field.codec)}) { event.${ident(name)} = ${fromLua(field.codec, 'v', `&event.${ident(name)}`, it.raised.bounds?.[name])}; }`,
    )
  }
  if (it.event.cancellable) body.push('out.cancelled')
  return [
    `fn ${snake(it.method)}<S: EventSession>(s: &S, event: &mut ${payloadType(it)})${returns(it)} {`,
    ...body.map((l) => `    ${l}`),
    '}',
  ]
}

export function dispatchRust(events: RaisedEvent[]): string {
  const watched = events.filter((it) => it.watched)
  const lines = [
    `// ${GENERATED_BY}`,
    '',
    "//! The runtime's side of `GameEvents`: each event raised to the scripts of a session, on the",
    "//! handle the spec says it's heard on first and then on `nf`, with its payload built only when",
    '//! something listens, and what handlers left in its writable fields written back into the',
    "//! platform's payload (kept within the spec's bounds). Unchanged while no project runs.",
    '',
    '#![allow(clippy::all, unused_imports, unused_variables, unused_parens)]',
    '',
    'use netherforge_api::codec::*;',
    'use netherforge_api::handles::{self, Handle};',
    'use netherforge_api::{codecs, shapes, values};',
    'use netherforge_platform::{game_event, GameEvents, WatchedEvent};',
    '',
    'use crate::events::EventSession;',
    '',
    '/// Where the dispatch finds the session running now, opened once per event: none while no',
    "/// project runs (or it can't be reached now), and every event then goes through unchanged.",
    'pub trait EventSource {',
    "    type Open<'b>: EventSession + Around where Self: 'b;",
    "    fn open(&self) -> Option<Self::Open<'_>>;",
    '}',
    '',
    "/// What the runtime does around an event besides raising it to scripts (the Kotlin runtime's",
    '/// `RuntimeGameEvents` overrides): a service hears of it before or after, or adds to its answer.',
    '/// `raise` raises it; each defaults to only that.',
    'pub trait Around {',
  ]
  events.forEach((it, k) => {
    if (k) lines.push('')
    lines.push(...aroundMethod(it).map((l) => `    ${l}`))
  })
  lines.push(
    '}',
    '',
    "/// `GameEvents` raising each event to `S`'s session's scripts, through its `Around`.",
    'pub struct GameEventDispatch<S: EventSource>(pub S);',
    '',
    'impl<S: EventSource> GameEvents for GameEventDispatch<S> {',
  )
  events.forEach((it, k) => {
    if (k) lines.push('')
    lines.push(...dispatchMethod(it).map((l) => `    ${l}`))
  })
  lines.push('}')
  events.forEach((it) => {
    lines.push('', ...raiseFunction(it))
  })
  lines.push(
    '',
    '',
    '/// What scripts listen to each watched event as, `(class, event)`: while any is listened to, it is watched.',
    'pub const WATCHED: &[(WatchedEvent, &[(&str, &str)])] = &[',
    ...watched.map((it) => {
      const { first, nf } = stages(it)
      const all = [...(first ? [first] : []), nf].map(
        ([c, e]) => `(${JSON.stringify(c)}, ${JSON.stringify(e)})`,
      )
      return `    (WatchedEvent::${pascal(it.watched!)}, &[${all.join(', ')}]),`
    }),
    '];',
    '',
    '/// Every event raised here, `(class, event)`: nothing else raises them.',
    'pub const RAISED: &[(&str, &str)] = &[',
    ...events.flatMap((it) => {
      const { first, nf } = stages(it)
      return [...(first ? [first] : []), nf].map(
        ([c, e]) => `    (${JSON.stringify(c)}, ${JSON.stringify(e)}),`,
      )
    }),
    '];',
  )
  return finish(lines)
}
