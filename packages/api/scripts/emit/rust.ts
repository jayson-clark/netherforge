/**
 * The Rust runtime's half of the bindings (`crates/api/src/generated/`), from the same
 * binding model as the Kotlin one (`bindings.ts`), so the two runtimes can't disagree about
 * what crosses (see the rust-runtime skill, "The API crate"):
 *
 *  - `handles.rs`: one struct per handle class, holding what identifies it (its root's key),
 *    with conversions up its chain (a `Mob` is a `Living` is an `Entity`), and `Handle`, any
 *    of them;
 *  - `shapes.rs`: a struct per shape that crosses (option tables, returned records, events'
 *    payloads);
 *  - `unions.rs`: an enum per union that crosses and isn't only string literals;
 *  - `api.rs`: one trait per class with functions (`CentityApi`, `NfServerApi`), each
 *    function in the spec that isn't `impl: "lua"`. A handle method gets the handle as
 *    `this`; a namespace function gets the calling script as `caller`. A function the
 *    runtime doesn't implement is a compile error.
 *
 * Every type maps to a Rust type: `string` → `String`, `number` → `f64`, `integer` → `i64`
 * (a whole number in the safe range, see "Numbers"), `boolean` → `bool`, a function →
 * `Function`, `any` and `table` → `Value`, a literal union → `String` (checked to be one of
 * them), `T?` → `Option<T>`, `T[]` → `Vec<T>`, `table<K, V>` → `BTreeMap<K, V>`, a handle
 * class → its struct, a union of handle classes only → `Handle`, the runtime's own types
 * (`RUNTIME_TYPES`) → `values::*`, a shape → its struct. Several returns are a tuple (an
 * `Option` of one when they may be a single nil), and an `async` function returns a
 * `Pending` of its value.
 */
import { eventedClasses, GENERATED_BY, sinceVersion } from './common.ts'
import {
  classEvents,
  pascal,
  reachable,
  type Bindings,
  type ClassBinding,
  type Codec,
  type FnBinding,
  type ReturnBinding,
} from './bindings.ts'
import type { ApiSpec } from '../../src/types.ts'

const KEYWORDS = new Set(
  (
    'as async await box break const continue crate dyn else enum extern false final fn for gen if ' +
    'impl in let loop macro match mod move mut override priv pub ref return self static struct ' +
    'super trait true try type typeof unsafe unsized use virtual where while yield abstract ' +
    'become do'
  ).split(' '),
)

/** A Lua name as a Rust identifier: `type` → `r#type`. */
export const ident = (name: string) => (KEYWORDS.has(name) ? `r#${name}` : name)

/** `play_animation` stays; `nf.server` → `nf_server`. */
const snake = (name: string) => name.replace(/\./g, '_')

/** The file's first lines: who made it, what it is ([about], a module doc), what it uses. */
export function header(about: string[], uses: string[]): string[] {
  return [
    `// ${GENERATED_BY}`,
    '',
    ...about.map((line) => `//!${line ? ` ${line}` : ''}`),
    '',
    ...uses.map((it) => `use ${it};`),
    ...(uses.length ? [''] : []),
  ]
}

export function finish(lines: string[]): string {
  return `${lines.join('\n').replace(/\n{3,}/g, '\n\n')}\n`
}

/** A doc comment: each line of [doc] behind `///`, at [indent]. */
export function rustdoc(doc: string, indent: string): string[] {
  if (!doc.trim()) return []
  return doc
    .trim()
    .split('\n')
    .map((line) => `${indent}///${line.trim() ? ` ${line.trim()}` : ''}`)
}

const RUNTIME_RUST: Record<string, string> = {
  Vec3: 'values::Vec3',
  Location: 'values::Location',
  Item: 'values::Item',
  ItemMatch: 'values::ItemMatch',
  Text: 'values::Text',
}

/** [boxed] names the shapes a field must box, being part of the shape it's in. */
function rustType(codec: Codec, boxed: Set<string> = new Set()): string {
  switch (codec.kind) {
    case 'string':
    case 'choice':
      return 'String'
    case 'number':
      return 'f64'
    case 'integer':
      return 'i64'
    case 'boolean':
      return 'bool'
    case 'function':
      return 'Function'
    case 'any':
      return 'Value'
    case 'optional': {
      const inner = rustType(codec.inner, boxed)
      // `any` is any value already, nil included.
      return codec.inner.kind === 'any' || inner.startsWith('Option<') ? inner : `Option<${inner}>`
    }
    case 'list':
      // A list holds its items behind a pointer already, so it never needs a box.
      return `Vec<${rustType(codec.item)}>`
    case 'map': {
      const key = codec.key.kind
      if (!['string', 'integer', 'choice'].includes(key))
        throw new Error(`a map keyed by ${key} has no Rust type (BTreeMap needs an ordered key)`)
      return `BTreeMap<${rustType(codec.key)}, ${rustType(codec.value)}>`
    }
    case 'union':
      return codec.handlesOnly ? 'handles::Handle' : `unions::${codec.name}`
    case 'handle':
      return `handles::${codec.cls.name}`
    case 'runtime': {
      const type = RUNTIME_RUST[codec.name]
      if (!type) throw new Error(`runtime type ${codec.name} has no Rust type (RUNTIME_RUST)`)
      return type
    }
    case 'shape':
      return boxed.has(codec.name) ? `Box<shapes::${codec.name}>` : `shapes::${codec.name}`
  }
}

