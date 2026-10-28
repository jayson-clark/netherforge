/**
 * The project's script tests: the last run, each test with its verdict and where it failed. In a
 * workspace, Run Workspace Tests runs every project's; the others' follow, by project, and opening
 * one of their tests switches to that project.
 */
import { useApp, useTests, useWorkspace, useWorkspaceFile } from '@/state/providers'
import { byFile, unpassed } from '@/core/store/tests'
import type { TestReport, TestResult, WorkspaceMember } from '@/core/backend/types'
import { Button } from '@/ui/Button'
import { Icon } from '@/ui/Icon'
import { Empty } from '@/ui/text'
import { focusMember } from '@/workbench/toolbar/WorkspaceSwitcher'
import styles from './Tests.module.css'

/** How many tests didn't pass (the workspace's other projects' too, after a workspace run), for the tab's badge. */
export function useUnpassedCount() {
  return useTests(
    (s) =>
      unpassed(s.report).length + s.others.reduce((sum, it) => sum + unpassed(it.report).length, 0),
  )
}

const place = (result: TestResult) =>
  result.source
    ? `${result.source.file}${result.source.line ? `:${result.source.line}` : ''}`
    : null

const summary = (report: TestReport) =>
  `${report.passed} passed, ${report.failed} failed${report.errored > 0 ? `, ${report.errored} errored` : ''} (${report.durationMillis} ms)`

/** A report's results by file, or why it couldn't run; [onOpen] opens a result where it is. */
function ReportView({
  report,
  onOpen,
}: {
  report: TestReport
  onOpen: (result: TestResult) => void
}) {
  if (report.error) {
    return (
      <div className={styles.error}>
        <p>The tests couldn&apos;t run: {report.error}</p>
        <ul>
          {report.problems.map((problem, index) => (
            <li key={index}>{problem}</li>
          ))}
        </ul>
      </div>
    )
  }
  return (
    <>
      {byFile(report).map(({ file, results }) => (
        <section key={file} aria-label={file}>
          <h3 className={styles.file}>{file}</h3>
          <ul className={styles.list}>
            {results.map((result) => (
              <li key={result.name}>
                <button
                  type="button"
                  className={styles.result}
                  onClick={() => onOpen(result)}
                  aria-label={`${result.status}: ${result.name}`}
                >
                  <Icon
                    name={result.status === 'passed' ? 'check' : 'error'}
                    className={result.status === 'passed' ? styles.passed : styles.failed}
                  />
                  <span className={styles.name}>{result.name}</span>
                  <span className={styles.where}>{result.durationMillis} ms</span>
                </button>
                {result.status !== 'passed' && (
                  <div className={styles.detail}>
                    <div>
                      {place(result) && <span className={styles.where}>{place(result)}: </span>}
                      {result.message}
                    </div>
                    {result.logs && result.logs.length > 0 && (
                      <pre className={styles.logs}>{result.logs.join('\n')}</pre>
                    )}
                    {result.traceback && <pre className={styles.logs}>{result.traceback}</pre>}
                  </div>
                )}
              </li>
            ))}
          </ul>
        </section>
      ))}
    </>
  )
}

export function TestsPanel() {
  const app = useApp()
  const { workspace, tests } = app
  const running = useTests((s) => s.running)
  const report = useTests((s) => s.report)
  const error = useTests((s) => s.error)
  const others = useTests((s) => s.others)
  const root = useWorkspace((s) => s.project?.root)
  const members = useWorkspaceFile((s) => s.info?.members)
  const otherMembers = members?.filter((it) => it.root !== root) ?? []

  const openIn = async (member: WorkspaceMember | null, result: TestResult) => {
    if (member) {
      await focusMember(app, member)
      if (workspace.getState().project?.root !== member.root) return
    }
    const file = result.source?.file ?? result.file
    await workspace.getState().openFile(file, { line: result.source?.line ?? undefined })
  }

  return (
    <div className={styles.panel}>
      <div className={styles.bar}>
        <Button icon="play" onClick={() => void tests.getState().run()} disabled={running}>
          {running ? 'Running…' : 'Run Tests'}
        </Button>
        {otherMembers.length > 0 && (
          <Button
            onClick={() => void tests.getState().runWorkspace(otherMembers)}
            disabled={running}
          >
            Run Workspace Tests
          </Button>
        )}
        {report && !report.error && (
          <span className={styles.summary} role="status">
            {summary(report)}
          </span>
        )}
      </div>
      {error && <p className={styles.error}>{error}</p>}
      {!report && !error && !running && (
        <Empty>
          No run yet. Tests are the project&apos;s <code>*_test.lua</code> files.
        </Empty>
      )}
      {report && !report.error && report.results.length === 0 && (
        <Empty>No tests found. Declare one with nf.test.case in a *_test.lua file.</Empty>
      )}
      {report && <ReportView report={report} onOpen={(result) => void openIn(null, result)} />}
      {others.map(({ member, report: theirs, error: why }) => (
        <section key={member.root} aria-label={`Tests of ${member.name}`}>
          <h3 className={styles.project}>
            {member.name}{' '}
            <span className={styles.where}>
              {member.path}
              {theirs && !theirs.error ? ` · ${summary(theirs)}` : ''}
            </span>
          </h3>
          {why && <p className={styles.error}>{why}</p>}
          {theirs && !theirs.error && theirs.results.length === 0 && <Empty>No tests.</Empty>}
          {theirs && (
            <ReportView report={theirs} onOpen={(result) => void openIn(member, result)} />
          )}
        </section>
      ))}
    </div>
  )
}
