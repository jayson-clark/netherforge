/**
 * Where the workbench's panels are: three dock areas (left, right, bottom),
 * each empty or a tree of splits whose leaves are tab groups. A panel is
 * named by its id (the workbench's `DOCK_PANELS`) and is in exactly one
 * group. Everything here is pure: the layout store keeps the tree and calls
 * these to change it.
 */

export type DockArea = 'left' | 'right' | 'bottom'
export const DOCK_AREAS: readonly DockArea[] = ['left', 'right', 'bottom']

/** Panels shown one at a time behind a row of tabs. */
export interface TabGroup {
  type: 'tabs'
  id: string
  tabs: string[]
  /** The tab showing. */
  active: string
}

/** Nodes side by side (`row`) or stacked (`column`), each its share of the space. */
export interface DockSplit {
  type: 'split'
  id: string
  direction: 'row' | 'column'
  children: DockNode[]
  /** Fractions, one per child, adding up to 1. */
  sizes: number[]
}

export type DockNode = TabGroup | DockSplit
export type Docks = Record<DockArea, DockNode | null>

/** A group's side a panel is dropped on (splitting it there), or its middle (joining it). */
export type DropEdge = 'center' | 'left' | 'right' | 'top' | 'bottom'

/** Where a dragged panel goes. */
export type DockTarget =
  | { group: string; edge: DropEdge }
  /** Into a group's tab row, before the tab at [index]. */
  | { group: string; index: number }
  /** Into an area: its first group's tabs, or the area's only group when it's empty. */
  | { area: DockArea }

/**
 * Where the panels start, and so every panel there is: the outline over
 * Instances on the left, the inspector on the right, and at the bottom the
 * project explorer, Problems, and the Console with the other output panels
 * behind it, so what's used most is on screen at once.
 */
export const DEFAULT_DOCKS: Docks = {
  left: {
    type: 'split',
    id: 'left',
    direction: 'column',
    sizes: [0.65, 0.35],
    children: [
      { type: 'tabs', id: 'left-outline', tabs: ['outline'], active: 'outline' },
      { type: 'tabs', id: 'left-instances', tabs: ['instances'], active: 'instances' },
    ],
  },
  right: { type: 'tabs', id: 'right', tabs: ['inspector'], active: 'inspector' },
  bottom: {
    type: 'split',
    id: 'bottom',
    direction: 'row',
    sizes: [0.4, 0.25, 0.35],
    children: [
      { type: 'tabs', id: 'bottom-project', tabs: ['project'], active: 'project' },
      { type: 'tabs', id: 'bottom-problems', tabs: ['problems'], active: 'problems' },
      {
        type: 'tabs',
        id: 'bottom-output',
        tabs: ['console', 'tests', 'profiler', 'debug', 'network'],
        active: 'console',
      },
    ],
  },
}

/** A split's child can't be dragged smaller than this share. */
export const MIN_SHARE = 0.1

const groupsIn = (node: DockNode | null): TabGroup[] =>
  !node ? [] : node.type === 'tabs' ? [node] : node.children.flatMap(groupsIn)

/** Every panel id in [docks], in order. */
export const panelsOf = (docks: Docks): string[] =>
  DOCK_AREAS.flatMap((area) => groupsIn(docks[area]).flatMap((it) => it.tabs))

/** The groups of an area, in order. */
export const groupsOf = groupsIn

/** The group and area panel [id] is in. */
export function findPanel(docks: Docks, id: string): { area: DockArea; group: TabGroup } | null {
  for (const area of DOCK_AREAS) {
    const group = groupsIn(docks[area]).find((it) => it.tabs.includes(id))
    if (group) return { area, group }
  }
  return null
}

/** The area holding node [id]. */
export function areaOf(docks: Docks, id: string): DockArea | null {
  const has = (node: DockNode | null): boolean =>
    !!node && (node.id === id || (node.type === 'split' && node.children.some(has)))
  return DOCK_AREAS.find((area) => has(docks[area])) ?? null
}

/**
 * The group at an area's top right: where its own buttons (hide the dock)
 * go, and what a panel moved into the area joins.
 */
export function cornerGroup(node: DockNode): TabGroup {
  if (node.type === 'tabs') return node
  const child = node.direction === 'row' ? node.children.at(-1)! : node.children[0]!
  return cornerGroup(child)
}

const mapNode = (node: DockNode, fn: (node: DockNode) => DockNode): DockNode => {
  const mapped =
    node.type === 'split' ? { ...node, children: node.children.map((it) => mapNode(it, fn)) } : node
  return fn(mapped)
}
const mapDocks = (docks: Docks, fn: (node: DockNode) => DockNode): Docks => ({
  left: docks.left && mapNode(docks.left, fn),
  right: docks.right && mapNode(docks.right, fn),
  bottom: docks.bottom && mapNode(docks.bottom, fn),
})

