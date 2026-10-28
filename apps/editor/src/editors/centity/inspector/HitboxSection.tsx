/** A node's hitbox: a cube, boxes, or the display's collision shape, with Fit to display. */
import { setKey } from '@/core/draft'
import { useState } from 'react'
import { type Box, type HitboxDef, type NodeDef, type Vec3 } from '@/core/format'
import { useClientAssets } from '@/minecraft/client/useClientAssets'
import { useApp, useWorkspace } from '@/state/providers'
import { Row, Section, SelectField, TriStateField, Vec3Field, FieldGroup } from '@/ui/fields'
import { Button, IconButton } from '@/ui/Button'
import { Hint } from '@/ui/text'
import { glyphAdvancesOf, useGlyphMap } from '@/minecraft/text/glyphs'
import {
  blockModelBoxes,
  canFit,
  fitKey,
  fittedHitbox,
  textDisplayBoxes,
} from '@/editors/centity/fit'
import type { EditNode } from './editNode'

type HitboxMode = 'cube' | 'boxes' | 'collision'

export function HitboxSection({
  node,
  editNode,
  onGestureStart,
  onGestureEnd,
}: {
  node: NodeDef
  editNode: EditNode
  onGestureStart: () => void
  onGestureEnd: () => void
}) {
  const { workspace } = useApp()
  const gameData = useWorkspace((s) => s.gameData)
  const assets = useClientAssets()
  const [fitting, setFitting] = useState(false)
  const hitbox = node.hitbox
  const mode: HitboxMode =
    hitbox?.shape === 'collision' ? 'collision' : hitbox?.boxes ? 'boxes' : 'cube'
  const editHitbox = (recipe: (h: HitboxDef) => void) =>
    editNode((target) => {
      if (target.hitbox) recipe(target.hitbox)
    })

  const advances = useWorkspace((s) => s.glyphAdvances)
  const glyphs = useGlyphMap()
  const fit = async () => {
    const display = node.display
    if (!display || !canFit(display)) return
    if (display.type === 'text') {
      const boxes = textDisplayBoxes(display, advances, glyphAdvancesOf(glyphs))
      if (boxes) editNode((target) => (target.hitbox = fittedHitbox(target.hitbox, display, boxes)))
      if (!advances) {
        workspace
          .getState()
          .notify(
            'info',
            "Fitted with estimated glyph widths: import this version's client assets in Settings → Minecraft for exact ones.",
          )
      }
      return
    }
    if (display.type !== 'block') return
    setFitting(true)
    try {
      const boxes = await blockModelBoxes(display.block, {
        gameData,
        assets,
      })
      if (!boxes) {
        workspace
          .getState()
          .notify(
            'error',
            `No model for ${display.block}: import this version's client assets in Settings → Minecraft.`,
          )
        return
      }
      editNode((target) => {
        target.hitbox = fittedHitbox(target.hitbox, display, boxes)
      })
    } finally {
      setFitting(false)
    }
  }

  const stale =
    hitbox?.fittedTo !== undefined &&
    fitKey(node.display) !== null &&
    hitbox.fittedTo !== fitKey(node.display)

  return (
    <Section
      title="Hitbox"
      icon="crosshair"
      enabled={hitbox !== undefined}
      onToggle={(on) => editNode((target) => (on ? (target.hitbox = {}) : delete target.hitbox))}
    >
      {hitbox && (
        <>
          <SelectField
            label="Shape"
            dataPath="hitbox.shape"
            value={mode}
            options={[
              { value: 'cube', label: 'Unit cube' },
              { value: 'boxes', label: 'Boxes' },
              { value: 'collision', label: "Block's collision shape (live)" },
            ]}
            onChange={(next) =>
              editHitbox((h) => {
                delete h.fittedTo
                if (next === 'cube') {
                  delete h.boxes
                  delete h.shape
                } else if (next === 'boxes') {
                  delete h.shape
                  h.boxes = h.boxes ?? [{ min: [0, 0, 0], max: [1, 1, 1] }]
                } else {
                  delete h.boxes
                  h.shape = 'collision'
                }
              })
            }
          />
          {mode === 'boxes' &&
            (hitbox.boxes ?? []).map((box, index) => (
              <BoxFields
                key={index}
                label={`Box ${index + 1}`}
                box={box}
                dataPath={`hitbox.boxes[${index}]`}
                onChange={(next) => editHitbox((h) => (h.boxes![index] = next))}
                onRemove={() => editHitbox((h) => void h.boxes!.splice(index, 1))}
                onGestureStart={onGestureStart}
                onGestureEnd={onGestureEnd}
              />
            ))}
          {mode === 'boxes' && (
            <Button
              size="small"
              icon="plus"
              onClick={() =>
                editHitbox(
                  (h) => (h.boxes = [...(h.boxes ?? []), { min: [0, 0, 0], max: [1, 1, 1] }]),
                )
              }
            >
              Add box
            </Button>
          )}
          <TriStateField
            label="Raycast"
            dataPath="hitbox.raycast"
            value={hitbox.raycast}
            defaultLabel="on for several boxes"
            onChange={(value) => editHitbox((h) => setKey(h, 'raycast', value))}
          />
          <Row label="Fit">
            <Button
              size="small"
              disabled={!canFit(node.display) || fitting}
              title={
                node.display?.type === 'text'
                  ? canFit(node.display)
                    ? 'Write the text quad as a box'
                    : 'A text display that turns to face viewers has no one shape; use the fixed billboard'
                  : canFit(node.display)
                    ? 'Write the block model as boxes'
                    : 'Needs a block display, or a fixed text display'
              }
              onClick={() => void fit()}
            >
              Fit to display
            </Button>
          </Row>
          {hitbox.fittedTo && (
            <Hint tone={stale ? 'warning' : undefined} data-path="hitbox.fittedTo">
              Fitted to <code>{hitbox.fittedTo}</code>
              {stale && ' — the display changed; fit again.'}
            </Hint>
          )}
        </>
      )}
      {!hitbox && <Hint>No hitbox: this node can't be clicked.</Hint>}
    </Section>
  )
}

export function BoxFields({
  label,
  box,
  onChange,
  onRemove,
  dataPath,
  onGestureStart,
  onGestureEnd,
}: {
  label: string
  box: Box
  onChange: (box: Box) => void
  onRemove?: () => void
  dataPath: string
  onGestureStart?: () => void
  onGestureEnd?: () => void
}) {
  return (
    <FieldGroup
      data-path={dataPath}
      header={
        <>
          <span>{label}</span>
          {onRemove && <IconButton icon="trash" label={`Remove ${label}`} onClick={onRemove} />}
        </>
      }
    >
      <Vec3Field
        label={`${label} min`}
        value={box.min}
        defaultValue={[0, 0, 0]}
        step={1 / 16}
        onChange={(min: Vec3) => onChange({ ...box, min })}
        onGestureStart={onGestureStart}
        onGestureEnd={onGestureEnd}
      />
      <Vec3Field
        label={`${label} max`}
        value={box.max}
        defaultValue={[1, 1, 1]}
        step={1 / 16}
        onChange={(max: Vec3) => onChange({ ...box, max })}
        onGestureStart={onGestureStart}
        onGestureEnd={onGestureEnd}
      />
    </FieldGroup>
  )
}