function returnType(returns: ReturnBinding): string {
  switch (returns.kind) {
    case 'none':
      return '()'
    case 'one':
      return rustType(returns.codec)
    case 'tuple': {
      const tuple = `(${returns.codecs.map((it) => rustType(it)).join(', ')})`
      return returns.optional ? `Option<${tuple}>` : tuple
    }
    case 'async':
      return `Pending<${rustType(returns.codec)}>`
  }
}

// ---- handles.rs --------------------------------------------------------------------

const keyType = (type: 'string' | 'integer') => (type === 'string' ? 'String' : 'i64')

export function handlesRust(classes: ClassBinding[]): string {
  const handles = classes.flatMap((it) => (it.handle ? [it] : []))
  const lines = [
    ...header(
      [
        'One struct per handle class, holding what identifies the thing (its root',
        "class's key), and `Handle`, any of them. A thing is one handle whatever",
        'class it is: a `Mob` converts into the `Living` and the `Entity` it is,',
        'keeping its key.',
      ],
      ['crate::handle::{HandleClass, Key, KeyPart}'],
    ),
  ]
  for (const binding of handles) {
    const handle = binding.handle!
    lines.push(
      ...rustdoc(binding.cls.doc, ''),
      '#[derive(Clone, Debug, PartialEq, Eq, Hash)]',
      `pub struct ${handle.name} {`,
      ...handle.fields.map((field) => `    pub ${ident(field.name)}: ${keyType(field.type)},`),
      '}',
      '',
    )
    for (const above of handle.chain.slice(1)) {
      const fields = handle.fields.map((field) => ident(field.name))
      lines.push(
        `impl From<${handle.name}> for ${above} {`,
        `    fn from(it: ${handle.name}) -> ${above} {`,
        `        ${above} { ${fields.map((f) => `${f}: it.${f}`).join(', ')} }`,
        '    }',
        '}',
        '',
      )
    }
  }
  lines.push(
    '/// Any handle, as the class the runtime knows it as.',
    '#[derive(Clone, Debug, PartialEq, Eq, Hash)]',
    'pub enum Handle {',
    ...handles.map((it) => `    ${it.handle!.name}(${it.handle!.name}),`),
    '}',
    '',
    'impl Handle {',
    '    /// The class it is, as scripts spell it.',
    "    pub fn class(&self) -> &'static str {",
    '        match self {',
    ...handles.map(
      (it) => `            Handle::${it.handle!.name}(_) => ${JSON.stringify(it.handle!.name)},`,
    ),
    '        }',
    '    }',
    '}',
    '',
    '/// Every handle class, with its chain from itself up to its root.',
    'pub const CHAINS: &[&[&str]] = &[',
    ...handles.map((it) => `    &[${it.handle!.chain.map((c) => JSON.stringify(c)).join(', ')}],`),
    '];',
    '',
    '/// The handle classes a saved table (and `nf.json`) may keep (`saveable` in the spec), each at the top of',
    '/// its chain, with the tag it is saved under: `{"$entity":[id]}`.',
    'pub const SAVEABLE: &[(&str, &str)] = &[',
    ...handles
      .filter((it) => it.cls.saveable)
      .map(
        (it) =>
          `    (${JSON.stringify(it.handle!.name)}, ${JSON.stringify(it.handle!.name.toLowerCase())}),`,
      ),
    '];',
  )
  for (const binding of handles) {
    const handle = binding.handle!
    const parts = handle.fields.map((field, k) => {
      const variant = field.type === 'string' ? 'Str' : 'Int'
      return { field: ident(field.name), variant, k }
    })
    lines.push(
      '',
      `impl HandleClass for ${handle.name} {`,
      `    const NAME: &'static str = ${JSON.stringify(handle.name)};`,
      `    const ROOT: &'static str = ${JSON.stringify(handle.root)};`,
      `    const CLASSES: &'static [&'static str] = &[${handle.descendants.map((c) => JSON.stringify(c)).join(', ')}];`,
      '    fn key(&self) -> Key {',
      `        vec![${parts.map((p) => `KeyPart::${p.variant}(self.${p.field}.clone())`).join(', ')}]`,
      '    }',
      '    fn from_key(key: &[KeyPart]) -> Option<Self> {',
      `        match key {`,
      `            [${parts.map((p) => `KeyPart::${p.variant}(${p.field.replace('r#', '')})`).join(', ')}] => Some(${handle.name} { ${parts.map((p) => `${p.field}: ${p.field.replace('r#', '')}.clone()`).join(', ')} }),`,
      '            _ => None,',
      '        }',
      '    }',
      '}',
    )
  }
  lines.push(
    '',
    'impl Handle {',
    "    /// What identifies the thing: its root class's key.",
    '    pub fn key(&self) -> Key {',
    '        match self {',
    ...handles.map((it) => `            Handle::${it.handle!.name}(h) => h.key(),`),
    '        }',
    '    }',
    '',
    '    /// The handle of class `class` with `key`, if that class has such keys.',
    '    pub fn from_key(class: &str, key: &[KeyPart]) -> Option<Handle> {',
    '        match class {',
    ...handles.map(
      (it) =>
        `            ${JSON.stringify(it.handle!.name)} => ${it.handle!.name}::from_key(key).map(Handle::${it.handle!.name}),`,
    ),
    '            _ => None,',
    '        }',
    '    }',
    '}',
  )
  return finish(lines)
}

