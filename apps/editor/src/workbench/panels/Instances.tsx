/** The centities alive on the dev server right now. */
import { useEffect } from 'react'
import { useApp, useRun } from '@/state/providers'
import { Button } from '@/ui/Button'
import { Bar } from '@/ui/layout'
import { Empty } from '@/ui/text'
import styles from './Instances.module.css'

export function InstancesPanel() {
  const { run } = useApp()
  const instances = useRun((s) => s.instances)
  const connected = useRun((s) => s.server.bridgeConnected)
  const live = useRun((s) => s.liveInstances)

  useEffect(() => {
    if (connected) void run.getState().refreshInstances()
  }, [connected, live, run])

  if (!connected) return <Empty>Start the dev server to see live centities.</Empty>
  return (
    <div className={styles.instances}>
      <Bar>
        <span>{instances.length} live</span>
        <Button size="small" icon="refresh" onClick={() => void run.getState().refreshInstances()}>
          Refresh
        </Button>
      </Bar>
      <table aria-label="Instances">
        <thead>
          <tr>
            <th>Centity</th>
            <th>World</th>
            <th>Position</th>
            <th>UUID</th>
          </tr>
        </thead>
        <tbody>
          {instances.map((it) => (
            <tr key={it.uuid}>
              <td>{it.centity}</td>
              <td>{it.world}</td>
              <td>
                {it.x.toFixed(1)}, {it.y.toFixed(1)}, {it.z.toFixed(1)}
              </td>
              <td className={styles.uuid}>{it.uuid}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
