#!/usr/bin/env node
/**
 * Rewrites every dependency lock the Gradle build checks, after a dependency or
 * plugin changed (`pnpm deps:lock`):
 *
 *  - `gradle.lockfile` in each project, and `tools/gradle/{buildscript,settings}-gradle.lockfile`:
 *    the exact version of everything every configuration resolves (Gradle's
 *    dependency locking, strict: a configuration without a lock state fails);
 *  - `tools/gradle/gradle/verification-metadata.xml`: the SHA-256 of every artifact
 *    (Gradle's dependency verification). Its `<configuration>` (the trusted
 *    snapshots) is kept; its `<components>` are emptied and recorded afresh, so
 *    nothing stale stays trusted;
 *  - `tools/gradle/kotlin-js-store/package-lock.json`: the npm packages Kotlin/JS
 *    tests with.
 *
 * Review the diff like code: a new artifact or a changed checksum is a new thing
 * the build will download and run.
 */
import { spawnSync } from 'node:child_process'
import { readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const metadata = path.join(root, 'tools/gradle/gradle/verification-metadata.xml')

const before = readFileSync(metadata, 'utf8')

const gradle = (...args) => {
  const result = spawnSync('node', ['tools/gradle.mjs', ...args], {
    cwd: root,
    stdio: 'inherit',
    shell: process.platform === 'win32',
  })
  if (result.status !== 0) {
    // Don't leave the build without its checksums.
    writeFileSync(metadata, before)
    process.exit(result.status ?? 1)
  }
}

writeFileSync(metadata, before.replace(/<components>[\s\S]*<\/components>/, '<components/>'))

// --refresh-dependencies: with a warm cache Gradle skips parent POMs and BOMs it
// already resolved, which a fresh machine (CI) still fetches and verifies.
gradle(
  'resolveAndLockAll',
  '--write-locks',
  '--write-verification-metadata',
  'sha256',
  '--refresh-dependencies',
)
gradle('kotlinUpgradePackageLock')

console.log('\nLocks rewritten. Review `git diff` before committing.')