// ---- shapes.rs ---------------------------------------------------------------------

export function shapesRust(bindings: Bindings): string {
  const reach = reachable(bindings.shapes)
  const lines = [
    ...header(
      [
        'The shapes that cross: option tables functions take, records they return,',
        "events' payloads. A field that's left out is `None`.",
      ],
      [
        'std::collections::BTreeMap',
        'mlua::{Function, Value}',
        'crate::{handles, shapes, unions, values}',
      ],
    ),
  ]
  for (const shape of bindings.shapes) {
    const name = shape.shape.name
    // A field holding this very shape (directly, or through shapes that hold it back) is
    // boxed, so the struct has a size.
    const boxed = new Set(
      [...(reach.get(name) ?? [])].filter((other) => reach.get(other)?.has(name)),
    )
    lines.push(...rustdoc(shape.shape.doc, ''), '#[derive(Clone, Debug)]', `pub struct ${name} {`)
    for (const field of shape.fields)
      lines.push(
        ...rustdoc(field.doc, '    '),
        `    pub ${ident(field.name)}: ${rustType(field.codec, boxed)},`,
      )
    lines.push('}', '')
  }
  return finish(lines)
}

// ---- unions.rs ---------------------------------------------------------------------

/** A union member's variant: `Location`, `Vec3`, `String`, `Entity`, `StringList`. */
function variantName(codec: Codec): string {
  switch (codec.kind) {
    case 'string':
    case 'number':
    case 'integer':
    case 'boolean':
    case 'function':
      return pascal(codec.kind)
    case 'any':
      return codec.table ? 'Table' : 'Value'
    case 'choice':
      return 'Choice'
    case 'optional':
      return variantName(codec.inner)
    case 'list':
      return `${variantName(codec.item)}List`
    case 'map':
      return 'Map'
    case 'union':
      return codec.name
    case 'handle':
      return codec.cls.name
    case 'runtime':
    case 'shape':
      return codec.name
  }
}

export function unionsRust(bindings: Bindings): string {
  const lines = [
    ...header(
      [
        "The unions that cross and aren't only string literals: a variant per member,",
        'tried in order when one is read.',
      ],
      [
        'std::collections::BTreeMap',
        'mlua::{Function, Value}',
        'crate::{handles, shapes, unions, values}',
      ],
    ),
  ]
  for (const [name, union] of bindings.unions) {
    if (union.handlesOnly) continue
    const variants = union.members.map(
      (member) => `    ${variantName(member)}(${rustType(member)}),`,
    )
    if (new Set(variants.map((it) => it.split('(')[0])).size !== variants.length)
      throw new Error(`${name}: two members would be the same variant`)
    lines.push('#[derive(Clone, Debug)]', `pub enum ${name} {`, ...variants, '}', '')
  }
  return finish(lines)
}

// ---- api.rs ------------------------------------------------------------------------

/** `CentityApi`, `NfServerApi`. */
const traitName = (binding: ClassBinding) =>
  `${binding.cls.name
    .split('.')
    .map((part) => pascal(part))
    .join('')}Api`

/** A parameter's Rust name: `...` (the rest of the arguments) is `rest`. */
const paramName = (name: string) => (name === '...' ? 'rest' : ident(name))

/** A parameter's Rust type: the rest of the arguments are a list of values. */
const paramType = (p: { name: string; codec: Codec }) =>
  p.name === '...' ? 'Vec<Value>' : rustType(p.codec)

