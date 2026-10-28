/**
 * The documents slice: every open file's text or model, and its undo
 * history.
 *
 * - `format` is the only writer of project JSON. A model document is saved
 *   as `JSON.stringify(model)` → `canonicalize` → `writeText`.
 * - Undo is per document. Model documents keep snapshot histories here;
 *   text documents (Lua, raw JSON) rely on Monaco's own undo. A refactor
 *   (`refactors.ts`) is one step in every document's undo: `undo(path)`
 *   takes back the document's last edit or the project's last refactor,
 *   whichever came later.
 * - `savedText` is what we last read from or wrote to disk. Every external
 *   change is judged against it (see `external.ts`), which is also how our
 *   own writes, echoed back by the watcher, are recognised and ignored.
 */
import { produce, setAutoFreeze } from 'immer'
import {
  canonicalize,
  canonicalizeModel,
  documentKindOf,
  type DocumentKindId,
  type Problem,
} from '@/core/format'
import { readsText } from '@/core/paths'
import * as H from './history'
import { splitPackagePath } from './packages'
import { errorText, type SliceArgs } from './slice'

export type DocKind = DocumentKindId | 'lua' | 'text'

export interface Doc {
  path: string
  kind: DocKind
  /**
   * The text being edited when [raw] is true (Lua, a JSON file that doesn't
   * parse, or a model document opened as text). Stale otherwise.
   */
  text: string
  /** The text we last read from or wrote to disk. */
  savedText: string
  /** Edits go to [text] rather than [history]. Always true for Lua and text. */
  raw: boolean
  /** The parsed model's history; null for text documents and JSON that never parsed. */
  history: H.History<unknown> | null
  /** [savedText]'s model, for the dirty check; null when it doesn't parse. */
  savedModel: unknown
  dirty: boolean
  /** Why the text doesn't parse, when it doesn't. */
  parseProblem: Problem | null
  /** The disk changed under unsaved edits; holds the disk's text. */
  conflict: { theirs: string } | null
  /** The file was deleted on disk while this had unsaved edits. */
  deleted: boolean
}

/**
 * A document edited through its model rather than its text: a project file
 * format owns, parsed, and not switched to its JSON. Its history is the
 * truth; [Doc.text] is stale.
 */
export type ModelDoc = Doc & { kind: DocumentKindId; raw: false; history: H.History<unknown> }

export function isModel(doc: Doc | undefined): doc is ModelDoc {
  return !!doc && !doc.raw && doc.history !== null && doc.kind !== 'lua' && doc.kind !== 'text'
}

/** The model of a document, when it has one and isn't being edited as text. */
export function modelOf<T>(doc: Doc | undefined): T | null {
  return isModel(doc) ? (doc.history.present as T) : null
}

// Models stay mutable outside `edit`, as plain JSON: frozen ones would turn
// any stray mutation into a crash in the app.
setAutoFreeze(false)

/** The model a text parses to, through format, or null with the parse problem. */
function parseModel(
  kind: DocumentKindId,
  path: string,
  text: string,
): { model: unknown; problem: Problem | null } {
  const result = canonicalize(kind, path, text)
  if (result.text === undefined) return { model: null, problem: result.problems[0] ?? null }
  return { model: JSON.parse(result.text), problem: null }
}

export function docKind(path: string): DocKind {
  return documentKindOf(path) ?? (path.endsWith('.lua') ? 'lua' : 'text')
}

/** A document as read from disk. */
export function docFromText(path: string, text: string, preferRaw = false): Doc {
  const kind = docKind(path)
  if (kind === 'lua' || kind === 'text') {
    return {
      path,
      kind,
      text,
      savedText: text,
      raw: true,
      history: null,
      savedModel: null,
      dirty: false,
      parseProblem: null,
      conflict: null,
      deleted: false,
    }
  }
  const { model, problem } = parseModel(kind, path, text)
  return {
    path,
    kind,
    text,
    savedText: text,
    raw: preferRaw || model === null,
    history: model === null ? null : H.createHistory(model),
    savedModel: model,
    dirty: false,
    parseProblem: problem,
    conflict: null,
    deleted: false,
  }
}

/**
 * Whether a reloaded document should stay in text mode: yes if the user chose
 * it; no if it was only there because the old text didn't parse.
 */
export const keepsRaw = (doc: Doc) => doc.raw && doc.parseProblem === null

/**
 * Whether two models hold the same JSON. Edits share every subtree they
 * didn't touch, so this mostly compares references and costs about as much
 * as the edit did. Key order doesn't matter (the canonical writer sorts).
 */
export function sameJson(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (typeof a !== 'object' || typeof b !== 'object' || a === null || b === null) return false
  if (Array.isArray(a) !== Array.isArray(b)) return false
  const left = a as Record<string, unknown>
  const right = b as Record<string, unknown>
  const keys = Object.keys(left).filter((key) => left[key] !== undefined)
  if (keys.length !== Object.keys(right).filter((key) => right[key] !== undefined).length)
    return false
  return keys.every((key) => sameJson(left[key], right[key]))
}

