import { test as base, expect, type Page } from '@playwright/test'

/**
 * Helpers for the editor flows. The page runs on the memory backend (the e2e build), which
 * puts it on `window.__netherforge.backend`; `outside()` runs
 * code against it to play git, an agent or the plugin.
 */

export const TOWER = 'centities/tower/centity.json'

/**
 * Playwright's `test`, failing any flow that trips the app's
 * Content-Security-Policy (which `vite preview` sends; see vite.config.ts).
 */
export const test = base.extend<{ csp: void }>({
  csp: [
    async ({ page }, use) => {
      const violations: string[] = []
      page.on('console', (message) => {
        if (message.type() === 'error' && message.text().includes('Content Security Policy')) {
          violations.push(message.text())
        }
      })
      await use()
      expect(violations, 'Content-Security-Policy violations').toEqual([])
    },
    { auto: true },
  ],
})

/** Opens the editor on the seeded examples/showcase project; [query] adds to the page's (`&untrusted`). */
export async function openExample(page: Page, query = '') {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.goto(`/?fast${query}`)
  await page
    .getByRole('list', { name: 'Recent projects' })
    .getByRole('button', { name: /NetherForge Showcase/ })
    .click()
  await expect(explorer(page)).toBeVisible()
  return errors
}

/** The project explorer's resources (the bottom dock's Project tab). */
export const explorer = (page: Page) => page.getByRole('listbox').first()

/** Shows a dock panel's tab, wherever it's docked: `Project`, `Problems`, `Console`, `Instances`… */
export async function showPanel(page: Page, name: string) {
  await page.locator('[data-dock]').getByRole('tab', { name }).click()
}

/** Runs [fn] against the memory backend in the page. */
export function outside<T, A>(
  page: Page,
  fn: (backend: MemoryBackendHandle, arg: A) => T,
  arg: A,
): Promise<T> {
  // Evaluated as source by Playwright, not by `new Function` in the page,
  // which the app's script-src forbids.
  return page.evaluate(
    `(${fn.toString()})(window.__netherforge.backend, ${JSON.stringify(arg) ?? 'undefined'})`,
  ) as Promise<T>
}

/** The memory backend's test surface (see src/testing/backend/memory.ts). */
export interface MemoryBackendHandle {
  testFiles(): Record<string, string>
  testBytes(path: string): string | null
  testWrite(path: string, text: string): void
  testWriteBytes(path: string, base64: string): void
  testPlayer(player: string, world: string, x: number, y: number, z: number): void
  testWorld(name: string): void
  testServerFiles(files: Record<string, string>): void
  testDelete(path: string): void
  testBridgeEvent(event: string, params: unknown): void
  testServerOutput(line: string): void
  testConnect(): void
  testReloadRestarts(on?: boolean): void
  testDebugStop(options?: {
    file?: string
    line?: number
    reason?: string
    description?: string
  }): void
  testBreakpoints(): Record<string, number[]>
  bridgeLog: unknown[]
  dapLog: { command: string; arguments?: unknown }[]
}

export const files = (page: Page) => outside(page, (backend) => backend.testFiles(), null)

/** Opens a centity from the project explorer. */
export async function openCentity(page: Page, id: string) {
  await openResource(page, id)
  await expect(page.getByRole('treegrid', { name: 'Nodes' })).toBeVisible()
}

/** Shows one kind's resources (its title, `Menus`) or a dependency's (`Package library`) in the project explorer. */
export async function showKind(page: Page, title: string) {
  await showPanel(page, 'Project')
  await page
    .getByRole('listbox', { name: 'Resource kinds' })
    .getByRole('option', { name: title, exact: true })
    .click()
}

/**
 * Opens a resource from the project explorer by id, finding it with the
 * explorer's search (across every kind; [kind], its kind's title such as
 * `Menus`, when two kinds share the id).
 */
export async function openResource(page: Page, id: string, kind?: string) {
  await showPanel(page, 'Project')
  const search = page.getByRole('searchbox', { name: 'Search resources' })
  if (kind) {
    await search.fill('')
    await showKind(page, kind)
  } else {
    await search.fill(id)
  }
  await page.getByRole('option', { name: id, exact: true }).first().dblclick()
  await search.fill('')
  // A structure's tab is its file (`house.nbt`), a map's its folder (`arena/`), a module's its entry file.
  const tab = kind === 'Modules' ? /^init\.lua$/ : new RegExp(`^${id}(\\.nbt|/)?$`)
  await expect(
    page.getByRole('tablist', { name: 'Open files' }).getByRole('tab', { name: tab }),
  ).toHaveAttribute('aria-selected', 'true')
}

/** The outline's Files pane for the resource in [folder] (`centities/tower`). */
export const filesOf = (page: Page, folder: string) =>
  page.getByRole('treegrid', { name: `Files in ${folder}` })

/** Opens the example with the fixture client assets (models, textures, glyph advances). */
export async function openExampleWithAssets(page: Page) {
  await page.goto('/?fast&assets=fixture')
  await page
    .getByRole('list', { name: 'Recent projects' })
    .getByRole('button', { name: /NetherForge Showcase/ })
    .click()
  await expect(explorer(page)).toBeVisible()
}

/** Saves the active document and waits for its tab to be clean. */
export async function save(page: Page, tab: string) {
  await page.keyboard.press('ControlOrMeta+s')
  await expect(page.getByRole('tab', { name: tab, exact: true })).toBeVisible()
}

/**
 * Chooses [item] from [menu] in the in-app menu bar (the memory backend
 * reports Linux, so the menus are drawn in the title bar, not the system's).
 * Returns once the command has been started.
 */
export async function chooseMenu(page: Page, menu: string, item: string | RegExp) {
  await page
    .getByRole('menubar', { name: 'Menu' })
    .getByRole('menuitem', { name: menu, exact: true })
    .click()
  const panel = page.getByRole('menu', { name: menu })
  const name = typeof item === 'string' ? new RegExp(`^${item}`) : item
  await panel
    .getByRole('menuitem', { name })
    .or(panel.getByRole('menuitemcheckbox', { name }))
    .click()
  // The chosen command runs a frame after its menu has closed (see MenuBar), so
  // a key pressed right after the click could reach the page before it.
  await expect(panel).toHaveCount(0)
  // A string: the e2e code is typed for Node, not the page's DOM.
  await page.evaluate(
    'new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(done)))',
  )
}
