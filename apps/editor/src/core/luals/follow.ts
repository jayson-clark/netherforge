/**
 * Keeps `.netherforge/luals/` (`@netherforge/project-support`'s `luals.ts`) in step with the project, as
 * it is in the editor: unsaved edits included, so a node added a moment ago
 * completes at once.
 */
import {
  loadApiStubs,
  lualsFiles,
  packageSources as sourcesOf,
  type ApiStubs,
  type ModelReader,
  type PackageSource,
} from '@netherforge/project-support'
import type { Backend } from '@/core/backend/types'
import { AGENT_FILES } from '@/core/format'
import { modelOf } from '@/core/store/documents'
import type { Workspace, WorkspaceStore } from '@/core/store/workspace'
import { directDependencies } from '@/core/store/packages'
import { projectInput } from '@/core/store/validation'
import type { WorkspaceFileStore } from '@/core/store/workspaceFile'

/** Main files as they are now: the open document's model (unsaved edits included), else disk. */
export function modelReader(state: Pick<Workspace, 'docs' | 'diskTexts'>): ModelReader {
  return <T>(path: string): T | null => {
    const doc = state.docs[path]
    const model = modelOf<T>(doc)
    if (model) return model
    const text = doc?.text ?? state.diskTexts[path]
    try {
      return text ? (JSON.parse(text) as T) : null
    } catch {
      return null
    }
  }
}

/** The packages the project depends on directly, as LuaLS is told about them: their outlines and files as read. */
export function packageSources(
  state: Pick<Workspace, 'outline' | 'packages' | 'docs' | 'diskTexts'>,
): PackageSource[] {
  return sourcesOf(state.outline, state.packages.folders, directDependencies(state))
}

/**
 * The files as the store has the project now, by their path under `.netherforge/luals/`: with
 * [api], the API's stubs too, marked for what the project's target and `netherforge.json`
 * don't allow.
 */
export function lualsFilesOf(
  state: Workspace,
  api?: ApiStubs,
  servers: Record<string, string> | null = null,
) {
  return lualsFiles(
    api ?? { files: {}, gates: [] },
    state.outline,
    modelReader(state),
    packageSources(state),
    { scripts: projectInput(state), servers },
  )
}

/**
 * Keeps `.netherforge/luals/` in step with the project: rewritten (only
 * when its text changes) a moment after the outline or a document changes
 * (`netherforge.json` included, whose target and `requires` decide what the
 * API's stubs mark), or, with [workspaceFile], the workspace's network changes.
 */
export function followLualsStubs(
  workspace: WorkspaceStore,
  backend: Backend,
  delay = 300,
  workspaceFile?: WorkspaceFileStore,
) {
  let written = new Map<string, string>()
  let root: string | null = null
  let timer: ReturnType<typeof setTimeout> | null = null
  const write = async () => {
    timer = null
    const state = workspace.getState()
    if (!state.project || !state.outline) return
    const servers = workspaceFile?.getState().file?.network?.servers ?? null
    const files = lualsFilesOf(state, await loadApiStubs(), servers)
    for (const [file, text] of Object.entries(files)) {
      const path = `${AGENT_FILES.luals}/${file}`
      if (written.get(path) === text) continue
      try {
        await backend.writeText(path, text)
        written.set(path, text)
      } catch (error) {
        console.warn(`Couldn't write ${path}`, error)
      }
    }
    // A package no longer depended on, or a module it no longer exports: its stubs go.
    const wanted = new Set(Object.keys(files).map((file) => `${AGENT_FILES.luals}/${file}`))
    for (const path of [...written.keys()]) {
      if (wanted.has(path)) continue
      try {
        await backend.deletePath(path)
        written.delete(path)
      } catch (error) {
        console.warn(`Couldn't delete ${path}`, error)
      }
    }
  }
  const soon = () => {
    if (timer) clearTimeout(timer)
    // Loading the API's stubs can fail (a chunk that didn't load; a test's environment gone):
    // said, not thrown, and tried again on the next change.
    timer = setTimeout(
      () =>
        void write().catch((error: unknown) =>
          console.warn("Couldn't write lua-language-server's files", error),
        ),
      delay,
    )
  }
  const stopFile = workspaceFile?.subscribe((state, previous) => {
    if (state.file?.network !== previous.file?.network) soon()
  })
  const stop = workspace.subscribe((state, previous) => {
    if (state.project?.root !== root) {
      root = state.project?.root ?? null
      written = new Map()
    } else if (
      state.outline === previous.outline &&
      state.packages === previous.packages &&
      state.docs === previous.docs &&
      state.diskTexts === previous.diskTexts
    ) {
      return
    }
    soon()
  })
  return () => {
    stop()
    stopFile?.()
  }
}
