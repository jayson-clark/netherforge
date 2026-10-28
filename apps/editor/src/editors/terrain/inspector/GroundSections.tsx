import { setKey } from '@/core/draft'
import { TERRAIN_DEFAULTS } from '@/core/format'
import { FieldGroup, NumberField, Section } from '@/ui/fields'
import { Hint, Muted } from '@/ui/text'
import { setBlock, setStone } from '@/editors/terrain/ops'
import { BlockPicker, GROUND_KINDS, LayerRows, type Parts, whole } from './fields'

export function LayersSection(parts: Parts) {
  const { model, edit, blocks, customBlocks, structures } = parts
  return (
    <Section title="Ground" icon="layers">
      <Hint>Layers from the surface down, then stone all the way.</Hint>
      <LayerRows {...parts} at={{ key: 'layers' }} base={['layers']} />
      <FieldGroup data-path="stone" header={<strong>Then, all the way down</strong>}>
        <BlockPicker
          choice={model.stone}
          at={['stone']}
          kinds={GROUND_KINDS}
          fallback={TERRAIN_DEFAULTS.stone}
          optional
          blocks={blocks}
          customBlocks={customBlocks}
          structures={structures}
          onChange={(kind, value) => edit((d) => setStone(d, kind, value))}
        />
      </FieldGroup>
      <Muted>Under the sea, the ground is:</Muted>
      <LayerRows {...parts} at={{ key: 'underwater' }} base={['underwater']} />
    </Section>
  )
}

export function FloorSection({ model, edit, blocks, customBlocks, structures, gesture }: Parts) {
  const floor = model.floor
  return (
    <Section
      title="Floor"
      enabled={floor !== undefined}
      onToggle={(on) =>
        edit((d) => {
          if (on) d.floor = {}
          else delete d.floor
        })
      }
    >
      <BlockPicker
        choice={floor}
        at={['floor']}
        kinds={GROUND_KINDS}
        fallback={TERRAIN_DEFAULTS.floorBlock}
        optional
        blocks={blocks}
        customBlocks={customBlocks}
        structures={structures}
        onChange={(kind, value) =>
          edit((d) => {
            if (d.floor) setBlock(d.floor, kind, value)
          })
        }
      />
      <NumberField
        label="Thickness"
        dataPath="floor.thickness"
        value={floor?.thickness}
        placeholder={String(TERRAIN_DEFAULTS.floorThickness)}
        step={1}
        min={1}
        {...gesture}
        onChange={(value) =>
          edit((d) => {
            if (d.floor) setKey(d.floor, 'thickness', whole(value))
          })
        }
      />
    </Section>
  )
}
