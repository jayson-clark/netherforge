/**
 * The external changes slice, and what to do with an open document when its
 * file changes on disk.
 *
 * The watcher reports every change, including the editor's own writes, so
 * the first question is always "is this just what we last wrote or read?".
 * Comparing against `savedText` (the text we last synced with the disk)
 * answers that without tracking our own writes separately.
 */
import { DEFAULT_FONT_FILE, LOCK_FILE, MANIFEST_FILE } from '@/core/format'
import { TOKEN_FILES } from '@/core/mcp/config'
import { readsText } from '@/core/paths'
import { docFromText, keepsRaw, patchDoc, withDirty } from './documents'
import { nextNonce, type SliceArgs } from './slice'

export type ExternalDecision =
  /** The disk holds what we already know about: our own save, or a touch. */
  | 'ignore'
  /** Clean document, new contents: reload it silently. */
  | 'reload'
  /** Unsaved edits and new contents on disk: ask (keep mine / take theirs / diff). */
  | 'conflict'
  /** Gone from disk with nothing unsaved: close its tab. */
  | 'close'
  /** Gone from disk with unsaved edits: keep it open, marked deleted. */
  | 'mark-deleted'

export interface ExternalInput {
  /** The text we last read from or wrote to disk. */
  savedText: string
  dirty: boolean
  /** Whether the document already knows it was deleted. */
  deleted: boolean
}

/** [diskText] is the file's text now, or null if it no longer exists. */
export function decideExternalChange(
  doc: ExternalInput,
  diskText: string | null,
): ExternalDecision {
  if (diskText === null) {
    if (doc.deleted) return 'ignore'
    return doc.dirty ? 'mark-deleted' : 'close'
  }
  if (diskText === doc.savedText && !doc.deleted) return 'ignore'
  return doc.dirty ? 'conflict' : 'reload'
}

export interface ExternalActions {
  /** [rescan]: changes may have been lost, so every known file counts as changed. */
  handleFilesChanged(paths: string[], rescan?: boolean): Promise<void>
  /** Settles a conflict: keep mine (the next save overwrites theirs) or take theirs. */
  resolveConflict(path: string, choice: 'mine' | 'theirs'): void
}

export function externalSlice(args: SliceArgs): ExternalActions {
  const { backend, get, set } = args
  return {
    async handleFilesChanged(changed, rescan = false) {
      if (!get().project) return
      const before = get().files
      await get()
        .refreshFiles()
        .catch(() => {})
      const paths = rescan
        ? [...new Set([...changed, ...before, ...get().files, ...Object.keys(get().docs)])]
        : changed
      const exists = new Set(get().files)
      const diskTexts = { ...get().diskTexts }
      const read = async (path: string) => {
        if (!exists.has(path)) return null
        try {
          return await backend.readText(path)
        } catch {
          return null
        }
      }
      const stamps = { ...get().fileStamps }
      for (const path of paths) stamps[path] = nextNonce()
      set({ fileStamps: stamps })
      for (const path of paths) {
        const doc = get().docs[path]
        const needsText = doc !== undefined || readsText(path)
        if (!needsText) continue
        const text = await read(path)
        if (readsText(path)) {
          if (text === null) delete diskTexts[path]
          else diskTexts[path] = text
        }
        if (path === MANIFEST_FILE && text !== null) {
          try {
            const minecraft = (JSON.parse(text) as { minecraft?: string }).minecraft ?? null
            if (minecraft !== get().minecraft) {
              set({ minecraft })
              void get().refreshGameData()
            }
          } catch {
            // Problems will say what's wrong with it.
          }
        }
        const current = get().docs[path]
        if (!current) continue
        switch (decideExternalChange(current, text)) {
          case 'ignore':
            break
          case 'reload':
            patchDoc(args, path, (it) => docFromText(path, text!, keepsRaw(it)))
            break
          case 'conflict':
            patchDoc(args, path, (it) => ({ ...it, conflict: { theirs: text! }, deleted: false }))
            break
          case 'close': {
            const tabs = get().tabs.filter((tab) => tab.path === path)
            for (const tab of tabs) get().closeTab(tab.id)
            patchDoc(args, path, () => null)
            break
          }
          case 'mark-deleted':
            patchDoc(args, path, (it) => withDirty({ ...it, deleted: true, conflict: null }))
            break
        }
      }
      set({ diskTexts })
      // The editor's own files for coding agents (`.mcp.json`, written as the project opens) say nothing to format.
      if (rescan || paths.some((path) => !(TOKEN_FILES as readonly string[]).includes(path)))
        get().validateSoon()
      // A changed manifest may name other dependencies; a rescan may have missed a change to it.
      // The lock pins git dependencies' commits: a new one (a pull) may name others.
      if (rescan || paths.includes(MANIFEST_FILE) || paths.includes(LOCK_FILE))
        await get().refreshPackages()
      // Generated, not edited: put it back if it was deleted or changed.
      if (paths.includes(DEFAULT_FONT_FILE)) await get().syncDefaultFont()
    },

    resolveConflict(path, choice) {
      patchDoc(args, path, (doc) => {
        if (!doc.conflict) return doc
        const theirs = doc.conflict.theirs
        if (choice === 'mine') {
          // Mine stays; the next save overwrites theirs.
          return withDirty({ ...doc, savedText: theirs, conflict: null, deleted: false })
        }
        return docFromText(path, theirs, keepsRaw(doc))
      })
      if (choice === 'theirs' && readsText(path)) {
        const doc = get().docs[path]
        if (doc) set({ diskTexts: { ...get().diskTexts, [path]: doc.savedText } })
      }
      get().validateSoon()
    },
  }
}
