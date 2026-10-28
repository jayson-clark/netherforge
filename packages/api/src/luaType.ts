/**
 * The grammar of `LuaType`: the LuaLS type expressions the spec is written in,
 * parsed into a tree. The generator, the spec's tests and the editor's analyzer
 * all read types through this one parser, so a type means the same thing to
 * each of them.
 *
 * ```text
 * type     = single { "|" single }
 * single   = base { "[]" | "?" }
 * base     = "(" type ")" | string | [ "async" ] "fun" [ "(" params ")" ] [ ":" returns ]
 *          | "table" "<" type "," type ">" | name
 * params   = [ param { "," param } ]
 * param    = "..." [ ":" type ] | name [ "?" ] [ ":" type ]
 * returns  = type { "," type }
 * name     = letter { letter | digit | "_" | "." }
 * ```
 *
 * A `,` after a function's return type starts another return of the innermost
 * `fun`, unless what follows is a parameter (`name:` or `...`): in
 * `fun(s: string): fun(): integer, integer` the inner function returns two
 * values, and in `fun(a: fun(): x, b: y)` the comma belongs to the outer list.
 *
 * `async fun(...)` is LuaLS's: a function that may wait (`nf.task`'s callback), which
 * lua-language-server lets call the functions that wait (`---@async`). To everything else it's
 * a function like any other.
 */
import type { LuaType } from './types.ts'

/** Names Lua and LuaLS know without the spec declaring them. */
export const PRIMITIVES = [
  'any',
  'boolean',
  'function',
  'integer',
  'nil',
  'number',
  'string',
  'table',
  'thread',
  'userdata',
  'unknown',
] as const

export type PrimitiveName = (typeof PRIMITIVES)[number]

export interface FunParam {
  name: string
  type: TypeNode
  /** Written `name?: type`. */
  optional: boolean
}

export type TypeNode =
  | { kind: 'primitive'; name: PrimitiveName }
  /** A class, shape or alias the spec declares: `Centity`, `ClickEvent`, `nf.Event`. */
  | { kind: 'named'; name: string }
  | { kind: 'literal'; value: string }
  /** `T?`: `T` or nil. */
  | { kind: 'optional'; type: TypeNode }
  /** `T[]`. */
  | { kind: 'array'; item: TypeNode }
  /** `table<K, V>`. */
  | { kind: 'map'; key: TypeNode; value: TypeNode }
  | { kind: 'union'; types: TypeNode[] }
  /**
   * `fun(a: T, ...: U): R1, R2`. `vararg` is the type of `...`, when it's there; `async` when
   * it's written `async fun(...)`.
   */
  | { kind: 'fun'; params: FunParam[]; vararg?: TypeNode; returns: TypeNode[]; async?: true }

/** A type expression that doesn't follow the grammar, with where it went wrong. */
export class LuaTypeError extends Error {
  readonly text: string
  readonly offset: number

  constructor(text: string, offset: number, problem: string) {
    super(`${problem} at ${offset} in type ${JSON.stringify(text)}`)
    this.text = text
    this.offset = offset
  }
}

const isPrimitive = (name: string): name is PrimitiveName =>
  (PRIMITIVES as readonly string[]).includes(name)

