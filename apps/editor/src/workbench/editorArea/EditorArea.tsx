/**
 * Tabs and the active tab's view (the registry's, for its type), with the
 * banners for files that changed or vanished on disk while they had unsaved
 * edits, and for read-only ones (a dependency's), whose view is disabled.
 */
import { Fragment, lazy, Suspense, useState } from 'react'
import { TabPanel, Tabs } from 'react-aria-components'
import { basename } from '@/core/paths'
import { splitPackagePath } from '@/core/store/packages'
import { readOnlyReason } from '@/core/store/project'
import { useApp, useWorkspace } from '@/state/providers'
import { DocumentNamespace } from '@/state/useResourcePacks'
import { Button } from '@/ui/Button'
import { Banner } from '@/ui/Callout'
import { Modal } from '@/ui/dialogs'
import { Icon } from '@/ui/Icon'
import { Keys } from '@/ui/Keys'
import { Spacer } from '@/ui/layout'
import { Empty } from '@/ui/text'
import styles from './EditorArea.module.css'
import { TAB_VIEWS } from '@/workbench/contributions'
import { TabStrip } from './TabStrip'

// Monaco is a large part of the bundle: load it with the first diff.
const DiffView = lazy(() =>
  import('@/editors/script/CodeEditor').then((m) => ({ default: m.DiffView })),
)

/** The empty area's reminders: what, and its keys as the menus write them. */
const HINTS: [string, string[]][] = [
  ['Go to a resource or file', ['Mod+P']],
  ['Save · save all', ['Mod+S', 'Mod+Shift+S']],
  ['Close tab', ['Mod+W']],
  ['Outline · inspector · bottom dock', ['Mod+B', 'Mod+Alt+B', 'Mod+J']],
  ['Next · previous tab', ['Ctrl+Tab', 'Ctrl+Shift+Tab']],
  ['Zoom in · out · actual size', ['Mod+=', 'Mod+-', 'Mod+0']],
]

export function EditorArea() {
  const { workspace } = useApp()
  const tabs = useWorkspace((s) => s.tabs)
  const activeTab = useWorkspace((s) => s.activeTab)
  const active = tabs.find((it) => it.id === activeTab)
  const view = active ? TAB_VIEWS[active.type] : null
  const View = view?.view
  const readOnly = useWorkspace((s) => (active ? readOnlyReason(s.readOnly, active.path) : null))

  return (
    <Tabs
      className={styles.area}
      // No tab is '' (null would have react-aria pick the first).
      selectedKey={activeTab ?? ''}
      onSelectionChange={(id) => workspace.getState().activate(String(id))}
    >
      {tabs.length > 0 && <TabStrip tabs={tabs} />}
      {active && view && View ? (
        <TabPanel id={active.id} className={styles.content}>
          {view.document && <DocBanners path={active.path} />}
          {readOnly !== null && <ReadOnlyBanner reason={readOnly} />}
          {/* A disabled fieldset disables every control inside, whatever the view is made of. */}
          <fieldset className={styles.view} disabled={readOnly !== null}>
            <Suspense fallback={<Empty>Loading editor…</Empty>}>
              {/* A package's document names things in its own namespace; previews resolve them there. */}
              <DocumentNamespace.Provider value={splitPackagePath(active.path)[0]}>
                <View key={active.id} path={active.path} />
              </DocumentNamespace.Provider>
            </Suspense>
          </fieldset>
        </TabPanel>
      ) : (
        <div className={styles.empty}>
          <p>Open a resource from the Project explorer below.</p>
          <div className={styles.shortcuts}>
            {HINTS.map(([label, shortcuts]) => (
              <Fragment key={label}>
                <span>{label}</span>
                <span className={styles.keys}>
                  {shortcuts.map((shortcut, index) => (
                    <Fragment key={shortcut}>
                      {index > 0 && <span aria-hidden>·</span>}
                      <Keys shortcut={shortcut} caps />
                    </Fragment>
                  ))}
                </span>
              </Fragment>
            ))}
          </div>
        </div>
      )}
    </Tabs>
  )
}

function ReadOnlyBanner({ reason }: { reason: string }) {
  return (
    <Banner aria-label="Read-only">
      <Icon name="lock" />
      <span>Read-only: {reason}.</span>
    </Banner>
  )
}

export function DocBanners({ path }: { path: string }) {
  const { workspace } = useApp()
  const conflict = useWorkspace((s) => s.docs[path]?.conflict ?? null)
  const deleted = useWorkspace((s) => s.docs[path]?.deleted ?? false)
  const [diff, setDiff] = useState(false)
  const ws = () => workspace.getState()

  if (conflict) {
    return (
      <>
        <Banner aria-label="File changed on disk">
          <Icon name="warning" />
          <span>
            <strong>{basename(path)}</strong> changed on disk, and you have unsaved changes.
          </span>
          <Spacer />
          <Button size="small" onClick={() => ws().resolveConflict(path, 'mine')}>
            Keep mine
          </Button>
          <Button size="small" onClick={() => ws().resolveConflict(path, 'theirs')}>
            Take theirs
          </Button>
          <Button size="small" onClick={() => setDiff(true)}>
            Diff
          </Button>
        </Banner>
        {diff && (
          <Modal
            title={`${basename(path)}: disk (left) vs. yours (right)`}
            wide
            onClose={() => setDiff(false)}
            footer={
              <>
                <Spacer />
                <Button
                  onClick={() => {
                    setDiff(false)
                    ws().resolveConflict(path, 'mine')
                  }}
                >
                  Keep mine
                </Button>
                <Button
                  variant="primary"
                  onClick={() => {
                    setDiff(false)
                    ws().resolveConflict(path, 'theirs')
                  }}
                >
                  Take theirs
                </Button>
              </>
            }
          >
            <Suspense fallback={<Empty>Loading diff…</Empty>}>
              <DiffView path={path} theirs={conflict.theirs} mine={ws().canonicalMine(path)} />
            </Suspense>
          </Modal>
        )}
      </>
    )
  }
  if (deleted) {
    return (
      <Banner aria-label="File deleted on disk">
        <Icon name="warning" />
        <span>
          <strong>{basename(path)}</strong> was deleted on disk. Save to recreate it, or close it.
        </span>
        <Spacer />
        <Button size="small" onClick={() => void ws().save(path)}>
          Save
        </Button>
        <Button
          size="small"
          onClick={() => {
            for (const tab of ws().tabs.filter((it) => it.path === path)) ws().closeTab(tab.id)
          }}
        >
          Close
        </Button>
      </Banner>
    )
  }
  return null
}
