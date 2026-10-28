import type { LuaClass } from '../types.ts'

export const fileClass: LuaClass = {
  name: 'File',
  doc: "A path in the scripts' data directory (`plugins/NetherForge/data/`, on the editor's dev server too). The only state a script has that survives a restart. Files are capped at 1 MiB; writes past it are refused, not truncated.",
  methods: true,
  handle: { key: [{ name: 'path', type: 'string' }] },
  fields: [],
  functions: [
    {
      name: 'path',
      doc: 'The path from the data directory; `""` for the directory itself.',
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'name',
      doc: 'The last part of the path.',
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'parent',
      doc: "The folder it's in, or `nil` for the data directory itself.",
      params: [],
      returns: [{ type: 'File?' }],
    },
    {
      name: 'child',
      doc: "Something inside this folder, whether or not it exists yet. `nil` for a name that isn't allowed.",
      params: [{ name: 'name', type: 'string', doc: 'One name, no `/`.' }],
      returns: [{ type: 'File?' }],
    },
    {
      name: 'exists',
      doc: 'Whether anything is there.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'is_folder',
      doc: "Whether it's a folder.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'size',
      doc: "Its size in bytes, or `nil` when it isn't a file.",
      params: [],
      returns: [{ type: 'integer?' }],
    },
    {
      name: 'children',
      doc: "What's directly inside, sorted by name; empty for a file or nothing.",
      params: [],
      returns: [{ type: 'File[]' }],
    },
    {
      name: 'read',
      doc: "The contents, or `nil` when there's no file.",
      params: [],
      returns: [{ type: 'string?' }],
    },
    {
      name: 'write',
      doc: 'Replaces the contents, creating folders above it as needed.',
      params: [{ name: 'text', type: 'string', doc: '' }],
      returns: [
        { type: 'boolean', doc: '`false` for a folder, past the size cap, or on a disk error.' },
      ],
    },
    {
      name: 'append',
      doc: 'Adds to the end.',
      params: [{ name: 'text', type: 'string', doc: '' }],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'read_json',
      doc: "The contents parsed as JSON, as `nf.json.decode` does, or `nil` when there's no file or it isn't JSON.",
      params: [],
      returns: [{ type: 'any' }],
    },
    {
      name: 'write_json',
      doc: 'Writes a value as JSON, as `nf.json.encode` does.',
      params: [{ name: 'value', type: 'any', doc: '' }],
      returns: [
        {
          type: 'boolean',
          doc: "`false` for something JSON can't hold (a function, a table inside itself) or a failed write.",
        },
      ],
    },
    {
      name: 'modified_time',
      doc: "When it last changed, as Unix time in milliseconds (like `nf.server.unix_time()`), or `nil` when there's nothing there.",
      params: [],
      returns: [{ type: 'integer?' }],
    },
    {
      name: 'lines',
      impl: 'lua',
      doc: "An iterator over its lines, for a `for` loop: each without its line ending (`\\n` or `\\r\\n`). Nothing for a file that isn't there.",
      params: [],
      returns: [{ type: 'fun(): string?' }],
      example: 'for line in nf.files.get("motd.txt"):lines() do\n  player:send_message(line)\nend',
    },
    {
      name: 'rename',
      doc: 'Moves it (a file, or a folder and everything in it) to another path in the data directory, creating folders above it as needed. Nothing already there is overwritten.',
      params: [
        {
          name: 'path',
          type: 'string',
          doc: 'From the data directory, as `nf.files.get` takes it.',
        },
      ],
      returns: [
        {
          type: 'File?',
          doc: "It at its new path; `nil` when there's nothing to move, the path isn't allowed or is taken, or the move failed.",
        },
      ],
      example: 'assert(nf.files.get("scores.json")):rename("backups/scores-1.json")',
    },
    {
      name: 'copy_to',
      doc: "Copies a file to another path in the data directory, creating folders above it as needed. Nothing already there is overwritten. Folders can't be copied.",
      params: [
        {
          name: 'path',
          type: 'string',
          doc: 'From the data directory, as `nf.files.get` takes it.',
        },
      ],
      returns: [
        {
          type: 'File?',
          doc: "The copy; `nil` when it isn't a file, the path isn't allowed or is taken, or the copy failed.",
        },
      ],
    },
    {
      name: 'create_folder',
      doc: 'Creates the folder, and any above it.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'delete',
      doc: 'Deletes a file or an empty folder.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
  ],
}

export const subscriptionClass: LuaClass = {
  name: 'Subscription',
  doc: "What `:on` and `:once` return: one handler listening for one event on one handle (or on `nf`). A handler lives as long as **both** the script that registered it and the handle it's on: a module's handler on a centity's node goes when the module unloads or the centity is removed, whichever comes first; one on a menu window goes with the window. A handler that errors 20 times in a row is cancelled.",
  methods: true,
  handle: { key: [{ name: 'id', type: 'integer' }] },
  fields: [],
  functions: [
    {
      name: 'cancel',
      impl: 'lua',
      doc: 'Stops the handler being called. Cancelling one already cancelled does nothing.',
      params: [],
      returns: [],
    },
    {
      name: 'is_active',
      impl: 'lua',
      doc: "Whether the handler is still listening: `false` once it's cancelled (by `cancel()`, after a `once` handler ran, or after 20 errors in a row), its script has unloaded, or the handle it's on has gone.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
  ],
}

export const taskClass: LuaClass = {
  name: 'Task',
  doc: 'What `nf.task`, `nf.after` and `nf.every` return: a task that may be waiting, or a callback waiting to run.',
  methods: true,
  handle: { key: [{ name: 'id', type: 'integer' }] },
  fields: [],
  functions: [
    {
      name: 'cancel',
      impl: 'lua',
      doc: "Ends it: a task stops where it's waiting and never carries on; a callback doesn't run (again). A task that cancels itself stops at its next wait. Cancelling one that has ended does nothing.",
      params: [],
      returns: [],
    },
    {
      name: 'is_active',
      impl: 'lua',
      doc: "Whether it will still run: `false` once it's cancelled, once a task has finished, errored or lost the handle it was waiting on, once an `nf.after` callback has run, after an `nf.every` callback's 20th error in a row, or once its script has unloaded.",
      params: [],
      returns: [{ type: 'boolean' }],
    },
  ],
}

export const senderClass: LuaClass = {
  name: 'Sender',
  doc: 'Whoever ran a command: a player or the console. What `event.sender` is in a command handler. For a player, `event.player` is the same person as a `Player`.',
  methods: true,
  handle: { key: [{ name: 'id', type: 'string' }] },
  fields: [],
  functions: [
    {
      name: 'name',
      doc: 'Their name: a player\'s name, or `"CONSOLE"`.',
      params: [],
      returns: [{ type: 'string' }],
    },
    {
      name: 'send_message',
      doc: 'Sends them a line: a chat line for a player, a log line for the console.',
      params: [{ name: 'text', type: 'Text', doc: 'MiniMessage, like `"<green>Done!"`.' }],
      returns: [{ type: 'boolean', doc: '`false` for a player who has gone offline.' }],
    },
    {
      name: 'has_permission',
      doc: 'Whether they have a permission node. The console has every one.',
      params: [{ name: 'permission', type: 'string', doc: '' }],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'is_player',
      doc: 'Whether a player ran it.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
    {
      name: 'is_console',
      doc: 'Whether the console ran it.',
      params: [],
      returns: [{ type: 'boolean' }],
    },
  ],
}
