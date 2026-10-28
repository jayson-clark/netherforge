/**
 * Editor updates: a quiet check at start and one from Settings, and an
 * install that always asks first (the UI's job) and stops the dev server
 * before the updater replaces the app and restarts it.
 */
import { createStore, type StoreApi } from 'zustand/vanilla'
import type { Backend, UpdateInfo, UpdateProgress } from '@/core/backend/types'

export interface Updates {
  available: UpdateInfo | null
  checking: boolean
  /** The last explicit check found nothing newer. */
  upToDate: boolean
  installing: UpdateProgress | null
  error: string | null
  /** The user said "later" to this version. */
  dismissed: string | null
  /** [quiet] (at start): failures stay silent. */
  check(options?: { quiet?: boolean }): Promise<void>
  /** Stops the dev server ([stopServer]), then downloads, installs and restarts. */
  install(stopServer: () => Promise<void>): Promise<void>
  dismiss(): void
}

export type UpdatesStore = StoreApi<Updates>

const message = (error: unknown) =>
  error instanceof Error ? error.message : typeof error === 'string' ? error : String(error)

export function createUpdates(backend: Backend): UpdatesStore {
  return createStore<Updates>()((set, get) => ({
    available: null,
    checking: false,
    upToDate: false,
    installing: null,
    error: null,
    dismissed: null,

    async check(options = {}) {
      if (get().checking) return
      set({ checking: true, error: null, upToDate: false })
      try {
        const available = await backend.checkForUpdate()
        set({ available, upToDate: available === null })
      } catch (error) {
        // No release endpoint yet, offline, or a dev build: nothing to say at start.
        if (!options.quiet) set({ error: `Couldn't check for updates: ${message(error)}` })
      } finally {
        set({ checking: false })
      }
    },

    async install(stopServer) {
      if (!get().available || get().installing) return
      set({ installing: { downloaded: 0, total: null }, error: null })
      try {
        await stopServer()
        await backend.installUpdate((progress) => set({ installing: progress }))
      } catch (error) {
        set({ error: `Couldn't install the update: ${message(error)}` })
      } finally {
        set({ installing: null })
      }
    },

    dismiss() {
      set({ dismissed: get().available?.version ?? null })
    },
  }))
}
