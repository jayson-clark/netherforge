/**
 * The refactors slice: project-level undo. A rename, move, delete or copy
 * changes files on disk (some of them never open), moves or closes what's
 * kept by path (documents, tabs, views) and edits open documents that refer
 * to what moved. It runs as one **transaction** (`transact`), which records
 * all of that, so undo and redo put all of it back as one step.
 *
 * What a transaction holds:
 * - **Files**: every file under the paths it touched (`touch`, called before
 *   anything changes them), as it was before and after, so a file no tab has
 *   open is restored exactly, text or bytes.
 * - **Moves** of everything kept by path (`movePaths`), replayed backwards on undo.
 * - **Document steps**: an open document's edit while the transaction runs
 *   (a reference rewritten) is a step in its own history tagged with the
 *   transaction (`commitModel` asks `transactionStep`), undone and redone
 *   only with it.
 * - **A shelf**: the documents, tabs and views of paths it made go (a
 *   delete; a copy, on undo), unsaved edits included, put back when it's
 *   undone (or redone).
 *
 * How it meets per-document undo, and what happens when the disk changed
 * underneath, is in the editor-ui skill ("Undo for refactors").
 */
import { readProjectBytes } from '@/core/backend/projectBytes'
import { isProjectJson } from '@/core/paths'
import { docFromText, isModel, keepsRaw, movedDocs, withDirty, type Doc } from './documents'
import * as H from './history'
import { errorText, isUnder, movedPath, nextNonce, type SliceArgs } from './slice'
import { movedTabs, type Tab } from './tabs'
import { movedViews, type DocView } from './views'
import type { Workspace } from './workspace'

/** Files the editor reads as text; everything else is read and written as bytes. */
const TEXT_EXTENSIONS = /\.(json|lua|md|txt|mcmeta)$/
export const isTextFile = (path: string) => TEXT_EXTENSIONS.test(path)

/** A file's contents: text for the files the editor reads as text, bytes for the rest. */
export type Contents = string | Uint8Array

export function sameContents(a: Contents | null, b: Contents | null): boolean {
  if (a === null || b === null || typeof a === 'string' || typeof b === 'string') return a === b
  return a.length === b.length && a.every((byte, i) => byte === b[i])
}

/** What a transaction set aside: the documents, tabs and views of paths it made go. */
export interface Shelf {
  docs: Record<string, Doc>
  /** Each with the place it had in the strip. */
  tabs: { tab: Tab; index: number }[]
  /** The active tab, when it was one of these. */
  active: string | null
  views: Record<string, DocView>
}

export interface Transaction {
  /** Its tick on the undo clock (`nextSeq`), which orders it among documents' own steps. */
  id: number
  /** What it did, as the user would say it: `Rename items/ruby to items/gem`. */
  label: string
  /** The files and folders it changed, each as a whole (none inside another). */
  roots: string[]
  /** Every file under [roots] it changed, before and after (null: not there). */
  files: Record<string, { before: Contents | null; after: Contents | null }>
  /** Renames of what's kept by path, in the order they happened. */
  moves: { from: string; to: string }[]
  /** The documents it gave a step, by their paths after it. */
  docs: string[]
  /** What it set aside on the side it's on now (done or undone). */
  shelf: Shelf | null
}

/** Transactions kept for undo; older ones go, their document steps becoming the documents' own. */
const LIMIT = 100

export interface RefactorsState {
  /** Done, oldest first (the last is the next to undo), and undone (the first is the next to redo). */
  refactors: { done: Transaction[]; undone: Transaction[] }
}

/** Paths that moved (a rename, in order: a folder moves everything in it) or went (a deleted file or folder). */
export type PathChange = { moves: { from: string; to: string }[] } | { gone: string }

