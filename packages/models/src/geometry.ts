/**
 * Resolved model elements → three.js geometry, following Minecraft's baking
 * closely enough that the viewport matches what a player sees: per-face UVs
 * (explicit or derived from the element's extents), face UV rotation, element
 * rotation with rescale, the blockstate's x/y rotation, and vanilla's fixed
 * per-direction shading baked into vertex colours.
 *
 * Model space is 0–16 with y up; output is divided by 16 so a block is one
 * unit with its corner at the origin, exactly the cube a block display fills.
 * Faces are grouped by texture: one draw call per texture.
 */
import * as THREE from 'three'
import {
  FACE_NAMES,
  resolveTexture,
  splitId,
  type DisplayEntry,
  type FaceName,
  type Placement,
  type RawElement,
  type V3,
} from './model.ts'

const SHADE: Record<FaceName, number> = {
  down: 0.5,
  up: 1,
  north: 0.8,
  south: 0.8,
  west: 0.6,
  east: 0.6,
}

/** A face's corners in the order Minecraft assigns UVs: top-left seen from outside, anticlockwise. */
function corners(face: FaceName, from: V3, to: V3): [V3, V3, V3, V3] {
  const [x1, y1, z1] = from
  const [x2, y2, z2] = to
  switch (face) {
    case 'down':
      return [
        [x1, y1, z2],
        [x1, y1, z1],
        [x2, y1, z1],
        [x2, y1, z2],
      ]
    case 'up':
      return [
        [x1, y2, z1],
        [x1, y2, z2],
        [x2, y2, z2],
        [x2, y2, z1],
      ]
    case 'north':
      return [
        [x2, y2, z1],
        [x2, y1, z1],
        [x1, y1, z1],
        [x1, y2, z1],
      ]
    case 'south':
      return [
        [x1, y2, z2],
        [x1, y1, z2],
        [x2, y1, z2],
        [x2, y2, z2],
      ]
    case 'west':
      return [
        [x1, y2, z1],
        [x1, y1, z1],
        [x1, y1, z2],
        [x1, y2, z2],
      ]
    case 'east':
      return [
        [x2, y2, z2],
        [x2, y1, z2],
        [x2, y1, z1],
        [x2, y2, z1],
      ]
  }
}

/** The UV rectangle of a face that declares none: the element's extents projected onto it. */
function defaultUv(face: FaceName, from: V3, to: V3): [number, number, number, number] {
  const [x1, y1, z1] = from
  const [x2, y2, z2] = to
  switch (face) {
    case 'down':
      return [x1, 16 - z2, x2, 16 - z1]
    case 'up':
      return [x1, z1, x2, z2]
    case 'north':
      return [16 - x2, 16 - y2, 16 - x1, 16 - y1]
    case 'south':
      return [x1, 16 - y2, x2, 16 - y1]
    case 'west':
      return [z1, 16 - y2, z2, 16 - y1]
    case 'east':
      return [16 - z2, 16 - y2, 16 - z1, 16 - y1]
  }
}

function elementMatrix(element: RawElement): THREE.Matrix4 | null {
  const rotation = element.rotation
  if (!rotation) return null
  const origin = new THREE.Vector3(...rotation.origin)
  if (rotation.axis === undefined) {
    // Angles about all three axes, turned X, then Y, then Z.
    const [x, y, z] = [rotation.x ?? 0, rotation.y ?? 0, rotation.z ?? 0]
    if (!x && !y && !z) return null
    const euler = new THREE.Euler(
      THREE.MathUtils.degToRad(x),
      THREE.MathUtils.degToRad(y),
      THREE.MathUtils.degToRad(z),
      'XYZ',
    )
    return new THREE.Matrix4()
      .makeTranslation(origin.x, origin.y, origin.z)
      .multiply(new THREE.Matrix4().makeRotationFromEuler(euler))
      .multiply(new THREE.Matrix4().makeTranslation(-origin.x, -origin.y, -origin.z))
  }
  if (!rotation.angle) return null
  const radians = THREE.MathUtils.degToRad(rotation.angle)
  const axis = new THREE.Vector3(
    rotation.axis === 'x' ? 1 : 0,
    rotation.axis === 'y' ? 1 : 0,
    rotation.axis === 'z' ? 1 : 0,
  )
  const matrix = new THREE.Matrix4().makeTranslation(origin.x, origin.y, origin.z)
  matrix.multiply(new THREE.Matrix4().makeRotationAxis(axis, radians))
  if (rotation.rescale) {
    const factor = 1 / Math.cos(radians)
    matrix.multiply(
      new THREE.Matrix4().makeScale(
        rotation.axis === 'x' ? 1 : factor,
        rotation.axis === 'y' ? 1 : factor,
        rotation.axis === 'z' ? 1 : factor,
      ),
    )
  }
  matrix.multiply(new THREE.Matrix4().makeTranslation(-origin.x, -origin.y, -origin.z))
  return matrix
}

