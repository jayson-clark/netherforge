/**
 * A video (`videos/<id>/video.json`, docs/format/video.md): a source file the editor converts for screens. The
 * stage plays the source as it is, says what was converted and whether that still matches the file and the source,
 * and converts it again (ffmpeg, through the backend's `convertVideo`, with its progress); the inspector edits how:
 * its size in maps, its rate, how its colours are dithered, how it fits, the part of the source kept, its sound.
 */
import { useCallback, useEffect, useState } from 'react'
import type { ConvertProgress, VideoInfo } from '@/core/backend/types'
import { setKey } from '@/core/draft'
import type { Dither, VideoFile, VideoFit } from '@/core/format'
import { dirname } from '@/core/paths'
import { InspectorPanel } from '@/editors/shared/docks'
import { EditorBar, EditorScreen, Stage } from '@/editors/shared/EditorLayout'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { useApp, useWorkspace } from '@/state/providers'
import { Button } from '@/ui/Button'
import { CheckField, NumberField, Section, SelectField, TextField } from '@/ui/fields'
import { Hint } from '@/ui/text'
import styles from './VideoEditor.module.css'

const DITHERS: { value: Dither; label: string }[] = [
  { value: 'floyd_steinberg', label: 'Floyd–Steinberg (best for film)' },
  { value: 'ordered', label: 'Ordered (flickers less)' },
  { value: 'none', label: 'None (nearest colour)' },
]

const FITS: { value: VideoFit; label: string }[] = [
  { value: 'contain', label: 'All of it, with bars' },
  { value: 'cover', label: 'Fill the maps, cut' },
  { value: 'stretch', label: 'Stretch to the maps' },
]

const errorText = (error: unknown) => (error instanceof Error ? error.message : String(error))

/** Why what was converted no longer matches [file], in words; empty when it does. */
export function staleness(file: VideoFile, info: VideoInfo): string[] {
  if (!info.converted) return []
  const out: string[] = []
  if (info.columns !== file.width || info.rows !== file.height)
    out.push(`it was converted ${info.columns} by ${info.rows} maps`)
  if (info.fps !== (file.fps ?? 20)) out.push(`at ${info.fps} frames a second`)
  if (info.audio !== (file.audio ?? true) && (file.audio ?? true) === false)
    out.push('with its sound')
  if (info.sourceChanged) out.push('from a source that has changed since')
  return out
}

