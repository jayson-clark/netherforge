/**
 * Inspector fields. Each commits a whole value (on Enter, blur or change),
 * never per keystroke, so one edit is one undo step. Number fields can also
 * be scrubbed by dragging their label, which is a gesture: the store groups
 * the whole drag into one undo step.
 *
 * Every field carries `data-path` (its JSON path inside the node, like
 * `display.block`) so a problem in the Problems panel can focus it, and the
 * text fields `data-commit` (`clean` once what's typed is committed, else
 * `draft`): undo in a clean field is the document's, in a draft the field's.
 */
import { useId, useState, type HTMLAttributes, type ReactNode } from 'react'
import { cx } from './cx'
import styles from './fields.module.css'
import { Icon, type IconName } from './Icon'

export interface GestureHandlers {
  onGestureStart?: () => void
  onGestureEnd?: () => void
}

export function Row({
  label,
  children,
  htmlFor,
}: {
  label: ReactNode
  children: ReactNode
  htmlFor?: string
}) {
  return (
    <div className={styles.row}>
      <label className={styles.label} htmlFor={htmlFor}>
        {label}
      </label>
      <div className={styles.value}>{children}</div>
    </div>
  )
}

const format = (value: number | undefined) =>
  value === undefined ? '' : String(Math.round(value * 10000) / 10000)

export function NumberInput({
  value,
  onChange,
  label,
  placeholder,
  step = 0.1,
  dataPath,
  id,
  onGestureStart,
  onGestureEnd,
  min,
}: {
  value: number | undefined
  /** Undefined when cleared: the key goes back to its default. */
  onChange: (value: number | undefined) => void
  label: string
  placeholder?: string
  step?: number
  dataPath?: string
  id?: string
  min?: number
} & GestureHandlers) {
  const [text, setText] = useState(format(value))
  // Follow the value when it changes from outside (undo, a scrub, the arrow
  // keys) unless something uncommitted is typed.
  const [shown, setShown] = useState(value)
  if (shown !== value) {
    setShown(value)
    if (text === format(shown)) setText(format(value))
  }

  const commit = () => {
    const trimmed = text.trim()
    if (trimmed === '') {
      if (value !== undefined) onChange(undefined)
      return
    }
    const parsed = Number(trimmed)
    if (!Number.isFinite(parsed)) {
      setText(format(value))
      return
    }
    const next = min !== undefined ? Math.max(min, parsed) : parsed
    if (next !== value) onChange(next)
    setText(format(next))
  }

  return (
    <input
      id={id}
      className={styles.number}
      aria-label={label}
      data-path={dataPath}
      inputMode="decimal"
      data-commit={text === format(value) ? 'clean' : 'draft'}
      value={text}
      placeholder={placeholder}
      onChange={(event) => setText(event.target.value)}
      onBlur={commit}
      onKeyDown={(event) => {
        if (event.key === 'Enter') {
          commit()
          ;(event.target as HTMLInputElement).select()
        } else if (event.key === 'Escape') {
          setText(format(value))
          ;(event.target as HTMLInputElement).blur()
        } else if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
          event.preventDefault()
          const delta = (event.key === 'ArrowUp' ? 1 : -1) * step * (event.shiftKey ? 10 : 1)
          const base =
            value ?? (placeholder && Number.isFinite(Number(placeholder)) ? Number(placeholder) : 0)
          const next = Math.round((base + delta) * 10000) / 10000
          onChange(min !== undefined ? Math.max(min, next) : next)
        }
      }}
      onPointerDown={(event) => {
        // Alt-drag on the input scrubs it, like dragging a label.
        if (!event.altKey) return
        scrub(event, value ?? 0, step, onChange, { onGestureStart, onGestureEnd })
      }}
    />
  )
}

/** Drags a number horizontally as one gesture. */
function scrub(
  event: React.PointerEvent,
  start: number,
  step: number,
  onChange: (value: number) => void,
  gesture: GestureHandlers,
) {
  event.preventDefault()
  const x0 = event.clientX
  let moved = false
  const move = (e: PointerEvent) => {
    if (!moved) {
      moved = true
      gesture.onGestureStart?.()
    }
    const pixels = e.clientX - x0
    const value = start + Math.round(pixels / 4) * step * (e.shiftKey ? 10 : 1)
    onChange(Math.round(value * 10000) / 10000)
  }
  const up = () => {
    window.removeEventListener('pointermove', move)
    window.removeEventListener('pointerup', up)
    if (moved) gesture.onGestureEnd?.()
  }
  window.addEventListener('pointermove', move)
  window.addEventListener('pointerup', up)
}