function computeDirty(doc: Doc): boolean {
  if (doc.deleted) return true
  if (!isModel(doc)) return doc.text !== doc.savedText
  return !sameJson(doc.history.present, doc.savedModel)
}

export const withDirty = (doc: Doc): Doc => ({ ...doc, dirty: computeDirty(doc) })

/** Replaces the document at [path] with what [patch] makes of it, or forgets it for null. */
export function patchDoc(
  { get, set }: Pick<SliceArgs, 'get' | 'set'>,
  path: string,
  patch: (doc: Doc) => Doc | null,
) {
  const doc = get().docs[path]
  if (!doc) return
  const next = patch(doc)
  const docs = { ...get().docs }
  if (next) docs[path] = next
  else delete docs[path]
  set({ docs })
}

/** The documents after a rename: each one [moved] names, at its new path. */
export function movedDocs(
  docs: Record<string, Doc>,
  moved: (path: string) => string | null,
): Record<string, Doc> {
  const next: Record<string, Doc> = {}
  for (const [path, doc] of Object.entries(docs)) {
    const target = moved(path)
    next[target ?? path] = target ? { ...doc, path: target } : doc
  }
  return next
}

export interface DocumentsState {
  docs: Record<string, Doc>
}

export interface DocumentsActions {
  loadDoc(path: string): Promise<Doc | null>
  /** Applies [recipe] to a copy of the model. Inside a gesture, merges into one undo step. */
  edit<T>(path: string, recipe: (draft: T) => void): void
  /** Replaces a document's text (Lua, raw JSON). */
  setText(path: string, text: string): void
  setRaw(path: string, raw: boolean): void
  beginGesture(path: string): void
  endGesture(path: string): void
  /**
   * Takes back the document's last edit, or the project's last refactor
   * when that came later (or the edit was part of it: then the whole
   * refactor). A document's own edit is undone before this returns.
   */
  undo(path: string): Promise<void>
  /** Makes again what [undo] took back last: the document's edit or the project's refactor. */
  redo(path: string): Promise<void>
  save(path: string): Promise<boolean>
  saveAll(): Promise<void>
  /** Mine as it would be written, for the diff view. */
  canonicalMine(path: string): string
  /**
   * Commits [next] as a model document's new present (one undo step unless
   * inside a gesture; part of the running refactor's transaction, if any).
   */
  commitModel(path: string, next: unknown): void
}

export const DOCUMENTS_INITIAL: DocumentsState = { docs: {} }

