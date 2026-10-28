/**
 * Creating, renaming, duplicating, moving and deleting resources, the same way
 * from the explorer and the outline: ids are checked against format's rule
 * before anything is written, and deleting always asks. Each acts on a
 * resource where it is, in whichever group folder (`(weapons)`); an id is
 * the kind's whole, so a group never makes one free again.
 */
import { ID_RULE } from '@/core/format'
import { folderOf, GROUP_KEEP, groupFolder, groupName, ID_PATTERN } from '@/core/paths'
import type { WorkspaceStore } from '@/core/store/workspace'
import { ask } from '@/ui/dialogs'
import { isFileFolder, type ExplorerFolder } from './explorer/folders'

const ID_HINT = ID_RULE.charAt(0).toUpperCase() + ID_RULE.slice(1)

/** Why [value] can't be an id among [taken] (other than [current]), or null. */
export function idProblem(value: string, taken: string[], current?: string): string | null {
  if (!ID_PATTERN.test(value)) return ID_HINT
  if (value !== current && taken.includes(value)) return 'That id is taken'
  return null
}

/** `tower_copy`, then `tower_copy2`, …: an id for a copy that fits the id rule. */
/** Where a group of [folder]'s kind goes, as an example: `items/(weapons)/`. */
const KIND_EXAMPLE = (folder: ExplorerFolder) => `${folderOf(folder.key, groupFolder('weapons'))}/`

export function copyId(id: string, taken: string[]): string {
  const base = `${id.slice(0, 64 - '_copy'.length - 2)}_copy`
  if (!taken.includes(base)) return base
  for (let n = 2; ; n += 1) if (!taken.includes(`${base}${n}`)) return `${base}${n}`
}

/**
 * Why [value] can't be a group path: group names (ids, without their
 * parentheses) separated by `/`, like `weapons/swords`; empty is the kind's
 * own folder. Null when it can.
 */
export function groupProblem(value: string): string | null {
  if (value.trim() === '') return null
  return value
    .trim()
    .split('/')
    .every((name) => ID_PATTERN.test(name))
    ? null
    : `Group names, separated by /: ${ID_RULE}`
}

/** A group path as typed (`weapons/swords`) as group folders (`(weapons)/(swords)`), and back. */
export const groupFolders = (value: string) =>
  value.trim() === '' ? '' : value.trim().split('/').map(groupFolder).join('/')
export const groupPath = (group: string) => (group ? group.split('/').map(groupName).join('/') : '')

