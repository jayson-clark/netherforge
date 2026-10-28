/**
 * A project item (`items/<id>/item.json`): what it looks like in a slot and
 * its tooltip, and below them the recipes that make or take it, each opening
 * its recipe. The inspector has the item's look (the shared item form,
 * without what belongs to one stack) and its one script.
 */
import { newScript, newSiblingFile, type ItemDef, type ItemFile } from '@/core/format'
import { useGlyphMap } from '@/minecraft/text/glyphs'
import { dirname } from '@/core/paths'
import { useApp, useWorkspace } from '@/state/providers'
import { Button } from '@/ui/Button'
import { CheckField, JsonObjectField, SelectField, Section } from '@/ui/fields'
import { Muted } from '@/ui/text'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen, Stage } from '@/editors/shared/EditorLayout'
import { ScriptField } from '@/editors/shared/ScriptField'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { ItemEditor } from '@/minecraft/item/ItemForm'
import { ItemIcon } from '@/minecraft/item/ItemIcon'
import { TooltipFrame } from '@/minecraft/item/ItemTooltip'
import { useRecipesUsing } from '@/minecraft/item/projectItems'
import { tooltipOf } from '@/minecraft/item/tooltip'
import styles from './ItemScreen.module.css'

/** One empty list, so a selector returns the same value while there's no outline yet. */
const NO_BLOCKS: string[] = []

export function ItemScreen({ path }: { path: string }) {
  const { workspace } = useApp()
  const { doc, model, edit } = useModelDoc<ItemFile>(path)
  const glyphs = useGlyphMap()
  const id = path.split('/')[1] ?? ''
  const recipes = useRecipesUsing(id)
  const blocks = useWorkspace((s) => s.outline?.resources.block ?? NO_BLOCKS)

  useFocusRequests(path, (segments) => segments)

  if (!doc) return null
  if (!model) return <RawDocView path={path} />

  // The file is an item's look: everything the form edits sits at its top level.
  const item = model as ItemDef
  const editItem = (recipe: (draft: ItemDef) => void) =>
    edit((draft) => recipe(draft as unknown as ItemDef))
  // Shown even when the item hides its tooltip in game: this is where it's designed.
  const lines = tooltipOf({ ...item, hideTooltip: false }) ?? []

  return (
    <EditorScreen>
      <EditorBar title={id}>
        <EditAsJson path={path} />
      </EditorBar>
      <Stage>
        <div className={styles.showcase} aria-label="Item preview">
          <span className={styles.slot}>
            <ItemIcon item={item} size={128} />
          </span>
          <TooltipFrame lines={lines} glyphs={glyphs} />
        </div>
        <section className={styles.recipes} aria-label="Recipes">
          <h3>Recipes</h3>
          {recipes.length === 0 ? (
            <Muted>No recipe makes or takes {id} yet.</Muted>
          ) : (
            <ul>
              {recipes.map((recipe) => (
                <li key={recipe.id}>
                  <Button
                    variant="link"
                    icon="recipe"
                    onClick={() => void workspace.getState().openFile(recipe.path)}
                  >
                    {recipe.id}
                  </Button>
                  <Muted>
                    {[recipe.makes && 'makes it', recipe.uses && 'takes it']
                      .filter(Boolean)
                      .join(', ')}
                  </Muted>
                </li>
              ))}
            </ul>
          )}
        </section>
      </Stage>
      <InspectorPanel>
        <Section title="Item" icon="gem">
          <ItemEditor item={item} onEdit={editItem} dataPath="" glyphs={glyphs} look />
          <SelectField
            label="Places block"
            dataPath="block"
            value={model.block ?? ''}
            options={[
              { value: '', label: '(none)' },
              ...blocks.map((it) => ({ value: it, label: it })),
              ...(model.block && !blocks.includes(model.block)
                ? [{ value: model.block, label: `${model.block} (missing)` }]
                : []),
            ]}
            onChange={(value) =>
              edit((draft) => {
                if (value) draft.block = value
                else delete draft.block
              })
            }
          />
          <CheckField
            label="Bow draws without ammo"
            dataPath="drawWithoutAmmo"
            value={model.drawWithoutAmmo === true}
            onChange={(on) =>
              edit((draft) => {
                if (on) draft.drawWithoutAmmo = true
                else delete draft.drawWithoutAmmo
              })
            }
          />
          <JsonObjectField
            label="Properties"
            dataPath="properties"
            value={model.properties}
            onChange={(value) =>
              edit((draft) => {
                if (value === undefined) delete draft.properties
                else draft.properties = value
              })
            }
          />
          <ScriptField
            folder={dirname(path)}
            script={model.script}
            dataPath="script"
            suggestedName="script.lua"
            template={newScript('item', '')}
            siblingTemplate={newSiblingFile('item')}
            onChange={(script) =>
              edit((draft) => {
                if (script === undefined) delete draft.script
                else draft.script = script
              })
            }
          />
        </Section>
      </InspectorPanel>
    </EditorScreen>
  )
}
