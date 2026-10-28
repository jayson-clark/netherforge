import { cpSync, existsSync, mkdtempSync, readFileSync, rmSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { run } from './main.ts'
import { readPng } from './render.ts'

const example = path.resolve(import.meta.dirname, '../../../examples/showcase')

let tmp: string
let project: string
/** An editor data folder with no client imported: only the project's own looks are drawn. */
let env: NodeJS.ProcessEnv

beforeEach(() => {
  tmp = mkdtempSync(path.join(os.tmpdir(), 'netherforge-render-'))
  project = path.join(tmp, 'project')
  cpSync(example, project, {
    recursive: true,
    filter: (source) => path.relative(example, source) !== '.netherforge',
  })
  cpSync(path.resolve(example, '../library'), path.join(tmp, 'library'), { recursive: true })
  env = { NETHERFORGE_DATA_DIR: path.join(tmp, 'data') }
})
afterEach(() => rmSync(tmp, { recursive: true, force: true }))

/** How many of [file]'s pixels aren't clear. */
const drawn = (file: string) => {
  const picture = readPng(readFileSync(file))
  let count = 0
  for (let i = 3; i < picture.data.length; i += 4) if (picture.data[i]! > 0) count += 1
  return { size: picture.width, count }
}

describe('netherforge render', () => {
  it("draws a project item's pack picture as its icon", async () => {
    const out = path.join(tmp, 'ruby.png')
    const outcome = await run(
      ['render', path.join(project, 'items/ruby/item.json'), '--out', out, '--size', '32'],
      env,
    )
    expect(outcome.code).toBe(0)
    expect(outcome.out.join('\n')).toContain('No Minecraft')
    const { size, count } = drawn(out)
    expect(size).toBe(32)
    expect(count).toBeGreaterThan(50)
  })

  it('refuses a file that draws as nothing it knows', async () => {
    const outcome = await run(
      ['render', path.join(project, 'netherforge.json'), '--out', path.join(tmp, 'x.png')],
      env,
    )
    expect(outcome.code).toBe(2)
  })
})

describe('netherforge gallery', () => {
  it('draws what it can of every item, block and centity, and a page of them', async () => {
    const out = path.join(tmp, 'gallery')
    const outcome = await run(['gallery', project, '--out', out], env)
    expect(outcome.code).toBe(0)
    const page = readFileSync(path.join(out, 'index.html'), 'utf8')
    expect(page).toContain('items/ruby.png')
    expect(existsSync(path.join(out, 'items/ruby.png'))).toBe(true)
    // Its looks with no client are the pack's own: the crystal model draws, the game's blocks don't.
    expect(page).toContain('<figcaption>crystal</figcaption>')
  })
})
