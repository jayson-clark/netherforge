/**
 * The Kotlin half of the bindings, in `dev.netherforge.plugin.binding`:
 *
 *  - `LuaHandle.kt`: one class per handle class, holding what identifies it, with its codec;
 *  - `LuaApi.kt`: one interface per class, which the runtime implements, so a
 *    function in the spec without an implementation is a compile error;
 *  - `LuaPrimitives.kt`: the primitive table `bindings.lua` calls, which reads
 *    each argument with its type's codec, calls the interface and pushes the results;
 *  - `LuaShapes.kt`: a data class per shape that crosses (an option table, a
 *    returned record, an event's payload), with its codec;
 *  - `LuaUnions.kt`: a sealed interface per union that crosses, with its codec;
 *  - `Events.kt`: the event registry.
 *
 * Every type maps to a Kotlin type and a codec built from the runtime's
 * `LuaCodecs` (`lua/LuaCodec.kt`): `string` → `String`, `number` → `Double`,
 * `integer` → `Long` (Lua's integers are 64-bit), `boolean` → `Boolean`, a
 * handle class → `LuaHandle.<Class>`, `Vec3` → format's `Vec3`, `Location` →
 * `LuaLocation`, `Item` → `ItemData`, `ItemMatch` → `ItemMatch`, a function →
 * `LuaFunction`, `any` → `LuaValue` taken and `Any?` given back, `T[]` →
 * `List<T>`, `table<K, V>` → `Map<K, V>`, a union of string literals →
 * `String`, a union of handle classes → `LuaHandle`, any other union → its
 * sealed interface, a shape → its data class. Several returns are a `Pair`,
 * `Triple` or `Quadruple`, null when the function returns a single nil. An
 * `async` function returns a `CompletionStage` of its value, which the
 * primitive hands to `Marshal.await` with the prelude's waker.
 */
import type { ApiSpec } from '../../src/types.ts'
import { GENERATED_BY, eventedClasses, sinceVersion } from './common.ts'
import {
  RUNTIME_TYPES,
  camel,
  classEvents,
  pascal,
  pins,
  reachable,
  type Bindings,
  type ClassBinding,
  type Codec,
  type FnBinding,
  type ReturnBinding,
  type ShapeBinding,
} from './bindings.ts'

const PACKAGE = 'dev.netherforge.plugin.binding'

function header(imports: string[]): string[] {
  return [
    `// ${GENERATED_BY}`,
    '',
    `package ${PACKAGE}`,
    '',
    ...[...imports].sort().map((it) => `import ${it}`),
    ...(imports.length ? [''] : []),
  ]
}

/** A file's text, without the imports nothing in it uses (a spec without unions needs no `LuaUnion`). */
export function finish(lines: string[]): string {
  const text = lines.join('\n')
  const body = lines.filter((it) => !it.startsWith('import ')).join('\n')
  return text
    .split('\n')
    .filter((line) => {
      const name = line.startsWith('import ') ? line.slice(line.lastIndexOf('.') + 1) : undefined
      return name === undefined || new RegExp(`\\b${name}\\b`).test(body)
    })
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
}

