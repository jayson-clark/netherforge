/**
 * The settings tab: Minecraft (installs, client asset import, cache status
 * for the project's target), Server (whether the project is trusted, memory, port, JVM arguments),
 * Server-owner settings (the values the dev server runs the project's
 * settings with), Updates, and Agents (the project's AGENTS.md). The outline
 * lists the pages.
 */
import { useEffect, useState } from 'react'
import { isBackendError } from '@/core/backend/types'
import type {
  CacheStatus,
  ImportProgress,
  McpStatus,
  MinecraftInstall,
  Settings,
} from '@/core/backend/types'
import { forgetClientAssets } from '@/minecraft/client/assets'
import { useApp, useUpdates, useWorkspace } from '@/state/providers'
import { SETTINGS_PATH } from '@/core/store/tabs'
import { EditorBar, EditorScreen, Page } from '@/editors/shared/EditorLayout'
import { AGENT_FILES } from '@/core/format'
import { claudeAddCommand, MCP_CONFIG_FILE } from '@/core/mcp/config'
import { askToTrust, askToUntrust } from '@/workbench/TrustBanner'
import { useInstallUpdate } from '@/workbench/UpdateBanner'
import { Badge } from '@/ui/Badge'
import { Button } from '@/ui/Button'
import { Callout } from '@/ui/Callout'
import { ask } from '@/ui/dialogs'
import { Check, FormActions, FormError, FormLabel } from '@/ui/fields'
import { Hint, Muted } from '@/ui/text'
import styles from './SettingsScreen.module.css'
import { OwnerSettings } from './OwnerSettings'
import { SETTINGS_PAGES, settingsPageOf } from './pages'

/** The settings tab: the page the outline picked, in the editor area, scrolling inside it. */
export function SettingsScreen() {
  const page = settingsPageOf(useWorkspace((s) => s.views[SETTINGS_PATH]?.selection.at(-1)))
  const title = SETTINGS_PAGES.find((it) => it.id === page)!.title
  return (
    <EditorScreen aria-label="Settings">
      <EditorBar title={`Settings › ${title}`} />
      <Page>
        <div className={styles.body} role="region" aria-label={`${title} settings`}>
          <h2 className={styles.title}>{title}</h2>
          {page === 'minecraft' ? (
            <MinecraftSettings />
          ) : page === 'server' ? (
            <>
              <ProjectTrust />
              <ServerSettings />
            </>
          ) : page === 'owner' ? (
            <OwnerSettings />
          ) : page === 'updates' ? (
            <UpdateSettings />
          ) : (
            <AgentSettings />
          )}
        </div>
      </Page>
    </EditorScreen>
  )
}

