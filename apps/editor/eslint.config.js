import boundaries from 'eslint-plugin-boundaries'
import reactHooks from 'eslint-plugin-react-hooks'
import globals from 'globals'
import tseslint from 'typescript-eslint'
import { typescript } from '../../eslint.config.js'

/**
 * `src/`'s layers, top to bottom (the editor-ui skill's "Where things are"):
 * each imports only from itself and the layers above it.
 */
const LAYERS = ['core', 'ui', 'state', 'minecraft', 'editors', 'workbench', 'app']

export default tseslint.config(
  // generated/: tauri-specta's output, checked by the generated-file check instead.
  {
    ignores: [
      'dist',
      'src-tauri',
      'test-results',
      'playwright-report',
      'src/core/backend/generated',
    ],
  },
  // The repo's rules for every TypeScript project, with React's hooks on top.
  {
    ...typescript,
    languageOptions: {
      globals: globals.browser,
      parserOptions: {
        ...typescript.languageOptions.parserOptions,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    plugins: { 'react-hooks': reactHooks },
    rules: { ...typescript.rules, ...reactHooks.configs.recommended.rules },
  },
  {
    files: ['src/**/*.{ts,tsx}'],
    plugins: { boundaries },
    settings: {
      'import/resolver': { typescript: { project: `${import.meta.dirname}/tsconfig.app.json` } },
      'boundaries/root-path': import.meta.dirname,
      'boundaries/elements': [
        ...LAYERS.map((layer) => ({ type: layer, pattern: `src/${layer}`, partialMatch: false })),
        { type: 'testing', pattern: 'src/testing', partialMatch: false },
      ],
      'boundaries/files': [{ category: 'test', pattern: '**/*.test.{ts,tsx}' }],
    },
    rules: {
      // Across folders, imports name their target from src/ (`@/core/paths`), so
      // a file reads the same wherever it moves and the layers are plain to see.
      'no-restricted-imports': [
        'error',
        { patterns: [{ group: ['../*'], message: "Import across folders with '@/…'." }] },
      ],
      'boundaries/dependencies': [
        'error',
        {
          default: 'disallow',
          policies: [
            ...LAYERS.map((layer, index) => ({
              from: { element: { type: layer } },
              allow: { to: { element: { types: { anyOf: LAYERS.slice(0, index + 1) } } } },
            })),
            // Fixtures are built with what they stand in for; the shared set-up
            // (testing/workspace.ts) makes the app's stores.
            {
              from: { element: { type: 'testing' } },
              allow: {
                to: { element: { types: { anyOf: ['core', 'state', 'minecraft', 'testing'] } } },
              },
            },
            // Playwright's build (`--mode e2e`) runs on the seeded memory backend,
            // which app/backend.ts loads behind __MEMORY_BACKEND__.
            {
              from: { element: { type: 'app' } },
              allow: { to: { element: { types: { anyOf: ['testing'] } } } },
            },
            // A test may make the whole app and use any fixture.
            {
              from: { file: { categories: 'test' } },
              allow: { to: { element: { types: { anyOf: [...LAYERS, 'testing'] } } } },
            },
          ],
        },
      ],
    },
  },
)
