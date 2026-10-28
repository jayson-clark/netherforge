#!/usr/bin/env node
/**
 * Runs the editor's screenshot tests (`apps/editor/e2e/screenshots/`) with
 * the browser in the official Playwright container, so the pictures are the
 * same on every machine and in CI: the same Linux, fonts and Chromium, on
 * linux/amd64 (emulated on an Arm Mac) whatever the host.
 *
 *   node tools/screenshots.mjs            compare with the committed pictures
 *   node tools/screenshots.mjs --update   rewrite them (review the diff)
 *
 * Needs Docker. The container runs `playwright run-server` of the version
 * the editor has installed (client and server must match), the tests and the
 * web build run here, and the container's browser reaches the build through
 * Playwright's tunnel (`exposeNetwork` in playwright.config.ts). Bumping
 * @playwright/test changes the image, so it may change pictures: run
 * `--update` and look.
 */
import { spawn, spawnSync } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const editor = path.join(root, 'apps/editor')
const update = process.argv.includes('--update')

const version = JSON.parse(
  readFileSync(path.join(editor, 'node_modules/@playwright/test/package.json'), 'utf8'),
).version
const image = `mcr.microsoft.com/playwright:v${version}-noble`
const name = `netherforge-screenshots-${randomBytes(4).toString('hex')}`
const port = 39_000 + (randomBytes(2).readUInt16BE() % 1000)

function docker(args, options = {}) {
  const result = spawnSync('docker', args, { stdio: 'inherit', ...options })
  if (result.error) {
    console.error(
      `✗ docker isn't available (${result.error.message}): the screenshot tests need it`,
    )
    process.exit(1)
  }
  return result.status ?? 1
}

/**
 * Resolves once the container's server says it's listening, or fails after
 * [ms]. (Docker's published port accepts connections before anything inside
 * does, so the port alone can't tell.)
 */
async function listening(ms) {
  const deadline = Date.now() + ms
  while (Date.now() < deadline) {
    const logs = spawnSync('docker', ['logs', name], { encoding: 'utf8' })
    if (`${logs.stdout}${logs.stderr}`.includes('Listening on')) return
    if (logs.status !== 0) throw new Error('the Playwright container stopped')
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`the Playwright server didn't start within ${ms / 1000} s`)
}

const started = docker([
  'run',
  '--detach',
  '--rm',
  '--init',
  '--platform',
  'linux/amd64',
  '--name',
  name,
  '--publish',
  `127.0.0.1:${port}:3000`,
  '--user',
  'pwuser',
  '--workdir',
  '/home/pwuser',
  image,
  '/bin/sh',
  '-c',
  `npx -y playwright@${version} run-server --port 3000 --host 0.0.0.0`,
])
if (started !== 0) process.exit(started)

let status = 1
try {
  await listening(180_000)
  const args = ['exec', 'playwright', 'test', '--project', 'screenshots']
  if (update) args.push('--update-snapshots')
  status = await new Promise((resolve) => {
    const child = spawn('pnpm', args, {
      cwd: editor,
      stdio: 'inherit',
      shell: process.platform === 'win32',
      env: { ...process.env, NETHERFORGE_SCREENSHOT_SERVER: `ws://127.0.0.1:${port}/` },
    })
    child.on('exit', (code) => resolve(code ?? 1))
  })
} catch (error) {
  console.error(`✗ ${error.message}`)
  spawnSync('docker', ['logs', name], { stdio: 'inherit' })
} finally {
  spawnSync('docker', ['stop', name], { stdio: 'ignore' })
}
process.exit(status)
