/**
 * A model document shown as JSON in Monaco: because it doesn't parse, or
 * because the user chose "Edit as JSON". Saving writes it canonically when
 * it parses (see the workspace store).
 */
import { lazy, Suspense } from 'react'
import { useApp, useWorkspace } from '@/state/providers'
import { Button } from '@/ui/Button'
import { Bar, Spacer } from '@/ui/layout'
import { Empty, Tone } from '@/ui/text'
import styles from './RawDocView.module.css'

// Monaco loads only when a document is actually shown as JSON.
const CodeEditor = lazy(() =>
  import('@/editors/script/CodeEditor').then((m) => ({ default: m.CodeEditor })),
)

export function RawDocView({ path }: { path: string }) {
  const { workspace } = useApp()
  const problem = useWorkspace((s) => s.docs[path]?.parseProblem ?? null)
  return (
    <div className={styles.raw}>
      <Bar>
        {problem ? (
          <Tone tone="error" role="alert">
            This file doesn&apos;t parse
            {problem.line ? ` (line ${problem.line})` : ''}: {problem.message}
          </Tone>
        ) : (
          <span>Editing as JSON. Saving writes it canonically.</span>
        )}
        <Spacer />
        <Button size="small" onClick={() => workspace.getState().setRaw(path, false)}>
          Back to visual editor
        </Button>
      </Bar>
      <Suspense fallback={<Empty>Loading editor…</Empty>}>
        <CodeEditor path={path} language="json" />
      </Suspense>
    </div>
  )
}