/**
 * What a function returns in Rust: a function that waits (`nf.wait`, `dialog:ask`) returns
 * `Waiting`, its task's wake-up registered, and its value is what the task is resumed with.
 */
const rustReturn = (fn: FnBinding) => (fn.fn.waits ? 'Waiting' : returnType(fn.returns))

function signature(binding: ClassBinding, fn: FnBinding): string {
  const first = fn.method ? `this: &handles::${binding.cls.name}` : 'caller: &Caller'
  const params = fn.params.map((p) => `${paramName(p.name)}: ${paramType(p)}`)
  return `fn ${ident(fn.fn.name)}(&self, ${[first, ...params].join(', ')}) -> ApiResult<${rustReturn(fn)}>;`
}

export function apiRust(classes: ClassBinding[]): string {
  const withFunctions = classes.filter((it) => it.functions.length)
  const lines = [
    ...header(
      [
        'Everything the runtime implements for the Lua API: one trait per class, each',
        'function in the spec that isn\'t `impl: "lua"`. A handle method gets the',
        'handle as `this`; a namespace function gets the calling script as `caller`.',
      ],
      [
        'std::collections::BTreeMap',
        'mlua::{Function, Value}',
        'crate::{ApiResult, Caller, Pending, Waiting, handles, shapes, unions, values}',
      ],
    ),
    "/// Every class's implementation.",
    'pub trait LuaApi: crate::Runtime {',
    // Every class is unported until the runtime implements it: the port goes a class at a
    // time (see the rust-runtime skill). A class's own trait has no defaults.
    ...withFunctions.map(
      (it) =>
        `    fn ${snake(it.cls.name).toLowerCase()}(&self) -> &dyn ${traitName(it)} { &crate::unported::Unported }`,
    ),
    '}',
  ]
  for (const binding of withFunctions) {
    lines.push('', ...rustdoc(binding.cls.doc, ''), `pub trait ${traitName(binding)} {`)
    binding.functions.forEach((fn, index) => {
      if (index) lines.push('')
      lines.push(...rustdoc(fn.fn.doc, '    '), `    ${signature(binding, fn)}`)
    })
    lines.push('}')
  }
  return finish(lines)
}

// ---- codecs.rs and bind.rs: the glue -----------------------------------------------

/** A Rust expression for [codec]'s codec (a zero-sized value): `Opt(List(Str))`. */
export function codecExpr(codec: Codec, boxed: Set<string> = new Set()): string {
  switch (codec.kind) {
    case 'string':
      return 'Str'
    case 'number':
      return 'Num'
    case 'integer':
      return 'Int'
    case 'boolean':
      return 'Bool'
    case 'function':
      return 'Func'
    case 'any':
      return codec.table ? 'TABLE' : 'ANY'
    case 'choice':
      return `Choice(&[${codec.choices.map((it) => JSON.stringify(it)).join(', ')}])`
    case 'optional':
      // `any` is any value already, nil included.
      if (codec.inner.kind === 'any') return 'ANY'
      if (codec.inner.kind === 'optional') return codecExpr(codec.inner, boxed)
      return `Opt(${codecExpr(codec.inner, boxed)})`
    case 'list':
      return `List(${codecExpr(codec.item)})`
    case 'map':
      return `Map(${codecExpr(codec.key)}, ${codecExpr(codec.value)})`
    case 'union':
      return codec.handlesOnly
        ? `AnyHandle(&[${codec.members.map((m) => JSON.stringify(m.kind === 'handle' ? m.cls.name : '')).join(', ')}])`
        : `codecs::${codec.name}`
    case 'handle':
      return `HandleOf::<handles::${codec.cls.name}>::default()`
    case 'runtime':
      return `${codec.name}Codec`
    case 'shape':
      return boxed.has(codec.name) ? `Boxed(codecs::${codec.name})` : `codecs::${codec.name}`
  }
}

const GLUE_USES = [
  'mlua::{Lua, Table, Value}',
  'crate::codec::*',
  'crate::{codecs, handles, shapes, unions}',
]

