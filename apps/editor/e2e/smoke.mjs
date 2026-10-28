#!/usr/bin/env node
/**
 * Packaged-app smoke test: `pnpm --filter @netherforge/editor test:smoke`.
 *
 * Runs nightly only (.github/workflows/nightly.yml), on Linux and Windows,
 * after `tauri build`, against the real bundled app rather than the web build
 * the Playwright flows use. The plan (see the testing skill): drive the app
 * through `tauri-driver` (WebDriver), open `examples/showcase`, change a
 * centity, save, and check the file on disk is byte-for-byte canonical. On
 * macOS there's no WebDriver for WKWebView, so nightly only builds and
 * launches there.
 *
 * Not written yet: it needs `tauri-driver` and a WebDriver client in the
 * nightly job, and can only be developed on Linux or Windows. Until then this
 * is a placeholder that passes and says so rather than pretending to test.
 * (Startup failures the web build can't show, like a command the capability
 * doesn't grant, are what it would catch first; `commands/contract.rs` covers
 * that one meanwhile.)
 */
console.log(
  'test:smoke: packaged-app smoke test not implemented yet (placeholder; see e2e/smoke.mjs).',
)
