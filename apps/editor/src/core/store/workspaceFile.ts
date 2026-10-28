/**
 * The open project's workspace (`netherforge-workspace.json` above it; docs/format/workspace.md):
 * its members, its file as format reads it (and what's wrong with it), whether its dev network is
 * on here, and the changes the editor makes to it. The backend finds the workspace when a project
 * opens (`ProjectInfo.workspace`); this follows the open project and re-reads the file. Focusing
 * another member is opening that project: the backend keeps a running workspace network up. The
 * other members are checked too (`workspaceCheck.ts`), for Problems.
 */
import { createStore, type StoreApi } from 'zustand/vanilla'
import type { Problem } from '@netherforge/format/types'
import type { Backend, WorkspaceInfo } from '@/core/backend/types'
import {
  WORKSPACE_FILE,
  canonicalizeModel,
  checkNetworkUses,
  checkWorkspace,
  type WorkspaceFile,
  type WorkspaceNetwork,
} from '@/core/format'
import { schemaFiles } from '@netherforge/project-support'
import { projectInput } from './validation'
import { memberCheckRequest, type MemberProblems } from './workspaceCheck'
import type { WorkspaceStore } from './workspace'

export interface WorkspaceFileState {
  /** The open project's workspace, as the backend reads it; null when it isn't in one. */
  info: WorkspaceInfo | null
  /** The file as format reads it; null when it doesn't parse (or there's no workspace). */
  file: WorkspaceFile | null
  problems: Problem[]
  /** Whether Start runs the workspace's dev network, on this computer. */
  networkEnabled: boolean
  /** Why the last change was refused. */
  error: string | null
  /** Every other member's problems, in the workspace's order, as last checked. */
  others: MemberProblems[]
  /** Whether [checkOthers] is running. */
  checking: boolean
  /** What the workspace says is wrong with the open project, as it is in the editor: players sent to servers its network doesn't have. */
  openProblems: Problem[]
}

export interface WorkspaceFileActions {
  /** Reads the workspace (its members, its file) again. */
  refresh(): Promise<void>
  /** Trusts the workspace, and so every project in it (or stops trusting it). */
  trust(trusted: boolean): Promise<void>
  setNetworkEnabled(enabled: boolean): Promise<void>
  /** Writes the file with [network] as its dev network (null for none), canonically. */
  saveNetwork(network: WorkspaceNetwork | null): Promise<void>
  /** Checks every member but the open project, from disk; [others] when it's done. */
  checkOthers(): Promise<void>
}

export type WorkspaceFileModel = WorkspaceFileState & WorkspaceFileActions
export type WorkspaceFileStore = StoreApi<WorkspaceFileModel>

const NONE: WorkspaceFileState = {
  info: null,
  file: null,
  problems: [],
  networkEnabled: false,
  error: null,
  others: [],
  checking: false,
  openProblems: [],
}

/** How long after the last save the other members are checked again. */
export const RECHECK_DELAY_MS = 1000

/** How long after the last change the open project's network names are checked again. */
const OPEN_CHECK_DELAY_MS = 300

/** The Lua scripts of [files] (texts by path, null for what isn't read), as `checkNetworkUses` takes them. */
function scriptsOf(files: Record<string, string | null>): Record<string, string> {
  const scripts: Record<string, string> = {}
  for (const [path, text] of Object.entries(files))
    if (text != null && path.endsWith('.lua')) scripts[path] = text
  return scripts
}

