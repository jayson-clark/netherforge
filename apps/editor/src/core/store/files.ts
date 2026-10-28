/**
 * The files slice: creating, copying, writing, renaming and deleting the
 * project's files, and following a rename in every file that refers to what
 * moved (format's reference walker decides which references those are). A
 * rename moves everything kept by path (documents, tabs, views) in one
 * `set`; a delete closes what was there. Renames (moves), deletes and copies
 * are each one transaction (`refactors.ts`), undone and redone as one step.
 */
import {
  documentKindOf,
  findUsages,
  newResourceFiles,
  renameRefs,
  type RefTarget,
  type RefUse,
  type TemplateKindId,
} from '@/core/format'
import { readProjectBytes } from '@/core/backend/projectBytes'
import { opensAs, readsText, resourceOf } from '@/core/paths'
import { docFromText, isModel, keepsRaw, patchDoc } from './documents'
import { readOnlyReason } from './project'
import { isTextFile } from './refactors'
import { errorText, isUnder, nextNonce, type SliceArgs } from './slice'
import { namespaceOf, projectInput } from './validation'

export interface FilesActions {
  /**
   * Writes format's template for a new resource of [kind] (a module's `init.lua`), in [group] folders when given
   * (`(weapons)`), and opens it.
   */
  createResource(kind: TemplateKindId, id: string, group?: string): Promise<void>
  createFile(path: string, text: string): Promise<void>
  /**
   * Copies a file or a folder (a resource, a map) to [to], as it is
   * on disk, then hot-reloads the copy. False, with a notice, if it failed.
   */
  copyPath(from: string, to: string): Promise<boolean>
  /** Writes a binary file (an imported texture), then hot-reloads it like a save. */
  writeBinary(path: string, bytes: Uint8Array): Promise<boolean>
  /**
   * Renames a file or folder, and follows it in every file that refers to
   * it (or into it): a resource's id, a file in a resource's folder.
   */
  renamePath(from: string, to: string): Promise<void>
  /**
   * Every reference to [target] renamed to [to] (its new id or key, or a
   * file's new project path), wherever a project file makes one: an open
   * model document gets an edit (saved unless it had unsaved changes), any
   * other file is rewritten from disk; unsaved raw text is left alone, for
   * validation to point at. For renames that aren't a path's: a pack's entry.
   */
  renameReferences(target: RefTarget, to: string): Promise<void>
  /** Every reference to [target] from outside it, as the project is now: what deleting it would break. */
  usagesOf(target: RefTarget): RefUse[]
  deletePath(path: string): Promise<void>
}

