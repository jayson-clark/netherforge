import { MemoryBackend } from './memory'
import { EXAMPLE_ROOT, LIBRARY_ROOT, exampleProject, libraryProject } from './seed'
import type { UpdateInfo } from '@/core/backend/types'

/** A memory backend holding `examples/showcase`, as Playwright uses it. */
export function seededMemoryBackend(
  options: {
    serverDelayMs?: number
    clientAssets?: Record<string, string>
    glyphAdvances?: Record<string, number>
    update?: UpdateInfo
    /** Whether the example is a project the user trusted before (it is, unless a flow says otherwise). */
    trusted?: boolean
  } = {},
): MemoryBackend {
  return new MemoryBackend({
    projects: { [EXAMPLE_ROOT]: exampleProject, [LIBRARY_ROOT]: libraryProject },
    recent: [
      { root: EXAMPLE_ROOT, name: 'NetherForge Showcase', openedAt: '2026-01-01T00:00:00Z' },
    ],
    installs: [
      {
        launcher: 'vanilla',
        path: '/memory/.minecraft',
        versions: [{ version: '26.3', jar: '/memory/.minecraft/versions/26.3/26.3.jar' }],
      },
    ],
    pickFolderResults: ['/memory/new-project', '/memory/another-project'],
    serverDelayMs: options.serverDelayMs ?? 150,
    clientAssets: options.clientAssets,
    importedClients: options.clientAssets ? ['26.3'] : [],
    glyphAdvances: options.glyphAdvances ? { '26.3': options.glyphAdvances } : undefined,
    update: options.update,
    watchDebounceMs: 50,
    trusted: options.trusted === false ? [] : [EXAMPLE_ROOT],
  })
}

/** The seeded memory backend, configured by the page's query string. */
export async function memoryBackendFor(params: URLSearchParams): Promise<MemoryBackend> {
  // `?assets=fixture` serves a tiny synthetic asset set as if imported.
  const fixture =
    params.get('assets') === 'fixture' ? await import('@/testing/clientFixture') : null
  const backend = seededMemoryBackend({
    serverDelayMs: params.has('fast') ? 0 : 150,
    clientAssets: fixture?.clientFixture,
    glyphAdvances: fixture?.fixtureGlyphAdvances,
    // `?update=1.2.3` pretends a newer release is out.
    // `?untrusted` opens the example as a project from somewhere new: restricted mode.
    trusted: !params.has('untrusted'),
    update: params.get('update')
      ? { version: params.get('update')!, currentVersion: '0.0.0-memory', notes: null, date: null }
      : undefined,
  })
  window.__netherforge = { backend }
  return backend
}
