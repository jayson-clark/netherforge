/**
 * A dock area (left, right or bottom) as the layout has it (`core/store/docks.ts`):
 * splits drawn as resizable groups, and at their leaves tab groups of the
 * workbench's panels (`DOCK_PANELS`). A panel's tab drags to another group's
 * tabs, onto a group (its middle joins it, an edge splits it there), or onto
 * a dock that's empty or hidden (`AreaDrops`); its context menu does the same
 * without a pointer, and resets the layout.
 */
import { Fragment, useState, type DragEvent } from 'react'
import { Group, Panel, useGroupRef } from 'react-resizable-panels'
import { Tab, TabList, TabPanel, Tabs } from 'react-aria-components'
import { create } from 'zustand'
import { useApp, useLayout } from '@/state/providers'
import {
  cornerGroup,
  DOCK_AREAS,
  MIN_SHARE,
  type DockArea,
  type DockNode,
  type DockSplit,
  type DropEdge,
  type TabGroup,
} from '@/core/store/docks'
import { OPEN } from '@/core/store/layout'
import { IconButton } from '@/ui/Button'
import { openMenu, type MenuItem } from '@/ui/ContextMenu'
import { cx } from '@/ui/cx'
import { Splitter } from '@/ui/Splitter'
import { panelById } from '@/workbench/contributions'
import styles from './DockArea.module.css'

export const AREA_LABEL: Record<DockArea, string> = {
  left: 'Left dock',
  right: 'Right dock',
  bottom: 'Bottom dock',
}

/** The panel being dragged, while one is: drop targets show only then. */
const useDragging = create<{ panel: string | null }>(() => ({ panel: null }))
const endDrag = () => useDragging.setState({ panel: null })
const DOCK_MIME = 'application/x-netherforge-dock-panel'

/** Within this share of a group's edge, a drop splits the group there. */
const EDGE_SHARE = 0.25

export function DockAreaView({ area }: { area: DockArea }) {
  const root = useLayout((s) => s.docks[area])
  if (!root) return null
  return (
    <section className={styles.area} aria-label={AREA_LABEL[area]} data-dock={area}>
      <NodeView node={root} area={area} corner={cornerGroup(root).id} />
    </section>
  )
}

function NodeView({ node, area, corner }: { node: DockNode; area: DockArea; corner: string }) {
  return node.type === 'tabs' ? (
    <GroupView group={node} area={area} corner={node.id === corner} />
  ) : (
    <SplitView split={node} area={area} corner={corner} />
  )
}

const panelId = (node: DockNode) => `dock-${node.id}`

function SplitView({ split, area, corner }: { split: DockSplit; area: DockArea; corner: string }) {
  const { layout } = useApp()
  const group = useGroupRef()
  const percents = (sizes: number[]) =>
    Object.fromEntries(split.children.map((child, i) => [panelId(child), sizes[i]! * 100]))
  return (
    <Group
      // Another set of children is another group: its sizes are taken afresh.
      key={split.children.map((it) => it.id).join('|')}
      id={panelId(split)}
      groupRef={group}
      orientation={split.direction === 'row' ? 'horizontal' : 'vertical'}
      defaultLayout={percents(split.sizes)}
      onLayoutChanged={(next, meta) => {
        if (!meta.isUserInteraction) return
        layout.getState().resizeSplit(
          split.id,
          split.children.map((child) => next[panelId(child)] ?? 0),
        )
      }}
    >
      {split.children.map((child, index) => (
        <Fragment key={child.id}>
          {index > 0 && (
            <Splitter
              label="Resize the panels"
              onReset={() => {
                const equal = split.children.map(() => 1 / split.children.length)
                group.current?.setLayout(percents(equal))
                layout.getState().resizeSplit(split.id, equal)
              }}
            />
          )}
          <Panel
            id={panelId(child)}
            className={styles.cell}
            style={{ overflow: 'hidden' }}
            minSize={`${MIN_SHARE * 100}`}
          >
            <NodeView node={child} area={area} corner={corner} />
          </Panel>
        </Fragment>
      ))}
    </Group>
  )
}

