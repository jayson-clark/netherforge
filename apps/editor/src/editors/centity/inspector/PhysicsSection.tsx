/** A node's rigid body: its numbers and what it collides with. */
import { setKey } from '@/core/draft'
import { PHYSICS_DEFAULTS, type NodeDef, type PhysicsDef } from '@/core/format'
import { NumberField, Section, SelectField, TriStateField, CheckField } from '@/ui/fields'
import { BoxFields } from './HitboxSection'
import type { EditNode } from './editNode'

const PHYSICS_NUMBERS: {
  key: keyof PhysicsDef
  label: string
  placeholder: string
  step: number
}[] = [
  { key: 'gravity', label: 'Gravity', placeholder: String(PHYSICS_DEFAULTS.gravity), step: 1 },
  { key: 'mass', label: 'Mass', placeholder: 'volume', step: 0.1 },
  {
    key: 'bounciness',
    label: 'Bounciness',
    placeholder: String(PHYSICS_DEFAULTS.bounciness),
    step: 0.05,
  },
  {
    key: 'friction',
    label: 'Friction',
    placeholder: String(PHYSICS_DEFAULTS.friction),
    step: 0.05,
  },
  { key: 'drag', label: 'Drag', placeholder: String(PHYSICS_DEFAULTS.drag), step: 0.01 },
  {
    key: 'angularDrag',
    label: 'Angular drag',
    placeholder: String(PHYSICS_DEFAULTS.angularDrag),
    step: 0.01,
  },
  { key: 'maxSpeed', label: 'Max speed', placeholder: String(PHYSICS_DEFAULTS.maxSpeed), step: 1 },
  { key: 'maxSpin', label: 'Max spin', placeholder: String(PHYSICS_DEFAULTS.maxSpin), step: 10 },
]

const PHYSICS_FLAGS: { key: 'rotates' | 'sleeps' | 'blocks' | 'entities'; label: string }[] = [
  { key: 'rotates', label: 'Rotates' },
  { key: 'sleeps', label: 'Sleeps at rest' },
  { key: 'blocks', label: 'Collides with blocks' },
  { key: 'entities', label: 'Collides with centities' },
]

export function PhysicsSection({ node, editNode }: { node: NodeDef; editNode: EditNode }) {
  const physics = node.physics
  const editPhysics = (recipe: (p: PhysicsDef) => void) =>
    editNode((target) => {
      if (target.physics) recipe(target.physics)
    })
  return (
    <Section
      title="Physics"
      icon="atom"
      enabled={physics !== undefined}
      onToggle={(on) => editNode((target) => (on ? (target.physics = {}) : delete target.physics))}
    >
      {physics && (
        <>
          {PHYSICS_NUMBERS.map(({ key, label, placeholder, step }) => (
            <NumberField
              key={key}
              label={label}
              dataPath={`physics.${key}`}
              value={physics[key] as number | undefined}
              placeholder={placeholder}
              step={step}
              onChange={(value) => editPhysics((p) => setKey(p, key, value as never))}
            />
          ))}
          <SelectField
            label="Shape"
            dataPath="physics.shape"
            value={physics.shape ?? ''}
            options={[
              { value: '', label: 'Follow the hitbox' },
              { value: 'box', label: 'One box' },
              { value: 'sphere', label: 'Sphere (rolls)' },
            ]}
            onChange={(value) => editPhysics((p) => setKey(p, 'shape', value || undefined))}
          />
          <CheckField
            label="Own collider"
            dataPath="physics.collider"
            value={physics.collider !== undefined}
            onChange={(on) =>
              editPhysics((p) =>
                setKey(p, 'collider', on ? { min: [0, 0, 0], max: [1, 1, 1] } : undefined),
              )
            }
          />
          {physics.collider && (
            <BoxFields
              label="Collider"
              dataPath="physics.collider"
              box={physics.collider}
              onChange={(collider) => editPhysics((p) => (p.collider = collider))}
            />
          )}
          {PHYSICS_FLAGS.map(({ key, label }) => (
            <TriStateField
              key={key}
              label={label}
              dataPath={`physics.${key}`}
              value={physics[key]}
              defaultLabel="on"
              onChange={(value) => editPhysics((p) => setKey(p, key, value))}
            />
          ))}
        </>
      )}
    </Section>
  )
}