/** A blockstate's x then y rotation, clockwise about the block centre (same as `boxes.placeCorner`). */
export function placementMatrix(placement: Pick<Placement, 'x' | 'y'>): THREE.Matrix4 | null {
  if (!placement.x && !placement.y) return null
  const matrix = new THREE.Matrix4().makeTranslation(8, 8, 8)
  matrix.multiply(new THREE.Matrix4().makeRotationY(THREE.MathUtils.degToRad(-placement.y)))
  matrix.multiply(new THREE.Matrix4().makeRotationX(THREE.MathUtils.degToRad(-placement.x)))
  matrix.multiply(new THREE.Matrix4().makeTranslation(-8, -8, -8))
  return matrix
}

export interface BuiltGroup {
  /** Texture id, `minecraft:block/stone`. */
  texture: string
  geometry: THREE.BufferGeometry
}

interface Buffer {
  positions: number[]
  uvs: number[]
  colors: number[]
  indices: number[]
}

/** One baked quad, in block units (a block is 0–1), its corners in UV order. */
export interface BakedFace {
  texture: string
  /** Four corners, xyz each. */
  positions: number[]
  /** Four corners, uv each (three's convention: v up). */
  uvs: number[]
  /** Four corners, rgb each: vanilla's directional shade times any tint. */
  colors: number[]
  /** The side a neighbouring block hides it from, after the blockstate's rotation; null when none can. */
  cull: FaceName | null
  /** The side of the block this face covers completely, if it lies on one (what hides a neighbour's face). */
  covers: FaceName | null
}

const DIRECTIONS: Record<FaceName, V3> = {
  down: [0, -1, 0],
  up: [0, 1, 0],
  north: [0, 0, -1],
  south: [0, 0, 1],
  west: [-1, 0, 0],
  east: [1, 0, 0],
}

/** Where [face] points once [transform]'s rotation is applied. */
function turned(face: FaceName, transform: THREE.Matrix4 | null | undefined): FaceName {
  if (!transform) return face
  const direction = new THREE.Vector3(...DIRECTIONS[face]).transformDirection(transform)
  let best: FaceName = face
  let score = -Infinity
  for (const name of FACE_NAMES) {
    const [x, y, z] = DIRECTIONS[name]
    const dot = direction.x * x + direction.y * y + direction.z * z
    if (dot > score) {
      score = dot
      best = name
    }
  }
  return best
}

const EPSILON = 1e-4

/** The side a quad (block units) covers completely, facing [face]: the whole 1×1 square on the block's boundary. */
function coveredSide(face: FaceName, positions: number[]): FaceName | null {
  const axis = DIRECTIONS[face].findIndex((it) => it !== 0)
  const boundary = DIRECTIONS[face][axis]! > 0 ? 1 : 0
  const others = [0, 1, 2].filter((it) => it !== axis)
  for (let corner = 0; corner < 4; corner += 1) {
    if (Math.abs(positions[corner * 3 + axis]! - boundary) > EPSILON) return null
  }
  for (const other of others) {
    const values = [0, 1, 2, 3].map((corner) => positions[corner * 3 + other]!)
    if (Math.abs(Math.min(...values)) > EPSILON || Math.abs(Math.max(...values) - 1) > EPSILON)
      return null
  }
  return face
}

/**
 * Every face of [elements] baked into block units: UVs, rotation, the
 * element's and the blockstate's ([transform]) rotations, shade and tint.
 */
