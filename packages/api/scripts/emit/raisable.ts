/**
 * `api/RaisableEvents.kt`: every `nf` event a test can raise by name (`nf.test.raise`), with the
 * handle it's heard on first when the server raises it that way (`raised.first`), so a test
 * raises an event along the same path the server does. Every event but a local one: a new event
 * is raisable the day it's in the spec.
 */
import type { ApiSpec } from '../../src/types.ts'
import { GENERATED_BY } from './common.ts'
import { constant, finish } from './kotlin.ts'
import type { RaisedEvent } from './raised.ts'

/**
 * A Kotlin expression for the handle an event is heard on first, from the Lua payload `it`: the
 * handle in [first]'s field, or, for a `World` stage, the world of the `Block` or `Location` in it.
 */
function heardBy(first: NonNullable<RaisedEvent['first']>): string {
  const field = first.field
  const optional = field.codec.kind === 'optional'
  const inner = field.codec.kind === 'optional' ? field.codec.inner : field.codec
  const held = inner.kind === 'handle' ? inner.cls.name : inner.kind === 'runtime' ? inner.name : ''
  const of = (x: string) =>
    first.cls !== 'World' || held === 'World'
      ? x
      : held === 'Block'
        ? `LuaHandle.World(${x}.world)`
        : `${x}.world`
  return optional ? `it.${field.kotlin}?.let { x0 -> ${of('x0')} }` : of(`it.${field.kotlin}`)
}

export function raisableKotlin(spec: ApiSpec, events: RaisedEvent[]): string {
  const nf = spec.classes.find((it) => it.name === 'nf')
  const byName = new Map(events.map((it) => [it.event.name, it]))
  const entries = (nf?.events ?? [])
    .filter((it) => !it.local)
    .map((event) => {
      const first = byName.get(event.name)?.first
      const type = `Events.NF_${constant(event.name)}`
      return first
        ? `        ${JSON.stringify(event.name)} to Raisable(${type}, Events.${first.constant}) { ${heardBy(first)} }`
        : `        ${JSON.stringify(event.name)} to Raisable(${type})`
    })
  return finish([
    `// ${GENERATED_BY}`,
    '',
    'package dev.netherforge.plugin.binding',
    '',
    '/**',
    ' * An `nf` event a test can raise: [event], heard first on the handle [heardBy] finds in the',
    " * payload (as [first], the same event on that handle's class) when the server raises it",
    ' * that way, then on `nf`.',
    ' */',
    'class Raisable<P : LuaEvent>(',
    '    val event: EventType<P>,',
    '    val first: EventType<P>? = null,',
    '    val heardBy: (P) -> LuaHandle? = { null }',
    ') {',
    '    /** The path [payload] goes along, as `Scripts.emit` takes it. */',
    '    fun path(payload: P): List<Pair<EventType<out P>, LuaHandle?>> {',
    '        val target = first?.let { heardBy(payload) }',
    '        val stages = ArrayList<Pair<EventType<out P>, LuaHandle?>>(2)',
    '        if (first != null && target != null) stages += first to target',
    '        stages += event to null',
    '        return stages',
    '    }',
    '}',
    '',
    '/** Every `nf` event a test can raise, by name: all of them but those only their own script hears. */',
    'object RaisableEvents {',
    '    val byName: Map<String, Raisable<*>> = mapOf(',
    entries.join(',\n'),
    '    )',
    '}',
  ])
}