export function filesSlice(args: SliceArgs): FilesActions {
  const { backend, get, set } = args

  /** The files at [path]: the file itself, or everything in the folder. */
  const filesUnder = (path: string) => get().files.filter((it) => isUnder(it, path))

  return {
    async createResource(kind, id, group = '') {
      const made = newResourceFiles(kind, id, get().gameData, group)
      if ('needsGame' in made) return get().notify('error', `${made.needsGame}.`)
      const { files } = made
      if (get().refuseReadOnly(...Object.keys(files))) return
      for (const [path, text] of Object.entries(files)) await backend.writeText(path, text)
      await get().refreshFiles()
      const texts = Object.fromEntries(Object.entries(files).filter(([path]) => readsText(path)))
      set({ diskTexts: { ...get().diskTexts, ...texts } })
      await get().openFile(opensAs(kind, id, group))
      get().validateSoon()
    },

    async createFile(path, text) {
      if (get().refuseReadOnly(path)) return
      await backend.writeText(path, text)
      await get().refreshFiles()
      get().validateSoon()
      get().hotReload([path])
    },

    async copyPath(from, to) {
      const sources = filesUnder(from)
      if (sources.length === 0 || get().refuseReadOnly(to)) return false
      return get().transact(`Copy ${from} to ${to}`, async () => {
        const target = (path: string) => (path === from ? to : to + path.slice(from.length))
        const written: string[] = []
        const texts: Record<string, string> = {}
        await get().touch(to)
        try {
          for (const path of sources) {
            if (isTextFile(path)) {
              const text = await backend.readText(path)
              await backend.writeText(target(path), text)
              if (readsText(target(path))) texts[target(path)] = text
            } else {
              await backend.writeBytes(target(path), await readProjectBytes(backend, path))
            }
            written.push(target(path))
          }
        } catch (error) {
          get().notify('error', `Couldn't copy ${from}: ${errorText(error)}`)
          return false
        } finally {
          await get().refreshFiles()
          set({ diskTexts: { ...get().diskTexts, ...texts } })
          get().validateSoon()
          if (written.length > 0) get().hotReload(written)
        }
        return true
      })
    },

    async writeBinary(path, bytes) {
      if (get().refuseReadOnly(path)) return false
      try {
        await backend.writeBytes(path, bytes)
      } catch (error) {
        get().notify('error', `Couldn't write ${path}: ${errorText(error)}`)
        return false
      }
      set({ fileStamps: { ...get().fileStamps, [path]: nextNonce() } })
      if (!get().files.includes(path)) set({ files: [...get().files, path].sort() })
      get().validateSoon()
      get().hotReload([path])
      return true
    },

    async renameReferences(target, to) {
      const namespace = namespaceOf(get())
      if (namespace === null) return
      await get().transact(`Rename ${describeTarget(target)} to ${to}`, async () => {
        const written: string[] = []
        for (const path of get().files) {
          if (!documentKindOf(path) || readOnlyReason(get().readOnly, path) !== null) continue
          const doc = get().docs[path]
          if (isModel(doc)) {
            const next = renameRefs(
              path,
              JSON.stringify(doc.history.present),
              namespace,
              target,
              to,
            )
            if (next === null) continue
            const clean = !doc.dirty
            get().commitModel(path, JSON.parse(next))
            if (clean) {
              await get().touch(path)
              await get().save(path)
            }
            continue
          }
          if (doc?.dirty) continue
          const text = doc ? doc.savedText : get().diskTexts[path]
          if (text === undefined) continue
          const next = renameRefs(path, text, namespace, target, to)
          if (next === null) continue
          await get().touch(path)
          try {
            await backend.writeText(path, next)
          } catch (error) {
            get().notify('error', `Couldn't update ${path}: ${errorText(error)}`)
            continue
          }
          set({
            diskTexts: { ...get().diskTexts, [path]: next },
            fileStamps: { ...get().fileStamps, [path]: nextNonce() },
          })
          if (doc) patchDoc(args, path, (it) => docFromText(path, next, keepsRaw(it)))
          written.push(path)
        }
        get().hotReload(written)
      })
    },

    usagesOf(target) {
      try {
        return findUsages(projectInput(get()), target)
      } catch (error) {
        get().notify('error', `Couldn't find what refers to it: ${errorText(error)}`)
        return []
      }
    },

    async renamePath(from, to) {
      if (get().refuseReadOnly(from, to)) return
      await get().transact(`Rename ${from} to ${to}`, async () => {
        const moved = filesUnder(from)
        await get().touch(from, to)
        try {
          await backend.renamePath(from, to)
        } catch (error) {
          get().notify('error', `Couldn't rename ${from}: ${errorText(error)}`)
          return
        }
        // Everything kept by path follows it at once: documents, tabs and views.
        get().movePaths(from, to)
        await get().refreshFiles()

        // Follow it wherever a file refers to it: a file (or folder of them)
        // inside its resource (a script, a texture, a sound) in that
        // resource's file, a resource's new id (an item, a dialog, a pack and
        // everything in it) in every file. Format knows which references
        // name what. Part of this transaction: one undo puts it all back.
        const before = resourceOf(from)
        const after = resourceOf(to)
        if (before && after && before.kind === after.kind) {
          if (before.id === after.id && before.role === 'file' && after.role === 'file') {
            await get().renameReferences({ type: 'file', path: from }, to)
          } else if (
            before.id !== after.id &&
            (before.role === 'folder' || before.role === 'main')
          ) {
            await get().renameReferences(
              { type: 'resource', kind: before.kind, id: before.id },
              after.id,
            )
          }
        }
        get().validateSoon()
        // Gone from where it was, there where it is now: both resources reload (a script beside another is part of it).
        get().hotReload([...moved, ...moved.map((it) => to + it.slice(from.length))])
      })
    },

    async deletePath(path) {
      if (get().refuseReadOnly(path)) return
      await get().transact(`Delete ${path}`, async () => {
        const deleted = filesUnder(path)
        await get().touch(path)
        try {
          await backend.deletePath(path)
        } catch (error) {
          get().notify('error', `Couldn't delete ${path}: ${errorText(error)}`)
          return
        }
        // What was open there is set aside (unsaved edits too), for undo to bring back.
        get().shelvePaths(path)
        set({
          diskTexts: Object.fromEntries(
            Object.entries(get().diskTexts).filter(([it]) => !isUnder(it, path)),
          ),
        })
        await get().refreshFiles()
        get().validateSoon()
        get().hotReload(deleted)
      })
    },
  }
}

/** What a reference target is, for a transaction's name: `the glyph ui/coin`. */
function describeTarget(target: RefTarget): string {
  switch (target.type) {
    case 'file':
      return target.path
    case 'resource':
      return `${target.kind} ${target.id}`
    case 'resource_pack_entry':
      return `${target.entry} ${target.pack}/${target.key}`
  }
}
