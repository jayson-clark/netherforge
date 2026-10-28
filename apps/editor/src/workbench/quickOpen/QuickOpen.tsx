/**
 * The palette. Ctrl/Cmd+P: type part of a resource's id, a file's path or a
 * command's name, Enter opens or runs it. Ctrl/Cmd+Shift+P (or a leading
 * `>`) lists commands alone. A react-aria `Autocomplete` over a `ListBox` in
 * a modal `Dialog`: the arrow keys move through the matches while typing
 * goes on in the field, and Escape (or a click outside) closes. The ranking
 * is ours (`rankEntries`), so the autocomplete doesn't filter.
 */
import { useMemo, useState } from 'react'
import {
  Autocomplete,
  Dialog,
  Input,
  ListBox,
  ListBoxItem,
  Modal,
  ModalOverlay,
  TextField,
} from 'react-aria-components'
import { useApp, useWorkspace } from '@/state/providers'
import { Icon } from '@/ui/Icon'
import { Keys } from '@/ui/Keys'
import { Thumbnail } from '@/workbench/explorer/Thumbnail'
import { paletteCommands } from '@/workbench/menus/palette'
import { useMenus } from '@/workbench/menus/useAppMenus'
import { commandEntries, quickEntries, rankEntries, type QuickEntry } from './entries'
import styles from './QuickOpen.module.css'
import { useQuickOpen } from './store'

const THUMBNAIL = 36

export function QuickOpen({ onClose }: { onClose: () => void }) {
  const { workspace } = useApp()
  const files = useWorkspace((s) => s.files)
  const context = useMenus((s) => s.context)
  const [query, setQuery] = useState(() => useQuickOpen.getState().query)
  // The commands as they stand when it opens; running one closes it.
  const [commands] = useState(() => {
    const c = context()
    return c ? commandEntries(paletteCommands(c)) : []
  })
  const all = useMemo(() => [...quickEntries(files), ...commands], [files, commands])
  const shown = useMemo(() => rankEntries(all, query), [all, query])

  const choose = (entry: QuickEntry | undefined) => {
    if (!entry) return
    onClose()
    if ('open' in entry.action) void workspace.getState().openFile(entry.action.open)
    else useMenus.getState().run(entry.action.command)
  }

  return (
    <ModalOverlay
      isOpen
      isDismissable
      onOpenChange={(open) => !open && onClose()}
      className={styles.backdrop}
    >
      <Modal className={styles.palette}>
        <Dialog aria-label="Command palette" className={styles.dialog}>
          <Autocomplete inputValue={query} onInputChange={setQuery}>
            <TextField
              aria-label="Go to resource or file, or run a command"
              autoFocus
              className={styles.field}
            >
              <Input
                className={styles.input}
                placeholder="Go to a resource or file, or type > for commands"
              />
            </TextField>
            <ListBox
              items={shown}
              aria-label="Matches"
              className={styles.list}
              onAction={(key) => choose(shown.find((it) => it.key === key))}
              renderEmptyState={() => <p className={styles.empty}>Nothing matches.</p>}
            >
              {(entry) => (
                <ListBoxItem
                  id={entry.key}
                  textValue={`${entry.label} ${entry.detail}`}
                  className={styles.entry}
                >
                  <span className={styles.picture}>
                    {entry.resource ? (
                      <Thumbnail
                        folder={entry.resource.folder}
                        id={entry.resource.id}
                        size={THUMBNAIL}
                      />
                    ) : (
                      <Icon name={entry.icon} size={16} />
                    )}
                  </span>
                  <span className={styles.text}>
                    <span className={styles.label}>
                      <Highlighted text={entry.label} positions={entry.positions} />
                    </span>
                    {entry.detail && <span className={styles.detail}>{entry.detail}</span>}
                  </span>
                  {entry.shortcut && <Keys shortcut={entry.shortcut} className={styles.shortcut} />}
                </ListBoxItem>
              )}
            </ListBox>
          </Autocomplete>
        </Dialog>
      </Modal>
    </ModalOverlay>
  )
}

function Highlighted({ text, positions }: { text: string; positions: number[] }) {
  if (positions.length === 0) return <>{text}</>
  const marked = new Set(positions)
  return (
    <>
      {[...text].map((char, index) =>
        marked.has(index) ? <mark key={index}>{char}</mark> : <span key={index}>{char}</span>,
      )}
    </>
  )
}
