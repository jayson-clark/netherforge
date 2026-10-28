/** The dev server's loaded worlds, for the screens that capture from it. */
import { useCallback, useEffect, useState } from 'react'
import type { LoadedWorld } from '@netherforge/format/types'
import { useApp } from '@/state/providers'
import { Row } from '@/ui/fields'
import { IconButton } from '@/ui/Button'
import styles from './ServerWorlds.module.css'

/** A loaded world on the dev server, picked from what it has now. */
export function WorldSelect({
  worlds,
  value,
  onChange,
  onRefresh,
  disabled,
}: {
  worlds: LoadedWorld[]
  value: string
  onChange: (world: string) => void
  onRefresh: () => void
  disabled: boolean
}) {
  return (
    <Row label="World">
      <span className={styles.inline}>
        <select
          aria-label="World"
          value={value}
          disabled={disabled || worlds.length === 0}
          onChange={(event) => onChange(event.target.value)}
        >
          {worlds.length === 0 && <option value={value}>{value || 'No worlds'}</option>}
          {worlds.map((it) => (
            <option key={it.name} value={it.name}>
              {it.main ? `${it.name} (main)` : it.name}
            </option>
          ))}
        </select>
        <IconButton
          icon="refresh"
          label="Refresh worlds"
          title="Ask the server for its worlds again"
          disabled={disabled}
          onClick={onRefresh}
        />
      </span>
    </Row>
  )
}

/**
 * The dev server's loaded worlds while it's connected (asked again on
 * reconnect, and by [refresh]: a script may have made one since). The last
 * answer stays while a new one is on its way.
 */
export function useLoadedWorlds(connected: boolean): {
  worlds: LoadedWorld[]
  refresh: () => void
} {
  const { backend } = useApp()
  const [generation, setGeneration] = useState(0)
  const [worlds, setWorlds] = useState<LoadedWorld[]>([])
  const refresh = useCallback(() => setGeneration((it) => it + 1), [])
  useEffect(() => {
    if (!connected) return
    let live = true
    backend.bridgeRequest('worlds').then(
      (answer) => live && setWorlds(answer),
      () => live && setWorlds([]),
    )
    return () => {
      live = false
    }
  }, [backend, connected, generation])
  return { worlds: connected ? worlds : [], refresh }
}
