/**
 * The validation slice: the project as format reads it and the problems it
 * finds, worked out again 250 ms after the last change.
 *
 * Format runs in a worker (`core/validation/`), never on the main thread.
 * Each validation sends it only the files that changed since the last
 * (`ChangeTracker`); the worker keeps the rest, and what each file validated
 * to, so it validates only those files and then checks everything across
 * files again (format's `ProjectCache`).
 */
import {
  MANIFEST_FILE,
  type ImageSizes,
  type Problem,
  type ProjectOutline,
  type ProjectValidation,
} from '@/core/format'
import type { ProjectInfo } from '@/core/backend/types'
import { TOKEN_FILES } from '@/core/mcp/config'
import { readsText } from '@/core/paths'
import { validationWorker, type ValidationClient } from '@/core/validation/client'
import { ChangeTracker, type CheckRequest } from '@/core/validation/requests'
import { isModel } from './documents'
import { errorText, type SliceArgs } from './slice'
import type { Workspace } from './workspace'

/** How long after the last change `validateSoon` validates. */
export const VALIDATE_DELAY_MS = 250

/** Each model's text as format reads it, worked out once per model (an edit makes a new one). */
const modelTexts = new WeakMap<object, string>()

function modelText(model: unknown): string {
  if (typeof model !== 'object' || model === null) return JSON.stringify(model, null, 2)
  let text = modelTexts.get(model)
  if (text === undefined) {
    text = JSON.stringify(model, null, 2)
    modelTexts.set(model, text)
  }
  return text
}

/**
 * Every project file as format reads it: the open documents' current
 * models and texts, the rest from disk, and null for what format doesn't read (images).
 * The editor's own files for coding agents (`.mcp.json`, `opencode.json`) aren't the
 * project's, so they're left out: rewriting one changes nothing format sees.
 */
export function projectInput(
  state: Pick<Workspace, 'files' | 'docs' | 'diskTexts'>,
): Record<string, string | null> {
  const { files, docs, diskTexts } = state
  const input: Record<string, string | null> = {}
  for (const path of files) {
    if ((TOKEN_FILES as readonly string[]).includes(path)) continue
    if (!readsText(path)) {
      input[path] = null
      continue
    }
    const doc = docs[path]
    if (doc && !doc.deleted) {
      input[path] = isModel(doc) ? modelText(doc.history.present) : doc.text
    } else {
      input[path] = diskTexts[path] ?? null
    }
  }
  return input
}

/** The project's namespace, which its references resolve in: the outline's, or the manifest's as it is now. */
export function namespaceOf(state: Workspace): string | null {
  if (state.outline?.namespace) return state.outline.namespace
  const text = projectInput(state)[MANIFEST_FILE]
  try {
    const manifest = text ? (JSON.parse(text) as { namespace?: unknown }) : null
    return typeof manifest?.namespace === 'string' ? manifest.namespace : null
  } catch {
    return null
  }
}

export interface ValidationState {
  outline: ProjectOutline | null
  problems: Problem[]
  /** The pictures' pixel sizes, as the resource pack previews measured them: what holds a replaced game texture to the game's size. */
  imageSizes: ImageSizes
}

export interface ValidationActions {
  /** Runs validation now; resolves once its problems are in the store. */
  validateNow(): Promise<void>
  /** Runs validation once changes settle; every change to a project file asks for it. */
  validateSoon(): void
  /** Drops a validation that was asked for and hasn't run or answered (the project is closing). */
  cancelValidation(): void
  /**
   * While [held], [validateSoon] asks for nothing: a project opening validates once, when it has
   * everything (its packages, the game data), not when the files it writes as it opens are noticed.
   */
  holdValidation(held: boolean): void
  /** Tells validation the resource packs' pictures' sizes (by project path); it runs again when they changed. */
  setImageSizes(sizes: ImageSizes): void
  /** Validates another project once in the same worker (one of the workspace's), leaving the open one's as it is. */
  checkProject(request: CheckRequest): Promise<ProjectValidation>
}

export const VALIDATION_INITIAL: ValidationState = { outline: null, problems: [], imageSizes: {} }

/**
 * [connect] makes the worker, the first time a project is validated; it's
 * kept for every project after.
 */
export function validationSlice(
  { get, set }: SliceArgs,
  connect: () => ValidationClient = validationWorker,
): ValidationState & ValidationActions {
  let timer: ReturnType<typeof setTimeout> | null = null
  let client: ValidationClient | null = null
  const changes = new ChangeTracker()
  /** The project the worker holds; another one starts it over. */
  let holding: ProjectInfo | null = null
  /** Bumped by a cancel, so an answer to an earlier request is dropped. */
  let generation = 0
  let held = false

  return {
    ...VALIDATION_INITIAL,

    async validateNow() {
      // It takes every change so far, so one scheduled by validateSoon has nothing left to do.
      if (timer !== null) clearTimeout(timer)
      timer = null
      const state = get()
      if (!state.project) return
      if (state.project !== holding) {
        changes.reset()
        holding = state.project
      }
      client ??= connect()
      const asked = generation
      // Validation checks ids against the server's export only; glyph advances aren't game data.
      const request = changes.request(
        projectInput(state),
        state.gameData,
        state.packages,
        state.imageSizes,
      )
      try {
        const { outline } = await client.validate(request)
        if (asked !== generation) return
        get().packagesValidated(outline)
        const { fontProblem } = get()
        set({
          outline,
          problems: fontProblem === null ? outline.problems : [...outline.problems, fontProblem],
        })
        // A tripwire block wants the game's own look of tripwire, and hiding or replacing the game's HUD and textures their names and sizes, which only the import has.
        if (
          outline.problems.some(
            (it) =>
              it.code === 'block.vanilla-looks' ||
              it.code === 'resource_pack.hud-hide' ||
              it.code === 'resource_pack.vanilla-missing',
          )
        ) {
          void get().syncVanilla()
        }
      } catch (error) {
        if (asked !== generation) return
        // The worker may not have taken the request: tell it everything next time.
        changes.reset()
        get().notify('error', `Validation failed: ${errorText(error)}`)
      }
    },

    validateSoon() {
      if (held) return
      if (timer !== null) clearTimeout(timer)
      timer = setTimeout(() => {
        timer = null
        void get().validateNow()
      }, VALIDATE_DELAY_MS)
    },

    setImageSizes(sizes) {
      if (JSON.stringify(sizes) === JSON.stringify(get().imageSizes)) return
      set({ imageSizes: sizes })
      if (get().project) get().validateSoon()
    },

    checkProject(request) {
      client ??= connect()
      return client.check(request)
    },

    cancelValidation() {
      if (timer !== null) clearTimeout(timer)
      timer = null
      generation += 1
      holding = null
    },

    holdValidation(hold) {
      held = hold
      if (!hold || timer === null) return
      clearTimeout(timer)
      timer = null
    },
  }
}