function GroupView({ group, area, corner }: { group: TabGroup; area: DockArea; corner: boolean }) {
  const { layout } = useApp()
  const dragging = useDragging((s) => s.panel)
  // Where a dragged tab would land: a gap in the tab row (0 = before the first), or a side of the body.
  const [gap, setGap] = useState<number | null>(null)
  const [edge, setEdge] = useState<DropEdge | null>(null)
  const panels = group.tabs.flatMap((id) => panelById(id) ?? [])
  const shown = panelById(group.active) ?? panels[0]
  if (!shown) return null
  const View = shown.view
  // A group's only panel has nowhere to go inside it.
  const alone = dragging !== null && group.tabs.length === 1 && group.tabs[0] === dragging
  const accepts = dragging !== null && !alone

  const clear = () => {
    setGap(null)
    setEdge(null)
  }
  const over = (event: DragEvent) => {
    event.preventDefault()
    event.dataTransfer.dropEffect = 'move'
  }
  const drop = (event: DragEvent, run: (id: string) => void) => {
    event.preventDefault()
    event.stopPropagation()
    const id = useDragging.getState().panel
    clear()
    endDrag()
    if (id) run(id)
  }

  const move = layout.getState().movePanel
  const menuFor = (id: string): MenuItem[] => [
    {
      label: 'Split Right',
      disabled: group.tabs.length < 2,
      run: () => move(id, { group: group.id, edge: 'right' }),
    },
    {
      label: 'Split Down',
      disabled: group.tabs.length < 2,
      run: () => move(id, { group: group.id, edge: 'bottom' }),
    },
    'separator',
    ...DOCK_AREAS.map((to) => ({
      label: `Move to ${AREA_LABEL[to]}`,
      disabled: to === area,
      run: () => move(id, { area: to }),
    })),
    'separator',
    {
      label: `Hide ${AREA_LABEL[area]}`,
      run: () => layout.getState().set({ [OPEN[area]]: false }),
    },
    { label: 'Reset Layout', run: () => layout.getState().resetLayout() },
  ]

  return (
    <Tabs
      className={styles.group}
      selectedKey={shown.id}
      onSelectionChange={(key) => layout.getState().reveal(String(key))}
    >
      <div
        className={styles.row}
        onDragOver={(event) => {
          if (!dragging) return
          over(event)
          // Past the last tab: the end.
          if (!(event.target as Element).closest('[role="tab"]')) setGap(panels.length)
        }}
        onDragLeave={(event) => {
          if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setGap(null)
        }}
        onDrop={(event) =>
          drop(event, (id) => move(id, { group: group.id, index: gap ?? panels.length }))
        }
      >
        <TabList aria-label={`${AREA_LABEL[area]} panels`} className={styles.tabs}>
          {panels.map(({ id, title, badge: Badge }, index) => (
            <Tab
              key={id}
              id={id}
              aria-label={title}
              className={cx(
                styles.tab,
                id === dragging && styles.dragging,
                dragging !== null && gap === index && styles.dropBefore,
                dragging !== null &&
                  gap === panels.length &&
                  index === panels.length - 1 &&
                  styles.dropAfter,
              )}
              // react-aria's tabs have no drag and drop of their own.
              render={(props) =>
                'href' in props ? (
                  <a {...props} />
                ) : (
                  <div
                    {...props}
                    draggable
                    onDragStart={(event) => {
                      event.dataTransfer.effectAllowed = 'move'
                      event.dataTransfer.setData(DOCK_MIME, id)
                      useDragging.setState({ panel: id })
                    }}
                    onDragOver={(event) => {
                      if (!dragging) return
                      over(event)
                      const rect = event.currentTarget.getBoundingClientRect()
                      setGap(event.clientX < rect.left + rect.width / 2 ? index : index + 1)
                    }}
                    onDragEnd={endDrag}
                  />
                )
              }
              onContextMenu={(event) => openMenu(event, menuFor(id))}
            >
              {title}
              {Badge && <Badge />}
            </Tab>
          ))}
        </TabList>
        {corner && (
          <div className={styles.end}>
            <IconButton
              icon="close"
              size={12}
              label={`Hide the ${AREA_LABEL[area].toLowerCase()}`}
              onClick={() => layout.getState().set({ [OPEN[area]]: false })}
            />
          </div>
        )}
      </div>
      <TabPanel id={shown.id} className={styles.body}>
        <View />
        {accepts && (
          <div
            className={styles.drop}
            data-drop-zone
            onDragOver={(event) => {
              over(event)
              setEdge(edgeAt(event))
            }}
            onDragLeave={() => setEdge(null)}
            onDrop={(event) => {
              const at = edgeAt(event)
              drop(event, (id) => move(id, { group: group.id, edge: at }))
            }}
          >
            {edge && <div className={cx(styles.preview, styles[edge])} aria-hidden="true" />}
          </div>
        )}
      </TabPanel>
    </Tabs>
  )
}

/** The side of the element under the pointer it's nearest, when it's close to one, else the middle. */
function edgeAt(event: DragEvent): DropEdge {
  const rect = event.currentTarget.getBoundingClientRect()
  const x = (event.clientX - rect.left) / Math.max(1, rect.width)
  const y = (event.clientY - rect.top) / Math.max(1, rect.height)
  const [side, distance] = (
    [
      ['left', x],
      ['right', 1 - x],
      ['top', y],
      ['bottom', 1 - y],
    ] as const
  ).reduce((a, b) => (b[1] < a[1] ? b : a))
  return distance < EDGE_SHARE ? side : 'center'
}

/**
 * While a panel is dragged, a strip at the edge of each dock that's empty or
 * hidden: dropped there, the panel goes into that dock and it shows.
 */
export function AreaDrops() {
  const { layout } = useApp()
  const dragging = useDragging((s) => s.panel)
  const shown = useLayout((s) =>
    DOCK_AREAS.filter((area) => s.docks[area] !== null && s[OPEN[area]]).join(),
  )
  const [hover, setHover] = useState<DockArea | null>(null)
  if (!dragging) return null
  const missing = DOCK_AREAS.filter((area) => !shown.split(',').includes(area))
  return (
    <>
      {missing.map((area) => (
        <div
          key={area}
          className={cx(styles.areaDrop, styles[`area_${area}`], hover === area && styles.hovered)}
          data-drop-zone={area}
          onDragOver={(event) => {
            event.preventDefault()
            event.dataTransfer.dropEffect = 'move'
            setHover(area)
          }}
          onDragLeave={() => setHover(null)}
          onDrop={(event) => {
            event.preventDefault()
            setHover(null)
            const id = useDragging.getState().panel
            endDrag()
            if (id) layout.getState().movePanel(id, { area })
          }}
        >
          {AREA_LABEL[area]}
        </div>
      ))}
    </>
  )
}
