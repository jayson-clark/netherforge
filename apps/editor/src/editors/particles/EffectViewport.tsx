/**
 * The 3D preview of a particle effect, in the shared viewport (`Viewport3D`): the simulator's sprites (format's
 * real spawn points, moved approximately) as camera-facing quads, one
 * instanced mesh per emitter, each textured with its particle's sprite from
 * the client import and tinted by its colour; a soft dot without an import.
 * The selected emitter's shape is outlined at the playhead.
 */
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useFrame } from '@react-three/fiber'
import { Line } from '@react-three/drei'
import * as THREE from 'three'
import type { EmitterDef, ParticleEffectFile, SpawnData } from '@/core/format'
import type { ClientAssets } from '@/minecraft/client/assets'
import { parseBlockState } from '@netherforge/models/model'
import { loadTexture } from '@/minecraft/client/render'
import { useClientAssets } from '@/minecraft/client/useClientAssets'
import { useWorkspace } from '@/state/providers'
import { ViewportNote } from '@/editors/shared/viewport/parts'
import { BLOCK_GRID } from '@/editors/shared/viewport/scenes'
import { Viewport3D } from '@/editors/shared/viewport/Viewport3D'
import { shapeOf } from './ops'
import { opacity, type Frame, type Sprite } from './simulate'
import { sizeOf, spriteTexture, tintOf } from './sprites'

export function EffectViewport({
  path,
  model,
  frame,
  selected,
  tick,
}: {
  path: string
  model: ParticleEffectFile
  frame: Frame | null
  selected: string | null
  /** The timeline tick the playhead is on, for the selected emitter's outline. */
  tick: number
}) {
  const assets = useClientAssets()
  const minecraft = useWorkspace((s) => s.minecraft)

  return (
    <Viewport3D
      kind="particle_effect"
      path={path}
      label="Particle preview"
      scene={{
        content: (
          <EffectScene
            model={model}
            frame={frame}
            selected={selected}
            tick={tick}
            assets={assets}
          />
        ),
        home: { position: [4, 3, 5], target: [0, 0.75, 0] },
        grid: BLOCK_GRID,
      }}
    >
      {(frame?.problems.length ?? 0) > 0 && (
        <ViewportNote warning role="note">
          This effect has errors, so nothing plays. See Problems.
        </ViewportNote>
      )}
      <ViewportNote role="note">
        Preview: lifetimes and motion are approximate. Play on server for the real thing.
        {!assets &&
          ` Particles show as dots until Minecraft ${minecraft ?? ''} client assets are imported.`}
      </ViewportNote>
    </Viewport3D>
  )
}

/** The simulator's sprites, an instanced mesh per emitter, and the selected emitter's shape. */
function EffectScene({
  model,
  frame,
  selected,
  tick,
  assets,
}: {
  model: ParticleEffectFile
  frame: Frame | null
  selected: string | null
  tick: number
  assets: ClientAssets | null
}) {
  const groups = useMemo(() => {
    const out = new Map<string, Sprite[]>()
    for (const sprite of frame?.sprites ?? []) {
      const list = out.get(sprite.emitter)
      if (list) list.push(sprite)
      else out.set(sprite.emitter, [sprite])
    }
    return [...out]
  }, [frame])
  const emitter = selected ? model.emitters?.[selected] : undefined
  return (
    <>
      {groups.map(([name, list]) => (
        <SpriteBatch key={name} sprites={list} assets={assets} />
      ))}
      {emitter && selected && (
        <ShapeOutline emitter={emitter} radius={frame?.radii[selected]} tick={tick} />
      )}
    </>
  )
}

/** A soft white dot, for particles whose sprite isn't known. */
let dot: THREE.Texture | null = null
function softDot(): THREE.Texture {
  if (dot) return dot
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = 32
  const context = canvas.getContext('2d')
  if (context) {
    const gradient = context.createRadialGradient(16, 16, 0, 16, 16, 16)
    gradient.addColorStop(0, 'rgba(255,255,255,1)')
    gradient.addColorStop(0.5, 'rgba(255,255,255,0.6)')
    gradient.addColorStop(1, 'rgba(255,255,255,0)')
    context.fillStyle = gradient
    context.fillRect(0, 0, 32, 32)
  }
  dot = new THREE.CanvasTexture(canvas)
  return dot
}

