/** Writes every output of the Platform spec: so far, the Rust runtime's traits and types. */
import { mkdirSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { parseArgs } from 'node:util'
import { spec } from '../src/index.ts'
import { platformRust } from './rust.ts'

const { values } = parseArgs({ options: { out: { type: 'string' } } })
const root = values.out
  ? path.resolve(values.out)
  : path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..')

/** `PlayerAdmin.kt` → `playerAdmin`, as the spec's files are named. */
const specName = (file: string) => file[0]!.toLowerCase() + file.slice(1, -'.kt'.length)

const dir = path.join(root, 'crates/platform/src/generated')
mkdirSync(dir, { recursive: true })
const files = platformRust(spec, specName)
for (const old of readdirSync(dir))
  if (old.endsWith('.rs') && !files.has(old)) rmSync(path.join(dir, old))
for (const [name, text] of files) writeFileSync(path.join(dir, name), text)