function MinecraftSettings() {
  const { backend, workspace } = useApp()
  const target = useWorkspace((s) => s.minecraft)
  const [installs, setInstalls] = useState<MinecraftInstall[] | null>(null)
  const [status, setStatus] = useState<Record<string, CacheStatus>>({})
  const [progress, setProgress] = useState<ImportProgress | null>(null)
  const [error, setError] = useState<string | null>(null)

  const versions = [
    ...new Set([
      ...(target ? [target] : []),
      ...(installs ?? []).flatMap((it) => it.versions.map((v) => v.version)),
    ]),
  ]

  const [generation, setGeneration] = useState(0)
  const refresh = () => setGeneration((it) => it + 1)

  useEffect(() => {
    let live = true
    void (async () => {
      const found = await backend.findInstalls().catch(() => [])
      const all = [
        ...new Set([
          ...(target ? [target] : []),
          ...found.flatMap((it) => it.versions.map((v) => v.version)),
        ]),
      ]
      const entries = await Promise.all(
        all.map(async (v) => [v, await backend.cacheStatus(v).catch(() => null)] as const),
      )
      if (!live) return
      setInstalls(found)
      setStatus(
        Object.fromEntries(entries.filter(([, s]) => s !== null)) as Record<string, CacheStatus>,
      )
    })()
    return () => {
      live = false
    }
  }, [backend, target, generation])

  useEffect(() => {
    const unlisteners: (() => void)[] = []
    let live = true
    const keep = (unlisten: () => void) => (live ? unlisteners.push(unlisten) : unlisten())
    void backend.onImportProgress((event) => setProgress(event)).then(keep)
    // An import (or a dev server's export) finished: show the new status.
    void backend.onCacheChanged(() => setGeneration((it) => it + 1)).then(keep)
    return () => {
      live = false
      for (const unlisten of unlisteners) unlisten()
    }
  }, [backend])

  const importJar = async (version: string, jar: string) => {
    setError(null)
    setProgress({ version, done: 0, total: 1 })
    try {
      await backend.importClient(version, jar)
      forgetClientAssets(version)
      refresh()
      if (version === target) await workspace.getState().refreshGameData()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setProgress(null)
    }
  }

  const pickJar = async () => {
    const jar = await backend.pickJar('Choose a Minecraft client jar')
    if (!jar) return
    const version = await ask.prompt({
      title: 'Which version is this jar?',
      label: 'Minecraft version',
      initial: target ?? '',
      confirmLabel: 'Import',
      validate: (value) => (/^\d+(\.\d+)*$/.test(value.trim()) ? null : 'A version like 26.3'),
    })
    if (version) await importJar(version.trim(), jar)
  }

  const jarFor = (version: string) =>
    installs
      ?.flatMap((install) => install.versions.map((v) => ({ ...v, launcher: install.launcher })))
      .find((v) => v.version === version)

  const targetStatus = target ? status[target] : undefined
  const targetJar = target ? jarFor(target) : undefined

  return (
    <div>
      {target && (
        <Callout
          tone={targetStatus?.client ? 'ok' : 'warning'}
          role="status"
          aria-label="Target version status"
        >
          <strong>This project targets Minecraft {target}.</strong>{' '}
          {targetStatus?.client
            ? 'Its client assets are imported: previews show real models and textures.'
            : targetJar
              ? `Found it in ${targetJar.launcher}. Import its client assets to see real models in the viewport.`
              : `It isn't installed in any launcher we found. Play ${target} once in your launcher, or choose a client jar by hand. Until then, previews show placeholders.`}{' '}
          {targetStatus?.server
            ? 'Server data is cached, so block and item names are checked.'
            : 'Server data appears after the dev server first starts for this version.'}
          {!targetStatus?.client && !targetJar && installs !== null && (
            <Button
              size="small"
              variant="primary"
              onClick={async () => {
                setError(null)
                try {
                  await backend.openLauncher()
                } catch (e) {
                  setError(e instanceof Error ? e.message : String(e))
                }
              }}
            >
              Open launcher
            </Button>
          )}
          {!targetStatus?.client && !targetJar && installs !== null && (
            <Button size="small" icon="refresh" onClick={refresh}>
              Look again
            </Button>
          )}
          {!targetStatus?.client && targetJar && (
            <Button
              size="small"
              variant="primary"
              disabled={progress !== null}
              onClick={() => void importJar(target, targetJar.jar)}
            >
              Import {target}
            </Button>
          )}
        </Callout>
      )}

      {progress && (
        <div className={styles.progress} role="status" aria-label="Import progress">
          Importing {progress.version}…
          <progress value={progress.done} max={Math.max(1, progress.total)} />
        </div>
      )}
      {error && <FormError role="alert">{error}</FormError>}

      <h3>Installs</h3>
      {installs === null ? (
        <Hint>Looking for Minecraft installs…</Hint>
      ) : installs.length === 0 ? (
        <Hint>
          No Minecraft installs found (vanilla launcher, Prism, MultiMC, Modrinth App, CurseForge,
          ATLauncher).
        </Hint>
      ) : (
        <ul className={styles.installs} aria-label="Minecraft installs">
          {installs.map((install) => (
            <li key={install.path}>
              <div>
                <strong>{install.launcher}</strong>{' '}
                <code className={styles.installPath}>{install.path}</code>
              </div>
              {install.versions.length === 0 && <Hint>No client jars.</Hint>}
            </li>
          ))}
        </ul>
      )}

      <h3>Versions</h3>
      <table aria-label="Minecraft versions">
        <thead>
          <tr>
            <th>Version</th>
            <th>Client assets</th>
            <th>Server data</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {versions.map((version) => {
            const s = status[version]
            const jar = jarFor(version)
            return (
              <tr key={version} className={version === target ? styles.target : undefined}>
                <td>
                  {version}
                  {version === target && <Badge>target</Badge>}
                </td>
                <td>{s?.client ? 'Imported' : 'Not imported'}</td>
                <td>{s?.server ? 'Cached' : 'Not yet'}</td>
                <td>
                  {jar ? (
                    <Button
                      size="small"
                      aria-label={`Import ${version}`}
                      disabled={progress !== null}
                      onClick={() => void importJar(version, jar.jar)}
                    >
                      {s?.client ? 'Re-import' : 'Import'}
                    </Button>
                  ) : (
                    <Muted>Not installed</Muted>
                  )}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
      <Button size="small" icon="file" className={styles.after} onClick={() => void pickJar()}>
        Choose a client jar…
      </Button>
    </div>
  )
}

/**
 * Whether the open project is trusted (what its dev server, Lua language features, git packages
 * and agents' tools wait for), with the button that changes it: Untrust asks first.
 */
function ProjectTrust() {
  const { workspace } = useApp()
  const project = useWorkspace((s) => s.project)
  if (!project) return null
  return (
    <section aria-label="Project trust">
      <h3>Project trust</h3>
      <Hint>
        {project.trusted
          ? `You trust "${project.name}": its dev server, Lua language features, git packages and agents' tools may run.`
          : `You haven't trusted "${project.name}": it's in restricted mode.`}
      </Hint>
      {project.trusted ? (
        <Button onClick={() => void askToUntrust(workspace)}>Untrust project…</Button>
      ) : (
        <Button variant="primary" onClick={() => void askToTrust(workspace)}>
          Trust project…
        </Button>
      )}
    </section>
  )
}

function ServerSettings() {
  const { backend } = useApp()
  const [settings, setSettings] = useState<Settings | null>(null)
  const [saved, setSaved] = useState(false)
  // Why the JVM arguments were refused (only the allowlisted ones are accepted), shown by the field.
  const [jvmError, setJvmError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    backend.getSettings().then(setSettings, () => setSettings(null))
  }, [backend])
  if (!settings) return <Hint>Loading…</Hint>
  const update = (patch: Partial<Settings>) => {
    setSaved(false)
    setJvmError(null)
    setError(null)
    setSettings({ ...settings, ...patch })
  }
  return (
    <form
      className={styles.form}
      onSubmit={async (event) => {
        event.preventDefault()
        try {
          await backend.setSettings(settings)
          setSaved(true)
        } catch (e) {
          if (isBackendError(e, 'jvmArgument')) setJvmError(e.message)
          else setError(e instanceof Error ? e.message : String(e))
        }
      }}
    >
      <FormLabel htmlFor="server-memory">Memory (MB)</FormLabel>
      <input
        id="server-memory"
        type="number"
        min={512}
        step={256}
        value={settings.serverMemoryMb}
        onChange={(e) => update({ serverMemoryMb: Number(e.target.value) })}
      />
      <FormLabel htmlFor="server-port">Port</FormLabel>
      <input
        id="server-port"
        type="number"
        min={1}
        max={65535}
        value={settings.serverPort}
        onChange={(e) => update({ serverPort: Number(e.target.value) })}
      />
      <FormLabel htmlFor="server-jvm">Extra JVM arguments (one per line)</FormLabel>
      <textarea
        id="server-jvm"
        rows={3}
        value={settings.serverJvmArgs.join('\n')}
        onChange={(e) =>
          update({ serverJvmArgs: e.target.value.split('\n').filter((it) => it.trim()) })
        }
        aria-invalid={jvmError !== null}
        aria-describedby={jvmError ? 'server-jvm-error' : undefined}
      />
      {jvmError && (
        <FormError id="server-jvm-error" role="alert">
          {jvmError}
        </FormError>
      )}
      <FormLabel htmlFor="server-ffmpeg">ffmpeg, for converting videos for screens</FormLabel>
      <input
        id="server-ffmpeg"
        type="text"
        placeholder="Found on PATH"
        value={settings.ffmpegPath}
        onChange={(e) => update({ ffmpegPath: e.target.value })}
      />
      {error && <FormError role="alert">{error}</FormError>}
      <FormActions>
        {saved && <Muted>Saved. Applies the next time the server starts.</Muted>}
        <Button type="submit" variant="primary">
          Save settings
        </Button>
      </FormActions>
    </form>
  )
}

function UpdateSettings() {
  const { updates } = useApp()
  const version = useWorkspace((s) => s.appInfo?.version)
  const { available, checking, upToDate, installing, error } = useUpdates((s) => s)
  const install = useInstallUpdate()
  return (
    <div>
      <p>
        This is NetherForge <strong>{version ?? '?'}</strong>. NetherForge checks for a new release
        when it starts, and always asks before installing one.
      </p>
      {available ? (
        <Callout tone="ok" role="status" aria-label="Update status">
          <strong>NetherForge {available.version} is available.</strong>
          {available.notes && <pre className={styles.notes}>{available.notes}</pre>}
          <Button
            size="small"
            variant="primary"
            disabled={installing !== null}
            onClick={() => void install()}
          >
            Install and restart…
          </Button>
        </Callout>
      ) : upToDate ? (
        <p role="status" aria-label="Update status">
          You have the newest version.
        </p>
      ) : null}
      {error && <FormError role="alert">{error}</FormError>}
      <Button
        size="small"
        className={styles.after}
        disabled={checking}
        onClick={() => void updates.getState().check()}
      >
        {checking ? 'Checking…' : 'Check for updates'}
      </Button>
    </div>
  )
}

function AgentSettings() {
  const { backend, workspace } = useApp()
  const project = useWorkspace((s) => s.project)
  const files = useWorkspace((s) => s.files)
  const [added, setAdded] = useState<string[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [settings, setSettings] = useState<Settings | null>(null)
  const [status, setStatus] = useState<McpStatus | null>(null)
  useEffect(() => {
    backend.getSettings().then(setSettings, () => setSettings(null))
    backend.mcpStatus().then(setStatus, () => setStatus(null))
  }, [backend])
  const missing = [AGENT_FILES.agents, AGENT_FILES.claude, MCP_CONFIG_FILE].filter(
    (it) => !files.includes(it),
  )
  const port = settings?.mcpPort ?? status?.port ?? 0
  return (
    <div>
      <p>
        Coding agents (Claude Code, Codex, Cursor and others) can work on this project directly.
        Every time it opens a project, NetherForge writes what an agent needs for this version into{' '}
        <code>.netherforge/</code>: the file format, the Lua API reference and stubs, the guides,
        and a <code>netherforge check</code> command that validates the project like the Problems
        panel.
      </p>
      <p>
        <code>{AGENT_FILES.agents}</code> in the project root tells an agent where they are,{' '}
        <code>{AGENT_FILES.claude}</code> points Claude Code at the same file, and{' '}
        <code>{MCP_CONFIG_FILE}</code> connects Claude Code to the editor (below). The first two are
        your files: commit them and add your own notes. <code>{MCP_CONFIG_FILE}</code> holds this
        computer's token, so the project's <code>.gitignore</code> leaves it out; on another
        computer, add it again here.
      </p>
      {!project ? (
        <Hint>Open a project to set it up.</Hint>
      ) : missing.length === 0 ? (
        <p role="status" aria-label="Agent files">
          {added?.length ? `Added ${added.join(', ')}.` : 'This project is set up for agents.'}
        </p>
      ) : (
        <Button
          size="small"
          variant="primary"
          onClick={() => {
            setError(null)
            workspace
              .getState()
              .addAgentFiles()
              .then(setAdded, (e: unknown) => setError(e instanceof Error ? e.message : String(e)))
          }}
        >
          Add {missing.join(', ')}
        </Button>
      )}

      <h3>MCP server</h3>
      <p>
        While it's open, the editor serves tools to agents over MCP: the project's problems, hot
        reload onto the dev server, its console and script errors, server commands, spawning, and
        Minecraft id lookups. It listens on this computer only, and answers only requests that carry
        its token.
      </p>
      {status && (
        <Callout
          tone={status.url ? 'ok' : status.enabled ? 'warning' : undefined}
          role="status"
          aria-label="MCP server status"
        >
          {status.url ? (
            <>
              Listening at <code>{status.url}</code>
            </>
          ) : status.enabled ? (
            (status.error ?? 'Not listening.')
          ) : (
            'Off.'
          )}
        </Callout>
      )}
      {status?.url && (
        <Hint>
          Claude Code reads <code>{MCP_CONFIG_FILE}</code>, or run{' '}
          <code>{claudeAddCommand({ port, token: status.token })}</code>. Other agents: add an HTTP
          MCP server with that URL and the header <code>Authorization: Bearer {status.token}</code>.
        </Hint>
      )}
      {settings && (
        <form
          className={styles.form}
          onSubmit={async (event) => {
            event.preventDefault()
            setError(null)
            try {
              await backend.setSettings(settings)
              setStatus(await backend.mcpStatus())
            } catch (e) {
              setError(e instanceof Error ? e.message : String(e))
            }
          }}
        >
          <Check
            label="Serve tools to coding agents"
            checked={settings.mcpEnabled}
            onChange={(mcpEnabled) => setSettings({ ...settings, mcpEnabled })}
          />
          <FormLabel htmlFor="mcp-port">Port</FormLabel>
          <input
            id="mcp-port"
            type="number"
            min={1}
            max={65535}
            value={settings.mcpPort}
            onChange={(e) => setSettings({ ...settings, mcpPort: Number(e.target.value) })}
          />
          <FormActions>
            <Button type="submit" variant="primary">
              Apply
            </Button>
          </FormActions>
        </form>
      )}
      {error && <FormError role="alert">{error}</FormError>}
    </div>
  )
}
