/**
 * Modal dialogs: `Modal` for any dialog, drawn with react-aria's
 * `ModalOverlay`/`Modal`/`Dialog` (focus kept inside and given back after,
 * Escape or a click outside closes, the rest of the page inert and hidden
 * from screen readers), and dialogs as promises: `await ask.confirm(...)`,
 * `await ask.prompt(...)`. Native `confirm()`/`prompt()` aren't reliable in
 * every Tauri webview, and these are labelled for Playwright and screen
 * readers.
 */
import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { Dialog, Heading, Modal as AriaModal, ModalOverlay } from 'react-aria-components'
import { create } from 'zustand'
import { Button, IconButton } from './Button'
import { cx } from './cx'
import { FormError, FormLabel } from './fields'
import { Icon, type IconName } from './Icon'
import { Spacer } from './layout'
import styles from './Modal.module.css'

interface PromptRequest {
  kind: 'prompt'
  title: string
  message?: string
  /** Shown beside the title: what's being made or renamed. */
  icon?: IconName
  label: string
  initial: string
  confirmLabel: string
  /** An error message for invalid input, or null. */
  validate?: (value: string) => string | null
  /** Values offered as the field is typed in. */
  suggestions?: readonly string[]
  resolve: (value: string | null) => void
}

interface ConfirmRequest {
  kind: 'confirm'
  title: string
  message: string
  icon?: IconName
  confirmLabel: string
  danger?: boolean
  /** A third choice, e.g. "Don't save". */
  alternative?: string
  resolve: (value: boolean | 'alternative') => void
}

type Request = PromptRequest | ConfirmRequest

const useDialogs = create<{ current: Request | null }>(() => ({ current: null }))

function open(request: Request) {
  useDialogs.setState({ current: request })
}

export const ask = {
  prompt(
    options: Omit<PromptRequest, 'kind' | 'resolve' | 'initial' | 'confirmLabel'> & {
      initial?: string
      confirmLabel?: string
    },
  ): Promise<string | null> {
    return new Promise((resolve) =>
      open({ kind: 'prompt', initial: '', confirmLabel: 'OK', ...options, resolve }),
    )
  },
  confirm(
    options: Omit<ConfirmRequest, 'kind' | 'resolve' | 'confirmLabel'> & { confirmLabel?: string },
  ) {
    return new Promise<boolean | 'alternative'>((resolve) =>
      open({ kind: 'confirm', confirmLabel: 'OK', ...options, resolve }),
    )
  },
}

/**
 * A dialog of a set width (`wide` for pickers and diffs), so long text wraps
 * rather than stretching it: a title with an optional icon, the body, and
 * its buttons in the footer.
 */
export function Modal({
  title,
  icon,
  tone,
  children,
  onClose,
  footer,
  wide,
}: {
  title: string
  icon?: IconName
  /** Colours the icon: `danger` for something that can't be taken back. */
  tone?: 'danger'
  children: ReactNode
  onClose: () => void
  footer?: ReactNode
  wide?: boolean
}) {
  return (
    <ModalOverlay
      isOpen
      isDismissable
      onOpenChange={(open) => !open && onClose()}
      className={styles.backdrop}
    >
      <AriaModal className={cx(styles.modal, wide && styles.wide)}>
        <Dialog className={styles.dialog}>
          <header className={styles.header}>
            {icon && (
              <span className={cx(styles.headerIcon, tone === 'danger' && styles.danger)}>
                <Icon name={icon} size={16} />
              </span>
            )}
            <Heading slot="title" className={styles.title}>
              {title}
            </Heading>
            <IconButton icon="close" label="Close dialog" size={14} onClick={onClose} />
          </header>
          <div className={styles.body}>{children}</div>
          {footer && <div className={styles.footer}>{footer}</div>}
        </Dialog>
      </AriaModal>
    </ModalOverlay>
  )
}

export function DialogHost() {
  const current = useDialogs((s) => s.current)
  if (!current) return null
  const close = () => useDialogs.setState({ current: null })
  if (current.kind === 'prompt') {
    return (
      <PromptDialog
        key={current.title + current.initial}
        request={current}
        onDone={(value) => {
          close()
          current.resolve(value)
        }}
      />
    )
  }
  const done = (value: boolean | 'alternative') => {
    close()
    current.resolve(value)
  }
  return (
    <Modal
      title={current.title}
      icon={current.icon ?? (current.danger ? 'warning' : undefined)}
      tone={current.danger ? 'danger' : undefined}
      onClose={() => done(false)}
      footer={
        <>
          {current.alternative && (
            <Button onClick={() => done('alternative')}>{current.alternative}</Button>
          )}
          <Spacer />
          <Button onClick={() => done(false)}>Cancel</Button>
          <Button
            variant={current.danger ? 'danger' : 'primary'}
            autoFocus
            onClick={() => done(true)}
          >
            {current.confirmLabel}
          </Button>
        </>
      }
    >
      <p className={styles.message}>{current.message}</p>
    </Modal>
  )
}

function PromptDialog({
  request,
  onDone,
}: {
  request: PromptRequest
  onDone: (value: string | null) => void
}) {
  const [value, setValue] = useState(request.initial)
  const input = useRef<HTMLInputElement>(null)
  const id = useId()
  const error = request.validate?.(value) ?? null
  useEffect(() => input.current?.select(), [])
  const submit = () => {
    if (!error) onDone(value)
  }
  return (
    <Modal
      title={request.title}
      icon={request.icon}
      onClose={() => onDone(null)}
      footer={
        <>
          <Spacer />
          <Button onClick={() => onDone(null)}>Cancel</Button>
          <Button variant="primary" disabled={!!error} onClick={submit}>
            {request.confirmLabel}
          </Button>
        </>
      }
    >
      {request.message && <p className={styles.message}>{request.message}</p>}
      <FormLabel htmlFor={id}>{request.label}</FormLabel>
      <input
        id={id}
        className={styles.input}
        spellCheck={false}
        autoComplete="off"
        ref={input}
        autoFocus
        value={value}
        aria-invalid={!!error}
        // Red only once something is typed: an empty field isn't a mistake yet.
        data-error={(error && value) || undefined}
        // Why it's refused is read with the field.
        aria-describedby={error && value ? `${id}-error` : undefined}
        onChange={(event) => setValue(event.target.value)}
        onKeyDown={(event) => event.key === 'Enter' && submit()}
        list={request.suggestions ? `${id}-suggestions` : undefined}
      />
      {request.suggestions && (
        <datalist id={`${id}-suggestions`}>
          {request.suggestions.map((it) => (
            <option key={it} value={it} />
          ))}
        </datalist>
      )}
      {error && value && <FormError id={`${id}-error`}>{error}</FormError>}
    </Modal>
  )
}
