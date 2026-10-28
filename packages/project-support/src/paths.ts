/** Where a resource's files are, as format's kind table and `classify` say: what the stubs need of it. */
import * as nf from '@netherforge/format'
import { KINDS, type KindId } from '@netherforge/format/constants'
import type { ClassifiedPath } from '@netherforge/format/types'

/** The file a resource is read from (`centities/(towers)/tower/centity.json`), or a module's folder. */
export function mainFileOf(kind: KindId, id: string, group = ''): string {
  const where = KINDS[kind]
  const folder = group ? `${where.folder}/${group}` : where.folder
  const location = where.layout === 'file' ? `${folder}/${id}${where.extension}` : `${folder}/${id}`
  return where.main === null ? location : `${location}/${where.main}`
}

/** A file in a project resource, as format's `classify` places it. */
export interface ResourcePath {
  kind: KindId
  id: string
  role: 'main' | 'file' | 'folder'
  /** The path inside its folder (`lib/util.lua`); empty for the folder itself and a file kind's one file. */
  rest: string
}

/** The resource [path] belongs to (`modules/greeter/init.lua` → module `greeter`, rest `init.lua`), or null. */
export function resourceOf(path: string): ResourcePath | null {
  const found = JSON.parse(nf.classify(path)) as ClassifiedPath | null
  if (!found?.kind || !found.id || found.role === 'project' || found.role === 'group') return null
  return { kind: found.kind as KindId, id: found.id, role: found.role, rest: found.rest ?? '' }
}

/** A name a script gives `nf.network` as a literal: a server it sends players to, a channel or a shared map. */
export interface NetworkUse {
  kind: 'server' | 'channel' | 'network_map'
  name: string
  line: number
}

/** The network names [source] (Lua) uses as literals. */
export function networkUses(source: string): NetworkUse[] {
  return JSON.parse(nf.networkUses(source)) as NetworkUse[]
}
