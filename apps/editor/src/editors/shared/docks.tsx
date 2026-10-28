/**
 * Where an editor's inspector goes: the workbench's right dock. The editor
 * keeps rendering its inspector as part of itself (so its state and
 * handlers stay where they are); `InspectorPanel` portals it into the dock
 * the workbench provides. Outside a workbench (a unit test) it renders in
 * place.
 */
import { createContext, useContext, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import styles from './docks.module.css'

export const InspectorTarget = createContext<HTMLElement | null>(null)

export function InspectorPanel({
  label = 'Inspector',
  children,
}: {
  label?: string
  children: ReactNode
}) {
  const target = useContext(InspectorTarget)
  const panel = (
    <aside className={styles.inspector} aria-label={label} data-inspector>
      {children}
    </aside>
  )
  return target ? createPortal(panel, target) : panel
}
