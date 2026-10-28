/**
 * The open tabs, as react-aria's `TabList` (inside `EditorArea`'s `Tabs`):
 * click or the arrow keys to show, middle-click or the × to close, drag to
 * reorder, right-click for close others / to the right / saved. An unsaved
 * tab shows a dot in place of its × until hovered, as in VS Code.
 */
import { useState } from 'react'
import { Tab as AriaTab, TabList } from 'react-aria-components'
import type { Tab } from '@/core/store/tabs'
import { useApp, useWorkspace } from '@/state/providers'
import { openMenu } from '@/ui/ContextMenu'
import { cx } from '@/ui/cx'
import { Icon } from '@/ui/Icon'
import { TAB_VIEWS } from '@/workbench/contributions'
import { resourceAt } from '@/workbench/explorer/folders'
import { Thumbnail } from '@/workbench/explorer/Thumbnail'
import { closeTabAsking, closeTabsAsking } from './tabs'
import styles from './TabStrip.module.css'

/** A tab's name: a resource by its id (a structure or map by its file or folder), a file by its name. */
export const tabLabel = (tab: Tab): string => TAB_VIEWS[tab.type].label(tab.path)

/**
 * A resource's own tab shows its thumbnail, as the explorer does; a file
 * inside it (a script), settings and plain files show their kind's icon.
 */
function TabPicture({ tab, files }: { tab: Tab; files: string[] }) {
  const view = TAB_VIEWS[tab.type]
  const at = view.resource ? resourceAt(tab.path, files) : null
  if (!at) return <Icon name={view.icon} />
  return (
    <span className={styles.picture}>
      <Thumbnail folder={at.folder} id={at.id} size={28} />
    </span>
  )
}

const TAB_MIME = 'application/x-netherforge-tab'

export function TabStrip({ tabs }: { tabs: Tab[] }) {
  const { workspace } = useApp()
  const docs = useWorkspace((s) => s.docs)
  const files = useWorkspace((s) => s.files)
  // While a tab is dragged: the gap it would land in (0 = before the first tab).
  const [dragging, setDragging] = useState<string | null>(null)
  const [gap, setGap] = useState<number | null>(null)

  const endDrag = () => {
    setDragging(null)
    setGap(null)
  }
  const drop = () => {
    const from = tabs.findIndex((it) => it.id === dragging)
    if (dragging && from >= 0 && gap !== null) {
      workspace.getState().moveTab(dragging, gap > from ? gap - 1 : gap)
    }
    endDrag()
  }

  const menuFor = (tab: Tab, index: number) => [
    { label: 'Close', shortcut: 'Mod+W', run: () => void closeTabAsking(workspace, tab.id) },
    {
      label: 'Close Others',
      disabled: tabs.length < 2,
      run: () =>
        void closeTabsAsking(
          workspace,
          tabs.filter((it) => it.id !== tab.id).map((it) => it.id),
        ),
    },
    {
      label: 'Close to the Right',
      disabled: index === tabs.length - 1,
      run: () =>
        void closeTabsAsking(
          workspace,
          tabs.slice(index + 1).map((it) => it.id),
        ),
    },
    {
      label: 'Close Saved',
      run: () =>
        void closeTabsAsking(
          workspace,
          tabs.filter((it) => !docs[it.path]?.dirty).map((it) => it.id),
        ),
    },
    'separator' as const,
    { label: 'Copy Path', run: () => void navigator.clipboard?.writeText(tab.path) },
  ]

  return (
    <div
      className={styles.strip}
      onDragOver={(event) => {
        if (!dragging) return
        event.preventDefault()
        // Past the last tab: the end.
        if (!(event.target as Element).closest('[role="tab"]')) setGap(tabs.length)
      }}
      onDrop={(event) => {
        if (!dragging) return
        event.preventDefault()
        drop()
      }}
    >
      <TabList className={styles.tabs} aria-label="Open files">
        {tabs.map((tab, index) => {
          const dirty = !!docs[tab.path]?.dirty
          const label = tabLabel(tab)
          return (
            <AriaTab
              key={tab.id}
              id={tab.id}
              aria-label={`${label}${dirty ? ' (unsaved)' : ''}`}
              className={cx(
                styles.tab,
                tab.id === dragging && styles.dragging,
                dirty && styles.dirty,
                !!dragging && gap === index && styles.dropBefore,
                !!dragging && gap === tabs.length && index === tabs.length - 1 && styles.dropAfter,
              )}
              // Drag to reorder: react-aria's tabs have no drag and drop of their own.
              render={(props) =>
                'href' in props ? (
                  <a {...props} />
                ) : (
                  <div
                    {...props}
                    title={tab.path}
                    draggable
                    onDragStart={(event) => {
                      event.dataTransfer.effectAllowed = 'move'
                      event.dataTransfer.setData(TAB_MIME, tab.id)
                      setDragging(tab.id)
                      workspace.getState().activate(tab.id)
                    }}
                    onDragOver={(event) => {
                      if (!dragging) return
                      event.preventDefault()
                      event.dataTransfer.dropEffect = 'move'
                      const rect = event.currentTarget.getBoundingClientRect()
                      setGap(event.clientX < rect.left + rect.width / 2 ? index : index + 1)
                    }}
                    onDragEnd={endDrag}
                  />
                )
              }
              onAuxClick={(event) => event.button === 1 && void closeTabAsking(workspace, tab.id)}
              onContextMenu={(event) => openMenu(event, menuFor(tab, index))}
            >
              <TabPicture tab={tab} files={files} />
              <span>{label}</span>
              {/* Out of the tab order (Ctrl/Cmd+W closes), and its press isn't the tab's. */}
              <button
                type="button"
                tabIndex={-1}
                className={styles.close}
                aria-label={`Close ${label}`}
                onPointerDown={(event) => event.stopPropagation()}
                onClick={(event) => {
                  event.stopPropagation()
                  void closeTabAsking(workspace, tab.id)
                }}
              >
                {dirty && <span className={styles.dirtyDot} aria-hidden="true" />}
                <Icon name="close" size={10} className={styles.closeIcon} />
              </button>
            </AriaTab>
          )
        })}
      </TabList>
    </div>
  )
}
