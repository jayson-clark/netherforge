/**
 * The backend contract (`contract.json`, see `contractRunner.ts`) on
 * `MemoryBackend`. The same cases run on `TauriBackend` over the real Rust
 * commands in `contract.tauri.node.test.ts`, so what the UI's tests are told
 * here is what the app does. Also here: every `Backend` member is either in
 * a case or excluded below, with why.
 */
import { describe, expect, it } from 'vitest'
import { bytesToBase64, MemoryBackend, type MemoryGitRepo } from '@/testing/backend/memory'
import { runCase, suite, type ContractCase, type ContractWorld } from './contractRunner'
import { TauriBackend } from './tauri'
import type { Backend, MinecraftInstall, TestReport } from './types'

/**
 * What the contract can't check, by member, and why. Each is still tested:
 * the Tauri mapping in `tauri.test.ts` (plugins' IPC mocked), the memory
 * backend's fakes by the UI tests that use them.
 */
export const EXCLUDED: Partial<Record<keyof Backend, string>> = {
  openExternal: 'opens the user’s browser (opener plugin); nothing comes back to check',
  windowAction: 'acts on the native window, which the mock runtime and the memory backend lack',
  setAppMenu: 'builds the macOS menu bar (core menu API); tauriMenu.test.ts covers it',
  onMenuAction: 'fired by the native menu bar, which only the app has',
  setZoom: 'the webview’s own zoom; nothing comes back to check',
  checkForUpdate: 'asks the release endpoint over the network (updater plugin)',
  installUpdate: 'downloads, installs and relaunches the editor (updater and process plugins)',
  pickFolder: 'a native dialog (dialog plugin) that waits for the user',
  pickJar: 'a native dialog (dialog plugin) that waits for the user',
  openLauncher: 'starts the player’s Minecraft launcher, a program outside the editor',
  onLualsMessage:
    'the memory backend has no lua-language-server; luals/mod.rs and luals.node.test.ts drive a real one',
  onLualsExit:
    'the memory backend has no lua-language-server; luals/mod.rs tests the exit with a stand-in',
  onVideoProgress:
    'reported while ffmpeg converts, which neither contract run has; video/mod.rs tests it with a stand-in ffmpeg',
}

// The project's folder is named as the Rust side's case names it, so a workspace file names it alike.
const ROOT = '/memory/project'
const VARS = {
  ROOT,
  GIT: 'file:///memory/git',
  CLIENT_JAR: '/memory/client.jar',
  PORT: 25599,
  MCP_PORT: 47699,
}

/**
 * The contract's repositories as `MemoryBackend` serves them: each one's
 * files as one commit (an id made up per repository: the memory backend
 * hashes nothing), main and v1 naming it.
 */
function repositories(): Record<string, MemoryGitRepo> {
  return Object.fromEntries(
    Object.entries(suite.repositories).map(([name, files], i) => {
      const commit = (i + 1).toString(16).padStart(40, 'c')
      return [
        `${VARS.GIT}/${name}`,
        { refs: { HEAD: commit, main: commit, v1: commit }, commits: { [commit]: { ...files } } },
      ]
    }),
  )
}

/** The case's world in memory: the setup as the memory backend's options. */
function memoryWorld(testCase: ContractCase): ContractWorld {
  const setup = testCase.setup ?? {}
  const parent = ROOT.slice(0, ROOT.lastIndexOf('/'))
  const backend = new MemoryBackend({
    projects: {
      [ROOT]: { ...suite.project },
      ...Object.fromEntries(
        Object.entries(suite.packages).map(([name, files]) => [`${parent}/${name}`, { ...files }]),
      ),
    },
    gitRepos: repositories(),
    // As the app starts: nothing trusted yet.
    trusted: [],
    gameData: setup.gameData as never,
    clientAssets: Object.fromEntries(
      Object.entries(setup.client?.files ?? {}).map(([path, text]) => [
        path,
        `data:application/octet-stream;base64,${bytesToBase64(new TextEncoder().encode(text))}`,
      ]),
    ),
    installs: (setup.installs?.found ?? []) as unknown as MinecraftInstall[],
    serverFiles: setup.serverFiles,
    workspaces: setup.workspace ? { [parent]: setup.workspace } : {},
  })
  if (setup.testReport) backend.testTestReport(setup.testReport as unknown as TestReport)
  return {
    backend,
    vars: VARS,
    async outside(change) {
      if (change.outside === 'write') backend.testWrite(change.path, change.text)
      else if (change.outside === 'delete') backend.testDelete(change.path)
      else backend.testRename(change.from, change.to)
    },
    async agent(message) {
      const { method, params } = message as { method: string; params?: unknown }
      return (await backend.testMcpCall(method, params)) as never
    },
    async fetch(url) {
      // The memory backend serves files as data: URLs; anything else is nowhere.
      if (!url.startsWith('data:')) return { status: 404, text: '' }
      const response = await fetch(url)
      return { status: response.status, text: await response.text() }
    },
  }
}

describe('MemoryBackend keeps the backend contract', () => {
  for (const testCase of suite.cases) {
    it(testCase.name, async () => {
      const world = memoryWorld(testCase)
      if (testCase.open !== false) await world.backend.openProject(ROOT)
      await runCase(world, testCase)
    })
  }
})

describe('the contract covers the Backend', () => {
  // Every member, from the class the app runs (which TypeScript holds to `Backend`).
  const members = Object.getOwnPropertyNames(TauriBackend.prototype).filter(
    (name) => name !== 'constructor' && name !== 'init',
  )
  const used = new Set(
    suite.cases.flatMap((testCase) =>
      testCase.steps.flatMap((step) =>
        'call' in step
          ? [step.call]
          : 'listen' in step
            ? [step.listen]
            : 'agent' in step
              ? ['onMcpMessage', 'mcpSend']
              : [],
      ),
    ),
  )

  it('names only Backend members', () => {
    expect([...used].filter((name) => !members.includes(name))).toEqual([])
    expect(Object.keys(EXCLUDED).filter((name) => !members.includes(name))).toEqual([])
  })

  it('has every member in a case or excluded with a reason, not both', () => {
    expect(members.filter((name) => !used.has(name) && !(name in EXCLUDED))).toEqual([])
    expect(members.filter((name) => used.has(name) && name in EXCLUDED)).toEqual([])
  })
})
