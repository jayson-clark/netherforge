#!/usr/bin/env node
// Runs the repo's Gradle wrapper from anywhere, on any OS: `node tools/gradle.mjs <tasks…>`.
// The Gradle build's root is tools/gradle, which keeps its wrapper, caches and outputs
// out of the repo root.
import { spawnSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), 'gradle')
const wrapper = process.platform === 'win32' ? 'gradlew.bat' : './gradlew'
const result = spawnSync(wrapper, process.argv.slice(2), {
  cwd: root,
  stdio: 'inherit',
  shell: process.platform === 'win32',
})
process.exit(result.status ?? 1)
