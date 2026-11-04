/**
 * Reads the Platform's declarations out of the Kotlin runtime's `platform/` sources: the
 * subset of Kotlin they're written in (data classes, enums, sealed interfaces, interfaces of
 * `fun`s and `val`s, with KDoc). It's how the spec was first written (`scripts/extract.ts`),
 * and how `kotlin.test.ts` holds the spec and the Kotlin interfaces to each other until the
 * Kotlin side is generated from the spec. Bodies, computed properties (`get() = ...`),
 * private members and companion objects aren't part of the boundary and are skipped.
 */
import type { Decl, InterfaceDecl, Method, Param, PlatformFile, Property } from './types.ts'

type Token = { kind: 'word' | 'punct' | 'string' | 'doc'; text: string }

function tokenize(source: string): Token[] {
  const tokens: Token[] = []
  let i = 0
  while (i < source.length) {
    const c = source[i]!
    if (/\s/.test(c)) {
      i++
    } else if (source.startsWith('/**', i)) {
      const end = source.indexOf('*/', i + 3)
      tokens.push({ kind: 'doc', text: source.slice(i + 3, end) })
      i = end + 2
    } else if (source.startsWith('/*', i)) {
      i = source.indexOf('*/', i + 2) + 2
    } else if (source.startsWith('//', i)) {
      const end = source.indexOf('\n', i)
      i = end < 0 ? source.length : end
    } else if (c === '"') {
      let j = i + 1
      while (j < source.length && source[j] !== '"') j += source[j] === '\\' ? 2 : 1
      tokens.push({ kind: 'string', text: source.slice(i, j + 1) })
      i = j + 1
    } else if (c === "'") {
      let j = i + 1
      while (source[j] !== "'") j += source[j] === '\\' ? 2 : 1
      tokens.push({ kind: 'string', text: source.slice(i, j + 1) })
      i = j + 1
    } else if (/[A-Za-z_0-9`@]/.test(c)) {
      let j = i
      if (c === '`') {
        j = source.indexOf('`', i + 1) + 1
      } else {
        while (j < source.length && /[A-Za-z_0-9.@]/.test(source[j]!)) j++
      }
      tokens.push({ kind: 'word', text: source.slice(i, j).replace(/`/g, '') })
      i = j
    } else if (source.startsWith('->', i) || source.startsWith('::', i)) {
      tokens.push({ kind: 'punct', text: source.slice(i, i + 2) })
      i += 2
    } else {
      tokens.push({ kind: 'punct', text: c })
      i++
    }
  }
  return tokens
}

/** A KDoc block as plain text: the leading `*` of each line gone. */
function docText(raw: string): string {
  return raw
    .split('\n')
    .map((line) => line.replace(/^\s*\*\s?/, '').trimEnd())
    .join('\n')
    .trim()
}

const MODIFIERS = new Set([
  'public',
  'private',
  'internal',
  'protected',
  'override',
  'open',
  'abstract',
  'inline',
  'operator',
  'infix',
  'const',
  'lateinit',
  'suspend',
  'value',
  'final',
  'vararg',
  'crossinline',
  'noinline',
  'tailrec',
  'external',
])

class Parser {
  private at = 0
  private readonly tokens: Token[]
  constructor(tokens: Token[]) {
    this.tokens = tokens
  }

  private peek(offset = 0): Token | undefined {
    return this.tokens[this.at + offset]
  }
  private next(): Token {
    const token = this.tokens[this.at++]
    if (!token) throw new Error('unexpected end of file')
    return token
  }
  private is(text: string, offset = 0): boolean {
    const token = this.peek(offset)
    return token !== undefined && token.kind !== 'string' && token.text === text
  }
  private expect(text: string): void {
    const token = this.next()
    if (token.text !== text) throw new Error(`expected ${text}, got ${token.text}`)
  }
  done(): boolean {
    return this.at >= this.tokens.length
  }

  /** Skips a balanced group starting at the current `(`, `{`, `[` or `<`. */
  private skipGroup(): void {
    const open = this.next().text
    const close = { '(': ')', '{': '}', '[': ']', '<': '>' }[open]!
    let depth = 1
    while (depth > 0) {
      const t = this.next()
      if (t.kind === 'string') continue
      if (t.text === open) depth++
      else if (t.text === close) depth--
    }
  }

  /** Skips an expression up to (not including) one of [stops] at depth 0. */
  private skipExpression(stops: string[]): string {
    const parts: string[] = []
    let depth = 0
    for (;;) {
      const t = this.peek()
      if (!t) break
      if (t.kind !== 'string') {
        if (depth === 0 && stops.includes(t.text)) break
        if ('([{'.includes(t.text)) depth++
        if (')]}'.includes(t.text)) {
          if (depth === 0) break
          depth--
        }
      }
      parts.push(t.text)
      this.at++
    }
    return parts.join(' ').replace(/ ?([().,<>]) ?/g, '$1')
  }

  private doc(): string | undefined {
    let doc: string | undefined
    while (this.peek()?.kind === 'doc') doc = docText(this.next().text)
    return doc
  }

  private annotations(): void {
    while (this.peek()?.text.startsWith('@')) {
      this.next()
      if (this.is('(')) this.skipGroup()
    }
  }

  private modifiers(): Set<string> {
    const found = new Set<string>()
    for (;;) {
      this.annotations()
      const t = this.peek()
      if (t?.kind === 'word' && MODIFIERS.has(t.text) && !this.is(':', 1) && !this.is('(', 1)) {
        found.add(t.text)
        this.at++
      } else if (
        t?.kind === 'word' &&
        ['data', 'sealed', 'enum', 'fun'].includes(t.text) &&
        this.peek(1)?.kind === 'word' &&
        ['class', 'interface', 'object'].includes(this.peek(1)!.text)
      ) {
        found.add(t.text)
        this.at++
      } else {
        return found
      }
    }
  }

  /** A type: `Name<A, B>?`, `(a: A) -> R`, `Name.Inner`. */
  type(): string {
    let text: string
    if (this.is('(')) {
      this.next()
      const params: string[] = []
      while (!this.is(')')) {
        // `(name: T)` or `(T)`
        if (this.peek(1)?.text === ':' && this.peek()?.kind === 'word') {
          const name = this.next().text
          this.expect(':')
          params.push(`${name}: ${this.type()}`)
        } else {
          params.push(this.type())
        }
        if (this.is(',')) this.next()
      }
      this.expect(')')
      this.expect('->')
      text = `(${params.join(', ')}) -> ${this.type()}`
      if (this.is('?')) {
        this.next()
        text = `(${text})?`
      }
      return text
    }
    text = this.next().text
    if (this.is('<')) {
      this.next()
      const args: string[] = []
      while (!this.is('>')) {
        if (this.is('out') || this.is('in')) this.next()
        if (this.is('*')) {
          this.next()
          args.push('*')
        } else {
          args.push(this.type())
        }
        if (this.is(',')) this.next()
      }
      this.expect('>')
      text = `${text}<${args.join(', ')}>`
    }
    if (this.is('?')) {
      this.next()
      text += '?'
    }
    return text
  }

  /** `(a: A = 1, val b: B)`: a constructor's or a function's parameters, private ones marked. */
  params(): (Param & { private?: boolean })[] {
    this.expect('(')
    const params: (Param & { private?: boolean })[] = []
    while (!this.is(')')) {
      const doc = this.doc()
      const mods = this.modifiers()
      if (this.is('val') || this.is('var')) this.next()
      const name = this.next().text
      this.expect(':')
      const type = this.type()
      let fallback: string | undefined
      if (this.is('=')) {
        this.next()
        fallback = this.skipExpression([',', ')'])
      }
      params.push({
        name,
        type,
        ...(fallback !== undefined ? { default: fallback } : {}),
        ...(doc ? { doc } : {}),
        ...(mods.has('private') ? { private: true } : {}),
      })
      if (this.is(',')) this.next()
    }
    this.expect(')')
    return params
  }

  /** Skips a function's or property's body: `= expr` or `{ ... }`. */
  private skipBody(): void {
    if (this.is('=')) {
      this.next()
      this.skipExpression([
        '}',
        'fun',
        'val',
        'var',
        'override',
        'private',
        'data',
        'class',
        'object',
        'companion',
        'internal',
        'sealed',
        'enum',
        'interface',
      ])
    } else if (this.is('{')) {
      this.skipGroup()
    }
  }

  /** A class or interface body's members; [cases] collects a sealed interface's nested classes. */
  private members(owner: { properties: Property[]; methods: Method[] }, cases?: Decl[]): void {
    this.expect('{')
    while (!this.is('}')) {
      const doc = this.doc()
      if (this.is('}')) break
      const mods = this.modifiers()
      const t = this.peek()!
      if (t.text === 'companion' || (t.text === 'object' && !cases)) {
        // `companion object { ... }`, `object X : Y { ... }` that isn't a case: not the boundary.
        while (!this.is('{') && !this.is('}')) this.next()
        if (this.is('{')) this.skipGroup()
      } else if (t.text === 'fun') {
        this.next()
        if (this.is('<')) this.skipGroup()
        const name = this.next().text
        const params = this.params().map(({ private: _, ...p }) => p)
        let returns = 'Unit'
        if (this.is(':')) {
          this.next()
          returns = this.type()
        }
        const hasBody = this.is('=') || this.is('{')
        this.skipBody()
        // A method with a body is an interface's default: implementations may leave it out.
        if (!mods.has('private') && owner.methods !== NO_METHODS)
          owner.methods.push({
            name,
            ...(doc ? { doc } : {}),
            params,
            returns,
            ...(hasBody ? { defaulted: true } : {}),
          })
      } else if (t.text === 'val' || t.text === 'var') {
        this.next()
        const name = this.next().text
        let type = ''
        if (this.is(':')) {
          this.next()
          type = this.type()
        }
        let defaulted = false
        if (this.is('get')) {
          this.next()
          this.expect('(')
          this.expect(')')
          defaulted = true
          this.skipBody()
        } else if (this.is('=') || this.is('by')) {
          defaulted = true
          this.next()
          this.skipExpression([
            '}',
            'fun',
            'val',
            'var',
            'override',
            'private',
            'data',
            'class',
            'companion',
            'internal',
            'sealed',
            'enum',
            'interface',
          ])
        }
        // A computed value of a record (`val living get() = ...`) isn't data.
        if (
          !mods.has('private') &&
          type &&
          !(defaulted && cases === undefined && owner.methods === NO_METHODS)
        )
          owner.properties.push({
            name,
            ...(doc ? { doc } : {}),
            type,
            ...(defaulted ? { defaulted } : {}),
          })
      } else if (t.text === 'class' || t.text === 'interface' || t.text === 'object') {
        const decl = this.declaration(doc, mods)
        if (cases && decl) cases.push(decl)
      } else if (t.text === 'init') {
        this.next()
        this.skipGroup()
      } else {
        throw new Error(`unexpected ${t.text} in a body`)
      }
      if (this.is(';')) this.next()
    }
    this.expect('}')
  }

  /** One `class`, `interface`, `object` or `enum class` declaration, at its keyword. */
  declaration(doc: string | undefined, mods: Set<string>): Decl | undefined {
    const keyword = this.next().text
    const name = this.next().text
    if (this.is('<')) this.skipGroup()
    if (mods.has('enum')) return this.enumBody(name, doc)
    const ctor = this.is('(') ? this.params() : undefined
    let supertypes: string[] = []
    if (this.is(':')) {
      this.next()
      supertypes = this.skipExpression([
        '{',
        '}',
        'data',
        'class',
        'object',
        'fun',
        'val',
        'sealed',
        'interface',
        'enum',
      ])
        .split(',')
        .map((it) => it.replace(/\(.*$/, '').trim())
    }
    if (mods.has('sealed')) {
      const holder = { properties: [] as Property[], methods: [] as Method[] }
      const cases: Decl[] = []
      if (this.is('{')) this.members(holder, cases)
      return {
        kind: 'union',
        name,
        ...(doc ? { doc } : {}),
        cases: cases.map((it) => ({
          name: it.name,
          ...(it.doc ? { doc: it.doc } : {}),
          fields: it.kind === 'record' ? it.fields : [],
        })),
      }
    }
    if (keyword === 'interface') {
      const decl: InterfaceDecl = {
        kind: 'interface',
        name,
        ...(doc ? { doc } : {}),
        ...(supertypes[0] ? { extends: supertypes[0] } : {}),
        properties: [],
        methods: [],
      }
      if (this.is('{')) this.members(decl)
      return decl
    }
    if (mods.has('data') || keyword === 'object') {
      if (this.is('{')) this.members({ properties: [], methods: NO_METHODS })
      return {
        kind: 'record',
        name,
        ...(doc ? { doc } : {}),
        fields: (ctor ?? []).filter((p) => !p.private).map(({ private: _, ...p }) => p),
      }
    }
    // A plain class holding callbacks (`CommandSender`'s `replyTo`) is an object one side makes and
    // the other calls; any other is a record of its public values, its helpers not the boundary.
    if (!(ctor ?? []).some((p) => p.private && p.type.includes('->'))) {
      if (this.is('{')) this.skipGroup()
      return {
        kind: 'record',
        name,
        ...(doc ? { doc } : {}),
        fields: (ctor ?? []).filter((p) => !p.private).map(({ private: _, ...p }) => p),
      }
    }
    const decl: InterfaceDecl = {
      kind: 'object',
      name,
      ...(doc ? { doc } : {}),
      fields: (ctor ?? []).filter((p) => !p.private).map(({ private: _, ...p }) => p),
      properties: [],
      methods: [],
    }
    if (this.is('{')) this.objectMembers(decl)
    return decl
  }

  /** A plain class's public methods, bodies and all (`fun reply(m: String) = ...`). */
  private objectMembers(decl: InterfaceDecl): void {
    this.expect('{')
    while (!this.is('}')) {
      const doc = this.doc()
      if (this.is('}')) break
      const mods = this.modifiers()
      if (this.is('fun')) {
        this.next()
        const name = this.next().text
        const params = this.params().map(({ private: _, ...p }) => p)
        let returns = 'Unit'
        if (this.is(':')) {
          this.next()
          returns = this.type()
        }
        this.skipBody()
        if (!mods.has('private'))
          decl.methods.push({ name, ...(doc ? { doc } : {}), params, returns })
      } else if (this.is('val') || this.is('var')) {
        this.next()
        this.next()
        if (this.is(':')) {
          this.next()
          this.type()
        }
        if (this.is('get')) {
          this.next()
          this.expect('(')
          this.expect(')')
        }
        this.skipBody()
      } else {
        while (!this.is('{') && !this.is('}')) this.next()
        if (this.is('{')) this.skipGroup()
      }
    }
    this.expect('}')
  }

  private enumBody(name: string, doc: string | undefined): Decl {
    const params = this.is('(') ? this.params() : []
    if (this.is(':')) this.skipExpression(['{'])
    const entries: { name: string; doc?: string; args: string[] }[] = []
    this.expect('{')
    while (!this.is('}') && !this.is(';')) {
      const entryDoc = this.doc()
      this.annotations()
      if (this.is('}') || this.is(';')) break
      const entry = this.next().text
      const args: string[] = []
      if (this.is('(')) {
        this.next()
        while (!this.is(')')) {
          args.push(this.skipExpression([',', ')']))
          if (this.is(',')) this.next()
        }
        this.expect(')')
      }
      if (this.is('{')) this.skipGroup()
      entries.push({ name: entry, ...(entryDoc ? { doc: entryDoc } : {}), args })
      if (this.is(',')) this.next()
    }
    if (this.is(';')) {
      // The enum's own members: not data.
      this.next()
      let depth = 1
      while (depth > 0) {
        const t = this.next()
        if (t.kind === 'string') continue
        if (t.text === '{') depth++
        if (t.text === '}') depth--
      }
    } else {
      this.expect('}')
    }
    return { kind: 'enum', name, ...(doc ? { doc } : {}), params, entries }
  }

  /** The file's top-level declarations; functions, values and type aliases at the top aren't the boundary. */
  file(): Decl[] {
    const decls: Decl[] = []
    while (!this.done()) {
      const doc = this.doc()
      if (this.done()) break
      if (this.is('package') || this.is('import')) {
        this.next()
        this.next()
        continue
      }
      const mods = this.modifiers()
      const t = this.peek()!
      if (t.text === 'class' || t.text === 'interface' || t.text === 'object') {
        const decl = this.declaration(doc, mods)
        if (decl && !mods.has('private') && !mods.has('internal')) decls.push(decl)
      } else if (t.text === 'fun' || t.text === 'val' || t.text === 'typealias') {
        // Top-level helpers: skip to the next declaration.
        this.next()
        while (
          !this.done() &&
          ![
            'class',
            'interface',
            'object',
            'fun',
            'val',
            'data',
            'enum',
            'sealed',
            'private',
            'internal',
          ].includes(this.peek()!.text) &&
          this.peek()!.kind !== 'doc'
        ) {
          if (this.is('{') || this.is('(')) this.skipGroup()
          else this.next()
        }
      } else {
        throw new Error(`unexpected ${t.text} at the top level`)
      }
    }
    return decls
  }
}

/** Records' bodies: no methods kept. */
const NO_METHODS: Method[] = []

/** The declarations of one Kotlin file of the runtime's `platform/` package. */
export function readKotlin(source: string, file: string): PlatformFile {
  try {
    return { source: file, decls: new Parser(tokenize(source)).file() }
  } catch (e) {
    throw new Error(`${file}: ${(e as Error).message}`, { cause: e })
  }
}
