/**
 * Project settings: `netherforge.json` as a form. The name, namespace and
 * version, the target Minecraft version (one the editor runs) and the format
 * (shown: there's one), `managedWorlds`, the existing server worlds scripts may unload
 * and delete, `worlds` (each world's terrain, seed, dimension type, game rules and how mobs
 * spawn there), `requires`, what the project's scripts need that a package must declare
 * (moderation, a database, HTTP hosts, other plugins), beside what the whole tree requires
 * (format's combined list, from the outline), `allow`, the permissions scripts may grant,
 * `dependencies` and `exports` (what this project uses of others, and what others may use of
 * it), `generate`, and `settings`, what the server's owner may change ([SettingsSection]). Edits
 * go through the store like any model document, so undo, canonical saving
 * and hot reload are the usual ones.
 */
import { useState } from 'react'
import {
  KINDS,
  HTTP_HOST_PATTERN,
  HTTP_HOST_RULE,
  NAMESPACE_RULE,
  PERMISSION_NODE_PATTERN,
  PERMISSION_NODE_RULE,
  PLUGIN_NAME_PATTERN,
  PLUGIN_NAME_RULE,
  SpawnCategoryValues,
  type SpawnCategory,
  WORLD_NAME_PATTERN,
  WORLD_NAME_RULE,
  type KindId,
  type ProjectManifest,
} from '@/core/format'
import { useApp, useWorkspace } from '@/state/providers'
import { ask } from '@/ui/dialogs'
import {
  Check,
  CheckField,
  FieldGroup,
  NumberInput,
  Row,
  Section,
  SelectField,
  TextField,
} from '@/ui/fields'
import { IconButton, Button } from '@/ui/Button'
import { Hint, Mono, Muted } from '@/ui/text'
import { useFocusRequests } from '@/editors/shared/useFocusRequests'
import { EditAsJson, useModelDoc } from '@/editors/shared/modelDoc'
import { RawDocView } from '@/editors/shared/RawDocView'
import { EditorBar, EditorScreen, Page } from '@/editors/shared/EditorLayout'
import styles from './ProjectEditor.module.css'
import { SettingsSection } from './SettingsSection'
import { askFolderDependency, askGitDependency } from './dependencyPrompts'
import {
  addManagedWorld,
  addPermission,
  addRequired,
  removeManagedWorld,
  removePermission,
  removeRequired,
  parseGameRule,
  removeDependency,
  removeWorldSpawn,
  setDependency,
  setExported,
  setGameRule,
  setGenerate,
  setRequired,
  setWorldSpawn,
  setWorldValue,
  type DependencySource,
  type RequiredFlag,
  type RequiredList,
  type SpawnField,
} from './ops'

const capitalised = (rule: string) => rule.charAt(0).toUpperCase() + rule.slice(1)
const WORLD_RULE = capitalised(WORLD_NAME_RULE)
const PERMISSION_RULE = capitalised(PERMISSION_NODE_RULE)
const HOST_RULE = capitalised(HTTP_HOST_RULE)
const PLUGIN_RULE = capitalised(PLUGIN_NAME_RULE)

/** One empty list, so a selector returns the same value while there's no outline yet. */
const NO_IDS: string[] = []
const NO_VERSIONS: string[] = []

