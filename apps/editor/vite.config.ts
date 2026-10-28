import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

const repoRoot = fileURLToPath(new URL('../..', import.meta.url))
const srcRoot = fileURLToPath(new URL('./src', import.meta.url))

// The app's Content-Security-Policy, from the one place it's written. The
// preview server (what Playwright runs against) sends it too, so the e2e
// flows break if the policy would break the app.
const tauriConfig = JSON.parse(
  readFileSync(new URL('src-tauri/tauri.conf.json', import.meta.url), 'utf8'),
) as { app: { security: { csp: Record<string, string> } } }
const csp = Object.entries(tauriConfig.app.security.csp)
  .map(([directive, sources]) => `${directive} ${sources}`)
  .join('; ')

// Tauri expects a fixed dev port and serves the built files from dist/.
export default defineConfig(({ mode }) => ({
  plugins: [react()],
  // One `vscode` (the extension API monaco-languageclient talks to), whoever imports it.
  resolve: { alias: { '@': srcRoot }, dedupe: ['vscode'] },
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
    // examples/ is the memory backend's seed (import.meta.glob), but it's also
    // the project people open in `pnpm dev`: watching it would make every save
    // (and the dev server's logs) reload the page, back to the welcome screen.
    watch: { ignored: ['**/src-tauri/**', '**/examples/**'] },
    // The memory backend seeds itself from examples/ at build time.
    fs: { allow: [repoRoot] },
  },
  preview: { headers: { 'Content-Security-Policy': csp } },
  // Only Playwright's build (`vite build --mode e2e`) carries the memory
  // backend and the example project it seeds; the app, in dev or built,
  // always runs on the Rust backend.
  define: { __MEMORY_BACKEND__: JSON.stringify(mode === 'e2e') },
  worker: { format: 'es' },
  // Structure files a test reads as bytes (`?inline`): a captured one under src/testing/fixtures/.
  assetsInclude: ['**/*.nbt'],
  // The editors are lazy chunks. Pre-bundle what they import at startup, so
  // opening the first one doesn't make Vite discover deps and reload the page
  // (which would also break a Playwright flow halfway).
  optimizeDeps: {
    entries: ['index.html', 'src/**/*.tsx'],
    include: [
      'three',
      '@react-three/fiber',
      '@react-three/drei',
      'zustand',
      'zustand/vanilla',
      // Monaco and the language client: VS Code's services keep state in their modules, so
      // they must be optimized in one run (one copy of each), not discovered piecemeal.
      '@codingame/monaco-vscode-api',
      '@codingame/monaco-vscode-editor-api',
      '@codingame/monaco-vscode-files-service-override',
      '@codingame/monaco-vscode-standalone-languages/languages/definitions/lua/register',
      '@codingame/monaco-vscode-standalone-languages/languages/definitions/sql/register',
      '@codingame/monaco-vscode-standalone-json-language-features',
      'monaco-languageclient',
      'monaco-languageclient/vscodeApiWrapper',
      'vscode',
      'vscode/localExtensionHost',
      'vscode-languageclient/browser',
      'vscode-jsonrpc',
    ],
  },
  build: {
    target: 'es2022',
    outDir: 'dist',
    chunkSizeWarningLimit: 4096,
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    // jsdom has no Worker: this one runs a worker's module in the test's
    // process behind real messages (structured clones), so the store's tests
    // validate through the same worker module and Comlink calls as the app.
    setupFiles: ['@vitest/web-worker', './src/testing/workers.ts'],
    // The dev bridge's JSON-RPC runs on vscode-jsonrpc's browser runtime in the app; tests run in Node.
    alias: { 'vscode-jsonrpc/browser': 'vscode-jsonrpc/node' },
    // A test that types into the inspector takes under a second here and several on a busy
    // Windows runner (the terrain inspector's ore test ran 8s there), past vitest's 5s default.
    testTimeout: 20_000,
  },
}))
