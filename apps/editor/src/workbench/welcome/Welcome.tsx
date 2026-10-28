/** The first screen: recent projects, open a folder, or create a project. */
import { useEffect, useId, useState, type ReactNode } from 'react'
import { Label, Radio, RadioGroup } from 'react-aria-components'
import type { Backend, RecentProject } from '@/core/backend/types'
import { TEMPLATES } from '@/core/templates'
import type { WorkspaceStore } from '@/core/store/workspace'
import { useApp, useWorkspace } from '@/state/providers'
import { Button, IconButton } from '@/ui/Button'
import { cx } from '@/ui/cx'
import { FormActions, FormError, FormLabel } from '@/ui/fields'
import { Icon, type IconName } from '@/ui/Icon'
import { Logo } from '@/ui/Logo'
import { BareTitleBar } from '@/workbench/toolbar/TitleBar'
import styles from './Welcome.module.css'
import { openProjectAt, useWelcome } from './welcomeState'

/** Asks for a project folder and opens it. */
async function pickAndOpenProject(backend: Backend, workspace: WorkspaceStore) {
  const root = await backend.pickFolder('Open a NetherForge project')
  if (root) await openProjectAt(workspace, root)
}

/** What a project can hold, as the welcome screen sums it up. */
const MAKES: { icon: IconName; label: string }[] = [
  { icon: 'cube', label: 'Centities' },
  { icon: 'gem', label: 'Items' },
  { icon: 'slot', label: 'Menus' },
  { icon: 'dialog', label: 'Dialogs' },
  { icon: 'mountain', label: 'Worlds' },
  { icon: 'image', label: 'Resource packs' },
  { icon: 'module', label: 'Lua modules' },
]

