/** The spec's type grammar (see `types.ts`), parsed. */

export type TypeNode =
  | { kind: 'named'; name: string; args: TypeNode[] }
  | { kind: 'optional'; inner: TypeNode }
  | { kind: 'function'; params: { name?: string; type: TypeNode }[]; returns: TypeNode }
  | { kind: 'star' }

export function parseType(text: string): TypeNode {
  let at = 0
  const peek = () => text[at]
  const skip = () => {
    while (text[at] === ' ') at++
  }
  const type = (): TypeNode => {
    skip()
    let node: TypeNode
    if (peek() === '(') {
      // `(a: A, B) -> R`, or a parenthesized type `((A) -> R)?`
      at++
      const params: { name?: string; type: TypeNode }[] = []
      skip()
      while (peek() !== ')') {
        const named = /^([A-Za-z_][A-Za-z0-9_]*)\s*:/.exec(text.slice(at))
        let name: string | undefined
        if (named) {
          name = named[1]
          at += named[0].length
        }
        params.push({ ...(name ? { name } : {}), type: type() })
        skip()
        if (peek() === ',') at++
        skip()
      }
      at++
      skip()
      if (text.startsWith('->', at)) {
        at += 2
        node = { kind: 'function', params, returns: type() }
      } else {
        if (params.length !== 1) throw new Error(`${text}: a parenthesized type holds one type`)
        node = params[0]!.type
      }
    } else if (peek() === '*') {
      at++
      node = { kind: 'star' }
    } else {
      const name = /^[A-Za-z_][A-Za-z0-9_.]*/.exec(text.slice(at))
      if (!name) throw new Error(`${text}: a type expected at ${at}`)
      at += name[0].length
      const args: TypeNode[] = []
      skip()
      if (peek() === '<') {
        at++
        for (;;) {
          args.push(type())
          skip()
          if (peek() === ',') {
            at++
            continue
          }
          if (peek() !== '>') throw new Error(`${text}: '>' expected at ${at}`)
          at++
          break
        }
      }
      node = { kind: 'named', name: name[0], args }
    }
    skip()
    while (peek() === '?') {
      at++
      node = { kind: 'optional', inner: node }
    }
    return node
  }
  const node = type()
  skip()
  if (at !== text.length) throw new Error(`${text}: unexpected ${text.slice(at)}`)
  return node
}

/** The types every language has: Kotlin's names for them. */
export const PRIMITIVES = new Set([
  'String',
  'Int',
  'Long',
  'Short',
  'Byte',
  'Double',
  'Float',
  'Boolean',
  'Unit',
  'UUID',
  'ByteArray',
  'IntArray',
  'ShortArray',
  'BooleanArray',
  'Any',
  /** An error: its message, and maybe its cause's. */
  'Throwable',
])

/** The generic types: how many arguments each takes. */
export const GENERICS: Record<string, number> = {
  List: 1,
  Set: 1,
  Map: 2,
  Pair: 2,
  Triple: 3,
  Result: 1,
}

/** Every declared or external name [node] mentions. */
export function namesIn(node: TypeNode): string[] {
  switch (node.kind) {
    case 'named':
      return [node.name, ...node.args.flatMap(namesIn)]
    case 'optional':
      return namesIn(node.inner)
    case 'function':
      return [...node.params.flatMap((p) => namesIn(p.type)), ...namesIn(node.returns)]
    case 'star':
      return []
  }
}