export function codecsRust(bindings: Bindings, spec: ApiSpec): string {
  const reach = reachable(bindings.shapes)
  const lines = [
    ...header(
      [
        'A codec for each shape and union that crosses: a shape read strictly (a key',
        "that isn't a field is a mistake), pushed as a table of the fields that are",
        'set; a union by the first member whose kind of value it is.',
      ],
      GLUE_USES,
    ),
  ]
  for (const shape of bindings.shapes) {
    const name = shape.shape.name
    const boxed = new Set(
      [...(reach.get(name) ?? [])].filter((other) => reach.get(other)?.has(name)),
    )
    const fields = shape.fields.map((f) => ({ ...f, expr: codecExpr(f.codec, boxed) }))
    lines.push(
      '#[derive(Clone, Copy, Default)]',
      `pub struct ${name};`,
      `impl Codec for ${name} {`,
      `    type T = shapes::${name};`,
      `    fn expected(&self) -> String { ${JSON.stringify(name)}.into() }`,
      '    fn accepts(&self, value: &Value) -> bool { matches!(value, Value::Table(t) if t.metatable().is_none()) }',
      `    fn read(&self, lua: &Lua, value: Value, at: &str) -> crate::ApiResult<shapes::${name}> {`,
      `        let t = shape_table(&value, at, ${JSON.stringify(name)}, &[${fields.map((f) => JSON.stringify(f.name)).join(', ')}])?;`,
      `        let _ = (&lua, &t);`,
      `        Ok(shapes::${name} {`,
      ...fields.map(
        (f) =>
          `            ${ident(f.name)}: field(lua, t, at, ${JSON.stringify(f.name)}, ${f.expr})?,`,
      ),
      '        })',
      '    }',
      `    fn push(&self, lua: &Lua, value: shapes::${name}) -> mlua::Result<Value> {`,
      `        let t: Table = lua.create_table_with_capacity(0, ${fields.length})?;`,
      ...fields.map(
        (f) =>
          `        set_field(lua, &t, ${JSON.stringify(f.name)}, ${f.expr}, value.${ident(f.name)})?;`,
      ),
      ...(fields.length ? [] : ['        let _ = value;']),
      '        Ok(Value::Table(t))',
      '    }',
      '}',
      '',
    )
  }
  for (const [name, union] of bindings.unions) {
    if (union.handlesOnly) continue
    const members = union.members.map((m) => ({ variant: variantName(m), expr: codecExpr(m) }))
    lines.push(
      '#[derive(Clone, Copy, Default)]',
      `pub struct ${name};`,
      `impl Codec for ${name} {`,
      `    type T = unions::${name};`,
      '    fn expected(&self) -> String {',
      `        or_list(vec![${members.map((m) => `${m.expr}.expected()`).join(', ')}])`,
      '    }',
      '    fn accepts(&self, value: &Value) -> bool {',
      `        ${members.map((m) => `${m.expr}.accepts(value)`).join(' || ')}`,
      '    }',
      `    fn read(&self, lua: &Lua, value: Value, at: &str) -> crate::ApiResult<unions::${name}> {`,
      ...members.map(
        (m) =>
          `        if ${m.expr}.accepts(&value) { return ${m.expr}.read(lua, value, at).map(unions::${name}::${m.variant}); }`,
      ),
      '        Err(mismatch(at, &self.expected(), &value))',
      '    }',
      `    fn push(&self, lua: &Lua, value: unions::${name}) -> mlua::Result<Value> {`,
      '        match value {',
      ...members.map(
        (m) => `            unions::${name}::${m.variant}(v) => ${m.expr}.push(lua, v),`,
      ),
      '        }',
      '    }',
      '}',
      '',
    )
  }
  // A handler assigning an event's writable field is checked as the field will be read back.
  const writable = new Map<string, { payload: string; field: string; expr: string }>()
  const shapesByName = new Map(bindings.shapes.map((it) => [it.shape.name, it]))
  for (const cls of eventedClasses(spec))
    for (const event of cls.events) {
      if (!event.payload) continue
      const shape = shapesByName.get(event.payload)
      for (const name of event.writable ?? []) {
        const field = shape?.fields.find((it) => it.name === name)
        if (!field) continue
        writable.set(`${event.payload} ${name}`, {
          payload: event.payload,
          field: name,
          expr: codecExpr(field.codec),
        })
      }
    }
  lines.push(
    '/// Reads `value` as writable field `name` of event payload `shape`, as it will be read back',
    '/// once the handlers have run: what a handler assigning it is checked against. `None` for',
    "/// a field that isn't a writable one.",
    'pub fn check_field(lua: &Lua, shape: &str, name: &str, value: Value, at: &str) -> Option<crate::ApiResult<()>> {',
    '    let _ = (&lua, &value, &at);',
    '    match (shape, name) {',
    ...[...writable.values()].map(
      (w) =>
        `        (${JSON.stringify(w.payload)}, ${JSON.stringify(w.field)}) => Some(${w.expr}.read(lua, value, at).map(|_| ())),`,
    ),
    '        _ => None,',
    '    }',
    '}',
    '',
  )
  return finish(lines)
}

/** How a script calls it, in a message: `Player:ban`, `nf.server.set_motd`. */
const called = (binding: ClassBinding, fn: FnBinding) =>
  `${binding.cls.name}${fn.method ? ':' : '.'}${fn.fn.name}`

