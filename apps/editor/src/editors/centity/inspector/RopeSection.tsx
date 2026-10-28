import { setKey } from '@/core/draft'
import { ROPE_LIMITS, type NodeDef } from '@/core/format'
import { NumberField, Section } from '@/ui/fields'
import { Hint } from '@/ui/text'
import type { EditNode } from './editNode'

/** A node's `rope`: its display drawn by a line of links that scripts string between two ends. */
export function RopeSection({
  name,
  node,
  editNode,
}: {
  name: string
  node: NodeDef
  editNode: EditNode
}) {
  const rope = node.rope
  return (
    <Section
      title="Rope"
      icon="spline"
      enabled={rope !== undefined}
      onToggle={(on) => editNode((target) => setKey(target, 'rope', on ? { links: 8 } : undefined))}
    >
      {rope && (
        <>
          <NumberField
            label="Links"
            dataPath="rope.links"
            value={rope.links}
            step={1}
            min={1}
            onChange={(value) =>
              value !== undefined &&
              editNode((target) => {
                if (target.rope)
                  target.rope.links = Math.min(ROPE_LIMITS.maxLinks, Math.round(value))
              })
            }
          />
          <NumberField
            label="Link length"
            dataPath="rope.linkLength"
            value={rope.linkLength}
            placeholder={String(ROPE_LIMITS.linkLength)}
            step={0.05}
            min={0}
            onChange={(value) =>
              editNode((target) => {
                if (target.rope)
                  setKey(
                    target.rope,
                    'linkLength',
                    value === undefined ? undefined : Math.min(ROPE_LIMITS.maxLinkLength, value),
                  )
              })
            }
          />
          <Hint>
            Its display is drawn by links {name}[1] to {name}[{rope.links}], each stretched along Z
            to its length; <code>node:rope_between</code> strings them. A rope has no hitbox or
            physics.
          </Hint>
        </>
      )}
    </Section>
  )
}