export function Welcome() {
  const { backend, workspace } = useApp()
  const appInfo = useWorkspace((s) => s.appInfo)
  const [recent, setRecent] = useState<RecentProject[] | null>(null)
  const creating = useWelcome((s) => s.creating)
  const error = useWelcome((s) => s.error)
  const setCreating = (creating: boolean) => useWelcome.setState({ creating })

  useEffect(() => {
    backend.recentProjects().then(setRecent, () => setRecent([]))
  }, [backend])

  const open = (root: string) => openProjectAt(workspace, root)
  const openFolder = () => pickAndOpenProject(backend, workspace)

  return (
    <main className={styles.welcome}>
      <BareTitleBar />
      <div className={cx(styles.card, creating && styles.narrow)}>
        {creating ? (
          <CreateProject
            onCancel={() => setCreating(false)}
            versions={appInfo?.minecraftVersions ?? []}
            error={error}
          />
        ) : (
          <div className={styles.columns}>
            <section className={styles.intro}>
              <h1 className={styles.title}>
                <Logo size={32} /> NetherForge
              </h1>
              <p className={styles.tagline}>
                Make custom content for your Paper server, run it on a local dev server, and see
                every save hot-reloaded in game.
              </p>
              <ul className={styles.makes} aria-label="What you can make">
                {MAKES.map((it) => (
                  <li key={it.label}>
                    <Icon name={it.icon} size={12} />
                    {it.label}
                  </li>
                ))}
              </ul>
              {error && (
                <FormError role="alert" className={styles.error}>
                  {error}
                </FormError>
              )}
              <div className={styles.actions}>
                <Action
                  icon="plus"
                  title="Create project…"
                  primary
                  onPress={() => setCreating(true)}
                >
                  A new folder, empty or from a template
                </Action>
                <Action icon="folderOpen" title="Open folder…" onPress={() => void openFolder()}>
                  A project with a netherforge.json
                </Action>
              </div>
              {appInfo && <p className={styles.version}>Version {appInfo.version}</p>}
            </section>
            <section className={styles.recentPane}>
              <h2 className={styles.heading}>Recent projects</h2>
              {recent === null ? null : recent.length === 0 ? (
                <div className={styles.empty}>
                  <Icon name="folder" size={28} />
                  <p>Projects you open show up here.</p>
                </div>
              ) : (
                <ul className={styles.recent} aria-label="Recent projects">
                  {recent.map((project) => (
                    <li key={project.root}>
                      <button
                        type="button"
                        className={styles.recentButton}
                        title={project.root}
                        onClick={() => void open(project.root)}
                      >
                        <span className={styles.recentIcon}>
                          <Icon name="folder" size={16} />
                        </span>
                        <span className={styles.recentText}>
                          <span className={styles.recentName}>{project.name}</span>
                          <span className={styles.recentRoot}>{project.root}</span>
                        </span>
                        <Icon name="chevronRight" className={styles.recentGo} />
                      </button>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </div>
        )}
      </div>
    </main>
  )
}

/** One of the welcome screen's two big buttons: a title, named by it alone, and what it does. */
function Action({
  icon,
  title,
  primary,
  onPress,
  children,
}: {
  icon: IconName
  title: string
  primary?: boolean
  onPress: () => void
  children: ReactNode
}) {
  const id = useId()
  return (
    <button
      type="button"
      className={cx(styles.action, primary && styles.primary)}
      aria-describedby={id}
      onClick={onPress}
    >
      <span className={styles.actionIcon}>
        <Icon name={icon} size={18} />
      </span>
      <span className={styles.actionText}>
        <span className={styles.actionTitle}>{title}</span>
        <span id={id} className={styles.actionHint}>
          {children}
        </span>
      </span>
    </button>
  )
}

function CreateProject({
  onCancel,
  versions,
  error: openError,
}: {
  onCancel: () => void
  versions: string[]
  error: string | null
}) {
  const { backend, workspace } = useApp()
  const [name, setName] = useState('My server')
  const [minecraft, setMinecraft] = useState(versions[0] ?? '')
  // A template to start from ('' for an empty project): its files are copied into the new
  // project as its own.
  const [template, setTemplate] = useState('')
  const [folder, setFolder] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const nameId = useId()
  const versionId = useId()

  const create = async () => {
    if (!folder) return
    setBusy(true)
    setError(null)
    try {
      await workspace.getState().createProject(folder, name.trim(), minecraft)
      if (template) await workspace.getState().applyTemplate(template)
      useWelcome.setState({ creating: false })
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const choices = [
    {
      id: '',
      title: 'Empty project',
      summary: 'Just a netherforge.json, ready for your own resources.',
    },
    ...TEMPLATES,
  ]

  return (
    <form
      className={styles.form}
      aria-label="Create project"
      onSubmit={(event) => {
        event.preventDefault()
        void create()
      }}
    >
      <header className={styles.formHeader}>
        <IconButton icon="arrowLeft" label="Back" size={16} onClick={onCancel} />
        <h1 className={styles.formTitle}>Create a project</h1>
      </header>
      {openError && <FormError role="alert">{openError}</FormError>}
      <div className={styles.formRow}>
        <div className={styles.formField}>
          <FormLabel htmlFor={nameId}>Name</FormLabel>
          <input id={nameId} value={name} onChange={(e) => setName(e.target.value)} required />
        </div>
        <div className={styles.formField}>
          <FormLabel htmlFor={versionId}>Minecraft version</FormLabel>
          <select id={versionId} value={minecraft} onChange={(e) => setMinecraft(e.target.value)}>
            {versions.map((version) => (
              <option key={version} value={version}>
                {version}
              </option>
            ))}
          </select>
        </div>
      </div>
      <RadioGroup className={styles.templates} value={template} onChange={setTemplate}>
        <Label className={styles.groupLabel}>Start with</Label>
        <div className={styles.templateGrid}>
          {choices.map((it) => (
            <Radio key={it.id} value={it.id} className={styles.template} aria-label={it.title}>
              <span className={styles.templateTitle}>{it.title}</span>
              <span className={styles.templateSummary}>{it.summary}</span>
            </Radio>
          ))}
        </div>
      </RadioGroup>
      <FormLabel>Folder</FormLabel>
      <div className={styles.folderPick}>
        <Icon name="folder" />
        <code className={cx(!folder && styles.unset)}>{folder ?? 'No folder chosen'}</code>
        <Button
          onClick={async () =>
            setFolder(
              (await backend.pickFolder('Choose an empty folder for the project')) ?? folder,
            )
          }
        >
          Choose…
        </Button>
      </div>
      {error && <FormError role="alert">{error}</FormError>}
      <FormActions>
        <Button onClick={onCancel}>Cancel</Button>
        <Button
          type="submit"
          variant="primary"
          disabled={!folder || !name.trim() || !minecraft || busy}
        >
          Create
        </Button>
      </FormActions>
    </form>
  )
}
