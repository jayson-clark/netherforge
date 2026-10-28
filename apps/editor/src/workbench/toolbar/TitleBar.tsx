/**
 * The window's title bar is our toolbar (`tauri.<os>.conf.json`): macOS
 * overlays its traffic lights on it, Windows has no frame and we draw the
 * buttons, Linux keeps the window manager's (as does the memory backend,
 * which reports Linux).
 */
import type { AppInfo } from '@/core/backend/types'
import { useApp, useWorkspace } from '@/state/providers'
import { MenuBar } from '@/workbench/menus/MenuBar'
import styles from './TitleBar.module.css'

export type TitleBarKind = 'overlay' | 'custom' | 'native'

export function titleBarOf(os: AppInfo['os'] | undefined): TitleBarKind {
  return os === 'macos' ? 'overlay' : os === 'windows' ? 'custom' : 'native'
}

export function useTitleBar(): TitleBarKind {
  const os = useWorkspace((s) => s.appInfo?.os)
  return titleBarOf(os)
}

/** Minimize, maximize and close, for a window without a native frame. */
export function WindowControls() {
  const { backend } = useApp()
  if (useTitleBar() !== 'custom') return null
  return (
    <div className={styles.controls} role="group" aria-label="Window">
      <button
        type="button"
        aria-label="Minimize"
        onClick={() => void backend.windowAction('minimize')}
      >
        <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">
          <path d="M0 5.5h10" stroke="currentColor" />
        </svg>
      </button>
      <button
        type="button"
        aria-label="Maximize"
        onClick={() => void backend.windowAction('toggleMaximize')}
      >
        <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">
          <rect x="0.5" y="0.5" width="9" height="9" fill="none" stroke="currentColor" />
        </svg>
      </button>
      <button
        type="button"
        className={styles.close}
        aria-label="Close window"
        onClick={() => void backend.windowAction('close')}
      >
        <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true">
          <path d="M0 0l10 10M10 0L0 10" stroke="currentColor" />
        </svg>
      </button>
    </div>
  )
}

/**
 * A bare title bar for screens without the toolbar (the welcome screen), with
 * the menus off macOS. A drag region is only the element marked, not its
 * children, so the empty stretch is its own marked element, as in the toolbar.
 */
export function BareTitleBar() {
  const kind = useTitleBar()
  return (
    <div className={styles.bare} data-tauri-drag-region>
      {kind !== 'overlay' && <MenuBar />}
      <span className={styles.drag} data-tauri-drag-region />
      <WindowControls />
    </div>
  )
}