/** The sprite texture id for one emitter's spawns, once the assets have said. */
function useSprite(assets: ClientAssets | null, particle: string, data: SpawnData | null) {
  const defaults = useWorkspace((s) =>
    data?.type === 'block'
      ? s.gameData?.blocks?.[parseBlockState(data.state).id]?.defaults
      : undefined,
  )
  const key = `${assets?.key ?? 'none'}|${particle}|${data?.type === 'block' ? data.state : data?.type === 'item' ? data.item.kind : ''}`
  const [state, setState] = useState<{ key: string; texture: string | null } | null>(null)
  useEffect(() => {
    if (!assets) return
    let live = true
    spriteTexture(assets, particle, data, defaults).then(
      (texture) => live && setState({ key, texture }),
      () => live && setState({ key, texture: null }),
    )
    return () => {
      live = false
    }
    // `key` describes the request.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])
  return state?.key === key ? state.texture : null
}

const scratch = new THREE.Object3D()
const tint = new THREE.Color()

/** One emitter's sprites as camera-facing quads in one instanced mesh. */
function SpriteBatch({ sprites, assets }: { sprites: Sprite[]; assets: ClientAssets | null }) {
  const mesh = useRef<THREE.InstancedMesh>(null)
  const first = sprites[0]!
  const textureId = useSprite(assets, first.particle, first.data)
  const map = assets && textureId ? loadTexture(assets, textureId) : softDot()
  // Room to grow without remounting on every new sprite.
  const capacity = Math.max(64, 2 ** Math.ceil(Math.log2(sprites.length)))

  useFrame(({ camera }) => {
    const target = mesh.current
    if (!target) return
    sprites.forEach((sprite, i) => {
      scratch.position.set(...sprite.position)
      scratch.quaternion.copy(camera.quaternion)
      scratch.scale.setScalar(sizeOf(sprite.data))
      scratch.updateMatrix()
      target.setMatrixAt(i, scratch.matrix)
      // Additive blending: a darker colour reads as a fainter sprite.
      tint.set(tintOf(sprite.data) ?? '#ffffff').multiplyScalar(opacity(sprite))
      target.setColorAt(i, tint)
    })
    target.count = sprites.length
    target.instanceMatrix.needsUpdate = true
    if (target.instanceColor) target.instanceColor.needsUpdate = true
  })

  return (
    <instancedMesh
      key={capacity}
      ref={mesh}
      args={[undefined, undefined, capacity]}
      frustumCulled={false}
    >
      <planeGeometry />
      <meshBasicMaterial
        map={map}
        transparent
        alphaTest={0.05}
        depthWrite={false}
        blending={THREE.AdditiveBlending}
        side={THREE.DoubleSide}
      />
    </instancedMesh>
  )
}

const OUTLINE = '#e3b341'

/** The selected emitter's shape: where its points can go, at the playhead's radius and spin. */
function ShapeOutline({
  emitter,
  radius,
  tick,
}: {
  emitter: EmitterDef
  radius: number | undefined
  tick: number
}) {
  const shape = shapeOf(emitter)
  const offset = emitter.offset ?? [0, 0, 0]
  const rotation = (emitter.rotation ?? [0, 0, 0]).map(THREE.MathUtils.degToRad) as [
    number,
    number,
    number,
  ]
  // Rotation is X then Y then Z, as format's Matrix4.fromTrs (and three's 'XYZ' order).
  const spin = THREE.MathUtils.degToRad((emitter.spin ?? 0) * tick)
  const r = radius ?? ('radius' in shape ? shape.radius : undefined) ?? 1
  const circle = useMemo(() => {
    const points: [number, number, number][] = []
    for (let i = 0; i <= 48; i += 1) {
      const angle = (i / 48) * Math.PI * 2
      points.push([Math.cos(angle), 0, Math.sin(angle)])
    }
    return points
  }, [])
  let body: ReactNode
  switch (shape.type) {
    case 'point':
      body = (
        <mesh>
          <octahedronGeometry args={[0.06]} />
          <meshBasicMaterial color={OUTLINE} wireframe />
        </mesh>
      )
      break
    case 'line':
      body = <Line points={[[0, 0, 0], shape.to]} color={OUTLINE} lineWidth={1.5} />
      break
    case 'ring':
    case 'disc':
      body = (
        <group scale={[r, 1, r]}>
          <Line points={circle} color={OUTLINE} lineWidth={1.5} />
        </group>
      )
      break
    case 'sphere':
      body = (
        <mesh>
          <sphereGeometry args={[r, 16, 12]} />
          <meshBasicMaterial color={OUTLINE} wireframe transparent opacity={0.35} />
        </mesh>
      )
      break
    case 'box':
      body = (
        <mesh>
          <boxGeometry args={shape.size} />
          <meshBasicMaterial color={OUTLINE} wireframe transparent opacity={0.5} />
        </mesh>
      )
      break
  }
  return (
    <group position={offset} rotation={new THREE.Euler(...rotation, 'XYZ')}>
      <group rotation={[0, spin, 0]}>{body}</group>
    </group>
  )
}
