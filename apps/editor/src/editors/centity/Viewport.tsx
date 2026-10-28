/**
 * The 3D preview of a centity, in the workbench's shared viewport
 * (`Viewport3D`).
 *
 * Every node sits under the world matrix `format`'s composer produced
 * (`poseCentity`): the viewport has no transform maths of its own. The only
 * thing going the other way is the gizmo, which reads a dragged stand-in's
 * matrix back into the node's local channel (parent⁻¹ · world, decomposed
 * as XYZ Euler, the same convention as `Matrix4.fromTrs`) and writes that as
 * one undo gesture per drag.
 */
import { useEffect, useMemo } from 'react'
import type { ThreeEvent } from '@react-three/fiber'
import { Billboard, Edges } from '@react-three/drei'
import * as THREE from 'three'
import {
  canonicalBlockState,
  layoutText,
  TEXT_DEFAULTS,
  type Box,
  type CentityFile,
  type DisplayDef,
  type GameDataBundle,
  type NodeDef,
} from '@/core/format'
import type { PosedNode } from '@/core/format'
import { type ClientAssets } from '@/minecraft/client/assets'
import { parseBlockState } from '@netherforge/models/model'
import { BlockModel, ItemModel, PackLookModel } from '@/minecraft/client/models'
import { resourcePackItemLook } from '@/minecraft/item/icon'
import {
  useCompiledResourcePacks,
  useHomeNamespace,
  useProjectFileUrl,
  useReferenceNamespace,
  useResourcePackFiles,
  useResourcePackModels,
} from '@/state/useResourcePacks'
import { useClientAssets } from '@/minecraft/client/useClientAssets'
import { glyphAdvancesOf, useGlyphMap } from '@/minecraft/text/glyphs'
import { useApp, useWorkspace } from '@/state/providers'
import { BLOCK_GRID } from '@/editors/shared/viewport/scenes'
import { Gizmo, type GizmoMode } from '@/editors/shared/viewport/Gizmo'
import { ViewportNote, ViewportToolbar } from '@/editors/shared/viewport/parts'
import { Viewport3D } from '@/editors/shared/viewport/Viewport3D'
import { runsIn, styledChars, type TextRun } from '@/minecraft/text/minimessage'
import { localChannels } from './gizmo'
import { setChannel, setKeyframe } from './ops'
import { usePrimary, useSelection, useView, useViewState } from '@/editors/views'

const UNIT_BOX: Box = { min: [0, 0, 0], max: [1, 1, 1] }
const HOME = { position: [3, 2.5, 4], target: [0.5, 0.75, 0.5] } as const

export function Viewport({
  path,
  model,
  nodes,
  stale,
}: {
  path: string
  model: CentityFile
  nodes: PosedNode[]
  stale: boolean
}) {
  const minecraft = useWorkspace((s) => s.minecraft)
  const view = useViewState('centity', path)
  const changeView = useView('centity', path)
  const assets = useClientAssets()

  return (
    <Viewport3D
      kind="centity"
      path={path}
      label="3D viewport"
      scene={{
        content: <CentityScene path={path} model={model} nodes={nodes} stale={stale} />,
        home: { position: [...HOME.position], target: [...HOME.target] },
        grid: BLOCK_GRID,
        onMissed: () => changeView.select(),
      }}
    >
      <ViewportToolbar label="Viewport tools">
        {(['translate', 'rotate', 'scale'] as GizmoMode[]).map((mode, i) => (
          <button
            key={mode}
            type="button"
            aria-pressed={view.gizmo === mode}
            title={`${mode} (${'WER'[i]})`}
            onClick={() => changeView.update({ gizmo: mode })}
          >
            {mode[0]!.toUpperCase() + mode.slice(1)}
          </button>
        ))}
        <button
          type="button"
          aria-pressed={view.showHitboxes}
          onClick={() => changeView.update({ showHitboxes: !view.showHitboxes })}
        >
          Hitboxes
        </button>
      </ViewportToolbar>
      {!assets && (
        <ViewportNote role="note">
          Blocks show as placeholders until Minecraft {minecraft ?? ''} client assets are imported
          (Settings → Minecraft).
        </ViewportNote>
      )}
      {stale && (
        <ViewportNote warning role="note">
          This centity has errors, so the preview shows its last valid state. See Problems.
        </ViewportNote>
      )}
    </Viewport3D>
  )
}