export interface RefactorsActions {
  /**
   * Runs [work] as one undoable step named [label]: everything it touches,
   * moves, closes and edits is recorded, and undone or redone together.
   * Called while another transaction runs, [work] joins it (a rename's
   * reference rewriting is part of the rename).
   */
  transact<T>(label: string, work: () => Promise<T>): Promise<T>
  /**
   * Records [paths] (files, or folders and everything in them, there or
   * not yet) as changing in the running transaction, reading what's there
   * now. Call it before changing them. Nothing without a transaction.
   */
  touch(...paths: string[]): Promise<void>
  /**
   * The running transaction's id for a step [path]'s document is about to
   * take (recording that it took one), or null outside a transaction.
   */
  transactionStep(path: string): number | null
  /** Moves everything kept by path (documents, tabs, views, disk texts) from [from] to [to], recorded in the running transaction. */
  movePaths(from: string, to: string): void
  /** Closes the documents and tabs at [path] or inside it, set aside in the running transaction so undo brings them back. */
  shelvePaths(path: string): void
  /**
   * Calls [listener] whenever paths move (a rename, its undo or redo: each
   * move in order) or go (a delete); returns the function that stops it.
   * For what's kept by path outside the workspace (the debugger's breakpoints).
   */
  followPaths(listener: (change: PathChange) => void): () => void
  /** Undoes the last transaction; false (with a notice when it was refused) if nothing was undone. */
  undoRefactor(): Promise<boolean>
  /** Redoes the last transaction undone; false if nothing was redone. */
  redoRefactor(): Promise<boolean>
}

export const REFACTORS_INITIAL: RefactorsState = { refactors: { done: [], undone: [] } }

/** The workspace with everything kept by path at or inside [gone] closed, and what was closed. */
export function shelve(
  state: Pick<Workspace, 'docs' | 'tabs' | 'activeTab' | 'views'>,
  gone: (path: string) => boolean,
): { next: Pick<Workspace, 'docs' | 'tabs' | 'activeTab' | 'views'>; shelf: Shelf } {
  const { docs, tabs, activeTab, views } = state
  const pick = <V>(record: Record<string, V>, keep: boolean) =>
    Object.fromEntries(Object.entries(record).filter(([path]) => gone(path) !== keep))
  const shelved = tabs.flatMap((tab, index) => (gone(tab.path) ? [{ tab, index }] : []))
  const nextTabs = tabs.filter((tab) => !gone(tab.path))
  return {
    next: {
      docs: pick(docs, true),
      tabs: nextTabs,
      activeTab: nextTabs.some((tab) => tab.id === activeTab)
        ? activeTab
        : (nextTabs[nextTabs.length - 1]?.id ?? null),
      views: pick(views, true),
    },
    shelf: {
      docs: pick(docs, false),
      tabs: shelved,
      active: shelved.some(({ tab }) => tab.id === activeTab) ? activeTab : null,
      views: pick(views, false),
    },
  }
}

/** The workspace with [shelf] put back: its tabs where they were in the strip, and active again if one was. */
export function unshelve(
  state: Pick<Workspace, 'docs' | 'tabs' | 'activeTab' | 'views'>,
  shelf: Shelf,
): Pick<Workspace, 'docs' | 'tabs' | 'activeTab' | 'views'> {
  const tabs = state.tabs.filter((tab) => !shelf.tabs.some((it) => it.tab.id === tab.id))
  for (const { tab, index } of [...shelf.tabs].sort((a, b) => a.index - b.index)) {
    tabs.splice(Math.min(index, tabs.length), 0, tab)
  }
  return {
    docs: { ...state.docs, ...shelf.docs },
    tabs,
    activeTab: shelf.active ?? state.activeTab,
    views: { ...state.views, ...shelf.views },
  }
}

function mergeShelves(a: Shelf | null, b: Shelf): Shelf {
  if (!a) return b
  return {
    docs: { ...a.docs, ...b.docs },
    tabs: [...a.tabs, ...b.tabs],
    active: b.active ?? a.active,
    views: { ...a.views, ...b.views },
  }
}

/** Where [moves], in order, take [path]. */
const forward = (moves: Transaction['moves'], path: string) =>
  moves.reduce((at, { from, to }) => movedPath(from, to)(at) ?? at, path)

/** The moves that take everything back, in the order to make them. */
const backwards = (moves: Transaction['moves']) =>
  [...moves].reverse().map(({ from, to }) => ({ from: to, to: from }))

function moveAll(
  state: Pick<Workspace, 'docs' | 'tabs' | 'activeTab' | 'views' | 'diskTexts'>,
  moves: Transaction['moves'],
): Pick<Workspace, 'docs' | 'tabs' | 'activeTab' | 'views' | 'diskTexts'> {
  let next = state
  for (const { from, to } of moves) {
    const move = movedPath(from, to)
    next = {
      docs: movedDocs(next.docs, move),
      ...movedTabs(next.tabs, next.activeTab, move),
      views: movedViews(next.views, move),
      diskTexts: Object.fromEntries(
        Object.entries(next.diskTexts).map(([path, text]) => [move(path) ?? path, text]),
      ),
    }
  }
  return next
}