/** A KDoc block, wrapped. */
export function kdoc(doc: string, indent: string): string[] {
  const words = doc.replace(/\*\//g, '*&#47;').split(/\s+/).filter(Boolean)
  const lines: string[] = []
  let line = ''
  for (const word of words) {
    if (line && line.length + word.length + 1 > 100) {
      lines.push(line)
      line = word
    } else line = line ? `${line} ${word}` : word
  }
  if (line) lines.push(line)
  if (lines.length === 0) return []
  if (lines.length === 1) return [`${indent}/** ${lines[0]} */`]
  return [`${indent}/**`, ...lines.map((it) => `${indent} * ${it}`), `${indent} */`]
}

const KEYWORDS = new Set(['as', 'in', 'is', 'fun', 'object', 'val', 'var', 'when', 'class'])

export const ident = (name: string) => (KEYWORDS.has(name) ? `\`${name}\`` : name)

/**
 * Which way a value goes, for `any` and `table`: taken (`in`) it's the Lua
 * value itself, read while the call lasts (`LuaValue`); given back (`out`) it's
 * whatever Kotlin has (`Any?`).
 */
type Direction = 'in' | 'out'

const PRIMITIVE_TYPES: Record<string, string> = {
  string: 'String',
  number: 'Double',
  integer: 'Long',
  boolean: 'Boolean',
  function: 'LuaFunction',
}

/** [qualified] spells types in full, for a union's cases, whose names may shadow them (`String`). */
function kotlinType(codec: Codec, direction: Direction, qualified = false): string {
  switch (codec.kind) {
    case 'string':
    case 'number':
    case 'integer':
    case 'boolean':
    case 'function': {
      const name = PRIMITIVE_TYPES[codec.kind]!
      if (!qualified) return name
      return codec.kind === 'function' ? `dev.netherforge.plugin.lua.${name}` : `kotlin.${name}`
    }
    case 'any':
      if (direction === 'out') return 'Any?'
      return qualified ? 'dev.netherforge.plugin.lua.LuaValue' : 'LuaValue'
    case 'choice':
      return qualified ? 'kotlin.String' : 'String'
    case 'optional': {
      const inner = kotlinType(codec.inner, direction, qualified)
      return inner.endsWith('?') ? inner : `${inner}?`
    }
    case 'list':
      return `List<${kotlinType(codec.item, direction, qualified)}>`
    case 'map':
      return `Map<${kotlinType(codec.key, direction, qualified)}, ${kotlinType(codec.value, direction, qualified)}>`
    case 'union':
      return codec.handlesOnly ? 'LuaHandle' : codec.name
    case 'handle':
      return `LuaHandle.${codec.cls.name}`
    case 'runtime':
      return qualified ? RUNTIME_TYPES[codec.name]!.qualified : RUNTIME_TYPES[codec.name]!.kotlin
    case 'shape':
      return qualified ? `${PACKAGE}.${codec.name}` : codec.name
  }
}

const CODECS: Record<string, string> = {
  string: 'LuaCodecs.STRING',
  number: 'LuaCodecs.NUMBER',
  integer: 'LuaCodecs.INTEGER',
  boolean: 'LuaCodecs.BOOLEAN',
  function: 'LuaCodecs.FUNCTION',
}

/**
 * A Kotlin expression building the codec for [codec]. A shape in [lazy] is one whose codec
 * this one is part of (it holds itself), named lazily since it isn't built yet. [qualified]
 * names shapes in full, for a union's cases, which shadow them.
 */
function codecExpr(
  codec: Codec,
  direction: Direction,
  lazy: Set<string> = new Set(),
  qualified = false,
): string {
  switch (codec.kind) {
    case 'string':
    case 'number':
    case 'integer':
    case 'boolean':
    case 'function':
      return CODECS[codec.kind]!
    case 'any':
      if (direction === 'out') return 'LuaCodecs.DYNAMIC'
      return codec.table ? 'LuaCodecs.TABLE' : 'LuaCodecs.VALUE'
    case 'choice':
      return `LuaChoice(listOf(${codec.choices.map((it) => JSON.stringify(it)).join(', ')}))`
    case 'optional':
      // `any` given back is any value already, nil included.
      if (codec.inner.kind === 'any' && direction === 'out') return 'LuaCodecs.DYNAMIC'
      return `LuaOptional(${codecExpr(codec.inner, direction, lazy, qualified)})`
    case 'list':
      return `LuaList(${codecExpr(codec.item, direction, lazy, qualified)})`
    case 'map':
      return `LuaMap(${codecExpr(codec.key, direction, lazy, qualified)}, ${codecExpr(codec.value, direction, lazy, qualified)})`
    case 'union':
      return codec.handlesOnly ? `LuaUnions.${codec.name}` : `${codec.name}.Codec`
    case 'handle':
      return `LuaHandle.${codec.cls.name}.Codec`
    case 'runtime':
      return RUNTIME_TYPES[codec.name]!.codec
    case 'shape': {
      const name = qualified ? `${PACKAGE}.${codec.name}` : codec.name
      return lazy.has(codec.name) ? `LuaLazy("table") { ${name}.Codec }` : `${name}.Codec`
    }
  }
}

/**
 * Codecs built once per file, not per call: each distinct expression that
 * builds one (`LuaOptional(...)`) becomes a private value; one that names a
 * codec already (`LuaCodecs.STRING`, `Centity.Codec`) is used as it is.
 */
class Hoisted {
  private readonly names = new Map<string, string>()

  use(expr: string): string {
    if (!expr.includes('(')) return expr
    let name = this.names.get(expr)
    if (!name) {
      name = `CODEC_${this.names.size + 1}`
      this.names.set(expr, name)
    }
    return name
  }

  lines(prefix = 'private val'): string[] {
    return [...this.names].map(([expr, name]) => `${prefix} ${name} = ${expr}`)
  }
}

const nullable = (type: string, optional: boolean) =>
  optional && !type.endsWith('?') ? `${type}?` : type

function returnType(returns: ReturnBinding): string {
  switch (returns.kind) {
    case 'none':
      return ''
    case 'one':
      return kotlinType(returns.codec, 'out')
    case 'tuple': {
      const tuple = ['', '', 'Pair', 'Triple', 'Quadruple'][returns.codecs.length]!
      const types = returns.codecs.map((it) => kotlinType(it, 'out')).join(', ')
      return nullable(`${tuple}<${types}>`, returns.optional)
    }
    case 'async':
      return `CompletionStage<${kotlinType(returns.codec, 'out')}>`
  }
}

/** `CentityApi`, `NfApi`. */
const interfaceName = (binding: ClassBinding) => `${pascal(binding.cls.name)}Api`

/** The aggregate's property for a class: `centity`, `nf`. */
const propertyName = (binding: ClassBinding) =>
  camel(binding.cls.name[0]!.toLowerCase() + binding.cls.name.slice(1))

function signature(binding: FnBinding): string {
  const first = binding.method ? `self: LuaHandle.${binding.cls.name}` : 'caller: Caller'
  const params = binding.params.map((p) => `${ident(camel(p.name))}: ${kotlinType(p.codec, 'in')}`)
  const returns = returnType(binding.returns)
  return `fun ${ident(camel(binding.fn.name))}(${[first, ...params].join(', ')})${returns ? `: ${returns}` : ''}`
}

// ---- LuaHandle.kt ------------------------------------------------------------------

/** `31 * (31 * "Node".hashCode() + centity.hashCode()) + name.hashCode()`: equal for one thing whatever its class. */
function hashOf(root: string, fields: string[]): string {
  return fields.reduce(
    (hash, field) => `31 * ${hash.includes('+') ? `(${hash})` : hash} + ${field}.hashCode()`,
    `"${root}".hashCode()`,
  )
}

export function handlesKotlin(classes: ClassBinding[]): string {
  const handles = classes.flatMap((it) => (it.handle ? [{ ...it, handle: it.handle }] : []))
  const extended = new Set(handles.flatMap((it) => (it.handle.parent ? [it.handle.parent] : [])))
  const lines = [
    ...header(['dev.netherforge.plugin.lua.HandleCodec']),
    '/**',
    ' * A handle as Kotlin sees it: the values that identify it, and the class it is. Lua holds',
    " * one as its key in the host's handle table, where Kotlin finds it again, so a script",
    " * can't read or forge what it's made of; two of the same thing are the same table.",
    ' *',
    ' * A class that extends another is a Kotlin subclass of it (a [Mob] is a [Living] is an',
    ' * [Entity]), and equal to a handle of any class in its chain with the same key: a thing is',
    ' * one handle, of the most specific class the runtime knows it to be.',
    ' */',
    'sealed interface LuaHandle {',
    '    /** The Lua class it is a handle of. */',
    '    val luaClass: String',
    '',
    '    /** The values that identify it, in order: each a String or a Long. */',
    '    val keyValues: List<Any>',
    '',
    '    companion object {',
    '        /** The class each handle class extends. */',
    `        val PARENTS: Map<String, String> = ${
      handles.some((it) => it.handle.parent)
        ? `mapOf(${handles
            .filter((it) => it.handle.parent)
            .map((it) => `"${it.cls.name}" to "${it.handle.parent}"`)
            .join(', ')})`
        : 'emptyMap()'
    }`,
    '',
    '        /**',
    '         * A handle from its key values, as `new.<Class>` in the prelude asks for one: of the class at',
    '         * the top of its chain, which the runtime may know more about (`LuaMarshal.refine`).',
    '         */',
    '        fun build(luaClass: String, keyValues: List<Any>): LuaHandle = when (luaClass) {',
  ]
  for (const { cls, handle } of handles) {
    if (handle.parent) continue
    const args = handle.fields.map(
      (f, k) => `keyValues[${k}] as ${f.type === 'integer' ? 'Long' : 'String'}`,
    )
    lines.push(`            "${cls.name}" -> ${cls.name}(${args.join(', ')})`)
  }
  lines.push(
    '            else -> throw IllegalArgumentException("no handle class $luaClass at the top of a chain")',
    '        }',
    '    }',
  )
  for (const { cls, handle } of handles) {
    const name = cls.name
    const fields = handle.fields.map((f) => ({
      name: camel(f.name),
      type: f.type === 'integer' ? 'Long' : 'String',
    }))
    const open = extended.has(name) ? 'open ' : ''
    // An open class's identity is final: equal across its chain, whatever class a handle is.
    const kind = extended.has(name) ? 'final ' : ''
    const inherits = handle.parent
      ? `${handle.parent}(${fields.map((f) => f.name).join(', ')})`
      : 'LuaHandle'
    const params = handle.parent
      ? fields.map((f) => `${f.name}: ${f.type}`).join(', ')
      : fields.map((f) => `val ${f.name}: ${f.type}`).join(', ')
    lines.push(
      '',
      ...kdoc(cls.doc.split('. ')[0]!.replace(/\.$/, '') + '.', '    '),
      `    ${open}class ${name}(${params}) : ${inherits} {`,
      `        override val luaClass: String get() = "${name}"`,
    )
    if (!handle.parent) {
      const key = fields.map((f) => f.name)
      lines.push(
        '',
        `        ${kind}override val keyValues: List<Any> get() = listOf(${key.join(', ')})`,
        '',
        `        ${kind}override fun equals(other: Any?): Boolean = other is ${name} && ${key.map((f) => `other.${f} == ${f}`).join(' && ')}`,
        '',
        `        ${kind}override fun hashCode(): Int = ${hashOf(handle.root, key)}`,
        '',
        `        ${kind}override fun toString(): String = "$luaClass(${key.map((f) => `$${f}`).join(', ')})"`,
      )
    }
    const accepted = handle.descendants.map((it) => `"${it}"`).join(', ')
    lines.push(
      '',
      `        /** ${handle.descendants.length > 1 ? `${name} handles, and those of every class below it.` : `${name} handles.`} */`,
      `        object Codec : HandleCodec<${name}>("${name}", setOf(${accepted}))`,
      '    }',
    )
  }
  lines.push(
    '}',
    '',
    '/** Four returns, for the functions that have them. */',
    'data class Quadruple<out A, out B, out C, out D>(val first: A, val second: B, val third: C, val fourth: D)',
  )
  return finish(lines)
}

// ---- LuaApi.kt ---------------------------------------------------------------------

export function apiKotlin(classes: ClassBinding[]): string {
  const withFunctions = classes.filter((it) => it.functions.length)
  const lines = [
    ...header([
      'dev.netherforge.format.math.Vec3',
      'dev.netherforge.plugin.item.ItemMatch',
      'dev.netherforge.plugin.lua.LuaFunction',
      'dev.netherforge.plugin.lua.LuaValue',
      'dev.netherforge.plugin.platform.ItemData',
      'java.util.concurrent.CompletionStage',
    ]),
    '/**',
    ' * Everything the runtime implements for the Lua API: one interface per class, each',
    ' * function in the spec that isn\'t `impl: "lua"`. A handle method gets the handle as',
    ' * `self`; a namespace function gets the calling script as `caller`.',
    ' */',
    'interface LuaApi {',
    ...withFunctions.map((it) => `    val ${propertyName(it)}: ${interfaceName(it)}`),
    '}',
  ]
  for (const binding of withFunctions) {
    lines.push('', ...kdoc(binding.cls.doc, ''), `interface ${interfaceName(binding)} {`)
    binding.functions.forEach((fn, index) => {
      if (index) lines.push('')
      lines.push(...kdoc(fn.fn.doc, '    '), `    ${signature(fn)}`)
    })
    lines.push('}')
  }
  return finish(lines)
}

// ---- LuaPrimitives.kt --------------------------------------------------------------

/** How a script calls it, in a message: `Player:ban`, `nf.server.set_motd`. */
const called = (binding: ClassBinding, fn: FnBinding) =>
  `${binding.cls.name}${fn.method ? ':' : '.'}${fn.fn.name}`

function primitive(binding: ClassBinding, fn: FnBinding, codecs: Hoisted): string[] {
  const lines: string[] = ['val lua = call.lua']
  let index = 1
  const args: string[] = []
  if (fn.method) {
    // `self_of` checked its class and passed its key in the handle table.
    lines.push(`val self = call.self(${index}) as LuaHandle.${binding.cls.name}`)
    index += 1
    args.push('self')
  } else {
    lines.push(`val caller = marshal.caller(lua, ${index})`)
    index += 1
    args.push('caller')
  }
  // What the calling package must have declared, checked before anything is read or done.
  if (fn.fn.requires)
    lines.push(
      `marshal.requires(${JSON.stringify(fn.fn.requires)}, ${JSON.stringify(called(binding, fn))})`,
    )
  for (const param of fn.params) {
    const name = ident(camel(param.name))
    if (param.spread) {
      const read = param.codec.kind === 'optional' ? 'vec3OrNull' : 'vec3'
      lines.push(`val ${name} = call.${read}(${index})`)
      index += 3
    } else {
      const codec = codecs.use(codecExpr(param.codec, 'in'))
      lines.push(`val ${name} = call.arg(${index}, ${JSON.stringify(param.name)}, ${codec})`)
      index += 1
    }
    args.push(name)
  }
  const invoke = `api.${propertyName(binding)}.${ident(camel(fn.fn.name))}(${args.join(', ')})`
  const returns = fn.returns
  if (returns.kind === 'async') {
    // The work has started (or the call failed, keeping nothing): only now is the waker kept,
    // and the wait's id goes back for `Task:cancel`. A method's wait belongs to the scope whose
    // code called it, which the binding passes after the arguments.
    const codec = codecs.use(codecExpr(returns.codec, 'out'))
    if (fn.method) {
      lines.push(`val caller = marshal.caller(lua, ${index})`)
      index += 1
    }
    lines.push(
      `val result = ${invoke}`,
      `lua.push(marshal.await(${JSON.stringify(called(binding, fn))}, caller.scope, result, call.keep(${index}), ${codec}).toLong())`,
      '1',
    )
  } else if (returns.kind === 'none') {
    lines.push(invoke, '0')
  } else if (returns.kind === 'one') {
    lines.push(`val result = ${invoke}`)
    if (returns.spread) {
      if (returns.codec.kind === 'optional')
        lines.push('if (result == null) {', '    lua.pushNil()', '    return@fn 1', '}')
      lines.push('call.pushVec3(result)', '3')
    } else {
      lines.push(`call.push(result, ${codecs.use(codecExpr(returns.codec, 'out'))})`, '1')
    }
  } else {
    lines.push(`val result = ${invoke}`)
    if (returns.optional)
      lines.push('if (result == null) {', '    lua.pushNil()', '    return@fn 1', '}')
    const parts = ['first', 'second', 'third', 'fourth']
    returns.codecs.forEach((codec, k) =>
      lines.push(`call.push(result.${parts[k]}, ${codecs.use(codecExpr(codec, 'out'))})`),
    )
    lines.push(`${returns.codecs.length}`)
  }
  return [`fn(${JSON.stringify(fn.primitive)}) { call ->`, ...lines.map((l) => `    ${l}`), '}']
}

export function primitivesKotlin(classes: ClassBinding[]): string {
  const codecs = new Hoisted()
  const body: string[] = []
  for (const binding of classes) {
    for (const fn of binding.functions) {
      if (body.length) body.push('')
      body.push(...primitive(binding, fn, codecs).map((l) => (l ? `    ${l}` : l)))
    }
  }
  const lines = [
    ...header([
      'dev.netherforge.plugin.lua.LuaCall',
      'dev.netherforge.plugin.lua.LuaChoice',
      'dev.netherforge.plugin.lua.LuaCodecs',
      'dev.netherforge.plugin.lua.LuaList',
      'dev.netherforge.plugin.lua.LuaMap',
      'dev.netherforge.plugin.lua.LuaOptional',
      'dev.netherforge.plugin.lua.Primitive',
    ]),
    '/**',
    ' * The primitive table `bindings.lua` calls, by name (`Centity.play`): each reads its',
    " * arguments with their types' codecs (a mistake is an error at the script's line),",
    ' * calls [api] and pushes the results back with theirs.',
    ' */',
    'fun luaPrimitives(api: LuaApi, marshal: Marshal): Map<String, Primitive> = buildMap {',
    '    /**',
    '     * A primitive whose body reads and pushes through a [LuaCall] on the host [marshal] runs in.',
    '     * A call that fails lets go of the functions its arguments kept ([LuaCall.releasing]).',
    '     */',
    '    fun fn(name: String, body: (LuaCall) -> Int) {',
    '        put(name, Primitive { lua -> LuaCall(marshal.host, lua).releasing(body) })',
    '    }',
    '',
    ...body,
    '}',
    '',
    ...codecs.lines(),
  ]
  return finish(lines)
}

// ---- LuaShapes.kt ------------------------------------------------------------------

/** Every shape some binding reads or pushes, one data class each, with its codec. */
export function shapesKotlin(spec: ApiSpec, bindings: Bindings): string {
  const writable = new Map<string, Set<string>>()
  for (const cls of eventedClasses(spec))
    for (const event of cls.events)
      if (event.payload) {
        const fields = writable.get(event.payload) ?? new Set()
        for (const name of event.writable ?? []) fields.add(name)
        writable.set(event.payload, fields)
      }
  const lines = [
    ...header([
      'dev.netherforge.format.math.Vec3',
      'dev.netherforge.plugin.item.ItemMatch',
      'dev.netherforge.plugin.lua.LuaCall',
      'dev.netherforge.plugin.lua.LuaChoice',
      'dev.netherforge.plugin.lua.LuaCodec',
      'dev.netherforge.plugin.lua.LuaCodecs',
      'dev.netherforge.plugin.lua.LuaFunction',
      'dev.netherforge.plugin.lua.LuaLazy',
      'dev.netherforge.plugin.lua.LuaList',
      'dev.netherforge.plugin.lua.LuaMap',
      'dev.netherforge.plugin.lua.LuaOptional',
      'dev.netherforge.plugin.lua.LuaShape',
      'dev.netherforge.plugin.lua.LuaValue',
      'dev.netherforge.plugin.lua.ShapeCodec',
      'dev.netherforge.plugin.lua.ShapeReader',
      'dev.netherforge.plugin.lua.ShapeWriter',
      'dev.netherforge.plugin.platform.ItemData',
    ]),
    '/*',
    ' * The shapes that cross: option tables functions take, records they return,',
    " * events' payloads. Each is a data class with its codec: read strictly (a key",
    " * that isn't a field is an error), pushed as a table of the fields that are set.",
    ' */',
  ]
  const reaches = reachable(bindings.shapes)
  const byName = new Map(bindings.shapes.map((it) => [it.shape.name, it]))
  for (const binding of bindings.shapes) {
    const name = binding.shape.name
    // The shapes whose codecs this one's is part of: each reaches back to it.
    const lazy = new Set([...reaches.get(name)!].filter((it) => reaches.get(it)!.has(name)))
    const pinned = pins({ kind: 'shape', name }, byName)
    lines.push('', ...shapeClass(binding, lazy, pinned, writable.get(name)))
  }
  return finish(lines)
}

function shapeClass(
  binding: ShapeBinding,
  lazy: Set<string>,
  pinned: boolean,
  writable: Set<string> = new Set(),
): string[] {
  const { shape } = binding
  // An `any` field is the Lua value itself while a call reads it, and whatever Kotlin has otherwise.
  const direction: Direction = binding.pushed ? 'out' : 'in'
  const name = shape.name
  const fields = binding.fields.map((field) => ({
    ...field,
    any:
      field.codec.kind === 'any' ||
      (field.codec.kind === 'optional' && field.codec.inner.kind === 'any'),
    kotlin: ident(camel(field.name)),
    codec: `${camel(field.name)}Codec`,
    type: kotlinType(field.codec, direction),
    expr: codecExpr(field.codec, direction, lazy),
  }))
  const params = fields
    .map(
      (f) =>
        `${writable.has(f.name) ? 'var' : 'val'} ${f.kotlin}: ${f.type}${f.type.endsWith('?') ? ' = null' : ''}`,
    )
    .join(', ')
  const isEvent = shape.extends === 'Event'
  const lines = [
    ...kdoc(shape.doc, ''),
    `data class ${name}(${params}) : ${isEvent ? 'LuaEvent' : 'LuaShape'} {`,
    '    override fun push(call: LuaCall) = Codec.push(call, this)',
  ]
  if (writable.size) {
    lines.push(
      '',
      '    override fun write(field: String, call: LuaCall, index: Int) {',
      '        when (field) {',
    )
    // A writable `any` is read as data (JSON) there and then: the Lua value it is goes once the handlers have run.
    for (const f of fields.filter((it) => writable.has(it.name)))
      lines.push(
        `            ${JSON.stringify(f.name)} -> ${f.kotlin} = ${f.any ? 'LuaCodecs.DATA' : `Codec.${f.codec}`}.read(call, index, "event.${f.name}")`,
      )
    lines.push('        }', '    }')
  }
  lines.push(
    '',
    `    object Codec : ShapeCodec<${name}>(${JSON.stringify(name)}) {`,
    ...fields.map((f) => `        val ${f.codec} = ${f.expr}`),
    '',
    `        override val pinned = ${pinned}`,
    '',
    '        override val fields: Map<String, LuaCodec<*>> = mapOf(',
    ...fields.map(
      (f, k) =>
        `            ${JSON.stringify(f.name)} to ${f.codec}${k === fields.length - 1 ? '' : ','}`,
    ),
    '        )',
    '',
    `        override fun read(fields: ShapeReader) = ${name}(`,
    ...fields.map(
      (f, k) =>
        `            ${f.kotlin} = fields.field(${JSON.stringify(f.name)}, ${f.codec})${k === fields.length - 1 ? '' : ','}`,
    ),
    '        )',
    '',
    `        override fun write(fields: ShapeWriter, value: ${name}) {`,
    ...fields.map(
      (f) => `            fields.field(${JSON.stringify(f.name)}, value.${f.kotlin}, ${f.codec})`,
    ),
    '        }',
    '    }',
    '}',
  )
  return lines
}

// ---- LuaUnions.kt ------------------------------------------------------------------

/**
 * Every union that crosses: a sealed interface for one mixing kinds of value
 * (`LocationOrVec3`), a case per member holding its value; and for a union of
 * handle classes only, which Kotlin sees as the `LuaHandle` it is, its codec.
 */
export function unionsKotlin(bindings: Bindings): string {
  const lines = [
    ...header([
      'dev.netherforge.plugin.lua.LuaChoice',
      'dev.netherforge.plugin.lua.LuaCodec',
      'dev.netherforge.plugin.lua.LuaCodecs',
      'dev.netherforge.plugin.lua.LuaHandleUnion',
      'dev.netherforge.plugin.lua.LuaList',
      'dev.netherforge.plugin.lua.LuaMap',
      'dev.netherforge.plugin.lua.LuaUnion',
    ]),
  ]
  const unions = [...bindings.unions.values()].sort((a, b) => a.name.localeCompare(b.name))
  const handles = unions.filter((it) => it.handlesOnly)
  for (const union of unions.filter((it) => !it.handlesOnly)) {
    const cases = union.members.map((codec) => ({
      name: memberCase(codec),
      type: kotlinType(codec, 'in', true),
      codec,
    }))
    lines.push(
      '',
      `/** A \`${cases.map((it) => it.name).join('|')}\`: whichever it was. */`,
      `sealed interface ${union.name} {`,
      ...cases.map((it) => `    data class ${it.name}(val value: ${it.type}) : ${union.name}`),
      '',
      `    object Codec : LuaCodec<${union.name}> by LuaUnion(`,
      '        listOf(',
      ...cases.map(
        (it, k) =>
          `            LuaUnion.Member<${union.name}, ${it.type}>(${codecExpr(it.codec, 'in', new Set(), true)}, ::${it.name}) { (it as? ${it.name})?.value }${k === cases.length - 1 ? '' : ','}`,
      ),
      '        )',
      '    )',
      '}',
    )
  }
  if (handles.length) {
    lines.push(
      '',
      '/** The unions of handle classes, each a [LuaHandle] of one of them, tried in order. */',
      'object LuaUnions {',
    )
    for (const union of handles)
      lines.push(
        `    val ${union.name}: LuaCodec<LuaHandle> = LuaHandleUnion(listOf(${union.members.map((it) => codecExpr(it, 'in')).join(', ')}))`,
      )
    lines.push('}')
  }
  return finish(lines)
}

/** A union member's case in its sealed interface: `Location`, `Vec3`, `String`, `PlayerList`. */
function memberCase(codec: Codec): string {
  switch (codec.kind) {
    case 'handle':
      return codec.cls.name
    case 'runtime':
    case 'shape':
      return codec.name
    case 'list':
      return `${memberCase(codec.item)}List`
    case 'map':
      return `${memberCase(codec.key)}To${memberCase(codec.value)}Map`
    case 'any':
      return codec.table ? 'Table' : 'Any'
    case 'choice':
      return 'Choice'
    case 'optional':
    case 'union':
      throw new Error('a union member is never optional or a union itself')
    default:
      return pascal(codec.kind)
  }
}

// ---- Events.kt ---------------------------------------------------------------------

export const constant = (name: string) => name.toUpperCase()

export function eventsKotlin(spec: ApiSpec): string {
  const lines = [
    ...header([
      'dev.netherforge.plugin.lua.LuaCall',
      'dev.netherforge.plugin.lua.LuaShape',
      'dev.netherforge.plugin.lua.ShapeCodec',
    ]),
    '/**',
    " * An event's payload (its data class is in `LuaShapes.kt`). Handlers may assign its",
    ' * writable fields (the `var`s); the runtime reads each back with [write], by the',
    " * field's codec, after every handler has run.",
    ' */',
    'interface LuaEvent : LuaShape {',
    '    /** Reads writable field [field] back from the value at [index]. */',
    '    fun write(field: String, call: LuaCall, index: Int) {}',
    '}',
    '',
    '/** The payload of an event that has none: handlers get a bare `Event`. */',
    'object NoPayload : LuaEvent {',
    '    override fun push(call: LuaCall) = call.lua.createTable(0, 0)',
    '}',
    '',
    '/**',
    ' * One event on one class, typed by its payload: what `Scripts.emit` takes. [owner] is the',
    ' * Lua class (`"Node"`), or `"nf"` for a server-wide event.',
    ' */',
    'class EventType<P : LuaEvent>(',
    '    val owner: String,',
    '    val luaName: String,',
    "    /** The payload's codec, or null for a bare `Event`. */",
    '    val payload: ShapeCodec<P>?,',
    '    /** `event:cancel()` cancels what caused it. */',
    '    val cancellable: Boolean,',
    "    /** It goes on along a path after this handle: a node's click to its parent's, and so on. */",
    '    val bubbles: Boolean,',
    '    /** Payload fields handlers may assign, read back after they have all run. */',
    '    val writable: List<String>,',
    '    /** Fired only to the scope that registered it, as that scope is about to close. */',
    '    val local: Boolean,',
    '    /** The oldest Minecraft version that has it, or null for every supported one. */',
    '    val since: String?',
    ') {',
    '    override fun toString(): String = if (owner == "nf") luaName else "$owner $luaName"',
    '}',
    '',
    "/** Every built-in event, by owning class: the spec's `events`. */",
    'object Events {',
  ]
  const events = eventedClasses(spec).flatMap((cls) => cls.events.map((event) => ({ cls, event })))
  for (const { cls, event } of events) {
    const payloadType = event.payload ?? 'NoPayload'
    const payload = event.payload ? `${event.payload}.Codec` : 'null'
    const writable = event.writable?.length
      ? `listOf(${event.writable.map((it) => JSON.stringify(it)).join(', ')})`
      : 'emptyList()'
    const since = event.since ? JSON.stringify(sinceVersion(event.since)) : 'null'
    lines.push(
      ...kdoc(event.doc, '    '),
      `    val ${constant(cls.name)}_${constant(event.name)} = EventType<${payloadType}>(${JSON.stringify(cls.name)}, ${JSON.stringify(event.name)}, payload = ${payload}, cancellable = ${!!event.cancellable}, bubbles = ${!!event.bubbles}, writable = ${writable}, local = ${!!event.local}, since = ${since})`,
      '',
    )
  }
  const ref = (owner: string, event: string) => `${constant(owner)}_${constant(event)}`
  lines.push(
    `    val all: List<EventType<*>> = listOf(${events.map(({ cls, event }) => ref(cls.name, event.name)).join(', ')})`,
    '',
    '    /**',
    "     * Each class's events by name, its own and those it inherits up its chain (a `Mob`'s",
    "     * `death` is `LIVING_DEATH`), a class's own replacing one of the same name further up.",
    '     */',
    '    private val byClass: Map<String, Map<String, EventType<*>>> = mapOf(',
  )
  const evented = spec.classes.filter((cls) => classEvents(spec, cls).length)
  evented.forEach((cls, k) => {
    const entries = classEvents(spec, cls).map(
      ({ event, owner }) => `${JSON.stringify(event.name)} to ${ref(owner.name, event.name)}`,
    )
    lines.push(
      `        ${JSON.stringify(cls.name)} to mapOf(${entries.join(', ')})${k === evented.length - 1 ? '' : ','}`,
    )
  })
  lines.push(
    '    )',
    '',
    '    /** The event [name] means on a handle of class [luaClass] (`"nf"` for `nf.on`), or null when it has none. */',
    '    fun of(luaClass: String, name: String): EventType<*>? = byClass[luaClass]?.get(name)',
    '}',
  )
  return finish(lines)
}

/** `Class.name` keys for [gatesKotlin]: a namespace's own name is already dotted (`nf.worlds`). */
const gateKey = (owner: string, name: string) => `${owner}.${name}`

/**
 * `VersionGates.kt`: everything the spec marks `since` a feature (functions, events, option table
 * fields), with the version format's feature table says it arrived in, which the runtime hands the
 * prelude when the server is older, so using one is an error naming the version.
 */
export function gatesKotlin(spec: ApiSpec): string {
  const functions: [string, string][] = []
  const events: [string, string][] = []
  const options: [string, string][] = []
  for (const cls of spec.classes) {
    for (const fn of cls.functions)
      if (fn.since) functions.push([gateKey(cls.name, fn.name), sinceVersion(fn.since)])
    for (const event of cls.events ?? [])
      if (event.since) events.push([gateKey(cls.name, event.name), sinceVersion(event.since)])
  }
  for (const shape of spec.shapes)
    for (const field of shape.fields)
      if (field.since) options.push([gateKey(shape.name, field.name), sinceVersion(field.since)])
  const map = (entries: [string, string][]) =>
    entries.length
      ? `mapOf(${entries.map(([key, since]) => `${JSON.stringify(key)} to ${JSON.stringify(since)}`).join(', ')})`
      : 'emptyMap()'
  return [
    ...header([]),
    '/**',
    ' * What the spec marks `since` a Minecraft version, keyed by where it is: a function as',
    ' * `Class.name` (`World.time_of_day`, `nf.worlds.get`), an event as `Class.event` (`nf.player_join`),',
    " * an option table's field as `Shape.field`. On a server older than that version, calling the",
    ' * function, listening for the event or setting the field is an error naming the version.',
    ' */',
    'data class VersionGates(',
    '    val functions: Map<String, String>,',
    '    val events: Map<String, String>,',
    '    val options: Map<String, String>',
    ') {',
    '    companion object {',
    "        /** The spec's own. */",
    `        val SPEC = VersionGates(functions = ${map(functions)}, events = ${map(events)}, options = ${map(options)})`,
    '    }',
    '}',
  ].join('\n')
}

// ---- command arguments -------------------------------------------------------------

/** `block_state` → `BLOCK_STATE`. */
const enumName = (name: string) => name.toUpperCase()

/**
 * `platform/CommandArguments.kt`: `ArgumentType`, what a command argument's `type` can be, each
 * with its Lua name, what the server reads it as and whether only the runtime can resolve it.
 * Adapter-facing, so no Lua types: the codec its value crosses by is [argumentCodecsKotlin]'s.
 */
export function argumentTypesKotlin(bindings: Bindings): string {
  const entries = bindings.commandArguments.map(({ type }, index, all) => [
    ...kdoc(`\`"${type.name}"\`: ${type.doc}.`, '    '),
    `    ${enumName(type.name)}(${JSON.stringify(type.name)}, ArgumentReading.${enumName(type.reading)}, names = ${!!type.names})${index === all.length - 1 ? ';' : ','}`,
  ])
  return [
    `// ${GENERATED_BY}`,
    '',
    'package dev.netherforge.plugin.platform',
    '',
    '/**',
    " * What a command argument takes, by its Lua name (the spec's `commandArguments`), and what the",
    ' * server reads it as ([reading]). [names]: a word only the runtime can resolve and complete (the',
    " * project's own things, the server's worlds), which the runtime turns into the handler's value.",
    ' */',
    'enum class ArgumentType(val luaName: String, val reading: ArgumentReading, val names: Boolean) {',
    ...entries.flatMap((it, index) => (index ? ['', ...it] : it)),
    '',
    '    companion object {',
    '        fun of(luaName: String): ArgumentType? = entries.firstOrNull { it.luaName == luaName }',
    '    }',
    '}',
  ].join('\n')
}

/**
 * `ArgumentCodecs.kt`: the codec each command argument type's value crosses by (`Player` for
 * `player`, `Vec3` for `position`): its handler's value, and a `default` of it.
 */
export function argumentCodecsKotlin(bindings: Bindings): string {
  const codecs = new Hoisted()
  const cases = bindings.commandArguments.map(
    ({ type, codec }) =>
      `        ArgumentType.${enumName(type.name)} -> ${codecs.use(codecExpr(codec, 'in'))}`,
  )
  return finish([
    ...header([
      'dev.netherforge.plugin.lua.LuaCodec',
      'dev.netherforge.plugin.lua.LuaCodecs',
      'dev.netherforge.plugin.lua.LuaList',
      'dev.netherforge.plugin.platform.ArgumentType',
    ]),
    "/** The codec a command argument of this type crosses by: its handler's value, and a `default` of it. */",
    'val ArgumentType.codec: LuaCodec<*>',
    '    get() = when (this) {',
    ...cases,
    '    }',
    '',
    ...codecs.lines(),
  ])
}