/** The centity's nodes where the composer put them, and the selected one's gizmo. */
function CentityScene({
  path,
  model,
  nodes,
  stale,
}: {
  path: string
  model: CentityFile
  nodes: PosedNode[]
  stale: boolean
}) {
  const selection = useSelection('centity', path)
  const selected = usePrimary('centity', path)
  const gameData = useWorkspace((s) => s.gameData)
  const view = useViewState('centity', path)
  const changeView = useView('centity', path)
  const assets = useClientAssets()

  const matrices = useMemo(() => {
    const out = new Map<string, THREE.Matrix4>()
    for (const node of nodes) out.set(node.name, new THREE.Matrix4().fromArray(node.matrix))
    return out
  }, [nodes])

  return (
    <>
      {nodes.map((posed) => {
        const node = model.nodes?.[posed.name]
        if (!node) return null
        return (
          <group key={posed.name} matrixAutoUpdate={false} matrix={matrices.get(posed.name)!}>
            <NodeVisual
              name={posed.name}
              node={node}
              assets={assets}
              gameData={gameData}
              selected={selection.includes(posed.name)}
              showHitbox={view.showHitboxes}
              onSelect={(add) =>
                add ? changeView.toggle(posed.name) : changeView.select(posed.name)
              }
            />
          </group>
        )
      })}
      {selected && matrices.has(selected) && !stale && (
        <NodeGizmo
          path={path}
          name={selected}
          model={model}
          mode={view.gizmo}
          world={matrices.get(selected)!}
          parentWorld={
            model.nodes?.[selected]?.parent
              ? matrices.get(model.nodes[selected]!.parent!)
              : undefined
          }
          clip={view.clip}
          time={view.time}
        />
      )}
    </>
  )
}

function NodeVisual({
  name,
  node,
  assets,
  gameData,
  selected,
  showHitbox,
  onSelect,
}: {
  name: string
  node: NodeDef
  assets: ClientAssets | null
  gameData: GameDataBundle | null
  selected: boolean
  showHitbox: boolean
  /** [add]: Shift or Cmd/Ctrl was held, so it's added to (or taken out of) the selection. */
  onSelect: (add: boolean) => void
}) {
  const click = (event: ThreeEvent<MouseEvent>) => {
    event.stopPropagation()
    onSelect(event.shiftKey || event.metaKey || event.ctrlKey)
  }
  return (
    <group name={name} onClick={click}>
      {node.display && <DisplayVisual display={node.display} assets={assets} gameData={gameData} />}
      {!node.display && <EmptyMarker />}
      {showHitbox && node.hitbox && <HitboxVisual node={node} gameData={gameData} />}
      {selected && <SelectionBox display={node.display} />}
    </group>
  )
}

export function DisplayVisual({
  display,
  assets,
  gameData,
}: {
  display: DisplayDef
  assets: ClientAssets | null
  gameData: GameDataBundle | null
}) {
  switch (display.type) {
    case 'block': {
      const id = parseBlockState(display.block).id
      return (
        <BlockModel
          state={display.block}
          assets={assets}
          defaults={gameData?.blocks?.[id]?.defaults}
        />
      )
    }
    case 'item':
      return (
        <ItemModel item={display.item} context={display.itemTransform ?? 'none'} assets={assets} />
      )
    case 'model':
      return (
        <ModelVisual
          model={display.model}
          context={display.itemTransform ?? 'none'}
          assets={assets}
        />
      )
    case 'text':
      return <TextVisual display={display} />
  }
}

/** A model display: one of the project's resource pack looks, as the server shows it. */
function ModelVisual({
  model,
  context,
  assets,
}: {
  model: string
  context: string
  assets: ClientAssets | null
}) {
  const look = resourcePackItemLook(
    model,
    useResourcePackFiles(),
    useCompiledResourcePacks(),
    useReferenceNamespace(),
    useHomeNamespace(),
    useResourcePackModels(),
  )
  return (
    <PackLookModel
      id={model}
      look={look}
      urlOf={useProjectFileUrl()}
      context={context}
      assets={assets}
    />
  )
}

/** Pixels to blocks for text displays. */
const TEXT_SCALE = 1 / 40
const LINE_HEIGHT = 10

/**
 * A preview of a text display, laid out by format (`layoutText`: the game's
 * wrapping and widths, so it fills what Fit measures) and coloured run by run
 * (`styledChars`). The browser's font stands in for the game's, squeezed or
 * stretched to the game's width for each run.
 */