/** Parses a type expression. Throws [LuaTypeError] for anything the grammar doesn't allow. */
export function parseLuaType(text: LuaType): TypeNode {
  let i = 0
  const fail = (problem: string): never => {
    throw new LuaTypeError(text, i, problem)
  }
  const skip = () => {
    while (i < text.length && /\s/.test(text[i]!)) i += 1
  }
  const eat = (token: string) => {
    skip()
    if (text.startsWith(token, i)) {
      i += token.length
      return true
    }
    return false
  }
  const expect = (token: string) => {
    if (!eat(token)) fail(`expected "${token}"`)
  }
  const name = (): string | null => {
    skip()
    const match = /^[A-Za-z_][\w.]*/.exec(text.slice(i))
    if (!match) return null
    i += match[0].length
    return match[0]
  }

  const type = (): TypeNode => {
    const options = [single()]
    while (eat('|')) options.push(single())
    return options.length === 1 ? options[0]! : { kind: 'union', types: options }
  }

  const single = (): TypeNode => {
    let node = base()
    for (;;) {
      if (eat('[]')) node = { kind: 'array', item: node }
      else if (eat('?')) node = { kind: 'optional', type: node }
      else return node
    }
  }

  const base = (): TypeNode => {
    skip()
    if (eat('(')) {
      const inner = type()
      expect(')')
      return inner
    }
    const quote = text[i]
    if (quote === '"' || quote === "'") {
      const end = text.indexOf(quote, i + 1)
      if (end < 0) fail('unterminated string')
      const value = text.slice(i + 1, end)
      i = end + 1
      return { kind: 'literal', value }
    }
    const word = name()
    if (word === null) return fail('expected a type')
    if (word === 'fun') return fun()
    if (word === 'async') {
      if (name() !== 'fun') fail('expected "fun" after "async"')
      return { ...fun(), async: true }
    }
    if (word === 'table' && eat('<')) {
      const key = type()
      expect(',')
      const value = type()
      expect('>')
      return { kind: 'map', key, value }
    }
    return isPrimitive(word) ? { kind: 'primitive', name: word } : { kind: 'named', name: word }
  }

  const fun = (): Extract<TypeNode, { kind: 'fun' }> => {
    const params: FunParam[] = []
    let vararg: TypeNode | undefined
    if (eat('(') && !eat(')')) {
      do {
        if (vararg) fail('"..." must be the last parameter')
        if (eat('...')) {
          vararg = eat(':') ? type() : { kind: 'primitive', name: 'any' }
          continue
        }
        const param = name()
        if (param === null) fail('expected a parameter name')
        const optional = eat('?')
        const paramType: TypeNode = eat(':') ? type() : { kind: 'primitive', name: 'any' }
        params.push({ name: param!, type: paramType, optional })
      } while (eat(','))
      expect(')')
    }
    const returns: TypeNode[] = []
    if (eat(':')) {
      returns.push(type())
      while (anotherReturn()) returns.push(type())
    }
    return { kind: 'fun', params, ...(vararg ? { vararg } : {}), returns }
  }

  const anotherReturn = () => {
    const save = i
    if (!eat(',')) return false
    const rest = text.slice(i)
    if (/^\s*[A-Za-z_]\w*\??\s*:/.test(rest) || /^\s*\.\.\./.test(rest)) {
      i = save
      return false
    }
    return true
  }

  const node = type()
  skip()
  if (i < text.length) fail('unexpected text')
  return node
}

/** Every declared name a type mentions, in order: `fun(e: ClickEvent): Node?` → ClickEvent, Node. */
export function namedTypes(node: TypeNode): string[] {
  switch (node.kind) {
    case 'primitive':
    case 'literal':
      return []
    case 'named':
      return [node.name]
    case 'optional':
      return namedTypes(node.type)
    case 'array':
      return namedTypes(node.item)
    case 'map':
      return [...namedTypes(node.key), ...namedTypes(node.value)]
    case 'union':
      return node.types.flatMap(namedTypes)
    case 'fun':
      return [
        ...node.params.flatMap((p) => namedTypes(p.type)),
        ...(node.vararg ? namedTypes(node.vararg) : []),
        ...node.returns.flatMap(namedTypes),
      ]
  }
}

/** Whether nil is one of the values: `T?`, `T|nil`, `any`. */
export function acceptsNil(node: TypeNode): boolean {
  switch (node.kind) {
    case 'optional':
      return true
    case 'primitive':
      return node.name === 'nil' || node.name === 'any' || node.name === 'unknown'
    case 'union':
      return node.types.some(acceptsNil)
    default:
      return false
  }
}

/** The type without its nil: `T?` → `T`, `A|B|nil` → `A|B`. */
export function withoutNil(node: TypeNode): TypeNode {
  if (node.kind === 'optional') return withoutNil(node.type)
  if (node.kind !== 'union') return node
  const rest = node.types
    .filter((it) => !(it.kind === 'primitive' && it.name === 'nil'))
    .map(withoutNil)
  return rest.length === 1 ? rest[0]! : { kind: 'union', types: rest }
}
