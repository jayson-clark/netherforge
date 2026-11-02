/**
 * A dev network server's group, capacity and role, as a production network would run it: the
 * row under its project on the Network panel's cards, for a project's layout and a workspace's
 * file alike.
 */
import { CommitInput } from './Network'
import styles from './Network.module.css'

/** What a server runs with besides its project; empty fields are left out. */
export interface ServerOptionValues {
  group?: string | null
  capacity?: number | null
  role?: string | null
}

const ROLES = ['trusted', 'standard', 'isolated'] as const

/** [options] without what's empty, or undefined when nothing's left. */
export function cleanOptions(options: ServerOptionValues): ServerOptionValues | undefined {
  const out: ServerOptionValues = {}
  if (options.group?.trim()) out.group = options.group.trim()
  if (options.capacity != null && options.capacity > 0) out.capacity = options.capacity
  if (options.role && options.role !== 'trusted') out.role = options.role
  return Object.keys(out).length > 0 ? out : undefined
}

export function ServerOptionsFields({
  server,
  options,
  onChange,
}: {
  server: string
  options: ServerOptionValues | null | undefined
  onChange: (next: ServerOptionValues | undefined) => void
}) {
  const current = options ?? {}
  const set = (patch: ServerOptionValues) => onChange(cleanOptions({ ...current, ...patch }))
  return (
    <div className={styles.options}>
      <CommitInput
        className={styles.optionInput}
        label={`Group of server ${server}`}
        placeholder="group"
        value={current.group ?? ''}
        onCommit={(group) => set({ group })}
      />
      <CommitInput
        className={styles.optionInput}
        label={`Capacity of server ${server}`}
        placeholder="players"
        value={current.capacity != null ? String(current.capacity) : ''}
        onCommit={(text) => {
          const capacity = Number.parseInt(text, 10)
          set({ capacity: Number.isFinite(capacity) && capacity > 0 ? capacity : null })
        }}
      />
      <select
        className={styles.optionInput}
        aria-label={`Role of server ${server}`}
        title="What the hub lets it do: trusted is everything, standard its own namespace and what's shared, isolated only what's shared"
        value={current.role ?? 'trusted'}
        onChange={(e) => set({ role: e.target.value })}
      >
        {ROLES.map((role) => (
          <option key={role} value={role}>
            {role}
          </option>
        ))}
      </select>
    </div>
  )
}