/** The Rust function a binding is: `centity_play_animation`, `nf_server_tick`. */
const glueName = (binding: ClassBinding, fn: FnBinding) =>
  `${snake(binding.cls.name).toLowerCase()}_${fn.fn.name}`

function glue(binding: ClassBinding, fn: FnBinding): string[] {
  const lines: string[] = []
  const args: string[] = []
  let index = 0
  if (fn.method) {
    lines.push(
      `let Some(this) = args.first().filter(|v| HandleOf::<handles::${binding.cls.name}>::default().accepts(v)).cloned() else {`,
      `    return Err(mlua::Error::runtime("call ${binding.cls.name} methods with ':' (${called(binding, fn)})"));`,
      '};',
      `let this = HandleOf::<handles::${binding.cls.name}>::default().read(lua, this, "self").map_err(|e| mlua::Error::runtime(e.0))?;`,
    )
    args.push('&this')
    index += 1
  } else {
    lines.push(
      `let caller = crate::Caller { scope: scope.ok_or_else(|| mlua::Error::runtime("${called(binding, fn)} needs a calling script"))? };`,
    )
    args.push('&caller')
  }
  const scope = fn.method ? 'host_scope(lua)' : 'Some(caller.scope)'
  if (fn.fn.requires)
    lines.push(
      `api.requires(${scope}, ${JSON.stringify(fn.fn.requires)}, ${JSON.stringify(called(binding, fn))}).map_err(|e| mlua::Error::runtime(e.0))?;`,
    )
  for (const param of fn.params) {
    if (param.name === '...') {
      lines.push(
        `let a_rest: Vec<Value> = args.get(${index}..).map(<[Value]>::to_vec).unwrap_or_default();`,
      )
      args.push('a_rest')
      index += 1
      continue
    }
    const name = `a_${param.name}`
    lines.push(
      `let ${name} = arg(lua, args, ${index}, ${JSON.stringify(param.name)}, ${codecExpr(param.codec)})?;`,
    )
    args.push(name)
    index += 1
  }
  const trait = snake(binding.cls.name).toLowerCase()
  const invoke = `api.${trait}().${ident(fn.fn.name)}(${args.join(', ')})`
  const returns = fn.returns
  if (fn.fn.waits) {
    // Its task's wake-up is registered: the binding's shim yields, and the task is resumed with
    // the value.
    lines.push(
      `let crate::Waiting = ${invoke}.map_err(|e| mlua::Error::runtime(e.0))?;`,
      'Ok(MultiValue::from_vec(vec![crate::bind::waiting(lua)?]))',
    )
    return wrap(binding, fn, lines)
  }
  switch (returns.kind) {
    case 'none':
      lines.push(`${invoke}.map_err(|e| mlua::Error::runtime(e.0))?;`, 'Ok(MultiValue::new())')
      break
    case 'one':
      lines.push(
        `Ok(MultiValue::from_vec(vec![result(lua, ${codecExpr(returns.codec)}, ${invoke})?]))`,
      )
      break
    case 'tuple': {
      lines.push(`let r = ${invoke}.map_err(|e| mlua::Error::runtime(e.0))?;`)
      const pushed = returns.codecs.map((codec, k) => `${codecExpr(codec)}.push(lua, r.${k})?`)
      if (returns.optional)
        lines.push('let Some(r) = r else { return Ok(MultiValue::from_vec(vec![Value::Nil])) };')
      lines.push(`Ok(MultiValue::from_vec(vec![${pushed.join(', ')}]))`)
      break
    }
    case 'async':
      // Its two forms: a callback, called with `value, err` once the work is done (the call returns
      // nothing), or, without one and inside a task, the task waits and is resumed with `value, err`.
      // A mistaken callback, or no callback outside a task, is said before any work starts.
      lines.push(
        `let callback = crate::pending::callback(args.get(${index}))?;`,
        `let task = match callback { None => Some(api.waiting_task(${JSON.stringify(called(binding, fn))}).map_err(|e| mlua::Error::runtime(e.0))?), Some(_) => None };`,
        `let pending = ${invoke}.map_err(|e| mlua::Error::runtime(e.0))?;`,
        `let id = crate::pending::defer(lua, ${scope}, pending, ${codecExpr(returns.codec)}, callback)?;`,
        'match task {',
        '    Some(task) => { api.wait_on(task, id); Ok(MultiValue::from_vec(vec![crate::bind::waiting(lua)?])) }',
        '    None => Ok(MultiValue::new()),',
        '}',
      )
      break
  }
  return wrap(binding, fn, lines)
}

