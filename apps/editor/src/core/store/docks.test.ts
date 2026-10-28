import { describe, expect, it } from 'vitest'
import {
  cornerGroup,
  DEFAULT_DOCKS,
  findPanel,
  groupsOf,
  movePanel,
  panelsOf,
  readDocks,
  resizeSplit,
  selectPanel,
  type DockNode,
  type Docks,
} from './docks'

/** An area as nested arrays of tab ids: `[['a', 'b'], ['c']]` (sizes and ids left out). */
const shape = (node: DockNode | null): unknown =>
  !node ? null : node.type === 'tabs' ? node.tabs : { [node.direction]: node.children.map(shape) }

const all = (docks: Docks) => ({
  left: shape(docks.left),
  right: shape(docks.right),
  bottom: shape(docks.bottom),
})

const OUTPUT = ['console', 'tests', 'profiler', 'debug', 'network']

describe('the default layout', () => {
  it('shows the outline over Instances, the inspector, and Project, Problems and the Console side by side', () => {
    expect(all(DEFAULT_DOCKS)).toEqual({
      left: { column: [['outline'], ['instances']] },
      right: ['inspector'],
      bottom: { row: [['project'], ['problems'], OUTPUT] },
    })
    expect(groupsOf(DEFAULT_DOCKS.bottom).map((it) => it.active)).toEqual([
      'project',
      'problems',
      'console',
    ])
  })
})

describe('movePanel', () => {
  it("moves a panel into another group's tabs, at a place, and shows it", () => {
    const docks = movePanel(DEFAULT_DOCKS, 'console', { group: 'right', index: 0 })
    expect(all(docks).right).toEqual(['console', 'inspector'])
    expect(findPanel(docks, 'console')?.group.active).toBe('console')
    expect(all(docks).bottom).toEqual({
      row: [['project'], ['problems'], ['tests', 'profiler', 'debug', 'network']],
    })
  })

  it('reorders within a group', () => {
    const docks = movePanel(DEFAULT_DOCKS, 'debug', { group: 'bottom-output', index: 0 })
    expect(findPanel(docks, 'debug')?.group.tabs).toEqual([
      'debug',
      'console',
      'tests',
      'profiler',
      'network',
    ])
    // Before the tab after itself: where it was.
    const same = movePanel(DEFAULT_DOCKS, 'tests', { group: 'bottom-output', index: 2 })
    expect(findPanel(same, 'tests')?.group.tabs).toEqual(OUTPUT)
  })

  it('splits a group at the edge it was dropped on', () => {
    const below = movePanel(DEFAULT_DOCKS, 'console', { group: 'right', edge: 'bottom' })
    expect(all(below).right).toEqual({ column: [['inspector'], ['console']] })
    const above = movePanel(DEFAULT_DOCKS, 'console', { group: 'right', edge: 'top' })
    expect(all(above).right).toEqual({ column: [['console'], ['inspector']] })
    const split = above.right as Extract<DockNode, { type: 'split' }>
    expect(split.sizes).toEqual([0.5, 0.5])
  })

  it('adds a child to a split going the same way, in half of the share it splits', () => {
    const docks = movePanel(DEFAULT_DOCKS, 'console', { group: 'bottom-project', edge: 'right' })
    expect(all(docks).bottom).toEqual({
      row: [['project'], ['console'], ['problems'], ['tests', 'profiler', 'debug', 'network']],
    })
    const split = docks.bottom as Extract<DockNode, { type: 'split' }>
    expect(split.sizes.map((it) => Math.round(it * 1000) / 1000)).toEqual([0.2, 0.2, 0.25, 0.35])
  })

  it('nests a split the other way', () => {
    const docks = movePanel(DEFAULT_DOCKS, 'console', { group: 'bottom-project', edge: 'bottom' })
    expect(all(docks).bottom).toEqual({
      row: [
        { column: [['project'], ['console']] },
        ['problems'],
        ['tests', 'profiler', 'debug', 'network'],
      ],
    })
  })

  it('takes away groups left empty and splits left with one child', () => {
    const docks = movePanel(DEFAULT_DOCKS, 'problems', { group: 'bottom-output', edge: 'center' })
    expect(all(docks).bottom).toEqual({ row: [['project'], [...OUTPUT, 'problems']] })
    const one = movePanel(DEFAULT_DOCKS, 'instances', { group: 'right', edge: 'bottom' })
    expect(all(one).left).toEqual(['outline'])
    expect(all(one).right).toEqual({ column: [['inspector'], ['instances']] })
    const empty = movePanel(one, 'outline', { group: 'right', edge: 'top' })
    expect(empty.left).toBeNull()
  })

  it('flattens a split into its parent when the middle goes', () => {
    let docks = movePanel(DEFAULT_DOCKS, 'console', { group: 'bottom-project', edge: 'bottom' })
    docks = movePanel(docks, 'project', { group: 'right', edge: 'center' })
    expect(all(docks).bottom).toEqual({
      row: [['console'], ['problems'], ['tests', 'profiler', 'debug', 'network']],
    })
  })

  it("doesn't split a group by its only panel", () => {
    expect(movePanel(DEFAULT_DOCKS, 'inspector', { group: 'right', edge: 'left' })).toBe(
      DEFAULT_DOCKS,
    )
  })

  it("doesn't move a panel onto something that isn't a tab group", () => {
    // `left` is a split: taking the panel out with nowhere to put it would lose it.
    expect(movePanel(DEFAULT_DOCKS, 'debug', { group: 'left', edge: 'right' })).toBe(DEFAULT_DOCKS)
    expect(movePanel(DEFAULT_DOCKS, 'debug', { group: 'nowhere', index: 0 })).toBe(DEFAULT_DOCKS)
  })

  it("goes into an empty area, or an area's corner group", () => {
    let docks = movePanel(DEFAULT_DOCKS, 'outline', { area: 'right' })
    docks = movePanel(docks, 'instances', { area: 'right' })
    expect(docks.left).toBeNull()
    expect(all(docks).right).toEqual(['inspector', 'outline', 'instances'])
    const back = movePanel(docks, 'console', { area: 'left' })
    expect(all(back).left).toEqual(['console'])
    // The bottom's corner is its rightmost group, the left's its top one.
    expect(cornerGroup(DEFAULT_DOCKS.bottom!).id).toBe('bottom-output')
    expect(cornerGroup(DEFAULT_DOCKS.left!).id).toBe('left-outline')
  })

  it('keeps every panel exactly once', () => {
    let docks = DEFAULT_DOCKS
    docks = movePanel(docks, 'console', { group: 'right', edge: 'bottom' })
    docks = movePanel(docks, 'debug', { group: 'left-instances', edge: 'right' })
    docks = movePanel(docks, 'inspector', { area: 'bottom' })
    docks = movePanel(docks, 'problems', { area: 'left' })
    expect([...panelsOf(docks)].sort()).toEqual([...panelsOf(DEFAULT_DOCKS)].sort())
  })
})

