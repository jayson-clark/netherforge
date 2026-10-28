/** The inspector for a sound: its file or its pack.json event, with a player per file. */
import { type ResourcePackFile } from '@/core/format'
import { resourcePackSoundPath } from '@/core/paths'
import { useApp } from '@/state/providers'
import { ask } from '@/ui/dialogs'
import { CheckField, Datalist, NumberField, Row, Section, TextField } from '@/ui/fields'
import { Button } from '@/ui/Button'
import { Muted } from '@/ui/text'
import { fieldPath } from '@/editors/shared/focus'
import { removeSoundEntry, setSoundValue, soundFileOf } from './ops'
import { useProjectFileUrl } from '@/state/useResourcePacks'
import styles from './ResourcePackEditor.module.css'

export function SoundInspector({
  pack,
  soundKey,
  model,
  plays,
  soundFiles,
  edit,
  onDeleted,
}: {
  pack: string
  soundKey: string
  model: ResourcePackFile
  plays: string[]
  soundFiles: string[]
  edit: (recipe: (draft: ResourcePackFile) => void) => void
  onDeleted: () => void
}) {
  const { workspace } = useApp()
  const url = useProjectFileUrl()
  const def = model.sounds?.[soundKey]
  const at = (key: string) => fieldPath(['sounds', soundKey, key])
  const file = soundFileOf(soundKey)
  const listId = `sound-files-${pack}`
  return (
    <Section title={`Sound ${pack}/${soundKey}`}>
      <Row label="Plays">
        <div className={styles.soundFiles}>
          {plays.map((it) =>
            soundFiles.includes(it) ? (
              <div key={it} className={styles.soundFile}>
                <span>{it}</span>
                <audio
                  controls
                  preload="none"
                  src={url(resourcePackSoundPath(pack, it))}
                  aria-label={`Play ${it}`}
                />
              </div>
            ) : (
              <Muted key={it}>{it} (missing)</Muted>
            ),
          )}
          {plays.length > 1 && <Muted>One at random each time.</Muted>}
        </div>
      </Row>
      <TextField
        label="Files"
        dataPath={at('files')}
        value={(def?.files ?? []).join(', ')}
        placeholder={soundKey.includes(':') ? 'step/grass_1.ogg, …' : `${file} (its own)`}
        list={listId}
        onChange={(text) => {
          const files = text
            .split(',')
            .map((it) => it.trim())
            .filter(Boolean)
          edit((draft) => setSoundValue(draft, soundKey, 'files', files.length ? files : undefined))
        }}
      />
      <Datalist id={listId} values={soundFiles} />
      <NumberField
        label="Volume"
        dataPath={at('volume')}
        value={def?.volume}
        placeholder="1"
        step={0.1}
        min={0}
        onChange={(value) => edit((draft) => setSoundValue(draft, soundKey, 'volume', value))}
      />
      <NumberField
        label="Pitch"
        dataPath={at('pitch')}
        value={def?.pitch}
        placeholder="1"
        step={0.1}
        min={0}
        onChange={(value) => edit((draft) => setSoundValue(draft, soundKey, 'pitch', value))}
      />
      <CheckField
        label="Stream"
        dataPath={at('stream')}
        value={def?.stream ?? false}
        onChange={(value) =>
          edit((draft) => setSoundValue(draft, soundKey, 'stream', value || undefined))
        }
      />
      <TextField
        label="Subtitle"
        dataPath={at('subtitle')}
        value={def?.subtitle}
        placeholder="(none)"
        onChange={(value) => edit((draft) => setSoundValue(draft, soundKey, 'subtitle', value))}
      />
      {def && !soundFiles.includes(file) && (
        <Row label="">
          <Button
            size="small"
            icon="trash"
            onClick={() => {
              edit((draft) => removeSoundEntry(draft, soundKey))
              onDeleted()
            }}
          >
            Remove event
          </Button>
        </Row>
      )}
      {soundFiles.includes(file) && (
        <Row label="">
          <Button
            size="small"
            icon="trash"
            onClick={async () => {
              const ok = await ask.confirm({
                title: 'Delete sound',
                message: `Delete resource_packs/${pack}/sounds/${file}? Scripts playing ${pack}/${soundKey} will play nothing.`,
                confirmLabel: 'Delete',
                danger: true,
              })
              if (ok !== true) return
              await workspace.getState().deletePath(resourcePackSoundPath(pack, file))
              onDeleted()
            }}
          >
            Delete file
          </Button>
        </Row>
      )}
    </Section>
  )
}
