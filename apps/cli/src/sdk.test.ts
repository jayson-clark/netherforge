import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { run } from './main.ts'
import { project } from './sdk.ts'

let tmp: string

const MANIFEST = {
  formatVersion: 1,
  name: 'Gen',
  namespace: 'gen',
  version: '1.0.0',
  minecraft: '26.3',
}

beforeEach(() => {
  tmp = mkdtempSync(path.join(os.tmpdir(), 'netherforge-sdk-'))
  writeFileSync(path.join(tmp, 'netherforge.json'), JSON.stringify(MANIFEST))
  mkdirSync(path.join(tmp, 'items/(gems)/ruby'), { recursive: true })
  writeFileSync(path.join(tmp, 'items/(gems)/ruby/item.json'), '{"kind":"minecraft:paper"}')
})
afterEach(() => rmSync(tmp, { recursive: true, force: true }))

describe('the generator SDK', () => {
  it('finds resources wherever their groups put them, and places new ones', () => {
    const p = project(path.join(tmp, 'items'))
    expect(p.root).toBe(tmp)
    expect([...p.resources('items').values()]).toEqual([
      { kind: 'items', id: 'ruby', group: '(gems)', path: 'items/(gems)/ruby/item.json' },
    ])
    expect(p.place('items', 'ruby', 'other')).toBe('items/(gems)/ruby/item.json')
    expect(p.place('items', 'sapphire', 'gems/blue')).toBe('items/(gems)/(blue)/sapphire/item.json')
    expect(p.place('recipes', 'ruby_block')).toBe('recipes/ruby_block.json')
  })

  it('writes resources formatted as the editor would, and leaves unchanged files alone', () => {
    const p = project(tmp)
    const file = p.writeResource(
      'items',
      'sapphire',
      { name: 'Sapphire', kind: 'minecraft:paper' },
      { group: 'gems' },
    )
    expect(file).toBe('items/(gems)/sapphire/item.json')
    const written = readFileSync(path.join(tmp, file), 'utf8')
    expect(written.indexOf('"kind"')).toBeLessThan(written.indexOf('"name"'))
    expect(p.resources('items').has('sapphire')).toBe(true)
    expect(
      p.writeResource('items', 'sapphire', { kind: 'minecraft:paper', name: 'Sapphire' }),
    ).toBe(file)
    expect(p.write(file, written)).toBe(false)
    expect(p.writeResource('items', 'sapphire', 'print("hi")\n', { file: 'script.lua' })).toBe(
      'items/(gems)/sapphire/script.lua',
    )
    expect(p.readJson<{ name: string }>(file)?.name).toBe('Sapphire')
    expect(p.read('nowhere.txt')).toBeNull()
  })
})

describe('netherforge generate', () => {
  it("runs netherforge.json's generate commands in order, then formats", async () => {
    const write = (name: string, text: string) =>
      `node -e "require('fs').writeFileSync('${name}', '${text}')"`
    writeFileSync(
      path.join(tmp, 'netherforge.json'),
      JSON.stringify({
        ...MANIFEST,
        generate: [
          write(
            'items/(gems)/ruby/item.json',
            '{\\"name\\":\\"Ruby\\",\\"kind\\":\\"minecraft:paper\\"}',
          ),
          write('second.txt', 'after the first'),
        ],
      }),
    )
    const outcome = await run(['generate', tmp])
    expect(outcome.code).toBe(0)
    const ruby = readFileSync(path.join(tmp, 'items/(gems)/ruby/item.json'), 'utf8')
    expect(ruby.indexOf('"kind"')).toBeLessThan(ruby.indexOf('"name"'))
    expect(readFileSync(path.join(tmp, 'second.txt'), 'utf8')).toBe('after the first')
  })

  it('stops at the first command that fails', async () => {
    writeFileSync(
      path.join(tmp, 'netherforge.json'),
      JSON.stringify({ ...MANIFEST, generate: ['node -e "process.exit(3)"', 'node -e "1"'] }),
    )
    const outcome = await run(['generate', tmp])
    expect(outcome.code).toBe(1)
    expect(outcome.err.join('\n')).toContain('exit 3')
  })
})
