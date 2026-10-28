import { CheckField, Section } from '@/ui/fields'
import { Hint } from '@/ui/text'
import type { Parts } from './fields'

export function StructuresSection({ model, edit }: Parts) {
  return (
    <Section title="Structures" icon="structure">
      <CheckField
        label="The game's structures"
        dataPath="structures.vanilla"
        value={model.structures?.vanilla ?? false}
        onChange={(value) =>
          edit((d) => {
            if (value) d.structures = { vanilla: true }
            else delete d.structures
          })
        }
      />
      <Hint>
        Villages, ruins and the project's structures that generate, where the biomes allow them.
      </Hint>
    </Section>
  )
}