/** Shows panel [id] in its group. */
export function selectPanel(docks: Docks, id: string): Docks {
  const found = findPanel(docks, id)
  if (!found || found.group.active === id) return docks
  return mapDocks(docks, (node) => (node.id === found.group.id ? { ...node, active: id } : node))
}

/** A split's children's new shares (fractions, in order). */
export function resizeSplit(docks: Docks, id: string, sizes: number[]): Docks {
  return mapDocks(docks, (node) =>
    node.id === id && node.type === 'split' && sizes.length === node.children.length
      ? { ...node, sizes: shares(sizes) }
      : node,
  )
}

/** [sizes] scaled to add up to 1 (equal shares when they can't). */
function shares(sizes: number[]): number[] {
  const clean = sizes.map((it) => (Number.isFinite(it) && it > 0 ? it : 0))
  const total = clean.reduce((a, b) => a + b, 0)
  return total > 0 ? clean.map((it) => it / total) : sizes.map(() => 1 / sizes.length)
}

let counter = 0
/** An id no node has: nodes are made in more than one session, so it's random too. */
const newId = () => `dock-${Date.now().toString(36)}-${(counter++).toString(36)}`

/** Stands in for a panel between taking it out and putting it in, so neither moves the other. */
const MOVING = '\u0000moving'

/**
 * Moves panel [id] to [target]: into a group's tabs, beside a group
 * (splitting it), or into an area. Groups left empty go, and so do splits
 * left with one child.
 */
export function movePanel(docks: Docks, id: string, target: DockTarget): Docks {
  const from = findPanel(docks, id)
  if (!from) return docks
  // Only a tab group takes a panel (anything else would lose it).
  if (
    'group' in target &&
    !DOCK_AREAS.some((area) => groupsIn(docks[area]).some((it) => it.id === target.group))
  ) {
    return docks
  }
  // Splitting a group by its only panel changes nothing.
  if ('edge' in target && target.group === from.group.id && from.group.tabs.length === 1) {
    return docks
  }
  // Mark where it was, put it where it goes, then take the mark out.
  let next = mapDocks(docks, (node) =>
    node.id === from.group.id && node.type === 'tabs'
      ? { ...node, tabs: node.tabs.map((it) => (it === id ? MOVING : it)) }
      : node,
  )
  next = insert(next, id, target)
  next = mapDocks(next, (node) =>
    node.type === 'tabs' && node.tabs.includes(MOVING)
      ? { ...node, tabs: node.tabs.filter((it) => it !== MOVING) }
      : node,
  )
  return tidy(next)
}

function insert(docks: Docks, id: string, target: DockTarget): Docks {
  const group = (): TabGroup => ({ type: 'tabs', id: newId(), tabs: [id], active: id })
  if ('area' in target) {
    const root = docks[target.area]
    if (!root) return { ...docks, [target.area]: group() }
    const corner = cornerGroup(root)
    return insert(docks, id, { group: corner.id, index: corner.tabs.length })
  }
  if ('index' in target) {
    return mapDocks(docks, (node) => {
      if (node.id !== target.group || node.type !== 'tabs') return node
      const tabs = [...node.tabs]
      tabs.splice(Math.max(0, Math.min(target.index, tabs.length)), 0, id)
      return { ...node, tabs, active: id }
    })
  }
  if (target.edge === 'center') {
    return mapDocks(docks, (node) =>
      node.id === target.group && node.type === 'tabs'
        ? { ...node, tabs: [...node.tabs, id], active: id }
        : node,
    )
  }
  const direction = target.edge === 'left' || target.edge === 'right' ? 'row' : 'column'
  const before = target.edge === 'left' || target.edge === 'top'
  const added = group()
  const splitAround = (node: DockNode): DockNode => {
    if (node.type === 'split') {
      const index = node.children.findIndex((it) => it.id === target.group)
      // Beside a child of a split the same way: one more child, in half of the child's share.
      if (index >= 0 && node.direction === direction) {
        const children = [...node.children]
        const sizes = [...node.sizes]
        const half = sizes[index]! / 2
        sizes[index] = half
        children.splice(before ? index : index + 1, 0, added)
        sizes.splice(before ? index : index + 1, 0, half)
        return { ...node, children, sizes }
      }
      return node
    }
    if (node.id !== target.group) return node
    return {
      type: 'split',
      id: newId(),
      direction,
      children: before ? [added, node] : [node, added],
      sizes: [0.5, 0.5],
    }
  }
  // Splits first (the parent takes the new child), else the group becomes a split.
  const parentTakes = (node: DockNode | null): boolean =>
    !!node &&
    node.type === 'split' &&
    ((node.direction === direction && node.children.some((it) => it.id === target.group)) ||
      node.children.some(parentTakes))
  const area = areaOf(docks, target.group)
  if (!area) return docks
  const root = docks[area]!
  const replaced = parentTakes(root)
    ? mapNode(root, (node) => (node.type === 'split' ? splitAround(node) : node))
    : mapNode(root, (node) => (node.type === 'tabs' ? splitAround(node) : node))
  return { ...docks, [area]: replaced }
}

