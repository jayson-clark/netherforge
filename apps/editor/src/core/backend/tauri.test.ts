/**
 * `TauriBackend`'s members the backend contract can't run (`EXCLUDED` in
 * `contract.test.ts`: Tauri's plugins and the native window), against
 * `@tauri-apps/api`'s IPC, mocked: what each asks Tauri for, and what it
 * makes of the answer. The commands themselves are the contract's.
 */
import type { Channel } from '@tauri-apps/api/core'
import { clearMocks, mockIPC, mockWindows } from '@tauri-apps/api/mocks'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { nfAssetUrl, nfPackageUrl, nfProjectUrl, TauriBackend, toBackendError } from './tauri'
import { BackendError } from './types'

let calls: { cmd: string; args: Record<string, unknown> }[]
let answers: Record<string, (args: Record<string, unknown>) => unknown>

beforeEach(() => {
  calls = []
  answers = {
    app_info: () => ({ version: '1.0.0', minecraftVersions: ['26.3'], os: 'macos' }),
  }
  mockWindows('main')
  mockIPC((cmd, args) => {
    const given = (args ?? {}) as Record<string, unknown>
    calls.push({ cmd, args: given })
    return answers[cmd]?.(given) ?? null
  })
})

afterEach(() => clearMocks())

const sent = (cmd: string) => calls.filter((c) => c.cmd === cmd).map((c) => c.args)

describe('TauriBackend', () => {
  it('opens URLs in the browser', async () => {
    await new TauriBackend().openExternal('https://aka.ms/MinecraftEULA')
    expect(sent('plugin:opener|open_url')).toEqual([
      expect.objectContaining({ url: 'https://aka.ms/MinecraftEULA' }),
    ])
  })

  it('acts on its own window and zooms its webview', async () => {
    const backend = new TauriBackend()
    await backend.windowAction('minimize')
    await backend.windowAction('toggleMaximize')
    await backend.windowAction('close')
    await backend.setZoom(1.25)
    expect(sent('plugin:window|minimize')).toEqual([{ label: 'main' }])
    expect(sent('plugin:window|toggle_maximize')).toEqual([{ label: 'main' }])
    expect(sent('plugin:window|close')).toEqual([{ label: 'main' }])
    expect(sent('plugin:webview|set_webview_zoom')).toEqual([
      expect.objectContaining({ label: 'main', value: 1.25 }),
    ])
  })

  it('picks a folder or a jar with the native dialog, null when cancelled', async () => {
    const backend = new TauriBackend()
    answers['plugin:dialog|open'] = () => '/home/me/project'
    expect(await backend.pickFolder('Open a project')).toBe('/home/me/project')
    answers['plugin:dialog|open'] = () => null
    expect(await backend.pickJar('Pick the client jar')).toBeNull()
    const [folder, jar] = sent('plugin:dialog|open').map((a) => a.options)
    expect(folder).toEqual(
      expect.objectContaining({ directory: true, multiple: false, title: 'Open a project' }),
    )
    expect(jar).toEqual(
      expect.objectContaining({
        directory: false,
        filters: [{ name: 'Minecraft client jar', extensions: ['jar'] }],
      }),
    )
  })

  it('reports an update the updater found, and installs it with progress, then relaunches', async () => {
    const backend = new TauriBackend()
    expect(await backend.checkForUpdate()).toBeNull()
    await expect(backend.installUpdate()).rejects.toThrow('There is no update to install')

    answers['plugin:updater|check'] = () => ({
      rid: 7,
      currentVersion: '1.0.0',
      version: '1.1.0',
      body: 'Notes',
      date: '2026-10-01',
    })
    answers['plugin:updater|download_and_install'] = (args) => {
      const channel = args.onEvent as Channel<unknown>
      channel.onmessage({ event: 'Started', data: { contentLength: 10 } })
      channel.onmessage({ event: 'Progress', data: { chunkLength: 4 } })
      channel.onmessage({ event: 'Progress', data: { chunkLength: 6 } })
      channel.onmessage({ event: 'Finished' })
      return null
    }
    expect(await backend.checkForUpdate()).toEqual({
      version: '1.1.0',
      currentVersion: '1.0.0',
      notes: 'Notes',
      date: '2026-10-01',
    })
    const progress: unknown[] = []
    await backend.installUpdate((p) => progress.push(p))
    expect(sent('plugin:updater|download_and_install')).toEqual([
      expect.objectContaining({ rid: 7 }),
    ])
    expect(progress.at(-1)).toEqual({ downloaded: 10, total: 10 })
    expect(sent('plugin:process|restart')).toHaveLength(1)
  })

  it('opens the launcher through its command', async () => {
    await new TauriBackend().openLauncher()
    expect(sent('mc_open_launcher')).toEqual([{}])
  })

  it('rethrows a command’s rejection as a BackendError with its code', async () => {
    answers.fs_list = () => Promise.reject({ code: 'noProject', message: 'No project is open' })
    const error = await new TauriBackend().listFiles().catch((e: unknown) => e)
    expect(error).toBeInstanceOf(BackendError)
    expect(error).toMatchObject({ code: 'noProject', message: 'No project is open' })
    expect(toBackendError(new Error('IPC broke'))).toMatchObject({
      code: 'other',
      message: 'IPC broke',
    })
  })

  it('builds its URLs for the platform it runs on', async () => {
    expect(nfProjectUrl('macos', 'packs/a b.png', 3)).toBe(
      'nfproject://localhost/packs/a%20b.png?v=3',
    )
    expect(nfProjectUrl('windows', 'a.png')).toBe('http://nfproject.localhost/a.png')
    expect(nfPackageUrl('linux', '../lib', 'items/gem.png')).toBe(
      'nfproject://localhost/:package/..%2Flib/items/gem.png',
    )
    expect(nfAssetUrl('windows', '26.3', 'assets/minecraft/x.png')).toBe(
      'http://nfasset.localhost/26.3/assets/minecraft/x.png',
    )
    answers.app_info = () => ({ version: '1.0.0', minecraftVersions: [], os: 'windows' })
    const backend = await new TauriBackend().init()
    expect(backend.assetUrl('26.3', 'a.png')).toBe('http://nfasset.localhost/26.3/a.png')
  })
})
