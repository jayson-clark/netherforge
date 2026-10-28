/**
 * Every problem: the editor's validation and what the dev server reported. Each opens where it is.
 * In a workspace, the other projects' problems follow, by project; opening one switches to it.
 */
import { useMemo } from 'react'
import type { WorkspaceMember } from '@/core/backend/types'
import type { Problem } from '@/core/format'
import type { MemberProblems } from '@/core/store/workspaceCheck'
import { useApp, useRun, useWorkspace, useWorkspaceFile } from '@/state/providers'
import { focusMember } from '@/workbench/toolbar/WorkspaceSwitcher'
import { Icon } from '@/ui/Icon'
import { Empty } from '@/ui/text'
import styles from './Problems.module.css'

/** Errors and warnings across both sources, for the tab's counts. */
export function useProblemCounts() {
  const problems = useWorkspace((s) => s.problems)
  const serverProblems = useRun((s) => s.serverProblems)
  const workspaceProblems = useWorkspaceFile((s) => s.openProblems)
  const all = [...problems, ...workspaceProblems, ...serverProblems]
  const errors = all.filter((it) => it.severity === 'error').length
  return { errors, warnings: all.length - errors }
}

function ProblemRow({
  problem,
  server,
  onOpen,
}: {
  problem: Problem
  server?: boolean
  onOpen: () => void
}) {
  return (
    <li>
      <button type="button" className={styles.problem} onClick={onOpen}>
        <Icon
          name={problem.severity === 'error' ? 'error' : 'warning'}
          className={problem.severity === 'error' ? styles.error : styles.warning}
        />
        <span className={styles.message}>{problem.message}</span>
        <span className={styles.where}>
          {problem.file}
          {problem.line ? `:${problem.line}` : ''}
          {problem.path ? ` ${problem.path}` : ''}
          {server ? ' · server' : ''}
        </span>
      </button>
    </li>
  )
}

/** The workspace's other projects with problems (or that couldn't be checked), each under its name. */
function OtherProjects({ others, checking }: { others: MemberProblems[]; checking: boolean }) {
  const app = useApp()
  const shown = others.filter((it) => it.problems.length > 0 || it.error !== null)
  if (shown.length === 0) return null
  const open = async (member: WorkspaceMember, problem: Problem) => {
    await focusMember(app, member)
    if (app.workspace.getState().project?.root !== member.root) return
    await app.workspace.getState().openFile(problem.file, {
      line: problem.line,
      column: problem.column,
      jsonPath: problem.path,
    })
  }
  return (
    <section aria-label="Other projects in the workspace">
      <h3 className={styles.heading}>
        Other projects in the workspace{checking ? ' (checking…)' : ''}
      </h3>
      {shown.map(({ member, problems, error }) => (
        <div key={member.root}>
          <h4 className={styles.project}>
            {member.name} <span className={styles.where}>{member.path}</span>
          </h4>
          {error !== null ? (
            <p className={styles.unchecked}>Couldn't check it: {error}</p>
          ) : (
            <ul className={styles.list} aria-label={`Problems in ${member.name}`}>
              {problems.map((problem, index) => (
                <ProblemRow
                  key={index}
                  problem={problem}
                  onOpen={() => void open(member, problem)}
                />
              ))}
            </ul>
          )}
        </div>
      ))}
    </section>
  )
}

export function ProblemsPanel() {
  const { workspace } = useApp()
  const others = useWorkspaceFile((s) => s.others)
  const checking = useWorkspaceFile((s) => s.checking)
  const problems = useWorkspace((s) => s.problems)
  const serverProblems = useRun((s) => s.serverProblems)
  const workspaceProblems = useWorkspaceFile((s) => s.openProblems)
  const all = useMemo(
    () => [
      ...problems.map((problem) => ({ problem, source: 'editor' as const })),
      ...workspaceProblems.map((problem) => ({ problem, source: 'editor' as const })),
      ...serverProblems.map((problem) => ({ problem, source: 'server' as const })),
    ],
    [problems, workspaceProblems, serverProblems],
  )

  const othersHave = others.some((it) => it.problems.length > 0 || it.error !== null)
  if (all.length === 0 && !othersHave) return <Empty>No problems.</Empty>

  const open = (problem: Problem) =>
    void workspace.getState().openFile(problem.file, {
      line: problem.line,
      column: problem.column,
      jsonPath: problem.path,
    })

  return (
    <>
      {all.length > 0 ? (
        <ul className={styles.list} aria-label="Problems">
          {all.map(({ problem, source }, index) => (
            <ProblemRow
              key={index}
              problem={problem}
              server={source === 'server'}
              onOpen={() => open(problem)}
            />
          ))}
        </ul>
      ) : (
        <Empty>No problems in this project.</Empty>
      )}
      <OtherProjects others={others} checking={checking} />
    </>
  )
}
