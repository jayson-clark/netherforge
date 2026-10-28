import { ITEM_REGISTRY } from '@netherforge/format/constants'
import { useEffect, useState } from 'react'
import { useApp, useWorkspace } from '@/state/providers'
import { clientAssets, type ClientIndex } from './assets'

/**
 * Ids for the block and item pickers: the dev server's game data when it has
 * been exported, else the client import's index (ids only, no properties).
 */
export function usePickerIds(): { blocks: string[]; items: string[] } {
  const { backend } = useApp()
  const gameData = useWorkspace((s) => s.gameData)
  const minecraft = useWorkspace((s) => s.minecraft)
  const client = useWorkspace((s) => s.cache?.client ?? false)
  const [index, setIndex] = useState<{ version: string; value: ClientIndex | null } | null>(null)
  useEffect(() => {
    if (!client || !minecraft || gameData) return
    let live = true
    void clientAssets(backend, minecraft)
      .json<ClientIndex>('index.json')
      .then((value) => live && setIndex({ version: minecraft, value }))
    return () => {
      live = false
    }
  }, [backend, client, minecraft, gameData])
  if (gameData) {
    return {
      blocks: Object.keys(gameData.blocks ?? {}),
      items: gameData.registries?.[ITEM_REGISTRY] ?? [],
    }
  }
  const fallback = index?.version === minecraft ? index?.value : null
  return { blocks: fallback?.blockstates ?? [], items: fallback?.items ?? [] }
}