function TextVisual({ display }: { display: Extract<DisplayDef, { type: 'text' }> }) {
  const advances = useWorkspace((s) => s.glyphAdvances)
  const glyphs = glyphAdvancesOf(useGlyphMap())
  const glyphKey = JSON.stringify(glyphs)
  const { texture, width, height } = useMemo(
    () => drawText(display, advances, glyphs),
    // `glyphs` is rebuilt every render; its contents are what matter.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [display, advances, glyphKey],
  )
  useEffect(() => () => texture.dispose(), [texture])
  const plane = (
    <mesh position={[0, (height * TEXT_SCALE) / 2, 0]}>
      <planeGeometry args={[width * TEXT_SCALE, height * TEXT_SCALE]} />
      <meshBasicMaterial
        map={texture}
        transparent
        side={THREE.DoubleSide}
        depthTest={!display.seeThrough}
      />
    </mesh>
  )
  const billboard = display.billboard ?? TEXT_DEFAULTS.billboard
  if (billboard === 'fixed') return plane
  return (
    <Billboard lockX={billboard === 'vertical'} lockY={billboard === 'horizontal'}>
      {plane}
    </Billboard>
  )
}

function drawText(
  display: Extract<DisplayDef, { type: 'text' }>,
  advances: Record<string, number> | null,
  glyphs: Record<string, number>,
) {
  const lines = layoutText(
    display.text,
    display.lineWidth ?? TEXT_DEFAULTS.lineWidth,
    advances,
    glyphs,
  )
  const chars = styledChars(display.text)
  const scale = 4 // canvas pixels per text pixel, for crisp edges
  // As the game draws it: a pixel of padding each side, a line every LINE_HEIGHT.
  const width = Math.max(1, ...lines.map((it) => it.width)) + 2
  const height = lines.length * LINE_HEIGHT + 2
  const canvas = document.createElement('canvas')
  canvas.width = Math.ceil(width * scale)
  canvas.height = Math.ceil(height * scale)
  const context = canvas.getContext('2d')
  if (context) {
    context.fillStyle = argb(display.background ?? '#40000000')
    context.fillRect(0, 0, canvas.width, canvas.height)
    context.font = `${8 * scale}px monospace`
    context.textBaseline = 'top'
    lines.forEach((line, i) => {
      const runs = runsIn(chars, line.start, line.end)
      const measured = runs.reduce((sum, run) => sum + context.measureText(run.text).width, 0)
      // What the game's own widths say: glyphs and moves. What an <at> holds takes none.
      const fixedWidth = runs.reduce(
        (sum, run) => sum + (run.glyph ? (glyphs[run.glyph] ?? 0) : 0) + (run.move ?? 0),
        0,
      )
      // Stretch the browser's font to the game's width for this line.
      const ratio = measured > 0 ? ((line.width - fixedWidth) * scale) / measured : 1
      const free = width - 2 - line.width
      const offset =
        display.alignment === 'left' ? 0 : display.alignment === 'right' ? free : free / 2
      const y = (1 + i * LINE_HEIGHT) * scale
      // How far [list] moves the cursor, in canvas pixels.
      const advance = (list: TextRun[]): number =>
        list.reduce((sum, run) => {
          if (run.glyph) return sum + (glyphs[run.glyph] ?? 0) * scale
          if (run.move !== undefined) return sum + run.move * scale
          if (run.overlay) return sum
          return sum + context.measureText(run.text).width * ratio
        }, 0)
      // Draws [list] from [start].
      const draw = (list: TextRun[], start: number) => {
        let x = start
        for (const run of list) {
          if (run.overlay) {
            // Counted from the cursor, its `align` edge there.
            const held = advance(run.overlay.runs)
            const shift = { left: 0, center: held / 2, right: held }[
              run.overlay.at.align as 'left' | 'center' | 'right'
            ]
            draw(run.overlay.runs, x + run.overlay.at.x * scale - shift)
            continue
          }
          if (run.glyph === undefined && run.move === undefined) {
            const runWidth = context.measureText(run.text).width * ratio
            if (display.shadow) {
              context.fillStyle = '#3f3f3f'
              context.fillText(run.text, x + scale, y + scale, runWidth)
            }
            context.fillStyle = run.style.color ?? '#ffffff'
            context.fillText(run.text, x, y, runWidth)
          }
          x += advance([run])
        }
      }
      draw(runs, (1 + offset) * scale)
    })
  }
  const texture = new THREE.CanvasTexture(canvas)
  texture.colorSpace = THREE.SRGBColorSpace
  return { texture, width, height }
}