/** Why an undo or redo was refused; [drop] when it never can be (the disk moved on). */
class Refused extends Error {
  readonly drop: boolean
  constructor(message: string, drop: boolean) {
    super(message)
    this.drop = drop
  }
}

export function refactorsSlice(args: SliceArgs): RefactorsState & RefactorsActions {
  const { backend, get, set } = args
  /** The transaction running now, if any. */
  let open: (Transaction & { unreadable: string | null }) | null = null
  /** An undo or redo is under way: another waits for nothing and does nothing. */
  let busy = false
  /** Who follows paths moving and going (`followPaths`). */
  const pathListeners = new Set<(change: PathChange) => void>()
  const tell = (change: PathChange) => {
    for (const listener of pathListeners) listener(change)
  }

  const read = async (path: string): Promise<Contents> =>
    isTextFile(path)
      ? backend.readText(path)
      : // A fresh stamp: never a cached picture of what was there.
        readProjectBytes(backend, path, nextNonce())

  /** Forgets transactions [ids]: their document steps become the documents' own. */
  const drop = (ids: number[]) => {
    if (ids.length === 0) return
    const { done, undone } = get().refactors
    const untag = (doc: Doc) =>
      doc.history
        ? ids.reduce((it, id) => ({ ...it, history: H.untag(it.history!, id) }), doc)
        : doc
    const keep = (tx: Transaction) => !ids.includes(tx.id)
    const unshelf = (tx: Transaction): Transaction =>
      tx.shelf
        ? {
            ...tx,
            shelf: {
              ...tx.shelf,
              docs: Object.fromEntries(
                Object.entries(tx.shelf.docs).map(([path, doc]) => [path, untag(doc)]),
              ),
            },
          }
        : tx
    set({
      refactors: { done: done.filter(keep).map(unshelf), undone: undone.filter(keep).map(unshelf) },
      docs: Object.fromEntries(Object.entries(get().docs).map(([path, doc]) => [path, untag(doc)])),
    })
  }

  /** Records a finished transaction, with each touched file's contents now. */
  const finish = async (tx: Transaction & { unreadable: string | null }) => {
    if (tx.roots.length === 0 && tx.docs.length === 0) return
    if (tx.unreadable !== null) {
      get().notify('info', `"${tx.label}" can't be undone: ${tx.unreadable}`)
      return
    }
    await get()
      .refreshFiles()
      .catch(() => {})
    const files = { ...tx.files }
    for (const path of get().files) {
      if (!(path in files) && tx.roots.some((root) => isUnder(path, root))) {
        files[path] = { before: null, after: null }
      }
    }
    const exists = new Set(get().files)
    try {
      for (const [path, change] of Object.entries(files)) {
        const after = exists.has(path) ? await read(path) : null
        if (sameContents(change.before, after)) delete files[path]
        else files[path] = { ...change, after }
      }
    } catch (error) {
      get().notify('info', `"${tx.label}" can't be undone: ${errorText(error)}`)
      return
    }
    if (Object.keys(files).length === 0 && tx.docs.length === 0) return
    const recorded: Transaction = {
      id: tx.id,
      label: tx.label,
      // A root nothing under which changed is no part of it (undo would close what's open there).
      roots: tx.roots.filter((root) => Object.keys(files).some((path) => isUnder(path, root))),
      files,
      moves: tx.moves,
      docs: tx.docs,
      shelf: tx.shelf,
    }
    const kept = [...get().refactors.done, recorded]
    const old = get().refactors.undone.map((it) => it.id)
    set({ refactors: { done: kept.slice(-LIMIT), undone: get().refactors.undone } })
    // A new transaction ends the redo stack, as a new edit does; the oldest past the limit go.
    drop([...old, ...kept.slice(0, -LIMIT).map((it) => it.id)])
  }

  /**
   * Takes [tx] back (undo) or makes it again (redo): checks first that
   * everything it changed is still as it left it, then puts the workspace
   * (documents, tabs, views, disk texts) on the other side in one `set`,
   * before the disk, so the watcher's echo of each write is already known.
   */
  const apply = async (tx: Transaction, undoing: boolean): Promise<Transaction> => {
    const leaving = (change: Transaction['files'][string]) =>
      undoing ? change.after : change.before
    const entering = (change: Transaction['files'][string]) =>
      undoing ? change.before : change.after

    // 1. Everything it changed is as it left it.
    await get().refreshFiles()
    const exists = new Set(get().files)
    for (const [path, change] of Object.entries(tx.files)) {
      let now: Contents | null = null
      if (exists.has(path)) {
        try {
          now = await read(path)
        } catch {
          throw new Refused(`${path} can't be read`, true)
        }
      }
      if (!sameContents(now, leaving(change))) throw new Refused(`${path} changed since`, true)
    }
    for (const path of get().files) {
      if (!(path in tx.files) && tx.roots.some((root) => isUnder(path, root))) {
        throw new Refused(`${path} was added since`, true)
      }
    }
    // The documents still holding its step: one closed and opened again since is like any other.
    const holds = (doc: Doc | undefined) =>
      !!doc?.history &&
      [...doc.history.pastSteps, ...doc.history.futureSteps].some((it) => it.tx === tx.id)
    const stepped = new Set(
      tx.docs
        .map((path) => (undoing ? path : forward(backwards(tx.moves), path)))
        .filter((path) => holds(get().docs[path])),
    )
    for (const path of stepped) {
      const doc = get().docs[path]!
      const step = isModel(doc) ? (undoing ? H.lastStep : H.nextStep)(doc.history) : null
      if (step?.tx !== tx.id) {
        // Edits since come off first (undo them there); a redo's step is gone for good.
        throw undoing
          ? new Refused(`${path} was edited since: undo that there first`, false)
          : new Refused(`${path} was edited since`, true)
      }
    }

    // 2. The workspace on the other side.
    let state: Pick<Workspace, 'docs' | 'tabs' | 'activeTab' | 'views' | 'diskTexts'> = get()
    const step = (docs: Record<string, Doc>, paths: Iterable<string>) => {
      const next = { ...docs }
      for (const path of paths) {
        const doc = next[path]
        if (doc?.history) {
          const history = (undoing ? H.undo : H.redo)(doc.history)
          next[path] = withDirty({ ...doc, history })
        }
      }
      return next
    }
    // Where the documents it stepped are once it's on the other side.
    let steppedThere: string[]
    if (undoing) {
      state = { ...state, docs: step(state.docs, stepped) }
      state = moveAll(state, backwards(tx.moves))
      steppedThere = [...stepped].map((path) => forward(backwards(tx.moves), path))
    } else {
      state = moveAll(state, tx.moves)
      steppedThere = [...stepped].map((path) => forward(tx.moves, path))
      state = { ...state, docs: step(state.docs, steppedThere) }
    }
    const ownSteps = new Set(steppedThere)
    const docs = { ...state.docs }
    const diskTexts = { ...state.diskTexts }
    const fileStamps = { ...get().fileStamps }
    const gone = new Set<string>()
    for (const [path, change] of Object.entries(tx.files)) {
      const contents = entering(change)
      fileStamps[path] = nextNonce()
      if (isProjectJson(path)) {
        if (typeof contents === 'string') diskTexts[path] = contents
        else delete diskTexts[path]
      }
      if (contents === null) {
        gone.add(path)
        continue
      }
      const doc = docs[path]
      if (!doc || typeof contents !== 'string') continue
      if (ownSteps.has(path)) {
        // Its step is undone or redone; what's on disk is what it now matches.
        docs[path] = withDirty({
          ...doc,
          savedText: contents,
          savedModel: docFromText(path, contents).savedModel,
          conflict: null,
          deleted: false,
        })
      } else if (doc.savedText !== contents) {
        if (doc.dirty) throw new Refused(`${path} has unsaved changes`, false)
        docs[path] = docFromText(path, contents, keepsRaw(doc))
      }
    }
    // The roots that are empty on the other side go whole, folders and all.
    const emptied = tx.roots.filter(
      (root) =>
        !Object.entries(tx.files).some(
          ([path, change]) => isUnder(path, root) && entering(change) !== null,
        ),
    )
    const closes = (path: string) => gone.has(path) || emptied.some((root) => isUnder(path, root))
    const shelved = shelve({ ...state, docs }, closes)
    const back = tx.shelf ? unshelve(shelved.next, tx.shelf) : shelved.next
    const empty = Object.keys(shelved.shelf.docs).length === 0 && shelved.shelf.tabs.length === 0
    const next: Transaction = { ...tx, shelf: empty ? null : shelved.shelf }
    set({ ...back, diskTexts, fileStamps })
    if (tx.moves.length > 0) tell({ moves: undoing ? backwards(tx.moves) : tx.moves })

    // 3. The disk.
    try {
      for (const root of tx.roots) {
        const inside = Object.entries(tx.files).filter(([path]) => isUnder(path, root))
        if (emptied.includes(root)) {
          if (inside.some(([, change]) => leaving(change) !== null)) await backend.deletePath(root)
          continue
        }
        for (const [path, change] of inside) {
          const contents = entering(change)
          if (sameContents(contents, leaving(change))) continue
          if (contents === null) await backend.deletePath(path)
          else if (typeof contents === 'string') await backend.writeText(path, contents)
          else await backend.writeBytes(path, contents)
        }
      }
    } catch (error) {
      // Half done: what's on disk is what the watcher and validation now say.
      throw new Refused(`writing failed (${errorText(error)})`, true)
    } finally {
      await get()
        .refreshFiles()
        .catch(() => {})
      get().validateSoon()
      get().hotReload(Object.keys(tx.files))
    }
    return next
  }

  const run = async (undoing: boolean): Promise<boolean> => {
    const { done, undone } = get().refactors
    const tx = undoing ? done[done.length - 1] : undone[0]
    if (!tx || busy || open) return false
    busy = true
    try {
      const next = await apply(tx, undoing)
      const now = get().refactors
      set({
        refactors: undoing
          ? { done: now.done.filter((it) => it.id !== tx.id), undone: [next, ...now.undone] }
          : { done: [...now.done, next], undone: now.undone.filter((it) => it.id !== tx.id) },
      })
      return true
    } catch (error) {
      const verb = undoing ? 'undo' : 'redo'
      if (!(error instanceof Refused)) {
        get().notify('error', `Couldn't ${verb} "${tx.label}": ${errorText(error)}`)
        return false
      }
      get().notify(
        'error',
        `Couldn't ${verb} "${tx.label}": ${error.message}.` +
          (error.drop ? ' It is no longer in the undo history.' : ''),
      )
      if (error.drop) drop([tx.id])
      return false
    } finally {
      busy = false
    }
  }

  return {
    ...REFACTORS_INITIAL,

    async transact(label, work) {
      if (open) return work()
      const tx = (open = {
        id: H.nextSeq(),
        label,
        roots: [],
        files: {},
        moves: [],
        docs: [],
        shelf: null,
        unreadable: null,
      })
      try {
        return await work()
      } finally {
        open = null
        await finish(tx)
      }
    },

    async touch(...paths) {
      const tx = open
      if (!tx) return
      for (const path of paths) {
        if (tx.roots.some((root) => isUnder(path, root))) continue
        tx.roots = [...tx.roots.filter((root) => !isUnder(root, path)), path]
        for (const file of get().files.filter((it) => isUnder(it, path))) {
          if (file in tx.files) continue
          try {
            tx.files[file] = { before: await read(file), after: null }
          } catch (error) {
            tx.unreadable = `couldn't read ${file} (${errorText(error)})`
          }
        }
      }
    },

    transactionStep(path) {
      if (!open) return null
      if (!open.docs.includes(path)) open.docs.push(path)
      return open.id
    },

    movePaths(from, to) {
      const { docs, tabs, activeTab, views, diskTexts } = get()
      set(moveAll({ docs, tabs, activeTab, views, diskTexts }, [{ from, to }]))
      tell({ moves: [{ from, to }] })
      if (!open) return
      open.moves.push({ from, to })
      open.docs = open.docs.map((path) => movedPath(from, to)(path) ?? path)
    },

    shelvePaths(path) {
      const { next, shelf } = shelve(get(), (it) => isUnder(it, path))
      set(next)
      tell({ gone: path })
      if (open) open.shelf = mergeShelves(open.shelf, shelf)
    },

    followPaths(listener) {
      pathListeners.add(listener)
      return () => void pathListeners.delete(listener)
    },

    undoRefactor: () => run(true),
    redoRefactor: () => run(false),
  }
}
