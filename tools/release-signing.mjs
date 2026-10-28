#!/usr/bin/env node
/**
 * Release CI only: turns whichever signing secrets this repository has into
 * what the Tauri build needs, and degrades to an unsigned build for whatever
 * is missing, instead of failing.
 *
 * Tauri reads signing material from environment variables, and treats a
 * variable that is *set but empty* as "sign with this", which fails. GitHub
 * expands a missing secret to an empty string. So the workflow hands the
 * secrets to this step only, and this script exports to `$GITHUB_ENV` just
 * the ones that are present and complete.
 *
 * It also writes a Tauri config overlay (passed as a second `--config`):
 *
 * - `bundle.createUpdaterArtifacts`: on only with an updater private key, so
 *   a build without one still succeeds (it just has no `latest.json`).
 * - `bundle.macOS.signingIdentity: "-"` (ad-hoc) when there's no Developer ID
 *   certificate, so Apple silicon Macs can still open the app after the
 *   "unidentified developer" prompt instead of calling it damaged.
 * - `bundle.windows.certificateThumbprint` when the previous step imported a
 *   code-signing certificate (`WINDOWS_CERTIFICATE_THUMBPRINT`).
 * - `bundle.windows.wix.version` for a prerelease: an MSI's version is four
 *   numbers, so `1.2.3-rc.4` is built as `1.2.3.4` (what Tauri itself makes of
 *   `1.2.3-4`; it refuses `-rc.4`). The app and the other bundles keep the
 *   full version.
 *
 * Outputs `config=<overlay path>` for the next step, and a summary of what's
 * signed to the job summary. The secrets and what each enables are listed in
 * .claude/skills/release/SKILL.md.
 */
import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import path from 'node:path'

const env = process.env
const os = env.RUNNER_OS // Linux | Windows | macOS
const temp = env.RUNNER_TEMP
if (!os || !temp || !env.GITHUB_ENV || !env.GITHUB_OUTPUT) {
  console.error('release-signing.mjs runs inside GitHub Actions only')
  process.exit(2)
}

const has = (...names) => names.every((name) => (env[name] ?? '').trim() !== '')
const exported = []
function exportVar(name, value = env[name]) {
  const delimiter = `NF_${Math.random().toString(36).slice(2)}`
  appendFileSync(env.GITHUB_ENV, `${name}<<${delimiter}\n${value}\n${delimiter}\n`)
  exported.push(name)
}

const overlay = { bundle: {} }
const summary = []

// ---- updater signatures (every OS) ----------------------------------------
// An app built without the public key can never verify an update, so a
// signed latest.json would point every install at updates it must refuse.
const tauriConfig = JSON.parse(
  readFileSync(new URL('../apps/editor/src-tauri/tauri.conf.json', import.meta.url), 'utf8'),
)
const pubkey = (tauriConfig.plugins?.updater?.pubkey ?? '').trim()
if (has('TAURI_SIGNING_PRIVATE_KEY') && !pubkey) {
  console.error(
    'TAURI_SIGNING_PRIVATE_KEY is set but plugins.updater.pubkey in tauri.conf.json is empty: ' +
      'the released app could never install an update. Put the public key there ' +
      '(see "The updater keypair" in .claude/skills/release/SKILL.md).',
  )
  process.exit(1)
}
if (has('TAURI_SIGNING_PRIVATE_KEY')) {
  exportVar('TAURI_SIGNING_PRIVATE_KEY')
  if (has('TAURI_SIGNING_PRIVATE_KEY_PASSWORD')) exportVar('TAURI_SIGNING_PRIVATE_KEY_PASSWORD')
  overlay.bundle.createUpdaterArtifacts = true
  summary.push('Updater artifacts: signed (latest.json will be generated)')
} else {
  summary.push('Updater artifacts: **off** (no TAURI_SIGNING_PRIVATE_KEY)')
}

