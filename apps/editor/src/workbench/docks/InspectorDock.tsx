/**
 * The Inspector panel: whatever the active editor portals into it with
 * `InspectorPanel`. Editors portal into one element the workbench keeps for
 * the whole session (`useInspectorTarget`), whether or not the panel shows,
 * so an editor's inspector always has somewhere to go; the panel puts that
 * element in itself while it's on screen, wherever it's docked.
 * A read-only document's inspector is disabled, as its view is.
 */
import { useContext, useEffect, useRef, useState } from 'react'
import { useWorkspace } from '@/state/providers'
import { readOnlyReason } from '@/core/store/project'
import { InspectorTarget } from '@/editors/shared/docks'
import styles from './Docks.module.css'

/** The element editors' inspectors render into, disabled while the active document is read-only. */
export function useInspectorTarget(): HTMLFieldSetElement {
  const [target] = useState(() => {
    const element = document.createElement('fieldset')
    element.className = styles.target!
    return element
  })
  const readOnly = useWorkspace((s) => {
    const path = s.tabs.find((it) => it.id === s.activeTab)?.path
    return path !== undefined && readOnlyReason(s.readOnly, path) !== null
  })
  useEffect(() => {
    target.toggleAttribute('disabled', readOnly)
  }, [target, readOnly])
  return target
}

export function InspectorDock() {
  const target = useContext(InspectorTarget)
  const host = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const element = host.current
    if (!element || !target) return
    element.prepend(target)
    return () => target.remove()
  }, [target])
  return (
    <section className={styles.dock} aria-label="Inspector dock">
      <div ref={host} className={styles.host} />
      <p className={styles.empty}>Nothing to inspect here.</p>
    </section>
  )
}