describe('selectPanel and resizeSplit', () => {
  it('shows a tab in its group', () => {
    expect(findPanel(selectPanel(DEFAULT_DOCKS, 'debug'), 'debug')?.group.active).toBe('debug')
  })
  it('keeps shares adding up to 1', () => {
    const docks = resizeSplit(DEFAULT_DOCKS, 'left', [30, 90])
    expect((docks.left as Extract<DockNode, { type: 'split' }>).sizes).toEqual([0.25, 0.75])
    // A share per child, or nothing changes.
    expect(resizeSplit(DEFAULT_DOCKS, 'bottom', [1, 1])).toEqual(DEFAULT_DOCKS)
  })
})

describe('readDocks', () => {
  it('starts as the defaults from nothing', () => {
    expect(readDocks(null)).toBe(DEFAULT_DOCKS)
  })

  it('drops panels that are gone and repeats, and adds ones it lacks where they start', () => {
    const docks = readDocks({
      left: { type: 'tabs', id: 'a', tabs: ['console', 'gone', 'console'], active: 'gone' },
      right: {
        type: 'split',
        id: 'b',
        direction: 'column',
        sizes: [1, 'x'],
        children: [
          { type: 'tabs', id: 'c', tabs: ['inspector'], active: 'inspector' },
          { type: 'tabs', id: 'd', tabs: ['gone'], active: 'gone' },
        ],
      },
      bottom: { type: 'tabs', id: 'e', tabs: ['project', 'problems'], active: 'problems' },
    })
    // Outline and Instances start alone, so they go to their area; the rest join the console.
    expect(all(docks)).toEqual({
      left: ['console', 'outline', 'instances', 'tests', 'profiler', 'debug', 'network'],
      right: ['inspector'],
      bottom: ['project', 'problems'],
    })
    // A panel coming back doesn't take over its group.
    expect(findPanel(docks, 'problems')?.group.active).toBe('problems')
    expect(findPanel(docks, 'console')?.group.active).toBe('console')
  })

  it('keeps every panel in some group, whatever was stored', () => {
    for (const stored of [{}, { left: 5 }, { bottom: { type: 'split', children: [] } }]) {
      expect([...panelsOf(readDocks(stored))].sort()).toEqual([...panelsOf(DEFAULT_DOCKS)].sort())
      expect(groupsOf(readDocks(stored).left).length).toBeGreaterThan(0)
    }
  })
})