export function resourceActions(workspace: WorkspaceStore) {
  const ws = () => workspace.getState()
  const taken = (folder: ExplorerFolder) => folder.ids(ws().files)
  /** The group folders [id] is in now. */
  const groupOf = (folder: ExplorerFolder, id: string) => folder.groupOf(ws().files, id)
  /** The document beside a resource's file, when it has one on disk: it follows the file. */
  const companionOf = (folder: ExplorerFolder, id: string) => {
    const path = folder.companion(id, groupOf(folder, id))
    return path !== null && ws().files.includes(path) ? path : null
  }

  return {
    /** Asks for an id and makes a new one in [group] (the explorer's open group folder, `(weapons)`). */
    async create(folder: ExplorerFolder, group = ''): Promise<string | null> {
      const id = await ask.prompt({
        title: group ? `New ${folder.one} in ${groupPath(group)}` : `New ${folder.one}`,
        icon: folder.icon,
        label: isFileFolder(folder) ? 'Id (the file name)' : 'Id (the folder name)',
        message: folder.message,
        confirmLabel: 'Create',
        validate: (value) => idProblem(value, taken(folder)),
      })
      if (!id) return null
      await folder.create(ws(), id, group)
      return id
    },

    /** Asks for the new id first (the command palette's rename; trees rename inline). */
    async renameAsking(folder: ExplorerFolder, id: string) {
      const next = await ask.prompt({
        title: `Rename ${id}`,
        icon: folder.icon,
        label: isFileFolder(folder) ? 'Id (the file name)' : 'Id (the folder name)',
        message: folder.message,
        initial: id,
        confirmLabel: 'Rename',
        validate: (value) => idProblem(value, taken(folder), id),
      })
      if (next) await this.rename(folder, id, next)
    },

    async rename(folder: ExplorerFolder, id: string, next: string) {
      if (next === id || idProblem(next, taken(folder), id)) return
      const group = groupOf(folder, id)
      const companion = companionOf(folder, id)
      await ws().renamePath(folder.location(id, group), folder.location(next, group))
      if (companion) await ws().renamePath(companion, folder.companion(next, group)!)
    },

    /** Copies it beside itself (in its group) under a fresh id; returns that id, or null if the copy failed. */
    async duplicate(folder: ExplorerFolder, id: string): Promise<string | null> {
      const copy = copyId(id, taken(folder))
      const group = groupOf(folder, id)
      const companion = companionOf(folder, id)
      if (!(await ws().copyPath(folder.location(id, group), folder.location(copy, group))))
        return null
      if (companion) await ws().copyPath(companion, folder.companion(copy, group)!)
      return copy
    },

    /**
     * Moves it into [group] (group folders, `(weapons)/(swords)`; empty for the kind's own folder). Its id stays, so
     * nothing that names it changes: only its files move, the document beside a single file with it.
     */
    async move(folder: ExplorerFolder, id: string, group: string) {
      const from = groupOf(folder, id)
      if (group === from) return
      const companion = companionOf(folder, id)
      await ws().renamePath(folder.location(id, from), folder.location(id, group))
      if (companion) await ws().renamePath(companion, folder.companion(id, group)!)
    },

    /** Asks which group to move it to: names separated by `/`, or nothing for the kind's own folder. */
    async moveAsking(folder: ExplorerFolder, id: string) {
      const current = groupPath(groupOf(folder, id))
      const next = await ask.prompt({
        title: `Move ${id} to a group`,
        icon: 'folder',
        label: 'Group (names separated by /, empty for none)',
        message: `Groups sort ${folder.title.toLowerCase()} into folders, like ${KIND_EXAMPLE(folder)}. Its id stays "${id}", so nothing that names it changes.`,
        initial: current,
        confirmLabel: 'Move',
        validate: groupProblem,
      })
      if (next === null) return
      await this.move(folder, id, groupFolders(next))
    },

    /**
     * Asks for a name and makes an empty group folder in [parent] (empty: the kind's own folder), kept by a hidden
     * file. Returns its group folders (`(weapons)/(swords)`), or null.
     */
    async createGroup(folder: ExplorerFolder, parent = ''): Promise<string | null> {
      const inside = folder
        .groups(ws().files)
        .filter((it) =>
          parent
            ? it.startsWith(`${parent}/`) && !it.slice(parent.length + 1).includes('/')
            : !it.includes('/'),
        )
      const name = await ask.prompt({
        title: parent
          ? `New group in ${groupPath(parent)}`
          : `New group of ${folder.title.toLowerCase()}`,
        icon: 'newFolder',
        label: 'Name',
        message: `A folder to sort ${folder.title.toLowerCase()} into, like ${KIND_EXAMPLE(folder)}. It only sorts: what's in it keeps its id.`,
        confirmLabel: 'Create',
        validate: (value) =>
          !ID_PATTERN.test(value)
            ? ID_HINT
            : inside.includes(parent ? `${parent}/${groupFolder(value)}` : groupFolder(value))
              ? 'There is a group by that name here'
              : null,
      })
      if (!name) return null
      const group = parent ? `${parent}/${groupFolder(name)}` : groupFolder(name)
      await ws().createFile(`${folderOf(folder.key, group)}/${GROUP_KEEP}`, '')
      return group
    },

    /** Deletes group folder [group] and everything in it, after asking (naming what goes with it). */
    async removeGroup(folder: ExplorerFolder, group: string): Promise<boolean> {
      const files = ws().files
      const ids = folder.ids(files).filter((id) => {
        const at = folder.groupOf(files, id)
        return at === group || at.startsWith(`${group}/`)
      })
      const location = folderOf(folder.key, group)
      const ok = await ask.confirm({
        title: `Delete group ${groupPath(group)}`,
        message:
          ids.length === 0
            ? `Delete the empty group folder ${location}?`
            : `Delete the folder ${location} and the ${ids.length === 1 ? `${folder.one} in it (${ids[0]})` : `${ids.length} ${folder.title.toLowerCase()} in it (${ids.join(', ')})`}? Git can bring them back if they were committed.`,
        confirmLabel: 'Delete',
        danger: true,
      })
      if (ok !== true) return false
      await ws().deletePath(location)
      return true
    },

    /** Renames the group folder [group] of [folder]'s kind (`(weapons)/(swords)`) to [name] (an id); everything in it goes along. */
    async renameGroup(folder: ExplorerFolder, group: string, name: string): Promise<string | null> {
      const parent = group.includes('/') ? group.slice(0, group.lastIndexOf('/')) : ''
      const next = parent ? `${parent}/${groupFolder(name)}` : groupFolder(name)
      if (next === group || !ID_PATTERN.test(name)) return null
      if (folder.groups(ws().files).includes(next)) {
        ws().notify(
          'error',
          `There's already a group ${groupPath(next)} in ${folder.title.toLowerCase()}.`,
        )
        return null
      }
      await ws().renamePath(folderOf(folder.key, group), folderOf(folder.key, next))
      return next
    },

    async remove(folder: ExplorerFolder, id: string): Promise<boolean> {
      const location = folder.location(id, groupOf(folder, id))
      // What breaks: every file that refers to it (format's reference walker).
      const users = [
        ...new Set(
          ws()
            .usagesOf({ type: 'resource', kind: folder.key, id })
            .map((it) => it.file),
        ),
      ].sort()
      const breaks = users.length
        ? ` ${users.length === 1 ? `${users[0]} refers` : `${users.length} files refer`} to it and will show problems${users.length > 1 ? `: ${users.join(', ')}` : ''}.`
        : ''
      const ok = await ask.confirm({
        title: `Delete ${id}`,
        message:
          (isFileFolder(folder)
            ? `Delete the file ${location}${companionOf(folder, id) ? ` and ${companionOf(folder, id)}` : ''}? Git can bring it back if it was committed.`
            : `Delete the folder ${location} and everything in it? Git can bring it back if it was committed.`) +
          breaks,
        confirmLabel: 'Delete',
        danger: true,
      })
      if (ok !== true) return false
      const companion = companionOf(folder, id)
      await ws().deletePath(location)
      if (companion) await ws().deletePath(companion)
      return true
    },
  }
}
