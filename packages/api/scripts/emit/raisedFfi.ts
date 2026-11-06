/**
 * The server's events across the FFM boundary: the Kotlin adapter calls the Rust runtime's
 * `GameEvents` (`RustGameEvents`, a proxy), which `game_events_dispatch` runs (see the
 * rust-runtime skill, "The FFM boundary"). A method's number is its event's place in the list; its
 * payload's fields are written in order (a lazy one as a function the runtime calls back if a
 * script listens), and the answer is whether it ended cancelled (a cancellable event's) and then
 * each writable field's value, which the proxy writes back into the payload.
 *
 * The wire is the Platform's (`packages/platform/scripts/ffi.ts`): the payloads hold only its
 * records, whose codecs the glue already has.
 */
import { GENERATED_BY } from './common.ts'
import type { Codec } from './bindings.ts'
import type { RaisedEvent } from './raised.ts'
import { platformRust, snake } from './raisedRust.ts'
import { ident as rustIdent } from './rust.ts'

const HANDLE_PUT: Record<string, [put: string, take: string]> = {
  Player: ['putPlayerRef(w, $)', 'takePlayerRef(r)'],
  Entity: ['w.uuid($)', 'r.uuid()'],
  Living: ['w.uuid($)', 'r.uuid()'],
  Mob: ['w.uuid($)', 'r.uuid()'],
  Block: ['putBlockRef(w, $)', 'takeBlockRef(r)'],
  World: ['w.string($)', 'r.string()'],
  Inventory: ['putInventoryRef(w, $)', 'takeInventoryRef(r)'],
}

const VALUE_PUT: Record<string, [put: string, take: string]> = {
  Location: ['putLocation(w, $)', 'takeLocation(r)'],
  Item: ['putItemData(w, $)', 'takeItemData(r)'],
  StatusEffect: ['putStatusEffectData(w, $)', 'takeStatusEffectData(r)'],
  Text: ['w.string($)', 'r.string()'],
}

function kotlinPut(codec: Codec, v: string, depth = 0): string {
  const x = `x${depth}`
  switch (codec.kind) {
    case 'string':
    case 'choice':
      return `w.string(${v})`
    case 'number':
      return `w.double(${v})`
    case 'integer':
      return `w.int(${v})`
    case 'boolean':
      return `w.bool(${v})`
    case 'optional':
      return `w.opt(${v}) { ${x} -> ${kotlinPut(codec.inner, x, depth + 1)} }`
    case 'list':
      return `w.list(${v}) { ${x} -> ${kotlinPut(codec.item, x, depth + 1)} }`
    case 'map':
      return `w.map(${v}, { ${x} -> ${kotlinPut(codec.key, x, depth + 1)} }) { ${x} -> ${kotlinPut(codec.value, x, depth + 1)} }`
    case 'handle':
      return HANDLE_PUT[codec.cls.name]![0].replace('$', v)
    case 'runtime':
    case 'shape':
      return VALUE_PUT[codec.name]![0].replace('$', v)
    default:
      throw new Error(`no wire for a ${codec.kind}`)
  }
}

function kotlinTake(codec: Codec): string {
  switch (codec.kind) {
    case 'string':
    case 'choice':
      return 'r.string()'
    case 'number':
      return 'r.double()'
    case 'integer':
      return 'r.int()'
    case 'boolean':
      return 'r.bool()'
    case 'optional':
      return `r.opt { ${kotlinTake(codec.inner)} }`
    case 'list':
      return `r.list { ${kotlinTake(codec.item)} }`
    case 'map':
      return `r.map({ ${kotlinTake(codec.key)} }) { ${kotlinTake(codec.value)} }`
    case 'handle':
      return HANDLE_PUT[codec.cls.name]![1]
    case 'runtime':
    case 'shape':
      return VALUE_PUT[codec.name]![1]
    default:
      throw new Error(`no wire for a ${codec.kind}`)
  }
}

const text = (lines: string[]) =>
  `${lines
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trimEnd()}\n`

