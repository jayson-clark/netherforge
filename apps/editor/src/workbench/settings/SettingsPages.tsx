/** The settings tab's pages in the outline dock: picking one shows it in the tab. */
import { usePane } from '@/state/useExpanded'
import { useApp, useWorkspace } from '@/state/providers'
import { SETTINGS_PATH } from '@/core/store/tabs'
import { Pane } from '@/ui/Pane'
import { Tree } from '@/ui/Tree'
import { SETTINGS_PAGES, settingsPageOf } from './pages'

export function SettingsPages() {
  const { workspace } = useApp()
  const pane = usePane('settings-pages')
  const page = settingsPageOf(useWorkspace((s) => s.views[SETTINGS_PATH]?.selection.at(-1)))
  return (
    <Pane title="Pages" {...pane}>
      <Tree
        label="Settings pages"
        nodes={SETTINGS_PAGES.map((it) => ({ id: it.id, label: it.title, icon: it.icon }))}
        selected={page}
        selectOnFocus
        onSelect={(id) => id && workspace.getState().openSettings(id)}
        isExpanded={() => false}
        onExpand={() => {}}
      />
    </Pane>
  )
}
