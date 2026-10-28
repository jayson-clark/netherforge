import { ContextMenuHost } from '@/ui/ContextMenu'
import { DialogHost } from '@/ui/dialogs'
import { UpdateBanner } from '@/workbench/UpdateBanner'
import { Welcome } from '@/workbench/welcome/Welcome'
import { useAppMenus } from '@/workbench/menus/useAppMenus'
import { Workbench } from '@/workbench/Workbench'
import { useApp, useWorkspace } from '@/state/providers'

export function App() {
  const project = useWorkspace((s) => s.project)
  useAppMenus(useApp())
  return (
    <>
      {project ? <Workbench key={project.root} /> : <Welcome />}
      <UpdateBanner />
      <DialogHost />
      <ContextMenuHost />
    </>
  )
}