export function documentsSlice(args: SliceArgs): DocumentsState & DocumentsActions {
  const { backend, get, set } = args
  const patch = (path: string, change: (doc: Doc) => Doc | null) => patchDoc(args, path, change)

  return {
    ...DOCUMENTS_INITIAL,

    async loadDoc(path) {
      const existing = get().docs[path]
      if (existing) return existing
      let text: string
      try {
        // A package path is read from its package's folder (read-only: the whole package is marked).
        const [pkg, inside] = splitPackagePath(path)
        const location = pkg === null ? null : get().outline?.packages?.[pkg]?.location
        if (pkg !== null && location == null) throw new Error(`there's no package "${pkg}"`)
        text =
          pkg === null
            ? await backend.readText(path)
            : await backend.packageReadText(location!, inside)
      } catch (error) {
        get().notify('error', `Couldn't read ${path}: ${errorText(error)}`)
        return null
      }
      // Another call may have loaded it while we were reading.
      const raced = get().docs[path]
      if (raced) return raced
      const doc = docFromText(path, text)
      set({ docs: { ...get().docs, [path]: doc } })
      return doc
    },

    commitModel(path, next) {
      const doc = get().docs[path]
      if (!isModel(doc) || Object.is(next, doc.history.present)) return
      const tx = get().transactionStep(path)
      patch(path, (it) =>
        isModel(it) ? withDirty({ ...it, history: H.commit(it.history, next, tx) }) : it,
      )
      get().validateSoon()
    },

    edit(path, recipe) {
      const doc = get().docs[path]
      if (!isModel(doc) || get().refuseReadOnly(path)) return
      // Copies only what the recipe touches; an edit that changes nothing
      // returns the same model, which commits nothing.
      const next = produce(doc.history.present, (draft) => {
        recipe(draft as Parameters<typeof recipe>[0])
      })
      get().commitModel(path, next)
    },

    setText(path, text) {
      if (get().refuseReadOnly(path)) return
      patch(path, (doc) => (doc.text === text ? doc : withDirty({ ...doc, text })))
      if (get().docs[path]?.kind !== 'lua') get().validateSoon()
    },

    setRaw(path, raw) {
      const doc = get().docs[path]
      if (!doc || doc.raw === raw || doc.kind === 'lua' || doc.kind === 'text') return
      if (raw) {
        // Show the file as it is on disk when nothing changed, so opening
        // a hand-formatted file as text doesn't reformat it.
        const text = doc.dirty
          ? (canonicalizeModel(doc.kind, path, doc.history?.present).text ?? doc.savedText)
          : doc.savedText
        patch(path, (it) => withDirty({ ...it, raw: true, text }))
        return
      }
      const { model, problem } = parseModel(doc.kind, path, doc.text)
      if (model === null) {
        patch(path, (it) => ({ ...it, parseProblem: problem }))
        get().notify('error', `Fix the JSON first: ${problem?.message ?? 'it does not parse'}`)
        return
      }
      patch(path, (it) =>
        withDirty({
          ...it,
          raw: false,
          parseProblem: null,
          history: it.history ? H.commit(it.history, model) : H.createHistory(model),
        }),
      )
      get().validateSoon()
    },

    beginGesture(path) {
      patch(path, (doc) => (doc.history ? { ...doc, history: H.beginGesture(doc.history) } : doc))
    },

    endGesture(path) {
      patch(path, (doc) => (doc.history ? { ...doc, history: H.endGesture(doc.history) } : doc))
    },

    async undo(path) {
      const doc = get().docs[path]
      const own = isModel(doc) ? H.lastStep(doc.history) : null
      const refactor = get().refactors.done.at(-1)
      if (refactor && (own === null || own.tx !== null || own.seq < refactor.id)) {
        await get().undoRefactor()
        return
      }
      if (!isModel(doc) || own === null) return
      patch(path, (it) => (isModel(it) ? withDirty({ ...it, history: H.undo(it.history) }) : it))
      get().validateSoon()
    },

    async redo(path) {
      const doc = get().docs[path]
      const own = isModel(doc) ? H.nextStep(doc.history) : null
      const refactor = get().refactors.undone[0]
      // The one undone last: undo goes newest first, so the older of the two.
      if (refactor && (own === null || own.tx !== null || refactor.id < own.seq)) {
        await get().redoRefactor()
        return
      }
      if (!isModel(doc) || own === null) return
      patch(path, (it) => (isModel(it) ? withDirty({ ...it, history: H.redo(it.history) }) : it))
      get().validateSoon()
    },

    async save(path) {
      const doc = get().docs[path]
      if (!doc || get().refuseReadOnly(path)) return false
      let text: string
      let model: unknown = null
      if (doc.kind === 'lua' || doc.kind === 'text') {
        text = doc.text
      } else if (!isModel(doc)) {
        // Hand-edited JSON: written canonically when it parses, and as typed
        // when it doesn't, so nothing the user wrote is lost.
        const result = canonicalize(doc.kind, path, doc.text)
        text = result.text ?? doc.text
        model = result.text === undefined ? null : JSON.parse(result.text)
      } else {
        const result = canonicalizeModel(doc.kind, path, doc.history.present)
        if (result.text === undefined) {
          get().notify(
            'error',
            `Couldn't save ${path}: ${result.problems[0]?.message ?? 'invalid'}`,
          )
          return false
        }
        text = result.text
        model = doc.history.present
      }
      // A workspace package's file, edited in place: written into its own project.
      const inPackage = splitPackagePath(path)[0] !== null
      if (inPackage) {
        if (!(await get().writePackageFile(path, text))) return false
      } else {
        try {
          await backend.writeText(path, text)
        } catch (error) {
          get().notify('error', `Couldn't save ${path}: ${errorText(error)}`)
          return false
        }
      }
      patch(path, (current) => {
        const next: Doc = { ...current, savedText: text, conflict: null, deleted: false }
        if (current.kind === 'lua' || current.kind === 'text') return withDirty(next)
        if (!isModel(current)) {
          return withDirty({
            ...next,
            // The editor shows what was written, so a raw save reformats.
            text: current.text === doc.text ? text : current.text,
            savedModel: model,
            parseProblem: model === null ? current.parseProblem : null,
            history:
              model === null
                ? current.history
                : current.history
                  ? H.commit(current.history, model)
                  : H.createHistory(model),
          })
        }
        return withDirty({ ...next, savedModel: model })
      })
      if (inPackage) return true
      if (readsText(path)) set({ diskTexts: { ...get().diskTexts, [path]: text } })
      if (!get().files.includes(path)) set({ files: [...get().files, path].sort() })
      get().validateSoon()
      get().hotReload([path])
      return true
    },

    async saveAll() {
      for (const doc of Object.values(get().docs)) {
        if (doc.dirty) await get().save(doc.path)
      }
    },

    canonicalMine(path) {
      const doc = get().docs[path]
      if (!doc) return ''
      if (!isModel(doc)) return doc.text
      return canonicalizeModel(doc.kind, path, doc.history.present).text ?? doc.text
    },
  }
}
