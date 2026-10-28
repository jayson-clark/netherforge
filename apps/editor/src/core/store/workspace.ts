/**
 * The workspace: one store made of slices, each in its own module.
 *
 * - `project.ts`: the open project, what's known about it, notices, hot reload.
 * - `files.ts`: creating, copying, renaming and deleting files, and following references.
 * - `documents.ts`: open documents and their undo histories.
 * - `refactors.ts`: project-level undo: a rename, delete or copy as one transaction.
 * - `tabs.ts`: the tabs and focus requests.
 * - `views.ts`: per-document view state and selection, never undone.
 * - `validation.ts`: the project as format reads it (in a worker), and its problems.
 * - `packages.ts`: the packages the project depends on, `netherforge.lock`, copying from a package.
 * - `external.ts`: what a change on disk does to an open document.
 *
 * Invariants (see the editor-ui skill): format is the only writer of project
 * JSON, undo is per document (a refactor's transaction being one step in each
 * document it touched), and everything kept by path (documents, tabs, views)
 * moves together on a rename.
 */
import { createStore, type StoreApi } from 'zustand/vanilla'
import type { Backend } from '@/core/backend/types'
import type { ValidationClient } from '@/core/validation/client'
import { documentsSlice, type DocumentsActions, type DocumentsState } from './documents'
import { externalSlice, type ExternalActions } from './external'
import { filesSlice, type FilesActions } from './files'
import { packagesSlice, type PackagesActions, type PackagesState } from './packages'
import { projectSlice, type ProjectActions, type ProjectState } from './project'
import { refactorsSlice, type RefactorsActions, type RefactorsState } from './refactors'
import { tabsSlice, type TabsActions, type TabsState } from './tabs'
import { validationSlice, type ValidationActions, type ValidationState } from './validation'
import { liveViews, viewsSlice, type ViewsActions, type ViewsState } from './views'

export type WorkspaceState = ProjectState &
  DocumentsState &
  TabsState &
  ViewsState &
  ValidationState &
  PackagesState &
  RefactorsState

export type WorkspaceActions = ProjectActions &
  FilesActions &
  DocumentsActions &
  TabsActions &
  ViewsActions &
  ValidationActions &
  PackagesActions &
  ExternalActions &
  RefactorsActions

export type Workspace = WorkspaceState & WorkspaceActions
export type WorkspaceStore = StoreApi<Workspace>

export interface WorkspaceOptions {
  /** Makes the validation worker's client (a test's, to watch what it validates); a real worker by default. */
  validation?: () => ValidationClient
}

export function createWorkspace(backend: Backend, options: WorkspaceOptions = {}): WorkspaceStore {
  const store = createStore<Workspace>()((set, get) => {
    const args = { backend, get, set }
    return {
      ...projectSlice(args),
      ...filesSlice(args),
      ...documentsSlice(args),
      ...tabsSlice(args),
      ...viewsSlice(args),
      ...validationSlice(args, options.validation),
      ...packagesSlice(args),
      ...externalSlice(args),
      ...refactorsSlice(args),
    }
  })
  // A view goes once nothing has its path open: the one place views are dropped.
  store.subscribe((state, previous) => {
    if (state.docs === previous.docs && state.tabs === previous.tabs) return
    const views = liveViews(state)
    if (views !== state.views) store.setState({ views })
  })
  return store
}
