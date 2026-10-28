/** Pieces the structure and map screens share: a table of counts, a row of actions. */
import type { ReactNode } from 'react'
import styles from './parts.module.css'

/** Names with counts, two columns, numbers right-aligned. */
export function CountsTable({ label, rows }: { label: string; rows: [ReactNode, ReactNode][] }) {
  return (
    <table className={styles.counts} aria-label={label}>
      <tbody>
        {rows.map(([name, count], index) => (
          <tr key={index}>
            <td>{name}</td>
            <td className={styles.number}>{count}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

/** Buttons at the end of an inspector section. */
export function Actions({ children }: { children: ReactNode }) {
  return <div className={styles.actions}>{children}</div>
}
