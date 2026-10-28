/**
 * The cutscene editor: the camera path in 3D over the timeline, with the
 * cutscene's own fields in the inspector dock. The preview is format's own
 * `CameraPath` (`cutsceneDirector`), so it shows the shot the player sees.
 * A key is selected by its timeline row (or its marker in the viewport), and
 * its fields sit beside the timeline; problems about a key select it and
 * focus the field.
 */
import { useCallback, useMemo } from 'react'
import { cutsceneDirector, type CutsceneFile } from '@/core/format'
import { resourceOf } from '@/core/paths'
import { setKey } from '@/core/draft'
import { usePrimary, useView, useViewState } from '@/editors/views'
import { CheckField, NumberField, Section } from '@/ui/fields'
import { Hint } from '@/ui/text'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen } from '@/editors/shared/EditorLayout'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { CutsceneTimeline, timelineLength } from './CutsceneTimeline'
import { CutsceneViewport, TRAIL_POINTS } from './CutsceneViewport'
import { lengthOf, setLength, type CutscenePick } from './ops'
import type { PreviewState } from './view'

export function CutsceneEditor({ path }: { path: string }) {
  const { doc, model, edit } = useModelDoc<CutsceneFile>(path)
  const view = useView('cutscene', path)
  const selected = usePrimary('cutscene', path)
  // The preview is the document's view state, so it survives a tab switch.
  const preview = useViewState('cutscene', path)
  const setPreview = useCallback((patch: Partial<PreviewState>) => view.update(patch), [view])
  const id = resourceOf(path)?.id ?? 'cutscene'
  const select = useCallback(
    (pick: CutscenePick | null) => (pick ? view.select(pick) : view.select()),
    [view],
  )

  useFocusRequests(path, (segments) => {
    const [first, second, third] = segments
    if (first === 'camera' && (second === 'position' || second === 'rotation')) {
      if (typeof third !== 'number') return segments
      select({ track: second, index: third })
      return segments
    }
    if (first === 'cues' && typeof second === 'number') {
      select({ track: 'cues', index: second })
      return segments
    }
    select(null)
    return segments
  })

  // Compiled once per model: the playhead only asks it where the camera is.
  const director = useMemo(
    () => (model ? cutsceneDirector(id, JSON.stringify(model)) : null),
    [id, model],
  )
  const length = model ? timelineLength(model) : 1
  const time = Math.min(preview.time, length)
  const shot = useMemo(() => director?.shot(time) ?? null, [director, time])
  const trail = useMemo(() => director?.trail(TRAIL_POINTS) ?? null, [director])

  if (!doc) return null
  if (!model || !shot || !trail) return <RawDocView path={path} />

  return (
    <EditorScreen>
      <EditorBar title={id}>
        <EditAsJson path={path} />
      </EditorBar>
      <CutsceneViewport
        path={path}
        model={model}
        shot={shot}
        trail={trail}
        selected={selected}
        onSelect={select}
        onMissed={() => select(null)}
      />
      <CutsceneTimeline
        path={path}
        model={model}
        shot={shot}
        preview={preview}
        setPreview={setPreview}
        selected={selected}
        onSelect={select}
        edit={edit}
      />
      <InspectorPanel>
        <CutsceneInspector model={model} edit={edit} />
      </InspectorPanel>
    </EditorScreen>
  )
}

/** What the cutscene as a whole says: how long it is and whether the player may skip it. */
function CutsceneInspector({
  model,
  edit,
}: {
  model: CutsceneFile
  edit: (recipe: (draft: CutsceneFile) => void) => void
}) {
  return (
    <Section title="Cutscene" icon="film">
      <NumberField
        label="Length (seconds)"
        dataPath="length"
        value={model.length}
        placeholder={String(lengthOf(model))}
        step={0.5}
        min={0}
        onChange={(value) => edit((draft) => setLength(draft, value))}
      />
      <CheckField
        label="Skippable"
        dataPath="skippable"
        value={model.skippable ?? false}
        onChange={(value) => edit((draft) => setKey(draft, 'skippable', value || undefined))}
      />
      <Hint>
        Played with <code>nf.cutscenes.play(player, id)</code>. The camera's coordinates are the
        world's, or measured from the <code>origin</code> a script passes. A skippable cutscene ends
        when the player sneaks.
      </Hint>
    </Section>
  )
}
