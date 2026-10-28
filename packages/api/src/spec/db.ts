import { asyncFunction } from '../async.ts'
import type { LuaClass } from '../types.ts'

/** What every Database function says about the SQL it takes. */
const SQL_DOC =
  "One SQL statement in the database's own dialect (SQLite's for the package's own database; MySQL's or PostgreSQL's for a connection the server owner set up), with `?` where a value goes: the values come in `params`, never inside the text, so a name a player typed can't become SQL. A second statement after a `;` is an error: use `db:transaction` for several. On PostgreSQL a literal `?` (the JSON operators) is written `??`."

/** What `params` may hold. */
const PARAMS_DOC =
  "The values for the `?`s, in order: `{ player_name, 5 }`. Each is a `string`, `number`, `integer` or `boolean` (stored as 1 or 0); a hole in the list (`{ 1, nil, 3 }`) is SQL `NULL`, and so are the last `?`s when the list is shorter (Lua can't tell `{ name, nil }` from `{ name }`). Any other kind of value is an error, and more values than `?`s fails the statement."

/** A database: the package's own SQLite file, or a MySQL or PostgreSQL connection the server's owner set up. */
export const databaseClass: LuaClass = {
  name: 'Database',
  doc: "A database, from `nf.db()` or `nf.db('network')`. `nf.db()` is this package's own: one SQLite file per package, kept across restarts, which every other package (a dependency, or the project using one) never sees; its tables are made by the package's `migrations/NNN_name.sql` files, applied in order when the project loads. `nf.db('network')` (a name) is a connection to a MySQL or PostgreSQL server that the server's owner set up under `databases:` in the plugin's `config.yml`: shared by every package the owner lists for it, so give tables a name no other package would (`shop_scores`), create them yourself (`CREATE TABLE IF NOT EXISTS`: migrations belong to the package's own database only), and write the SQL in the server's dialect. The handle is the same either way: the same calls, `?` and `params`, rows and `value, err` answers. Every function is asynchronous (see `nf.task`): the work runs on a background thread, so a slow query never holds up the server, and one database runs its work in the order it was given, so a task's `db:execute` is always done before its next `db:query`. Use it from a task (`local rows, err = db:query(...)`) or give a callback. A mistake in the SQL, a constraint a row breaks or a database that couldn't be migrated is `nil, err`, never a thrown error.",
  methods: true,
  handle: {
    key: [
      { name: 'namespace', type: 'string' },
      { name: 'connection', type: 'string' },
    ],
  },
  fields: [],
  functions: [
    asyncFunction({
      name: 'query',
      doc: `Runs a statement that reads rows and gives them all. Each row is a table keyed by column name; a \`NULL\` column is left out of its row (so \`row.nickname\` is \`nil\`), integers come as \`integer\`s, reals as \`number\`s and text as \`string\`s; what a MySQL or PostgreSQL server has beyond those arrives as one of them (a boolean as \`1\` or \`0\`, a \`DECIMAL\` as a \`number\`, a date or time as its text, an integer too big for an \`integer\` as text; \`CAST(column AS CHAR)\` keeps a decimal exact). A column that holds a blob is an error: \`hex(column)\` it in the SQL (\`encode(column, 'hex')\` on PostgreSQL). ${SQL_DOC}`,
      params: [
        {
          name: 'sql',
          type: 'string',
          doc: 'The statement: `"SELECT name, score FROM scores WHERE score > ?"`.',
        },
        { name: 'params', type: 'table', doc: PARAMS_DOC, optional: true },
      ],
      value: {
        name: 'rows',
        type: 'table<string, any>[]',
        doc: 'the rows, in the order the statement gave them (empty when there are none)',
      },
      example:
        'local db = nf.db()\nnf.task(function()\n  local rows, err = db:query("SELECT name, score FROM scores ORDER BY score DESC LIMIT ?", { 10 })\n  if not rows then\n    log("no scores: " .. err)\n    return\n  end\n  for place, row in ipairs(rows) do\n    log(place, row.name, row.score)\n  end\nend)',
    }),
    asyncFunction({
      name: 'execute',
      doc: `Runs a statement that changes data (\`INSERT\`, \`UPDATE\`, \`DELETE\`) and says what it changed. It's committed on its own. ${SQL_DOC}`,
      params: [
        {
          name: 'sql',
          type: 'string',
          doc: 'The statement: `"INSERT INTO scores (name, score) VALUES (?, ?)"`.',
        },
        { name: 'params', type: 'table', doc: PARAMS_DOC, optional: true },
      ],
      value: {
        name: 'result',
        type: 'DatabaseResult',
        doc: 'how many rows it changed and the last row it inserted',
      },
      example:
        'nf.task(function()\n  local result, err = nf.db():execute("INSERT INTO scores (name, score) VALUES (?, ?)", { player:name(), 10 })\n  if not result then\n    log("not saved: " .. err)\n  end\nend)',
    }),
    asyncFunction({
      name: 'transaction',
      doc: 'Runs several statements as one: all of them happen, or, when one fails, none do (the ones before it are undone) and the error says which failed. Other work on the database waits until it is done, so nothing sees it half-way.',
      params: [
        {
          name: 'statements',
          type: 'DatabaseStatement[]',
          doc: 'The statements, run in order. Rows they would give are ignored: a transaction changes data.',
        },
      ],
      value: {
        name: 'results',
        type: 'DatabaseResult[]',
        doc: 'what each statement changed, in the same order',
      },
      example:
        'nf.task(function()\n  local results, err = nf.db():transaction({\n    { sql = "UPDATE accounts SET coins = coins - ? WHERE name = ?", params = { 50, "alex" } },\n    { sql = "UPDATE accounts SET coins = coins + ? WHERE name = ?", params = { 50, "sam" } },\n  })\n  if not results then\n    log("transfer failed: " .. err)\n  end\nend)',
    }),
  ],
}

/** The plain tables the database takes and gives. */
export const databaseShapes: LuaClass[] = [
  {
    name: 'DatabaseStatement',
    doc: 'One statement of `db:transaction`.',
    methods: false,
    functions: [],
    fields: [
      { name: 'sql', type: 'string', doc: SQL_DOC },
      { name: 'params', type: 'table?', doc: PARAMS_DOC },
    ],
  },
  {
    name: 'DatabaseResult',
    doc: 'What `db:execute` (and each statement of `db:transaction`) did.',
    methods: false,
    functions: [],
    fields: [
      { name: 'changes', type: 'integer', doc: 'How many rows it inserted, updated or deleted.' },
      {
        name: 'last_insert_id',
        type: 'integer?',
        doc: "On the package's own database, the `rowid` of the last row it inserted (the new row's `INTEGER PRIMARY KEY`), or `nil` when nothing was ever inserted. On a MySQL connection, the `AUTO_INCREMENT` key the statement generated, or `nil` when it generated none. On PostgreSQL always `nil`: ask for the key with `INSERT ... RETURNING id` through `db:query`.",
      },
    ],
  },
]
