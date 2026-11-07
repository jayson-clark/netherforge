/**
 * `api/RaisableEvents.kt`: every `nf` event a test can raise by name (`nf.test.raise`), with the
 * handle it's heard on first when the server raises it that way (`raised.first`), so a test
 * raises an event along the same path the server does. Every event but a local one: a new event
 * is raisable the day it's in the spec.
 */
import type { ApiSpec } from '../../src/types.ts'
import { GENERATED_BY } from './common.ts'
import type { RaisedEvent } from './raised.ts'

/**
 * `crates/api/src/generated/raisable.rs`: the same table for the Rust runtime. Each event's payload
 * is read with its shape's codec (so a mistake is the codec's, at `payload`) and pushed back as the
 * table its handlers read; its first stage names the class's event and the payload field whose
 * handle hears it, and how to reach that handle (a `World` stage from a `Block` or `Location`).
 */
export function raisableRust(spec: ApiSpec, events: RaisedEvent[]): string {
  const nf = spec.classes.find((it) => it.name === 'nf')
  const byName = new Map(events.map((it) => [it.event.name, it]))
  const entries = (nf?.events ?? [])
    .filter((it) => !it.local)
    .map((event) => {
      const raised = byName.get(event.name)
      const first = raised?.first
      const shape = event.payload ? spec.shapes.find((it) => it.name === event.payload) : undefined
      const fields = (shape?.fields ?? []).map((it) => it.name).sort()
      const payload = event.payload
        ? `Some(|lua, value| { let read = codecs::${event.payload}.read(lua, value, "payload")?; codecs::${event.payload}.push(lua, read).map_err(|e| ApiError(e.to_string())) })`
        : 'None'
      let firstExpr = 'None'
      if (first) {
        const inner =
          first.field.codec.kind === 'optional' ? first.field.codec.inner : first.field.codec
        const held =
          inner.kind === 'handle' ? inner.cls.name : inner.kind === 'runtime' ? inner.name : ''
        const via =
          first.cls !== 'World' || held === 'World'
            ? 'Via::Handle'
            : held === 'Block'
              ? 'Via::BlockWorld'
              : 'Via::LocationWorld'
        const own = raised!.event.raised!.first!
        firstExpr = `Some(First { class: ${JSON.stringify(first.cls)}, event: ${JSON.stringify(own.event)}, field: ${JSON.stringify(first.field.name)}, via: ${via} })`
      }
      return [
        '    Raisable {',
        `        event: ${JSON.stringify(event.name)},`,
        `        fields: &[${fields.map((it) => JSON.stringify(it)).join(', ')}],`,
        `        payload: ${payload},`,
        `        first: ${firstExpr},`,
        '    },',
      ].join('\n')
    })
  return [
    `// ${GENERATED_BY}`,
    '',
    '//! Every `nf` event a test can raise by name (`nf.test.raise`): all of them but those only their',
    "//! own script hears, each with the handle it's heard on first when the server raises it that way.",
    '',
    'use mlua::{Lua, Value};',
    '',
    'use crate::codec::Codec;',
    'use crate::{ApiError, ApiResult, codecs};',
    '',
    '/// How the handle an event is heard on first is found in its payload.',
    '#[derive(Clone, Copy, Debug, PartialEq, Eq)]',
    'pub enum Via {',
    "    /// The field's own handle.",
    '    Handle,',
    "    /// The world of the field's `Block`.",
    '    BlockWorld,',
    "    /// The world of the field's `Location`.",
    '    LocationWorld,',
    '}',
    '',
    "/// The stage before `nf`'s: `class`'s event `event`, on the handle in payload field `field`.",
    '#[derive(Clone, Copy, Debug)]',
    'pub struct First {',
    "    pub class: &'static str,",
    "    pub event: &'static str,",
    "    pub field: &'static str,",
    '    pub via: Via,',
    '}',
    '',
    '/// An `nf` event a test can raise.',
    'pub struct Raisable {',
    "    pub event: &'static str,",
    "    /// Its payload's fields, sorted: what a mistake names.",
    "    pub fields: &'static [&'static str],",
    '    /// Reads a payload as the event has it and pushes it back as its table; none for an event without one.',
    '    pub payload: Option<fn(&Lua, Value) -> ApiResult<Value>>,',
    '    pub first: Option<First>,',
    '}',
    '',
    '/// Every raisable event.',
    'pub const RAISABLE: &[Raisable] = &[',
    ...entries,
    '];',
    '',
  ].join('\n')
}
