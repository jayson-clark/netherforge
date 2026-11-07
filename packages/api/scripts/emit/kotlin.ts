/**
 * What of the Platform's Kotlin is generated from the API spec (`apps/plugin/platform/src/generated`): the command
 * arguments' types here, and the server's events in `raised.ts` (`platformKotlin`), with the helpers they share. The
 * runtime's bindings are Rust (`rust.ts`).
 */
import { GENERATED_BY } from './common.ts'
import type { Bindings } from './bindings.ts'

/** The name of an event's or a type's constant: `PLAYER_JOIN`. */
export const constant = (name: string) => name.toUpperCase()

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

const KEYWORDS = new Set([
  'as',
  'in',
  'is',
  'fun',
  'object',
  'package',
  'val',
  'var',
  'when',
  'class',
])

export const ident = (name: string) => (KEYWORDS.has(name) ? `\`${name}\`` : name)

const enumName = (name: string) => name.toUpperCase()

/**
 * `platform/CommandArguments.kt`: `ArgumentType`, what a command argument's `type` can be, each
 * with its Lua name, what the server reads it as and whether only the runtime can resolve it.
 * Adapter-facing, so no Lua types.
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
