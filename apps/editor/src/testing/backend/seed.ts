/**
 * The `examples/showcase` project and `examples/library` (the package it depends
 * on, at `../library`), bundled at build time so the memory backend (unit
 * tests, Playwright) opens a real project with no disk. Text
 * files come in raw; PNGs, structures (`.nbt`) and sounds (`.ogg`) as data URLs (`?inline`),
 * decoded to bytes.
 */
import { base64ToBytes } from '@/core/backend/base64'
import type { FileContents } from './memory'

const texts = import.meta.glob(
  [
    '../../../../../examples/{showcase,library}/**/*',
    '../../../../../examples/{showcase,library}/**/.*',
    '!../../../../../examples/{showcase,library}/**/*.{png,nbt,ogg}',
    // Opening the example in the editor writes this from the client; it isn't committed.
    '!../../../../../examples/{showcase,library}/fonts/default.json',
  ],
  { query: '?raw', import: 'default', eager: true },
) as Record<string, string>

const binaries = import.meta.glob('../../../../../examples/{showcase,library}/**/*.{png,nbt,ogg}', {
  query: '?inline',
  import: 'default',
  eager: true,
}) as Record<string, string>

const EXAMPLES = '../../../../../examples/'

const decode = (dataUrl: string) => base64ToBytes(dataUrl.slice(dataUrl.indexOf(',') + 1))

/** The files of `examples/<example>`, by path inside it, from what [found] bundled. */
function filesOf<T, R>(found: Record<string, T>, example: string, map: (value: T) => R) {
  const prefix = `${EXAMPLES}${example}/`
  return Object.entries(found)
    .filter(([key]) => key.startsWith(prefix))
    .map(([key, value]) => [key.slice(prefix.length), map(value)] as const)
    .sort(([a], [b]) => a.localeCompare(b))
}

function project(example: string): Record<string, FileContents> {
  return Object.fromEntries([
    ...filesOf(texts, example, (text) => text),
    ...filesOf(binaries, example, decode),
  ])
}

/** Project path → text. */
export const exampleFiles: Record<string, string> = Object.fromEntries(
  filesOf(texts, 'showcase', (text) => text),
)

/** Project path → contents, binary files included: what the memory backend serves. */
export const exampleProject: Record<string, FileContents> = project('showcase')

/** `examples/library`: the package `examples/showcase` depends on (`../library`). */
export const libraryProject: Record<string, FileContents> = project('library')

export const EXAMPLE_ROOT = '/memory/showcase'
export const LIBRARY_ROOT = '/memory/library'
