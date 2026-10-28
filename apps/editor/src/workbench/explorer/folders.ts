/**
 * What the project explorer shows: one folder per kind of resource, each
 * resource an atom (a centity, a menu, a module, a map) rather than
 * the files it's made of. A folder knows its resources' ids, where one lives
 * on disk (what rename, duplicate and delete act on), what opening one opens
 * and how to create one: all from format's kind table and the kind's entry
 * in the registry, in the registry's order.
 */
import {
  companionOf,
  groupOf,
  groupsOf,
  isBinaryKind,
  isFileKind,
  locationOf,
  opensAs,
  resourceIdsOf,
  resourceOf,
} from '@/core/paths'
import { KINDS, type KindId, type TemplateKindId } from '@/core/format'
import type { Workspace } from '@/core/store/workspace'
import type { KindContribution } from '@/editors/contributions'
import { KIND_GROUPS, KIND_ORDER, kindContribution } from '@/editors/registry'
import type { IconName } from '@/ui/Icon'

export interface ExplorerFolder {
  key: KindId
  title: string
  /** One of them, for "New …". */
  one: string
  icon: IconName
  /** Under the id prompt when creating one. */
  message: string
  /** Every resource's id, whatever group folder it's in: an id is the kind's whole. */
  ids: (files: string[]) => string[]
  /** The group folders resource [id] is in (`(weapons)/(swords)`), empty for none. */
  groupOf: (files: string[], id: string) => string
  /** Every group folder its resources are in, nested ones too (`(weapons)`, `(weapons)/(swords)`), sorted. */
  groups: (files: string[]) => string[]
  /** What rename, duplicate and delete act on: its folder, or its one file, in [group]. */
  location: (id: string, group?: string) => string
  /** The document beside a single file (a structure's generation `.json`), which goes where it goes; null for a kind without one. */
  companion: (id: string, group?: string) => string | null
  /** The file its tab opens (or a map's folder). */
  openPath: (id: string, group?: string) => string
  /** Whether its resources may go in group folders (all but resource packs). */
  groupable: boolean
  /** Makes a new one, in [group] when it's given. */
  create: (ws: Workspace, id: string, group?: string) => Promise<void> | void
  /** Its kind's entry in the registry. */
  contribution: KindContribution
}

const folderOf = (kind: KindId): ExplorerFolder => {
  const contribution = kindContribution(kind)
  return {
    key: kind,
    title: contribution.title,
    one: contribution.one,
    icon: contribution.icon,
    message: contribution.message,
    ids: (files) => resourceIdsOf(files, kind),
    groupOf: (files, id) => groupOf(files, kind, id),
    groups: (files) => groupsOf(files, kind),
    groupable: KINDS[kind].groupable,
    location: (id, group) => locationOf(kind, id, group),
    companion: (id, group) => companionOf(kind, id, group),
    openPath: (id, group) => opensAs(kind, id, group),
    // A kind format has a template for is written and opened; Minecraft's own
    // files (a structure, a map) open on their screen, which captures one
    // (where the screen saves it).
    create: (ws, id, group) => {
      if (isBinaryKind(kind)) return ws.openBinary(kind, id, group)
      if (KINDS[kind].template) return ws.createResource(kind as TemplateKindId, id, group)
    },
    contribution,
  }
}

export const EXPLORER_FOLDERS: ExplorerFolder[] = KIND_ORDER.map(folderOf)

/** The explorer's sections: each group's title and its folders, in order. */
export const EXPLORER_SECTIONS = KIND_GROUPS.map((group) => ({
  ...group,
  folders: EXPLORER_FOLDERS.filter((it) => it.contribution.group === group.id),
}))

/** The folder of kind [key]; the first (the explorer's default) for none or a kind that's gone. */
export const folderByKey = (key: string | null): ExplorerFolder =>
  EXPLORER_FOLDERS.find((it) => it.key === key) ?? EXPLORER_FOLDERS[0]!

/** Whether a folder's resources are single files (recipes, structures) rather than folders. */
export const isFileFolder = (folder: ExplorerFolder) => isFileKind(folder.key)

/** The folder, id and group a project path belongs to, if it's inside a resource the explorer shows. */
export function resourceAt(
  path: string,
  files: string[],
): { folder: ExplorerFolder; id: string; group: string } | null {
  // A package path (`library:items/gem/item.json`) is the package's, never the project's: a project path has no `:`.
  const found = path.includes(':') ? null : resourceOf(path)
  const folder = found && EXPLORER_FOLDERS.find((it) => it.key === found.kind)
  // The resource as the project has it: a duplicate id in another group folder isn't one format reads.
  if (!found || !folder || folder.groupOf(files, found.id) !== found.group) return null
  return folder.ids(files).includes(found.id) ? { folder, id: found.id, group: found.group } : null
}

/** Up to four resources of group folder [group] to draw on its tile: those right in it, then those in its own groups. */
export function groupPreview(folder: ExplorerFolder, files: string[], group: string): string[] {
  const inside = folder.ids(files).map((id) => ({ id, at: folder.groupOf(files, id) }))
  const direct = inside.filter((it) => it.at === group)
  const deeper = inside.filter((it) => it.at.startsWith(`${group}/`))
  return [...direct, ...deeper].slice(0, 4).map((it) => it.id)
}