export function ProjectEditor({ path }: { path: string }) {
  const { doc, model, edit } = useModelDoc<ProjectManifest>(path)
  useFocusRequests(path, (segments) => segments)
  if (!doc) return null
  if (!model) return <RawDocView path={path} />
  return (
    <EditorScreen aria-label="Project settings">
      <EditorBar title="Project settings">
        <EditAsJson path={path} />
      </EditorBar>
      <Page className={styles.page}>
        <Section title="Project" icon="gear">
          <TextField
            label="Name"
            dataPath="name"
            value={model.name}
            onChange={(name) =>
              edit((draft) => {
                draft.name = name
              })
            }
          />
          <TextField
            label="Namespace"
            dataPath="namespace"
            value={model.namespace}
            onChange={(namespace) =>
              edit((draft) => {
                draft.namespace = namespace
              })
            }
          />
          <Hint>
            Everything the project registers or saves on a server is named in it (a stack of the
            item <Mono>ruby</Mono> is <Mono>{model.namespace}:ruby</Mono>), so stacks and recipes a
            server already has keep the old one if it changes. {capitalised(NAMESPACE_RULE)}.
          </Hint>
          <TextField
            label="Version"
            dataPath="version"
            value={model.version}
            onChange={(version) =>
              edit((draft) => {
                draft.version = version
              })
            }
          />
          <TargetVersion
            current={model.minecraft}
            onChange={(minecraft) =>
              edit((draft) => {
                draft.minecraft = minecraft
              })
            }
          />
          <Row label="Format">
            <span>{model.formatVersion}</span>
          </Row>
        </Section>
        <ManagedWorlds
          worlds={model.managedWorlds ?? []}
          onAdd={(name) => edit((draft) => addManagedWorld(draft, name))}
          onRemove={(name) => edit((draft) => removeManagedWorld(draft, name))}
        />
        <WorldSpawning
          worlds={model.worlds}
          onSet={(world, field, category, value) =>
            edit((draft) => setWorldSpawn(draft, world, field, category, value))
          }
          onValue={(world, key, value) => edit((draft) => setWorldValue(draft, world, key, value))}
          onRule={(world, rule, value) => edit((draft) => setGameRule(draft, world, rule, value))}
          onRemove={(world) => edit((draft) => removeWorldSpawn(draft, world))}
        />
        <Requirements
          requires={model.requires}
          onFlag={(key, on) => edit((draft) => setRequired(draft, key, on))}
          onAdd={(key, name) => edit((draft) => addRequired(draft, key, name))}
          onRemove={(key, name) => edit((draft) => removeRequired(draft, key, name))}
        />
        <TreeRequirements />
        <Permissions
          allow={model.allow}
          onAdd={(node) => edit((draft) => addPermission(draft, node))}
          onRemove={(node) => edit((draft) => removePermission(draft, node))}
        />
        <Dependencies
          dependencies={model.dependencies}
          onSet={(namespace, source) => edit((draft) => setDependency(draft, namespace, source))}
          onRemove={(namespace) => edit((draft) => removeDependency(draft, namespace))}
        />
        <Exports
          exports={model.exports}
          onSet={(folder, id, on) => edit((draft) => setExported(draft, folder, id, on))}
        />
        <Section title="Generators" icon="mountain">
          <TextField
            label="Commands"
            dataPath="generate"
            multiline
            value={(model.generate ?? []).join('\n')}
            placeholder="node tools/items.mjs (one a line)"
            onChange={(text) => edit((draft) => setGenerate(draft, text))}
          />
          <Hint>
            What <Mono>netherforge generate</Mono> runs, in order from the project&apos;s folder, to
            write generated files; it stops at the first that fails, then formats the project. The
            server never runs them.
          </Hint>
        </Section>
        <SettingsSection settings={model.settings} edit={edit} />
      </Page>
    </EditorScreen>
  )
}

function ManagedWorlds({
  worlds,
  onAdd,
  onRemove,
}: {
  worlds: string[]
  onAdd: (name: string) => void
  onRemove: (name: string) => void
}) {
  return (
    <Section title="Managed worlds" icon="globe">
      <Hint>
        Worlds already on the server that this project&apos;s scripts may unload and delete. Worlds
        its scripts create are the project&apos;s anyway; the server&apos;s main world never is.
      </Hint>
      <NameList
        label="Managed worlds"
        dataPath="managedWorlds"
        names={worlds}
        pattern={WORLD_NAME_PATTERN}
        rule={WORLD_RULE}
        listed="That world is listed already"
        inputLabel="World name"
        placeholder="A world on the server"
        addLabel="Add world"
        onAdd={onAdd}
        onRemove={onRemove}
      />
    </Section>
  )
}

/**
 * `worlds`: how the game spawns mobs in a world: the most of each spawn category there may be
 * around each player, and the ticks between spawn attempts for it. A category left blank keeps
 * the server's own setting. A world that has none of either isn't in the file, so one just added
 * is only here (`added`) until a value is set.
 */
