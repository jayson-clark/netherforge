import { readFileSync } from 'node:fs'
import { defineConfig } from 'vitest/config'

const { version } = JSON.parse(readFileSync(new URL('package.json', import.meta.url), 'utf8')) as {
  version: string
}

/**
 * The script's first lines: run by Node, and what CommonJS code bundled into an ES module expects to find
 * (wasmoon's Emscripten build, the Lua that terrain scripts run on, asks for `require` and `__filename`).
 */
const BANNER = [
  '#!/usr/bin/env node',
  "import { createRequire as __nfCreateRequire } from 'node:module'",
  "import { fileURLToPath as __nfFileURLToPath } from 'node:url'",
  'const require = __nfCreateRequire(import.meta.url)',
  'const __filename = __nfFileURLToPath(import.meta.url)',
].join('\n')

// One self-contained file, format's Kotlin/JS build included, so it runs with
// nothing but Node: `node netherforge.mjs check`. The editor copies it into each
// project's .netherforge/bin/, and each release attaches it.
export default defineConfig({
  define: { __NETHERFORGE_VERSION__: JSON.stringify(version) },
  ssr: { noExternal: true },
  build: {
    ssr: 'src/bin.ts',
    target: 'node22',
    outDir: 'dist',
    minify: false,
    rollupOptions: {
      // One file: what @netherforge/project-support loads lazily (the API's stubs, the docs) is inlined.
      output: { entryFileNames: 'netherforge.mjs', banner: BANNER, inlineDynamicImports: true },
    },
  },
  test: {
    include: ['src/**/*.test.ts'],
    // A test runs the built command in a process of its own: a preview takes about a second here and
    // nearly 8 on a busy Windows runner, past vitest's 5s default (as the editor's tests found).
    testTimeout: 20_000,
  },
})
