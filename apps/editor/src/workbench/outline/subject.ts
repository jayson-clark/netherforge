/**
 * Which resource the outline is about: the one the active tab's file belongs
 * to, whether that's its own editor or one of its scripts.
 */
import { KINDS, type KindId } from '@/core/format'
import {
  companionOf,
  inPackage,
  isBinaryKind,
  locationOf,
  mainFileOf,
  resourceOf,
} from '@/core/paths'

export interface OutlineSubject {
  kind: KindId
  id: string
  /** The group folders it's in (`(weapons)`), empty when none. */
  group: string
  /** The package it's in (opened read-only from the explorer's Dependencies), null for the project's own. */
  pkg: string | null
  /** Its folder (or its one file), whose files the outline lists: a package path for a package's. */
  location: string
  /** The file that opens it in its own editor, if it has one. */
  main: string | null
  /** Whether its files are worth listing (a map's are the game's own; a single-file kind has none beside it). */
  hasFiles: boolean
  /** A single-file kind's file beside its one file (a terrain's `.lua`), listed with it; null for the rest. */
  companion: string | null
}

export function subjectOf(path: string | null): OutlineSubject | null {
  const resource = path ? resourceOf(path) : null
  if (!resource) return null
  const { kind, id, group, pkg } = resource
  const where = KINDS[kind]
  const companion = isBinaryKind(kind) ? null : companionOf(kind, id, group)
  return {
    kind,
    id,
    group,
    pkg,
    location: inPackage(pkg, locationOf(kind, id, group)),
    main: where.contents === 'json' ? inPackage(pkg, mainFileOf(kind, id, group)) : null,
    hasFiles: where.layout === 'folder' && !isBinaryKind(kind),
    companion: companion === null ? null : inPackage(pkg, companion),
  }
}