/** `RustGameEvents`: the runtime's `GameEvents` as the adapter calls it. */
export function gameEventsFfiKotlin(events: RaisedEvent[]): string {
  const lines = [
    `// ${GENERATED_BY}`,
    '',
    '@file:Suppress("ktlint", "RedundantUnitExpression")',
    '',
    'package dev.netherforge.plugin.ffi',
    '',
    'import dev.netherforge.plugin.platform.GameEvent',
    'import dev.netherforge.plugin.platform.GameEvents',
    '',
    "/** The Rust runtime's `GameEvents`, called through its id: each payload written, what handlers left read back. */",
    'internal class RustGameEvents(private val id: Long) : GameEvents {',
    '    init {',
    '        Native.track(this, id)',
    '    }',
  ]
  events.forEach((it, k) => {
    const puts = it.payload.fields.map((f) =>
      f.lazy
        ? `w.java(Objects.export(event.${f.kotlin}) { f, _, _, w -> ${kotlinPut(f.codec, 'f()')} })`
        : kotlinPut(f.codec, `event.${f.kotlin}`),
    )
    const back = it.payload.fields
      .filter((f) => f.writable)
      .map((f) => `event.${f.kotlin} = ${kotlinTake(f.codec)}`)
    const cancellable = it.event.cancellable
    lines.push(
      '',
      `    override fun ${it.method}(event: GameEvent.${it.payload.name})${cancellable ? ': Boolean' : ''} = Native.call(id, ${k}, { w ->`,
      ...puts.map((p) => `        ${p}`),
      '    }) { r ->',
      ...(cancellable ? ['        val cancelled = r.bool()'] : []),
      ...back.map((b) => `        ${b}`),
      ...(cancellable ? ['        cancelled'] : []),
      '    }',
    )
  })
  lines.push('}')
  return text(lines)
}

/** `game_events_dispatch`: what the adapter's calls to the runtime's `GameEvents` run. */
export function gameEventsFfiRust(events: RaisedEvent[]): string {
  const lines = [
    `// ${GENERATED_BY}`,
    '',
    "//! The server's events (`GameEvents`) as the JVM calls them: `PlatformEvents::game`'s dispatcher.",
    '',
    '#![allow(clippy::all, unused_imports)]',
    '',
    'use std::collections::HashMap;',
    'use std::rc::Rc;',
    '',
    'use netherforge_platform::api::game_event;',
    'use netherforge_platform::*;',
    '',
    'use crate::objects::{Dispatch, JavaRef};',
    'use crate::wire::{Put, Take};',
    '',
    "/// What the JVM's `events.game` calls: the runtime's `GameEvents`, through `events`.",
    'pub fn game_events_dispatch(events: Rc<dyn PlatformEvents>) -> Dispatch {',
    '    Rc::new(move |method, r, w| {',
    '        let game = events.game();',
    '        match method {',
  ]
  events.forEach((it, k) => {
    const fields = it.payload.fields.map((f) => {
      const type = platformRust(f.codec)
      return `${rustIdent(f.name)}: ${
        f.lazy
          ? `{ let obj = JavaRef::take(r); Box::new(move || obj.call::<${type}>("${it.payload.name}.${f.name}", 0, |_| {})) }`
          : `<${type} as Take>::take(r)`
      }`
    })
    const back = it.payload.fields
      .filter((f) => f.writable)
      .map((f) => `event.${rustIdent(f.name)}.put(w);`)
    lines.push(
      `            ${k} => {`,
      `                let mut event = game_event::${it.payload.name} { ${fields.join(', ')} };`,
      it.event.cancellable
        ? `                game.${snake(it.method)}(&mut event).put(w);`
        : `                game.${snake(it.method)}(&mut event);`,
      ...back.map((b) => `                ${b}`),
      '            }',
    )
  })
  lines.push(
    '            other => panic!("GameEvents has no method {other}"),',
    '        }',
    '    })',
    '}',
  )
  return text(lines)
}
