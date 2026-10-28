/**
 * The preview's stand-in for Minecraft's particle engine, which the editor
 * can't run. The points are real (format's sampler, the server's own code);
 * what happens to a particle after it spawns is an approximation: `count`
 * sprites per spawn scattered with a Gaussian of σ = spread, a velocity
 * (`offset * speed` for an exact spawn, a random direction times `speed`
 * otherwise), a 20-tick life with drag 0.96 per tick, fading over its last 5.
 *
 * Pure and seeded, so scrubbing to a tick always shows the same thing: the
 * [Simulator] replays from tick 0 whenever it has to go back.
 */
import type { EffectSpawn, EffectStepResult, Problem, SpawnData, Vec3 } from '@/core/format'

/** Ticks a sprite lives. */
export const LIFETIME = 20
/** Ticks it fades over, at the end of its life. */
export const FADE = 5
/** Velocity kept per tick. */
export const DRAG = 0.96

export interface Sprite {
  emitter: string
  particle: string
  data: SpawnData | null
  position: Vec3
  velocity: Vec3
  /** Ticks since it spawned. */
  age: number
}

/** A small seeded generator (mulberry32): the same sprites on every replay. */
export function seededRandom(seed: number): () => number {
  let state = seed >>> 0
  return () => {
    state = (state + 0x6d2b79f5) >>> 0
    let t = state
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/** A standard normal sample (Box–Muller). */
function gaussian(random: () => number): number {
  const u = Math.max(random(), Number.EPSILON)
  return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * random())
}

/** A uniformly random unit vector. */
function direction(random: () => number): Vec3 {
  const y = 2 * random() - 1
  const angle = random() * 2 * Math.PI
  const r = Math.sqrt(1 - y * y)
  return [r * Math.cos(angle), y, r * Math.sin(angle)]
}

/** The sprites one spawn makes. */
export function spritesOf(spawn: EffectSpawn, random: () => number): Sprite[] {
  const base = {
    emitter: spawn.emitter,
    particle: spawn.particle,
    data: spawn.data ?? null,
    age: 0,
  }
  if (spawn.count === 0) {
    const [x, y, z] = spawn.offset
    return [
      {
        ...base,
        position: [...spawn.position],
        velocity: [x * spawn.speed, y * spawn.speed, z * spawn.speed],
      },
    ]
  }
  const out: Sprite[] = []
  for (let i = 0; i < spawn.count; i += 1) {
    const [dx, dy, dz] = direction(random)
    out.push({
      ...base,
      position: [
        spawn.position[0] + gaussian(random) * spawn.offset[0],
        spawn.position[1] + gaussian(random) * spawn.offset[1],
        spawn.position[2] + gaussian(random) * spawn.offset[2],
      ],
      velocity: [dx * spawn.speed, dy * spawn.speed, dz * spawn.speed],
    })
  }
  return out
}

/** One tick later: moved by its velocity, slowed by drag, older; gone at the end of its life. */
export function advance(sprites: Sprite[]): Sprite[] {
  const out: Sprite[] = []
  for (const sprite of sprites) {
    if (sprite.age + 1 >= LIFETIME) continue
    const [x, y, z] = sprite.position
    const [vx, vy, vz] = sprite.velocity
    out.push({
      ...sprite,
      position: [x + vx, y + vy, z + vz],
      velocity: [vx * DRAG, vy * DRAG, vz * DRAG],
      age: sprite.age + 1,
    })
  }
  return out
}

/** How visible a sprite is: 1, falling to 0 over its last [FADE] ticks. */
export const opacity = (sprite: Sprite) => Math.min(1, (LIFETIME - sprite.age) / FADE)

/** What a sampler gives the simulator: format's `ParticleEffectSampler`. */
export interface StepSource {
  step(loop: boolean): EffectStepResult
  reset(): void
}

export interface Frame {
  /** Ticks since the preview started (with loop on, past the effect's duration). */
  tick: number
  sprites: Sprite[]
  /** Each ring, disc and sphere emitter's radius at the last tick sampled. */
  radii: Record<string, number>
  /** Why the effect can't be sampled, when it can't. */
  problems: Problem[]
}

/**
 * Runs the preview forwards one tick at a time and remembers where it got
 * to, so playing is a step per tick; asking for an earlier tick (a seek, a
 * loop toggle) replays from tick 0.
 */
export class Simulator {
  private frame: Frame = { tick: -1, sprites: [], radii: {}, problems: [] }
  private random = seededRandom(1)
  private loop = false

  constructor(private readonly source: StepSource) {}

  /** The sprites alive at [tick], after that tick's spawns. */
  at(tick: number, loop: boolean): Frame {
    if (tick < this.frame.tick || loop !== this.loop) this.restart(loop)
    while (this.frame.tick < tick) this.step()
    return this.frame
  }

  private restart(loop: boolean) {
    this.source.reset()
    this.random = seededRandom(1)
    this.loop = loop
    this.frame = { tick: -1, sprites: [], radii: {}, problems: [] }
  }

  private step() {
    const result = this.source.step(this.loop)
    const sprites = advance(this.frame.sprites)
    if (result.type === 'failed') {
      this.frame = { tick: this.frame.tick + 1, sprites, radii: {}, problems: result.problems }
      return
    }
    for (const spawn of result.spawns) sprites.push(...spritesOf(spawn, this.random))
    this.frame = { tick: this.frame.tick + 1, sprites, radii: result.radii, problems: [] }
  }
}
