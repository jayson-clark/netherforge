/// <reference path="./assets.d.ts" />
/**
 * Lua for the preview in a browser (the editor's worker): wasmoon's `glue.wasm` as a file of the bundle, fetched
 * once. Kept apart from `lua.node.ts` so neither bundles the other's way of finding it.
 */
import wasm from 'wasmoon/dist/glue.wasm?url'
import { loadLua } from './previewer.ts'

/** Loads the Lua terrain scripts run on, once ([loadLua]). */
export const loadBrowserLua = (): Promise<void> => loadLua(wasm)
