/**
 * The project structures a terrain's decorations place, as format's preview takes them: the names the file
 * gives, and each `.nbt` read into [TerrainStructureInput]. The editor reads the files from its backend and the
 * `netherforge` command from disk; what a file means is decided here, once.
 */
import type { TerrainStructureInput } from '@netherforge/format/types'
import type { Structure } from '@netherforge/structures/structure'

/**
 * The project structures a generator's [decorations] (the file's `decorations` object) place, by the name the file
 * gives them, sorted. A name with a namespace is a package's, which the preview doesn't draw.
 */
export function structuresNamed(
  decorations: Record<string, { structure?: string | null }> | undefined,
): string[] {
  const names = new Set<string>()
  for (const decoration of Object.values(decorations ?? {})) {
    if (decoration.structure && !decoration.structure.includes(':')) names.add(decoration.structure)
  }
  return [...names].sort()
}

/** A parsed structure as the preview takes it. */
export function structureInput(structure: Structure): TerrainStructureInput {
  return {
    size: [...structure.size],
    palette: structure.palette,
    blocks: Array.from(structure.blocks),
  }
}
