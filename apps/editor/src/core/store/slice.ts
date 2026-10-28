/**
 * What every slice of the workspace store is made with (see `workspace.ts`).
 * A slice owns its part of the state and the actions that change it; it
 * reads the rest of the workspace through [SliceArgs.get] and calls other
 * slices' actions there, never their internals.
 */
import type { Backend } from '@/core/backend/types'
import type { Workspace } from './workspace'

export interface SliceArgs {
  backend: Backend
  get: () => Workspace
  set: (partial: Partial<Workspace>) => void
}

export function errorText(error: unknown): string {
  if (error instanceof Error) return error.message
  return typeof error === 'string' ? error : JSON.stringify(error)
}

let nonce = 0
/** A number never handed out before, for requests and stamps that must differ from the last. */
export const nextNonce = () => (nonce += 1)

/** [path] itself, or something inside the folder [path]. */
export const isUnder = (path: string, root: string) => path === root || path.startsWith(`${root}/`)

/**
 * Where a rename moves [path]: the same place under [to] when it's [from] or
 * inside it, null when the rename doesn't touch it.
 */
export function movedPath(from: string, to: string): (path: string) => string | null {
  return (path) => (isUnder(path, from) ? to + path.slice(from.length) : null)
}