/** Without empty groups, splits of one child, or a split inside one the same way. */
function tidy(docks: Docks): Docks {
  const clean = (node: DockNode): DockNode | null => {
    if (node.type === 'tabs') {
      if (node.tabs.length === 0) return null
      return node.tabs.includes(node.active) ? node : { ...node, active: node.tabs[0]! }
    }
    const children: DockNode[] = []
    const sizes: number[] = []
    node.children.forEach((child, index) => {
      const kept = clean(child)
      if (!kept) return
      const size = node.sizes[index] ?? 1 / node.children.length
      if (kept.type === 'split' && kept.direction === node.direction) {
        kept.children.forEach((it, i) => {
          children.push(it)
          sizes.push(size * kept.sizes[i]!)
        })
      } else {
        children.push(kept)
        sizes.push(size)
      }
    })
    if (children.length === 0) return null
    if (children.length === 1) return children[0]!
    return { ...node, children, sizes: shares(sizes) }
  }
  return {
    left: docks.left && clean(docks.left),
    right: docks.right && clean(docks.right),
    bottom: docks.bottom && clean(docks.bottom),
  }
}

/**
 * What was stored, held to the shapes above and to the panels there are
 * ([known], each with the area it starts in): a panel that's gone is left
 * out, one stored twice is kept once, and one the stored docks don't have
 * (new since) joins the group of a panel it starts beside, or its area.
 */
export function readDocks(stored: unknown, defaults: Docks = DEFAULT_DOCKS): Docks {
  const known = new Set(panelsOf(defaults))
  const seen = new Set<string>()
  const ids = new Set<string>()
  const read = (value: unknown, depth: number): DockNode | null => {
    if (typeof value !== 'object' || value === null || depth > 8) return null
    const it = value as Record<string, unknown>
    let id = typeof it.id === 'string' && it.id !== '' ? it.id : newId()
    if (ids.has(id)) id = newId()
    ids.add(id)
    if (it.type === 'tabs') {
      const tabs: string[] = []
      for (const tab of Array.isArray(it.tabs) ? it.tabs : []) {
        if (typeof tab !== 'string' || !known.has(tab) || seen.has(tab)) continue
        seen.add(tab)
        tabs.push(tab)
      }
      if (tabs.length === 0) return null
      const active =
        typeof it.active === 'string' && tabs.includes(it.active) ? it.active : tabs[0]!
      return { type: 'tabs', id, tabs, active }
    }
    if (it.type === 'split' && Array.isArray(it.children)) {
      const direction = it.direction === 'column' ? 'column' : 'row'
      const sizes = Array.isArray(it.sizes) ? it.sizes : []
      const children: DockNode[] = []
      const kept: number[] = []
      it.children.forEach((child, index) => {
        const node = read(child, depth + 1)
        if (!node) return
        children.push(node)
        const size = sizes[index]
        kept.push(typeof size === 'number' ? Math.max(MIN_SHARE, size) : 1)
      })
      return { type: 'split', id, direction, children, sizes: kept }
    }
    return null
  }
  if (typeof stored !== 'object' || stored === null) return defaults
  const raw = stored as Record<string, unknown>
  let docks = tidy({
    left: read(raw.left, 0),
    right: read(raw.right, 0),
    bottom: read(raw.bottom, 0),
  })
  // Panels the stored docks don't have go where they start.
  for (const area of DOCK_AREAS) {
    for (const group of groupsIn(defaults[area])) {
      for (const id of group.tabs) {
        if (seen.has(id)) continue
        seen.add(id)
        const beside = group.tabs.map((it) => findPanel(docks, it)).find((it) => it !== null)
        const root = docks[area]
        const joins = beside?.group ?? (root ? cornerGroup(root) : null)
        docks = insert(docks, id, joins ? { group: joins.id, index: joins.tabs.length } : { area })
        // A panel coming back unasked doesn't take over the group it joins.
        if (joins) {
          docks = mapDocks(docks, (node) =>
            node.id === joins.id ? { ...node, active: joins.active } : node,
          )
        }
      }
    }
  }
  return docks
}
