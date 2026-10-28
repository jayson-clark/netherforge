/**
 * What goes in one ingredient slot, picked from three tabs: a Minecraft item
 * (searchable, from the game data or the client import, with icons), an item
 * tag, or one of the project's items. A tag is typed; format checks it
 * against the game's item tags once it's in the file.
 */
import { useState } from 'react'
import { Tab as AriaTab, TabList, TabPanel, Tabs } from 'react-aria-components'
import { ItemIcon } from '@/minecraft/item/ItemIcon'
import { useProjectItemIds } from '@/minecraft/item/projectItems'
import { usePickerIds } from '@/minecraft/client/usePickerIds'
import { Button } from '@/ui/Button'
import { Modal } from '@/ui/dialogs'
import { Spacer } from '@/ui/layout'
import { Muted } from '@/ui/text'
import styles from './IngredientPicker.module.css'
import { describeIngredient, ingredientOf, type Ingredient, type IngredientChoice } from './ops'

type Tab = IngredientChoice['type']

const TABS: { tab: Tab; label: string }[] = [
  { tab: 'vanilla', label: 'Minecraft item' },
  { tab: 'tag', label: 'Tag' },
  { tab: 'project', label: 'Project item' },
]

/** How many matches the item list shows at once; searching narrows it. */
const SHOWN = 120

export function IngredientPicker({
  title,
  current,
  onPick,
  onClose,
}: {
  title: string
  current: Ingredient | null
  /** The new ingredient, or null to empty the slot. */
  onPick: (ingredient: Ingredient | null) => void
  onClose: () => void
}) {
  const initial = current === null ? null : describeIngredient(current)
  const [tab, setTab] = useState<Tab>(initial?.type ?? 'vanilla')
  const pick = (choice: IngredientChoice) => {
    onPick(ingredientOf(choice))
    onClose()
  }

  return (
    <Modal
      title={title}
      onClose={onClose}
      wide
      footer={
        <>
          {current !== null && (
            <Button
              onClick={() => {
                onPick(null)
                onClose()
              }}
            >
              Empty the slot
            </Button>
          )}
          <Spacer />
          <Button onClick={onClose}>Cancel</Button>
        </>
      }
    >
      <Tabs selectedKey={tab} onSelectionChange={(key) => setTab(key as Tab)}>
        <TabList className={styles.tabs} aria-label="Ingredient kind">
          {TABS.map((it) => (
            <AriaTab key={it.tab} id={it.tab} className={styles.tab}>
              {it.label}
            </AriaTab>
          ))}
        </TabList>
        <TabPanel id="vanilla">
          <VanillaTab
            initial={initial?.type === 'vanilla' ? initial.id : ''}
            onPick={(id) => pick({ type: 'vanilla', id })}
          />
        </TabPanel>
        <TabPanel id="tag">
          <TagTab
            initial={initial?.type === 'tag' ? initial.id : ''}
            onPick={(id) => pick({ type: 'tag', id })}
          />
        </TabPanel>
        <TabPanel id="project">
          <ProjectTab onPick={(id) => pick({ type: 'project', id })} />
        </TabPanel>
      </Tabs>
    </Modal>
  )
}

function VanillaTab({ initial, onPick }: { initial: string; onPick: (id: string) => void }) {
  const { items } = usePickerIds()
  const [query, setQuery] = useState('')
  const [typed, setTyped] = useState(initial)
  const needle = query.trim().toLowerCase()
  const matches = items.filter((it) => it.includes(needle))

  if (items.length === 0) {
    // No game data and no client import: the id is typed, and format checks it once the game data is there.
    return (
      <div className={styles.free}>
        <p>
          <Muted>
            No Minecraft items to list yet: start the dev server once, or import the client in
            Settings. You can type an item id.
          </Muted>
        </p>
        <FreeText
          label="Item id"
          value={typed}
          placeholder="minecraft:stick"
          onChange={setTyped}
          onPick={onPick}
        />
      </div>
    )
  }
  return (
    <>
      <input
        className={styles.search}
        aria-label="Search items"
        placeholder={`Search ${items.length} items`}
        value={query}
        autoFocus
        onChange={(event) => setQuery(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && matches[0]) onPick(matches[0])
        }}
      />
      <ul className={styles.grid} aria-label="Minecraft items">
        {matches.slice(0, SHOWN).map((id) => (
          <li key={id}>
            <Button className={styles.choice} aria-label={id} onClick={() => onPick(id)}>
              <ItemIcon item={{ kind: id }} size={32} />
              <span className={styles.choiceName}>{id.slice(id.indexOf(':') + 1)}</span>
            </Button>
          </li>
        ))}
      </ul>
      {matches.length > SHOWN && (
        <p>
          <Muted>{matches.length - SHOWN} more: keep typing to narrow it down.</Muted>
        </p>
      )}
      {matches.length === 0 && (
        <p>
          <Muted>No item matches “{query}”.</Muted>
        </p>
      )}
    </>
  )
}

function TagTab({ initial, onPick }: { initial: string; onPick: (id: string) => void }) {
  const [typed, setTyped] = useState(initial)
  return (
    <div className={styles.free}>
      <p>
        <Muted>
          Any item in an item tag fits, like #minecraft:planks. The game data doesn&apos;t list
          tags, so type one; the recipe&apos;s problems say if it isn&apos;t a tag id.
        </Muted>
      </p>
      <FreeText
        label="Tag"
        value={typed}
        placeholder="#minecraft:planks"
        onChange={setTyped}
        onPick={onPick}
      />
    </div>
  )
}

function ProjectTab({ onPick }: { onPick: (id: string) => void }) {
  const ids = useProjectItemIds()
  if (ids.length === 0) {
    return (
      <p>
        <Muted>The project has no items yet: add one under Items.</Muted>
      </p>
    )
  }
  return (
    <ul className={styles.grid} aria-label="Project items">
      {ids.map((id) => (
        <li key={id}>
          <Button className={styles.choice} aria-label={id} onClick={() => onPick(id)}>
            <ItemIcon item={{ item: id }} size={32} />
            <span className={styles.choiceName}>{id}</span>
          </Button>
        </li>
      ))}
    </ul>
  )
}

function FreeText({
  label,
  value,
  placeholder,
  onChange,
  onPick,
}: {
  label: string
  value: string
  placeholder: string
  onChange: (value: string) => void
  onPick: (value: string) => void
}) {
  const use = () => value.trim() && onPick(value.trim())
  return (
    <div className={styles.freeRow}>
      <input
        aria-label={label}
        value={value}
        placeholder={placeholder}
        autoFocus
        onChange={(event) => onChange(event.target.value)}
        onKeyDown={(event) => event.key === 'Enter' && use()}
      />
      <Button disabled={!value.trim()} onClick={use}>
        Use
      </Button>
    </div>
  )
}
