import { defineConfig, devices } from '@playwright/test'

/**
 * Editor UI flows against the production web build with the in-memory
 * backend (`vite build --mode e2e`, the only build that carries it), so they
 * run the same on every OS with no Tauri, disk or server. See e2e/helpers.ts for how tests play the outside
 * world. The build (not the dev server) keeps runs deterministic: nothing is
 * transformed or dependency-optimized on first use mid-test.
 *
 * The `screenshots` project (`e2e/screenshots/`) compares the pixel-exact
 * previews with committed pictures. Fonts and anti-aliasing differ by OS and
 * by what's installed, so its browser always runs in the official Playwright
 * container (linux/amd64, the version installed here), which
 * `tools/screenshots.mjs` starts and names in `NETHERFORGE_SCREENSHOT_SERVER`;
 * the tests and the web server stay on this machine, the container reaching
 * the server through Playwright's tunnel (`exposeNetwork`). Without that
 * server the project's tests skip. `pnpm test:screenshots` runs them;
 * `pnpm test:screenshots --update` rewrites the pictures.
 */
const screenshotServer = process.env.NETHERFORGE_SCREENSHOT_SERVER

export default defineConfig({
  testDir: 'e2e',
  testMatch: '**/*.spec.ts',
  fullyParallel: true,
  // GitHub's Windows runners draw WebGL in software, and two browsers drawing at once starve
  // each other: 3D flows stall for tens of seconds and time out. One worker runs them all in
  // well under a minute each (Linux and macOS keep Playwright's default).
  workers: process.env.CI && process.platform === 'win32' ? 1 : undefined,
  forbidOnly: !!process.env.CI,
  retries: 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  timeout: 30_000,
  // One picture per test and name, for the one platform they're taken on.
  snapshotPathTemplate: '{testDir}/screenshots/{testFileName}-snapshots/{arg}{ext}',
  expect: {
    toHaveScreenshot: {
      // Pixel-exact: the previews are drawn from format's numbers, so any change is one.
      maxDiffPixels: 0,
      threshold: 0,
      animations: 'disabled',
      caret: 'hide',
      scale: 'css',
    },
  },
  use: {
    baseURL: 'http://localhost:4173',
    trace: 'retain-on-failure',
    ...devices['Desktop Chrome'],
    viewport: { width: 1440, height: 900 },
  },
  projects: [
    { name: 'chromium', use: { browserName: 'chromium' }, testIgnore: 'screenshots/**' },
    {
      name: 'screenshots',
      testMatch: 'screenshots/**/*.spec.ts',
      use: {
        browserName: 'chromium',
        deviceScaleFactor: 1,
        connectOptions: screenshotServer
          ? { wsEndpoint: screenshotServer, exposeNetwork: '<loopback>' }
          : undefined,
      },
    },
  ],
  webServer: {
    command: 'pnpm exec vite build --mode e2e && pnpm exec vite preview --port 4173 --strictPort',
    url: 'http://localhost:4173',
    reuseExistingServer: false,
    timeout: 180_000,
  },
})
