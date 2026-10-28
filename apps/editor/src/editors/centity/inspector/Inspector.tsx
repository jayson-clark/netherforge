/**
 * Every field of the selected node, as `docs/format/centity.md` defines it, and with no node selected the
 * centity's own (its settings, body, health, natural spawning). Each section is a module here.
 *
 * Adding a field: add its control in the right section with `dataPath` set to its JSON path inside the
 * node, write it through `edit()` (one call = one undo step; wrap drags in a gesture), and leave a key out
 * rather than writing its default.
 */
import { memo } from 'react'
import { type CentityFile, type NodeDef } from '@/core/format'
import { NODE_NAME_PATTERN } from '@/core/paths'
import { useApp } from '@/state/providers'
import { usePrimary, useView } from '@/editors/views'
import { Section, SelectField, TextField, Vec3Field } from '@/ui/fields'
import { Empty } from '@/ui/text'
import { InspectorPanel } from '@/editors/shared/docks'
import {
  channelDefault,
  descendantsOf,
  renameNode,
  reparent,
  setChannel,
} from '@/editors/centity/ops'
import { BodySection, CentitySettings, HealthSection } from './CentitySections'
import { DisplaySection } from './DisplaySection'
import { HitboxSection } from './HitboxSection'
import { PhysicsSection } from './PhysicsSection'
import { RopeSection } from './RopeSection'
import { SpawningSection } from './SpawningSection'

// Memoized: playback changes the view's time every frame, and nothing here depends on it.
export const Inspector = memo(function Inspector({
  path,
  model,
}: {
  path: string
  model: CentityFile
}) {
  const { workspace } = useApp()
  const selected = usePrimary('centity', path)
  const changeView = useView('centity', path)
  const node = selected ? model.nodes?.[selected] : undefined

  if (!selected || !node) {
    return (
      <InspectorPanel>
        <CentitySettings path={path} model={model} />
        <BodySection path={path} model={model} />
        <HealthSection path={path} model={model} />
        <SpawningSection path={path} model={model} />
        <Empty>Select a node to edit it.</Empty>
      </InspectorPanel>
    )
  }

  const ws = workspace.getState()
  const editNode = (recipe: (node: NodeDef) => void) =>
    ws.edit<CentityFile>(path, (draft) => {
      const target = draft.nodes?.[selected]
      if (target) recipe(target)
    })
  const gesture = {
    onGestureStart: () => workspace.getState().beginGesture(path),
    onGestureEnd: () => workspace.getState().endGesture(path),
  }

  return (
    <InspectorPanel>
      <Section title="Node" icon="tag">
        <TextField
          label="Name"
          value={selected}
          onChange={(next) => {
            if (!NODE_NAME_PATTERN.test(next) || model.nodes?.[next]) {
              ws.notify('error', `"${next}" can't be used as a node name here`)
              return
            }
            ws.edit<CentityFile>(path, (draft) => renameNode(draft, selected, next))
            changeView.mapSelection((it) => (it === selected ? next : it))
          }}
        />
        <SelectField
          label="Parent"
          dataPath="parent"
          value={node.parent ?? ''}
          options={[
            { value: '', label: '(none: a root)' },
            ...Object.keys(model.nodes ?? {})
              .filter((name) => name !== selected && !descendantsOf(model, selected).has(name))
              .map((name) => ({ value: name, label: name })),
            // A parent that doesn't exist still shows, so the problem is visible.
            ...(node.parent && !model.nodes?.[node.parent]
              ? [{ value: node.parent, label: `${node.parent} (missing)` }]
              : []),
          ]}
          onChange={(parent) =>
            ws.edit<CentityFile>(path, (draft) => void reparent(draft, selected, parent || null))
          }
        />
      </Section>

      <Section title="Transform" icon="move">
        {(['translation', 'rotation', 'scale'] as const).map((channel) => (
          <Vec3Field
            key={channel}
            label={channel[0]!.toUpperCase() + channel.slice(1)}
            dataPath={`transform.${channel}`}
            value={node.transform?.[channel]}
            defaultValue={channelDefault(channel)}
            step={channel === 'rotation' ? 5 : channel === 'scale' ? 0.1 : 1 / 16}
            onChange={(value) => editNode((target) => setChannel(target, channel, value))}
            {...gesture}
          />
        ))}
      </Section>

      <DisplaySection node={node} editNode={editNode} />
      <HitboxSection node={node} editNode={editNode} {...gesture} />
      <PhysicsSection node={node} editNode={editNode} />
      <RopeSection name={selected} node={node} editNode={editNode} />
    </InspectorPanel>
  )
})