export function bakeFaces(
  elements: RawElement[],
  textures: Record<string, string>,
  options: { tint?: THREE.Color; transform?: THREE.Matrix4 | null } = {},
): BakedFace[] {
  const faces: BakedFace[] = []
  const vertex = new THREE.Vector3()
  for (const element of elements) {
    if (!element.from || !element.to) continue
    const local = elementMatrix(element)
    const shaded = element.shade !== false
    for (const face of FACE_NAMES) {
      const definition = element.faces?.[face]
      if (!definition) continue
      const texture = resolveTexture(definition.texture, textures)
      if (!texture) continue
      const quad = corners(face, element.from, element.to)
      const uv = definition.uv ?? defaultUv(face, element.from, element.to)
      const steps = ((((definition.rotation ?? 0) / 90) % 4) + 4) % 4
      const uvCorners: [number, number][] = [
        [uv[0], uv[1]],
        [uv[0], uv[3]],
        [uv[2], uv[3]],
        [uv[2], uv[1]],
      ]
      const brightness = shaded ? SHADE[face] : 1
      const tint =
        definition.tintindex !== undefined && definition.tintindex >= 0 ? options.tint : undefined
      const baked: BakedFace = {
        texture,
        positions: [],
        uvs: [],
        colors: [],
        cull: null,
        covers: null,
      }
      for (let corner = 0; corner < 4; corner += 1) {
        vertex.set(...quad[corner]!)
        if (local) vertex.applyMatrix4(local)
        if (options.transform) vertex.applyMatrix4(options.transform)
        baked.positions.push(vertex.x / 16, vertex.y / 16, vertex.z / 16)
        const [u, v] = uvCorners[(corner + steps) % 4]!
        // Minecraft's V runs down from the top of the image; three's runs up.
        baked.uvs.push(u / 16, 1 - v / 16)
        baked.colors.push(
          brightness * (tint?.r ?? 1),
          brightness * (tint?.g ?? 1),
          brightness * (tint?.b ?? 1),
        )
      }
      const cullface = definition.cullface as FaceName | undefined
      if (cullface && cullface in DIRECTIONS) baked.cull = turned(cullface, options.transform)
      if (!local) baked.covers = coveredSide(turned(face, options.transform), baked.positions)
      faces.push(baked)
    }
  }
  return faces
}

export function buildElements(
  elements: RawElement[],
  textures: Record<string, string>,
  options: { tint?: THREE.Color; transform?: THREE.Matrix4 | null } = {},
): BuiltGroup[] {
  const buffers = new Map<string, Buffer>()
  for (const face of bakeFaces(elements, textures, options)) {
    let buffer = buffers.get(face.texture)
    if (!buffer) {
      buffer = { positions: [], uvs: [], colors: [], indices: [] }
      buffers.set(face.texture, buffer)
    }
    const base = buffer.positions.length / 3
    buffer.positions.push(...face.positions)
    buffer.uvs.push(...face.uvs)
    buffer.colors.push(...face.colors)
    buffer.indices.push(base, base + 1, base + 2, base, base + 2, base + 3)
  }
  return [...buffers.entries()].map(([texture, buffer]) => {
    const geometry = new THREE.BufferGeometry()
    geometry.setAttribute('position', new THREE.Float32BufferAttribute(buffer.positions, 3))
    geometry.setAttribute('uv', new THREE.Float32BufferAttribute(buffer.uvs, 2))
    geometry.setAttribute('color', new THREE.Float32BufferAttribute(buffer.colors, 3))
    geometry.setIndex(buffer.indices)
    geometry.computeVertexNormals()
    return { texture, geometry }
  })
}

/** A sprite item as a flat one-pixel-thick quad, front and back. */
export function buildSprite(layers: string[]): BuiltGroup[] {
  return layers.flatMap((texture) =>
    buildElements(
      [
        {
          from: [0, 0, 7.5],
          to: [16, 16, 8.5],
          shade: false,
          faces: {
            south: { texture, uv: [0, 0, 16, 16] },
            north: { texture, uv: [16, 0, 0, 16] },
          },
        },
      ],
      {},
    ),
  )
}

/**
 * Tint for faces with a `tintindex` (grass, leaves, water). The real colour
 * depends on the biome; the editor pretends to be in plains. These are
 * colours, not game facts about any particular block.
 */
const TINTS = {
  grass: new THREE.Color(0x91bd59),
  foliage: new THREE.Color(0x77ab2f),
  water: new THREE.Color(0x3f76e4),
}

export function tintFor(id: string): THREE.Color {
  const { path } = splitId(id)
  if (path.includes('water')) return TINTS.water
  if (path.includes('leaves') || path.includes('vine')) return TINTS.foliage
  return TINTS.grass
}

/**
 * Vanilla applies the display transform about the model centre, then draws
 * the item centred on the entity: the transform, then a half-block shift.
 */
export function displayMatrix(entry: DisplayEntry | undefined): THREE.Matrix4 {
  const matrix = new THREE.Matrix4()
  if (entry) {
    const [tx, ty, tz] = entry.translation ?? [0, 0, 0]
    const [rx, ry, rz] = entry.rotation ?? [0, 0, 0]
    const [sx, sy, sz] = entry.scale ?? [1, 1, 1]
    matrix.compose(
      new THREE.Vector3(tx / 16, ty / 16, tz / 16),
      new THREE.Quaternion().setFromEuler(
        new THREE.Euler(
          THREE.MathUtils.degToRad(rx),
          THREE.MathUtils.degToRad(ry),
          THREE.MathUtils.degToRad(rz),
          'XYZ',
        ),
      ),
      new THREE.Vector3(sx, sy, sz),
    )
  }
  return matrix.multiply(new THREE.Matrix4().makeTranslation(-0.5, -0.5, -0.5))
}