export function VideoEditor({ path }: { path: string }) {
  const { doc, model, edit } = useModelDoc<VideoFile>(path)
  const { backend } = useApp()
  const files = useWorkspace((s) => s.files)
  const folder = dirname(path)
  const id = folder.split('/').pop() ?? ''
  const [info, setInfo] = useState<VideoInfo | null>(null)
  const [busy, setBusy] = useState(false)
  const [progress, setProgress] = useState<ConvertProgress | null>(null)
  const [message, setMessage] = useState<{ kind: 'error' | 'info'; text: string } | null>(null)
  useFocusRequests(path, (segments) => segments)

  const refresh = useCallback(() => {
    backend.videoInfo(folder).then(setInfo, () => setInfo(null))
  }, [backend, folder])
  // Read again whenever the folder's files change: a conversion, a new source.
  useEffect(refresh, [refresh, files])

  useEffect(() => {
    if (!busy) return
    let unlisten: (() => void) | null = null
    let live = true
    void backend
      .onVideoProgress((event) => {
        if (event.folder === folder) setProgress(event)
      })
      .then((stop) => {
        if (live) unlisten = stop
        else stop()
      })
    return () => {
      live = false
      unlisten?.()
    }
  }, [backend, busy, folder])

  if (!doc) return null
  if (!model) return <RawDocView path={path} />

  const convert = async () => {
    setMessage(null)
    setBusy(true)
    setProgress(null)
    try {
      const made = await backend.convertVideo(folder)
      setMessage({
        kind: 'info',
        text: `Converted ${made.frames} frames (${(made.seconds ?? 0).toFixed(1)} s)${made.audio ? ' with its sound' : model.audio === false ? '' : ', silent: the source has no sound'}.`,
      })
    } catch (error) {
      setMessage({ kind: 'error', text: `Couldn't convert it: ${errorText(error)}` })
    } finally {
      setBusy(false)
      refresh()
    }
  }

  const stale = info ? staleness(model, info) : []
  const percent =
    progress && progress.total > 0
      ? Math.min(100, Math.round((progress.done / progress.total) * 100))
      : null
  return (
    <EditorScreen>
      <EditorBar title={id}>
        <Button
          icon="refresh"
          variant="primary"
          disabled={busy || !info?.sourcePresent}
          onClick={() => void convert()}
        >
          {busy ? 'Converting…' : info?.converted ? 'Convert again' : 'Convert'}
        </Button>
        <EditAsJson path={path} />
      </EditorBar>
      <Stage>
        <div className={styles.stage}>
          {info?.sourcePresent ? (
            <video
              className={styles.video}
              controls
              src={backend.projectFileUrl(`${folder}/${model.source}`)}
            />
          ) : (
            <Hint>
              Put the video in this folder as {model.source}: any file ffmpeg reads (mp4, webm, mov,
              gif…).
            </Hint>
          )}
          <div className={styles.status} role="status" data-testid="video-status">
            {busy ? (
              <>
                <span>
                  {progress?.stage === 'audio'
                    ? 'Converting its sound…'
                    : progress?.stage === 'writing'
                      ? 'Writing the frames…'
                      : `Converting frames${progress ? `: ${progress.done}${progress.total ? ` of ${progress.total}` : ''}` : '…'}`}
                </span>
                <progress max={100} value={percent ?? undefined} aria-label="Converting" />
              </>
            ) : info?.converted ? (
              <span>
                Converted: {info.frames} frames, {info.columns} by {info.rows} maps at {info.fps} a
                second
                {info.audio ? ', with its sound' : ', silent'}.
              </span>
            ) : (
              <span>Not converted yet: screens can't play it until it is.</span>
            )}
            {stale.length > 0 && !busy && (
              <span className={styles.stale}>
                Out of date: {stale.join(', ')}. Convert it again.
              </span>
            )}
            {message && (
              <span className={message.kind === 'error' ? styles.error : undefined}>
                {message.text}
              </span>
            )}
          </div>
        </div>
      </Stage>
      <InspectorPanel>
        <Section title="Source" icon="video">
          <TextField
            label="File"
            dataPath="source"
            value={model.source}
            onChange={(value) => edit((d) => (d.source = value.trim() || d.source))}
          />
          <NumberField
            label="Start (seconds)"
            dataPath="start"
            value={model.start}
            placeholder="0"
            step={0.5}
            min={0}
            onChange={(value) => edit((d) => setKey(d, 'start', value))}
          />
          <NumberField
            label="End (seconds)"
            dataPath="end"
            value={model.end}
            placeholder="its end"
            step={0.5}
            min={0}
            onChange={(value) => edit((d) => setKey(d, 'end', value))}
          />
          <CheckField
            label="Keep its sound"
            dataPath="audio"
            value={model.audio !== false}
            onChange={(on) => edit((d) => setKey(d, 'audio', on ? undefined : false))}
          />
        </Section>
        <Section title="Picture" icon="image">
          <NumberField
            label="Maps across"
            dataPath="width"
            value={model.width}
            step={1}
            min={1}
            onChange={(value) =>
              edit((d) => (d.width = Math.min(16, Math.max(1, Math.round(value ?? 1)))))
            }
          />
          <NumberField
            label="Maps down"
            dataPath="height"
            value={model.height}
            step={1}
            min={1}
            onChange={(value) =>
              edit((d) => (d.height = Math.min(16, Math.max(1, Math.round(value ?? 1)))))
            }
          />
          <NumberField
            label="Frames a second"
            dataPath="fps"
            value={model.fps}
            placeholder="20"
            step={1}
            min={1}
            onChange={(value) =>
              edit((d) => setKey(d, 'fps', value === undefined ? undefined : Math.round(value)))
            }
          />
          <SelectField<Dither>
            label="Dithering"
            dataPath="dither"
            value={model.dither ?? 'floyd_steinberg'}
            options={DITHERS}
            onChange={(value) =>
              edit((d) => setKey(d, 'dither', value === 'floyd_steinberg' ? undefined : value))
            }
          />
          <SelectField<VideoFit>
            label="Fit"
            dataPath="fit"
            value={model.fit ?? 'contain'}
            options={FITS}
            onChange={(value) =>
              edit((d) => setKey(d, 'fit', value === 'contain' ? undefined : value))
            }
          />
          <Hint>
            Every map shown is 16 KiB a frame to each player, so a big video at 20 frames a second
            is a lot to send: the server sends only what changed, and skips frames for a player it
            can't keep up with. Scripts play it with screen:play("{id}").
          </Hint>
        </Section>
      </InspectorPanel>
    </EditorScreen>
  )
}
