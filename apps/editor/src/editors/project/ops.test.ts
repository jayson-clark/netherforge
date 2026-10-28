import { describe, expect, it } from 'vitest'
import { canonicalizeModel, type ProjectManifest } from '@/core/format'
import {
  addManagedWorld,
  addPermission,
  addRequired,
  removeManagedWorld,
  removePermission,
  removeRequired,
  removeWorldSpawn,
  setRequired,
  setWorldSpawn,
} from './ops'

const manifest = (): ProjectManifest => ({
  formatVersion: 1,
  name: 'Test',
  namespace: 'test',
  version: '1.0.0',
  minecraft: '26.3',
})
const canonical = (model: ProjectManifest) =>
  canonicalizeModel('netherforge', 'netherforge.json', model).text!

describe('project settings', () => {
  it('keeps managed worlds in name order, and drops the key once the list is empty', () => {
    const model = manifest()
    addManagedWorld(model, 'lobby')
    addManagedWorld(model, 'arena')
    addManagedWorld(model, 'lobby')
    expect(model.managedWorlds).toEqual(['arena', 'lobby'])
    // What the editor holds is what format writes: saving it changes nothing.
    expect(JSON.parse(canonical(model)).managedWorlds).toEqual(['arena', 'lobby'])
    removeManagedWorld(model, 'arena')
    removeManagedWorld(model, 'lobby')
    expect('managedWorlds' in model).toBe(false)
    expect(canonical(model)).not.toContain('managedWorlds')
  })

  it('keeps grantable permissions in order without repeats, as format writes them', () => {
    const model = manifest()
    addPermission(model, 'shop.vip')
    addPermission(model, 'arena')
    addPermission(model, 'shop')
    addPermission(model, 'shop')
    expect(model.allow).toEqual({ permissions: ['arena', 'shop', 'shop.vip'] })
    expect(JSON.parse(canonical(model)).allow).toEqual(model.allow)
  })

  it('writes a required flag only as true, and drops requires once it says nothing', () => {
    const model = manifest()
    setRequired(model, 'moderation', false)
    expect('requires' in model).toBe(false)
    setRequired(model, 'moderation', true)
    setRequired(model, 'db', true)
    expect(model.requires).toEqual({ moderation: true, db: true })
    expect(JSON.parse(canonical(model)).requires).toEqual({ moderation: true, db: true })
    setRequired(model, 'moderation', false)
    setRequired(model, 'db', false)
    expect('requires' in model).toBe(false)
    expect(canonical(model)).not.toContain('requires')
  })

  it('keeps required hosts and plugins in order without repeats, as format writes them', () => {
    const model = manifest()
    addRequired(model, 'http', 'discord.com')
    addRequired(model, 'http', '*.example.com')
    addRequired(model, 'http', 'discord.com')
    addRequired(model, 'plugins', 'vault')
    expect(model.requires).toEqual({ http: ['*.example.com', 'discord.com'], plugins: ['vault'] })
    expect(JSON.parse(canonical(model)).requires).toEqual(model.requires)
    removeRequired(model, 'plugins', 'vault')
    removeRequired(model, 'http', '*.example.com')
    expect(model.requires).toEqual({ http: ['discord.com'] })
    removeRequired(model, 'http', 'discord.com')
    expect('requires' in model).toBe(false)
  })

  it('drops permissions once the list is empty, and allow with it', () => {
    const model = manifest()
    addPermission(model, 'shop')
    removePermission(model, 'shop')
    expect('allow' in model).toBe(false)
    removePermission(model, 'shop')
    expect('allow' in model).toBe(false)
    expect(canonical(model)).not.toContain('allow')
  })

  it('keeps worlds and categories in the order format writes them, as whole numbers of 0 or more', () => {
    const model = manifest()
    setWorldSpawn(model, 'lobby', 'spawnLimits', 'ambient', 5.6)
    setWorldSpawn(model, 'arena', 'spawnLimits', 'monster', -3)
    setWorldSpawn(model, 'lobby', 'spawnLimits', 'animal', 10)
    setWorldSpawn(model, 'lobby', 'spawnIntervals', 'monster', 400)
    expect(Object.keys(model.worlds!)).toEqual(['arena', 'lobby'])
    expect(Object.keys(model.worlds!.lobby!.spawnLimits!)).toEqual(['animal', 'ambient'])
    expect(model.worlds!.lobby!.spawnLimits).toEqual({ animal: 10, ambient: 6 })
    expect(model.worlds!.arena!.spawnLimits).toEqual({ monster: 0 })
    // Saving what the editor holds changes nothing.
    expect(JSON.parse(canonical(model)).worlds).toEqual(model.worlds)
  })

  it('drops a spawn map, a world and worlds as they become empty', () => {
    const model = manifest()
    setWorldSpawn(model, 'arena', 'spawnLimits', 'monster', 0)
    setWorldSpawn(model, 'arena', 'spawnIntervals', 'animal', 400)
    setWorldSpawn(model, 'arena', 'spawnLimits', 'monster', undefined)
    expect(model.worlds).toEqual({ arena: { spawnIntervals: { animal: 400 } } })
    setWorldSpawn(model, 'arena', 'spawnIntervals', 'animal', undefined)
    expect('worlds' in model).toBe(false)
    setWorldSpawn(model, 'arena', 'spawnIntervals', 'animal', undefined)
    expect('worlds' in model).toBe(false)
    setWorldSpawn(model, 'arena', 'spawnLimits', 'monster', 1)
    setWorldSpawn(model, 'lobby', 'spawnLimits', 'monster', 1)
    removeWorldSpawn(model, 'arena')
    expect(Object.keys(model.worlds!)).toEqual(['lobby'])
    removeWorldSpawn(model, 'lobby')
    expect('worlds' in model).toBe(false)
    expect(canonical(model)).not.toContain('worlds')
  })
})
