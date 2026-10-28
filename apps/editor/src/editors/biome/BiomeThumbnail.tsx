/** A biome's picture: its colours as the biome editor's little landscape, as it is now (unsaved edits too). */
import type { BiomeFile } from '@/core/format'
import { mainFileIn } from '@/core/paths'
import type { ThumbnailProps } from '@/editors/contributions'
import { currentText, parseCached } from '@/minecraft/item/projectItems'
import { useWorkspace } from '@/state/providers'
import { BiomeScene } from './BiomeScene'

export function BiomeThumbnail({ id, size, fallback }: ThumbnailProps) {
  const text = useWorkspace((s) => currentText(s, mainFileIn(s.files, 'biome', id)))
  const colors = parseCached<BiomeFile>(text)?.colors
  // A biome that leaves every colour to the world has nothing of its own to show.
  if (typeof colors !== 'object' || colors === null || !Object.values(colors).some(Boolean))
    return <>{fallback}</>
  return (
    <span style={{ width: size, display: 'block' }}>
      <BiomeScene colors={colors} small />
    </span>
  )
}
