import {
  act,
  cleanup,
  createEvent,
  fireEvent,
  render,
  renderHook,
  screen,
  within,
} from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { MemoryBackend } from '@/testing/backend/memory'
import { DEFAULT_DOCKS, findPanel } from '@/core/store/docks'
import { InspectorTarget } from '@/editors/shared/docks'
import { AppProvider, type AppStores } from '@/state/providers'
import { openExampleApp } from '@/testing/workspace'
import { AreaDrops, DockAreaView } from './DockArea'
import { InspectorDock, useInspectorTarget } from './InspectorDock'

let backend: MemoryBackend
let app: AppStores

beforeEach(async () => {
  window.localStorage.clear()
  ;({ backend, app } = await openExampleApp())
  await app.run.getState().connect()
})

afterEach(cleanup)

describe('a dock area', () => {
  // A split's splitter measures its panels; jsdom has no layout to observe.
  beforeEach(() => {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe() {}
        unobserve() {}
        disconnect() {}
      },
    )
  })
  afterEach(() => vi.unstubAllGlobals())

  const show = (...areas: ('left' | 'right' | 'bottom')[]) =>
    render(
      <AppProvider app={app}>
        {areas.map((area) => (
          <DockAreaView key={area} area={area} />
        ))}
        <AreaDrops />
      </AppProvider>,
    )
  const groups = (area: string) =>
    within(screen.getByRole('region', { name: area })).getAllByRole('tablist')
  const names = (list: HTMLElement) =>
    within(list)
      .getAllByRole('tab')
      .map((it) => it.getAttribute('aria-label'))
  const selected = (list: HTMLElement) =>
    within(list)
      .getAllByRole('tab')
      .find((it) => it.getAttribute('aria-selected') === 'true')
      ?.getAttribute('aria-label')
  const tab = (name: string) => screen.getByRole('tab', { name })
  /** A drag event at a point: jsdom's events take no coordinates of their own. */
  const at = (
    kind: 'dragOver' | 'drop',
    element: Element,
    dataTransfer: object,
    clientX: number,
    clientY = 0,
  ) => {
    const event = createEvent[kind](element, { dataTransfer })
    Object.defineProperties(event, { clientX: { value: clientX }, clientY: { value: clientY } })
    fireEvent(element, event)
  }
  /** jsdom lays nothing out (every box is 0 by 0, measured as 1 by 1): a point is a share of the box. */
  const drag = (name: string) => {
    const dataTransfer = { setData() {}, effectAllowed: '', dropEffect: '' }
    fireEvent.dragStart(tab(name), { dataTransfer })
    return dataTransfer
  }

  it('starts with Project, Problems and the Console side by side, the other output panels behind the Console', () => {
    show('bottom', 'left')
    const [project, problems, output] = groups('Bottom dock')
    expect(names(project!)).toEqual(['Project'])
    expect(names(problems!)).toEqual(['Problems'])
    expect(names(output!)).toEqual(['Console', 'Tests', 'Profiler', 'Debug', 'Network'])
    expect(selected(output!)).toBe('Console')
    expect(groups('Left dock').map(names)).toEqual([['Outline'], ['Instances']])
  })

  it('shows the tab the layout says, and remembers a switch', () => {
    app.layout.getState().reveal('profiler')
    show('bottom')
    expect(selected(groups('Bottom dock')[2]!)).toBe('Profiler')
    fireEvent.click(tab('Debug'))
    expect(findPanel(app.layout.getState().docks, 'debug')?.group.active).toBe('debug')
    expect(selected(groups('Bottom dock')[2]!)).toBe('Debug')
  })

  it("counts the console's lines on its tab", () => {
    show('bottom')
    expect(tab('Console').textContent).toBe('Console')
    act(() => {
      backend.testServerOutput('[12:00:00 INFO]: one')
      backend.testServerOutput('[12:00:00 INFO]: two')
    })
    expect(tab('Console').textContent).toBe('Console2')
  })

  it("drags a tab into another group's tabs", () => {
    show('bottom', 'right')
    const dataTransfer = drag('Console')
    at('dragOver', tab('Inspector'), dataTransfer, -1)
    fireEvent.drop(tab('Inspector'), { dataTransfer })
    expect(findPanel(app.layout.getState().docks, 'console')?.group.tabs).toEqual([
      'console',
      'inspector',
    ])
    expect(names(groups('Right dock')[0]!)).toEqual(['Console', 'Inspector'])
  })

  it('drags a tab onto the edge of a group to split it', () => {
    show('bottom')
    const dataTransfer = drag('Tests')
    const [, , output] = screen.getAllByRole('tabpanel')
    const zone = output!.querySelector('[data-drop-zone]')!
    at('dragOver', zone, dataTransfer, 0.5, 0.95)
    at('drop', zone, dataTransfer, 0.5, 0.95)
    const bottom = app.layout.getState().docks.bottom!
    expect(bottom.type === 'split' && bottom.children[2]).toMatchObject({
      type: 'split',
      direction: 'column',
    })
    expect(groups('Bottom dock').map(names)).toEqual([
      ['Project'],
      ['Problems'],
      ['Console', 'Profiler', 'Debug', 'Network'],
      ['Tests'],
    ])
  })

  it('offers a dock that is hidden or empty while a tab is dragged', () => {
    app.layout.getState().set({ outlineOpen: false })
    show('bottom')
    expect(screen.queryByText('Left dock')).toBeNull()
    const dataTransfer = drag('Problems')
    const strip = screen.getByText('Left dock')
    fireEvent.dragOver(strip, { dataTransfer })
    fireEvent.drop(strip, { dataTransfer })
    expect(app.layout.getState().outlineOpen).toBe(true)
    expect(findPanel(app.layout.getState().docks, 'problems')?.area).toBe('left')
    expect(screen.queryByText('Left dock')).toBeNull()
  })

  it('hides its dock from its top right group', () => {
    show('bottom')
    const [project, problems, output] = groups('Bottom dock')
    expect(project!.parentElement!.querySelector('button')).toBeNull()
    expect(problems!.parentElement!.querySelector('button')).toBeNull()
    fireEvent.click(
      within(output!.parentElement!).getByRole('button', { name: 'Hide the bottom dock' }),
    )
    expect(app.layout.getState().bottomOpen).toBe(false)
    expect(app.layout.getState().docks).toBe(DEFAULT_DOCKS)
  })
})

describe('the inspector', () => {
  it('keeps one target while the panel comes and goes, and disables it for a read-only document', async () => {
    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <AppProvider app={app}>{children}</AppProvider>
    )
    const { result } = renderHook(() => useInspectorTarget(), { wrapper })
    const target = result.current
    const panel = render(
      <AppProvider app={app}>
        <InspectorTarget.Provider value={target}>
          <InspectorDock />
        </InspectorTarget.Provider>
      </AppProvider>,
    )
    const dock = screen.getByRole('region', { name: 'Inspector dock' })
    expect(dock.contains(target)).toBe(true)
    panel.unmount()
    expect(target.isConnected).toBe(false)
    expect(result.current).toBe(target)

    await act(() => app.workspace.getState().openFile('centities/tower/centity.json'))
    expect(target.disabled).toBe(false)
    act(() => app.workspace.getState().setReadOnly('centities/tower', 'from a package'))
    expect(target.disabled).toBe(true)
  })
})
