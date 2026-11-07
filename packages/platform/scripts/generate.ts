/** Writes every output of the Platform spec: the Rust runtime's traits and types, and the FFM glue's two halves. */
import { mkdirSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'
import { spec } from '../src/index.ts'
import { ffiKotlin, ffiRust } from './ffi.ts'
import { platformRust } from './rust.ts'

const { values } = parseArgs({ options: { out: { type: 'string' } } })
const root = values.out
  ? path.resolve(values.out)
  : path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')

/** `PlayerAdmin.kt` → `playerAdmin`, as the spec's files are named. */
const specName = (file: string) => file[0]!.toLowerCase() + file.slice(1, -'.kt'.length)

/** Writes [files] into [folder], removing what's there with [extension] that isn't among them. */
function write(folder: string, extension: string, files: Map<string, string>) {
  const dir = path.join(root, folder)
  mkdirSync(dir, { recursive: true })
  for (const old of readdirSync(dir))
    if (old.endsWith(extension) && !files.has(old)) rmSync(path.join(dir, old))
  for (const [name, text] of files) writeFileSync(path.join(dir, name), text)
}

write('crates/platform/src/generated', '.rs', platformRust(spec, specName))
write('crates/ffi/src/generated', '.rs', ffiRust(spec))
write('apps/plugin/ffi/src/generated/kotlin/dev/netherforge/plugin/ffi', '.kt', ffiKotlin(spec))
