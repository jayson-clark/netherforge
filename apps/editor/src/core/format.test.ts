import { describe, expect, it } from 'vitest'
import {
  canonicalize,
  canonicalizeModel,
  documentKindOf,
  compileResourcePacks,
  classify,
  KINDS,
  loadProject,
  newProjectFiles,
  newResourceFiles,
  newScript,
  particleCurveAt,
  particleEffectSampler,
  poseCentity,
  type EffectStepResult,
  type CentityFile,
  type GameDataBundle,
} from './format'
import { isProjectJson, mainFileOf } from './paths'
import { exampleFiles, exampleProject, examplePackages } from '@/testing/fixtures'

const towerPath = 'centities/tower/centity.json'

/** Game data with what a template may ask for: the data pack format a new datapack is for. */
const TEMPLATE_GAME: GameDataBundle = { minecraft: '26.3', dataPackFormat: [121, 0] }

const filesOf = (made: ReturnType<typeof newResourceFiles>) => {
  if ('needsGame' in made) throw new Error(made.needsGame)
  return made.files
}

describe('format wrapper', () => {
  it('round-trips an example byte for byte', () => {
    const text = exampleFiles[towerPath]!
    const result = canonicalize('centity', towerPath, text)
    expect(result.problems).toEqual([])
    expect(result.text).toBe(text)
  })

  it('writes a model edited in memory canonically', () => {
    const model = JSON.parse(exampleFiles[towerPath]!) as CentityFile
    // Built in a different key order.
    const { root, ...rest } = model.nodes!
    const reordered: CentityFile = {
      script: model.script,
      nodes: { root: root!, ...rest },
      name: model.name,
      animations: model.animations,
    }
    expect(canonicalizeModel('centity', towerPath, reordered).text).toBe(exampleFiles[towerPath])
  })

  it('reports a parse problem with its line', () => {
    const result = canonicalize('centity', towerPath, '{\n  "nodes": { "a": { "bogus": 1 } }\n}')
    expect(result.text).toBeUndefined()
    expect(result.problems[0]).toMatchObject({ severity: 'error', file: towerPath, line: 2 })
  })

  it('validates a project and finds a missing script', () => {
    const files: Record<string, string | null> = {}
    for (const [path, text] of Object.entries(exampleProject)) {
      files[path] = isProjectJson(path) ? (text as string) : null
    }
    expect(loadProject(files, null, examplePackages).problems).toEqual([])

    delete files['centities/tower/script.lua']
    const problems = loadProject(files, null, examplePackages).problems
    expect(problems.map((it) => it.code)).toContain('script.missing')
  })

  it('poses a centity through the shared composer', () => {
    const pose = poseCentity('tower', exampleFiles[towerPath]!, null, 0)
    if (pose.type !== 'posed') throw new Error('expected a pose')
    const cap = pose.nodes.find((node) => node.name === 'cap')!
    expect(cap.parent).toBe('turret')
    // its translation [-0.5, 0, -0.5], under the turret's [0, 2, 0], lands in the matrix's last column
    expect(cap.matrix.slice(12, 15)).toEqual([-0.5, 2, -0.5])
    expect(pose.animations.map((it) => it.name)).toEqual(['flutter', 'spin'])
  })

  it('refuses to pose a centity with errors', () => {
    const pose = poseCentity('x', '{"nodes":{"a":{"parent":"b"}}}', null, 0)
    expect(pose.type).toBe('failed')
  })

  it('creates canonical templates', () => {
    const project = newProjectFiles('Test', '26.3')
    expect(Object.keys(project)).toContain('netherforge.json')
    const centity = filesOf(newResourceFiles('centity', 'crate', null))
    const text = centity['centities/crate/centity.json']!
    expect(canonicalize('centity', 'centities/crate/centity.json', text).text).toBe(text)
  })

  it('knows which paths it owns', () => {
    expect(documentKindOf('netherforge.json')).toBe('netherforge')
    expect(documentKindOf(towerPath)).toBe('centity')
    expect(documentKindOf('centities/tower/script.lua')).toBeNull()
    expect(documentKindOf('centities/tower/extra/centity.json')).toBeNull()
  })

  it('knows every document kind by path', () => {
    expect(documentKindOf('menus/shop/menu.json')).toBe('menu')
    expect(documentKindOf('dialogs/welcome/dialog.json')).toBe('dialog')
    expect(documentKindOf('resource_packs/ui/pack.json')).toBe('resource_pack')
    expect(documentKindOf('particles/sparkle/effect.json')).toBe('particle_effect')
    expect(documentKindOf('cutscenes/intro.json')).toBe('cutscene')
    expect(documentKindOf('cutscenes/old/intro.json')).toBeNull()
    expect(documentKindOf('terrain/hills.json')).toBe('terrain')
    expect(documentKindOf('terrain/old/hills.json')).toBeNull()
    expect(documentKindOf('fonts/default.json')).toBe('default_font')
    expect(documentKindOf('resource_packs/ui/textures/pack.json')).toBeNull()
    expect(documentKindOf('menus/shop/dialog.json')).toBeNull()
    expect(documentKindOf('items/ruby/item.json')).toBe('item')
    expect(documentKindOf('recipes/ruby_sword.json')).toBe('recipe')
    expect(documentKindOf('recipes/.json')).toBeNull()
    expect(documentKindOf('recipes/swords/ruby.json')).toBeNull()
  })

  it('round-trips the new kinds and compiles packs', () => {
    for (const path of [
      'menus/shop/menu.json',
      'dialogs/welcome/dialog.json',
      'resource_packs/ui/pack.json',
      'particles/shockwave/effect.json',
    ]) {
      const kind = documentKindOf(path)!
      expect(canonicalize(kind, path, exampleFiles[path]!).text).toBe(exampleFiles[path])
    }
    const unmeasured = compileResourcePacks('basic', {
      ui: exampleFiles['resource_packs/ui/pack.json']!,
    }).resourcePacks.ui!
    expect(unmeasured.skins.shop!.height).toBe(168)
    expect(unmeasured.skins.shop!.advance).toBeUndefined()
    // Without pixels there's no way back: the prefix ends with the picture.
    expect(unmeasured.skins.shop!.titlePrefix.endsWith(unmeasured.skins.shop!.char)).toBe(true)
    const compiled = compileResourcePacks(
      'basic',
      { ui: exampleFiles['resource_packs/ui/pack.json']! },
      {
        'resource_packs/ui/textures/gui/shop.png': { width: 176, height: 168, opaqueWidth: 176 },
        'resource_packs/ui/textures/glyph/coin.png': { width: 8, height: 8, opaqueWidth: 7 },
      },
    )
    const shop = compiled.resourcePacks.ui!.skins.shop!
    expect(shop).toMatchObject({
      height: 168,
      ascent: 13,
      offset: -8,
      advance: 177,
      texture: 'gui/shop.png',
    })
    expect(shop.titlePrefix.endsWith(shop.char)).toBe(false)
    expect(compiled.resourcePacks.ui!.glyphs.coin!).toMatchObject({ advance: 8 })
    expect(compiled.resourcePacks.ui!.glyphs.coin!.char).toHaveLength(1)
    expect(compileResourcePacks('basic', { bad: '{' }).problems).toHaveLength(1)
  })

  it('writes a template for every kind that has one, found where the kind table says', () => {
    for (const kind of Object.keys(KINDS) as (keyof typeof KINDS)[]) {
      const where = KINDS[kind]
      if (!where.template) continue
      const files = filesOf(
        newResourceFiles(kind as Parameters<typeof newResourceFiles>[0], 'sample', TEMPLATE_GAME),
      )
      for (const path of Object.keys(files)) {
        expect(classify(path)).toMatchObject({ kind, id: 'sample' })
      }
      if (where.contents === 'json')
        expect(Object.keys(files)).toEqual([mainFileOf(kind, 'sample')])
    }
    expect(Object.keys(filesOf(newResourceFiles('recipe', 'torch', null)))).toEqual([
      'recipes/torch.json',
    ])
    expect(Object.keys(filesOf(newResourceFiles('module', 'greeter', null)))).toEqual([
      'modules/greeter/init.lua',
    ])
    // A datapack's pack.mcmeta names the server's data pack format, which only game data knows.
    expect(newResourceFiles('datapack', 'trees', null)).toEqual({
      needsGame: expect.stringContaining('start the dev server once') as unknown,
    })
    const pack = filesOf(newResourceFiles('datapack', 'trees', TEMPLATE_GAME))
    expect(JSON.parse(pack['datapacks/trees/pack.mcmeta']!)).toMatchObject({
      pack: { min_format: 121, max_format: 121 },
    })
    // An item script says what `this` is, for lua-language-server.
    expect(newScript('item', '')).toMatch(/^local this = this --\[\[@as ProjectItem\]\]\n/)
  })

  it('steps a particle effect through the server sampler', () => {
    const text = exampleFiles['particles/shockwave/effect.json']!
    const sampler = particleEffectSampler('shockwave', text, 1)
    const first = sampler.step(false)
    if (first.type !== 'stepped') throw new Error('expected spawns')
    expect(first.tick).toBe(0)
    // Tick 0: the flash, 48 ring points and 20 pieces of debris (sparks start at tick 2).
    const counts: Record<string, number> = {}
    for (const spawn of first.spawns) counts[spawn.emitter] = (counts[spawn.emitter] ?? 0) + 1
    expect(counts).toEqual({ debris: 20, flash: 1, ring: 48 })
    expect(first.radii.ring).toBe(0.5)
    // A replay from the same seed draws the same points.
    sampler.reset()
    expect(sampler.step(false)).toEqual(first)
    // And steps on to the end.
    let last: EffectStepResult = first
    for (let i = 1; i < 30; i += 1) last = sampler.step(false)
    expect(last).toMatchObject({ tick: 29, finished: true })
  })

  it('reports why an effect with errors cannot be sampled', () => {
    const result = particleEffectSampler('bad', '{"duration": 0}', 1).step(false)
    expect(result.type).toBe('failed')
    if (result.type === 'failed')
      expect(result.problems.map((it) => it.code)).toContain('particle.duration')
  })

  it('reads a curve the way the sampler does', () => {
    const keys = [
      { time: 0, value: 0 },
      { time: 10, value: 10, easing: 'step' as const },
      { time: 20, value: 20 },
    ]
    expect(particleCurveAt(keys, 5)).toBe(5)
    expect(particleCurveAt(keys, 15)).toBe(10)
    expect(particleCurveAt(keys, 99)).toBe(20)
    expect(
      particleCurveAt(
        [
          { time: 0, color: '#000000' },
          { time: 2, color: '#ffffff' },
        ],
        1,
      ),
    ).toBe('#808080')
    expect(particleCurveAt([] as { time: number; value: number }[], 1)).toBeNull()
  })
})