function wrap(binding: ClassBinding, fn: FnBinding, lines: string[]): string[] {
  return [
    `fn ${glueName(binding, fn)}(lua: &Lua, api: &dyn LuaApi, scope: Option<ScopeId>, args: &[Value]) -> mlua::Result<MultiValue> {`,
    ...(fn.method ? ['    let _ = scope;'] : []),
    ...lines.map((l) => `    ${l}`),
    '}',
    '',
  ]
}

export function bindRust(classes: ClassBinding[]): string {
  const lines = [
    ...header(
      [
        "Each function's binding: it reads its arguments from Luau with their codecs,",
        'calls the API trait, and pushes what it returns. The only place Luau values',
        'meet Rust types.',
      ],
      [
        'mlua::{Lua, MultiValue, Value}',
        'netherforge_luau::ScopeId',
        'crate::api::LuaApi',
        'crate::bind::{Binding, host_scope}',
        'crate::codec::*',
        'crate::{codecs, handles}',
      ],
    ),
  ]
  const entries: string[] = []
  for (const binding of classes)
    for (const fn of binding.functions) {
      lines.push(...glue(binding, fn))
      entries.push(
        `    Binding { class: ${JSON.stringify(binding.cls.name)}, name: ${JSON.stringify(fn.fn.name)}, method: ${fn.method}, waits: ${!!fn.fn.waits || fn.returns.kind === 'async'}, call: ${glueName(binding, fn)} },`,
      )
    }
  lines.push(
    '/// Every binding, by class.',
    'pub const BINDINGS: &[Binding] = &[',
    ...entries,
    '];',
    '',
    '/// The namespaces only a test run has (`testOnly` in the spec): a server has no such field of `nf`.',
    'pub const TEST_ONLY: &[&str] = &[',
    ...classes.filter((it) => it.cls.testOnly).map((it) => `    ${JSON.stringify(it.cls.name)},`),
    '];',
  )
  return finish(lines)
}

/** `unported.rs`: every class implemented as "not on the Rust runtime yet". */
export function unportedRust(classes: ClassBinding[]): string {
  const lines = [
    ...header(
      [
        "Every class's functions, each an error saying it isn't on the Rust runtime yet:",
        "what `LuaApi`'s accessors give until the runtime implements that class.",
      ],
      [
        'std::collections::BTreeMap',
        'mlua::{Function, Value}',
        'crate::api::*',
        'crate::{ApiError, ApiResult, Caller, Pending, Waiting, handles, shapes, unions, values}',
      ],
    ),
    "/// The API as far as the Rust runtime hasn't ported it.",
    'pub struct Unported;',
    '',
    'fn unported<T>(called: &str) -> ApiResult<T> {',
    '    Err(ApiError(format!("{called} isn\'t on the Rust runtime yet")))',
    '}',
  ]
  for (const binding of classes.filter((it) => it.functions.length)) {
    lines.push('', `impl ${traitName(binding)} for Unported {`)
    for (const fn of binding.functions) {
      const sig = signature(binding, fn).replace(/;$/, '')
      // Unused parameters are fine: they're named after the spec's.
      lines.push(
        `    #[allow(unused_variables)]`,
        `    ${sig} { unported(${JSON.stringify(called(binding, fn))}) }`,
      )
    }
    lines.push('}')
  }
  return finish(lines)
}

/** `events.rs`: every built-in event, and what each class may listen for. */
export function eventsRust(spec: ApiSpec): string {
  const lines = [
    ...header(
      [
        "Every built-in event, by owning class (the spec's `events`), and each class's",
        "events by name, its own and those it inherits up its chain (a `Mob`'s `death`",
        "is `Living`'s), a class's own replacing one of the same name further up.",
      ],
      ['crate::events::EventType'],
    ),
  ]
  const events = eventedClasses(spec).flatMap((cls) => cls.events.map((event) => ({ cls, event })))
  const index = new Map(events.map(({ cls, event }, k) => [`${cls.name} ${event.name}`, k]))
  const list = (items: string[] | undefined) =>
    `&[${(items ?? []).map((it) => JSON.stringify(it)).join(', ')}]`
  lines.push('/// Every built-in event.', 'pub const EVENTS: &[EventType] = &[')
  for (const { cls, event } of events)
    lines.push(
      '    EventType {',
      `        owner: ${JSON.stringify(cls.name)},`,
      `        name: ${JSON.stringify(event.name)},`,
      `        payload: ${event.payload ? JSON.stringify(event.payload) : 'None'},`.replace(
        /payload: "(.*)",/,
        'payload: Some("$1"),',
      ),
      `        cancellable: ${!!event.cancellable},`,
      `        bubbles: ${!!event.bubbles},`,
      `        writable: ${list(event.writable)},`,
      `        options: ${list(event.options)},`,
      `        local: ${!!event.local},`,
      `        since: ${event.since ? `Some(${JSON.stringify(sinceVersion(event.since))})` : 'None'},`,
      '    },',
    )
  lines.push(
    '];',
    '',
    "/// Each evented class's events: indices into [`EVENTS`].",
    'pub const BY_CLASS: &[(&str, &[(&str, usize)])] = &[',
  )
  for (const cls of spec.classes) {
    const own = classEvents(spec, cls)
    if (!own.length) continue
    const entries = own.map(
      ({ event, owner }) =>
        `(${JSON.stringify(event.name)}, ${index.get(`${owner.name} ${event.name}`)})`,
    )
    lines.push(`    (${JSON.stringify(cls.name)}, &[${entries.join(', ')}]),`)
  }
  lines.push('];')
  return finish(lines)
}