function argb(text: string): string {
  const match = /^#([0-9a-fA-F]{2})([0-9a-fA-F]{6})$/.exec(text)
  if (!match) return 'rgba(0,0,0,0.25)'
  return `#${match[2]}${match[1]}`
}

function EmptyMarker() {
  return (
    <mesh>
      <octahedronGeometry args={[0.08]} />
      <meshBasicMaterial color="#8a93a5" wireframe />
    </mesh>
  )
}

function boxesOf(node: NodeDef, gameData: GameDataBundle | null): { boxes: Box[]; live: boolean } {
  const hitbox = node.hitbox!
  if (hitbox.boxes) return { boxes: hitbox.boxes, live: false }
  if (hitbox.shape === 'collision' && node.display?.type === 'block') {
    const key = canonicalBlockState(node.display.block)
    const id = parseBlockState(node.display.block).id
    return {
      boxes: (key ? gameData?.collision?.[key] : undefined) ??
        gameData?.collision?.[id] ?? [UNIT_BOX],
      live: true,
    }
  }
  return { boxes: [UNIT_BOX], live: false }
}

function HitboxVisual({ node, gameData }: { node: NodeDef; gameData: GameDataBundle | null }) {
  const { boxes, live } = boxesOf(node, gameData)
  return (
    <>
      {boxes.map((box, i) => {
        const size: [number, number, number] = [
          box.max[0] - box.min[0],
          box.max[1] - box.min[1],
          box.max[2] - box.min[2],
        ]
        if (size.some((it) => it <= 0)) return null
        return (
          <mesh
            key={i}
            position={[
              (box.min[0] + box.max[0]) / 2,
              (box.min[1] + box.max[1]) / 2,
              (box.min[2] + box.max[2]) / 2,
            ]}
            raycast={() => null}
          >
            <boxGeometry args={size} />
            <meshBasicMaterial visible={false} />
            <Edges color={live ? '#58a6ff' : '#3fb950'} />
          </mesh>
        )
      })}
    </>
  )
}

function SelectionBox({ display }: { display: DisplayDef | undefined }) {
  const size: [number, number, number] =
    display?.type === 'block'
      ? [1.02, 1.02, 1.02]
      : display?.type === 'item'
        ? [0.55, 0.55, 0.1]
        : display?.type === 'model'
          ? [1.02, 1.02, 1.02]
          : [0.25, 0.25, 0.25]
  const offset: [number, number, number] = display?.type === 'block' ? [0.5, 0.5, 0.5] : [0, 0, 0]
  return (
    <mesh position={offset} raycast={() => null}>
      <boxGeometry args={size} />
      <meshBasicMaterial visible={false} />
      <Edges color="#e3b341" />
    </mesh>
  )
}

const CHANNEL_OF: Record<GizmoMode, 'translation' | 'rotation' | 'scale'> = {
  translate: 'translation',
  rotate: 'rotation',
  scale: 'scale',
}

/** The selected node's gizmo: a drag edits its base transform, or keys its channel at the playhead. */
function NodeGizmo({
  path,
  name,
  model,
  mode,
  world,
  parentWorld,
  clip,
  time,
}: {
  path: string
  name: string
  model: CentityFile
  mode: GizmoMode
  world: THREE.Matrix4
  parentWorld?: THREE.Matrix4
  clip: string | null
  time: number
}) {
  const { workspace } = useApp()
  const channel = CHANNEL_OF[mode]
  // While previewing a clip that animates this channel, a drag keys it at the playhead.
  const keyed =
    clip !== null && (model.animations?.[clip]?.tracks?.[name]?.[channel]?.length ?? 0) > 0

  return (
    <Gizmo
      world={world}
      mode={mode}
      onBegin={() => workspace.getState().beginGesture(path)}
      onEnd={() => workspace.getState().endGesture(path)}
      onChange={(proxy) => {
        const value = localChannels(proxy.matrixWorld, parentWorld)[channel]
        workspace.getState().edit<CentityFile>(path, (draft) => {
          const node = draft.nodes?.[name]
          if (!node) return
          const animation = clip ? draft.animations?.[clip] : undefined
          if (keyed && animation) setKeyframe(animation, name, channel, time, value)
          else setChannel(node, channel, value)
        })
      }}
    />
  )
}
