/**
 * What the workbench contributes beside the editors' kinds and file tabs
 * (`editors/registry.tsx`): the settings tab and the docks' panels.
 * Every tab type and every panel is listed once, here or there.
 */
import { lazy } from 'react'
import { useRun } from '@/state/providers'
import type { LayoutStore } from '@/core/store/layout'
import type { TabType } from '@/core/store/tabs'
import type { DockPanel, TabView } from '@/editors/contributions'
import { EDITOR_TABS } from '@/editors/registry'
import { Count } from '@/ui/Badge'
import { Explorer } from './explorer/Explorer'
import { InspectorDock } from './docks/InspectorDock'
import { OutlineDock } from './outline/OutlineDock'
import { ConsolePanel } from './panels/Console'
import { DebugBadge, DebugPanel } from './panels/Debug'
import { InstancesPanel } from './panels/Instances'
import { NetworkPanel } from './panels/Network'
import { ProfilerPanel } from './panels/Profiler'
import { ProblemsPanel, useProblemCounts } from './panels/Problems'
import { TestsPanel, useUnpassedCount } from './panels/Tests'
import { SettingsPages } from './settings/SettingsPages'

/** What every tab type shows. A tab type without an entry is a type error. */
export const TAB_VIEWS: Record<TabType, TabView> = {
  ...EDITOR_TABS,
  settings: {
    icon: 'gear',
    label: () => 'Settings',
    view: lazy(() =>
      import('@/workbench/settings/SettingsScreen').then((m) => ({ default: m.SettingsScreen })),
    ),
    document: false,
    resource: false,
    outline: { subtitle: 'Editor', view: SettingsPages },
  },
}

function ProblemCounts() {
  const { errors, warnings } = useProblemCounts()
  return (
    <>
      {errors > 0 && <Count value={errors} tone="error" />}
      {warnings > 0 && <Count value={warnings} tone="warning" />}
    </>
  )
}

function UnpassedTests() {
  const count = useUnpassedCount()
  return count > 0 ? <Count value={count} tone="error" /> : null
}

function ConsoleLines() {
  const lines = useRun((s) => s.console.size)
  return lines > 0 ? <Count value={lines} /> : null
}

/**
 * The docks' panels, in the View menu's order. Where each starts is
 * `DEFAULT_DOCKS` (`core/store/docks.ts`), which names every one.
 */
export const DOCK_PANELS: DockPanel[] = [
  {
    id: 'outline',
    title: 'Outline',
    view: OutlineDock,
    menu: { label: 'Outline', hint: "The active resource's parts and files", icon: 'list' },
  },
  {
    id: 'inspector',
    title: 'Inspector',
    view: InspectorDock,
    menu: { label: 'Inspector', hint: "The selection's fields", icon: 'edit' },
  },
  {
    id: 'project',
    title: 'Project',
    view: Explorer,
    menu: { label: 'Project Explorer', hint: 'Every resource in the project', icon: 'grid' },
  },
  {
    id: 'instances',
    title: 'Instances',
    view: InstancesPanel,
    menu: { label: 'Instances', hint: 'Centities alive on the dev server', icon: 'list' },
  },
  {
    id: 'problems',
    title: 'Problems',
    view: ProblemsPanel,
    badge: ProblemCounts,
    menu: { label: 'Problems', hint: 'What validation found in the project', icon: 'warning' },
  },
  {
    id: 'console',
    title: 'Console',
    view: ConsolePanel,
    badge: ConsoleLines,
    menu: { label: 'Console', hint: "The dev server's log and commands", icon: 'code' },
  },
  {
    id: 'network',
    title: 'Network',
    view: NetworkPanel,
    menu: {
      label: 'Network',
      hint: "The dev network: a proxy and other servers beside the project's, and their consoles",
      icon: 'network',
    },
  },
  {
    id: 'tests',
    title: 'Tests',
    view: TestsPanel,
    badge: UnpassedTests,
    menu: {
      label: 'Tests',
      hint: "The project's script tests (*_test.lua) and how the last run went",
      icon: 'check',
    },
  },
  {
    id: 'profiler',
    title: 'Profiler',
    view: ProfilerPanel,
    menu: {
      label: 'Profiler',
      hint: "Where the dev server's ticks go: each step, script and handler",
      icon: 'chart',
    },
  },
  {
    id: 'debug',
    title: 'Debug',
    view: DebugPanel,
    badge: DebugBadge,
    menu: {
      label: 'Debug',
      hint: 'The debugger: where the dev server is paused, its call stack and variables',
      icon: 'breakpoint',
    },
  },
]

const BY_ID = new Map(DOCK_PANELS.map((it) => [it.id, it]))

/** The panel with [id], if there is one. */
export const panelById = (id: string): DockPanel | undefined => BY_ID.get(id)

/** Shows panel [id] wherever it's docked: its tab, and its dock if hidden. */
export function showPanel(layout: LayoutStore, id: string) {
  layout.getState().reveal(id)
}