/** [files]' problems with the network names their scripts use, for [file]'s network (none without one). */
export function networkProblems(
  file: WorkspaceFile | null,
  files: Record<string, string | null>,
): Problem[] {
  const servers = file?.network?.servers
  return servers ? checkNetworkUses(scriptsOf(files), Object.keys(servers)) : []
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

export function createWorkspaceFile(
  backend: Backend,
  workspace: WorkspaceStore,
): WorkspaceFileStore {
  const store = createStore<WorkspaceFileModel>()((set, get) => {
    /** Reads [info]'s file through format, against its members. */
    const read = async (info: WorkspaceInfo) => {
      const text = await backend.workspaceRead()
      const result = checkWorkspace(
        text,
        info.members.map((it) => it.path),
      )
      set({ info, file: result.workspace ?? null, problems: result.problems })
    }

    /** Bumped by every [checkOthers] and by a refresh, so a slower earlier check doesn't land over it. */
    let checks = 0

    return {
      ...NONE,

      async refresh() {
        checks += 1
        if (!workspace.getState().project?.workspace) {
          set(NONE)
          return
        }
        try {
          const info = await backend.workspaceInfo()
          if (!info) {
            set(NONE)
            return
          }
          const networkEnabled = await backend.workspaceNetworkEnabled()
          set({ networkEnabled, error: null })
          await read(info)
          // Where the file's $schema points, as a project's .netherforge/schema/ is written on open.
          const schema = schemaFiles['workspace.schema.json']
          if (schema) void backend.workspaceWriteSchema(schema).catch(() => {})
          void get().checkOthers()
        } catch (error) {
          set({ error: errorText(error) })
        }
      },

      async trust(trusted) {
        try {
          // The project's trust follows the workspace's.
          await workspace.getState().trustWorkspace(trusted)
          set({ info: workspace.getState().project?.workspace ?? null, error: null })
        } catch (error) {
          set({ error: errorText(error) })
        }
      },

      async setNetworkEnabled(enabled) {
        try {
          await backend.workspaceSetNetworkEnabled(enabled)
          set({ networkEnabled: enabled, error: null })
        } catch (error) {
          set({ error: errorText(error) })
        }
      },

      async checkOthers() {
        const info = get().info
        const open = workspace.getState().project?.root
        if (!info) return
        const mine = ++checks
        set({ checking: true })
        const others: MemberProblems[] = []
        for (const member of info.members) {
          if (member.root === open) continue
          try {
            const request = await memberCheckRequest(backend, info, member)
            const { outline } = await workspace.getState().checkProject(request)
            const network = networkProblems(get().file, request.files)
            others.push({ member, problems: [...outline.problems, ...network], error: null })
          } catch (error) {
            others.push({ member, problems: [], error: errorText(error) })
          }
          if (mine !== checks) return
        }
        set({ others, checking: false })
      },

      async saveNetwork(network) {
        const file = get().file
        if (!file) {
          set({ error: `${WORKSPACE_FILE} doesn't read: fix it before changing its network` })
          return
        }
        const next: WorkspaceFile = { ...file, network: network ?? undefined }
        const written = canonicalizeModel('workspace', WORKSPACE_FILE, next)
        if (!written.text) {
          set({ error: written.problems[0]?.message ?? `Couldn't write ${WORKSPACE_FILE}` })
          return
        }
        try {
          const info = await backend.workspaceWrite(written.text)
          set({ error: null })
          await read(info)
        } catch (error) {
          set({ error: errorText(error) })
        }
      },
    }
  })

  // Each project has its own: re-read whenever another opens. A save (to the open project, or to a
  // package of the workspace edited in place) may change what the others see: check them again
  // once saving settles.
  let root: string | null = null
  let disk: object | null = null
  let timer: ReturnType<typeof setTimeout> | null = null
  // The open project's network names, as it is in the editor (unsaved edits too), a moment after a change.
  let openTimer: ReturnType<typeof setTimeout> | null = null
  const checkOpen = () => {
    if (openTimer !== null) clearTimeout(openTimer)
    openTimer = setTimeout(() => {
      openTimer = null
      const state = workspace.getState()
      const openProblems = state.project
        ? networkProblems(store.getState().file, projectInput(state))
        : []
      const before = store.getState().openProblems
      if (JSON.stringify(before) !== JSON.stringify(openProblems)) store.setState({ openProblems })
    }, OPEN_CHECK_DELAY_MS)
  }
  store.subscribe((state, previous) => {
    if (state.file !== previous.file) checkOpen()
  })
  workspace.subscribe((state, previous) => {
    if (state.docs !== previous.docs || state.diskTexts !== previous.diskTexts) checkOpen()
    const next = state.project?.root ?? null
    if (next !== root) {
      root = next
      disk = state.diskTexts
      if (timer !== null) clearTimeout(timer)
      timer = null
      void store.getState().refresh()
      return
    }
    if (state.diskTexts === disk) return
    disk = state.diskTexts
    if (!store.getState().info) return
    if (timer !== null) clearTimeout(timer)
    timer = setTimeout(() => {
      timer = null
      void store.getState().checkOthers()
    }, RECHECK_DELAY_MS)
  })
  return store
}