/**
 * `crates/platform/src/api/command_arguments.rs`: `ArgumentType`, what a command argument's `type` can be, each
 * with its Lua name, what the server reads it as and whether only the runtime can resolve it (Kotlin's
 * `platform/CommandArguments.kt`).
 */
export function argumentTypesRust(bindings: Bindings): string {
  const types = bindings.commandArguments.map(({ type }) => type)
  return finish([
    ...header(
      [
        "What a command argument takes, by its Lua name (the spec's `commandArguments`), and what the server reads",
        "it as (`reading`). `names`: a word only the runtime can resolve and complete (the project's own things,",
        "the server's worlds), which the runtime turns into the handler's value.",
      ],
      ['crate::ArgumentReading'],
    ),
    '#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord)]',
    'pub enum ArgumentType {',
    ...types.flatMap((type) => [
      ...rustdoc(`\`"${type.name}"\`: ${type.doc}.`, '    '),
      `    ${pascal(type.name)},`,
    ]),
    '}',
    '',
    'impl ArgumentType {',
    "    /// Every type, in the spec's order.",
    `    pub const ALL: &'static [ArgumentType] = &[${types.map((t) => `ArgumentType::${pascal(t.name)}`).join(', ')}];`,
    '',
    '    /// Its name in Lua (`block_state`).',
    "    pub fn lua_name(self) -> &'static str {",
    '        match self {',
    ...types.map(
      (t) => `            ArgumentType::${pascal(t.name)} => ${JSON.stringify(t.name)},`,
    ),
    '        }',
    '    }',
    '',
    '    /// What the server reads it as.',
    '    pub fn reading(self) -> ArgumentReading {',
    '        match self {',
    ...types.map(
      (t) =>
        `            ArgumentType::${pascal(t.name)} => ArgumentReading::${pascal(t.reading)},`,
    ),
    '        }',
    '    }',
    '',
    "    /// Whether it's a word only the runtime can resolve and complete.",
    '    pub fn names(self) -> bool {',
    `        matches!(self, ${
      types
        .filter((t) => t.names)
        .map((t) => `ArgumentType::${pascal(t.name)}`)
        .join(' | ') || 'Self::__None'
    })`,
    '    }',
    '',
    '    /// The type a Lua name names.',
    '    pub fn of(lua_name: &str) -> Option<ArgumentType> {',
    '        ArgumentType::ALL.iter().copied().find(|t| t.lua_name() == lua_name)',
    '    }',
    '}',
  ])
}

/**
 * `crates/api/src/generated/arguments.rs`: a command argument's `default` read by the codec its type's value
 * crosses by (Kotlin's `ArgumentCodecs.kt`), so a default is a value the handler could have got.
 */
export function argumentCodecsRust(bindings: Bindings): string {
  return finish([
    ...header(
      [
        "A command argument's `default`, read by the codec its type's value crosses by (`Player` for `player`,",
        '`Vec3` for `position`): a mistake names `at`.',
      ],
      ['mlua::{Lua, Value}', 'crate::codec::*', 'crate::{handles, ApiResult}'],
    ),
    "/// Reads `value` as a value of the argument type `type_name` (its Lua name); none for a type there isn't.",
    'pub fn read_default(lua: &Lua, type_name: &str, value: Value, at: &str) -> Option<ApiResult<()>> {',
    '    Some(match type_name {',
    ...bindings.commandArguments.map(
      ({ type, codec }) =>
        `        ${JSON.stringify(type.name)} => ${codecExpr(codec)}.read(lua, value, at).map(|_| ()),`,
    ),
    '        _ => return None,',
    '    })',
    '}',
    '',
    "/// What a default of argument type `type_name` was expected to be, for a mistake's words.",
    'pub fn expected(type_name: &str) -> Option<String> {',
    '    Some(match type_name {',
    ...bindings.commandArguments.map(
      ({ type, codec }) =>
        `        ${JSON.stringify(type.name)} => ${codecExpr(codec)}.expected(),`,
    ),
    '        _ => return None,',
    '    })',
    '}',
  ])
}