const AXES = ['X', 'Y', 'Z'] as const

export function Vec3Field({
  label,
  value,
  defaultValue,
  onChange,
  dataPath,
  step = 0.1,
  ...gesture
}: {
  label: string
  value: [number, number, number] | undefined
  defaultValue: [number, number, number]
  onChange: (value: [number, number, number]) => void
  dataPath?: string
  step?: number
} & GestureHandlers) {
  const current = value ?? defaultValue
  return (
    <div className={styles.row} data-path={dataPath}>
      <span
        className={cx(styles.label, styles.scrub)}
        title="Drag to scrub X; drag an axis letter to scrub that axis"
        onPointerDown={(event) =>
          scrub(event, current[0], step, (x) => onChange([x, current[1], current[2]]), gesture)
        }
      >
        {label}
      </span>
      <div className={cx(styles.value, styles.vec3)}>
        {AXES.map((axis, i) => {
          const set = (next: number | undefined) => {
            const out = [...current] as [number, number, number]
            out[i] = next ?? defaultValue[i]!
            onChange(out)
          }
          return (
            <span key={axis} className={styles.axis} data-axis={axis}>
              {/* The axis letter scrubs that axis alone. */}
              <span
                className={styles.axisLetter}
                aria-hidden="true"
                onPointerDown={(event) => scrub(event, current[i]!, step, set, gesture)}
              >
                {axis}
              </span>
              <NumberInput
                label={`${label} ${axis}`}
                dataPath={dataPath ? `${dataPath}[${i}]` : undefined}
                value={current[i]}
                step={step}
                onChange={set}
                {...gesture}
              />
            </span>
          )
        })}
      </div>
    </div>
  )
}

export function NumberField({ label, ...props }: Parameters<typeof NumberInput>[0]) {
  const id = useId()
  return (
    <Row label={label} htmlFor={id}>
      <NumberInput id={id} label={label} {...props} />
    </Row>
  )
}

export function TextField({
  label,
  value,
  onChange,
  dataPath,
  placeholder,
  list,
  multiline,
}: {
  label: string
  value: string | undefined
  onChange: (value: string) => void
  dataPath?: string
  placeholder?: string
  list?: string
  multiline?: boolean
}) {
  const id = useId()
  const [text, setText] = useState(value ?? '')
  // Follow the value when it changes from outside (undo, another field).
  const [shown, setShown] = useState(value)
  if (shown !== value) {
    setShown(value)
    setText(value ?? '')
  }
  const commit = () => {
    if (text !== (value ?? '')) onChange(text)
  }
  const common = {
    id,
    'aria-label': label,
    'data-path': dataPath,
    'data-commit': text === (value ?? '') ? 'clean' : 'draft',
    value: text,
    placeholder,
    onBlur: commit,
  }
  return (
    <Row label={label} htmlFor={id}>
      {multiline ? (
        <textarea
          {...common}
          rows={3}
          onChange={(event) => setText(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) commit()
          }}
        />
      ) : (
        <input
          {...common}
          list={list}
          onChange={(event) => setText(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') commit()
            if (event.key === 'Escape') setText(value ?? '')
          }}
        />
      )}
    </Row>
  )
}

const jsonText = (value: Record<string, unknown> | undefined) =>
  value === undefined ? '' : JSON.stringify(value, null, 2)

/**
 * A JSON object (`properties`, a stack's `data`), typed as text: committed on blur or
 * Ctrl/Cmd+Enter when it parses as an object, cleared (undefined) when empty. What doesn't parse
 * stays typed, with why, and commits nothing.
 */
