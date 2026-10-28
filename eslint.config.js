import js from '@eslint/js'
import globals from 'globals'
import tseslint from 'typescript-eslint'

/**
 * The rules every TypeScript project here is linted with: ESLint's and typescript-eslint's
 * recommended sets, and the type-aware ones that catch a promise nobody awaits or handles.
 * The editor (`apps/editor/eslint.config.js`, its own config) adds React's and its layers' rules
 * on top; everything else (`packages/*`, `apps/cli`) is linted with this file as it is.
 */
export const typescript = {
  files: ['**/*.{ts,tsx}'],
  extends: [js.configs.recommended, ...tseslint.configs.recommended],
  languageOptions: {
    // Each file is checked with the nearest tsconfig.json, as `tsc -p .` checks it.
    parserOptions: { projectService: true },
  },
  rules: {
    // `const { x: _x, ...rest } = it` is how a field is left out of a copy.
    '@typescript-eslint/no-unused-vars': ['error', { ignoreRestSiblings: true }],
    // A `.d.ts` beside a file, for what only the bundler knows (`?url` imports), is referenced by path.
    '@typescript-eslint/triple-slash-reference': ['error', { path: 'always' }],
    '@typescript-eslint/no-floating-promises': 'error',
    '@typescript-eslint/no-misused-promises': [
      'error',
      { checksVoidReturn: { attributes: false } },
    ],
  },
}

export default tseslint.config(
  {
    ignores: [
      '**/node_modules',
      '**/build',
      '**/dist',
      '.claude',
      // The editor lints itself (its own config, run by its `lint`); the docs site is VitePress's.
      'apps/editor',
      'docs',
      'examples',
      'tools',
    ],
  },
  { ...typescript, languageOptions: { ...typescript.languageOptions, globals: globals.node } },
)
