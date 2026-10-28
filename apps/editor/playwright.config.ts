import { defineConfig, devices } from '@playwright/test'
import { availableParallelism } from 'node:os'

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
  // Headless Chromium draws WebGL in software (always on CI's runners), and every 3D view
  // redraws each frame, so browsers drawing at once starve each other: a flow that takes 3 s on
  // its own took 22 s beside one other on a Linux runner, and timed out at Playwright's default
  // of half the cores on an 18-core machine (68 tests: 4-11 timeouts a run; 4 workers: none, in
  // half the time). CI runs one at a time; a machine of your own, at most four.
  workers: process.env.CI ? 1 : Math.max(1, Math.min(4, Math.floor(availableParallelism() / 2))),
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
