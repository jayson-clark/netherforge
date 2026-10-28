/**
 * The terrain's inspector: every part of the file as fields, in the order a column of the world is
 * made (terrain and its 3D ground, layers, caves, ores, decorations), then the biomes (with their own terrain and the jitter of
 * their borders), structures, and the script the file hands stages to. Each is an edit through `edit`; the
 * preview beside it redraws from the file as it then is. Each section is a module of its own here.
 */
import { useApp } from '@/state/providers'
import { BIOME_REGISTRY, type TerrainFile } from '@/core/format'
import { BIOME_FOLDER, biomeChoices } from '@/editors/biome/ops'
import { usePickerIds } from '@/minecraft/client/usePickerIds'
import { useWorkspace } from '@/state/providers'
import { Datalist } from '@/ui/fields'
import { BiomesSection } from './BiomesSection'
import { CavesSection } from './CavesSection'
import { DecorationsSection } from './DecorationsSection'
import { FloorSection, LayersSection } from './GroundSections'
import { OresSection } from './OresSection'
import { ScriptSection } from './ScriptSection'
import { StructuresSection } from './StructuresSection'
import { TerrainSection } from './TerrainSection'
import { BIOMES_LIST, BLOCKS_LIST, type Edit, NO_IDS } from './fields'

export function TerrainInspector({
  path,
  model,
  edit,
}: {
  path: string
  model: TerrainFile
  edit: Edit
}) {
  const { workspace } = useApp()
  const { blocks } = usePickerIds()
  const gameData = useWorkspace((s) => s.gameData)
  const projectBiomes = useWorkspace((s) => s.outline?.registryNames?.[BIOME_FOLDER] ?? NO_IDS)
  // The project's own biomes (its datapacks' too) by id, then the game's in full.
  const biomes = biomeChoices(projectBiomes, gameData?.registries?.[BIOME_REGISTRY] ?? NO_IDS)
  const customBlocks = useWorkspace((s) => s.outline?.resources.block ?? NO_IDS)
  const structures = useWorkspace((s) => s.outline?.resources.structure ?? NO_IDS)
  const lootTables = useWorkspace((s) => s.outline?.resources.loot_table ?? NO_IDS)
  const gesture = {
    onGestureStart: () => workspace.getState().beginGesture(path),
    onGestureEnd: () => workspace.getState().endGesture(path),
  }
  const common = { edit, gesture, blocks, customBlocks, structures, biomes, lootTables }
  return (
    <>
      <Datalist id={BLOCKS_LIST} values={blocks} />
      <Datalist id={BIOMES_LIST} values={biomes} />
      <TerrainSection model={model} {...common} />
      <LayersSection model={model} {...common} />
      <FloorSection model={model} {...common} />
      <CavesSection model={model} {...common} />
      <OresSection model={model} {...common} />
      <DecorationsSection model={model} {...common} />
      <BiomesSection model={model} {...common} />
      <StructuresSection model={model} {...common} />
      <ScriptSection path={path} model={model} {...common} />
    </>
  )
}