// ---- macOS: Developer ID signing and notarization -------------------------
if (os === 'macOS') {
  if (has('APPLE_CERTIFICATE', 'APPLE_CERTIFICATE_PASSWORD')) {
    exportVar('APPLE_CERTIFICATE')
    exportVar('APPLE_CERTIFICATE_PASSWORD')
    if (has('APPLE_SIGNING_IDENTITY')) exportVar('APPLE_SIGNING_IDENTITY')
    summary.push('macOS signing: Developer ID')

    if (has('APPLE_API_KEY', 'APPLE_API_ISSUER', 'APPLE_API_KEY_P8')) {
      // App Store Connect API key: Tauri wants the .p8 as a file.
      const dir = path.join(temp, 'private_keys')
      mkdirSync(dir, { recursive: true })
      const file = path.join(dir, `AuthKey_${env.APPLE_API_KEY}.p8`)
      writeFileSync(file, env.APPLE_API_KEY_P8, { mode: 0o600 })
      exportVar('APPLE_API_KEY')
      exportVar('APPLE_API_ISSUER')
      exportVar('APPLE_API_KEY_PATH', file)
      summary.push('macOS notarization: App Store Connect API key')
    } else if (has('APPLE_ID', 'APPLE_PASSWORD', 'APPLE_TEAM_ID')) {
      exportVar('APPLE_ID')
      exportVar('APPLE_PASSWORD')
      exportVar('APPLE_TEAM_ID')
      summary.push('macOS notarization: Apple ID')
    } else {
      summary.push(
        'macOS notarization: **off** (no APPLE_ID/APPLE_PASSWORD/APPLE_TEAM_ID or API key)',
      )
    }
  } else {
    overlay.bundle.macOS = { signingIdentity: '-' }
    summary.push('macOS signing: **ad-hoc only** (no APPLE_CERTIFICATE); not notarized')
  }
}

// ---- Windows: Authenticode -------------------------------------------------
if (os === 'Windows') {
  if (has('WINDOWS_CERTIFICATE_THUMBPRINT')) {
    overlay.bundle.windows = {
      certificateThumbprint: env.WINDOWS_CERTIFICATE_THUMBPRINT.trim(),
      digestAlgorithm: 'sha256',
      timestampUrl: env.WINDOWS_TIMESTAMP_URL?.trim() || 'http://timestamp.digicert.com',
    }
    summary.push('Windows signing: Authenticode')
  } else {
    summary.push('Windows signing: **off** (no WINDOWS_CERTIFICATE)')
  }
}

// ---- Windows: the MSI's version --------------------------------------------
if (os === 'Windows') {
  const { version } = JSON.parse(
    readFileSync(new URL('../apps/editor/package.json', import.meta.url), 'utf8'),
  )
  const [, core, pre] = /^(\d+\.\d+\.\d+)(?:-(.+))?$/.exec(version) ?? []
  if (!core) {
    console.error(`The editor's version ${version} isn't major.minor.patch[-prerelease]`)
    process.exit(1)
  }
  if (pre) {
    const build = Number(/(\d+)$/.exec(pre)?.[1] ?? 0)
    if (build > 65535) {
      console.error(`An MSI's version numbers stop at 65535: ${version} has ${build}`)
      process.exit(1)
    }
    overlay.bundle.windows = { ...overlay.bundle.windows, wix: { version: `${core}.${build}` } }
    summary.push(`Windows MSI version: ${core}.${build} (for ${version})`)
  }
}

if (os === 'Linux')
  summary.push('Linux: packages are not signed (the updater signature covers the AppImage)')

const file = path.join(temp, 'netherforge-release.conf.json')
writeFileSync(file, JSON.stringify(overlay, null, 2) + '\n')
// Forward slashes: the path goes through tauri-action's argument splitting.
appendFileSync(env.GITHUB_OUTPUT, `config=${file.replaceAll('\\', '/')}\n`)

const report = [`### Signing (${os})`, '', ...summary.map((line) => `- ${line}`), '']
if (env.GITHUB_STEP_SUMMARY) appendFileSync(env.GITHUB_STEP_SUMMARY, report.join('\n') + '\n')
console.log(report.join('\n'))
console.log(`Exported: ${exported.join(', ') || 'nothing'}`)
console.log(`Config overlay: ${JSON.stringify(overlay)}`)