function WorldSpawning({
  worlds,
  onSet,
  onValue,
  onRule,
  onRemove,
}: {
  worlds: ProjectManifest['worlds']
  onSet: (
    world: string,
    field: SpawnField,
    category: SpawnCategory,
    value: number | undefined,
  ) => void
  onValue: (
    world: string,
    key: 'terrain' | 'seed' | 'dimensionType',
    value: string | number | undefined,
  ) => void
  onRule: (world: string, rule: string, value: boolean | number | undefined) => void
  onRemove: (world: string) => void
}) {
  const terrains = useWorkspace((s) => s.outline?.resources.terrain ?? NO_IDS)
  const dimensions = useWorkspace((s) => s.outline?.resources.dimension_type ?? NO_IDS)
  const choices = (ids: string[], current: string | undefined, none: string) => [
    { value: '', label: none },
    ...ids.map((it) => ({ value: it, label: it })),
    ...(current && !ids.includes(current)
      ? [{ value: current, label: `${current} (missing)` }]
      : []),
  ]
  const [added, setAdded] = useState<string[]>([])
  const [name, setName] = useState('')
  const saved = Object.keys(worlds ?? {})
  const names = [...saved, ...added.filter((it) => !saved.includes(it))].sort()
  const trimmed = name.trim()
  const error =
    trimmed === ''
      ? null
      : !WORLD_NAME_PATTERN.test(trimmed)
        ? WORLD_RULE
        : names.includes(trimmed)
          ? 'That world is listed already'
          : null
  const add = () => {
    if (trimmed === '' || error) return
    setAdded([...added, trimmed])
    setName('')
  }
  return (
    <Section title="Worlds" icon="globe">
      <Hint>
        Settings for a world on the server, by the world&apos;s name (it needn&apos;t be one the
        project manages). A world with a terrain or a dimension type the server doesn&apos;t have
        yet is made with them when the project loads. Mob spawning: the limit is the most mobs of a
        category around each player, the interval the ticks between spawn attempts; 0 stops the
        category spawning naturally. Blank keeps the server&apos;s own setting (
        <Mono>bukkit.yml</Mono>). Applied when the project loads and reloads, and whenever the world
        is loaded or created.
      </Hint>
      {names.length === 0 && <Muted>None.</Muted>}
      {names.map((world) => (
        <fieldset
          key={world}
          className={styles.setting}
          aria-label={`World ${world}`}
          data-path={`worlds.${world}`}
          tabIndex={-1}
        >
          <legend className={styles.settingName}>
            <Mono>{world}</Mono>
            <IconButton
              icon="trash"
              label={`Remove world ${world}`}
              onClick={() => {
                setAdded(added.filter((it) => it !== world))
                onRemove(world)
              }}
            />
          </legend>
          <SelectField
            label="Terrain"
            dataPath={`worlds.${world}.terrain`}
            value={worlds?.[world]?.terrain ?? ''}
            options={choices(terrains, worlds?.[world]?.terrain, "(the server's generator)")}
            onChange={(value) => onValue(world, 'terrain', value || undefined)}
          />
          <TextField
            label="Seed"
            dataPath={`worlds.${world}.seed`}
            value={worlds?.[world]?.seed === undefined ? '' : String(worlds[world]!.seed)}
            placeholder="random"
            onChange={(text) => {
              const seed = Number(text.trim())
              if (text.trim() === '') onValue(world, 'seed', undefined)
              else if (Number.isSafeInteger(seed)) onValue(world, 'seed', seed)
            }}
          />
          <SelectField
            label="Dimension type"
            dataPath={`worlds.${world}.dimensionType`}
            value={worlds?.[world]?.dimensionType ?? ''}
            options={choices(dimensions, worlds?.[world]?.dimensionType, "(the world's own)")}
            onChange={(value) => onValue(world, 'dimensionType', value || undefined)}
          />
          <GameRules
            world={world}
            rules={worlds?.[world]?.gameRules as Record<string, unknown> | undefined}
            onRule={(rule, value) => onRule(world, rule, value)}
          />
          <table className={styles.requirements}>
            <thead>
              <tr>
                <th scope="col">Category</th>
                <th scope="col">Limit</th>
                <th scope="col">Interval (ticks)</th>
              </tr>
            </thead>
            <tbody>
              {SpawnCategoryValues.map((category) => (
                <tr key={category}>
                  <th scope="row">
                    <Mono>{category}</Mono>
                  </th>
                  {(['spawnLimits', 'spawnIntervals'] as const).map((field) => (
                    <td key={field}>
                      <NumberInput
                        label={`${category} ${field === 'spawnLimits' ? 'limit' : 'interval'}`}
                        dataPath={`worlds.${world}.${field}.${category}`}
                        value={worlds?.[world]?.[field]?.[category]}
                        placeholder="Server's"
                        step={1}
                        min={0}
                        onChange={(value) => onSet(world, field, category, value)}
                      />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </fieldset>
      ))}
      <div className={styles.add}>
        <input
          aria-label="Spawn world name"
          placeholder="A world on the server"
          value={name}
          aria-invalid={error !== null}
          onChange={(event) => setName(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') add()
          }}
        />
        <Button size="small" icon="plus" disabled={trimmed === '' || error !== null} onClick={add}>
          Add world
        </Button>
      </div>
      {error && (
        <Hint tone="error" role="alert">
          {error}
        </Hint>
      )}
    </Section>
  )
}

/**
 * `requires`: what this project's scripts need that a package must declare. The runtime holds
 * each package to its own: a call its manifest doesn't declare is an error naming it.
 */
function Requirements({
  requires,
  onFlag,
  onAdd,
  onRemove,
}: {
  requires: ProjectManifest['requires']
  onFlag: (key: RequiredFlag, on: boolean) => void
  onAdd: (key: RequiredList, name: string) => void
  onRemove: (key: RequiredList, name: string) => void
}) {
  return (
    <Section title="What scripts need" icon="code">
      <Hint>
        What this project&apos;s scripts may do only when they&apos;ve asked for it here, so whoever
        runs the project sees in one place what it needs. A script that tries without it gets an
        error saying what to add.
      </Hint>
      <CheckField
        label="Moderation"
        dataPath="requires.moderation"
        value={requires?.moderation === true}
        onChange={(on) => onFlag('moderation', on)}
      />
      <Hint>
        Lets scripts ban and unban players, change the whitelist, and change the message of the day
        and the most players allowed.
      </Hint>
      <CheckField
        label="Database"
        dataPath="requires.db"
        value={requires?.db === true}
        onChange={(on) => onFlag('db', on)}
      />
      <Hint>Lets scripts keep a database of the project&apos;s own.</Hint>
      <CheckField
        label="Web routes"
        dataPath="requires.web"
        value={requires?.web === true}
        onChange={(on) => onFlag('web', on)}
      />
      <Hint>
        Lets scripts answer web requests to the server (webhooks, logins, small pages) under the
        project&apos;s own path, once the server owner has turned web routes on.
      </Hint>
      <CheckField
        label="Bots"
        dataPath="requires.bots"
        value={requires?.bots === true}
        onChange={(on) => onFlag('bots', on)}
      />
      <Hint>
        Lets scripts join fake players to the server and drive them (opponents, teammates, crowds),
        once the server owner allows bots. The dev server always does.
      </Hint>
      <CheckField
        label="WebAssembly"
        dataPath="requires.wasm"
        value={requires?.wasm === true}
        onChange={(on) => onFlag('wasm', on)}
      />
      <Hint>
        Lets scripts run the project&apos;s WebAssembly modules (<Mono>wasm/</Mono>): heavy
        computation off the main thread, within the server owner&apos;s limits.
      </Hint>
      <CheckField
        label="Network"
        dataPath="requires.network"
        value={requires?.network === true}
        onChange={(on) => onFlag('network', on)}
      />
      <Hint>
        Lets scripts talk to the other servers of the network the server is part of (messages,
        requests, shared maps, who&apos;s online where) and send players between them. The dev
        server is a network of its own.
      </Hint>
      <Hint>
        Hosts scripts may make HTTP requests to. <Mono>*.example.com</Mono> covers every host under{' '}
        <Mono>example.com</Mono>, not <Mono>example.com</Mono> itself.
      </Hint>
      <NameList
        label="HTTP hosts"
        dataPath="requires.http"
        names={requires?.http ?? []}
        pattern={HTTP_HOST_PATTERN}
        rule={HOST_RULE}
        listed="That host is listed already"
        inputLabel="Host"
        placeholder="A host, like discord.com or *.example.com"
        addLabel="Add host"
        onAdd={(name) => onAdd('http', name)}
        onRemove={(name) => onRemove('http', name)}
      />
      <Hint>Other plugins on the server that scripts may use, by name.</Hint>
      <NameList
        label="Plugins"
        dataPath="requires.plugins"
        names={requires?.plugins ?? []}
        pattern={PLUGIN_NAME_PATTERN}
        rule={PLUGIN_RULE}
        listed="That plugin is listed already"
        inputLabel="Plugin"
        placeholder="A plugin's name, like vault"
        addLabel="Add plugin"
        onAdd={(name) => onAdd('plugins', name)}
        onRemove={(name) => onRemove('plugins', name)}
      />
    </Section>
  )
}

/**
 * What the whole tree requires (the project and every package it depends on, theirs too), each
 * with who declares it: what running the project asks of a server, as format sums it.
 */
const NONE: never[] = []

function TreeRequirements() {
  const requirements = useWorkspace((s) => s.outline?.requirements ?? NONE)
  return (
    <Section title="What running it asks of a server" icon="server">
      <Hint>
        Everything the project and the packages it depends on need, as the server logs it and{' '}
        <Mono>/nf requires</Mono> lists it.
      </Hint>
      {requirements.length === 0 ? (
        <Muted>Nothing beyond what every project may do.</Muted>
      ) : (
        <table className={styles.requirements} aria-label="Requirements across the packages">
          <thead>
            <tr>
              <th scope="col">Requirement</th>
              <th scope="col">Declared by</th>
            </tr>
          </thead>
          <tbody>
            {requirements.map((use) => (
              <tr key={use.requirement}>
                <td>
                  <Mono>{use.requirement}</Mono>
                </td>
                <td>{use.packages.join(', ')}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Section>
  )
}

/** `allow`: the permissions the project's scripts may grant players. */
function Permissions({
  allow,
  onAdd,
  onRemove,
}: {
  allow: ProjectManifest['allow']
  onAdd: (node: string) => void
  onRemove: (node: string) => void
}) {
  return (
    <Section title="Grantable permissions" icon="shield">
      <Hint>
        Permissions scripts may grant or deny players. Each covers itself and every permission under
        it: <Mono>shop</Mono> covers <Mono>shop.vip</Mono> too.
      </Hint>
      <NameList
        label="Grantable permissions"
        dataPath="allow.permissions"
        names={allow?.permissions ?? []}
        pattern={PERMISSION_NODE_PATTERN}
        rule={PERMISSION_RULE}
        listed="That permission is listed already"
        inputLabel="Permission"
        placeholder="A permission, like shop or shop.vip"
        addLabel="Add permission"
        onAdd={onAdd}
        onRemove={onRemove}
      />
    </Section>
  )
}

/**
 * Names with a remove button each and a row that adds one, checked as it's
 * typed against format's pattern. Format's length limit isn't a generated
 * constant, so a name that's too long is left to validation, whose problem
 * points at its row.
 */
function NameList({
  label,
  dataPath,
  names,
  pattern,
  rule,
  listed,
  inputLabel,
  placeholder,
  addLabel,
  onAdd,
  onRemove,
}: {
  label: string
  dataPath: string
  names: string[]
  pattern: RegExp
  rule: string
  listed: string
  inputLabel: string
  placeholder: string
  addLabel: string
  onAdd: (name: string) => void
  onRemove: (name: string) => void
}) {
  const [name, setName] = useState('')
  const trimmed = name.trim()
  const error =
    trimmed === '' ? null : !pattern.test(trimmed) ? rule : names.includes(trimmed) ? listed : null
  const add = () => {
    if (trimmed === '' || error) return
    onAdd(trimmed)
    setName('')
  }
  return (
    <>
      <ul className={styles.list} aria-label={label}>
        {names.map((it, index) => (
          <li key={it} data-path={`${dataPath}[${index}]`} tabIndex={-1}>
            <span>{it}</span>
            <IconButton icon="trash" label={`Remove ${it}`} onClick={() => onRemove(it)} />
          </li>
        ))}
        {names.length === 0 && (
          <li>
            <Muted>None.</Muted>
          </li>
        )}
      </ul>
      <div className={styles.add}>
        <input
          aria-label={inputLabel}
          placeholder={placeholder}
          value={name}
          aria-invalid={error !== null}
          onChange={(event) => setName(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') add()
          }}
        />
        <Button size="small" icon="plus" disabled={trimmed === '' || error !== null} onClick={add}>
          {addLabel}
        </Button>
      </div>
      {error && (
        <Hint tone="error" role="alert">
          {error}
        </Hint>
      )}
    </>
  )
}

/** `minecraft`: the version the project targets, from those this editor runs a server for. */
function TargetVersion({
  current,
  onChange,
}: {
  current: string
  onChange: (version: string) => void
}) {
  const versions = useWorkspace((s) => s.appInfo?.minecraftVersions ?? NO_VERSIONS)
  const options = versions.includes(current) ? versions : [current, ...versions]
  return (
    <>
      <SelectField
        label="Minecraft"
        dataPath="minecraft"
        value={current}
        options={options.map((it) => ({ value: it, label: it }))}
        onChange={onChange}
      />
      <Hint>
        The server the editor runs and the game data validation checks against. Import this
        version&apos;s client (Settings › Minecraft) for its pictures, and restart the dev server to
        run it.
      </Hint>
    </>
  )
}

/** A world's `gameRules`: each `true`, `false` or a whole number, by the game's id. */
function GameRules({
  world,
  rules,
  onRule,
}: {
  world: string
  rules: Record<string, unknown> | undefined
  onRule: (rule: string, value: boolean | number | undefined) => void
}) {
  const entries = Object.entries(rules ?? {})
  return (
    <FieldGroup header="Game rules" data-path={`worlds.${world}.gameRules`}>
      {entries.map(([rule, value]) => (
        <Row key={rule} label={<Mono>{rule}</Mono>}>
          <input
            aria-label={`${world} ${rule}`}
            data-path={`worlds.${world}.gameRules.${rule}`}
            defaultValue={String(value)}
            key={String(value)}
            onBlur={(event) => {
              const parsed = parseGameRule(event.target.value)
              if (parsed === null) event.target.value = String(value)
              else if (parsed !== value) onRule(rule, parsed)
            }}
            onKeyDown={(event) => {
              if (event.key === 'Enter') event.currentTarget.blur()
            }}
          />
          <IconButton
            icon="trash"
            label={`Remove ${world} ${rule}`}
            onClick={() => onRule(rule, undefined)}
          />
        </Row>
      ))}
      <Button
        size="small"
        icon="plus"
        onClick={async () => {
          const rule = await ask.prompt({
            title: `New game rule for ${world}`,
            label: 'Rule (the game’s id)',
            initial: '',
            confirmLabel: 'Add',
            validate: (value) =>
              !/^([a-z0-9_.-]+:)?[a-zA-Z0-9_.-]+$/.test(value)
                ? 'A game rule id: keep_inventory, minecraft:random_tick_speed'
                : rules?.[value] !== undefined
                  ? 'That rule is set'
                  : null,
          })
          if (!rule) return
          const value = await ask.prompt({
            title: `${rule} in ${world}`,
            label: 'Value: true, false or a whole number',
            initial: 'true',
            confirmLabel: 'Set',
            validate: (text) =>
              parseGameRule(text) === null ? 'true, false or a whole number' : null,
          })
          const parsed = value === null ? null : parseGameRule(value)
          if (parsed !== null) onRule(rule, parsed)
        }}
      >
        Add game rule
      </Button>
    </FieldGroup>
  )
}

/** `dependencies`: the packages the project uses, by namespace: a folder beside it, or a git repository. */
function Dependencies({
  dependencies,
  onSet,
  onRemove,
}: {
  dependencies: ProjectManifest['dependencies']
  onSet: (namespace: string, source: DependencySource) => void
  onRemove: (namespace: string) => void
}) {
  const { backend, workspace } = useApp()
  const entries = Object.entries(dependencies ?? {})
  const add = async (kind: 'path' | 'git') => {
    const choice =
      kind === 'path'
        ? await askFolderDependency(backend, workspace)
        : await askGitDependency(workspace)
    if (choice) onSet(choice.namespace, choice.source)
  }
  return (
    <Section title="Dependencies" icon="box">
      <Hint>
        Packages this project uses: their exported resources are named <Mono>ns:id</Mono>. A git
        dependency is fetched at its rev (a branch, tag or commit; the default branch without one)
        and pinned in <Mono>netherforge.lock</Mono>.
      </Hint>
      {entries.length === 0 && <Muted>None.</Muted>}
      {entries.map(([name, dependency]) => (
        <fieldset
          key={name}
          className={styles.setting}
          aria-label={`Dependency ${name}`}
          data-path={`dependencies.${name}`}
          tabIndex={-1}
        >
          <legend className={styles.settingName}>
            <Mono>{name}</Mono>
            <IconButton
              icon="trash"
              label={`Remove dependency ${name}`}
              onClick={() => onRemove(name)}
            />
          </legend>
          {dependency.git !== undefined ? (
            <>
              <TextField
                label="Repository"
                dataPath={`dependencies.${name}.git`}
                value={dependency.git}
                onChange={(git) =>
                  git.trim() &&
                  onSet(name, {
                    git: git.trim(),
                    ...(dependency.rev ? { rev: dependency.rev } : {}),
                  })
                }
              />
              <TextField
                label="Rev"
                dataPath={`dependencies.${name}.rev`}
                value={dependency.rev}
                placeholder="the default branch"
                onChange={(rev) =>
                  onSet(name, { git: dependency.git!, ...(rev.trim() ? { rev: rev.trim() } : {}) })
                }
              />
            </>
          ) : (
            <TextField
              label="Folder"
              dataPath={`dependencies.${name}.path`}
              value={dependency.path}
              onChange={(path) => path.trim() && onSet(name, { path: path.trim() })}
            />
          )}
        </fieldset>
      ))}
      <div className={styles.add}>
        <Button size="small" icon="plus" onClick={() => void add('path')}>
          Add folder dependency…
        </Button>
        <Button size="small" icon="plus" onClick={() => void add('git')}>
          Add git dependency…
        </Button>
      </div>
    </Section>
  )
}

/** `exports`: what projects that depend on this one may use of it, ticked per resource. */
function Exports({
  exports,
  onSet,
}: {
  exports: ProjectManifest['exports']
  onSet: (folder: string, id: string, on: boolean) => void
}) {
  const resources = useWorkspace((s) => s.outline?.resources)
  const kinds = (Object.keys(KINDS) as KindId[]).filter(
    (kind) => KINDS[kind].exportable && (resources?.[kind]?.length ?? 0) > 0,
  )
  return (
    <Section title="Exports" icon="share">
      <Hint>
        When another project depends on this one, it may use only what&apos;s ticked here, by{' '}
        <Mono>ns:id</Mono>. Everything else stays this project&apos;s own.
      </Hint>
      {kinds.length === 0 && <Muted>No resources yet.</Muted>}
      {kinds.map((kind) => {
        const folder = KINDS[kind].folder
        const listed = exports?.[folder] ?? []
        return (
          <FieldGroup key={kind} header={<Mono>{folder}</Mono>} data-path={`exports.${folder}`}>
            {resources![kind]!.map((id) => (
              <Check
                key={id}
                label={id}
                checked={listed.includes(id)}
                onChange={(on) => onSet(folder, id, on)}
              />
            ))}
          </FieldGroup>
        )
      })}
    </Section>
  )
}
