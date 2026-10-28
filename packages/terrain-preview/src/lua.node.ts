/// <reference path="./assets.d.ts" />
/**
 * Lua for the preview in Node (`netherforge preview`, and tests): wasmoon's `glue.wasm` inlined into the script,
 * since the `netherforge` command is one self-contained file. Node's build of wasmoon reads it from a file, so it's
 * written once into the system's temporary folder, named by its hash (an unchanged one is used as it is).
 */
import { createHash } from 'node:crypto'
import { existsSync, renameSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import inlined from 'wasmoon/dist/glue.wasm?url&inline'
import { loadLua } from './previewer.ts'

let loaded: Promise<void> | null = null

/** Loads the Lua terrain scripts run on, once ([loadLua]). */
export function loadNodeLua(): Promise<void> {
  loaded ??= loadLua(wasmFile())
  return loaded
}

/** A file holding wasmoon's `glue.wasm`, written if it isn't there yet. */
export function wasmFile(): string {
  const bytes = Buffer.from(inlined.slice(inlined.indexOf(',') + 1), 'base64')
  const hash = createHash('sha256').update(bytes).digest('hex').slice(0, 16)
  const file = path.join(tmpdir(), `netherforge-lua-${hash}.wasm`)
  if (!existsSync(file)) {
    // Written beside it, then moved: another process starting at once never reads half a file.
    const partial = `${file}.${process.pid}.part`
    writeFileSync(partial, bytes)
    renameSync(partial, file)
  }
  return file
}
