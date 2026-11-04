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
import { GENERATED_BY } from './common.ts'
import {
  pascal,
  reachable,
  type Bindings,
  type ClassBinding,
  type Codec,
  type FnBinding,
  type ReturnBinding,
} from './bindings.ts'

const KEYWORDS = new Set(
  (
    'as async await box break const continue crate dyn else enum extern false final fn for gen if ' +
    'impl in let loop macro match mod move mut override priv pub ref return self static struct ' +
    'super trait true try type typeof unsafe unsized use virtual where while yield abstract ' +
    'become do'
  ).split(' '),
)

/** A Lua name as a Rust identifier: `type` → `r#type`. */
const ident = (name: string) => (KEYWORDS.has(name) ? `r#${name}` : name)

/** `play_animation` stays; `nf.server` → `nf_server`. */
const snake = (name: string) => name.replace(/\./g, '_')

/** The file's first lines: who made it, what it is ([about], a module doc), what it uses. */
function header(about: string[], uses: string[]): string[] {
  return [
    `// ${GENERATED_BY}`,
    '',
    ...about.map((line) => `//!${line ? ` ${line}` : ''}`),
    '',
    ...uses.map((it) => `use ${it};`),
    ...(uses.length ? [''] : []),
  ]
}

function finish(lines: string[]): string {
  return `${lines.join('\n').replace(/\n{3,}/g, '\n\n')}\n`
}

/** A doc comment: each line of [doc] behind `///`, at [indent]. */
function rustdoc(doc: string, indent: string): string[] {
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
      [],
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

function signature(binding: ClassBinding, fn: FnBinding): string {
  const first = fn.method ? `this: &handles::${binding.cls.name}` : 'caller: &Caller'
  const params = fn.params.map((p) => `${ident(p.name)}: ${rustType(p.codec)}`)
  return `fn ${ident(fn.fn.name)}(&self, ${[first, ...params].join(', ')}) -> ApiResult<${returnType(fn.returns)}>;`
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
        'crate::{ApiResult, Caller, Pending, handles, shapes, unions, values}',
      ],
    ),
    "/// Every class's implementation.",
    'pub trait LuaApi {',
    ...withFunctions.map(
      (it) => `    fn ${snake(it.cls.name).toLowerCase()}(&self) -> &dyn ${traitName(it)};`,
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