export function JsonObjectField({
  label,
  value,
  onChange,
  dataPath,
  placeholder = '{ "key": "value" }',
}: {
  label: string
  value: Record<string, unknown> | undefined
  onChange: (value: Record<string, unknown> | undefined) => void
  dataPath?: string
  placeholder?: string
}) {
  const id = useId()
  const [text, setText] = useState(jsonText(value))
  const [error, setError] = useState<string | null>(null)
  const [shown, setShown] = useState(value)
  if (shown !== value) {
    setShown(value)
    setText(jsonText(value))
    setError(null)
  }
  const commit = () => {
    const trimmed = text.trim()
    if (trimmed === '') {
      setError(null)
      if (value !== undefined) onChange(undefined)
      return
    }
    let parsed: unknown
    try {
      parsed = JSON.parse(trimmed)
    } catch (e) {
      setError(`Not JSON: ${(e as Error).message}`)
      return
    }
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      setError('A JSON object, in { }')
      return
    }
    setError(null)
    if (JSON.stringify(parsed) !== JSON.stringify(value))
      onChange(parsed as Record<string, unknown>)
  }
  return (
    <Row label={label} htmlFor={id}>
      <div className={styles.json}>
        <textarea
          id={id}
          aria-label={label}
          data-path={dataPath}
          data-commit={text === jsonText(value) ? 'clean' : 'draft'}
          rows={Math.min(10, Math.max(3, text.split('\n').length))}
          value={text}
          placeholder={placeholder}
          spellCheck={false}
          onChange={(event) => setText(event.target.value)}
          onBlur={commit}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) commit()
          }}
        />
        {error && <FormError role="alert">{error}</FormError>}
      </div>
    </Row>
  )
}

export function SelectField<T extends string>({
  label,
  value,
  options,
  onChange,
  dataPath,
}: {
  label: string
  value: T
  options: readonly (T | { value: T; label: string })[]
  onChange: (value: T) => void
  dataPath?: string
}) {
  const id = useId()
  return (
    <Row label={label} htmlFor={id}>
      <select
        id={id}
        aria-label={label}
        data-path={dataPath}
        value={value}
        onChange={(e) => onChange(e.target.value as T)}
      >
        {options.map((option) => {
          const item = typeof option === 'string' ? { value: option, label: option } : option
          return (
            <option key={item.value} value={item.value}>
              {item.label}
            </option>
          )
        })}
      </select>
    </Row>
  )
}

/** How a colour is written: `#rrggbb`. */
const COLOR = /^#[0-9a-fA-F]{6}$/

/**
 * A colour, `#rrggbb`, or none (the key's default, which [placeholder] says). A swatch opens the
 * system's picker, which commits as it's picked; the text commits on Enter or blur, a colour
 * written another way is put back. The clear button removes it.
 */
export function ColorField({
  label,
  value,
  onChange,
  dataPath,
  placeholder,
}: {
  label: string
  value: string | undefined
  /** Lower-case `#rrggbb`, or undefined when cleared. */
  onChange: (value: string | undefined) => void
  dataPath?: string
  placeholder?: string
}) {
  const id = useId()
  const [text, setText] = useState(value ?? '')
  const [shown, setShown] = useState(value)
  if (shown !== value) {
    setShown(value)
    setText(value ?? '')
  }
  const commit = (next: string) => {
    const trimmed = next.trim()
    if (trimmed === '') {
      if (value !== undefined) onChange(undefined)
      return
    }
    if (!COLOR.test(trimmed)) {
      setText(value ?? '')
      return
    }
    const color = trimmed.toLowerCase()
    if (color !== value) onChange(color)
    setText(color)
  }
  return (
    <Row label={label} htmlFor={id}>
      <input
        type="color"
        className={styles.swatch}
        aria-label={`${label} picker`}
        value={value ?? '#000000'}
        data-unset={value === undefined || undefined}
        onChange={(event) => commit(event.target.value)}
      />
      <input
        id={id}
        className={styles.number}
        aria-label={label}
        data-path={dataPath}
        data-commit={text === (value ?? '') ? 'clean' : 'draft'}
        value={text}
        placeholder={placeholder}
        onChange={(event) => setText(event.target.value)}
        onBlur={() => commit(text)}
        onKeyDown={(event) => {
          if (event.key === 'Enter') commit(text)
          if (event.key === 'Escape') setText(value ?? '')
        }}
      />
      {value !== undefined && (
        <button
          type="button"
          className={styles.clear}
          aria-label={`Clear ${label}`}
          onClick={() => onChange(undefined)}
        >
          <Icon name="close" size={12} />
        </button>
      )}
    </Row>
  )
}

/** A boolean that can also be "default" (absent), for keys whose default the reader knows. */
export function TriStateField({
  label,
  value,
  defaultLabel,
  onChange,
  dataPath,
}: {
  label: string
  value: boolean | undefined
  defaultLabel: string
  onChange: (value: boolean | undefined) => void
  dataPath?: string
}) {
  const current = value === undefined ? 'default' : value ? 'on' : 'off'
  return (
    <SelectField
      label={label}
      dataPath={dataPath}
      value={current}
      options={[
        { value: 'default', label: `Default (${defaultLabel})` },
        { value: 'on', label: 'On' },
        { value: 'off', label: 'Off' },
      ]}
      onChange={(next) => onChange(next === 'default' ? undefined : next === 'on')}
    />
  )
}

export function CheckField({
  label,
  value,
  onChange,
  dataPath,
}: {
  label: string
  value: boolean
  onChange: (value: boolean) => void
  dataPath?: string
}) {
  // Not a `Row`: a yes-or-no reads as one line, box first, its label free to use the whole width.
  return (
    <label className={styles.checkRow}>
      <input
        type="checkbox"
        data-path={dataPath}
        checked={value}
        onChange={(e) => onChange(e.target.checked)}
      />
      <span className={styles.checkLabel}>{label}</span>
    </label>
  )
}

/** A collapsible inspector section with an optional enable toggle (for optional node parts). */
export function Section({
  title,
  icon,
  enabled,
  onToggle,
  children,
  actions,
}: {
  title: string
  /** Drawn before the title, so a long inspector is easy to scan. */
  icon?: IconName
  enabled?: boolean
  onToggle?: (enabled: boolean) => void
  children?: ReactNode
  actions?: ReactNode
}) {
  const [open, setOpen] = useState(true)
  const id = useId()
  return (
    <section
      className={styles.section}
      aria-labelledby={id}
      data-off={(onToggle && !enabled) || undefined}
    >
      <header className={styles.sectionHeader}>
        <button
          type="button"
          className={styles.sectionTitle}
          id={id}
          aria-expanded={open}
          onClick={() => setOpen(!open)}
        >
          <Icon
            name="chevronRight"
            size={12}
            className={cx(styles.caret, open && styles.caretOpen)}
          />
          {icon && <Icon name={icon} size={14} className={styles.sectionIcon} />}
          <span className={styles.sectionName}>{title}</span>
        </button>
        <span className={styles.sectionActions}>
          {actions}
          {onToggle && (
            <input
              type="checkbox"
              className={styles.switch}
              aria-label={`${title} enabled`}
              checked={enabled ?? false}
              onChange={(event) => onToggle(event.target.checked)}
            />
          )}
        </span>
      </header>
      {open && (enabled ?? true) && <div className={styles.sectionBody}>{children}</div>}
    </section>
  )
}

/** Suggestions for a text field's `list`. */
export function Datalist({ id, values }: { id: string; values: string[] }) {
  return (
    <datalist id={id}>
      {values.map((value) => (
        <option key={value} value={value} />
      ))}
    </datalist>
  )
}

/** A checkbox with its label beside it. */
export function Check({
  label,
  checked,
  onChange,
  disabled,
  title,
}: {
  label: ReactNode
  checked: boolean
  onChange: (checked: boolean) => void
  disabled?: boolean
  title?: string
}) {
  return (
    <label className={styles.check} title={title}>
      <input
        type="checkbox"
        checked={checked}
        disabled={disabled}
        onChange={(event) => onChange(event.target.checked)}
      />
      {label}
    </label>
  )
}

/** A bordered group of rows (a box, a curve key) with an optional header line. */
export function FieldGroup({
  header,
  className,
  children,
  ...rest
}: HTMLAttributes<HTMLDivElement> & { header?: ReactNode }) {
  return (
    <div className={cx(styles.group, className)} {...rest}>
      {header && <div className={styles.groupHeader}>{header}</div>}
      {children}
    </div>
  )
}

/** A form's field label (dialogs, settings, the welcome screen), above its input. */
export function FormLabel({ htmlFor, children }: { htmlFor?: string; children: ReactNode }) {
  return (
    <label htmlFor={htmlFor} className={styles.formLabel}>
      {children}
    </label>
  )
}

/** Why a form's value can't be used, under it (pass `role="alert"` when it appears after an action). */
export function FormError({ className, ...rest }: HTMLAttributes<HTMLParagraphElement>) {
  return <p className={cx(styles.formError, className)} {...rest} />
}

/** A form's buttons, at its end. */
export function FormActions({ children }: { children: ReactNode }) {
  return <div className={styles.formActions}>{children}</div>
}

/** The class that briefly outlines a field a problem points at (see `editors/shared/focus.ts`). */
export const FLASH_CLASS = styles.flash
